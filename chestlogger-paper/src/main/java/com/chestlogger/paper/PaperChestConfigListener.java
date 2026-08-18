package com.chestlogger.paper;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Intercepts clicks and drag operations inside PaperChestConfigView configuration menus.
 */
public final class PaperChestConfigListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory inv = event.getInventory();
        if (!(inv.getHolder() instanceof PaperChestConfigView view)) {
            return;
        }

        // Cancel default item movement inside config menu
        event.setCancelled(true);

        int rawSlot = event.getRawSlot();
        if (rawSlot >= 0 && rawSlot < PaperChestConfigView.GUI_SIZE) {
            view.handleSlotClick(rawSlot);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory inv = event.getInventory();
        if (inv.getHolder() instanceof PaperChestConfigView) {
            event.setCancelled(true);
        }
    }
}
