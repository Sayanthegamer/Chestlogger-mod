package com.chestlogger.mixin;

import com.chestlogger.ChestLoggerMod;
import com.chestlogger.container.ContainerSnapshot;
import com.chestlogger.container.ContainerTracker;
import com.chestlogger.container.ContainerType;
import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {

    @Inject(
            method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD")
    )
    private static void chestlogger$onAddItemHead(
            Container from,
            Container to,
            ItemStack stack,
            Direction direction,
            CallbackInfoReturnable<ItemStack> cir
    ) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        // Only snapshot valid tracked containers
        if (from != null && chestlogger$isTrackedContainer(from)) {
            chestlogger$threadFromPre.set(ContainerTracker.capture(from));
        }
        if (to != null && chestlogger$isTrackedContainer(to)) {
            chestlogger$threadToPre.set(ContainerTracker.capture(to));
        }
    }

    @Inject(
            method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("RETURN")
    )
    private static void chestlogger$onAddItemReturn(
            Container from,
            Container to,
            ItemStack stack,
            Direction direction,
            CallbackInfoReturnable<ItemStack> cir
    ) {
        ContainerSnapshot fromPre = chestlogger$threadFromPre.get();
        ContainerSnapshot toPre = chestlogger$threadToPre.get();
        chestlogger$threadFromPre.remove();
        chestlogger$threadToPre.remove();

        // If returned stack count equals input stack count, transfer failed completely -> no logs
        ItemStack remainder = cir.getReturnValue();
        if (remainder != null && !stack.isEmpty() && remainder.getCount() == stack.getCount()) {
            return;
        }

        if (fromPre != null && from != null) {
            ContainerSnapshot fromPost = ContainerTracker.capture(from);
            chestlogger$recordTransfer(from, fromPre, fromPost, ActionType.HOPPER_EXTRACT, "hopper");
        }

        if (toPre != null && to != null) {
            ContainerSnapshot toPost = ContainerTracker.capture(to);
            chestlogger$recordTransfer(to, toPre, toPost, ActionType.HOPPER_INSERT, "hopper");
        }
    }

    @Unique
    private static final ThreadLocal<ContainerSnapshot> chestlogger$threadFromPre = new ThreadLocal<>();

    @Unique
    private static final ThreadLocal<ContainerSnapshot> chestlogger$threadToPre = new ThreadLocal<>();

    @Unique
    private static boolean chestlogger$isTrackedContainer(Container container) {
        return container instanceof BlockEntity
                || container instanceof CompoundContainer
                || container instanceof Entity
                || container instanceof Hopper;
    }

    @Unique
    private static void chestlogger$recordTransfer(
            Container container,
            ContainerSnapshot pre,
            ContainerSnapshot post,
            ActionType actionType,
            String actorName
    ) {
        String dimension = "minecraft:overworld";
        long packedPos = 0L;
        ActorType actorType = ActorType.HOPPER_BLOCK;

        if (container instanceof CompoundContainer compoundContainer) {
            // Unwrap double chest container to primary block entity
            if (compoundContainer instanceof CompoundContainerAccessor acc) {
                Container primary = acc.chestlogger$getContainer1();
                if (primary instanceof BlockEntity be && be.getLevel() != null) {
                    dimension = be.getLevel().dimension().identifier().toString();
                    BlockPos pos = be.getBlockPos();
                    packedPos = BlockPosUtil.pack(pos.getX(), pos.getY(), pos.getZ());
                }
            }
            actorType = ActorType.AUTOMATION;
        } else if (container instanceof BlockEntity blockEntity && blockEntity.getLevel() != null) {
            dimension = blockEntity.getLevel().dimension().identifier().toString();
            BlockPos pos = blockEntity.getBlockPos();
            packedPos = BlockPosUtil.pack(pos.getX(), pos.getY(), pos.getZ());
            actorType = (blockEntity instanceof HopperBlockEntity) ? ActorType.HOPPER_BLOCK : ActorType.AUTOMATION;
        } else if (container instanceof Entity entity) {
            dimension = entity.level().dimension().identifier().toString();
            packedPos = BlockPosUtil.pack(entity.getBlockX(), entity.getBlockY(), entity.getBlockZ());
            actorType = ActorType.HOPPER_MINECART;
        } else if (container instanceof Hopper hopper) {
            packedPos = BlockPosUtil.pack((int) hopper.getLevelX(), (int) hopper.getLevelY(), (int) hopper.getLevelZ());
        }

        ChestLoggerMod.getTracker().processTransaction(
                pre,
                post,
                actionType,
                actorType,
                null,
                actorName,
                dimension,
                packedPos
        );
    }
}
