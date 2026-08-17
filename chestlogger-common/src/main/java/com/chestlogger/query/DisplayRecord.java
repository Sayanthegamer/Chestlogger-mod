package com.chestlogger.query;

import java.util.Objects;
import java.util.UUID;

/**
 * Compact, platform-independent display record representation for GUI rendering and Web dashboard.
 */
public record DisplayRecord(
        long sequenceId,
        long timestampMs,
        UUID actorUuid,
        String actorName,
        byte actorType,
        byte actionType,
        int slotIndex,
        String itemId,
        int quantityDelta,
        long metadataFingerprint,
        String dimension,
        long packedBlockPos
) {
    public static final UUID NIL_UUID = new UUID(0L, 0L);

    public DisplayRecord(
            long sequenceId,
            long timestampMs,
            UUID actorUuid,
            String actorName,
            byte actorType,
            byte actionType,
            int slotIndex,
            String itemId,
            int quantityDelta,
            long metadataFingerprint
    ) {
        this(
                sequenceId, timestampMs, actorUuid, actorName,
                actorType, actionType, slotIndex, itemId,
                quantityDelta, metadataFingerprint,
                "minecraft:overworld", 0L
        );
    }

    public DisplayRecord {
        Objects.requireNonNull(actorUuid, "actorUuid cannot be null");
        Objects.requireNonNull(actorName, "actorName cannot be null");
        Objects.requireNonNull(itemId, "itemId cannot be null");
        if (dimension == null || dimension.isBlank()) {
            dimension = "minecraft:overworld";
        }
    }
}
