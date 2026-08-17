package com.chestlogger.paper;

import com.chestlogger.container.ContainerSnapshot;
import com.chestlogger.container.SlotSnapshot;
import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaperInventoryDeltaCalculatorTest {

    @Test
    @DisplayName("Should compute accurate deltas when container slot is extracted (PICKUP)")
    void testSlotExtractionDelta() {
        SlotSnapshot before = new SlotSnapshot(0, "minecraft:diamond", 64, 0L);
        SlotSnapshot after = new SlotSnapshot(0, "minecraft:diamond", 32, 0L);

        List<SlotDelta> deltas = PaperInventoryDeltaCalculator.calculateSlotDiff(List.of(before), List.of(after));

        assertThat(deltas).hasSize(1);
        SlotDelta delta = deltas.get(0);
        assertThat(delta.slotIndex()).isEqualTo(0);
        assertThat(delta.itemId()).isEqualTo("minecraft:diamond");
        assertThat(delta.deltaQuantity()).isEqualTo(-32);
        assertThat(delta.preCount()).isEqualTo(64);
        assertThat(delta.postCount()).isEqualTo(32);
    }

    @Test
    @DisplayName("Should compute accurate deltas when items are placed into empty slot")
    void testSlotPlacementDelta() {
        SlotSnapshot before = new SlotSnapshot(5, "", 0, 0L);
        SlotSnapshot after = new SlotSnapshot(5, "minecraft:gold_ingot", 16, 12345L);

        List<SlotDelta> deltas = PaperInventoryDeltaCalculator.calculateSlotDiff(List.of(before), List.of(after));

        assertThat(deltas).hasSize(1);
        SlotDelta delta = deltas.get(0);
        assertThat(delta.slotIndex()).isEqualTo(5);
        assertThat(delta.itemId()).isEqualTo("minecraft:gold_ingot");
        assertThat(delta.deltaQuantity()).isEqualTo(16);
        assertThat(delta.preCount()).isEqualTo(0);
        assertThat(delta.postCount()).isEqualTo(16);
        assertThat(delta.metadataFingerprint()).isEqualTo(12345L);
    }

    @Test
    @DisplayName("Should compute accurate swap deltas when different item replaces slot")
    void testSlotItemSwapDelta() {
        SlotSnapshot before = new SlotSnapshot(2, "minecraft:iron_ingot", 10, 0L);
        SlotSnapshot after = new SlotSnapshot(2, "minecraft:copper_ingot", 20, 0L);

        List<SlotDelta> deltas = PaperInventoryDeltaCalculator.calculateSlotDiff(List.of(before), List.of(after));

        // When swapping item types, produces negative delta for old item and positive delta for new item
        assertThat(deltas).hasSize(2);
        assertThat(deltas.get(0).itemId()).isEqualTo("minecraft:iron_ingot");
        assertThat(deltas.get(0).deltaQuantity()).isEqualTo(-10);

        assertThat(deltas.get(1).itemId()).isEqualTo("minecraft:copper_ingot");
        assertThat(deltas.get(1).deltaQuantity()).isEqualTo(20);
    }

    @Test
    @DisplayName("Should map Paper event properties into immutable TransactionLogEntry")
    void testBuildTransactionEntry() {
        UUID actorUuid = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        long pos = 123456789L;
        String dim = "minecraft:overworld";

        SlotDelta delta = new SlotDelta(0, "minecraft:netherite_ingot", -1, 1, 0, 0L);
        TransactionLogEntry entry = PaperInventoryDeltaCalculator.buildEntry(
                100L,
                txId,
                ActionType.PICKUP,
                ActorType.PLAYER,
                actorUuid,
                "Sayan",
                dim,
                pos,
                List.of(delta)
        );

        assertThat(entry.sequenceId()).isEqualTo(100L);
        assertThat(entry.transactionId()).isEqualTo(txId);
        assertThat(entry.actionType()).isEqualTo(ActionType.PICKUP);
        assertThat(entry.actorName()).isEqualTo("Sayan");
        assertThat(entry.deltas()).containsExactly(delta);
    }
}
