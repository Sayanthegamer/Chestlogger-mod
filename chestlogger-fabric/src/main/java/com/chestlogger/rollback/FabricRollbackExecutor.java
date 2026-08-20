package com.chestlogger.rollback;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionEventQueue;
import com.chestlogger.event.TransactionLogEntry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a rollback plan on a live Minecraft Container.
 */
public final class FabricRollbackExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(FabricRollbackExecutor.class);

    /**
     * Interface for mutating a container, abstracting away Minecraft specific classes for testability.
     */
    interface ContainerMutator {
        int getSize();
        boolean isEmpty(int slot);
        int getCount(int slot);
        void setItemCount(int slot, String itemId, int count);
        void setChanged();
    }

    /**
     * Applies a rollback plan directly to a live Minecraft Container.
     *
     * @param plan        the rollback plan to execute
     * @param container   the live Minecraft Container
     * @param eventQueue  the event queue to publish the compensation audit entry
     * @param adminUuid   the UUID of the administrator performing the rollback
     * @param adminName   the name of the administrator
     * @param dimension   the dimension identifier
     * @param packedPos   the packed block position of the container
     * @return the result of the rollback execution
     */
    public RollbackResult applyRollback(
            RollbackPlan plan,
            Container container,
            TransactionEventQueue eventQueue,
            UUID adminUuid,
            String adminName,
            String dimension,
            long packedPos) {
        Objects.requireNonNull(container, "container cannot be null");
        
        ContainerMutator mutator = new ContainerMutator() {
            @Override
            public int getSize() {
                return container.getContainerSize();
            }

            @Override
            public boolean isEmpty(int slot) {
                ItemStack stack = container.getItem(slot);
                return stack == null || stack.isEmpty();
            }

            @Override
            public int getCount(int slot) {
                ItemStack stack = container.getItem(slot);
                return (stack == null || stack.isEmpty()) ? 0 : stack.getCount();
            }

            @Override
            public void setItemCount(int slot, String itemId, int count) {
                if (count <= 0) {
                    container.setItem(slot, ItemStack.EMPTY);
                } else {
                    Identifier identifier = Identifier.tryParse(itemId);
                    if (identifier != null && BuiltInRegistries.ITEM.containsKey(identifier)) {
                        Item item = BuiltInRegistries.ITEM.getValue(identifier);
                        if (item != null && item != Items.AIR) {
                            container.setItem(slot, new ItemStack(item, count));
                        }
                    }
                }
            }

            @Override
            public void setChanged() {
                container.setChanged();
            }
        };

        return applyRollbackInternal(plan, mutator, eventQueue, adminUuid, adminName, dimension, packedPos);
    }

    RollbackResult applyRollbackInternal(
            RollbackPlan plan,
            ContainerMutator container,
            TransactionEventQueue eventQueue,
            UUID adminUuid,
            String adminName,
            String dimension,
            long packedPos) {
        
        Objects.requireNonNull(plan, "plan cannot be null");
        Objects.requireNonNull(container, "container cannot be null");
        Objects.requireNonNull(eventQueue, "eventQueue cannot be null");

        List<SlotDelta> auditDeltas = new ArrayList<>();
        int applied = 0;

        for (RollbackStep step : plan.steps()) {
            int targetSlot = step.slotIndex();
            
            if (targetSlot >= container.getSize()) {
                // Adaptive compensation: find first empty slot in surviving container
                int fallbackSlot = -1;
                for (int s = 0; s < container.getSize(); s++) {
                    if (container.isEmpty(s)) {
                        fallbackSlot = s;
                        break;
                    }
                }
                if (fallbackSlot != -1) {
                    targetSlot = fallbackSlot;
                }
            }

            if (targetSlot < container.getSize()) {
                if (step.metadataHash() != 0L) {
                    LOGGER.warn("Rollback: Item {} in slot {} had custom components (hash: {}) that cannot be reconstructed from hash alone", step.itemId(), targetSlot, step.metadataHash());
                }
                
                int currentCount = container.getCount(targetSlot);
                int newCount = Math.max(0, Math.min(64, currentCount + step.targetDeltaQuantity()));
                
                container.setItemCount(targetSlot, step.itemId(), newCount);
                
                auditDeltas.add(new SlotDelta(
                        targetSlot,
                        step.itemId(),
                        step.targetDeltaQuantity(),
                        currentCount,
                        newCount,
                        step.metadataHash()
                ));
                applied++;
            }
        }

        container.setChanged();

        if (!auditDeltas.isEmpty()) {
            TransactionLogEntry compensationLog = new TransactionLogEntry(
                    0L,
                    System.currentTimeMillis(),
                    UUID.randomUUID(),
                    ActionType.ROLLBACK_COMPENSATION,
                    ActorType.ADMIN_COMMAND,
                    adminUuid,
                    adminName != null ? adminName : "Server",
                    dimension,
                    packedPos,
                    auditDeltas
            );
            eventQueue.offer(compensationLog);
        }

        return new RollbackResult(applied, plan.conflictCount(), true);
    }
}
