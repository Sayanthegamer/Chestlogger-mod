package com.chestlogger.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.Objects;

/**
 * Serialized chain-of-custody node for item provenance GUI rendering on Fabric.
 */
public record ProvenanceDisplayNode(
        int stepIndex,
        String actionType,
        String confidence,
        String actorName,
        String actorType,
        int deltaQuantity,
        String itemId,
        long timestampMs,
        long packedPos,
        String dimension,
        String notes
) {
    public ProvenanceDisplayNode {
        actionType = actionType != null ? actionType : "UNKNOWN";
        confidence = confidence != null ? confidence : "UNKNOWN";
        actorName = actorName != null ? actorName : "Unknown";
        actorType = actorType != null ? actorType : "UNKNOWN";
        itemId = itemId != null ? itemId : "minecraft:chest";
        dimension = dimension != null ? dimension : "minecraft:overworld";
        notes = notes != null ? notes : "";
    }

    public static void write(FriendlyByteBuf buf, ProvenanceDisplayNode node) {
        buf.writeVarInt(node.stepIndex());
        buf.writeUtf(node.actionType());
        buf.writeUtf(node.confidence());
        buf.writeUtf(node.actorName());
        buf.writeUtf(node.actorType());
        buf.writeVarInt(node.deltaQuantity());
        buf.writeUtf(node.itemId());
        buf.writeLong(node.timestampMs());
        buf.writeLong(node.packedPos());
        buf.writeUtf(node.dimension());
        buf.writeUtf(node.notes());
    }

    public static ProvenanceDisplayNode read(FriendlyByteBuf buf) {
        return new ProvenanceDisplayNode(
                buf.readVarInt(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readVarInt(),
                buf.readUtf(),
                buf.readLong(),
                buf.readLong(),
                buf.readUtf(),
                buf.readUtf()
        );
    }
}
