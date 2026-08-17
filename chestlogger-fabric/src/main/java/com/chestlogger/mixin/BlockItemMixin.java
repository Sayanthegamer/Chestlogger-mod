package com.chestlogger.mixin;

import com.chestlogger.ChestLoggerMod;
import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Injects into BlockItem.place to capture CONTAINER_PLACE events and establish
 * container ownership on Fabric servers.
 */
@Mixin(BlockItem.class)
public class BlockItemMixin {

    @Inject(method = "place", at = @At("RETURN"))
    private void chestlogger$onBlockPlaced(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result = cir.getReturnValue();
        if (result != null && result.consumesAction()) {
            Level level = context.getLevel();
            if (level.isClientSide()) return;

            Player player = context.getPlayer();
            if (!(player instanceof ServerPlayer serverPlayer)) return;

            BlockPos pos = context.getClickedPos();
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof Container container) {
                List<SlotDelta> deltas = new ArrayList<>();
                int size = container.getContainerSize();
                for (int i = 0; i < size; i++) {
                    ItemStack stack = container.getItem(i);
                    if (!stack.isEmpty()) {
                        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                        deltas.add(new SlotDelta(i, itemId, stack.getCount(), 0, stack.getCount(), 0L));
                    }
                }

                long packed = BlockPosUtil.pack(pos.getX(), pos.getY(), pos.getZ());
                String dim = level.dimension().identifier().toString();

                TransactionLogEntry entry = new TransactionLogEntry(
                        ChestLoggerMod.getTracker().getNextSequenceId(),
                        System.currentTimeMillis(),
                        UUID.randomUUID(),
                        ActionType.CONTAINER_PLACE,
                        ActorType.PLAYER,
                        serverPlayer.getUUID(),
                        serverPlayer.getGameProfile().name(),
                        dim,
                        packed,
                        deltas
                );
                ChestLoggerMod.getEventQueue().offer(entry);

                if (ChestLoggerMod.getSecurityBroadcaster() != null) {
                    ChestLoggerMod.getSecurityBroadcaster().registerContainerOwner(
                            packed,
                            serverPlayer.getUUID(),
                            serverPlayer.getGameProfile().name()
                    );
                }
            }
        }
    }
}
