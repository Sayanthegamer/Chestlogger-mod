package com.chestlogger.paper;

import com.chestlogger.provenance.ProvenanceGraph;
import com.chestlogger.provenance.ProvenanceNode;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Objects;

/**
 * Interactive 54-slot Bukkit Inventory GUI displaying the sequential life journey of an item.
 */
public final class PaperProvenanceGuiView implements InventoryHolder {

    private final Player player;
    private final ProvenanceGraph graph;

    private int currentPage;
    private int totalPages;
    private Inventory inventory;

    public PaperProvenanceGuiView(Player player, ProvenanceGraph graph) {
        this.player = Objects.requireNonNull(player, "player cannot be null");
        this.graph = (graph != null) ? graph : ProvenanceGraph.empty("minecraft:air", 0L);

        this.currentPage = 1;
        this.totalPages = PaperProvenanceGuiModel.calculateTotalPages(this.graph.totalSteps());
    }

    public void open() {
        String cleanName = graph.targetItemId();
        if (cleanName.startsWith("minecraft:")) {
            cleanName = cleanName.substring("minecraft:".length());
        }
        String title = "§8Trace: §6" + cleanName;
        if (title.length() > 32) {
            title = "§8Item Provenance Trace";
        }

        this.inventory = Bukkit.createInventory(this, PaperProvenanceGuiModel.GUI_SIZE, title);
        render();
        player.openInventory(this.inventory);
    }

    public void render() {
        if (this.inventory == null) return;
        this.inventory.clear();

        List<ProvenanceNode> pageNodes = PaperProvenanceGuiModel.getPageItems(graph.nodes(), currentPage, PaperProvenanceGuiModel.PAGE_SIZE);

        for (int i = 0; i < pageNodes.size(); i++) {
            ProvenanceNode node = pageNodes.get(i);
            ItemStack item = PaperProvenanceGuiModel.createNodeItem(node);
            inventory.setItem(i, item);
        }

        // Fill bottom control bar with glass pane
        ItemStack filler = PaperProvenanceGuiModel.createControlItem(Material.GRAY_STAINED_GLASS_PANE, "§7", List.of());
        for (int slot = 45; slot < 54; slot++) {
            inventory.setItem(slot, filler);
        }

        // Previous Page Button
        if (currentPage > 1) {
            inventory.setItem(
                    PaperProvenanceGuiModel.SLOT_PREV_PAGE,
                    PaperProvenanceGuiModel.createControlItem(Material.ARROW, "§a◀ Previous Page", List.of("§7Switch to page " + (currentPage - 1)))
            );
        } else {
            inventory.setItem(
                    PaperProvenanceGuiModel.SLOT_PREV_PAGE,
                    PaperProvenanceGuiModel.createControlItem(Material.RED_STAINED_GLASS_PANE, "§8◀ No Previous Page", List.of("§7You are on the first page."))
            );
        }

        // Summary Book
        inventory.setItem(
                PaperProvenanceGuiModel.SLOT_SUMMARY,
                PaperProvenanceGuiModel.createSummaryItem(graph)
        );

        // Close / Exit Button
        inventory.setItem(
                PaperProvenanceGuiModel.SLOT_CLOSE,
                PaperProvenanceGuiModel.createControlItem(Material.BARRIER, "§c✖ Close Menu", List.of("§7Click to exit provenance view."))
        );

        // Next Page Button
        if (currentPage < totalPages) {
            inventory.setItem(
                    PaperProvenanceGuiModel.SLOT_NEXT_PAGE,
                    PaperProvenanceGuiModel.createControlItem(Material.ARROW, "§aNext Page ▶", List.of("§7Switch to page " + (currentPage + 1)))
            );
        } else {
            inventory.setItem(
                    PaperProvenanceGuiModel.SLOT_NEXT_PAGE,
                    PaperProvenanceGuiModel.createControlItem(Material.RED_STAINED_GLASS_PANE, "§8No Next Page ▶", List.of("§7You are on the last page."))
            );
        }
    }

    public void previousPage() {
        if (currentPage > 1) {
            currentPage--;
            render();
            try {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            } catch (Throwable ignored) {}
        }
    }

    public void nextPage() {
        if (currentPage < totalPages) {
            currentPage++;
            render();
            try {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            } catch (Throwable ignored) {}
        }
    }

    public Player getPlayer() {
        return player;
    }

    public ProvenanceGraph getGraph() {
        return graph;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public int getTotalPages() {
        return totalPages;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
