package com.chestlogger.rollback;

import com.chestlogger.container.ContainerSnapshot;
import com.chestlogger.container.SlotSnapshot;
import com.chestlogger.event.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Non-destructive compensation rollback engine for container snapshots.
 * Computes inverse deltas and applies compensating transactions with audit records.
 */
public final class RollbackEngine {
    private final RollbackPlanner planner = new RollbackPlanner();

    public RollbackPlan createPlan(List<TransactionLogEntry> historyToUndo, ContainerSnapshot currentContainer) {
        return planner.createPlan(historyToUndo, currentContainer);
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
            int targetSlot = step.slotIndex();
            if (targetSlot >= container.size()) {
                // Adaptive compensation: find first empty slot in surviving container
                int fallbackSlot = -1;
                for (int s = 0; s < container.size(); s++) {
                    if (container.getSlot(s).isEmpty()) {
                        fallbackSlot = s;
                        break;
                    }
                }
                if (fallbackSlot != -1) {
                    targetSlot = fallbackSlot;
                }
            }

            if (targetSlot < container.size()) {
                SlotSnapshot current = container.getSlot(targetSlot);
                int newCount = Math.max(0, Math.min(64, current.count() + step.targetDeltaQuantity()));
                String item = newCount == 0 ? "" : step.itemId();
                container.setSlot(targetSlot, item, newCount, step.metadataHash());

                auditDeltas.add(new SlotDelta(
                        targetSlot,
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
                    0L,
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
