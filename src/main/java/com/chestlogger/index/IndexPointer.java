package com.chestlogger.index;

import java.util.Objects;
import java.util.UUID;

/**
 * Lightweight pointer mapping an indexed transaction to its exact storage location.
 */
public record IndexPointer(
        long sequenceId,
        long timestampMs,
        UUID actorUuid,
        String itemId,
        String dimension,
        long packedBlockPos,
        int segmentIndex,
        long blockOffset,
        int recordIndexInBlock
) {
    public IndexPointer {
        Objects.requireNonNull(dimension, "dimension cannot be null");
    }
}
