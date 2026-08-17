package com.chestlogger.index;

import com.chestlogger.event.*;
import com.chestlogger.storage.LZ4BlockCompressor;
import com.chestlogger.storage.LogSegmentWriter;
import com.chestlogger.storage.StorageProfile;
import com.chestlogger.storage.StringTableDictionary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IndexRebuildTest {

    @Test
    @DisplayName("Should self-heal and rebuild full multi-dimensional index from raw log segments")
    void testIndexRebuildFromLogs(@TempDir Path tempDir) throws IOException {
        File dataDir = tempDir.toFile();
        StringTableDictionary dict = new StringTableDictionary();
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        StorageProfile profile = StorageProfile.BALANCED;

        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        long pos1 = BlockPosUtil.pack(50, 64, -100);
        long pos2 = BlockPosUtil.pack(200, 70, 300);
        long now = System.currentTimeMillis();

        // Write 2 batches of records to log files
        List<TransactionLogEntry> batch1 = new ArrayList<>();
        batch1.add(new TransactionLogEntry(1L, now, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER, playerA, "Alex", "minecraft:overworld", pos1, List.of(new SlotDelta(0, "minecraft:diamond", 64, 0, 64, 0L))));
        batch1.add(new TransactionLogEntry(2L, now + 10, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER, playerB, "Steve", "minecraft:overworld", pos2, List.of(new SlotDelta(0, "minecraft:iron_ingot", -10, 20, 10, 0L))));

        List<TransactionLogEntry> batch2 = new ArrayList<>();
        batch2.add(new TransactionLogEntry(3L, now + 100, UUID.randomUUID(), ActionType.HOPPER_EXTRACT, ActorType.HOPPER_BLOCK, null, "hopper", "minecraft:overworld", pos1, List.of(new SlotDelta(0, "minecraft:diamond", -1, 64, 63, 0L))));

        try (LogSegmentWriter writer = new LogSegmentWriter(dataDir, "chestlog", 0, 1L, compressor, profile, dict)) {
            writer.writeBatch(batch1);
            writer.writeBatch(batch2);
        }

        // Initialize fresh empty index manager (no checkpoint file)
        PersistentIndexManager indexManager = new PersistentIndexManager(dataDir);
        assertThat(indexManager.size()).isEqualTo(0);

        // Run rebuilder
        IndexRebuilder rebuilder = new IndexRebuilder(compressor);
        int rebuiltCount = rebuilder.rebuild(dataDir, indexManager);

        assertThat(rebuiltCount).isEqualTo(3);
        assertThat(indexManager.size()).isEqualTo(3);

        // Query spatial
        List<IndexPointer> atPos1 = indexManager.query(IndexQueryFilter.builder().exactBlockPos(pos1).build());
        assertThat(atPos1).hasSize(2);
        assertThat(atPos1.get(0).sequenceId()).isEqualTo(1L);
        assertThat(atPos1.get(1).sequenceId()).isEqualTo(3L);

        // Query player
        List<IndexPointer> playerBResults = indexManager.query(IndexQueryFilter.builder().actorUuid(playerB).build());
        assertThat(playerBResults).hasSize(1);
        assertThat(playerBResults.get(0).sequenceId()).isEqualTo(2L);
    }
}
