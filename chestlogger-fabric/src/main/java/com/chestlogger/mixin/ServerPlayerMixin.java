package com.chestlogger.mixin;

import com.chestlogger.container.ContainerContext;
import com.chestlogger.container.ContainerTracker;
import com.chestlogger.container.ContainerType;
import com.chestlogger.container.MenuContainerAccessor;
import com.chestlogger.event.BlockPosUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @Inject(method = "openMenu", at = @At("RETURN"))
    private void chestlogger$onOpenMenu(MenuProvider menuProvider, CallbackInfoReturnable<OptionalInt> cir) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (player.containerMenu instanceof MenuContainerAccessor accessor) {
            String dimKey = player.level().dimension().identifier().toString();

            if (menuProvider instanceof BlockEntity blockEntity) {
                BlockPos pos = blockEntity.getBlockPos();
                long packedPos = BlockPosUtil.pack(pos.getX(), pos.getY(), pos.getZ());
                ContainerType type = ContainerTracker.resolveType(blockEntity);
                accessor.chestlogger$setContainerContext(new ContainerContext(dimKey, packedPos, type, menuProvider.getDisplayName().getString()));
            }
        }
    }
}
