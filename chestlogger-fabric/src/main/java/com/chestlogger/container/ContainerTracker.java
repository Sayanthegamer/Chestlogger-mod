package com.chestlogger.container;

import com.chestlogger.event.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-authoritative container tracker coordinating snapshot diffing
 * and publishing atomic TransactionLogEntry events to the queue.
 */
public final class ContainerTracker {
    private final TransactionEventQueue eventQueue;
    private final AtomicLong sequenceGenerator;

    public ContainerTracker(TransactionEventQueue eventQueue) {
        this(eventQueue, 1L);
    }

    public ContainerTracker(TransactionEventQueue eventQueue, long startSequenceId) {
        this.eventQueue = Objects.requireNonNull(eventQueue, "eventQueue cannot be null");
        this.sequenceGenerator = new AtomicLong(startSequenceId);
    }

    /**
     * Snapshots the contents of a Minecraft Container.
     */
    public static ContainerSnapshot capture(Container container) {
        if (container == null) {
            return new ContainerSnapshot(0);
        }
        int size = container.getContainerSize();
        ContainerSnapshot snapshot = new ContainerSnapshot(size);
        for (int i = 0; i < size; i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                int count = stack.getCount();
                // In Minecraft 26.2, components can be fingerprinted deterministically
                long fingerprint = MetadataFingerprint.EMPTY;
                if (!stack.getComponents().isEmpty()) {
                    fingerprint = MetadataFingerprint.compute(stack.getComponents().toString().getBytes());
                }
                snapshot.setSlot(i, itemId, count, fingerprint);
            }
        }
        return snapshot;
    }

    /**
     * Resolves container categorization from a BlockEntity.
     */
    public static ContainerType resolveType(BlockEntity blockEntity) {
        if (blockEntity instanceof ChestBlockEntity) {
            return ContainerType.CHEST;
        } else if (blockEntity instanceof HopperBlockEntity) {
            return ContainerType.HOPPER;
        }
        return ContainerType.GENERIC_CONTAINER;
    }

    /**
     * Creates and enqueues a transaction record if deltas are detected between snapshots.
     */
    public boolean processTransaction(
            ContainerSnapshot pre,
            ContainerSnapshot post,
            ActionType actionType,
            ActorType actorType,
            UUID actorUuid,
            String actorName,
            String dimension,
            long packedPos
    ) {
        List<SlotDelta> deltas = pre.diff(post);
        if (deltas.isEmpty()) {
            return false;
        }

        long seq = sequenceGenerator.getAndIncrement();
        TransactionLogEntry entry = new TransactionLogEntry(
                seq,
                System.currentTimeMillis(),
                UUID.randomUUID(),
                actionType,
                actorType,
                actorUuid,
                actorName,
                dimension,
                packedPos,
                deltas
        );

        return eventQueue.offer(entry);
    }

    public TransactionEventQueue getEventQueue() {
        return eventQueue;
    }

    public long getNextSequenceId() {
        return sequenceGenerator.getAndIncrement();
    }
}
