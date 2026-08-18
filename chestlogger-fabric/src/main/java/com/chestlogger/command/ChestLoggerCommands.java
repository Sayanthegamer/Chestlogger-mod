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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import com.chestlogger.provenance.ItemProvenanceResolver;
import com.chestlogger.provenance.ProvenanceGraph;
import com.chestlogger.provenance.ProvenanceNode;

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

        var traceNode = Commands.literal("trace")
                .executes(ctx -> executeTraceHand(ctx.getSource()))
                .then(Commands.literal("hand")
                        .executes(ctx -> executeTraceHand(ctx.getSource())))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> executeTracePos(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos"), 0))
                        .then(Commands.argument("slot", IntegerArgumentType.integer(0, 54))
                                .executes(ctx -> executeTracePos(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos"), IntegerArgumentType.getInteger(ctx, "slot")))));

        var trustNode = Commands.literal("trust")
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            var server = ctx.getSource().getServer();
                            if (server != null) {
                                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                                    if (ctx.getSource().getEntity() instanceof ServerPlayer senderPlayer && p.getUUID().equals(senderPlayer.getUUID())) {
                                        continue;
                                    }
                                    String name = p.getGameProfile().name();
                                    if (name.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                        builder.suggest(name);
                                    }
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> executeTrust(ctx.getSource(), StringArgumentType.getString(ctx, "player"))));

        var untrustNode = Commands.literal("untrust")
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            var server = ctx.getSource().getServer();
                            if (server != null && ctx.getSource().getEntity() instanceof ServerPlayer senderPlayer && ChestLoggerMod.getTrustManager() != null) {
                                Set<UUID> list = ChestLoggerMod.getTrustManager().getTrustList(senderPlayer.getUUID());
                                for (UUID uuid : list) {
                                    ServerPlayer onlineP = server.getPlayerList().getPlayer(uuid);
                                    String name = onlineP != null ? onlineP.getGameProfile().name() : uuid.toString();
                                    if (name.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                        builder.suggest(name);
                                    }
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> executeUntrust(ctx.getSource(), StringArgumentType.getString(ctx, "player"))));

        var trustlistNode = Commands.literal("trustlist")
                .executes(ctx -> executeTrustList(ctx.getSource()));

        var claimNode = Commands.literal("claim")
                .requires(CommandSourceStack::isPlayer)
                .executes(ctx -> executeClaim(ctx.getSource(), 0))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                        .requires(source -> !source.isPlayer() || (source.getServer() != null && source.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(source.getPlayer().getGameProfile()))))
                        .executes(ctx -> executeClaim(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"))));

        var unclaimNode = Commands.literal("unclaim")
                .requires(CommandSourceStack::isPlayer)
                .executes(ctx -> executeUnclaim(ctx.getSource()));

        var mainCommand = Commands.literal("chestlog")
                .then(inspectNode)
                .then(iNode)
                .then(wandNode)
                .then(claimNode)
                .then(unclaimNode)
                .then(traceNode)
                .then(trustNode)
                .then(untrustNode)
                .then(trustlistNode)
                // --- ROLLBACK ---
                .then(Commands.literal("rollback")
                        .requires(source -> !source.isPlayer() || (source.getServer() != null && source.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(source.getPlayer().getGameProfile()))))
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
                        .requires(source -> !source.isPlayer() || (source.getServer() != null && source.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(source.getPlayer().getGameProfile()))))
                        .executes(ctx -> executeStats(ctx.getSource())))
                // --- PURGE ---
                .then(Commands.literal("purge")
                        .requires(source -> !source.isPlayer() || (source.getServer() != null && source.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(source.getPlayer().getGameProfile()))))
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 3650))
                                .executes(ctx -> executePurgeRequest(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "days")))
                                .then(Commands.argument("confirmToken", StringArgumentType.string())
                                        .executes(ctx -> executePurgeConfirm(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "days"), StringArgumentType.getString(ctx, "confirmToken"))))));

        dispatcher.register(mainCommand);
        dispatcher.register(Commands.literal("cl")
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

    private static int executeTraceHand(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            net.minecraft.world.item.ItemStack handStack = player.getMainHandItem();
            if (handStack.isEmpty()) {
                source.sendFailure(Component.literal("You must hold an item in your main hand to trace its provenance."));
                return 0;
            }

            String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(handStack.getItem()).toString();
            long fingerprint = com.chestlogger.event.MetadataFingerprint.EMPTY;
            if (!handStack.getComponents().isEmpty()) {
                fingerprint = com.chestlogger.event.MetadataFingerprint.compute(handStack.getComponents().toString().getBytes());
            }
            long finalFingerprint = fingerprint;
            String dim = source.getLevel().dimension().identifier().toString();

            source.sendSuccess(() -> Component.literal(String.format("§6[ChestLogger] Resolving provenance trace for §e%s§6...", itemId)), false);

            CompletableFuture.runAsync(() -> {
                try {
                    ItemProvenanceResolver resolver = new ItemProvenanceResolver();
                    ProvenanceGraph graph = resolver.resolveProvenance(0L, dim, itemId, finalFingerprint, ChestLoggerMod.getQueryEngine());

                    source.getServer().execute(() -> {
                        sendProvenanceResults(source, graph);
                    });
                } catch (Exception e) {
                    source.getServer().execute(() -> {
                        source.sendFailure(Component.literal("Trace resolution failed: " + e.getMessage()));
                    });
                }
            });
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only players can trace items in hand."));
            return 0;
        }
    }

    private static int executeTracePos(CommandSourceStack source, BlockPos pos, int slotIndex) {
        try {
            BlockEntity be = source.getLevel().getBlockEntity(pos);
            if (!(be instanceof Container container)) {
                source.sendFailure(Component.literal(String.format("Block at (%d, %d, %d) is not a container.", pos.getX(), pos.getY(), pos.getZ())));
                return 0;
            }

            if (slotIndex < 0 || slotIndex >= container.getContainerSize()) {
                source.sendFailure(Component.literal(String.format("Invalid slot %d for container of size %d.", slotIndex, container.getContainerSize())));
                return 0;
            }

            net.minecraft.world.item.ItemStack stack = container.getItem(slotIndex);
            if (stack.isEmpty()) {
                source.sendFailure(Component.literal(String.format("Slot %d is empty in container at (%d, %d, %d).", slotIndex, pos.getX(), pos.getY(), pos.getZ())));
                return 0;
            }

            String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            long fingerprint = com.chestlogger.event.MetadataFingerprint.EMPTY;
            if (!stack.getComponents().isEmpty()) {
                fingerprint = com.chestlogger.event.MetadataFingerprint.compute(stack.getComponents().toString().getBytes());
            }
            long finalFingerprint = fingerprint;
            long packedPos = BlockPosUtil.pack(pos.getX(), pos.getY(), pos.getZ());
            String dim = source.getLevel().dimension().identifier().toString();

            source.sendSuccess(() -> Component.literal(String.format("§6[ChestLogger] Resolving provenance trace for §e%s§6 at (%d, %d, %d) slot %d...",
                    itemId, pos.getX(), pos.getY(), pos.getZ(), slotIndex)), false);

            CompletableFuture.runAsync(() -> {
                try {
                    ItemProvenanceResolver resolver = new ItemProvenanceResolver();
                    ProvenanceGraph graph = resolver.resolveProvenance(packedPos, dim, itemId, finalFingerprint, ChestLoggerMod.getQueryEngine());

                    source.getServer().execute(() -> {
                        sendProvenanceResults(source, graph);
                    });
                } catch (Exception e) {
                    source.getServer().execute(() -> {
                        source.sendFailure(Component.literal("Trace resolution failed: " + e.getMessage()));
                    });
                }
            });
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Trace command failed: " + e.getMessage()));
            return 0;
        }
    }

    private static void sendProvenanceResults(CommandSourceStack source, ProvenanceGraph graph) {
        if (graph == null || graph.isEmpty()) {
            source.sendSuccess(() -> Component.literal(String.format("§eNo provenance records found for %s.", graph != null ? graph.targetItemId() : "item")), false);
            return;
        }

        source.sendSuccess(() -> Component.literal(String.format("§6=== Provenance Trace: %s (%d steps, %s) ===",
                graph.targetItemId(), graph.totalSteps(), graph.overallConfidence().name())), false);

        for (ProvenanceNode node : graph.nodes()) {
            int[] coords = BlockPosUtil.unpack(node.packedPos());
            String confBadge = switch (node.confidence()) {
                case EXACT_LINKAGE -> "§a[EXACT]";
                case HIGH_CONFIDENCE -> "§e[HIGH]";
                case PROBABLE -> "§6[PROB]";
            };
            String deltaStr = (node.deltaQuantity() > 0 ? "§a+" : "§c") + node.deltaQuantity();
            String line = String.format("§e#%d %s §7%s %s §7by §e%s §7at §b[%d, %d, %d] §7(%s)",
                    node.stepIndex() + 1, confBadge, node.actionType().name(), deltaStr,
                    node.actorName(), coords[0], coords[1], coords[2], node.dimension());
            source.sendSuccess(() -> Component.literal(line), false);
        }

        // Open Screen GUI on player client
        if (source.isPlayer()) {
            try {
                ServerPlayer player = source.getPlayer();
                if (player != null && ChestLoggerMod.getSessionManager() != null) {
                    List<TransactionLogEntry> txEntries = new java.util.ArrayList<>();
                    for (ProvenanceNode node : graph.nodes()) {
                        txEntries.add(new TransactionLogEntry(
                                node.sequenceId(),
                                node.timestampMs(),
                                UUID.randomUUID(),
                                node.actionType(),
                                node.actorType(),
                                node.actorUuid(),
                                node.actorName(),
                                node.dimension(),
                                node.packedPos(),
                                List.of(new com.chestlogger.event.SlotDelta(0, node.itemId(), node.deltaQuantity(), 0, Math.abs(node.deltaQuantity()), node.metadataFingerprint()))
                        ));
                    }

                    UUID queryId = UUID.randomUUID();
                    String containerTitle = "Trace: " + graph.targetItemId() + " (" + graph.overallConfidence().name() + ")";
                    String dim = source.getLevel().dimension().identifier().toString();
                    var sessionPage = ChestLoggerMod.getSessionManager().createSession(
                            queryId, containerTitle, dim, graph.targetPackedPos(), txEntries, 1
                    );

                    List<com.chestlogger.network.DisplayRecord> netRecords = sessionPage.items().stream()
                            .map(r -> new com.chestlogger.network.DisplayRecord(
                                    r.sequenceId(), r.timestampMs(), r.actorUuid(), r.actorName(),
                                    r.actorType(), r.actionType(), r.slotIndex(), r.itemId(),
                                    r.quantityDelta(), r.metadataFingerprint(), r.dimension(), r.packedBlockPos()
                            )).toList();

                    ChestLogPagePayload pagePayload = new ChestLogPagePayload(
                            queryId, sessionPage.pageNumber(), sessionPage.totalPages(), sessionPage.totalElements(),
                            containerTitle, dim, graph.targetPackedPos(), netRecords
                    );
                    ServerPlayNetworking.send(player, pagePayload);
                }
            } catch (Exception ignored) {}
        }
    }

    private static int executeTrust(CommandSourceStack source, String targetName) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Trust commands can only be executed by players."));
            return 0;
        }

        if (targetName.equalsIgnoreCase(player.getGameProfile().name())) {
            source.sendFailure(Component.literal("§c[ChestLogger] You cannot trust yourself!"));
            return 0;
        }

        var server = source.getServer();
        ServerPlayer targetPlayer = server.getPlayerList().getPlayerByName(targetName);
        UUID targetUuid;
        String finalTargetName;
        if (targetPlayer != null) {
            targetUuid = targetPlayer.getUUID();
            finalTargetName = targetPlayer.getGameProfile().name();
        } else {
            targetUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + targetName.toLowerCase(Locale.ROOT)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            finalTargetName = targetName;
        }

        if (player.getUUID().equals(targetUuid)) {
            source.sendFailure(Component.literal("§c[ChestLogger] You cannot trust yourself!"));
            return 0;
        }

        var trustManager = ChestLoggerMod.getTrustManager();
        if (trustManager == null) {
            source.sendFailure(Component.literal("§c[ChestLogger] Trust manager is not initialized."));
            return 0;
        }

        if (trustManager.isTrusted(player.getUUID(), targetUuid)) {
            source.sendSuccess(() -> Component.literal("§e[ChestLogger] " + finalTargetName + " is already in your trust list."), false);
            return 1;
        }

        boolean added = trustManager.trust(player.getUUID(), targetUuid);
        if (added) {
            try {
                trustManager.save();
            } catch (Exception e) {
                ChestLoggerMod.LOGGER.warn("Failed to save trust_data.json: {}", e.getMessage());
            }
            source.sendSuccess(() -> Component.literal("§a[ChestLogger] Successfully trusted " + finalTargetName + ". They can now access your containers without triggering alerts."), false);
        } else {
            source.sendSuccess(() -> Component.literal("§e[ChestLogger] " + finalTargetName + " is already in your trust list."), false);
        }
        return 1;
    }

    private static int executeUntrust(CommandSourceStack source, String targetName) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Trust commands can only be executed by players."));
            return 0;
        }

        var trustManager = ChestLoggerMod.getTrustManager();
        if (trustManager == null) {
            source.sendFailure(Component.literal("§c[ChestLogger] Trust manager is not initialized."));
            return 0;
        }

        var server = source.getServer();
        UUID targetUuid = null;
        String resolvedName = targetName;

        Set<UUID> trusted = trustManager.getTrustList(player.getUUID());
        for (UUID u : trusted) {
            ServerPlayer onlineP = server.getPlayerList().getPlayer(u);
            if (onlineP != null && onlineP.getGameProfile().name().equalsIgnoreCase(targetName)) {
                targetUuid = u;
                resolvedName = onlineP.getGameProfile().name();
                break;
            }
        }

        if (targetUuid == null) {
            ServerPlayer targetPlayer = server.getPlayerList().getPlayerByName(targetName);
            if (targetPlayer != null) {
                targetUuid = targetPlayer.getUUID();
                resolvedName = targetPlayer.getGameProfile().name();
            } else {
                targetUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + targetName.toLowerCase(Locale.ROOT)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }

        final String targetDisplayName = resolvedName;
        boolean removed = trustManager.untrust(player.getUUID(), targetUuid);
        if (removed) {
            try {
                trustManager.save();
            } catch (Exception e) {
                ChestLoggerMod.LOGGER.warn("Failed to save trust_data.json: {}", e.getMessage());
            }
            source.sendSuccess(() -> Component.literal("§a[ChestLogger] Successfully untrusted " + targetDisplayName + "."), false);
        } else {
            source.sendSuccess(() -> Component.literal("§e[ChestLogger] " + targetDisplayName + " is not in your trust list."), false);
        }
        return 1;
    }

    private static int executeTrustList(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Trust commands can only be executed by players."));
            return 0;
        }

        var trustManager = ChestLoggerMod.getTrustManager();
        if (trustManager == null) {
            source.sendFailure(Component.literal("§c[ChestLogger] Trust manager is not initialized."));
            return 0;
        }

        Set<UUID> list = trustManager.getTrustList(player.getUUID());
        if (list.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§e[ChestLogger] You have not trusted any players yet. Use /chestlog trust <player> to add someone."), false);
            return 1;
        }

        var server = source.getServer();
        List<String> names = new ArrayList<>();
        for (UUID u : list) {
            ServerPlayer targetOnline = server.getPlayerList().getPlayer(u);
            if (targetOnline != null) {
                names.add(targetOnline.getGameProfile().name());
            } else {
                names.add(u.toString());
            }
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);

        source.sendSuccess(() -> Component.literal("§6=== Trusted Players (" + list.size() + ") ==="), false);
        source.sendSuccess(() -> Component.literal("§f" + String.join(", ", names)), false);
        return 1;
    }

    private static int executeClaim(CommandSourceStack source, int radius) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("§cThis command can only be executed by a player."));
            return 0;
        }

        var claimManager = ChestLoggerMod.getClaimManager();
        if (claimManager == null) {
            source.sendFailure(Component.literal("§c[ChestLogger] Claim manager is not active."));
            return 0;
        }

        String dim = player.level().dimension().identifier().toString();

        if (radius > 0) {
            boolean isOp = source.getServer() != null && source.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(player.getGameProfile()));
            if (!isOp) {
                source.sendFailure(Component.literal("§c[ChestLogger] Radius claiming requires admin permissions."));
                return 0;
            }

            BlockPos center = player.blockPosition();
            int claimed = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                            BlockPos p = center.offset(dx, dy, dz);
                            BlockEntity be = player.level().getBlockEntity(p);
                            if (be instanceof Container) {
                                long packed = BlockPosUtil.pack(p.getX(), p.getY(), p.getZ());
                                claimManager.claim(dim, packed, player.getUUID(), player.getName().getString());
                                claimed++;
                            }
                        }
                    }
                }
            }
            final int count = claimed;
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "§a[ChestLogger] Batch claimed %d container(s) within %d blocks.", count, radius)), true);
            return 1;
        }

        // Raycast / line-of-sight check for targeted container block
        net.minecraft.world.phys.HitResult hit = player.pick(6.0D, 0.0F, false);
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("§c[ChestLogger] You must be looking at a container to claim it."));
            return 0;
        }

        BlockPos targetPos = ((net.minecraft.world.phys.BlockHitResult) hit).getBlockPos();
        BlockEntity be = player.level().getBlockEntity(targetPos);
        if (!(be instanceof Container)) {
            source.sendFailure(Component.literal("§c[ChestLogger] Target block is not a container."));
            return 0;
        }

        long packed = BlockPosUtil.pack(targetPos.getX(), targetPos.getY(), targetPos.getZ());
        UUID existingOwner = claimManager.getOwner(dim, packed);
        String existingName = claimManager.getOwnerName(dim, packed);
        boolean isOp = source.getServer() != null && source.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(player.getGameProfile()));

        if (existingOwner != null && !existingOwner.equals(player.getUUID()) && !isOp) {
            source.sendFailure(Component.literal("§c[ChestLogger] Container is already claimed by " + (existingName != null ? existingName : "another player") + "!"));
            return 0;
        }

        Long partnerPacked = findDoubleChestPartner(player.level(), targetPos);
        if (partnerPacked != null) {
            claimManager.claim(dim, packed, partnerPacked, player.getUUID(), player.getName().getString());
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "§a[ChestLogger] Double chest at [%d, %d, %d] successfully claimed!", targetPos.getX(), targetPos.getY(), targetPos.getZ())), false);
        } else {
            claimManager.claim(dim, packed, player.getUUID(), player.getName().getString());
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "§a[ChestLogger] Container at [%d, %d, %d] successfully claimed!", targetPos.getX(), targetPos.getY(), targetPos.getZ())), false);
        }
        return 1;
    }

    private static int executeUnclaim(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("§cThis command can only be executed by a player."));
            return 0;
        }

        var claimManager = ChestLoggerMod.getClaimManager();
        if (claimManager == null) {
            source.sendFailure(Component.literal("§c[ChestLogger] Claim manager is not active."));
            return 0;
        }

        net.minecraft.world.phys.HitResult hit = player.pick(6.0D, 0.0F, false);
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("§c[ChestLogger] You must be looking at a container to unclaim it."));
            return 0;
        }

        BlockPos targetPos = ((net.minecraft.world.phys.BlockHitResult) hit).getBlockPos();
        String dim = player.level().dimension().identifier().toString();
        long packed = BlockPosUtil.pack(targetPos.getX(), targetPos.getY(), targetPos.getZ());

        if (!claimManager.isClaimed(dim, packed)) {
            source.sendFailure(Component.literal("§e[ChestLogger] This container is not claimed."));
            return 0;
        }

        UUID existingOwner = claimManager.getOwner(dim, packed);
        boolean isOp = source.getServer() != null && source.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(player.getGameProfile()));

        if (existingOwner != null && !existingOwner.equals(player.getUUID()) && !isOp) {
            source.sendFailure(Component.literal("§c[ChestLogger] You do not own this container!"));
            return 0;
        }

        claimManager.unclaim(dim, packed);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "§a[ChestLogger] Container claim removed from [%d, %d, %d].", targetPos.getX(), targetPos.getY(), targetPos.getZ())), false);
        return 1;
    }

    public static Long findDoubleChestPartner(net.minecraft.world.level.Level level, BlockPos pos) {
        if (level == null || pos == null) return null;
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
            net.minecraft.world.level.block.state.properties.ChestType type = state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE);
            if (type == net.minecraft.world.level.block.state.properties.ChestType.LEFT || type == net.minecraft.world.level.block.state.properties.ChestType.RIGHT) {
                net.minecraft.core.Direction connectedDir = net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state);
                BlockPos partnerPos = pos.relative(connectedDir);
                if (level.getBlockState(partnerPos).is(state.getBlock())) {
                    return BlockPosUtil.pack(partnerPos.getX(), partnerPos.getY(), partnerPos.getZ());
                }
            }
        }
        return null;
    }
}
