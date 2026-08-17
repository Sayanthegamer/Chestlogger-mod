package com.chestlogger.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionModelTest {

    @Test
    @DisplayName("Should create immutable SlotDelta with correct positive/negative delta")
    void testSlotDeltaCreation() {
        SlotDelta insertDelta = new SlotDelta(0, "minecraft:diamond", 16, 0, 16, 0L);
        assertThat(insertDelta.slotIndex()).isEqualTo(0);
        assertThat(insertDelta.itemId()).isEqualTo("minecraft:diamond");
        assertThat(insertDelta.deltaQuantity()).isEqualTo(16);
        assertThat(insertDelta.isInsertion()).isTrue();
        assertThat(insertDelta.isExtraction()).isFalse();

        SlotDelta extractDelta = new SlotDelta(5, "minecraft:iron_ingot", -4, 10, 6, 12345L);
        assertThat(extractDelta.slotIndex()).isEqualTo(5);
        assertThat(extractDelta.itemId()).isEqualTo("minecraft:iron_ingot");
        assertThat(extractDelta.deltaQuantity()).isEqualTo(-4);
        assertThat(extractDelta.isInsertion()).isFalse();
        assertThat(extractDelta.isExtraction()).isTrue();
        assertThat(extractDelta.metadataFingerprint()).isEqualTo(12345L);
    }

    @Test
    @DisplayName("Should validate TransactionLogEntry fields and immutability")
    void testTransactionLogEntry() {
        UUID txId = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        long packedPos = BlockPosUtil.pack(100, 64, -200);

        SlotDelta delta = new SlotDelta(2, "minecraft:netherite_ingot", 1, 0, 1, 0L);
        TransactionLogEntry entry = new TransactionLogEntry(
                1001L,
                now,
                txId,
                ActionType.PICKUP,
                ActorType.PLAYER,
                playerUuid,
                "Steve",
                "minecraft:overworld",
                packedPos,
                List.of(delta)
        );

        assertThat(entry.sequenceId()).isEqualTo(1001L);
        assertThat(entry.timestampMs()).isEqualTo(now);
        assertThat(entry.transactionId()).isEqualTo(txId);
        assertThat(entry.actionType()).isEqualTo(ActionType.PICKUP);
        assertThat(entry.actorType()).isEqualTo(ActorType.PLAYER);
        assertThat(entry.actorUuid()).isEqualTo(playerUuid);
        assertThat(entry.actorName()).isEqualTo("Steve");
        assertThat(entry.dimension()).isEqualTo("minecraft:overworld");
        assertThat(entry.packedBlockPos()).isEqualTo(packedPos);
        assertThat(entry.deltas()).hasSize(1);
        assertThat(entry.deltas().get(0)).isEqualTo(delta);

        // Coordinate unpacking verification
        assertThat(BlockPosUtil.unpackX(packedPos)).isEqualTo(100);
        assertThat(BlockPosUtil.unpackY(packedPos)).isEqualTo(64);
        assertThat(BlockPosUtil.unpackZ(packedPos)).isEqualTo(-200);
    }

    @Test
    @DisplayName("Should reject invalid null arguments in models")
    void testNullGuards() {
        assertThatThrownBy(() -> new SlotDelta(0, null, 1, 0, 1, 0L))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new TransactionLogEntry(
                1L, 1000L, null, ActionType.PICKUP, ActorType.PLAYER,
                UUID.randomUUID(), "Steve", "minecraft:overworld", 0L, List.of()
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should generate deterministic metadata fingerprint for item components")
    void testMetadataFingerprint() {
        long fp1 = MetadataFingerprint.compute(new byte[]{1, 2, 3, 4, 5});
        long fp2 = MetadataFingerprint.compute(new byte[]{1, 2, 3, 4, 5});
        long fp3 = MetadataFingerprint.compute(new byte[]{1, 2, 3, 4, 6});

        assertThat(fp1).isEqualTo(fp2);
        assertThat(fp1).isNotEqualTo(fp3);
        assertThat(MetadataFingerprint.EMPTY).isEqualTo(0L);
    }
}
