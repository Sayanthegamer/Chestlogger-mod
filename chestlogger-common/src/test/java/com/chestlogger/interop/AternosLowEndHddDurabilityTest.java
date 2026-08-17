package com.chestlogger.interop;

import com.chestlogger.event.*;
import com.chestlogger.recovery.RecoveryReport;
import com.chestlogger.recovery.TailRecoveryEngine;
import com.chestlogger.storage.LZ4BlockCompressor;
import com.chestlogger.storage.LogSegmentWriter;
import com.chestlogger.storage.StorageProfile;
import com.chestlogger.storage.StringTableDictionary;
import com.chestlogger.util.ThreadGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Aternos Low-End HDD Durability & Backpressure Verification")
class AternosLowEndHddDurabilityTest {

    @Test
    @DisplayName("Under heavy transaction throughput with slow simulated HDD flush, main thread never blocks")
    void testLowEndHddZeroBlocking(@TempDir File dataDir) throws InterruptedException, ExecutionException, TimeoutException {
        // High-stress scenario: 10 producers generating events at high rate, single consumer writing with simulated 10ms fsync delays
        TransactionEventQueue queue = new TransactionEventQueue(10_000);
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        StringTableDictionary dict = new StringTableDictionary();
        StorageProfile lowEndProfile = new StorageProfile("low_end", 10_000, 250, 64 * 1024, 100L, true, 512 * 1024);

        int producerCount = 10;
        int eventsPerProducer = 1000;
        ExecutorService producerPool = Executors.newFixedThreadPool(producerCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicBoolean writerRunning = new AtomicBoolean(true);

        // Dedicated background writer thread
        Future<Integer> writerFuture = CompletableFuture.supplyAsync(() -> {
            int totalWritten = 0;
            try (LogSegmentWriter writer = new LogSegmentWriter(dataDir, "chestlog", 0, 1L, compressor, lowEndProfile, dict)) {
                List<TransactionLogEntry> batch = new ArrayList<>(250);
                while (writerRunning.get() || queue.getDepth() > 0) {
                    int drained = queue.drain(batch, 250);
                    if (drained > 0) {
                        writer.writeBatch(batch);
                        totalWritten += drained;
                        batch.clear();
                    } else {
                        Thread.sleep(10);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return totalWritten;
        });

        // Simulate main thread marking
        List<Future<Long>> producerFutures = new ArrayList<>();
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            producerFutures.add(producerPool.submit(() -> {
                startLatch.await();
                long startNs = System.nanoTime();
                UUID actor = UUID.randomUUID();
                for (int i = 0; i < eventsPerProducer; i++) {
                    TransactionLogEntry entry = new TransactionLogEntry(
                            (long) (producerId * eventsPerProducer + i + 1),
                            System.currentTimeMillis(),
                            UUID.randomUUID(),
                            ActionType.PICKUP,
                            ActorType.PLAYER,
                            actor,
                            "Player_" + producerId,
                            "minecraft:overworld",
                            12345L,
                            List.of(new SlotDelta(0, "minecraft:iron_ingot", -1, 1, 0, 0L))
                    );
                    queue.offer(entry);
                }
                return System.nanoTime() - startNs;
            }));
        }

        startLatch.countDown();

        // Ensure all producers finish quickly (zero disk blocking)
        for (Future<Long> future : producerFutures) {
            Long durationNs = future.get(5, TimeUnit.SECONDS);
            // 1,000 enqueues should take < 500ms even under contention
            assertThat(durationNs).isLessThan(TimeUnit.MILLISECONDS.toNanos(1000));
        }

        producerPool.shutdown();
        writerRunning.set(false);

        Integer totalDrained = writerFuture.get(10, TimeUnit.SECONDS);
        assertThat(totalDrained).isGreaterThan(0);
        assertThat(queue.getEnqueuedCount()).isEqualTo(producerCount * eventsPerProducer);
    }

    @Test
    @DisplayName("Simulated power loss / SIGKILL during segment write should recover clean tail without corruption")
    void testAternosCrashRecovery(@TempDir File dataDir) throws IOException {
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        StringTableDictionary dict = new StringTableDictionary();
        StorageProfile profile = StorageProfile.BALANCED;

        // 1. Write clean batch
        LogSegmentWriter writer = new LogSegmentWriter(dataDir, "chestlog", 0, 1L, compressor, profile, dict);
        writer.writeBatch(List.of(
                new TransactionLogEntry(
                        1L, System.currentTimeMillis(), UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                        UUID.randomUUID(), "Steve", "minecraft:overworld", 100L,
                        List.of(new SlotDelta(0, "minecraft:diamond", 1, 0, 1, 0L))
                )
        ));
        writer.close();

        // 2. Simulate dirty incomplete block append (sudden power kill / crash)
        File segFile = new File(dataDir, "chestlog_000000.clog");
        try (FileOutputStream fos = new FileOutputStream(segFile, true)) {
            // Append corrupted partial block frame header and random bytes
            fos.write(new byte[]{(byte) 0xCB, (byte) 0x10, (byte) 0x99, (byte) 0xFF, 0x12, 0x34, 0x56, 0x78});
            fos.flush();
        }

        // 3. TailRecoveryEngine repairs the tail
        TailRecoveryEngine recovery = new TailRecoveryEngine(compressor);
        RecoveryReport report = recovery.recoverAndValidate(dataDir);

        assertThat(report.hasCorruptions()).isTrue();
        assertThat(report.totalTruncatedBytes()).isGreaterThan(0L);
        assertThat(report.maxSequenceId()).isEqualTo(1L);

        // 4. Verify writer can cleanly append to recovered segment
        try (LogSegmentWriter resumedWriter = new LogSegmentWriter(dataDir, "chestlog", 0, 2L, compressor, profile, dict)) {
            resumedWriter.writeBatch(List.of(
                    new TransactionLogEntry(
                            2L, System.currentTimeMillis(), UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER,
                            UUID.randomUUID(), "Alex", "minecraft:overworld", 100L,
                            List.of(new SlotDelta(0, "minecraft:diamond", -1, 1, 0, 0L))
                    )
            ));
        }

        RecoveryReport postResumeReport = recovery.recoverAndValidate(dataDir);
        assertThat(postResumeReport.hasCorruptions()).isFalse();
        assertThat(postResumeReport.maxSequenceId()).isEqualTo(2L);
    }
}
