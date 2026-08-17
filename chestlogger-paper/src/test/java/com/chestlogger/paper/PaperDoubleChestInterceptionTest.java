package com.chestlogger.paper;

import com.chestlogger.container.ContainerSnapshot;
import com.chestlogger.container.SlotSnapshot;
import com.chestlogger.event.SlotDelta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Paper Double Chest 54-Slot Interception & Rollback Tests")
class PaperDoubleChestInterceptionTest {

    @Test
    @DisplayName("PaperInventoryDeltaCalculator calculates accurate multi-slot diffs across 54 double chest slots")
    void testPaperDoubleChest54SlotDeltas() {
        List<SlotSnapshot> before = new ArrayList<>();
        List<SlotSnapshot> after = new ArrayList<>();

        for (int i = 0; i < 54; i++) {
            before.add(new SlotSnapshot(i, "", 0, 0L));
            after.add(new SlotSnapshot(i, "", 0, 0L));
        }

        // Before: 64 Iron in slot 10 (left half), 32 Gold in slot 45 (right half)
        before.set(10, new SlotSnapshot(10, "minecraft:iron_ingot", 64, 0L));
        before.set(45, new SlotSnapshot(45, "minecraft:gold_ingot", 32, 0L));

        // After: 0 Iron in slot 10 (extracted), 64 Gold in slot 45 (added 32), and 10 Diamonds inserted into slot 53
        after.set(10, new SlotSnapshot(10, "", 0, 0L));
        after.set(45, new SlotSnapshot(45, "minecraft:gold_ingot", 64, 0L));
        after.set(53, new SlotSnapshot(53, "minecraft:diamond", 10, 0L));

        List<SlotDelta> deltas = PaperInventoryDeltaCalculator.calculateSlotDiff(before, after);

        assertThat(deltas).hasSize(3);

        SlotDelta delta10 = deltas.stream().filter(d -> d.slotIndex() == 10).findFirst().orElseThrow();
        assertThat(delta10.itemId()).isEqualTo("minecraft:iron_ingot");
        assertThat(delta10.deltaQuantity()).isEqualTo(-64);

        SlotDelta delta45 = deltas.stream().filter(d -> d.slotIndex() == 45).findFirst().orElseThrow();
        assertThat(delta45.itemId()).isEqualTo("minecraft:gold_ingot");
        assertThat(delta45.deltaQuantity()).isEqualTo(32);

        SlotDelta delta53 = deltas.stream().filter(d -> d.slotIndex() == 53).findFirst().orElseThrow();
        assertThat(delta53.itemId()).isEqualTo("minecraft:diamond");
        assertThat(delta53.deltaQuantity()).isEqualTo(10);
    }
}
