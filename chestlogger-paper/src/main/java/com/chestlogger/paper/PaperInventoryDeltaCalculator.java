package com.chestlogger.paper;

import com.chestlogger.container.SlotSnapshot;
import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Calculates slot deltas and constructs immutable TransactionLogEntry records
 * for Paper server event interception.
 */
public final class PaperInventoryDeltaCalculator {

    private PaperInventoryDeltaCalculator() {}

    /**
     * Compares before and after slot snapshots to produce exact slot deltas.
     */
    public static List<SlotDelta> calculateSlotDiff(List<SlotSnapshot> beforeSlots, List<SlotSnapshot> afterSlots) {
        List<SlotDelta> deltas = new ArrayList<>();
        int count = Math.min(beforeSlots.size(), afterSlots.size());

        for (int i = 0; i < count; i++) {
            SlotSnapshot before = beforeSlots.get(i);
            SlotSnapshot after = afterSlots.get(i);

            if (before.equals(after)) {
                continue;
            }

            int slotIndex = after.slotIndex() >= 0 ? after.slotIndex() : before.slotIndex();

            if (before.itemId().isEmpty() || before.count() == 0) {
                // Item placed into previously empty slot
                deltas.add(new SlotDelta(
                        slotIndex,
                        after.itemId(),
                        after.count(),
                        0,
                        after.count(),
                        after.metadataFingerprint()
                ));
            } else if (after.itemId().isEmpty() || after.count() == 0) {
                // Item completely removed from slot
                deltas.add(new SlotDelta(
                        slotIndex,
                        before.itemId(),
                        -before.count(),
                        before.count(),
                        0,
                        before.metadataFingerprint()
                ));
            } else if (before.itemId().equals(after.itemId())) {
                // Same item, quantity changed
                int qtyDiff = after.count() - before.count();
                if (qtyDiff != 0) {
                    deltas.add(new SlotDelta(
                            slotIndex,
                            after.itemId(),
                            qtyDiff,
                            before.count(),
                            after.count(),
                            after.metadataFingerprint()
                    ));
                }
            } else {
                // Different item replaced slot (e.g. swap): old item removed, new item placed
                deltas.add(new SlotDelta(
                        slotIndex,
                        before.itemId(),
                        -before.count(),
                        before.count(),
                        0,
                        before.metadataFingerprint()
                ));
                deltas.add(new SlotDelta(
                        slotIndex,
                        after.itemId(),
                        after.count(),
                        0,
                        after.count(),
                        after.metadataFingerprint()
                ));
            }
        }

        return deltas;
    }

    /**
     * Assembles an immutable TransactionLogEntry.
     */
    public static TransactionLogEntry buildEntry(
            long sequenceId,
            UUID transactionId,
            ActionType actionType,
            ActorType actorType,
            UUID actorUuid,
            String actorName,
            String dimension,
            long packedBlockPos,
            List<SlotDelta> deltas
    ) {
        return new TransactionLogEntry(
                sequenceId,
                System.currentTimeMillis(),
                transactionId != null ? transactionId : UUID.randomUUID(),
                actionType != null ? actionType : ActionType.PICKUP,
                actorType != null ? actorType : ActorType.PLAYER,
                actorUuid,
                actorName != null ? actorName : "Unknown",
                dimension != null ? dimension : "minecraft:overworld",
                packedBlockPos,
                deltas != null ? deltas : List.of()
        );
    }
}
