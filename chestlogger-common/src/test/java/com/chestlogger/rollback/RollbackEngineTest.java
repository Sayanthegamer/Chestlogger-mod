package com.chestlogger.rollback;

import com.chestlogger.container.ContainerSnapshot;
import com.chestlogger.event.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RollbackEngineTest {

    @Test
    @DisplayName("Should compute inverse compensation delta for stolen items")
    void testInverseExtractionRollback() {
        // Player stole 64 diamonds from slot 0
        TransactionLogEntry theft = new TransactionLogEntry(
                1L, 1000L, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER,
                UUID.randomUUID(), "Griefer", "minecraft:overworld", BlockPosUtil.pack(0, 64, 0),
                List.of(new SlotDelta(0, "minecraft:diamond", -64, 64, 0, 0L))
        );

        // Container currently has 0 items in slot 0
        ContainerSnapshot currentContainer = new ContainerSnapshot(27);

        RollbackEngine engine = new RollbackEngine();
        RollbackPlan plan = engine.createPlan(List.of(theft), currentContainer);

        assertThat(plan.hasConflicts()).isFalse();
        assertThat(plan.steps()).hasSize(1);

        RollbackStep step = plan.steps().get(0);
        assertThat(step.slotIndex()).isEqualTo(0);
        assertThat(step.itemId()).isEqualTo("minecraft:diamond");
        assertThat(step.targetDeltaQuantity()).isEqualTo(64); // Inverse of -64 is +64
    }

    @Test
    @DisplayName("Should compute inverse compensation delta for illicit placed items")
    void testInversePlacementRollback() {
        // Griefer placed 64 tnt into slot 5
        TransactionLogEntry illicitPlace = new TransactionLogEntry(
                2L, 2000L, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                UUID.randomUUID(), "Griefer", "minecraft:overworld", BlockPosUtil.pack(0, 64, 0),
                List.of(new SlotDelta(5, "minecraft:tnt", 64, 0, 64, 0L))
        );

        ContainerSnapshot currentContainer = new ContainerSnapshot(27);
        currentContainer.setSlot(5, "minecraft:tnt", 64, 0L);

        RollbackEngine engine = new RollbackEngine();
        RollbackPlan plan = engine.createPlan(List.of(illicitPlace), currentContainer);

        assertThat(plan.hasConflicts()).isFalse();
        assertThat(plan.steps()).hasSize(1);

        RollbackStep step = plan.steps().get(0);
        assertThat(step.slotIndex()).isEqualTo(5);
        assertThat(step.itemId()).isEqualTo("minecraft:tnt");
        assertThat(step.targetDeltaQuantity()).isEqualTo(-64); // Inverse of +64 is -64
    }

    @Test
    @DisplayName("Should detect conflict when target slot is occupied by different item")
    void testSlotConflictResolution() {
        TransactionLogEntry theft = new TransactionLogEntry(
                3L, 3000L, UUID.randomUUID(), ActionType.SHIFT_CLICK_EXTRACT, ActorType.PLAYER,
                UUID.randomUUID(), "Griefer", "minecraft:overworld", BlockPosUtil.pack(0, 64, 0),
                List.of(new SlotDelta(0, "minecraft:netherite_block", -10, 10, 0, 0L))
        );

        // Slot 0 is now occupied by obsidian!
        ContainerSnapshot currentContainer = new ContainerSnapshot(27);
        currentContainer.setSlot(0, "minecraft:obsidian", 64, 0L);

        RollbackEngine engine = new RollbackEngine();
        RollbackPlan plan = engine.createPlan(List.of(theft), currentContainer);

        // Fallback: redirects to first available empty slot (slot 1) without deleting obsidian
        assertThat(plan.steps()).hasSize(1);
        RollbackStep step = plan.steps().get(0);
        assertThat(step.slotIndex()).isEqualTo(1); // Relocated safely
        assertThat(step.itemId()).isEqualTo("minecraft:netherite_block");
        assertThat(step.targetDeltaQuantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("Should execute rollback compensation and log non-destructive audit event")
    void testExecuteRollbackAndAudit() {
        TransactionEventQueue queue = new TransactionEventQueue(100);
        RollbackEngine engine = new RollbackEngine();

        ContainerSnapshot container = new ContainerSnapshot(27);
        RollbackStep step = new RollbackStep(0, "minecraft:diamond", 64, 0L);
        RollbackPlan plan = new RollbackPlan(List.of(step), 0);

        UUID adminUuid = UUID.randomUUID();
        long pos = BlockPosUtil.pack(10, 20, 30);

        RollbackResult result = engine.applyRollback(plan, container, queue, adminUuid, "Admin", "minecraft:overworld", pos);

        assertThat(result.appliedSteps()).isEqualTo(1);
        assertThat(container.getSlot(0).itemId()).isEqualTo("minecraft:diamond");
        assertThat(container.getSlot(0).count()).isEqualTo(64);

        // Verify audit event was published to queue
        assertThat(queue.getDepth()).isEqualTo(1);
        List<TransactionLogEntry> auditEvents = new ArrayList<>();
        queue.drain(auditEvents, 1);

        TransactionLogEntry audit = auditEvents.get(0);
        assertThat(audit.actionType()).isEqualTo(ActionType.ROLLBACK_COMPENSATION);
        assertThat(audit.actorName()).isEqualTo("Admin");
        assertThat(audit.deltas().get(0).deltaQuantity()).isEqualTo(64);
    }
}
