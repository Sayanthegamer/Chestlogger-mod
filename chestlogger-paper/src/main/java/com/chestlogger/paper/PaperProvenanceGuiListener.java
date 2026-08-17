package com.chestlogger.paper;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Intercepts clicks and drag operations inside PaperProvenanceGuiView menus.
 */
public final class PaperProvenanceGuiListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory inv = event.getInventory();
        if (!(inv.getHolder() instanceof PaperProvenanceGuiView view)) {
            return;
        }

        // Cancel all click interactions inside the provenance GUI
        event.setCancelled(true);

        int rawSlot = event.getRawSlot();
        if (rawSlot == PaperProvenanceGuiModel.SLOT_PREV_PAGE) {
            view.previousPage();
        } else if (rawSlot == PaperProvenanceGuiModel.SLOT_NEXT_PAGE) {
            view.nextPage();
        } else if (rawSlot == PaperProvenanceGuiModel.SLOT_CLOSE) {
            event.getWhoClicked().closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory inv = event.getInventory();
        if (inv.getHolder() instanceof PaperProvenanceGuiView) {
            event.setCancelled(true);
        }
    }
}
