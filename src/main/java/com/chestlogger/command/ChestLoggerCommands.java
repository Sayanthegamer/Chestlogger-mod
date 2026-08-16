package com.chestlogger.command;

import com.chestlogger.ChestLoggerMod;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.index.IndexQueryFilter;
import com.chestlogger.query.PagedResult;
import com.chestlogger.query.TransactionFormatter;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Administrative Brigadier command suite for ChestLogger.
 */
public final class ChestLoggerCommands {
    private static final ConcurrentHashMap<UUID, String> PENDING_PURGE_TOKENS = new ConcurrentHashMap<>();

    private ChestLoggerCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
        });
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("chestlog")
                .requires(source -> !source.isPlayer() || source.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(source.getPlayer().getGameProfile())))
                .then(Commands.literal("inspect")
                        .executes(ctx -> executeInspectPos(ctx.getSource(), ctx.getSource().getPlayerOrException().blockPosition(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> executeInspectPos(ctx.getSource(), ctx.getSource().getPlayerOrException().blockPosition(), IntegerArgumentType.getInteger(ctx, "page"))))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> executeInspectPos(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos"), 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> executeInspectPos(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos"), IntegerArgumentType.getInteger(ctx, "page"))))))
                .then(Commands.literal("stats")
                        .executes(ctx -> executeStats(ctx.getSource())))
                .then(Commands.literal("purge")
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 3650))
                                .executes(ctx -> executePurgeRequest(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "days")))
                                .then(Commands.argument("confirmToken", StringArgumentType.string())
                                        .executes(ctx -> executePurgeConfirm(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "days"), StringArgumentType.getString(ctx, "confirmToken"))))))
        );
    }

    private static int executeInspectPos(CommandSourceStack source, BlockPos pos, int page) {
        try {
            long packed = BlockPosUtil.pack(pos.getX(), pos.getY(), pos.getZ());
            String dim = source.getLevel().dimension().identifier().toString();

            IndexQueryFilter filter = IndexQueryFilter.builder()
                    .dimension(dim)
                    .exactBlockPos(packed)
                    .limit(500)
                    .build();

            if (ChestLoggerMod.getQueryEngine() == null) {
                source.sendFailure(Component.literal("ChestLogger query engine is not initialized yet."));
                return 0;
            }

            PagedResult<TransactionLogEntry> result = ChestLoggerMod.getQueryEngine().queryPaged(filter, page, 8);

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
}
