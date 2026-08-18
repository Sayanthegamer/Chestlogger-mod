package com.chestlogger.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChestLogProvenancePayloadTest {

    @Test
    @DisplayName("Should encode and decode ChestLogProvenancePayload round-trip accurately")
    void testChestLogProvenancePayloadRoundTrip() {
        ProvenanceDisplayNode node1 = new ProvenanceDisplayNode(
                1,
                "TAKE",
                "HIGH",
                "Alice",
                "PLAYER",
                -64,
                "minecraft:diamond",
                1723849200000L,
                123456789L,
                "minecraft:overworld",
                "Direct transfer from chest"
        );
        ProvenanceDisplayNode node2 = new ProvenanceDisplayNode(
                2,
                "PUT",
                "MEDIUM",
                "Bob",
                "PLAYER",
                64,
                "minecraft:diamond",
                1723849260000L,
                987654321L,
                "minecraft:the_nether",
                "Deposited into ender chest"
        );

        ChestLogProvenancePayload original = new ChestLogProvenancePayload(
                "minecraft:diamond",
                123456789L,
                "minecraft:overworld",
                2,
                "HIGH",
                List.of(node1, node2)
        );

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ChestLogProvenancePayload.STREAM_CODEC.encode(buf, original);

        ChestLogProvenancePayload decoded = ChestLogProvenancePayload.STREAM_CODEC.decode(buf);

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.targetItemId()).isEqualTo("minecraft:diamond");
        assertThat(decoded.targetPackedPos()).isEqualTo(123456789L);
        assertThat(decoded.targetDimension()).isEqualTo("minecraft:overworld");
        assertThat(decoded.totalSteps()).isEqualTo(2);
        assertThat(decoded.overallConfidence()).isEqualTo("HIGH");
        assertThat(decoded.nodes()).hasSize(2);

        ProvenanceDisplayNode decNode1 = decoded.nodes().get(0);
        assertThat(decNode1.stepIndex()).isEqualTo(1);
        assertThat(decNode1.actionType()).isEqualTo("TAKE");
        assertThat(decNode1.confidence()).isEqualTo("HIGH");
        assertThat(decNode1.actorName()).isEqualTo("Alice");
        assertThat(decNode1.actorType()).isEqualTo("PLAYER");
        assertThat(decNode1.deltaQuantity()).isEqualTo(-64);
        assertThat(decNode1.itemId()).isEqualTo("minecraft:diamond");
        assertThat(decNode1.timestampMs()).isEqualTo(1723849200000L);
        assertThat(decNode1.packedPos()).isEqualTo(123456789L);
        assertThat(decNode1.dimension()).isEqualTo("minecraft:overworld");
        assertThat(decNode1.notes()).isEqualTo("Direct transfer from chest");

        ProvenanceDisplayNode decNode2 = decoded.nodes().get(1);
        assertThat(decNode2.stepIndex()).isEqualTo(2);
        assertThat(decNode2.actionType()).isEqualTo("PUT");
        assertThat(decNode2.confidence()).isEqualTo("MEDIUM");
        assertThat(decNode2.actorName()).isEqualTo("Bob");
        assertThat(decNode2.actorType()).isEqualTo("PLAYER");
        assertThat(decNode2.deltaQuantity()).isEqualTo(64);
        assertThat(decNode2.itemId()).isEqualTo("minecraft:diamond");
        assertThat(decNode2.timestampMs()).isEqualTo(1723849260000L);
        assertThat(decNode2.packedPos()).isEqualTo(987654321L);
        assertThat(decNode2.dimension()).isEqualTo("minecraft:the_nether");
        assertThat(decNode2.notes()).isEqualTo("Deposited into ender chest");
    }

    @Test
    @DisplayName("Should encode and decode ChestLogProvenancePayload with empty node list round-trip accurately")
    void testChestLogProvenancePayloadEmptyNodes() {
        ChestLogProvenancePayload original = new ChestLogProvenancePayload(
                "minecraft:netherite_ingot",
                456789012L,
                "minecraft:the_end",
                0,
                "UNKNOWN",
                List.of()
        );

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ChestLogProvenancePayload.STREAM_CODEC.encode(buf, original);

        ChestLogProvenancePayload decoded = ChestLogProvenancePayload.STREAM_CODEC.decode(buf);

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.targetItemId()).isEqualTo("minecraft:netherite_ingot");
        assertThat(decoded.targetPackedPos()).isEqualTo(456789012L);
        assertThat(decoded.targetDimension()).isEqualTo("minecraft:the_end");
        assertThat(decoded.totalSteps()).isEqualTo(0);
        assertThat(decoded.overallConfidence()).isEqualTo("UNKNOWN");
        assertThat(decoded.nodes()).isEmpty();
    }
}
