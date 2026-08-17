package com.chestlogger.mixin;

import com.chestlogger.ChestLoggerMod;
import com.chestlogger.container.ContainerContext;
import com.chestlogger.container.ContainerSnapshot;
import com.chestlogger.container.ContainerTracker;
import com.chestlogger.container.MenuContainerAccessor;
import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin implements MenuContainerAccessor {

    @Shadow @Final public NonNullList<Slot> slots;

    @Unique
    private ContainerContext chestlogger$context;

    @Unique
    private ContainerSnapshot chestlogger$preSnapshot;

    @Override
    public ContainerContext chestlogger$getContainerContext() {
        return chestlogger$context;
    }

    @Override
    public void chestlogger$setContainerContext(ContainerContext context) {
        this.chestlogger$context = context;
    }

    @Inject(method = "clicked", at = @At("HEAD"))
    private void chestlogger$onClickedHead(int slotIndex, int button, ContainerInput clickType, Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer) || chestlogger$context == null) {
            return;
        }

        // Snapshot container slots prior to click resolution
        Container container = chestlogger$resolveContainer();
        if (container != null) {
            chestlogger$preSnapshot = ContainerTracker.capture(container);
        }
    }

    @Inject(method = "clicked", at = @At("RETURN"))
    private void chestlogger$onClickedReturn(int slotIndex, int button, ContainerInput clickType, Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer) || chestlogger$context == null || chestlogger$preSnapshot == null) {
            return;
        }

        Container container = chestlogger$resolveContainer();
        if (container != null) {
            ContainerSnapshot postSnapshot = ContainerTracker.capture(container);
            ActionType actionType = chestlogger$mapClickType(clickType, button);

            for (Long pos : chestlogger$context.allPositions()) {
                ChestLoggerMod.getTracker().processTransaction(
                        chestlogger$preSnapshot,
                        postSnapshot,
                        actionType,
                        ActorType.PLAYER,
                        serverPlayer.getUUID(),
                        serverPlayer.getScoreboardName(),
                        chestlogger$context.dimension(),
                        pos
                );
            }
        }
        chestlogger$preSnapshot = null;
    }

    @Unique
    private Container chestlogger$resolveContainer() {
        if (slots.isEmpty()) return null;
        Slot firstSlot = slots.get(0);
        return firstSlot.container;
    }

    @Unique
    private ActionType chestlogger$mapClickType(ContainerInput clickType, int button) {
        if (clickType == null) return ActionType.PICKUP;
        return switch (clickType) {
            case QUICK_MOVE -> ActionType.SHIFT_CLICK_EXTRACT;
            case SWAP -> ActionType.HOTBAR_SWAP;
            case QUICK_CRAFT -> ActionType.DRAG_SPLIT;
            case PICKUP_ALL -> ActionType.DOUBLE_CLICK_COLLECT;
            case THROW -> ActionType.DROP_FROM_SLOT;
            default -> (button == 1) ? ActionType.PLACE : ActionType.PICKUP;
        };
    }
}
