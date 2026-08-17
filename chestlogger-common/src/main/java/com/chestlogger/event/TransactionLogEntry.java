package com.chestlogger.event;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable representation of an atomic container inventory transaction.
 */
public record TransactionLogEntry(
        long sequenceId,
        long timestampMs,
        UUID transactionId,
        ActionType actionType,
        ActorType actorType,
        UUID actorUuid,
        String actorName,
        String dimension,
        long packedBlockPos,
        List<SlotDelta> deltas
) {
    public TransactionLogEntry {
        Objects.requireNonNull(transactionId, "transactionId cannot be null");
        Objects.requireNonNull(actionType, "actionType cannot be null");
        Objects.requireNonNull(actorType, "actorType cannot be null");
        Objects.requireNonNull(dimension, "dimension cannot be null");
        Objects.requireNonNull(deltas, "deltas cannot be null");
        deltas = List.copyOf(deltas);
    }

    public boolean isAutomation() {
        return actorType != ActorType.PLAYER;
    }
}
