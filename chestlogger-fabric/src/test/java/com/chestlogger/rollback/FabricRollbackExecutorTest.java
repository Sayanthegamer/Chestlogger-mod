package com.chestlogger.rollback;

import com.chestlogger.container.ContainerSnapshot;
import com.chestlogger.event.ActionType;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionEventQueue;
import com.chestlogger.event.TransactionLogEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FabricRollbackExecutorTest {

    @Test
    @DisplayName("Should correctly apply item restoration (positive delta) and call setChanged")
    void testApplyPositiveDelta() {
        TransactionEventQueue queue = new TransactionEventQueue(100);
        FabricRollbackExecutor executor = new FabricRollbackExecutor();
        
        ContainerSnapshot snapshot = new ContainerSnapshot(27);
        TestMutator mutator = new TestMutator(snapshot);
        
        RollbackStep step = new RollbackStep(5, "minecraft:diamond", 10, 0L);
        RollbackPlan plan = new RollbackPlan(List.of(step), 0);
        
        UUID adminId = UUID.randomUUID();
        
        RollbackResult result = executor.applyRollbackInternal(plan, mutator, queue, adminId, "Admin", "minecraft:overworld", 0L);
        
        assertThat(result.appliedSteps()).isEqualTo(1);
        assertThat(snapshot.getSlot(5).itemId()).isEqualTo("minecraft:diamond");
        assertThat(snapshot.getSlot(5).count()).isEqualTo(10);
        assertThat(mutator.changedCalled).isTrue();
        
        assertThat(queue.getDepth()).isEqualTo(1);
        List<TransactionLogEntry> auditEvents = new ArrayList<>();
        queue.drain(auditEvents, 1);
        
        TransactionLogEntry audit = auditEvents.get(0);
        assertThat(audit.actionType()).isEqualTo(ActionType.ROLLBACK_COMPENSATION);
        assertThat(audit.deltas().get(0).deltaQuantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("Should correctly apply item removal (negative delta) by clearing to empty and call setChanged")
    void testApplyNegativeDelta() {
        TransactionEventQueue queue = new TransactionEventQueue(100);
        FabricRollbackExecutor executor = new FabricRollbackExecutor();
        
        ContainerSnapshot snapshot = new ContainerSnapshot(27);
        snapshot.setSlot(10, "minecraft:dirt", 64, 0L);
        
        TestMutator mutator = new TestMutator(snapshot);
        
        RollbackStep step = new RollbackStep(10, "minecraft:dirt", -64, 0L);
        RollbackPlan plan = new RollbackPlan(List.of(step), 0);
        
        RollbackResult result = executor.applyRollbackInternal(plan, mutator, queue, UUID.randomUUID(), "Admin", "minecraft:overworld", 0L);
        
        assertThat(result.appliedSteps()).isEqualTo(1);
        assertThat(snapshot.getSlot(10).isEmpty()).isTrue();
        assertThat(mutator.changedCalled).isTrue();
    }
    
    @Test
    @DisplayName("Should handle double chest boundaries (up to 54 slots) and adapt if needed")
    void testDoubleChestBoundary() {
        TransactionEventQueue queue = new TransactionEventQueue(100);
        FabricRollbackExecutor executor = new FabricRollbackExecutor();
        
        ContainerSnapshot snapshot = new ContainerSnapshot(54);
        TestMutator mutator = new TestMutator(snapshot);
        
        RollbackStep step = new RollbackStep(55, "minecraft:gold_ingot", 5, 0L);
        RollbackPlan plan = new RollbackPlan(List.of(step), 0);
        
        RollbackResult result = executor.applyRollbackInternal(plan, mutator, queue, UUID.randomUUID(), "Admin", "minecraft:overworld", 0L);
        
        assertThat(result.appliedSteps()).isEqualTo(1);
        assertThat(snapshot.getSlot(0).itemId()).isEqualTo("minecraft:gold_ingot");
        assertThat(snapshot.getSlot(0).count()).isEqualTo(5);
        assertThat(mutator.changedCalled).isTrue();
    }

    private static class TestMutator implements FabricRollbackExecutor.ContainerMutator {
        private final ContainerSnapshot snapshot;
        boolean changedCalled = false;

        TestMutator(ContainerSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public int getSize() {
            return snapshot.size();
        }

        @Override
        public boolean isEmpty(int slot) {
            return snapshot.getSlot(slot).isEmpty();
        }

        @Override
        public int getCount(int slot) {
            return snapshot.getSlot(slot).count();
        }

        @Override
        public void setItemCount(int slot, String itemId, int count) {
            if (count <= 0) {
                snapshot.setSlot(slot, "", 0, 0L);
            } else {
                snapshot.setSlot(slot, itemId, count, 0L);
            }
        }

        @Override
        public void setChanged() {
            this.changedCalled = true;
        }
    }

    @Test
    @DisplayName("Should correctly record metadata hash in audit delta when metadataHash != 0L")
    void testApplyWithMetadataHash() {
        TransactionEventQueue queue = new TransactionEventQueue(100);
        FabricRollbackExecutor executor = new FabricRollbackExecutor();
        
        ContainerSnapshot snapshot = new ContainerSnapshot(27);
        TestMutator mutator = new TestMutator(snapshot);
        
        long customHash = 123456789L;
        RollbackStep step = new RollbackStep(5, "minecraft:diamond_sword", 1, customHash);
        RollbackPlan plan = new RollbackPlan(List.of(step), 0);
        
        UUID adminId = UUID.randomUUID();
        
        RollbackResult result = executor.applyRollbackInternal(plan, mutator, queue, adminId, "Admin", "minecraft:overworld", 0L);
        
        assertThat(result.appliedSteps()).isEqualTo(1);
        
        queue.drain(new ArrayList<>(), 0); // Drain to get the queue count, wait, let's just drain to list
        List<TransactionLogEntry> auditEvents = new ArrayList<>();
        queue.drain(auditEvents, 1);
        
        TransactionLogEntry audit = auditEvents.get(0);
        assertThat(audit.deltas().get(0).metadataFingerprint()).isEqualTo(customHash);
    }

    @Test
    @DisplayName("Should verify that ROLLBACK_COMPENSATION is only logged after confirmed world mutation")
    void testAuditLoggedAfterMutation() {
        TransactionEventQueue queue = new TransactionEventQueue(100);
        FabricRollbackExecutor executor = new FabricRollbackExecutor();
        
        ContainerSnapshot snapshot = new ContainerSnapshot(27);
        
        // Use a mutator that verifies the queue is empty when setChanged is called
        TestMutator mutator = new TestMutator(snapshot) {
            @Override
            public void setChanged() {
                super.setChanged();
                assertThat(queue.getDepth()).isZero(); // Queue must be empty before this completes! Wait, the implementation calls setChanged() *before* offering to queue.
            }
        };
        
        RollbackStep step = new RollbackStep(5, "minecraft:diamond", 1, 0L);
        RollbackPlan plan = new RollbackPlan(List.of(step), 0);
        
        executor.applyRollbackInternal(plan, mutator, queue, UUID.randomUUID(), "Admin", "minecraft:overworld", 0L);
        
        assertThat(mutator.changedCalled).isTrue();
        assertThat(queue.getDepth()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should correctly apply partial rollback and log partial application correctly")
    void testPartialRollbackLogging() {
        TransactionEventQueue queue = new TransactionEventQueue(100);
        FabricRollbackExecutor executor = new FabricRollbackExecutor();
        
        ContainerSnapshot snapshot = new ContainerSnapshot(27); // Only 27 slots (0-26)
        // Fill all slots so fallback fails, wait.
        // If one targets slot 30 (out of bounds) and we don't want it to fallback, or if we let it fallback, the test asks: "the out-of-bounds one falls back to first empty slot".
        // Verify appliedSteps == 2 (both applied, one to slot 5, one to fallback slot 0)
        TestMutator mutator = new TestMutator(snapshot);
        
        RollbackStep step1 = new RollbackStep(30, "minecraft:gold_ingot", 5, 0L);
        RollbackStep step2 = new RollbackStep(5, "minecraft:iron_ingot", 10, 0L);
        RollbackPlan plan = new RollbackPlan(List.of(step1, step2), 0);
        
        RollbackResult result = executor.applyRollbackInternal(plan, mutator, queue, UUID.randomUUID(), "Admin", "minecraft:overworld", 0L);
        
        assertThat(result.appliedSteps()).isEqualTo(2);
        assertThat(snapshot.getSlot(0).itemId()).isEqualTo("minecraft:gold_ingot"); // fallback slot
        assertThat(snapshot.getSlot(0).count()).isEqualTo(5);
        assertThat(snapshot.getSlot(5).itemId()).isEqualTo("minecraft:iron_ingot");
        assertThat(snapshot.getSlot(5).count()).isEqualTo(10);
        
        List<TransactionLogEntry> auditEvents = new ArrayList<>();
        queue.drain(auditEvents, 1);
        
        TransactionLogEntry audit = auditEvents.get(0);
        assertThat(audit.deltas()).hasSize(2);
        
        // Verify the audit deltas reflect the actual slots used (0 and 5)
        assertThat(audit.deltas()).extracting(com.chestlogger.event.SlotDelta::slotIndex).containsExactlyInAnyOrder(0, 5);
    }

    @Test
    @DisplayName("Integration test: Full flow with positive and negative deltas")
    void testIntegrationEndToEnd() {
        TransactionEventQueue queue = new TransactionEventQueue(100);
        FabricRollbackExecutor executor = new FabricRollbackExecutor();
        
        ContainerSnapshot snapshot = new ContainerSnapshot(27);
        snapshot.setSlot(0, "minecraft:stone", 64, 0L); // We will remove 32
        snapshot.setSlot(1, "minecraft:air", 0, 0L); // We will add 10 diamond
        
        TestMutator mutator = new TestMutator(snapshot);
        
        RollbackStep step1 = new RollbackStep(0, "minecraft:stone", -32, 0L);
        RollbackStep step2 = new RollbackStep(1, "minecraft:diamond", 10, 0L);
        RollbackPlan plan = new RollbackPlan(List.of(step1, step2), 0);
        
        RollbackResult result = executor.applyRollbackInternal(plan, mutator, queue, UUID.randomUUID(), "Admin", "minecraft:overworld", 0L);
        
        assertThat(result.appliedSteps()).isEqualTo(2);
        assertThat(snapshot.getSlot(0).count()).isEqualTo(32);
        assertThat(snapshot.getSlot(1).itemId()).isEqualTo("minecraft:diamond");
        assertThat(snapshot.getSlot(1).count()).isEqualTo(10);
        
        List<TransactionLogEntry> auditEvents = new ArrayList<>();
        queue.drain(auditEvents, 1);
        
        assertThat(auditEvents).hasSize(1);
        TransactionLogEntry audit = auditEvents.get(0);
        assertThat(audit.deltas()).hasSize(2);
        
        SlotDelta delta1 = audit.deltas().stream().filter(d -> d.slotIndex() == 0).findFirst().get();
        assertThat(delta1.preCount()).isEqualTo(64);
        assertThat(delta1.postCount()).isEqualTo(32);
        
        SlotDelta delta2 = audit.deltas().stream().filter(d -> d.slotIndex() == 1).findFirst().get();
        assertThat(delta2.preCount()).isEqualTo(0);
        assertThat(delta2.postCount()).isEqualTo(10);
    }
}
