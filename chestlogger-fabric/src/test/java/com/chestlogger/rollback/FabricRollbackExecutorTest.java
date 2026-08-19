package com.chestlogger.rollback;

import com.chestlogger.container.ContainerSnapshot;
import com.chestlogger.event.ActionType;
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
}
