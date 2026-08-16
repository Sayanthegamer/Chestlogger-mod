package com.chestlogger.event;

import java.util.Objects;

/**
 * Represents a single container slot modification delta.
 *
 * @param slotIndex Container slot index (0..53)
 * @param itemId Item resource identifier (e.g. "minecraft:diamond")
 * @param deltaQuantity Signed modification (+count for insertion, -count for extraction)
 * @param preCount Slot count prior to transaction
 * @param postCount Slot count after transaction
 * @param metadataFingerprint 64-bit deterministic hash of item components (0L for vanilla default item)
 */
public record SlotDelta(
        int slotIndex,
        String itemId,
        int deltaQuantity,
        int preCount,
        int postCount,
        long metadataFingerprint
) {
    public SlotDelta {
        Objects.requireNonNull(itemId, "itemId cannot be null");
    }

    public boolean isInsertion() {
        return deltaQuantity > 0;
    }

    public boolean isExtraction() {
        return deltaQuantity < 0;
    }
}
