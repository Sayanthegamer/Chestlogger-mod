package com.chestlogger.paper;

import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Pure model & item builder for the Paper 54-slot chest inspection GUI.
 */
public final class PaperHistoryGuiModel {

    public static final int GUI_SIZE = 54;
    public static final int PAGE_SIZE = 45;

    public static final int SLOT_PREV_PAGE = 45;
    public static final int SLOT_INFO = 48;
    public static final int SLOT_CLOSE = 49;
    public static final int SLOT_ROLLBACK = 50;
    public static final int SLOT_NEXT_PAGE = 53;

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private PaperHistoryGuiModel() {}

    public static int calculateTotalPages(int totalItems) {
        if (totalItems <= 0) return 1;
        return (int) Math.ceil((double) totalItems / PAGE_SIZE);
    }

    public static List<TransactionLogEntry> getPageItems(List<TransactionLogEntry> allItems, int page, int pageSize) {
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

    public static ItemStack createEntryItem(TransactionLogEntry entry, int containerCapacity) {
        SlotDelta delta = (entry.deltas() != null && !entry.deltas().isEmpty()) ? entry.deltas().get(0) : null;
        String itemId = delta != null ? delta.itemId() : "minecraft:chest";
        int qty = delta != null ? Math.max(1, Math.min(64, Math.abs(delta.deltaQuantity()))) : 1;

        Material mat = resolveMaterial(itemId);
        ItemStack item = new ItemStack(mat, qty);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            boolean isAddition = delta != null && delta.deltaQuantity() > 0;
            String prefix = isAddition ? "§a+ " : "§c- ";
            String cleanName = mat.name().replace('_', ' ').toLowerCase(Locale.ROOT);
            meta.setDisplayName(prefix + "§f" + qty + "x §e" + cleanName);
            meta.setLore(buildItemLore(entry, delta != null ? delta.slotIndex() : 0, containerCapacity));
            item.setItemMeta(meta);
        }
        return item;
    }

    public static List<String> buildItemLore(TransactionLogEntry entry, int slotIndex, int containerCapacity) {
        List<String> lore = new ArrayList<>();
        SlotDelta delta = (entry.deltas() != null && !entry.deltas().isEmpty()) ? entry.deltas().get(0) : null;
        boolean isAddition = delta != null && delta.deltaQuantity() > 0;

        lore.add("§7Action: §f" + entry.actionType().name());
        lore.add("§7Delta: " + (isAddition ? "§a+" : "§c") + (delta != null ? delta.deltaQuantity() : 0) + " §7" + (delta != null ? delta.itemId() : ""));
        lore.add("§7Actor: §e" + (entry.actorName() != null ? entry.actorName() : entry.actorType().name()));

        synchronized (DATE_FORMAT) {
            lore.add("§7Time: §f" + DATE_FORMAT.format(new Date(entry.timestampMs())));
        }

        String slotText = "§7Slot: §f#" + slotIndex;
        if (containerCapacity == 54) {
            slotText += (slotIndex < 27) ? " §b[Left Half]" : " §d[Right Half]";
        }
        lore.add(slotText);

        int[] coords = BlockPosUtil.unpack(entry.packedBlockPos());
        lore.add("§7Pos: §b" + coords[0] + ", " + coords[1] + ", " + coords[2] + " (" + entry.dimension() + ")");
        return lore;
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
