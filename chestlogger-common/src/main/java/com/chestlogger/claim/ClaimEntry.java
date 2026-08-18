package com.chestlogger.claim;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable representation of a container claim record.
 *
 * @param dimension Dimension identifier (e.g. "minecraft:overworld").
 * @param packedBlockPos Packed 64-bit coordinate of the container block.
 * @param ownerUuid UUID of the player who claimed or placed the container.
 * @param ownerName Display name of the owner.
 * @param partnerPackedPos Partner block coordinate if part of a linked double chest (or null).
 * @param claimedAtMs Unix epoch timestamp in milliseconds when the claim was created.
 */
public record ClaimEntry(
        String dimension,
        long packedBlockPos,
        UUID ownerUuid,
        String ownerName,
        Long partnerPackedPos,
        long claimedAtMs
) {
    public ClaimEntry {
        Objects.requireNonNull(dimension, "dimension cannot be null");
        Objects.requireNonNull(ownerUuid, "ownerUuid cannot be null");
    }

    public ClaimEntry(String dimension, long packedBlockPos, UUID ownerUuid, String ownerName, Long partnerPackedPos) {
        this(dimension, packedBlockPos, ownerUuid, ownerName, partnerPackedPos, System.currentTimeMillis());
    }

    public ClaimEntry(String dimension, long packedBlockPos, UUID ownerUuid, String ownerName) {
        this(dimension, packedBlockPos, ownerUuid, ownerName, null, System.currentTimeMillis());
    }
}
