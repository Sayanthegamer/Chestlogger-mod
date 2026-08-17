package com.chestlogger.container;

import com.chestlogger.event.*;
import com.chestlogger.index.IndexPointer;
import com.chestlogger.index.IndexQueryFilter;
import com.chestlogger.index.PersistentIndexManager;
import com.chestlogger.rollback.RollbackEngine;
import com.chestlogger.rollback.RollbackPlan;
import com.chestlogger.rollback.RollbackResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Double Chest 54-Slot Snapshots, Dual-Block Indexing & Rollback Tests")
class DoubleChestSnapshotAndIndexTest {

    @Test
    @DisplayName("ContainerSnapshot with 54 slots correctly tracks diffs across left (0-26) and right (27-53) halves")
    void test54SlotDoubleChestDiffs() {
        ContainerSnapshot pre = new ContainerSnapshot(54);
        ContainerSnapshot post = new ContainerSnapshot(54);

        // Pre state: 64 Diamonds in left half (slot 0), 32 Gold in right half (slot 30)
        pre.setSlot(0, "minecraft:diamond", 64, 0L);
        pre.setSlot(30, "minecraft:gold_ingot", 32, 0L);

        // Post state: 32 Diamonds left in slot 0, 64 Gold in slot 30, and 16 Emeralds placed in right half (slot 53)
        post.setSlot(0, "minecraft:diamond", 32, 0L);
        post.setSlot(30, "minecraft:gold_ingot", 64, 0L);
        post.setSlot(53, "minecraft:emerald", 16, 0L);

        List<SlotDelta> deltas = pre.diff(post);

        assertThat(deltas).hasSize(3);

        // Slot 0 (Left half): -32 diamonds
        SlotDelta delta0 = deltas.stream().filter(d -> d.slotIndex() == 0).findFirst().orElseThrow();
        assertThat(delta0.itemId()).isEqualTo("minecraft:diamond");
        assertThat(delta0.deltaQuantity()).isEqualTo(-32);
        assertThat(delta0.preCount()).isEqualTo(64);
        assertThat(delta0.postCount()).isEqualTo(32);

        // Slot 30 (Right half): +32 gold
        SlotDelta delta30 = deltas.stream().filter(d -> d.slotIndex() == 30).findFirst().orElseThrow();
        assertThat(delta30.itemId()).isEqualTo("minecraft:gold_ingot");
        assertThat(delta30.deltaQuantity()).isEqualTo(32);
        assertThat(delta30.preCount()).isEqualTo(32);
        assertThat(delta30.postCount()).isEqualTo(64);

        // Slot 53 (Right half): +16 emeralds
        SlotDelta delta53 = deltas.stream().filter(d -> d.slotIndex() == 53).findFirst().orElseThrow();
        assertThat(delta53.itemId()).isEqualTo("minecraft:emerald");
        assertThat(delta53.deltaQuantity()).isEqualTo(16);
        assertThat(delta53.preCount()).isEqualTo(0);
        assertThat(delta53.postCount()).isEqualTo(16);
    }

    @Test
    @DisplayName("Dual-coordinate indexing allows querying either half of double chest to retrieve transactions")
    void testDualCoordinateIndexing(@TempDir File indexDir) throws IOException {
        PersistentIndexManager indexManager = new PersistentIndexManager(indexDir);

        long leftPos = BlockPosUtil.pack(100, 64, -200);
        long rightPos = BlockPosUtil.pack(101, 64, -200);
        String dimension = "minecraft:overworld";
        UUID playerUuid = UUID.randomUUID();

        // Index pointer associated with both left and right positions (or dual-indexed pointers)
        IndexPointer leftPtr = new IndexPointer(
                1L, 1000L, playerUuid, "minecraft:diamond", dimension, leftPos, 0, 32L, 0
        );
        IndexPointer rightPtr = new IndexPointer(
                1L, 1000L, playerUuid, "minecraft:diamond", dimension, rightPos, 0, 32L, 0
        );

        indexManager.index(leftPtr);
        indexManager.index(rightPtr);

        // Query left pos
        List<IndexPointer> leftMatches = indexManager.query(
                IndexQueryFilter.builder().dimension(dimension).exactBlockPos(leftPos).build()
        );
        assertThat(leftMatches).hasSize(1);
        assertThat(leftMatches.get(0).sequenceId()).isEqualTo(1L);

        // Query right pos
        List<IndexPointer> rightMatches = indexManager.query(
                IndexQueryFilter.builder().dimension(dimension).exactBlockPos(rightPos).build()
        );
        assertThat(rightMatches).hasSize(1);
        assertThat(rightMatches.get(0).sequenceId()).isEqualTo(1L);

        // Query with exact block position set (both coords)
        List<IndexPointer> setMatches = indexManager.query(
                IndexQueryFilter.builder().dimension(dimension).exactBlockPositions(Set.of(leftPos, rightPos)).build()
        );
        assertThat(setMatches).hasSize(2);
    }

    @Test
    @DisplayName("RollbackEngine accurately plans and executes rollback on 54-slot double container")
    void test54SlotRollbackPlanAndExecution() {
        long leftPos = BlockPosUtil.pack(100, 64, -200);
        UUID txId = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();

        // Stolen 64 diamonds from slot 5 (left half) and 64 netherite from slot 40 (right half)
        TransactionLogEntry theftEntry = new TransactionLogEntry(
                1L,
                System.currentTimeMillis(),
                txId,
                ActionType.SHIFT_CLICK_EXTRACT,
                ActorType.PLAYER,
                playerUuid,
                "Griefer",
                "minecraft:overworld",
                leftPos,
                List.of(
                        new SlotDelta(5, "minecraft:diamond", -64, 64, 0, 0L),
                        new SlotDelta(40, "minecraft:netherite_ingot", -64, 64, 0, 0L)
                )
        );

        // Current container state: empty double chest
        ContainerSnapshot doubleChest = new ContainerSnapshot(54);

        RollbackEngine engine = new RollbackEngine();
        RollbackPlan plan = engine.createPlan(List.of(theftEntry), doubleChest);

        assertThat(plan.hasConflicts()).isFalse();
        assertThat(plan.steps()).hasSize(2);

        TransactionEventQueue auditQueue = new TransactionEventQueue(100);
        RollbackResult result = engine.applyRollback(
                plan, doubleChest, auditQueue, UUID.randomUUID(), "Admin", "minecraft:overworld", leftPos
        );

        assertThat(result.success()).isTrue();
        assertThat(result.appliedSteps()).isEqualTo(2);

        // Assert slots restored across both halves
        assertThat(doubleChest.getSlot(5).itemId()).isEqualTo("minecraft:diamond");
        assertThat(doubleChest.getSlot(5).count()).isEqualTo(64);

        assertThat(doubleChest.getSlot(40).itemId()).isEqualTo("minecraft:netherite_ingot");
        assertThat(doubleChest.getSlot(40).count()).isEqualTo(64);
    }
}
