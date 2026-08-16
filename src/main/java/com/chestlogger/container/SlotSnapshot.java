package com.chestlogger.container;

import java.util.Objects;

/**
 * Immutable capture of a single container slot state.
 */
public record SlotSnapshot(
        int slotIndex,
        String itemId,
        int count,
        long metadataFingerprint
) {
    public static final SlotSnapshot EMPTY = new SlotSnapshot(0, "minecraft:air", 0, 0L);

    public SlotSnapshot {
        Objects.requireNonNull(itemId, "itemId cannot be null");
    }

    public boolean isEmpty() {
        return count <= 0 || "minecraft:air".equals(itemId);
    }
}
