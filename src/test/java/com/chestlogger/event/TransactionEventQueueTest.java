package com.chestlogger.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionEventQueueTest {

    private TransactionLogEntry createSampleEntry(long seq) {
        return new TransactionLogEntry(
                seq,
                System.currentTimeMillis(),
                UUID.randomUUID(),
                ActionType.PICKUP,
                ActorType.PLAYER,
                UUID.randomUUID(),
                "Player" + seq,
                "minecraft:overworld",
                BlockPosUtil.pack(0, 64, 0),
                List.of(new SlotDelta(0, "minecraft:stone", 1, 0, 1, 0L))
        );
    }

    @Test
    @DisplayName("Should enqueue and drain events sequentially")
    void testBasicEnqueueAndDrain() {
        TransactionEventQueue queue = new TransactionEventQueue(1024);
        assertThat(queue.getDepth()).isEqualTo(0);
        assertThat(queue.getCapacity()).isEqualTo(1024);

        TransactionLogEntry e1 = createSampleEntry(1);
        TransactionLogEntry e2 = createSampleEntry(2);

        assertThat(queue.offer(e1)).isTrue();
        assertThat(queue.offer(e2)).isTrue();

        assertThat(queue.getDepth()).isEqualTo(2);
        assertThat(queue.getEnqueuedCount()).isEqualTo(2);
        assertThat(queue.getDroppedCount()).isEqualTo(0);

        List<TransactionLogEntry> drained = new ArrayList<>();
        int count = queue.drain(drained, 10);

        assertThat(count).isEqualTo(2);
        assertThat(drained).containsExactly(e1, e2);
        assertThat(queue.getDepth()).isEqualTo(0);
        assertThat(queue.getDrainedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should handle bounded capacity overflow without blocking")
    void testQueueOverflowPolicy() {
        int smallCapacity = 4;
        TransactionEventQueue queue = new TransactionEventQueue(smallCapacity);

        for (int i = 0; i < smallCapacity; i++) {
            assertThat(queue.offer(createSampleEntry(i))).isTrue();
        }

        assertThat(queue.getDepth()).isEqualTo(4);

        // 5th event must overflow and be dropped without throwing or blocking
        boolean accepted = queue.offer(createSampleEntry(99));
        assertThat(accepted).isFalse();
        assertThat(queue.getDroppedCount()).isEqualTo(1);
        assertThat(queue.getDepth()).isEqualTo(4);
    }

    @Test
    @DisplayName("Should sustain high concurrency multi-producer ingestion")
    void testConcurrentProducers() throws InterruptedException {
        int producerCount = 8;
        int eventsPerProducer = 1000;
        int totalExpected = producerCount * eventsPerProducer;

        TransactionEventQueue queue = new TransactionEventQueue(32768);
        ExecutorService executor = Executors.newFixedThreadPool(producerCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(producerCount);

        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < eventsPerProducer; i++) {
                        queue.offer(createSampleEntry((long) producerId * 10000 + i));
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        executor.shutdown();

        assertThat(queue.getEnqueuedCount()).isEqualTo(totalExpected);
        assertThat(queue.getDroppedCount()).isEqualTo(0);
        assertThat(queue.getDepth()).isEqualTo(totalExpected);

        List<TransactionLogEntry> drained = new ArrayList<>();
        int drainedTotal = 0;
        while (queue.getDepth() > 0) {
            drainedTotal += queue.drain(drained, 500);
        }

        assertThat(drainedTotal).isEqualTo(totalExpected);
        assertThat(drained).hasSize(totalExpected);
        assertThat(queue.getDepth()).isEqualTo(0);
    }
}
