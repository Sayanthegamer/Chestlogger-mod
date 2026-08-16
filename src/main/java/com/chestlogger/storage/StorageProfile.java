package com.chestlogger.storage;

import java.util.Locale;

/**
 * Hardware-tuned storage engine profile presets.
 *
 * @param name Profile identifier
 * @param queueCapacity In-memory MPSC ring queue capacity
 * @param maxBatchEvents Max transaction events per compressed block
 * @param maxBatchBytes Max uncompressed bytes before triggering flush
 * @param flushIntervalMs Maximum background flush delay
 * @param forceFsync Whether to invoke FileChannel.force(false) on every block flush
 * @param maxSegmentSizeBytes Maximum size before segment rotation (e.g. 64MB)
 */
public record StorageProfile(
        String name,
        int queueCapacity,
        int maxBatchEvents,
        int maxBatchBytes,
        long flushIntervalMs,
        boolean forceFsync,
        long maxSegmentSizeBytes
) {
    public static final StorageProfile BALANCED = new StorageProfile(
            "balanced",
            65536,
            1000,
            256 * 1024,
            1000L,
            false,
            64L * 1024L * 1024L
    );

    public static final StorageProfile HDD = new StorageProfile(
            "hdd",
            131072,
            5000,
            1024 * 1024,
            5000L,
            false,
            128L * 1024L * 1024L
    );

    public static final StorageProfile SSD = new StorageProfile(
            "ssd",
            32768,
            250,
            64 * 1024,
            150L,
            true,
            64L * 1024L * 1024L
    );

    public static StorageProfile fromName(String name) {
        if (name == null) return BALANCED;
        return switch (name.toLowerCase(Locale.ROOT).trim()) {
            case "hdd" -> HDD;
            case "ssd" -> SSD;
            default -> BALANCED;
        };
    }
}
