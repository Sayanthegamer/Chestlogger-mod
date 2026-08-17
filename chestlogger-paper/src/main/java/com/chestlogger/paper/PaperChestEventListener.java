package com.chestlogger.paper;

import com.chestlogger.container.SlotSnapshot;
import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionEventQueue;
import com.chestlogger.event.TransactionLogEntry;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance Paper event listener intercepting container interactions.
 * Converts Bukkit inventory events into immutable TransactionLogEntry records and offers
 * them directly to the TransactionEventQueue without disk I/O or main-thread locking.
 */
public final class PaperChestEventListener implements Listener {

    private final Plugin plugin;
    private final TransactionEventQueue eventQueue;
    private final AtomicLong sequenceGenerator;

    public PaperChestEventListener(Plugin plugin, TransactionEventQueue eventQueue, AtomicLong sequenceGenerator) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.eventQueue = Objects.requireNonNull(eventQueue, "eventQueue cannot be null");
        this.sequenceGenerator = Objects.requireNonNull(sequenceGenerator, "sequenceGenerator cannot be null");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        Location loc = getInventoryLocation(event.getInventory());
        if (loc == null || loc.getWorld() == null) {
            return;
        }

        long pos = BlockPosUtil.pack(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        String dim = loc.getWorld().getName();
        HumanEntity player = event.getPlayer();

        TransactionLogEntry entry = new TransactionLogEntry(
                sequenceGenerator.incrementAndGet(),
                System.currentTimeMillis(),
                UUID.randomUUID(),
                ActionType.CONTAINER_OPEN,
                ActorType.PLAYER,
                player.getUniqueId(),
                player.getName(),
                dim,
                pos,
                List.of()
        );
        eventQueue.offer(entry);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        Location loc = getInventoryLocation(event.getInventory());
        if (loc == null || loc.getWorld() == null) {
            return;
        }

        long pos = BlockPosUtil.pack(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        String dim = loc.getWorld().getName();
        HumanEntity player = event.getPlayer();

        TransactionLogEntry entry = new TransactionLogEntry(
                sequenceGenerator.incrementAndGet(),
                System.currentTimeMillis(),
                UUID.randomUUID(),
                ActionType.CONTAINER_CLOSE,
                ActorType.PLAYER,
                player.getUniqueId(),
                player.getName(),
                dim,
                pos,
                List.of()
        );
        eventQueue.offer(entry);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory topInv = event.getView().getTopInventory();
        Location loc = getInventoryLocation(topInv);
        if (loc == null || loc.getWorld() == null) {
            return;
        }

        // Snapshot slots of top inventory before scheduled tick comparison or analyze raw click
        int rawSlot = event.getRawSlot();
        boolean isTopInventorySlot = rawSlot >= 0 && rawSlot < topInv.getSize();

        // If shift-clicking or interacting with the container, track the container mutation
        List<SlotSnapshot> beforeSlots = snapshotInventorySlots(topInv);
        long pos = BlockPosUtil.pack(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        String dim = loc.getWorld().getName();
        HumanEntity player = event.getWhoClicked();
        ActionType actionType = mapClickAction(event.getAction(), event.getClick());

        // Run post-tick snapshot comparison to capture exact item changes across multiple slots
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            List<SlotSnapshot> afterSlots = snapshotInventorySlots(topInv);
            List<SlotDelta> deltas = PaperInventoryDeltaCalculator.calculateSlotDiff(beforeSlots, afterSlots);

            if (!deltas.isEmpty()) {
                TransactionLogEntry entry = new TransactionLogEntry(
                        sequenceGenerator.incrementAndGet(),
                        System.currentTimeMillis(),
                        UUID.randomUUID(),
                        actionType,
                        ActorType.PLAYER,
                        player.getUniqueId(),
                        player.getName(),
                        dim,
                        pos,
                        deltas
                );
                eventQueue.offer(entry);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory topInv = event.getView().getTopInventory();
        Location loc = getInventoryLocation(topInv);
        if (loc == null || loc.getWorld() == null) {
            return;
        }

        List<SlotSnapshot> beforeSlots = snapshotInventorySlots(topInv);
        long pos = BlockPosUtil.pack(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        String dim = loc.getWorld().getName();
        HumanEntity player = event.getWhoClicked();

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            List<SlotSnapshot> afterSlots = snapshotInventorySlots(topInv);
            List<SlotDelta> deltas = PaperInventoryDeltaCalculator.calculateSlotDiff(beforeSlots, afterSlots);

            if (!deltas.isEmpty()) {
                TransactionLogEntry entry = new TransactionLogEntry(
                        sequenceGenerator.incrementAndGet(),
                        System.currentTimeMillis(),
                        UUID.randomUUID(),
                        ActionType.PLACE,
                        ActorType.PLAYER,
                        player.getUniqueId(),
                        player.getName(),
                        dim,
                        pos,
                        deltas
                );
                eventQueue.offer(entry);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        Location sourceLoc = getInventoryLocation(event.getSource());
        Location destLoc = getInventoryLocation(event.getDestination());
        ItemStack movedItem = event.getItem();

        if (movedItem.getType().isAir() || movedItem.getAmount() <= 0) {
            return;
        }

        String itemId = PaperRollbackExecutor.resolveItemId(movedItem.getType());
        int qty = movedItem.getAmount();

        // Source extraction log
        if (sourceLoc != null && sourceLoc.getWorld() != null) {
            long sourcePos = BlockPosUtil.pack(sourceLoc.getBlockX(), sourceLoc.getBlockY(), sourceLoc.getBlockZ());
            SlotDelta extractDelta = new SlotDelta(0, itemId, -qty, qty, 0, 0L);
            TransactionLogEntry sourceEntry = new TransactionLogEntry(
                    sequenceGenerator.incrementAndGet(),
                    System.currentTimeMillis(),
                    UUID.randomUUID(),
                    ActionType.HOPPER_EXTRACT,
                    ActorType.HOPPER_BLOCK,
                    null,
                    "Hopper",
                    sourceLoc.getWorld().getName(),
                    sourcePos,
                    List.of(extractDelta)
            );
            eventQueue.offer(sourceEntry);
        }

        // Destination insertion log
        if (destLoc != null && destLoc.getWorld() != null) {
            long destPos = BlockPosUtil.pack(destLoc.getBlockX(), destLoc.getBlockY(), destLoc.getBlockZ());
            SlotDelta insertDelta = new SlotDelta(0, itemId, qty, 0, qty, 0L);
            TransactionLogEntry destEntry = new TransactionLogEntry(
                    sequenceGenerator.incrementAndGet(),
                    System.currentTimeMillis(),
                    UUID.randomUUID(),
                    ActionType.HOPPER_INSERT,
                    ActorType.HOPPER_BLOCK,
                    null,
                    "Hopper",
                    destLoc.getWorld().getName(),
                    destPos,
                    List.of(insertDelta)
            );
            eventQueue.offer(destEntry);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        BlockState state = block.getState();
        if (state instanceof Container container) {
            Location loc = block.getLocation();
            long pos = BlockPosUtil.pack(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            String dim = loc.getWorld() != null ? loc.getWorld().getName() : "minecraft:overworld";
            Player player = event.getPlayer();

            List<SlotDelta> deltas = new ArrayList<>();
            Inventory inv = container.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack != null && !stack.getType().isAir()) {
                    deltas.add(new SlotDelta(
                            i,
                            PaperRollbackExecutor.resolveItemId(stack.getType()),
                            -stack.getAmount(),
                            stack.getAmount(),
                            0,
                            0L
                    ));
                }
            }

            TransactionLogEntry entry = new TransactionLogEntry(
                    sequenceGenerator.incrementAndGet(),
                    System.currentTimeMillis(),
                    UUID.randomUUID(),
                    ActionType.PICKUP,
                    ActorType.PLAYER,
                    player.getUniqueId(),
                    player.getName(),
                    dim,
                    pos,
                    deltas
            );
            eventQueue.offer(entry);
        }
    }

    private static Location getInventoryLocation(Inventory inventory) {
        if (inventory == null) return null;
        Location loc = inventory.getLocation();
        if (loc != null) return loc;
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Container container) {
            return container.getLocation();
        } else if (holder instanceof BlockState blockState) {
            return blockState.getLocation();
        }
        return null;
    }

    private static List<SlotSnapshot> snapshotInventorySlots(Inventory inventory) {
        if (inventory == null) return List.of();
        List<SlotSnapshot> snapshots = new ArrayList<>(inventory.getSize());
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack != null && !stack.getType().isAir()) {
                snapshots.add(new SlotSnapshot(i, PaperRollbackExecutor.resolveItemId(stack.getType()), stack.getAmount(), 0L));
            } else {
                snapshots.add(new SlotSnapshot(i, "", 0, 0L));
            }
        }
        return snapshots;
    }

    private static ActionType mapClickAction(InventoryAction action, ClickType click) {
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            return ActionType.SHIFT_CLICK_EXTRACT;
        }
        if (click == ClickType.DOUBLE_CLICK) {
            return ActionType.DOUBLE_CLICK_COLLECT;
        }
        if (click == ClickType.NUMBER_KEY) {
            return ActionType.HOTBAR_SWAP;
        }
        if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
            return ActionType.DROP_FROM_SLOT;
        }
        return switch (action) {
            case PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE -> ActionType.PICKUP;
            case PLACE_ALL, PLACE_SOME, PLACE_ONE -> ActionType.PLACE;
            case SWAP_WITH_CURSOR -> ActionType.PICKUP;
            case MOVE_TO_OTHER_INVENTORY -> ActionType.SHIFT_CLICK_EXTRACT;
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> ActionType.HOTBAR_SWAP;
            case COLLECT_TO_CURSOR -> ActionType.DOUBLE_CLICK_COLLECT;
            default -> ActionType.PICKUP;
        };
    }
}
