package com.chestlogger.container;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.storage.BinaryRecordCodec;
import com.chestlogger.storage.StringTableDictionary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExtendedContainerLifecycleTest {

    @Test
    @DisplayName("ActionType properly supports CONTAINER_BREAK and CONTAINER_PLACE wire IDs")
    void testActionTypeWireIds() {
        assertThat(ActionType.CONTAINER_BREAK.getWireId()).isEqualTo((byte) 0x0D);
        assertThat(ActionType.CONTAINER_PLACE.getWireId()).isEqualTo((byte) 0x0E);
        assertThat(ActionType.CRAFTER_CRAFT.getWireId()).isEqualTo((byte) 0x0F);

        assertThat(ActionType.fromWireId((byte) 0x0D)).isEqualTo(ActionType.CONTAINER_BREAK);
        assertThat(ActionType.fromWireId((byte) 0x0E)).isEqualTo(ActionType.CONTAINER_PLACE);
        assertThat(ActionType.fromWireId((byte) 0x0F)).isEqualTo(ActionType.CRAFTER_CRAFT);
    }

    @Test
    @DisplayName("Encode and decode container break entry with inventory drop deltas")
    void testContainerBreakSerialization() throws IOException {
        StringTableDictionary dict = new StringTableDictionary();
        long now = System.currentTimeMillis();
        UUID txId = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();
        long pos = BlockPosUtil.pack(100, 64, -200);

        List<SlotDelta> dropDeltas = List.of(
                new SlotDelta(0, "minecraft:diamond", -64, 64, 0, 0L),
                new SlotDelta(1, "minecraft:netherite_ingot", -10, 10, 0, 0L)
        );

        TransactionLogEntry breakEntry = new TransactionLogEntry(
                500L,
                now,
                txId,
                ActionType.CONTAINER_BREAK,
                ActorType.PLAYER,
                playerUuid,
                "Alex",
                "minecraft:overworld",
                pos,
                dropDeltas
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BinaryRecordCodec.encode(out, breakEntry, dict, 0L, 0L);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        TransactionLogEntry decoded = BinaryRecordCodec.decode(in, dict, 0L, 0L);

        assertThat(decoded.sequenceId()).isEqualTo(500L);
        assertThat(decoded.actionType()).isEqualTo(ActionType.CONTAINER_BREAK);
        assertThat(decoded.actorName()).isEqualTo("Alex");
        assertThat(decoded.deltas()).hasSize(2);
        assertThat(decoded.deltas().get(0).itemId()).isEqualTo("minecraft:diamond");
        assertThat(decoded.deltas().get(0).deltaQuantity()).isEqualTo(-64);
        assertThat(decoded.deltas().get(1).itemId()).isEqualTo("minecraft:netherite_ingot");
        assertThat(decoded.deltas().get(1).deltaQuantity()).isEqualTo(-10);
    }

    @Test
    @DisplayName("Encode and decode crafter crafting transaction entry")
    void testCrafterCraftSerialization() throws IOException {
        StringTableDictionary dict = new StringTableDictionary();
        long now = System.currentTimeMillis();
        UUID txId = UUID.randomUUID();
        long pos = BlockPosUtil.pack(50, 70, 50);

        List<SlotDelta> craftDeltas = List.of(
                new SlotDelta(0, "minecraft:iron_ingot", -1, 1, 0, 0L),
                new SlotDelta(1, "minecraft:iron_ingot", -1, 1, 0, 0L),
                new SlotDelta(2, "minecraft:iron_ingot", -1, 1, 0, 0L),
                new SlotDelta(9, "minecraft:iron_block", 1, 0, 1, 0L)
        );

        TransactionLogEntry craftEntry = new TransactionLogEntry(
                600L,
                now,
                txId,
                ActionType.CRAFTER_CRAFT,
                ActorType.CRAFTER,
                null,
                "Crafter",
                "minecraft:the_nether",
                pos,
                craftDeltas
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BinaryRecordCodec.encode(out, craftEntry, dict, 0L, 0L);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        TransactionLogEntry decoded = BinaryRecordCodec.decode(in, dict, 0L, 0L);

        assertThat(decoded.sequenceId()).isEqualTo(600L);
        assertThat(decoded.actionType()).isEqualTo(ActionType.CRAFTER_CRAFT);
        assertThat(decoded.actorType()).isEqualTo(ActorType.CRAFTER);
        assertThat(decoded.deltas()).hasSize(4);
    }
}
