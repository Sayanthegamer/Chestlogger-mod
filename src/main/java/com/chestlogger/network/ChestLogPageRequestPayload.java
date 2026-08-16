package com.chestlogger.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.UUID;

/**
 * Client-to-server payload requesting a specific page or applying filters for a query.
 */
public record ChestLogPageRequestPayload(
        UUID queryId,
        int requestedPage,
        long packedBlockPos,
        String dimension,
        String filterPlayer,
        String filterItem
) implements CustomPacketPayload {

    public static final Type<ChestLogPageRequestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("chestlogger", "page_request"));

    public static final StreamCodec<FriendlyByteBuf, ChestLogPageRequestPayload> STREAM_CODEC = CustomPacketPayload.codec(
            ChestLogPageRequestPayload::write,
            ChestLogPageRequestPayload::read
    );

    public ChestLogPageRequestPayload {
        Objects.requireNonNull(queryId, "queryId cannot be null");
        Objects.requireNonNull(dimension, "dimension cannot be null");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(queryId);
        buf.writeVarInt(requestedPage);
        buf.writeLong(packedBlockPos);
        buf.writeUtf(dimension);

        buf.writeBoolean(filterPlayer != null && !filterPlayer.isBlank());
        if (filterPlayer != null && !filterPlayer.isBlank()) {
            buf.writeUtf(filterPlayer);
        }

        buf.writeBoolean(filterItem != null && !filterItem.isBlank());
        if (filterItem != null && !filterItem.isBlank()) {
            buf.writeUtf(filterItem);
        }
    }

    public static ChestLogPageRequestPayload read(FriendlyByteBuf buf) {
        UUID queryId = buf.readUUID();
        int requestedPage = buf.readVarInt();
        long packedBlockPos = buf.readLong();
        String dimension = buf.readUtf();

        boolean hasPlayer = buf.readBoolean();
        String filterPlayer = hasPlayer ? buf.readUtf() : null;

        boolean hasItem = buf.readBoolean();
        String filterItem = hasItem ? buf.readUtf() : null;

        return new ChestLogPageRequestPayload(
                queryId, requestedPage, packedBlockPos,
                dimension, filterPlayer, filterItem
        );
    }
}
