package com.chestlogger.storage;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.index.PersistentIndexManager;
import com.chestlogger.query.QueryEngine;
import com.chestlogger.recovery.RecoveryReport;
import com.chestlogger.recovery.TailRecoveryEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates byte-for-byte deterministic encoding across golden .chlog / .clog fixtures,
 * ensuring binary compatibility and identical crash recovery behavior across platforms.
 */
public class GoldenFixtureTest {

    private static final UUID TEST_TX_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID TEST_PLAYER_UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    void testGoldenValidLogGenerationAndRoundTrip(@TempDir Path tempDir) throws Exception {
        File logDir = tempDir.toFile();
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        StringTableDictionary dict = new StringTableDictionary();

        LogSegmentWriter writer = new LogSegmentWriter(
                logDir,
                "golden",
                0,
                1L,
                compressor,
                StorageProfile.BALANCED,
                dict
        );

        long pos = BlockPosUtil.pack(100, 64, -200);
        TransactionLogEntry entry1 = new TransactionLogEntry(
                1L,
                1700000000000L,
                TEST_TX_ID,
                ActionType.PICKUP,
                ActorType.PLAYER,
                TEST_PLAYER_UUID,
                "Steve",
                "minecraft:overworld",
                pos,
                List.of(new SlotDelta(0, "minecraft:diamond", -5, 10, 5, 0L))
        );

        TransactionLogEntry entry2 = new TransactionLogEntry(
                2L,
                1700000000500L,
                UUID.fromString("22222222-3333-4444-5555-666666666666"),
                ActionType.PLACE,
                ActorType.PLAYER,
                TEST_PLAYER_UUID,
                "Steve",
                "minecraft:overworld",
                pos,
                List.of(new SlotDelta(1, "minecraft:gold_ingot", 16, 0, 16, 12345L))
        );

        writer.writeBatch(List.of(entry1, entry2));
        writer.close();

        File generatedFile = writer.getCurrentSegmentFile();
        assertThat(generatedFile).exists();
        assertThat(generatedFile.length()).isGreaterThan(BinaryLogHeader.HEADER_SIZE);

        // Verify recovery report on clean file
        TailRecoveryEngine recoveryEngine = new TailRecoveryEngine(compressor);
        RecoveryReport cleanReport = recoveryEngine.recoverAndValidate(logDir);
        assertThat(cleanReport.hasCorruptions()).isFalse();
        assertThat(cleanReport.maxSequenceId()).isEqualTo(2L);

        // Verify query engine reading back the exact records
        PersistentIndexManager indexManager = new PersistentIndexManager(tempDir.resolve("index").toFile());
        com.chestlogger.index.IndexRebuilder rebuilder = new com.chestlogger.index.IndexRebuilder(compressor);
        int indexed = rebuilder.rebuild(logDir, indexManager);
        assertThat(indexed).isEqualTo(2);

        QueryEngine queryEngine = new QueryEngine(logDir, compressor, indexManager, () -> dict);
        List<TransactionLogEntry> fetched = queryEngine.fetchRecords(
                com.chestlogger.index.IndexQueryFilter.builder().build()
        );
        assertThat(fetched).hasSize(2);
        assertThat(fetched.get(0).sequenceId()).isEqualTo(1L);
        assertThat(fetched.get(0).actorName()).isEqualTo("Steve");
        assertThat(fetched.get(0).deltas().get(0).itemId()).isEqualTo("minecraft:diamond");
        assertThat(fetched.get(1).sequenceId()).isEqualTo(2L);
        assertThat(fetched.get(1).deltas().get(0).itemId()).isEqualTo("minecraft:gold_ingot");
    }

    @Test
    void testTruncatedTailRecoveryParity(@TempDir Path tempDir) throws Exception {
        File logDir = tempDir.toFile();
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        StringTableDictionary dict = new StringTableDictionary();

        LogSegmentWriter writer = new LogSegmentWriter(
                logDir,
                "truncated",
                0,
                1L,
                compressor,
                StorageProfile.BALANCED,
                dict
        );

        long pos = BlockPosUtil.pack(50, 70, 50);
        TransactionLogEntry entry = new TransactionLogEntry(
                1L,
                1700000000000L,
                TEST_TX_ID,
                ActionType.CONTAINER_OPEN,
                ActorType.PLAYER,
                TEST_PLAYER_UUID,
                "Alex",
                "minecraft:overworld",
                pos,
                List.of()
        );
        writer.writeBatch(List.of(entry));
        writer.close();

        File segFile = writer.getCurrentSegmentFile();
        long originalLen = segFile.length();

        // Intentionally append incomplete garbage bytes to simulate sudden server crash during flush
        try (FileOutputStream fos = new FileOutputStream(segFile, true)) {
            fos.write(new byte[]{(byte) 0xAA, (byte) 0x55, 0x01, 0x00, 0x00, 0x00, 0x10}); // Incomplete block frame
        }

        assertThat(segFile.length()).isGreaterThan(originalLen);

        // Run tail recovery
        TailRecoveryEngine recoveryEngine = new TailRecoveryEngine(compressor);
        RecoveryReport report = recoveryEngine.recoverAndValidate(logDir);

        assertThat(report.hasCorruptions()).isTrue();
        assertThat(report.totalTruncatedBytes()).isEqualTo(7L);
        assertThat(segFile.length()).isEqualTo(originalLen);
    }
}
