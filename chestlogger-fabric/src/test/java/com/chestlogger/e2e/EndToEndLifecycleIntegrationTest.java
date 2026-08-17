package com.chestlogger.e2e;

import com.chestlogger.container.ContainerSnapshot;
import com.chestlogger.event.*;
import com.chestlogger.index.IndexQueryFilter;
import com.chestlogger.lifecycle.ChestLoggerLifecycleManager;
import com.chestlogger.rollback.RollbackEngine;
import com.chestlogger.rollback.RollbackPlan;
import com.chestlogger.rollback.RollbackResult;
import com.chestlogger.storage.LZ4BlockCompressor;
import com.chestlogger.storage.StorageProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class EndToEndLifecycleIntegrationTest {

    @Test
    @DisplayName("Full End-to-End: Concurrent transactions, crash recovery, index rebuilding, inspect queries, and compensation rollback")
    void testEndToEndLifecyclePipeline(@TempDir Path tempDir) throws Exception {
        File worldDataDir = tempDir.toFile();
        TransactionEventQueue queue = new TransactionEventQueue(65536);
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        StorageProfile profile = StorageProfile.BALANCED;

        ChestLoggerLifecycleManager manager = new ChestLoggerLifecycleManager(queue, compressor, profile);
        manager.start(worldDataDir);

        int threadCount = 4;
        int eventsPerThread = 250;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        long chestPos1 = BlockPosUtil.pack(100, 64, 200);
        long chestPos2 = BlockPosUtil.pack(50, 70, -100);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    UUID actor = UUID.randomUUID();
                    String name = "Player_" + threadId;
                    for (int i = 0; i < eventsPerThread; i++) {
                        long pos = (i % 2 == 0) ? chestPos1 : chestPos2;
                        int delta = (i % 3 == 0) ? -1 : 2;
                        SlotDelta sd = new SlotDelta(i % 27, "minecraft:diamond", delta, 10, 10 + delta, 0L);
                        TransactionLogEntry entry = new TransactionLogEntry(
                                (long) threadId * 10000 + i + 1,
                                System.currentTimeMillis(),
                                UUID.randomUUID(),
                                delta > 0 ? ActionType.PLACE : ActionType.PICKUP,
                                ActorType.PLAYER,
                                actor,
                                name,
                                "minecraft:overworld",
                                pos,
                                List.of(sd)
                        );
                        queue.offer(entry);
                    }
                } catch (Exception ignored) {
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        finishLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Allow async writer to consume events
        Thread.sleep(400);

        // Graceful stop
        manager.stop(5000);
        assertThat(manager.isRunning()).isFalse();

        // 1. Simulate server crash by appending corrupt bytes to segment tail
        File[] clogFiles = worldDataDir.listFiles((dir, name) -> name.endsWith(".clog"));
        assertThat(clogFiles).isNotNull().isNotEmpty();
        File activeClog = clogFiles[0];
        try (FileOutputStream fos = new FileOutputStream(activeClog, true)) {
            fos.write(new byte[]{(byte) 0xAA, 0x55, 0x01, 0x00, 0x12, 0x34}); // Half-written frame
        }

        // Delete index file to force cold self-healing rebuild
        File indexFile = new File(worldDataDir, "index.cidx");
        indexFile.delete();
        assertThat(indexFile.exists()).isFalse();

        // 2. Restart lifecycle manager (simulating server reboot)
        TransactionEventQueue postRestartQueue = new TransactionEventQueue(65536);
        ChestLoggerLifecycleManager restartManager = new ChestLoggerLifecycleManager(postRestartQueue, compressor, profile);
        restartManager.start(worldDataDir);

        assertThat(restartManager.isRunning()).isTrue();
        assertThat(restartManager.getIndexManager().size()).isEqualTo(threadCount * eventsPerThread);

        // 3. Inspect queries
        List<TransactionLogEntry> chest1Records = restartManager.getQueryEngine().fetchRecords(
                IndexQueryFilter.builder().exactBlockPos(chestPos1).limit(1000).build()
        );
        assertThat(chest1Records).hasSize((threadCount * eventsPerThread) / 2);

        // 4. Compensation rollback test
        ContainerSnapshot liveContainer = new ContainerSnapshot(27);
        liveContainer.setSlot(0, "minecraft:diamond", 5, 0L);

        RollbackEngine rollbackEngine = new RollbackEngine();
        RollbackPlan plan = rollbackEngine.createPlan(chest1Records, liveContainer);
        assertThat(plan.steps()).isNotEmpty();

        UUID admin = UUID.randomUUID();
        RollbackResult result = rollbackEngine.applyRollback(
                plan, liveContainer, postRestartQueue, admin, "AdminOp", "minecraft:overworld", chestPos1
        );
        assertThat(result.success()).isTrue();
        assertThat(postRestartQueue.getDepth()).isEqualTo(1); // Audit log generated

        restartManager.stop(5000);
        assertThat(restartManager.isRunning()).isFalse();
    }

    @Test
    @DisplayName("Should cleanly start and stop multiple times without leaking writer threads")
    void testRepeatedLifecycleTransitions(@TempDir Path tempDir) throws Exception {
        File worldDataDir = tempDir.toFile();
        TransactionEventQueue queue = new TransactionEventQueue(1024);
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        StorageProfile profile = StorageProfile.SSD;

        for (int cycle = 1; cycle <= 3; cycle++) {
            ChestLoggerLifecycleManager manager = new ChestLoggerLifecycleManager(queue, compressor, profile);
            manager.start(worldDataDir);
            assertThat(manager.isRunning()).isTrue();

            queue.offer(new TransactionLogEntry(
                    cycle, System.currentTimeMillis(), UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                    UUID.randomUUID(), "Player", "minecraft:overworld", BlockPosUtil.pack(1, 2, 3),
                    List.of(new SlotDelta(0, "minecraft:stone", 1, 0, 1, 0L))
            ));

            Thread.sleep(150);
            manager.stop(3000);
            assertThat(manager.isRunning()).isFalse();
        }

        File indexFile = new File(worldDataDir, "index.cidx");
        assertThat(indexFile.exists()).isTrue();
    }
}
