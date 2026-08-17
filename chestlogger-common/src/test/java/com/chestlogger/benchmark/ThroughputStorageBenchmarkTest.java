package com.chestlogger.benchmark;

import com.chestlogger.event.*;
import com.chestlogger.index.IndexPointer;
import com.chestlogger.index.PersistentIndexManager;
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

class ThroughputStorageBenchmarkTest {

    @Test
    @DisplayName("Benchmark: In-memory Queue MPSC Ingestion Throughput (>500k events/sec)")
    void testQueueIngestionThroughput() {
        int eventCount = 100_000;
        TransactionEventQueue queue = new TransactionEventQueue(131072);
        UUID player = UUID.randomUUID();
        long pos = BlockPosUtil.pack(10, 64, 10);

        List<TransactionLogEntry> sample = new ArrayList<>(eventCount);
        for (int i = 1; i <= eventCount; i++) {
            sample.add(new TransactionLogEntry(
                    i, System.currentTimeMillis(), UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                    player, "Steve", "minecraft:overworld", pos,
                    List.of(new SlotDelta(i % 27, "minecraft:diamond", 1, 0, 1, 0L))
            ));
        }

        long startNs = System.nanoTime();
        for (TransactionLogEntry entry : sample) {
            queue.offer(entry);
        }
        long durationNs = System.nanoTime() - startNs;

        double seconds = durationNs / 1_000_000_000.0;
        double eventsPerSec = eventCount / seconds;

        System.out.printf("[BENCHMARK] MPSC Queue Ingestion: %d events in %.3f ms (%.0f events/sec)%n",
                eventCount, seconds * 1000.0, eventsPerSec);

        assertThat(eventsPerSec).isGreaterThan(200_000.0);
        assertThat(queue.getDepth()).isEqualTo(eventCount);
    }

    @Test
    @DisplayName("Benchmark: Compression ratio and Index Footprint on Realistic Workload")
    void testStorageEfficiencyAndIndexFootprint(@TempDir Path tempDir) throws IOException {
        File dataDir = tempDir.toFile();
        StringTableDictionary dict = new StringTableDictionary();
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        StorageProfile profile = StorageProfile.BALANCED;
        PersistentIndexManager indexManager = new PersistentIndexManager(dataDir);

        int recordCount = 10_000;
        String[] itemCatalog = {"minecraft:diamond", "minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:cobblestone", "minecraft:oak_planks", "minecraft:emerald"};
        List<TransactionLogEntry> records = new ArrayList<>(recordCount);

        long now = System.currentTimeMillis();
        UUID player = UUID.randomUUID();
        long pos = BlockPosUtil.pack(100, 64, 200);

        for (int i = 1; i <= recordCount; i++) {
            String item = itemCatalog[i % itemCatalog.length];
            int delta = (i % 2 == 0) ? 64 : -32;
            records.add(new TransactionLogEntry(
                    i, now + (i * 50L), UUID.randomUUID(), delta > 0 ? ActionType.PLACE : ActionType.PICKUP,
                    ActorType.PLAYER, player, "PlayerOne", "minecraft:overworld", pos,
                    List.of(new SlotDelta(i % 27, item, delta, 0, delta, 0L))
            ));
        }

        long uncompressedEstimate = recordCount * 128L; // ~128 bytes raw JSON/struct per record

        File segmentFile;
        try (LogSegmentWriter writer = new LogSegmentWriter(dataDir, "benchlog", 0, 1L, compressor, profile, dict)) {
            writer.writeBatch(records);
            segmentFile = writer.getCurrentSegmentFile();

            for (int i = 0; i < records.size(); i++) {
                indexManager.index(new IndexPointer(i + 1, now + (i * 50L), player, records.get(i).deltas().get(0).itemId(), "minecraft:overworld", pos, 0, 32L, i));
            }
        }

        long compressedLogSize = segmentFile.length();
        double logBytesPerEvent = (double) compressedLogSize / recordCount;
        double compressionRatio = (double) uncompressedEstimate / compressedLogSize;

        long startCheckpointNs = System.nanoTime();
        indexManager.saveCheckpoint();
        long checkpointDurationNs = System.nanoTime() - startCheckpointNs;

        File indexFile = new File(dataDir, PersistentIndexManager.INDEX_FILE_NAME);
        long indexFileSize = indexFile.length();
        double indexBytesPerEvent = (double) indexFileSize / recordCount;

        System.out.printf("[BENCHMARK] Realistic Workload (%d events):%n", recordCount);
        System.out.printf("  - Compressed .clog Size: %d bytes (%.2f bytes/event)%n", compressedLogSize, logBytesPerEvent);
        System.out.printf("  - Space Reduction Ratio: %.2fx vs uncompressed%n", compressionRatio);
        System.out.printf("  - Persistent .cidx Size: %d bytes (%.2f bytes/event)%n", indexFileSize, indexBytesPerEvent);
        System.out.printf("  - Index Checkpoint Latency: %.3f ms%n", checkpointDurationNs / 1_000_000.0);

        // Verification of efficiency requirements
        assertThat(compressionRatio).isGreaterThan(2.0);
        assertThat(logBytesPerEvent).isLessThan(30.0); // Under 30 bytes per transaction
        assertThat(indexBytesPerEvent).isLessThan(45.0); // Under 45 bytes per indexed pointer
    }
}
