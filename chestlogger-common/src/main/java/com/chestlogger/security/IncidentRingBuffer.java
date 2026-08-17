package com.chestlogger.security;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Thread-safe fixed-capacity ring buffer holding the most recent SecurityIncidents.
 * When the buffer reaches capacity (default: 200), the oldest incident is evicted to make room for new ones.
 */
public class IncidentRingBuffer {

    public static final int DEFAULT_CAPACITY = 200;

    private final int capacity;
    private final Deque<SecurityIncident> buffer;

    public IncidentRingBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public IncidentRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(capacity);
    }

    /**
     * Adds an incident to the ring buffer, evicting the oldest incident if capacity is reached.
     *
     * @param incident SecurityIncident to store.
     */
    public synchronized void add(SecurityIncident incident) {
        Objects.requireNonNull(incident, "incident cannot be null");
        if (buffer.size() >= capacity) {
            buffer.removeFirst();
        }
        buffer.addLast(incident);
    }

    /**
     * Returns a snapshot of all stored incidents in newest-to-oldest order.
     *
     * @return Immutable list of incidents (newest first).
     */
    public synchronized List<SecurityIncident> getAll() {
        List<SecurityIncident> list = new ArrayList<>(buffer);
        Collections.reverse(list);
        return Collections.unmodifiableList(list);
    }

    /**
     * Returns a snapshot of stored incidents in chronological (oldest-to-newest) order.
     *
     * @return Immutable list of incidents (oldest first).
     */
    public synchronized List<SecurityIncident> getChronological() {
        return Collections.unmodifiableList(new ArrayList<>(buffer));
    }

    /**
     * Current number of incidents in the ring buffer.
     */
    public synchronized int size() {
        return buffer.size();
    }

    /**
     * Maximum capacity of this ring buffer.
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Whether the ring buffer is currently empty.
     */
    public synchronized boolean isEmpty() {
        return buffer.isEmpty();
    }

    /**
     * Clears all stored incidents from the ring buffer.
     */
    public synchronized void clear() {
        buffer.clear();
    }
}
