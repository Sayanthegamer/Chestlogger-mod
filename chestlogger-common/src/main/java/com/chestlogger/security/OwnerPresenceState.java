package com.chestlogger.security;

/**
 * Represents the presence state and proximity of a container owner relative to their container.
 *
 * @param isOnline Whether the owner is currently connected to the server.
 * @param distanceBlocks Euclidean distance in blocks between the container and the owner (-1.0 if offline/unknown).
 * @param isNearby Whether the owner is within the consensual co-presence proximity radius (<= 24 blocks).
 */
public record OwnerPresenceState(
        boolean isOnline,
        double distanceBlocks,
        boolean isNearby
) {
    /**
     * Default threshold radius in blocks within which an owner is considered nearby (co-present).
     */
    public static final double DEFAULT_NEARBY_THRESHOLD = 24.0;

    /**
     * Creates an offline presence state.
     */
    public static OwnerPresenceState offline() {
        return new OwnerPresenceState(false, -1.0, false);
    }

    /**
     * Creates an online presence state using the default 24-block proximity threshold.
     *
     * @param distanceBlocks Euclidean distance in blocks to the container.
     */
    public static OwnerPresenceState online(double distanceBlocks) {
        return online(distanceBlocks, DEFAULT_NEARBY_THRESHOLD);
    }

    /**
     * Creates an online presence state using a custom proximity threshold.
     *
     * @param distanceBlocks Euclidean distance in blocks to the container.
     * @param nearbyThreshold Maximum distance in blocks to be considered nearby.
     */
    public static OwnerPresenceState online(double distanceBlocks, double nearbyThreshold) {
        boolean nearby = distanceBlocks >= 0.0 && distanceBlocks <= nearbyThreshold;
        return new OwnerPresenceState(true, distanceBlocks, nearby);
    }

    /**
     * Explicit factory for an online, nearby owner.
     */
    public static OwnerPresenceState onlineNearby(double distanceBlocks) {
        return new OwnerPresenceState(true, distanceBlocks, true);
    }

    /**
     * Explicit factory for an online, absent/distant owner.
     */
    public static OwnerPresenceState onlineAbsent(double distanceBlocks) {
        return new OwnerPresenceState(true, distanceBlocks, false);
    }

    /**
     * Constructs an OwnerPresenceState from online status and distance with default threshold.
     */
    public static OwnerPresenceState of(boolean isOnline, double distanceBlocks) {
        if (!isOnline) {
            return offline();
        }
        return online(distanceBlocks, DEFAULT_NEARBY_THRESHOLD);
    }
}
