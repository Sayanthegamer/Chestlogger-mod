package com.chestlogger.container;

import com.chestlogger.event.SlotDelta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerDiffTest {

    @Test
    @DisplayName("Should detect single slot item insertion")
    void testSingleSlotInsertion() {
        ContainerSnapshot pre = new ContainerSnapshot(27);
        ContainerSnapshot post = new ContainerSnapshot(27);

        post.setSlot(0, "minecraft:diamond", 16, 0L);

        List<SlotDelta> deltas = pre.diff(post);
        assertThat(deltas).hasSize(1);

        SlotDelta d = deltas.get(0);
        assertThat(d.slotIndex()).isEqualTo(0);
        assertThat(d.itemId()).isEqualTo("minecraft:diamond");
        assertThat(d.deltaQuantity()).isEqualTo(16);
        assertThat(d.preCount()).isEqualTo(0);
        assertThat(d.postCount()).isEqualTo(16);
    }

    @Test
    @DisplayName("Should detect item extraction from container")
    void testSingleSlotExtraction() {
        ContainerSnapshot pre = new ContainerSnapshot(27);
        ContainerSnapshot post = new ContainerSnapshot(27);

        pre.setSlot(4, "minecraft:iron_ingot", 64, 0L);
        post.setSlot(4, "minecraft:iron_ingot", 32, 0L);

        List<SlotDelta> deltas = pre.diff(post);
        assertThat(deltas).hasSize(1);

        SlotDelta d = deltas.get(0);
        assertThat(d.slotIndex()).isEqualTo(4);
        assertThat(d.itemId()).isEqualTo("minecraft:iron_ingot");
        assertThat(d.deltaQuantity()).isEqualTo(-32);
        assertThat(d.preCount()).isEqualTo(64);
        assertThat(d.postCount()).isEqualTo(32);
    }

    @Test
    @DisplayName("Should detect slot item swap (replace one item type with another)")
    void testSlotSwap() {
        ContainerSnapshot pre = new ContainerSnapshot(27);
        ContainerSnapshot post = new ContainerSnapshot(27);

        pre.setSlot(2, "minecraft:gold_ingot", 10, 0L);
        post.setSlot(2, "minecraft:emerald", 5, 12345L);

        List<SlotDelta> deltas = pre.diff(post);
        // Swapping creates 2 deltas for that slot: -10 gold, +5 emerald
        assertThat(deltas).hasSize(2);

        SlotDelta extract = deltas.get(0);
        assertThat(extract.slotIndex()).isEqualTo(2);
        assertThat(extract.itemId()).isEqualTo("minecraft:gold_ingot");
        assertThat(extract.deltaQuantity()).isEqualTo(-10);

        SlotDelta insert = deltas.get(1);
        assertThat(insert.slotIndex()).isEqualTo(2);
        assertThat(insert.itemId()).isEqualTo("minecraft:emerald");
        assertThat(insert.deltaQuantity()).isEqualTo(5);
        assertThat(insert.metadataFingerprint()).isEqualTo(12345L);
    }

    @Test
    @DisplayName("Should detect multi-slot shift click and drag distribution")
    void testMultiSlotDiff() {
        ContainerSnapshot pre = new ContainerSnapshot(27);
        ContainerSnapshot post = new ContainerSnapshot(27);

        pre.setSlot(0, "minecraft:redstone", 10, 0L);
        pre.setSlot(1, "minecraft:redstone", 10, 0L);

        post.setSlot(0, "minecraft:redstone", 30, 0L);
        post.setSlot(1, "minecraft:redstone", 0, 0L);
        post.setSlot(2, "minecraft:redstone", 20, 0L);

        List<SlotDelta> deltas = pre.diff(post);
        assertThat(deltas).hasSize(3);

        assertThat(deltas).anyMatch(d -> d.slotIndex() == 0 && d.deltaQuantity() == 20);
        assertThat(deltas).anyMatch(d -> d.slotIndex() == 1 && d.deltaQuantity() == -10);
        assertThat(deltas).anyMatch(d -> d.slotIndex() == 2 && d.deltaQuantity() == 20);
    }

    @Test
    @DisplayName("Should return empty deltas when container is untouched")
    void testNoChanges() {
        ContainerSnapshot pre = new ContainerSnapshot(27);
        ContainerSnapshot post = new ContainerSnapshot(27);

        pre.setSlot(5, "minecraft:coal", 10, 0L);
        post.setSlot(5, "minecraft:coal", 10, 0L);

        List<SlotDelta> deltas = pre.diff(post);
        assertThat(deltas).isEmpty();
    }
}
