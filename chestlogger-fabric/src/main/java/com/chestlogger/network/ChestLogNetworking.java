package com.chestlogger.network;

import com.chestlogger.ChestLoggerMod;
import com.chestlogger.event.BlockPosUtil;
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
        PayloadTypeRegistry.clientboundPlay().register(
                ChestLogConfigPayload.TYPE,
                ChestLogConfigPayload.STREAM_CODEC
        );

        // Register serverbound payload (Client -> Server)
        PayloadTypeRegistry.serverboundPlay().register(
                ChestLogPageRequestPayload.TYPE,
                ChestLogPageRequestPayload.STREAM_CODEC
        );
        PayloadTypeRegistry.serverboundPlay().register(
                ChestLogConfigUpdatePayload.TYPE,
                ChestLogConfigUpdatePayload.STREAM_CODEC
        );

        // Register serverbound config update packet listener
        ServerPlayNetworking.registerGlobalReceiver(
                ChestLogConfigUpdatePayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    if (!context.server().getPlayerList().isOp(new NameAndId(player.getGameProfile()))) {
                        return; // Security: Strictly reject unauthorized requests
                    }

                    com.chestlogger.config.ConfigManager configManager = ChestLoggerMod.getConfigManager();
                    if (configManager != null) {
                        com.chestlogger.alert.AlertConfig currentAlert = configManager.getAlertConfig();
                        com.chestlogger.alert.AlertConfig updatedAlert = new com.chestlogger.alert.AlertConfig(
                                payload.alertEnabled(),
                                payload.discordWebhookUrl(),
                                payload.botUsername(),
                                payload.avatarUrl(),
                                currentAlert != null ? currentAlert.quantityThreshold() : 64,
                                new java.util.HashSet<>(payload.trackedItems()),
                                currentAlert != null ? currentAlert.alertOnContainerBreak() : true,
                                currentAlert != null ? currentAlert.alertOnValuableTheft() : true,
                                payload.alertCooldownSeconds()
                        );

                        configManager.updateAlertConfig(updatedAlert);
                        configManager.setActionBarNoticeEnabled(payload.actionBarNoticeEnabled());
                        configManager.setInGameChatAlertEnabled(payload.inGameChatAlertEnabled());
                        configManager.setMaxOwnerAlertDistance(payload.maxOwnerAlertDistance());
                        configManager.setTrackedItems(new java.util.HashSet<>(payload.trackedItems()));

                        configManager.updateWebConfig(web -> {
                            web.setEnabled(payload.webEnabled());
                            web.setHost(payload.webHost());
                            web.setPort(payload.webPort());
                        });

                        configManager.saveAll();

                        player.sendSystemMessage(
                                net.minecraft.network.chat.Component.literal("§a[ChestLogger] Settings updated and hot-reloaded successfully!")
                        );
                    }
                }
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

                            String containerType = resolveContainerType(player.level(), payload.packedBlockPos());

                            sessionPage = sessionManager.createSession(
                                    payload.queryId(),
                                    containerType,
                                    payload.dimension(),
                                    payload.packedBlockPos(),
                                    history,
                                    payload.requestedPage()
                            );
                        } catch (Exception e) {
                            String containerType = resolveContainerType(player.level(), payload.packedBlockPos());
                            sessionPage = sessionManager.createSession(
                                    payload.queryId(),
                                    containerType,
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
                        String containerType = resolveContainerType(player.level(), payload.packedBlockPos());
                        ChestLogPagePayload response = toPayload(payload.queryId(), containerType, payload.dimension(), payload.packedBlockPos(), sessionPage);
                        ServerPlayNetworking.send(player, response);
                    }
                }
        );
    }

    private static String resolveContainerType(net.minecraft.world.level.Level level, long packedPos) {
        if (level != null) {
            try {
                int x = BlockPosUtil.unpackX(packedPos);
                int y = BlockPosUtil.unpackY(packedPos);
                int z = BlockPosUtil.unpackZ(packedPos);
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
                net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                if (state != null && state.getBlock() != null) {
                    String name = state.getBlock().getName().getString();
                    if (name != null && !name.isBlank() && !name.equalsIgnoreCase("Air")) {
                        return name;
                    }
                }
            } catch (Exception ignored) {}
        }
        return "Container";
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
