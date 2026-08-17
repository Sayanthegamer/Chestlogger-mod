package com.chestlogger.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.Objects;
import java.util.UUID;

/**
 * Compact, client-friendly record representation for GUI rendering and Web dashboard.
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

    public static final StreamCodec<FriendlyByteBuf, DisplayRecord> STREAM_CODEC = StreamCodec.of(
            DisplayRecord::write,
            DisplayRecord::read
    );

    public static void write(FriendlyByteBuf buf, DisplayRecord record) {
        buf.writeVarLong(record.sequenceId());
        buf.writeVarLong(record.timestampMs());
        buf.writeUUID(record.actorUuid());
        buf.writeUtf(record.actorName());
        buf.writeByte(record.actorType());
        buf.writeByte(record.actionType());
        buf.writeVarInt(record.slotIndex());
        buf.writeUtf(record.itemId());
        buf.writeVarInt(record.quantityDelta());
        buf.writeLong(record.metadataFingerprint());
        buf.writeUtf(record.dimension());
        buf.writeLong(record.packedBlockPos());
    }

    public static DisplayRecord read(FriendlyByteBuf buf) {
        long sequenceId = buf.readVarLong();
        long timestampMs = buf.readVarLong();
        UUID actorUuid = buf.readUUID();
        String actorName = buf.readUtf();
        byte actorType = buf.readByte();
        byte actionType = buf.readByte();
        int slotIndex = buf.readVarInt();
        String itemId = buf.readUtf();
        int quantityDelta = buf.readVarInt();
        long metadataFingerprint = buf.readLong();
        String dimension = buf.readUtf();
        long packedBlockPos = buf.readLong();

        return new DisplayRecord(
                sequenceId, timestampMs, actorUuid, actorName,
                actorType, actionType, slotIndex, itemId,
                quantityDelta, metadataFingerprint,
                dimension, packedBlockPos
        );
    }
}
