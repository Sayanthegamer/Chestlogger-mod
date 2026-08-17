package com.chestlogger.security;

import com.chestlogger.event.BlockPosUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RaidVelocityTrackerTest {

    private RaidVelocityTracker tracker;
    private UUID actor1;
    private UUID actor2;

    @BeforeEach
    void setUp() {
        tracker = new RaidVelocityTracker(300_000L, 3);
        actor1 = UUID.randomUUID();
        actor2 = UUID.randomUUID();
    }

    @Test
    @DisplayName("Invalid constructor arguments throw IllegalArgumentException")
    void testConstructorValidation() {
        assertThatThrownBy(() -> new RaidVelocityTracker(0, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RaidVelocityTracker(300_000L, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Repeated access to the same container does not trigger raid burst")
    void testRepeatedSameContainerAccess() {
        long pos1 = BlockPosUtil.pack(10, 64, 10);
        long baseTime = 1_000_000L;

        // Actor 1 accesses pos1 ten times within 30 seconds
        for (int i = 0; i < 10; i++) {
            boolean burst = tracker.recordAndCheckBurst(actor1, pos1, baseTime + (i * 3000L));
            assertThat(burst).isFalse();
        }

        assertThat(tracker.getDistinctContainerCount(actor1, baseTime + 30_000L)).isEqualTo(1);
        assertThat(tracker.isRaidBurst(actor1, baseTime + 30_000L)).isFalse();
    }

    @Test
    @DisplayName("Raid burst triggers when threshold of 3 distinct containers is reached in 300s window")
    void testRaidBurstTrigger() {
        long pos1 = BlockPosUtil.pack(10, 64, 10);
        long pos2 = BlockPosUtil.pack(20, 64, 20);
        long pos3 = BlockPosUtil.pack(30, 64, 30);
        long baseTime = 1_000_000L;

        // 1st container
        assertThat(tracker.recordAndCheckBurst(actor1, pos1, baseTime)).isFalse();
        assertThat(tracker.getDistinctContainerCount(actor1, baseTime)).isEqualTo(1);

        // 2nd container
        assertThat(tracker.recordAndCheckBurst(actor1, pos2, baseTime + 10_000L)).isFalse();
        assertThat(tracker.getDistinctContainerCount(actor1, baseTime + 10_000L)).isEqualTo(2);

        // 3rd container -> triggers raid burst!
        assertThat(tracker.recordAndCheckBurst(actor1, pos3, baseTime + 20_000L)).isTrue();
        assertThat(tracker.getDistinctContainerCount(actor1, baseTime + 20_000L)).isEqualTo(3);
        assertThat(tracker.isRaidBurst(actor1, baseTime + 20_000L)).isTrue();
    }

    @Test
    @DisplayName("Sliding window expiration: accesses older than 300s expire correctly")
    void testSlidingWindowExpiration() {
        long pos1 = BlockPosUtil.pack(10, 64, 10);
        long pos2 = BlockPosUtil.pack(20, 64, 20);
        long pos3 = BlockPosUtil.pack(30, 64, 30);
        long pos4 = BlockPosUtil.pack(40, 64, 40);

        long t0 = 0L;
        long t1 = 100_000L;
        long t2 = 250_000L;

        tracker.recordAccess(actor1, pos1, t0);
        tracker.recordAccess(actor1, pos2, t1);
        tracker.recordAccess(actor1, pos3, t2);

        // At t = 250_000ms: all 3 positions are in [0, 250_000ms] -> raid burst active!
        assertThat(tracker.getDistinctContainerCount(actor1, t2)).isEqualTo(3);
        assertThat(tracker.isRaidBurst(actor1, t2)).isTrue();

        // At t = 350_000ms: window is [50_000ms, 350_000ms]. Pos 1 (t0 = 0ms) has expired!
        long t3 = 350_000L;
        assertThat(tracker.getDistinctContainerCount(actor1, t3)).isEqualTo(2);
        assertThat(tracker.isRaidBurst(actor1, t3)).isFalse();

        // New access at t = 360_000ms to pos4: window [60_000ms, 360_000ms] contains pos2, pos3, pos4 -> burst again!
        long t4 = 360_000L;
        assertThat(tracker.recordAndCheckBurst(actor1, pos4, t4)).isTrue();
        assertThat(tracker.getDistinctContainerCount(actor1, t4)).isEqualTo(3);
    }

    @Test
    @DisplayName("Pruning expired entries clears old records and bounded memory")
    void testPruneExpired() {
        long pos1 = BlockPosUtil.pack(10, 64, 10);
        long pos2 = BlockPosUtil.pack(20, 64, 20);

        tracker.recordAccess(actor1, pos1, 10_000L);
        tracker.recordAccess(actor2, pos2, 20_000L);

        assertThat(tracker.getTrackedActorsCount()).isEqualTo(2);

        // Pruning at 100_000ms (cutoff is 100_000 - 300_000 = -200_000) -> nothing expired
        tracker.pruneExpired(100_000L);
        assertThat(tracker.getTrackedActorsCount()).isEqualTo(2);

        // Pruning at 500_000ms (cutoff is 200_000ms) -> all accesses are expired
        tracker.pruneExpired(500_000L);
        assertThat(tracker.getTrackedActorsCount()).isEqualTo(0);
        assertThat(tracker.getDistinctContainerCount(actor1, 500_000L)).isEqualTo(0);
    }

    @Test
    @DisplayName("Clear and clearActor reset tracking state")
    void testClearOperations() {
        long pos = BlockPosUtil.pack(10, 64, 10);
        tracker.recordAccess(actor1, pos, 1000L);
        tracker.recordAccess(actor2, pos, 1000L);

        tracker.clearActor(actor1);
        assertThat(tracker.getDistinctContainerCount(actor1, 1000L)).isEqualTo(0);
        assertThat(tracker.getDistinctContainerCount(actor2, 1000L)).isEqualTo(1);

        tracker.clear();
        assertThat(tracker.getTrackedActorsCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Concurrent multi-threaded recording and burst checking")
    void testConcurrentTrackerOperations() throws InterruptedException {
        int threadCount = 16;
        int operationsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        List<UUID> actors = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        AtomicInteger totalOps = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            int threadId = t;
            executor.submit(() -> {
                try {
                    Random rand = new Random(threadId);
                    for (int i = 0; i < operationsPerThread; i++) {
                        UUID actor = actors.get(rand.nextInt(actors.size()));
                        long pos = BlockPosUtil.pack(rand.nextInt(100), 64, rand.nextInt(100));
                        long time = 1_000_000L + (i * 100L);

                        tracker.recordAndCheckBurst(actor, pos, time);
                        tracker.getDistinctContainerCount(actor, time);
                        if (i % 100 == 0) {
                            tracker.pruneExpired(time);
                        }
                        totalOps.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(finished).isTrue();
        assertThat(totalOps.get()).isEqualTo(threadCount * operationsPerThread);
    }
}
