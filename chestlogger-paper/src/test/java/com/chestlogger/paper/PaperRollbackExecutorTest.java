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
    @Test
    @DisplayName("Should call container.update(true, true) when executing rollback")
    void testExecuteCallsContainerUpdate() {
        TransactionEventQueue queue = new TransactionEventQueue(100);
        PaperRollbackExecutor executor = new PaperRollbackExecutor(queue);

        boolean[] updateCalled = { false };
        org.bukkit.block.Container mockContainer = (org.bukkit.block.Container) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { org.bukkit.block.Container.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("update") && args.length == 2) {
                        if (Boolean.TRUE.equals(args[0]) && Boolean.TRUE.equals(args[1])) {
                            updateCalled[0] = true;
                        }
                        return true;
                    }
                    if (method.getName().equals("getInventory")) {
                        return (org.bukkit.inventory.Inventory) java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] { org.bukkit.inventory.Inventory.class },
                                (p, m, a) -> {
                                    if (m.getName().equals("getSize")) return 27;
                                    if (m.getName().equals("getItem")) return null;
                                    return null;
                                }
                        );
                    }
                    return null;
                }
        );

        RollbackPlan plan = new RollbackPlan(List.of(), 0);
        executor.execute(plan, mockContainer, UUID.randomUUID(), "Admin", "minecraft:overworld", 12345L);

        assertThat(updateCalled[0]).isTrue();
    }

    @Test
    @DisplayName("Should correctly resolve materials and item IDs")
    void testMaterialResolution() {
        assertThat(PaperRollbackExecutor.resolveItemId(null)).isEqualTo("minecraft:air");
        assertThat(PaperRollbackExecutor.resolveMaterial(null)).isEqualTo(org.bukkit.Material.AIR);
        assertThat(PaperRollbackExecutor.resolveMaterial("")).isEqualTo(org.bukkit.Material.AIR);
        assertThat(PaperRollbackExecutor.resolveMaterial("minecraft:diamond")).isEqualTo(org.bukkit.Material.DIAMOND);
    }

    @Test
    @DisplayName("Should snapshot inventory correctly")
    void testSnapshotNullInventory() {
        ContainerSnapshot snapshot = PaperRollbackExecutor.snapshotInventory(null);
        assertThat(snapshot.size()).isZero();
    }
}

