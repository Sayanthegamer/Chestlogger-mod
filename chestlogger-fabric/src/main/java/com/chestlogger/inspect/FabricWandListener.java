package com.chestlogger.inspect;

import com.chestlogger.ChestLoggerMod;
import com.chestlogger.command.ChestLoggerCommands;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.index.IndexQueryFilter;
import com.chestlogger.network.ChestLogNetworking;
import com.chestlogger.network.ChestLogPagePayload;
import com.chestlogger.query.PagedResult;
import com.chestlogger.query.QueryEngine;
import com.chestlogger.query.TransactionFormatter;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;
import java.util.UUID;

/**
 * Fabric event listener handling interactive wand and click-to-inspect gestures.
 */
public final class FabricWandListener {

    private FabricWandListener() {}

    public static void register() {
        // Right-click callback (Opens inspection GUI)
        UseBlockCallback.EVENT.register((Player player, Level world, InteractionHand hand, BlockHitResult hitResult) -> {
            if (hand != InteractionHand.MAIN_HAND || world.isClientSide()) {
                return InteractionResult.PASS;
            }

            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }

            if (serverPlayer.level() instanceof net.minecraft.server.level.ServerLevel serverLevel && !serverLevel.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(serverPlayer.getGameProfile()))) {
                return InteractionResult.PASS;
            }

            InspectModeManager manager = ChestLoggerMod.getInspectModeManager();
            ItemStack heldItem = player.getMainHandItem();
            String itemId = BuiltInRegistries.ITEM.getKey(heldItem.getItem()).toString();

            if (!manager.shouldInspect(player.getUUID(), itemId)) {
                return InteractionResult.PASS;
            }

            BlockPos pos = hitResult.getBlockPos();
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (!(blockEntity instanceof Container)) {
                return InteractionResult.PASS;
            }

            long packed = BlockPosUtil.pack(pos.getX(), pos.getY(), pos.getZ());
            if (!manager.tryDebounce(player.getUUID(), packed)) {
                return InteractionResult.SUCCESS;
            }

            // Right-click opens Fabric GUI
            openGuiInspection(serverPlayer, pos);
            return InteractionResult.SUCCESS;
        });

        // Left-click / attack callback (Prints chat inspection)
        AttackBlockCallback.EVENT.register((Player player, Level world, InteractionHand hand, BlockPos pos, net.minecraft.core.Direction direction) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }

            if (serverPlayer.level() instanceof net.minecraft.server.level.ServerLevel serverLevel && !serverLevel.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(serverPlayer.getGameProfile()))) {
                return InteractionResult.PASS;
            }

            InspectModeManager manager = ChestLoggerMod.getInspectModeManager();
            ItemStack heldItem = player.getMainHandItem();
            String itemId = BuiltInRegistries.ITEM.getKey(heldItem.getItem()).toString();

            if (!manager.shouldInspect(player.getUUID(), itemId)) {
                return InteractionResult.PASS;
            }

            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (!(blockEntity instanceof Container)) {
                return InteractionResult.PASS;
            }

            long packed = BlockPosUtil.pack(pos.getX(), pos.getY(), pos.getZ());
            if (!manager.tryDebounce(player.getUUID(), packed)) {
                return InteractionResult.SUCCESS;
            }

            // Left-click prints chat inspection
            performChatInspection(serverPlayer, pos);
            return InteractionResult.SUCCESS;
        });

        // Container destruction tracking
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (world.isClientSide()) return true;
            if (blockEntity instanceof Container container) {
                java.util.List<com.chestlogger.event.SlotDelta> deltas = new java.util.ArrayList<>();
                int size = container.getContainerSize();
                for (int i = 0; i < size; i++) {
                    ItemStack stack = container.getItem(i);
                    if (!stack.isEmpty()) {
                        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                        deltas.add(new com.chestlogger.event.SlotDelta(i, itemId, -stack.getCount(), stack.getCount(), 0, 0L));
                    }
                }
                if (!deltas.isEmpty()) {
                    long packed = BlockPosUtil.pack(pos.getX(), pos.getY(), pos.getZ());
                    String dim = world.dimension().identifier().toString();
                    TransactionLogEntry entry = new TransactionLogEntry(
                            ChestLoggerMod.getTracker().getNextSequenceId(),
                            System.currentTimeMillis(),
                            UUID.randomUUID(),
                            com.chestlogger.event.ActionType.CONTAINER_BREAK,
                            com.chestlogger.event.ActorType.PLAYER,
                            player.getUUID(),
                            player.getName().getString(),
                            dim,
                            packed,
                            deltas
                    );
                    ChestLoggerMod.getEventQueue().offer(entry);
                }
            }
            return true;
        });
    }

    private static void performChatInspection(ServerPlayer player, BlockPos pos) {
        String dim = player.level().dimension().identifier().toString();
        long packed = BlockPosUtil.pack(pos.getX(), pos.getY(), pos.getZ());

        player.sendSystemMessage(Component.literal("§6=== [ChestLogger] Inspecting container at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " ==="));

        QueryEngine engine = ChestLoggerMod.getQueryEngine();
        if (engine == null) {
            player.sendSystemMessage(Component.literal("§c[ChestLogger] Storage engine is not active."));
            return;
        }

        IndexQueryFilter filter = IndexQueryFilter.builder()
                .dimension(dim)
                .exactBlockPos(packed)
                .limit(100)
                .build();

        try {
            PagedResult<TransactionLogEntry> paged = engine.queryPaged(filter, 1, 6);
            if (paged.items().isEmpty()) {
                player.sendSystemMessage(Component.literal("§7No container transaction logs found for this position."));
            } else {
                for (TransactionLogEntry entry : paged.items()) {
                    player.sendSystemMessage(Component.literal("§f" + TransactionFormatter.formatLine(entry)));
                }
            }
        } catch (Exception e) {
            player.sendSystemMessage(Component.literal("§c[ChestLogger] Query failed: " + e.getMessage()));
        }
    }

    private static void openGuiInspection(ServerPlayer player, BlockPos pos) {
        String dim = player.level().dimension().identifier().toString();
        long packed = BlockPosUtil.pack(pos.getX(), pos.getY(), pos.getZ());
        QueryEngine engine = ChestLoggerMod.getQueryEngine();
        if (engine == null) {
            player.sendSystemMessage(Component.literal("§c[ChestLogger] Storage engine is not active."));
            return;
        }

        BlockEntity be = player.level().getBlockEntity(pos);
        String containerType = (be != null) ? be.getBlockState().getBlock().getName().getString() : "Container";

        IndexQueryFilter filter = IndexQueryFilter.builder()
                .dimension(dim)
                .exactBlockPos(packed)
                .limit(1000)
                .build();

        try {
            List<TransactionLogEntry> allMatches = engine.fetchRecords(filter);
            UUID queryId = UUID.randomUUID();
            com.chestlogger.query.PagedResult<com.chestlogger.query.DisplayRecord> sessionPage = ChestLoggerMod.getSessionManager().createSession(
                    queryId, containerType, dim, packed, allMatches, 1
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
        } catch (Exception e) {
            player.sendSystemMessage(Component.literal("§c[ChestLogger] Inspection GUI load failed: " + e.getMessage()));
        }
    }
}
