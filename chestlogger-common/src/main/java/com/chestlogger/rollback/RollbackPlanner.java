package com.chestlogger.rollback;

import com.chestlogger.container.ContainerSnapshot;
import com.chestlogger.container.SlotSnapshot;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Platform-independent rollback compensation planner.
 * Calculates inverse deltas and creates verifiable, conflict-checked rollback plans.
 */
public final class RollbackPlanner {

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
}
