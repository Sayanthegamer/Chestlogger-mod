package com.chestlogger.rollback;

import com.chestlogger.container.ContainerSnapshot;
import com.chestlogger.event.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RollbackSafetyIntegrationTest {

    @Test
    @DisplayName("Should link compensation transaction to original theft sequence ID and track relocated slot audit")
    void testOriginalTransactionLinkageAndRelocatedAudit() {
        TransactionEventQueue queue = new TransactionEventQueue(100);
        RollbackEngine engine = new RollbackEngine();

        long originalTheftSeq = 42L;
        UUID originalTheftUuid = UUID.randomUUID();
        long pos = BlockPosUtil.pack(100, 64, 200);

        TransactionLogEntry theft = new TransactionLogEntry(
                originalTheftSeq, 1000L, originalTheftUuid, ActionType.PICKUP, ActorType.PLAYER,
                UUID.randomUUID(), "Griefer", "minecraft:overworld", pos,
                List.of(new SlotDelta(0, "minecraft:diamond_block", -32, 32, 0, 0L))
        );

        // Current container: slot 0 is occupied by gold block (conflict), slot 3 is empty
        ContainerSnapshot container = new ContainerSnapshot(27);
        container.setSlot(0, "minecraft:gold_block", 64, 0L);

        RollbackPlan plan = engine.createPlan(List.of(theft), container);

        // Verification: Relocated to slot 1 safely
        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).slotIndex()).isEqualTo(1);

        UUID adminUuid = UUID.randomUUID();
        RollbackResult result = engine.applyRollback(plan, container, queue, adminUuid, "Operator", "minecraft:overworld", pos);

        assertThat(result.appliedSteps()).isEqualTo(1);
        assertThat(result.conflictCount()).isEqualTo(0);
        assertThat(container.getSlot(0).itemId()).isEqualTo("minecraft:gold_block"); // Preserved
        assertThat(container.getSlot(1).itemId()).isEqualTo("minecraft:diamond_block"); // Restored safely

        List<TransactionLogEntry> auditList = new ArrayList<>();
        queue.drain(auditList, 10);
        assertThat(auditList).hasSize(1);
        TransactionLogEntry audit = auditList.get(0);
        assertThat(audit.actionType()).isEqualTo(ActionType.ROLLBACK_COMPENSATION);
        assertThat(audit.deltas().get(0).slotIndex()).isEqualTo(1); // Relocated slot auditable
    }

    @Test
    @DisplayName("Should abort rollback atomically on unexpected container full condition without partial corrupt state")
    void testFullContainerAbortsCleanlyWithoutPartialDamage() {
        TransactionEventQueue queue = new TransactionEventQueue(100);
        RollbackEngine engine = new RollbackEngine();

        // 3 thefts to rollback
        TransactionLogEntry t1 = new TransactionLogEntry(1L, 1000L, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER, UUID.randomUUID(), "Griefer", "minecraft:overworld", 0L, List.of(new SlotDelta(0, "minecraft:netherite_ingot", -10, 10, 0, 0L)));
        TransactionLogEntry t2 = new TransactionLogEntry(2L, 1010L, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER, UUID.randomUUID(), "Griefer", "minecraft:overworld", 0L, List.of(new SlotDelta(1, "minecraft:diamond", -20, 20, 0, 0L)));

        // Fully full container (all 2 slots occupied by obsidian)
        ContainerSnapshot fullContainer = new ContainerSnapshot(2);
        fullContainer.setSlot(0, "minecraft:obsidian", 64, 0L);
        fullContainer.setSlot(1, "minecraft:obsidian", 64, 0L);

        RollbackPlan plan = engine.createPlan(List.of(t1, t2), fullContainer);

        // Should report conflicts because 0 empty slots exist
        assertThat(plan.hasConflicts()).isTrue();
        assertThat(plan.conflictCount()).isEqualTo(2);
        assertThat(plan.steps()).isEmpty(); // Zero unsafe modifications planned

        // Applying empty plan leaves container untouched and generates 0 false audit logs
        RollbackResult result = engine.applyRollback(plan, fullContainer, queue, UUID.randomUUID(), "Admin", "minecraft:overworld", 0L);
        assertThat(result.appliedSteps()).isEqualTo(0);
        assertThat(queue.getDepth()).isEqualTo(0);
    }
}
