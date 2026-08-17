package com.chestlogger.paper;

import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.provenance.ConfidenceLevel;
import com.chestlogger.provenance.ProvenanceGraph;
import com.chestlogger.provenance.ProvenanceNode;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Pure model & item builder for the Paper 54-slot item provenance & chain-of-custody GUI.
 */
public final class PaperProvenanceGuiModel {

    public static final int GUI_SIZE = 54;
    public static final int PAGE_SIZE = 45;

    public static final int SLOT_PREV_PAGE = 45;
    public static final int SLOT_SUMMARY = 48;
    public static final int SLOT_CLOSE = 49;
    public static final int SLOT_NEXT_PAGE = 53;

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private PaperProvenanceGuiModel() {}

    public static int calculateTotalPages(int totalItems) {
        if (totalItems <= 0) return 1;
        return (int) Math.ceil((double) totalItems / PAGE_SIZE);
    }

    public static List<ProvenanceNode> getPageItems(List<ProvenanceNode> allItems, int page, int pageSize) {
        if (allItems == null || allItems.isEmpty()) return List.of();
        int safePage = Math.max(1, page);
        int fromIndex = (safePage - 1) * pageSize;
        if (fromIndex >= allItems.size()) return List.of();
        int toIndex = Math.min(fromIndex + pageSize, allItems.size());
        return allItems.subList(fromIndex, toIndex);
    }

    public static Material resolveMaterial(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return Material.BARREL;
        }
        String clean = itemId.toLowerCase(Locale.ROOT);
        if (clean.startsWith("minecraft:")) {
            clean = clean.substring("minecraft:".length());
        }
        try {
            Material match = Material.matchMaterial(clean.toUpperCase(Locale.ROOT));
            if (match != null && match != Material.AIR) {
                return match;
            }
        } catch (Exception ignored) {}
        return Material.BARREL;
    }

    public static String formatConfidenceBadge(ConfidenceLevel confidence) {
        if (confidence == null) {
            return "§7[UNKNOWN]";
        }
        return switch (confidence) {
            case EXACT_LINKAGE -> "§a[EXACT_LINKAGE]";
            case HIGH_CONFIDENCE -> "§e[HIGH_CONFIDENCE]";
            case PROBABLE -> "§6[PROBABLE]";
        };
    }

    public static String formatTimestamp(long timestampMs) {
        synchronized (DATE_FORMAT) {
            return DATE_FORMAT.format(new Date(timestampMs));
        }
    }

    public static List<String> buildNodeLore(ProvenanceNode node) {
        List<String> lore = new ArrayList<>();
        lore.add("§7Step: §e#" + (node.stepIndex() + 1));
        lore.add("§7Action: §f" + node.actionType().name());
        lore.add("§7Confidence: " + formatConfidenceBadge(node.confidence()));
        lore.add("§7Actor: §e" + (node.actorName() != null ? node.actorName() : node.actorType().name()));

        boolean isAddition = node.deltaQuantity() > 0;
        String deltaStr = (isAddition ? "§a+" : "§c") + node.deltaQuantity();
        lore.add("§7Delta: " + deltaStr + " §7" + node.itemId());

        lore.add("§7Time: §f" + formatTimestamp(node.timestampMs()));

        int[] coords = BlockPosUtil.unpack(node.packedPos());
        lore.add("§7Pos: §b" + coords[0] + ", " + coords[1] + ", " + coords[2] + " (" + node.dimension() + ")");

        if (node.notes() != null && !node.notes().isBlank()) {
            lore.add("§8" + node.notes());
        }
        return lore;
    }

    public static ItemStack createNodeItem(ProvenanceNode node) {
        Material mat = resolveMaterial(node.itemId());
        int qty = Math.max(1, Math.min(64, Math.abs(node.deltaQuantity())));
        ItemStack item = new ItemStack(mat, qty);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            boolean isAddition = node.deltaQuantity() > 0;
            String prefix = isAddition ? "§a+ " : "§c- ";
            String cleanName = mat.name().replace('_', ' ').toLowerCase(Locale.ROOT);
            meta.setDisplayName("§eStep #" + (node.stepIndex() + 1) + ": " + prefix + "§f" + Math.abs(node.deltaQuantity()) + "x §e" + cleanName);
            meta.setLore(buildNodeLore(node));
            item.setItemMeta(meta);
        }
        return item;
    }

    public static List<String> buildSummaryLore(ProvenanceGraph graph) {
        List<String> lore = new ArrayList<>();
        lore.add("§7Target Item: §f" + graph.targetItemId());
        lore.add("§7Total Steps: §a" + graph.totalSteps());
        lore.add("§7Overall Confidence: " + formatConfidenceBadge(graph.overallConfidence()));
        if (graph.targetPackedPos() != 0L) {
            int[] coords = BlockPosUtil.unpack(graph.targetPackedPos());
            lore.add("§7Target Pos: §b" + coords[0] + ", " + coords[1] + ", " + coords[2]);
        }
        if (graph.rootNode() != null) {
            lore.add("§7First Recorded: §f" + formatTimestamp(graph.rootNode().timestampMs()) + " (" + graph.rootNode().actorName() + ")");
        }
        if (graph.terminalNode() != null) {
            lore.add("§7Last Recorded: §f" + formatTimestamp(graph.terminalNode().timestampMs()) + " (" + graph.terminalNode().actorName() + ")");
        }
        return lore;
    }

    public static ItemStack createSummaryItem(ProvenanceGraph graph) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§6§lProvenance Summary");
            meta.setLore(buildSummaryLore(graph));
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack createControlItem(Material material, String displayName, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(displayName);
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
