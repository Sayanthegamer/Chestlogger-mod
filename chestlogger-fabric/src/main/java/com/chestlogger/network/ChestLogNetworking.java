package com.chestlogger.network;

import com.chestlogger.ChestLoggerMod;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.index.IndexQueryFilter;
import com.chestlogger.query.PagedResult;
import com.chestlogger.query.QuerySessionManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Handles payload type registrations and serverbound request dispatching for Fabric Networking.
 */
public final class ChestLogNetworking {
    private ChestLogNetworking() {}

    public static void init() {
        // Register clientbound payload (Server -> Client)
        PayloadTypeRegistry.clientboundPlay().register(
                ChestLogPagePayload.TYPE,
                ChestLogPagePayload.STREAM_CODEC
        );

        // Register serverbound payload (Client -> Server)
        PayloadTypeRegistry.serverboundPlay().register(
                ChestLogPageRequestPayload.TYPE,
                ChestLogPageRequestPayload.STREAM_CODEC
        );

        // Register serverbound packet listener
        ServerPlayNetworking.registerGlobalReceiver(
                ChestLogPageRequestPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    if (!context.server().getPlayerList().isOp(new NameAndId(player.getGameProfile()))) {
                        return; // Security: Strictly reject unauthorized requests
                    }

                    QuerySessionManager sessionManager = ChestLoggerMod.getSessionManager();
                    PagedResult<com.chestlogger.query.DisplayRecord> sessionPage;

                    boolean hasFilters = (payload.filterPlayer() != null && !payload.filterPlayer().isBlank())
                            || (payload.filterItem() != null && !payload.filterItem().isBlank());

                    if (!sessionManager.hasSession(payload.queryId()) || hasFilters) {
                        try {
                            IndexQueryFilter.Builder builder = IndexQueryFilter.builder()
                                    .dimension(payload.dimension())
                                    .exactBlockPos(payload.packedBlockPos())
                                    .limit(1000);

                            if (payload.filterItem() != null && !payload.filterItem().isBlank()) {
                                builder.itemId(payload.filterItem().trim());
                            }

                            List<TransactionLogEntry> history = (ChestLoggerMod.getQueryEngine() != null)
                                    ? ChestLoggerMod.getQueryEngine().fetchRecords(builder.build())
                                    : Collections.emptyList();

                            if (payload.filterPlayer() != null && !payload.filterPlayer().isBlank()) {
                                String searchName = payload.filterPlayer().trim().toLowerCase();
                                history = history.stream()
                                        .filter(r -> r.actorName() != null && r.actorName().toLowerCase().contains(searchName))
                                        .toList();
                            }

                            sessionPage = sessionManager.createSession(
                                    payload.queryId(),
                                    "Container",
                                    payload.dimension(),
                                    payload.packedBlockPos(),
                                    history,
                                    payload.requestedPage()
                            );
                        } catch (Exception e) {
                            sessionPage = sessionManager.createSession(
                                    payload.queryId(),
                                    "Container",
                                    payload.dimension(),
                                    payload.packedBlockPos(),
                                    Collections.emptyList(),
                                    1
                            );
                        }
                    } else {
                        sessionPage = sessionManager.getPage(payload.queryId(), payload.requestedPage());
                    }

                    if (sessionPage != null) {
                        ChestLogPagePayload response = toPayload(payload.queryId(), "Container", payload.dimension(), payload.packedBlockPos(), sessionPage);
                        ServerPlayNetworking.send(player, response);
                    }
                }
        );
    }

    private static ChestLogPagePayload toPayload(
            UUID queryId,
            String containerType,
            String dimension,
            long packedBlockPos,
            com.chestlogger.query.PagedResult<com.chestlogger.query.DisplayRecord> result
    ) {
        if (result == null) return null;
        List<com.chestlogger.network.DisplayRecord> netRecords = result.items().stream()
                .map(r -> new com.chestlogger.network.DisplayRecord(
                        r.sequenceId(), r.timestampMs(), r.actorUuid(), r.actorName(),
                        r.actorType(), r.actionType(), r.slotIndex(), r.itemId(),
                        r.quantityDelta(), r.metadataFingerprint(), r.dimension(), r.packedBlockPos()
                )).toList();

        return new ChestLogPagePayload(
                queryId, result.pageNumber(), result.totalPages(), result.totalElements(),
                containerType, dimension, packedBlockPos, netRecords
        );
    }
}
