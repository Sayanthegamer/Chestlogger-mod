package com.chestlogger.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-to-client payload carrying a single bounded page of log records for GUI display.
 */
public record ChestLogPagePayload(
        UUID queryId,
        int pageIndex,
        int totalPages,
        int totalRecords,
        String containerType,
        String dimension,
        long packedBlockPos,
        List<DisplayRecord> records
) implements CustomPacketPayload {

    public static final Type<ChestLogPagePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("chestlogger", "page_payload"));

    public static final StreamCodec<FriendlyByteBuf, ChestLogPagePayload> STREAM_CODEC = CustomPacketPayload.codec(
            ChestLogPagePayload::write,
            ChestLogPagePayload::read
    );

    public ChestLogPagePayload {
        Objects.requireNonNull(queryId, "queryId cannot be null");
        Objects.requireNonNull(containerType, "containerType cannot be null");
        Objects.requireNonNull(dimension, "dimension cannot be null");
        Objects.requireNonNull(records, "records cannot be null");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(queryId);
        buf.writeVarInt(pageIndex);
        buf.writeVarInt(totalPages);
        buf.writeVarInt(totalRecords);
        buf.writeUtf(containerType);
        buf.writeUtf(dimension);
        buf.writeLong(packedBlockPos);

        buf.writeVarInt(records.size());
        for (DisplayRecord r : records) {
            DisplayRecord.write(buf, r);
        }
    }

    public static ChestLogPagePayload read(FriendlyByteBuf buf) {
        UUID queryId = buf.readUUID();
        int pageIndex = buf.readVarInt();
        int totalPages = buf.readVarInt();
        int totalRecords = buf.readVarInt();
        String containerType = buf.readUtf();
        String dimension = buf.readUtf();
        long packedBlockPos = buf.readLong();

        int recordCount = buf.readVarInt();
        List<DisplayRecord> records = new ArrayList<>(recordCount);
        for (int i = 0; i < recordCount; i++) {
            records.add(DisplayRecord.read(buf));
        }

        return new ChestLogPagePayload(
                queryId, pageIndex, totalPages, totalRecords,
                containerType, dimension, packedBlockPos, records
        );
    }
}
