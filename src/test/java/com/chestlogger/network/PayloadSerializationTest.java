package com.chestlogger.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadSerializationTest {

    @Test
    @DisplayName("Should encode and decode ChestLogPagePayload round-trip accurately")
    void testChestLogPagePayloadRoundTrip() {
        UUID queryId = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();
        DisplayRecord record1 = new DisplayRecord(
                1L, 1723849200000L, playerUuid, "Butter_offline", (byte) 0x01, (byte) 0x02,
                0, "minecraft:chest", -1, 123456L
        );
        DisplayRecord record2 = new DisplayRecord(
                2L, 1723849260000L, playerUuid, "Butter_offline", (byte) 0x01, (byte) 0x01,
                5, "minecraft:diamond", 64, 789012L
        );

        ChestLogPagePayload original = new ChestLogPagePayload(
                queryId,
                1,
                5,
                120,
                "Chest",
                "minecraft:overworld",
                123456789L,
                List.of(record1, record2)
        );

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ChestLogPagePayload.STREAM_CODEC.encode(buf, original);

        ChestLogPagePayload decoded = ChestLogPagePayload.STREAM_CODEC.decode(buf);

        assertThat(decoded.queryId()).isEqualTo(queryId);
        assertThat(decoded.pageIndex()).isEqualTo(1);
        assertThat(decoded.totalPages()).isEqualTo(5);
        assertThat(decoded.totalRecords()).isEqualTo(120);
        assertThat(decoded.containerType()).isEqualTo("Chest");
        assertThat(decoded.dimension()).isEqualTo("minecraft:overworld");
        assertThat(decoded.packedBlockPos()).isEqualTo(123456789L);
        assertThat(decoded.records()).hasSize(2);

        DisplayRecord decRec1 = decoded.records().get(0);
        assertThat(decRec1.sequenceId()).isEqualTo(1L);
        assertThat(decRec1.actorName()).isEqualTo("Butter_offline");
        assertThat(decRec1.itemId()).isEqualTo("minecraft:chest");
        assertThat(decRec1.quantityDelta()).isEqualTo(-1);

        DisplayRecord decRec2 = decoded.records().get(1);
        assertThat(decRec2.sequenceId()).isEqualTo(2L);
        assertThat(decRec2.itemId()).isEqualTo("minecraft:diamond");
        assertThat(decRec2.quantityDelta()).isEqualTo(64);
    }

    @Test
    @DisplayName("Should encode and decode ChestLogPageRequestPayload round-trip accurately")
    void testChestLogPageRequestPayloadRoundTrip() {
        UUID queryId = UUID.randomUUID();
        ChestLogPageRequestPayload original = new ChestLogPageRequestPayload(
                queryId,
                3,
                987654321L,
                "minecraft:the_nether",
                "Alex",
                "minecraft:ancient_debris"
        );

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ChestLogPageRequestPayload.STREAM_CODEC.encode(buf, original);

        ChestLogPageRequestPayload decoded = ChestLogPageRequestPayload.STREAM_CODEC.decode(buf);

        assertThat(decoded.queryId()).isEqualTo(queryId);
        assertThat(decoded.requestedPage()).isEqualTo(3);
        assertThat(decoded.packedBlockPos()).isEqualTo(987654321L);
        assertThat(decoded.dimension()).isEqualTo("minecraft:the_nether");
        assertThat(decoded.filterPlayer()).isEqualTo("Alex");
        assertThat(decoded.filterItem()).isEqualTo("minecraft:ancient_debris");
    }
}
