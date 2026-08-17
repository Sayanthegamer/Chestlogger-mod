package com.chestlogger.paper;

import com.chestlogger.container.ContainerSnapshot;
import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionEventQueue;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.rollback.RollbackPlan;
import com.chestlogger.rollback.RollbackStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaperRollbackExecutorTest {

    @Test
    @DisplayName("Should correctly plan inverse deltas from Paper container snapshots")
    void testPaperRollbackPlan() {
        TransactionEventQueue queue = new TransactionEventQueue(100);
        PaperRollbackExecutor executor = new PaperRollbackExecutor(queue);

        UUID thief = UUID.randomUUID();
        TransactionLogEntry theft = new TransactionLogEntry(
                1L,
                System.currentTimeMillis(),
                UUID.randomUUID(),
                ActionType.PICKUP,
                ActorType.PLAYER,
                thief,
                "Griefer",
                "minecraft:overworld",
                12345L,
                List.of(new SlotDelta(0, "minecraft:diamond", -16, 16, 0, 0L))
        );

        // Simulated empty container
        ContainerSnapshot container = new ContainerSnapshot(27);
        com.chestlogger.rollback.RollbackPlanner planner = new com.chestlogger.rollback.RollbackPlanner();
        RollbackPlan plan = planner.createPlan(List.of(theft), container);

        assertThat(plan.hasConflicts()).isFalse();
        assertThat(plan.steps()).hasSize(1);

        RollbackStep step = plan.steps().get(0);
        assertThat(step.slotIndex()).isEqualTo(0);
        assertThat(step.itemId()).isEqualTo("minecraft:diamond");
        assertThat(step.targetDeltaQuantity()).isEqualTo(16);
    }
}
