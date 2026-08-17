package com.chestlogger.recovery;

import com.chestlogger.event.*;
import com.chestlogger.storage.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TailRecoveryEngineTest {

    private final BlockCompressor compressor = new LZ4BlockCompressor();
    private final StorageProfile profile = StorageProfile.BALANCED;

    @Test
    @DisplayName("Should scan valid segment and report zero corruption with clean max sequence ID")
    void testCleanSegmentValidation(@TempDir Path tempDir) throws IOException {
        File dataDir = tempDir.toFile();
        StringTableDictionary dict = new StringTableDictionary();

        List<TransactionLogEntry> records = List.of(
                new TransactionLogEntry(100L, 1000L, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER, UUID.randomUUID(), "Steve", "minecraft:overworld", BlockPosUtil.pack(0, 64, 0), List.of(new SlotDelta(0, "minecraft:stone", 1, 0, 1, 0L))),
                new TransactionLogEntry(101L, 1010L, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER, UUID.randomUUID(), "Steve", "minecraft:overworld", BlockPosUtil.pack(0, 64, 0), List.of(new SlotDelta(0, "minecraft:stone", -1, 1, 0, 0L)))
        );

        try (LogSegmentWriter writer = new LogSegmentWriter(dataDir, "chestlog", 0, 100L, compressor, profile, dict)) {
            writer.writeBatch(records);
        }

        TailRecoveryEngine engine = new TailRecoveryEngine(compressor);
        RecoveryReport report = engine.recoverAndValidate(dataDir);

        assertThat(report.hasCorruptions()).isFalse();
        assertThat(report.totalTruncatedBytes()).isEqualTo(0L);
        assertThat(report.maxSequenceId()).isEqualTo(101L);
        assertThat(report.activeSegmentIndex()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should truncate partial trailing frame header caused by abrupt crash")
    void testPartialFrameHeaderTruncation(@TempDir Path tempDir) throws IOException {
        File dataDir = tempDir.toFile();
        StringTableDictionary dict = new StringTableDictionary();

        List<TransactionLogEntry> records = List.of(
                new TransactionLogEntry(1L, 1000L, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER, UUID.randomUUID(), "Alex", "minecraft:overworld", BlockPosUtil.pack(1, 2, 3), List.of(new SlotDelta(0, "minecraft:diamond", 10, 0, 10, 0L)))
        );

        File segmentFile;
        try (LogSegmentWriter writer = new LogSegmentWriter(dataDir, "chestlog", 0, 1L, compressor, profile, dict)) {
            writer.writeBatch(records);
            segmentFile = writer.getCurrentSegmentFile();
        }

        long validLength = segmentFile.length();

        // Simulate crash writing 12 bytes of a new block header
        try (FileOutputStream fos = new FileOutputStream(segmentFile, true)) {
            fos.write(new byte[]{(byte) 0xAA, 0x55, 0x01, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x0A, 0x00, 0x00});
        }
        assertThat(segmentFile.length()).isEqualTo(validLength + 12);

        TailRecoveryEngine engine = new TailRecoveryEngine(compressor);
        RecoveryReport report = engine.recoverAndValidate(dataDir);

        assertThat(report.hasCorruptions()).isTrue();
        assertThat(report.totalTruncatedBytes()).isEqualTo(12L);
        assertThat(report.maxSequenceId()).isEqualTo(1L);
        assertThat(segmentFile.length()).isEqualTo(validLength);
    }

    @Test
    @DisplayName("Should truncate payload with corrupted CRC32 checksum and safely resume writing")
    void testCorruptedPayloadTruncationAndResumption(@TempDir Path tempDir) throws IOException {
        File dataDir = tempDir.toFile();
        StringTableDictionary dict = new StringTableDictionary();

        List<TransactionLogEntry> batch1 = List.of(
                new TransactionLogEntry(1L, 1000L, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER, UUID.randomUUID(), "Alex", "minecraft:overworld", BlockPosUtil.pack(1, 2, 3), List.of(new SlotDelta(0, "minecraft:diamond", 10, 0, 10, 0L)))
        );
        List<TransactionLogEntry> batch2 = List.of(
                new TransactionLogEntry(2L, 2000L, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER, UUID.randomUUID(), "Alex", "minecraft:overworld", BlockPosUtil.pack(1, 2, 3), List.of(new SlotDelta(0, "minecraft:diamond", 5, 10, 15, 0L)))
        );

        File segmentFile;
        long lengthAfterBatch1;
        try (LogSegmentWriter writer = new LogSegmentWriter(dataDir, "chestlog", 0, 1L, compressor, profile, dict)) {
            writer.writeBatch(batch1);
            lengthAfterBatch1 = writer.getCurrentSegmentFile().length();
            writer.writeBatch(batch2);
            segmentFile = writer.getCurrentSegmentFile();
        }

        // Corrupt a byte inside batch 2's payload
        try (RandomAccessFile raf = new RandomAccessFile(segmentFile, "rw")) {
            raf.seek(lengthAfterBatch1 + BlockFrameHeader.HEADER_SIZE + 2);
            byte original = raf.readByte();
            raf.seek(lengthAfterBatch1 + BlockFrameHeader.HEADER_SIZE + 2);
            raf.writeByte(original ^ 0xFF); // Bit flip
        }

        TailRecoveryEngine engine = new TailRecoveryEngine(compressor);
        RecoveryReport report = engine.recoverAndValidate(dataDir);

        assertThat(report.hasCorruptions()).isTrue();
        assertThat(report.maxSequenceId()).isEqualTo(1L);
        assertThat(segmentFile.length()).isEqualTo(lengthAfterBatch1);

        // Resume writing post-recovery
        List<TransactionLogEntry> batchResume = List.of(
                new TransactionLogEntry(report.maxSequenceId() + 1, 3000L, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER, UUID.randomUUID(), "Alex", "minecraft:overworld", BlockPosUtil.pack(1, 2, 3), List.of(new SlotDelta(0, "minecraft:diamond", -2, 10, 8, 0L)))
        );

        try (LogSegmentWriter writer = new LogSegmentWriter(dataDir, "chestlog", report.activeSegmentIndex(), report.maxSequenceId() + 1, compressor, profile, dict)) {
            writer.writeBatch(batchResume);
        }

        // Verify recovered + resumed segment
        RecoveryReport postResumeReport = engine.recoverAndValidate(dataDir);
        assertThat(postResumeReport.hasCorruptions()).isFalse();
        assertThat(postResumeReport.maxSequenceId()).isEqualTo(2L);
    }
}
