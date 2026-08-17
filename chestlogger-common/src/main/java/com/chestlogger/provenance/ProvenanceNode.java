package com.chestlogger.provenance;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents a discrete step in the provenance / chain of custody graph.
 */
public record ProvenanceNode(
        int stepIndex,
        long sequenceId,
        long timestampMs,
        ActionType actionType,
        ActorType actorType,
        UUID actorUuid,
        String actorName,
        String dimension,
        long packedPos,
        String itemId,
        int deltaQuantity,
        long metadataFingerprint,
        ConfidenceLevel confidence,
        String notes
) {
    public ProvenanceNode {
        Objects.requireNonNull(actionType, "actionType cannot be null");
        Objects.requireNonNull(actorType, "actorType cannot be null");
        Objects.requireNonNull(dimension, "dimension cannot be null");
        Objects.requireNonNull(itemId, "itemId cannot be null");
        Objects.requireNonNull(confidence, "confidence cannot be null");
        if (actorName == null) {
            actorName = actorType == ActorType.PLAYER ? "Unknown Player" : actorType.name();
        }
        if (notes == null) {
            notes = "";
        }
    }
}
