package com.chestlogger.event;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance, lock-free bounded MPSC (Multi-Producer Single-Consumer) queue
 * for enqueuing transaction events from server/world threads without blocking disk I/O.
 */
public final class TransactionEventQueue {
    private final int capacity;
    private final ConcurrentLinkedQueue<TransactionLogEntry> queue;
    private final AtomicInteger depth;
    private final AtomicLong enqueuedCount;
    private final AtomicLong droppedCount;
    private final AtomicLong drainedCount;

    public TransactionEventQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Queue capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.queue = new ConcurrentLinkedQueue<>();
        this.depth = new AtomicInteger(0);
        this.enqueuedCount = new AtomicLong(0L);
        this.droppedCount = new AtomicLong(0L);
        this.drainedCount = new AtomicLong(0L);
    }

    /**
     * Non-blocking ingestion method.
     * Guaranteed never to block the caller thread.
     *
     * @param entry the immutable transaction record
     * @return true if accepted, false if dropped due to capacity saturation
     */
    public boolean offer(TransactionLogEntry entry) {
        Objects.requireNonNull(entry, "entry cannot be null");

        while (true) {
            int currentDepth = depth.get();
            if (currentDepth >= capacity) {
                droppedCount.incrementAndGet();
                return false;
            }
            if (depth.compareAndSet(currentDepth, currentDepth + 1)) {
                queue.offer(entry);
                enqueuedCount.incrementAndGet();
                return true;
            }
        }
    }

    /**
     * Drains up to maxBatchSize records into the target collection.
     * Intended to be called by the dedicated single writer thread.
     *
     * @param target the target collection
     * @param maxBatchSize maximum records to extract
     * @return number of records drained
     */
    public int drain(Collection<TransactionLogEntry> target, int maxBatchSize) {
        Objects.requireNonNull(target, "target collection cannot be null");
        int count = 0;
        while (count < maxBatchSize) {
            TransactionLogEntry entry = queue.poll();
            if (entry == null) {
                break;
            }
            depth.decrementAndGet();
            target.add(entry);
            count++;
        }
        drainedCount.addAndGet(count);
        return count;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getDepth() {
        return depth.get();
    }

    public boolean isEmpty() {
        return depth.get() == 0;
    }

    public long getEnqueuedCount() {
        return enqueuedCount.get();
    }

    public long getDroppedCount() {
        return droppedCount.get();
    }

    public long getDrainedCount() {
        return drainedCount.get();
    }

    public void resetDiagnostics() {
        enqueuedCount.set(0L);
        droppedCount.set(0L);
        drainedCount.set(0L);
    }
}
