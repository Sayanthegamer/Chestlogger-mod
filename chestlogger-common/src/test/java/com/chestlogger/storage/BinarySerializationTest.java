package com.chestlogger.storage;

import com.chestlogger.event.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BinarySerializationTest {

    @Test
    @DisplayName("Should encode and decode unsigned and signed VarInts / VarLongs correctly")
    void testVarIntEncoding() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        VarIntUtil.writeVarInt(out, 0);
        VarIntUtil.writeVarInt(out, 1);
        VarIntUtil.writeVarInt(out, 127);
        VarIntUtil.writeVarInt(out, 128);
        VarIntUtil.writeVarInt(out, 65535);
        VarIntUtil.writeVarInt(out, Integer.MAX_VALUE);

        VarIntUtil.writeSignedVarInt(out, 0);
        VarIntUtil.writeSignedVarInt(out, 1);
        VarIntUtil.writeSignedVarInt(out, -1);
        VarIntUtil.writeSignedVarInt(out, 64);
        VarIntUtil.writeSignedVarInt(out, -64);
        VarIntUtil.writeSignedVarInt(out, -10000);

        VarIntUtil.writeVarLong(out, 0L);
        VarIntUtil.writeVarLong(out, 1000000000000L);
        VarIntUtil.writeVarLong(out, Long.MAX_VALUE);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());

        assertThat(VarIntUtil.readVarInt(in)).isEqualTo(0);
        assertThat(VarIntUtil.readVarInt(in)).isEqualTo(1);
        assertThat(VarIntUtil.readVarInt(in)).isEqualTo(127);
        assertThat(VarIntUtil.readVarInt(in)).isEqualTo(128);
        assertThat(VarIntUtil.readVarInt(in)).isEqualTo(65535);
        assertThat(VarIntUtil.readVarInt(in)).isEqualTo(Integer.MAX_VALUE);

        assertThat(VarIntUtil.readSignedVarInt(in)).isEqualTo(0);
        assertThat(VarIntUtil.readSignedVarInt(in)).isEqualTo(1);
        assertThat(VarIntUtil.readSignedVarInt(in)).isEqualTo(-1);
        assertThat(VarIntUtil.readSignedVarInt(in)).isEqualTo(64);
        assertThat(VarIntUtil.readSignedVarInt(in)).isEqualTo(-64);
        assertThat(VarIntUtil.readSignedVarInt(in)).isEqualTo(-10000);

        assertThat(VarIntUtil.readVarLong(in)).isEqualTo(0L);
        assertThat(VarIntUtil.readVarLong(in)).isEqualTo(1000000000000L);
        assertThat(VarIntUtil.readVarLong(in)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("Should assign and persist StringTableDictionary entries")
    void testStringTableDictionary() throws IOException {
        StringTableDictionary dict = new StringTableDictionary();

        int id1 = dict.getOrAssign("minecraft:diamond");
        int id2 = dict.getOrAssign("minecraft:chest");
        int id3 = dict.getOrAssign("minecraft:diamond"); // repeat

        assertThat(id1).isEqualTo(0);
        assertThat(id2).isEqualTo(1);
        assertThat(id3).isEqualTo(0);
        assertThat(dict.size()).isEqualTo(2);

        assertThat(dict.getString(0)).isEqualTo("minecraft:diamond");
        assertThat(dict.getString(1)).isEqualTo("minecraft:chest");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        dict.writeTo(out);

        StringTableDictionary loadedDict = StringTableDictionary.readFrom(new ByteArrayInputStream(out.toByteArray()));
        assertThat(loadedDict.size()).isEqualTo(2);
        assertThat(loadedDict.getString(0)).isEqualTo("minecraft:diamond");
        assertThat(loadedDict.getString(1)).isEqualTo("minecraft:chest");
    }

    @Test
    @DisplayName("Should perform binary round-trip serialization for player and automation transactions")
    void testRecordBinaryCodecRoundTrip() throws IOException {
        StringTableDictionary stringDict = new StringTableDictionary();

        UUID txId = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        long packedPos = BlockPosUtil.pack(1234, 64, -5678);

        SlotDelta d1 = new SlotDelta(0, "minecraft:diamond", 10, 0, 10, 0L);
        SlotDelta d2 = new SlotDelta(1, "minecraft:netherite_sword", -1, 1, 0, 987654321L);

        TransactionLogEntry playerEntry = new TransactionLogEntry(
                100L, now, txId, ActionType.SHIFT_CLICK_INSERT, ActorType.PLAYER,
                playerUuid, "Alex", "minecraft:overworld", packedPos, List.of(d1, d2)
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BinaryRecordCodec.encode(out, playerEntry, stringDict, 0L, 0L);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        TransactionLogEntry decodedPlayer = BinaryRecordCodec.decode(in, stringDict, 0L, 0L);

        assertThat(decodedPlayer.sequenceId()).isEqualTo(playerEntry.sequenceId());
        assertThat(decodedPlayer.timestampMs()).isEqualTo(playerEntry.timestampMs());
        assertThat(decodedPlayer.transactionId()).isEqualTo(playerEntry.transactionId());
        assertThat(decodedPlayer.actionType()).isEqualTo(playerEntry.actionType());
        assertThat(decodedPlayer.actorType()).isEqualTo(playerEntry.actorType());
        assertThat(decodedPlayer.actorUuid()).isEqualTo(playerEntry.actorUuid());
        assertThat(decodedPlayer.actorName()).isEqualTo(playerEntry.actorName());
        assertThat(decodedPlayer.dimension()).isEqualTo(playerEntry.dimension());
        assertThat(decodedPlayer.packedBlockPos()).isEqualTo(playerEntry.packedBlockPos());
        assertThat(decodedPlayer.deltas()).hasSize(2);
        assertThat(decodedPlayer.deltas().get(0)).isEqualTo(d1);
        assertThat(decodedPlayer.deltas().get(1)).isEqualTo(d2);

        // Automation roundtrip
        TransactionLogEntry autoEntry = new TransactionLogEntry(
                101L, now + 50, UUID.randomUUID(), ActionType.HOPPER_EXTRACT, ActorType.HOPPER_BLOCK,
                null, "hopper_block", "minecraft:the_nether", packedPos, List.of(d1)
        );

        ByteArrayOutputStream autoOut = new ByteArrayOutputStream();
        BinaryRecordCodec.encode(autoOut, autoEntry, stringDict, 100L, now);

        ByteArrayInputStream autoIn = new ByteArrayInputStream(autoOut.toByteArray());
        TransactionLogEntry decodedAuto = BinaryRecordCodec.decode(autoIn, stringDict, 100L, now);

        assertThat(decodedAuto.sequenceId()).isEqualTo(autoEntry.sequenceId());
        assertThat(decodedAuto.actorType()).isEqualTo(ActorType.HOPPER_BLOCK);
        assertThat(decodedAuto.actorUuid()).isNull();
        assertThat(decodedAuto.dimension()).isEqualTo("minecraft:the_nether");
    }
}
