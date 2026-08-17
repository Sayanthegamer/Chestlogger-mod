package com.chestlogger.command;

import com.chestlogger.ChestLoggerMod;
import com.chestlogger.container.ContainerSnapshot;
import com.chestlogger.container.ContainerTracker;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.index.IndexQueryFilter;
import com.chestlogger.query.PagedResult;
import com.chestlogger.query.TransactionFormatter;
import com.chestlogger.rollback.RollbackPlan;
import com.chestlogger.rollback.RollbackResult;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.chestlogger.network.ChestLogPagePayload;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Administrative Brigadier command suite for ChestLogger.
 */
public final class ChestLoggerCommands {
    private static final ConcurrentHashMap<UUID, String> PENDING_PURGE_TOKENS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, PendingRollback> PENDING_ROLLBACKS = new ConcurrentHashMap<>();

    private record PendingRollback(String token, BlockPos pos, int seconds, UUID targetPlayer) {}

    private ChestLoggerCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
        });
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        var inspectNode = Commands.literal("inspect")
                .executes(ctx -> {
                    if (ctx.getSource().isPlayer()) {
                        return executeToggleInspect(ctx.getSource());
                    }
                    return executeInspect(ctx.getSource(), ctx.getSource().getPlayerOrException().blockPosition(), null, 0, 1);
                })
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> executeInspect(ctx.getSource(), ctx.getSource().getPlayerOrException().blockPosition(), null, 0, IntegerArgumentType.getInteger(ctx, "page"))))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> executeInspect(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos"), null, 0, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> executeInspect(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos"), null, 0, IntegerArgumentType.getInteger(ctx, "page"))))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> executeInspect(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos"), EntityArgument.getPlayer(ctx, "player").getUUID(), 0, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> executeInspect(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos"), EntityArgument.getPlayer(ctx, "player").getUUID(), 0, IntegerArgumentType.getInteger(ctx, "page"))))));

        var iNode = Commands.literal("i")
                .executes(ctx -> executeToggleInspect(ctx.getSource()))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> executeInspect(ctx.getSource(), ctx.getSource().getPlayerOrException().blockPosition(), null, 0, IntegerArgumentType.getInteger(ctx, "page"))))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> executeInspect(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos"), null, 0, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> executeInspect(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos"), null, 0, IntegerArgumentType.getInteger(ctx, "page")))));

        var wandNode = Commands.literal("wand")
                .executes(ctx -> executeWandInfo(ctx.getSource()));

        var mainCommand = Commands.literal("chestlog")
                .requires(source -> !source.isPlayer() || source.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(source.getPlayer().getGameProfile())))
                .then(inspectNode)
                .then(iNode)
                .then(wandNode)
                // --- ROLLBACK ---
                .then(Commands.literal("rollback")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 864000))
                                        .executes(ctx -> executeRollbackPreview(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos"), IntegerArgumentType.getInteger(ctx, "seconds"), null))
                                        .then(Commands.literal("confirm")
                                                .then(Commands.argument("token", StringArgumentType.string())
                                                        .executes(ctx -> executeRollbackConfirm(ctx.getSource(), StringArgumentType.getString(ctx, "token")))))
                                        .then(Commands.argument("targetPlayer", EntityArgument.player())
                                                .executes(ctx -> executeRollbackPreview(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos"), IntegerArgumentType.getInteger(ctx, "seconds"), EntityArgument.getPlayer(ctx, "targetPlayer").getUUID()))))))
                // --- STATS ---
                .then(Commands.literal("stats")
                        .executes(ctx -> executeStats(ctx.getSource())))
                // --- PURGE ---
                .then(Commands.literal("purge")
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 3650))
                                .executes(ctx -> executePurgeRequest(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "days")))
                                .then(Commands.argument("confirmToken", StringArgumentType.string())
                                        .executes(ctx -> executePurgeConfirm(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "days"), StringArgumentType.getString(ctx, "confirmToken"))))));

        dispatcher.register(mainCommand);
        dispatcher.register(Commands.literal("cl")
                .requires(source -> !source.isPlayer() || source.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(source.getPlayer().getGameProfile())))
                .redirect(dispatcher.getRoot().getChild("chestlog")));
    }

    private static int executeInspect(CommandSourceStack source, BlockPos pos, UUID filterPlayer, int durationSeconds, int page) {
        try {
            long packed = BlockPosUtil.pack(pos.getX(), pos.getY(), pos.getZ());
            String dim = source.getLevel().dimension().identifier().toString();

            long minTime = durationSeconds > 0 ? (System.currentTimeMillis() - durationSeconds * 1000L) : 0L;

            IndexQueryFilter.Builder builder = IndexQueryFilter.builder()
                    .dimension(dim)
                    .exactBlockPos(packed)
                    .timeRange(minTime, Long.MAX_VALUE)
                    .limit(500);

            if (filterPlayer != null) {
                builder.actorUuid(filterPlayer);
            }

            if (ChestLoggerMod.getQueryEngine() == null) {
                source.sendFailure(Component.literal("ChestLogger query engine is not initialized yet."));
                return 0;
            }

            BlockEntity be = source.getLevel().getBlockEntity(pos);
            String containerType = (be != null) ? be.getBlockState().getBlock().getName().getString() : "Container";

            List<TransactionLogEntry> allMatches = ChestLoggerMod.getQueryEngine().fetchRecords(builder.limit(1000).build());

            if (source.getEntity() instanceof ServerPlayer player) {
                UUID queryId = UUID.randomUUID();
                com.chestlogger.query.PagedResult<com.chestlogger.query.DisplayRecord> sessionPage = ChestLoggerMod.getSessionManager().createSession(
                        queryId, containerType, dim, packed, allMatches, page
                );
                List<com.chestlogger.network.DisplayRecord> netRecords = sessionPage.items().stream()
                        .map(r -> new com.chestlogger.network.DisplayRecord(
                                r.sequenceId(), r.timestampMs(), r.actorUuid(), r.actorName(),
                                r.actorType(), r.actionType(), r.slotIndex(), r.itemId(),
                                r.quantityDelta(), r.metadataFingerprint(), r.dimension(), r.packedBlockPos()
                        )).toList();

                ChestLogPagePayload pagePayload = new ChestLogPagePayload(
                        queryId, sessionPage.pageNumber(), sessionPage.totalPages(), sessionPage.totalElements(),
                        containerType, dim, packed, netRecords
                );
                ServerPlayNetworking.send(player, pagePayload);
            }

            PagedResult<TransactionLogEntry> result = PagedResult.of(allMatches, page, 8);

            if (result.totalElements() == 0) {
                source.sendSuccess(() -> Component.literal(String.format("§eNo container records found at (%d, %d, %d).", pos.getX(), pos.getY(), pos.getZ())), false);
                return 1;
            }

            source.sendSuccess(() -> Component.literal(String.format("§6=== ChestLog (%d, %d, %d) [Page %d/%d - %d records] ===",
                    pos.getX(), pos.getY(), pos.getZ(), result.pageNumber(), result.totalPages(), result.totalElements())), false);

            for (TransactionLogEntry entry : result.items()) {
                String line = TransactionFormatter.formatLine(entry);
                source.sendSuccess(() -> Component.literal("§7" + line), false);
            }

            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Query failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int executeRollbackPreview(CommandSourceStack source, BlockPos pos, int seconds, UUID targetPlayer) {
        try {
            long packed = BlockPosUtil.pack(pos.getX(), pos.getY(), pos.getZ());
            String dim = source.getLevel().dimension().identifier().toString();
            long minTime = System.currentTimeMillis() - (seconds * 1000L);

            IndexQueryFilter.Builder filterBuilder = IndexQueryFilter.builder()
                    .dimension(dim)
                    .exactBlockPos(packed)
                    .timeRange(minTime, Long.MAX_VALUE);

            if (targetPlayer != null) {
                filterBuilder.actorUuid(targetPlayer);
            }

            List<TransactionLogEntry> history = ChestLoggerMod.getQueryEngine().fetchRecords(filterBuilder.build());
            if (history.isEmpty()) {
                source.sendSuccess(() -> Component.literal("§eNo transactions found matching the rollback criteria."), false);
                return 1;
            }

            BlockEntity be = source.getLevel().getBlockEntity(pos);
            ContainerSnapshot currentSnapshot;
            if (be instanceof Container c) {
                currentSnapshot = ContainerTracker.capture(c);
            } else {
                currentSnapshot = new ContainerSnapshot(27);
            }

            RollbackPlan plan = ChestLoggerMod.getRollbackEngine().createPlan(history, currentSnapshot);

            String token = UUID.randomUUID().toString().substring(0, 6);
            UUID sender = (source.getEntity() instanceof ServerPlayer sp) ? sp.getUUID() : new UUID(0L, 0L);
            PENDING_ROLLBACKS.put(sender, new PendingRollback(token, pos, seconds, targetPlayer));

            source.sendSuccess(() -> Component.literal(String.format("§6=== Rollback Preview (%d items to compensate, %d conflicts) ===",
                    plan.steps().size(), plan.conflictCount())), false);
            source.sendSuccess(() -> Component.literal(String.format("§eTo apply compensation, run: §f/chestlog rollback %d %d %d %d confirm %s",
                    pos.getX(), pos.getY(), pos.getZ(), seconds, token)), false);

            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Rollback preview failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int executeRollbackConfirm(CommandSourceStack source, String token) {
        try {
            UUID sender = (source.getEntity() instanceof ServerPlayer sp) ? sp.getUUID() : new UUID(0L, 0L);
            PendingRollback pending = PENDING_ROLLBACKS.remove(sender);

            if (pending == null || !pending.token().equalsIgnoreCase(token)) {
                source.sendFailure(Component.literal("Invalid or expired rollback token."));
                return 0;
            }

            BlockPos pos = pending.pos();
            long packed = BlockPosUtil.pack(pos.getX(), pos.getY(), pos.getZ());
            String dim = source.getLevel().dimension().identifier().toString();
            long minTime = System.currentTimeMillis() - (pending.seconds() * 1000L);

            IndexQueryFilter.Builder filterBuilder = IndexQueryFilter.builder()
                    .dimension(dim)
                    .exactBlockPos(packed)
                    .timeRange(minTime, Long.MAX_VALUE);

            if (pending.targetPlayer() != null) {
                filterBuilder.actorUuid(pending.targetPlayer());
            }

            List<TransactionLogEntry> history = ChestLoggerMod.getQueryEngine().fetchRecords(filterBuilder.build());
            BlockEntity be = source.getLevel().getBlockEntity(pos);
            if (!(be instanceof Container c)) {
                source.sendFailure(Component.literal("Target block entity is not a container."));
                return 0;
            }

            ContainerSnapshot snapshot = ContainerTracker.capture(c);
            RollbackPlan plan = ChestLoggerMod.getRollbackEngine().createPlan(history, snapshot);

            String adminName = source.getTextName();
            RollbackResult result = ChestLoggerMod.getRollbackEngine().applyRollback(
                    plan, snapshot, ChestLoggerMod.getEventQueue(), sender, adminName, dim, packed
            );

            source.sendSuccess(() -> Component.literal(String.format("§aRollback applied successfully: %d slot compensations executed.", result.appliedSteps())), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Rollback execution failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int executeStats(CommandSourceStack source) {
        var queue = ChestLoggerMod.getEventQueue();
        var index = ChestLoggerMod.getIndexManager();

        source.sendSuccess(() -> Component.literal("§6=== ChestLogger Production Telemetry ==="), false);
        source.sendSuccess(() -> Component.literal(String.format("§7Queue Depth: §a%d§7 / %d", queue.getDepth(), queue.getCapacity())), false);
        source.sendSuccess(() -> Component.literal(String.format("§7Total Enqueued: §a%d", queue.getEnqueuedCount())), false);
        source.sendSuccess(() -> Component.literal(String.format("§7Dropped (Overflow): §c%d", queue.getDroppedCount())), false);
        source.sendSuccess(() -> Component.literal(String.format("§7Drained to Disk: §a%d", queue.getDrainedCount())), false);
        if (index != null) {
            source.sendSuccess(() -> Component.literal(String.format("§7Indexed Pointers: §a%d", index.size())), false);
        }
        return 1;
    }

    private static int executePurgeRequest(CommandSourceStack source, int days) {
        String token = UUID.randomUUID().toString().substring(0, 6);
        UUID sender = (source.getEntity() instanceof ServerPlayer sp) ? sp.getUUID() : new UUID(0L, 0L);
        PENDING_PURGE_TOKENS.put(sender, token);

        source.sendSuccess(() -> Component.literal(String.format("§c[WARNING] Purging logs older than %d days is irreversible!", days)), false);
        source.sendSuccess(() -> Component.literal(String.format("§eTo confirm, run: §f/chestlog purge %d %s", days, token)), false);
        return 1;
    }

    private static int executePurgeConfirm(CommandSourceStack source, int days, String token) {
        UUID sender = (source.getEntity() instanceof ServerPlayer sp) ? sp.getUUID() : new UUID(0L, 0L);
        String expected = PENDING_PURGE_TOKENS.remove(sender);

        if (expected == null || !expected.equalsIgnoreCase(token)) {
            source.sendFailure(Component.literal("Invalid or expired confirmation token."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(String.format("§aPurge confirmed. Trimming log segments older than %d days...", days)), true);
        return 1;
    }

    private static int executeToggleInspect(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            boolean active = ChestLoggerMod.getInspectModeManager().toggleInspect(player.getUUID());
            if (active) {
                source.sendSuccess(() -> Component.literal("§a[ChestLogger] Inspect mode enabled. Left-click a container to inspect chat, right-click to inspect GUI."), false);
            } else {
                source.sendSuccess(() -> Component.literal("§e[ChestLogger] Inspect mode disabled."), false);
            }
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only players can toggle inspect mode."));
            return 0;
        }
    }

    private static int executeWandInfo(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("§6=== [ChestLogger Wand Info] ==="), false);
        source.sendSuccess(() -> Component.literal("§eWand Item: §b" + ChestLoggerMod.getInspectModeManager().getConfig().getWandItem()), false);
        source.sendSuccess(() -> Component.literal("§eMode: §fLeft-click container for Chat history, Right-click for GUI history."), false);
        source.sendSuccess(() -> Component.literal("§7Tip: Use /chestlog i to toggle click-inspection without holding the wand."), false);
        return 1;
    }
}
