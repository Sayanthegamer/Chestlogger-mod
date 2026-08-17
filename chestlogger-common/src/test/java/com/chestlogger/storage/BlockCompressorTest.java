package com.chestlogger.storage;

import com.chestlogger.event.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockCompressorTest {

    @Test
    @DisplayName("Should compress and decompress byte payload using LZ4 with exact fidelity")
    void testLZ4CompressionRoundTrip() throws IOException {
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();

        byte[] original = "ChestLogger high performance Minecraft 26.2 container tracking storage engine testing payload!".repeat(20).getBytes(StandardCharsets.UTF_8);

        byte[] compressed = compressor.compress(original);
        assertThat(compressed.length).isLessThan(original.length);

        byte[] decompressed = compressor.decompress(compressed, original.length);
        assertThat(decompressed).isEqualTo(original);
    }

    @Test
    @DisplayName("Should compress serialized transaction batch and achieve significant space savings")
    void testTransactionBatchCompression() throws IOException {
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        StringTableDictionary dict = new StringTableDictionary();

        List<TransactionLogEntry> records = new ArrayList<>();
        UUID playerUuid = UUID.randomUUID();
        long now = System.currentTimeMillis();

        for (int i = 0; i < 200; i++) {
            SlotDelta d = new SlotDelta(i % 27, "minecraft:iron_ingot", i % 2 == 0 ? 1 : -1, 10, 11, 0L);
            records.add(new TransactionLogEntry(
                    i + 1, now + (i * 10), UUID.randomUUID(),
                    ActionType.PICKUP, ActorType.PLAYER, playerUuid,
                    "Steve", "minecraft:overworld", BlockPosUtil.pack(100, 64, 200),
                    List.of(d)
            ));
        }

        ByteArrayOutputStream uncompressedStream = new ByteArrayOutputStream();
        long prevSeq = 0L;
        long prevTime = 0L;
        for (TransactionLogEntry r : records) {
            BinaryRecordCodec.encode(uncompressedStream, r, dict, prevSeq, prevTime);
            prevSeq = r.sequenceId();
            prevTime = r.timestampMs();
        }

        byte[] rawBytes = uncompressedStream.toByteArray();
        byte[] compressed = compressor.compress(rawBytes);

        assertThat(compressed.length).isLessThan(rawBytes.length / 2); // >2x compression on transactions

        byte[] restored = compressor.decompress(compressed, rawBytes.length);
        assertThat(restored).isEqualTo(rawBytes);
    }

    @Test
    @DisplayName("Should validate StorageProfile presets")
    void testStorageProfiles() {
        StorageProfile balanced = StorageProfile.BALANCED;
        assertThat(balanced.queueCapacity()).isEqualTo(65536);
        assertThat(balanced.maxBatchEvents()).isEqualTo(1000);
        assertThat(balanced.flushIntervalMs()).isEqualTo(1000L);

        StorageProfile hdd = StorageProfile.HDD;
        assertThat(hdd.maxBatchEvents()).isEqualTo(5000);
        assertThat(hdd.flushIntervalMs()).isEqualTo(5000L);

        StorageProfile ssd = StorageProfile.SSD;
        assertThat(ssd.flushIntervalMs()).isEqualTo(150L);

        assertThat(StorageProfile.fromName("hdd")).isEqualTo(StorageProfile.HDD);
        assertThat(StorageProfile.fromName("ssd")).isEqualTo(StorageProfile.SSD);
        assertThat(StorageProfile.fromName("balanced")).isEqualTo(StorageProfile.BALANCED);
        assertThat(StorageProfile.fromName("unknown")).isEqualTo(StorageProfile.BALANCED);
    }
}
