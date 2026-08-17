package com.chestlogger.paper;

import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.TransactionLogEntry;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Objects;

/**
 * Interactive 54-slot Bukkit Inventory GUI displaying container transaction logs.
 */
public final class PaperChestHistoryView implements InventoryHolder {

    private final Player player;
    private final List<TransactionLogEntry> allEntries;
    private final String containerType;
    private final int containerCapacity;
    private final long packedPos;
    private final String dimension;

    private int currentPage;
    private int totalPages;
    private Inventory inventory;

    public PaperChestHistoryView(
            Player player,
            List<TransactionLogEntry> allEntries,
            String containerType,
            int containerCapacity,
            long packedPos,
            String dimension
    ) {
        this.player = Objects.requireNonNull(player, "player cannot be null");
        this.allEntries = (allEntries != null) ? allEntries : List.of();
        this.containerType = containerType != null ? containerType : "Container";
        this.containerCapacity = containerCapacity > 0 ? containerCapacity : 27;
        this.packedPos = packedPos;
        this.dimension = dimension != null ? dimension : "minecraft:overworld";

        this.currentPage = 1;
        this.totalPages = PaperHistoryGuiModel.calculateTotalPages(this.allEntries.size());
    }

    public void open() {
        int[] coords = BlockPosUtil.unpack(packedPos);
        String title = "§8ChestLog: §6" + coords[0] + "," + coords[1] + "," + coords[2];
        if (title.length() > 32) {
            title = "§8ChestLogger History";
        }

        this.inventory = Bukkit.createInventory(this, PaperHistoryGuiModel.GUI_SIZE, title);
        render();
        player.openInventory(this.inventory);
    }

    public void render() {
        if (this.inventory == null) return;
        this.inventory.clear();

        List<TransactionLogEntry> pageItems = PaperHistoryGuiModel.getPageItems(allEntries, currentPage, PaperHistoryGuiModel.PAGE_SIZE);

        for (int i = 0; i < pageItems.size(); i++) {
            TransactionLogEntry entry = pageItems.get(i);
            ItemStack item = PaperHistoryGuiModel.createEntryItem(entry, containerCapacity);
            inventory.setItem(i, item);
        }

        // Fill bottom control bar
        ItemStack filler = PaperHistoryGuiModel.createControlItem(Material.GRAY_STAINED_GLASS_PANE, "§7", List.of());
        for (int slot = 45; slot < 54; slot++) {
            inventory.setItem(slot, filler);
        }

        // Previous Page Button
        if (currentPage > 1) {
            inventory.setItem(
                    PaperHistoryGuiModel.SLOT_PREV_PAGE,
                    PaperHistoryGuiModel.createControlItem(Material.ARROW, "§a◀ Previous Page", List.of("§7Switch to page " + (currentPage - 1)))
            );
        } else {
            inventory.setItem(
                    PaperHistoryGuiModel.SLOT_PREV_PAGE,
                    PaperHistoryGuiModel.createControlItem(Material.RED_STAINED_GLASS_PANE, "§8◀ No Previous Page", List.of("§7You are on the first page."))
            );
        }

        // Info Book
        int[] coords = BlockPosUtil.unpack(packedPos);
        inventory.setItem(
                PaperHistoryGuiModel.SLOT_INFO,
                PaperHistoryGuiModel.createControlItem(
                        Material.BOOK,
                        "§e§lContainer Information",
                        List.of(
                                "§7Type: §f" + containerType + (containerCapacity == 54 ? " (Double Chest)" : ""),
                                "§7Position: §b" + coords[0] + ", " + coords[1] + ", " + coords[2],
                                "§7Dimension: §f" + dimension,
                                "§7Total Transactions: §a" + allEntries.size(),
                                "§7Page: §e" + currentPage + "§7 / §e" + totalPages
                        )
                )
        );

        // Close / Exit
        inventory.setItem(
                PaperHistoryGuiModel.SLOT_CLOSE,
                PaperHistoryGuiModel.createControlItem(Material.BARRIER, "§c✖ Close Menu", List.of("§7Click to exit inspection."))
        );

        // Rollback Shortcut Button
        inventory.setItem(
                PaperHistoryGuiModel.SLOT_ROLLBACK,
                PaperHistoryGuiModel.createControlItem(
                        Material.CLOCK,
                        "§6⚡ Rollback (1 Hour)",
                        List.of(
                                "§7Click to execute rollback preview",
                                "§7Reverts last 1 hour of modifications."
                        )
                )
        );

        // Next Page Button
        if (currentPage < totalPages) {
            inventory.setItem(
                    PaperHistoryGuiModel.SLOT_NEXT_PAGE,
                    PaperHistoryGuiModel.createControlItem(Material.ARROW, "§aNext Page ▶", List.of("§7Switch to page " + (currentPage + 1)))
            );
        } else {
            inventory.setItem(
                    PaperHistoryGuiModel.SLOT_NEXT_PAGE,
                    PaperHistoryGuiModel.createControlItem(Material.RED_STAINED_GLASS_PANE, "§8No Next Page ▶", List.of("§7You are on the last page."))
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

    public void handleRollbackClick() {
        player.closeInventory();
        int[] coords = BlockPosUtil.unpack(packedPos);
        player.sendMessage(ChatColor.GOLD + "[ChestLogger] Executing 1-hour rollback for container at (" + coords[0] + ", " + coords[1] + ", " + coords[2] + ")...");
        player.performCommand("chestlog rollback " + coords[0] + " " + coords[1] + " " + coords[2] + " 3600");
    }

    public Player getPlayer() {
        return player;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public long getPackedPos() {
        return packedPos;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
