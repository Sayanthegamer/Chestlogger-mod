package com.chestlogger.query;

import com.chestlogger.container.ContainerTracker;
import com.chestlogger.event.*;
import com.chestlogger.index.IndexPointer;
import com.chestlogger.index.IndexQueryFilter;
import com.chestlogger.index.PersistentIndexManager;
import com.chestlogger.lifecycle.ChestLoggerLifecycleManager;
import com.chestlogger.storage.LZ4BlockCompressor;
import com.chestlogger.storage.StorageProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LiveQueryDictionaryTest {

    @Test
    @DisplayName("Should query live unclosed segment records without Invalid dictionary id error")
    void testQueryWhileWriterIsOpen(@TempDir Path tempDir) throws Exception {
        File dataDir = tempDir.toFile();
        TransactionEventQueue queue = new TransactionEventQueue(1024);
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        StorageProfile profile = StorageProfile.SSD;

        ChestLoggerLifecycleManager manager = new ChestLoggerLifecycleManager(queue, compressor, profile);
        manager.start(dataDir);

        UUID player = UUID.randomUUID();
        long pos = BlockPosUtil.pack(12, 64, -34);

        // Offer live event
        TransactionLogEntry entry = new TransactionLogEntry(
                1L, System.currentTimeMillis(), UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                player, "Alex", "minecraft:overworld", pos,
                List.of(new SlotDelta(0, "minecraft:diamond", 64, 0, 64, 0L))
        );
        queue.offer(entry);

        // Wait for async writer to write the block to disk
        Thread.sleep(300);

        // Live inspect query while server/writer is running
        List<TransactionLogEntry> results = manager.getQueryEngine().fetchRecords(
                IndexQueryFilter.builder().exactBlockPos(pos).build()
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).deltas().get(0).itemId()).isEqualTo("minecraft:diamond");
        assertThat(results.get(0).actorName()).isEqualTo("Alex");

        manager.stop(3000);
    }
}
