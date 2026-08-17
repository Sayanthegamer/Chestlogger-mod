package com.chestlogger.paper;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Intercepts clicks and drag operations inside PaperChestHistoryView inspection menus.
 */
public final class PaperChestGuiListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory inv = event.getInventory();
        if (!(inv.getHolder() instanceof PaperChestHistoryView view)) {
            return;
        }

        // Cancel all click interactions inside the inspection GUI
        event.setCancelled(true);

        int rawSlot = event.getRawSlot();
        if (rawSlot == PaperHistoryGuiModel.SLOT_PREV_PAGE) {
            view.previousPage();
        } else if (rawSlot == PaperHistoryGuiModel.SLOT_NEXT_PAGE) {
            view.nextPage();
        } else if (rawSlot == PaperHistoryGuiModel.SLOT_CLOSE) {
            event.getWhoClicked().closeInventory();
        } else if (rawSlot == PaperHistoryGuiModel.SLOT_ROLLBACK) {
            view.handleRollbackClick();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory inv = event.getInventory();
        if (inv.getHolder() instanceof PaperChestHistoryView) {
            event.setCancelled(true);
        }
    }
}
