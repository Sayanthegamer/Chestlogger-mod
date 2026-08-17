package com.chestlogger.security;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Thread-safe tracker recording container access velocity per actor over a sliding time window
 * to detect rapid multi-container raid bursts (e.g. >= 3 distinct container positions within 300 seconds).
 */
public final class RaidVelocityTracker {

    /**
     * Default sliding window duration: 300 seconds (5 minutes).
     */
    public static final long DEFAULT_WINDOW_DURATION_MS = 300_000L;

    /**
     * Default burst threshold: 3 distinct container positions.
     */
    public static final int DEFAULT_BURST_THRESHOLD = 3;

    public record ContainerAccess(long packedPos, long timestampMs) {}

    private final long windowDurationMs;
    private final int burstThreshold;
    private final ConcurrentHashMap<UUID, Deque<ContainerAccess>> actorAccesses;

    /**
     * Constructs a RaidVelocityTracker with default parameters (300s window, threshold 3).
     */
    public RaidVelocityTracker() {
        this(DEFAULT_WINDOW_DURATION_MS, DEFAULT_BURST_THRESHOLD);
    }

    /**
     * Constructs a RaidVelocityTracker with custom window duration and burst threshold.
     *
     * @param windowDurationMs Sliding window duration in milliseconds.
     * @param burstThreshold Distinct container count threshold required to flag a raid burst.
     */
    public RaidVelocityTracker(long windowDurationMs, int burstThreshold) {
        if (windowDurationMs <= 0) {
            throw new IllegalArgumentException("windowDurationMs must be positive: " + windowDurationMs);
        }
        if (burstThreshold <= 0) {
            throw new IllegalArgumentException("burstThreshold must be positive: " + burstThreshold);
        }
        this.windowDurationMs = windowDurationMs;
        this.burstThreshold = burstThreshold;
        this.actorAccesses = new ConcurrentHashMap<>();
    }

    /**
     * Records a container access event for an actor.
     *
     * @param actorUuid UUID of the actor interacting with the container.
     * @param packedPos 64-bit packed block coordinates of the container.
     * @param timestampMs Epoch timestamp in milliseconds.
     */
    public void recordAccess(UUID actorUuid, long packedPos, long timestampMs) {
        if (actorUuid == null) {
            return;
        }
        Deque<ContainerAccess> deque = actorAccesses.computeIfAbsent(actorUuid, k -> new ConcurrentLinkedDeque<>());
        synchronized (deque) {
            deque.addLast(new ContainerAccess(packedPos, timestampMs));
            // Prune accesses older than timestampMs - windowDurationMs
            long cutoff = timestampMs - windowDurationMs;
            while (!deque.isEmpty() && deque.peekFirst().timestampMs() < cutoff) {
                deque.pollFirst();
            }
        }
    }

    /**
     * Records a container access and checks if the actor is currently in a raid burst state.
     *
     * @param actorUuid UUID of the actor.
     * @param packedPos 64-bit packed block coordinates of the container.
     * @param timestampMs Epoch timestamp in milliseconds.
     * @return true if distinct container count in the active window meets or exceeds the burst threshold.
     */
    public boolean recordAndCheckBurst(UUID actorUuid, long packedPos, long timestampMs) {
        recordAccess(actorUuid, packedPos, timestampMs);
        return isRaidBurst(actorUuid, timestampMs);
    }

    /**
     * Checks if the actor has triggered a raid burst at the given timestamp.
     *
     * @param actorUuid UUID of the actor.
     * @param timestampMs Current timestamp to evaluate sliding window against.
     * @return true if >= burstThreshold distinct container positions were accessed within the window.
     */
    public boolean isRaidBurst(UUID actorUuid, long timestampMs) {
        return getDistinctContainerCount(actorUuid, timestampMs) >= burstThreshold;
    }

    /**
     * Returns the count of distinct container positions accessed by the actor within the sliding window.
     *
     * @param actorUuid UUID of the actor.
     * @param timestampMs Current timestamp.
     * @return Number of unique packed container positions touched in [timestampMs - windowDurationMs, timestampMs].
     */
    public int getDistinctContainerCount(UUID actorUuid, long timestampMs) {
        return getDistinctContainersInWindow(actorUuid, timestampMs).size();
    }

    /**
     * Returns the set of distinct packed container positions accessed by the actor in the sliding window.
     *
     * @param actorUuid UUID of the actor.
     * @param timestampMs Current timestamp.
     * @return Set of unique packed positions.
     */
    public Set<Long> getDistinctContainersInWindow(UUID actorUuid, long timestampMs) {
        if (actorUuid == null) {
            return Set.of();
        }
        Deque<ContainerAccess> deque = actorAccesses.get(actorUuid);
        if (deque == null) {
            return Set.of();
        }

        long minTime = timestampMs - windowDurationMs;
        Set<Long> uniquePositions = new HashSet<>();

        synchronized (deque) {
            for (ContainerAccess access : deque) {
                if (access.timestampMs() >= minTime && access.timestampMs() <= timestampMs) {
                    uniquePositions.add(access.packedPos());
                }
            }
        }
        return Collections.unmodifiableSet(uniquePositions);
    }

    /**
     * Prunes expired access entries across all tracked actors.
     *
     * @param currentTimestampMs Current epoch timestamp in milliseconds.
     */
    public void pruneExpired(long currentTimestampMs) {
        long cutoff = currentTimestampMs - windowDurationMs;
        Iterator<Map.Entry<UUID, Deque<ContainerAccess>>> iterator = actorAccesses.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Deque<ContainerAccess>> entry = iterator.next();
            Deque<ContainerAccess> deque = entry.getValue();
            synchronized (deque) {
                while (!deque.isEmpty() && deque.peekFirst().timestampMs() < cutoff) {
                    deque.pollFirst();
                }
                if (deque.isEmpty()) {
                    iterator.remove();
                }
            }
        }
    }

    /**
     * Clears all tracking records for all actors.
     */
    public void clear() {
        actorAccesses.clear();
    }

    /**
     * Clears tracking records for a specific actor.
     *
     * @param actorUuid UUID of the actor.
     */
    public void clearActor(UUID actorUuid) {
        if (actorUuid != null) {
            actorAccesses.remove(actorUuid);
        }
    }

    /**
     * Returns the configured sliding window duration in milliseconds.
     */
    public long getWindowDurationMs() {
        return windowDurationMs;
    }

    /**
     * Returns the configured distinct container burst threshold.
     */
    public int getBurstThreshold() {
        return burstThreshold;
    }

    /**
     * Returns the number of currently tracked active actors.
     */
    public int getTrackedActorsCount() {
        return actorAccesses.size();
    }
}
