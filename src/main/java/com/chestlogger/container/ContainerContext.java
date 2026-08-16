package com.chestlogger.container;

import java.util.Objects;

/**
 * Contextual metadata about an active container interaction.
 *
 * @param dimension Dimension identifier (e.g. "minecraft:overworld")
 * @param packedBlockPos 64-bit bitpacked BlockPos
 * @param containerType Identified container type
 * @param containerName Human-readable container name or custom title
 */
public record ContainerContext(
        String dimension,
        long packedBlockPos,
        ContainerType containerType,
        String containerName
) {
    public ContainerContext {
        Objects.requireNonNull(dimension, "dimension cannot be null");
        Objects.requireNonNull(containerType, "containerType cannot be null");
    }
}
