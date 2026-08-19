package com.chestlogger.paper;

import com.chestlogger.container.ContainerSnapshot;
import com.chestlogger.container.SlotSnapshot;
import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionEventQueue;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.rollback.RollbackPlan;
import com.chestlogger.rollback.RollbackPlanner;
import com.chestlogger.rollback.RollbackResult;
import com.chestlogger.rollback.RollbackStep;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Executes rollback plans against Paper Bukkit Inventory instances on the server main thread.
 */
public final class PaperRollbackExecutor {
    private final RollbackPlanner planner = new RollbackPlanner();
    private final TransactionEventQueue eventQueue;

    public PaperRollbackExecutor(TransactionEventQueue eventQueue) {
        this.eventQueue = Objects.requireNonNull(eventQueue, "eventQueue cannot be null");
    }

    public RollbackPlan plan(List<TransactionLogEntry> historyToUndo, Inventory targetInventory) {
        ContainerSnapshot snapshot = snapshotInventory(targetInventory);
        return planner.createPlan(historyToUndo, snapshot);
    }

    public RollbackResult execute(
            RollbackPlan plan,
            org.bukkit.block.Container container,
            UUID adminUuid,
            String adminName,
            String dimension,
            long packedBlockPos
    ) {
        Objects.requireNonNull(plan, "plan cannot be null");
        Objects.requireNonNull(container, "container cannot be null");
        
        Inventory targetInventory = container.getInventory();
        if (targetInventory instanceof org.bukkit.inventory.DoubleChestInventory dci) {
            targetInventory = dci;
        }

        List<SlotDelta> compensationDeltas = new ArrayList<>();
        int applied = 0;

        for (RollbackStep step : plan.steps()) {
            if (step.slotIndex() < 0 || step.slotIndex() >= targetInventory.getSize()) {
                continue;
            }

            ItemStack currentStack = targetInventory.getItem(step.slotIndex());
            int prevQty = currentStack != null ? currentStack.getAmount() : 0;
            String prevItem = currentStack != null ? resolveItemId(currentStack.getType()) : "";

            if (step.targetDeltaQuantity() > 0) {
                // Restore items
                Material mat = resolveMaterial(step.itemId());
                if (mat != null && mat != Material.AIR) {
                    ItemStack newStack = new ItemStack(mat, prevQty + step.targetDeltaQuantity());
                    targetInventory.setItem(step.slotIndex(), newStack);
                    compensationDeltas.add(new SlotDelta(
                            step.slotIndex(),
                            step.itemId(),
                            step.targetDeltaQuantity(),
                            prevQty,
                            newStack.getAmount(),
                            step.metadataHash()
                    ));
                    applied++;
                }
            } else if (step.targetDeltaQuantity() < 0) {
                // Remove items
                int toRemove = -step.targetDeltaQuantity();
                int newQty = Math.max(0, prevQty - toRemove);
                if (newQty == 0) {
                    targetInventory.setItem(step.slotIndex(), null);
                } else if (currentStack != null) {
                    currentStack.setAmount(newQty);
                    targetInventory.setItem(step.slotIndex(), currentStack);
                }
                compensationDeltas.add(new SlotDelta(
                        step.slotIndex(),
                        step.itemId(),
                        step.targetDeltaQuantity(),
                        prevQty,
                        newQty,
                        step.metadataHash()
                ));
                applied++;
            }
        }

        if (!compensationDeltas.isEmpty()) {
            TransactionLogEntry compensationEntry = new TransactionLogEntry(
                    0L,
                    System.currentTimeMillis(),
                    UUID.randomUUID(),
                    ActionType.ROLLBACK_COMPENSATION,
                    ActorType.ADMIN_COMMAND,
                    adminUuid,
                    adminName != null ? adminName : "Console",
                    dimension != null ? dimension : "minecraft:overworld",
                    packedBlockPos,
                    compensationDeltas
            );
            eventQueue.offer(compensationEntry);
        }

        container.update(true, true);

        return new RollbackResult(applied, plan.conflictCount(), true);
    }

    public static ContainerSnapshot snapshotInventory(Inventory inventory) {
        if (inventory == null) {
            return new ContainerSnapshot(0);
        }
        ContainerSnapshot snapshot = new ContainerSnapshot(inventory.getSize());
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack != null && !stack.getType().isAir()) {
                String itemId = resolveItemId(stack.getType());
                snapshot.setSlot(i, itemId, stack.getAmount(), 0L);
            } else {
                snapshot.setSlot(i, "", 0, 0L);
            }
        }
        return snapshot;
    }

    public static String resolveItemId(Material material) {
        if (material == null) return "minecraft:air";
        NamespacedKey key = material.getKey();
        return key != null ? key.toString() : "minecraft:" + material.name().toLowerCase();
    }

    public static Material resolveMaterial(String itemId) {
        if (itemId == null || itemId.isBlank()) return Material.AIR;
        NamespacedKey key = NamespacedKey.fromString(itemId);
        if (key != null) {
            Material mat = Registry.MATERIAL.get(key);
            if (mat != null) return mat;
        }
        return Material.matchMaterial(itemId);
    }
}
