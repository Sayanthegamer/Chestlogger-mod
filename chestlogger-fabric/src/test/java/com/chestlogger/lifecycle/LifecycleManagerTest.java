package com.chestlogger.lifecycle;

import com.chestlogger.container.ContainerTracker;
import com.chestlogger.event.*;
import com.chestlogger.index.IndexQueryFilter;
import com.chestlogger.storage.LZ4BlockCompressor;
import com.chestlogger.storage.StorageProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleManagerTest {

    @Test
    @DisplayName("Should start lifecycle, process async queued events, and flush gracefully on shutdown")
    void testAsyncWriterAndShutdownFlush(@TempDir Path tempDir) throws Exception {
        File dataDir = tempDir.toFile();
        TransactionEventQueue queue = new TransactionEventQueue(4096);
        ContainerTracker tracker = new ContainerTracker(queue, 1L);
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        StorageProfile profile = StorageProfile.BALANCED;

        ChestLoggerLifecycleManager manager = new ChestLoggerLifecycleManager(queue, compressor, profile);
        manager.start(dataDir);

        assertThat(manager.isRunning()).isTrue();

        // Simulate 50 transactions offered by game threads
        UUID player = UUID.randomUUID();
        long pos = BlockPosUtil.pack(5, 60, -5);

        for (int i = 1; i <= 50; i++) {
            TransactionLogEntry entry = new TransactionLogEntry(
                    i, System.currentTimeMillis(), UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                    player, "Steve", "minecraft:overworld", pos,
                    List.of(new SlotDelta(0, "minecraft:diamond", 1, 0, 1, 0L))
            );
            queue.offer(entry);
        }

        // Wait brief moment for async writer thread to process
        Thread.sleep(300);

        // Initiate graceful stop
        manager.stop(5000);

        assertThat(manager.isRunning()).isFalse();
        assertThat(queue.getDepth()).isEqualTo(0);

        // Verify all 50 records are written to disk and queryable
        assertThat(manager.getIndexManager().size()).isEqualTo(50);
        List<TransactionLogEntry> retrieved = manager.getQueryEngine().fetchRecords(
                IndexQueryFilter.builder().exactBlockPos(pos).limit(100).build()
        );
        assertThat(retrieved).hasSize(50);

        // Verify index file exists on disk
        File indexFile = new File(dataDir, "index.cidx");
        assertThat(indexFile.exists()).isTrue();
        assertThat(indexFile.length()).isGreaterThan(0);
    }
}
