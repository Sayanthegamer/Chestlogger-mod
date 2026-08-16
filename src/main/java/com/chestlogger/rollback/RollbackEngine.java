package com.chestlogger.rollback;

import com.chestlogger.container.ContainerSnapshot;
import com.chestlogger.container.SlotSnapshot;
import com.chestlogger.event.*;

import java.util.*;

/**
 * Non-destructive compensation rollback engine.
 * Computes inverse deltas and applies compensating transactions with admin audit records.
 */
public final class RollbackEngine {

    public RollbackPlan createPlan(List<TransactionLogEntry> historyToUndo, ContainerSnapshot currentContainer) {
        Objects.requireNonNull(historyToUndo, "historyToUndo cannot be null");
        Objects.requireNonNull(currentContainer, "currentContainer cannot be null");

        List<RollbackStep> steps = new ArrayList<>();
        int conflicts = 0;

        // Virtual simulator copy to verify slot availability
        ContainerSnapshot simulated = currentContainer.copy();

        // Process undo operations in reverse order
        for (int i = historyToUndo.size() - 1; i >= 0; i--) {
            TransactionLogEntry entry = historyToUndo.get(i);
            for (SlotDelta delta : entry.deltas()) {
                int inverseQty = -delta.deltaQuantity();
                int targetSlot = delta.slotIndex();
                String targetItem = delta.itemId();

                if (inverseQty > 0) {
                    // Need to restore items into the container
                    if (targetSlot < simulated.size()) {
                        SlotSnapshot currentSlot = simulated.getSlot(targetSlot);
                        if (currentSlot.count() == 0 || (currentSlot.itemId().equals(targetItem) && currentSlot.count() + inverseQty <= 64)) {
                            int newCount = currentSlot.count() + inverseQty;
                            simulated.setSlot(targetSlot, targetItem, newCount, delta.metadataFingerprint());
                            steps.add(new RollbackStep(targetSlot, targetItem, inverseQty, delta.metadataFingerprint()));
                            continue;
                        }
                    }

                    // Target slot occupied or invalid -> find next available empty slot
                    int emptySlot = -1;
                    for (int s = 0; s < simulated.size(); s++) {
                        if (simulated.getSlot(s).count() == 0) {
                            emptySlot = s;
                            break;
                        }
                    }

                    if (emptySlot != -1) {
                        simulated.setSlot(emptySlot, targetItem, inverseQty, delta.metadataFingerprint());
                        steps.add(new RollbackStep(emptySlot, targetItem, inverseQty, delta.metadataFingerprint()));
                    } else {
                        // Container full, cannot place without deleting existing items
                        conflicts++;
                    }
                } else if (inverseQty < 0) {
                    // Need to remove items that were illicitly placed
                    int toRemove = -inverseQty;
                    if (targetSlot < simulated.size() && simulated.getSlot(targetSlot).itemId().equals(targetItem)) {
                        SlotSnapshot currentSlot = simulated.getSlot(targetSlot);
                        int newCount = Math.max(0, currentSlot.count() - toRemove);
                        simulated.setSlot(targetSlot, newCount == 0 ? "" : targetItem, newCount, delta.metadataFingerprint());
                        steps.add(new RollbackStep(targetSlot, targetItem, inverseQty, delta.metadataFingerprint()));
                    } else {
                        conflicts++;
                    }
                }
            }
        }

        return new RollbackPlan(steps, conflicts);
    }

    public RollbackResult applyRollback(
            RollbackPlan plan,
            ContainerSnapshot container,
            TransactionEventQueue eventQueue,
            UUID adminUuid,
            String adminName,
            String dimension,
            long packedPos
    ) {
        Objects.requireNonNull(plan, "plan cannot be null");
        Objects.requireNonNull(container, "container cannot be null");
        Objects.requireNonNull(eventQueue, "eventQueue cannot be null");

        List<SlotDelta> auditDeltas = new ArrayList<>();
        int applied = 0;

        for (RollbackStep step : plan.steps()) {
            if (step.slotIndex() < container.size()) {
                SlotSnapshot current = container.getSlot(step.slotIndex());
                int newCount = Math.max(0, Math.min(64, current.count() + step.targetDeltaQuantity()));
                String item = newCount == 0 ? "" : step.itemId();
                container.setSlot(step.slotIndex(), item, newCount, step.metadataHash());

                auditDeltas.add(new SlotDelta(
                        step.slotIndex(),
                        step.itemId(),
                        step.targetDeltaQuantity(),
                        current.count(),
                        newCount,
                        step.metadataHash()
                ));
                applied++;
            }
        }

        if (!auditDeltas.isEmpty()) {
            TransactionLogEntry compensationLog = new TransactionLogEntry(
                    0L, // Assigned on drain/enqueue
                    System.currentTimeMillis(),
                    UUID.randomUUID(),
                    ActionType.ROLLBACK_COMPENSATION,
                    ActorType.ADMIN_COMMAND,
                    adminUuid,
                    adminName != null ? adminName : "Server",
                    dimension,
                    packedPos,
                    auditDeltas
            );
            eventQueue.offer(compensationLog);
        }

        return new RollbackResult(applied, plan.conflictCount(), true);
    }
}
