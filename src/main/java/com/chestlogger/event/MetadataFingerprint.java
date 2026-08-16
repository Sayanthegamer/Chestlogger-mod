package com.chestlogger.event;

/**
 * Computes deterministic 64-bit fingerprints for item metadata / components.
 * Utilizes 64-bit FNV-1a / Murmur3 mixing for high dispersion and low collision.
 */
public final class MetadataFingerprint {
    public static final long EMPTY = 0L;
    private static final long FNV_64_INIT = 0xcbf29ce484222325L;
    private static final long FNV_64_PRIME = 0x100000001b3L;

    private MetadataFingerprint() {}

    public static long compute(byte[] data) {
        if (data == null || data.length == 0) {
            return EMPTY;
        }
        long hash = FNV_64_INIT;
        for (byte b : data) {
            hash ^= (b & 0xFF);
            hash *= FNV_64_PRIME;
        }
        // Murmur3 finalizer mix
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;

        return hash == 0L ? 1L : hash; // 0L reserved for EMPTY
    }
}
