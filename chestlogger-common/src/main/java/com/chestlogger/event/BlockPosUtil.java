package com.chestlogger.event;

/**
 * Utility for packing and unpacking 3D world BlockPos into a 64-bit primitive long.
 * Bit allocation:
 *   X: 26 bits signed ([-33,554,432, +33,554,431])
 *   Z: 26 bits signed ([-33,554,432, +33,554,431])
 *   Y: 12 bits signed ([-2048, +2047])
 */
public final class BlockPosUtil {
    private static final long X_MASK = 0x3FFFFFFL;
    private static final long Z_MASK = 0x3FFFFFFL;
    private static final long Y_MASK = 0xFFFL;

    private static final int Z_SHIFT = 12;
    private static final int X_SHIFT = 38;

    private BlockPosUtil() {}

    public static long pack(int x, int y, int z) {
        return (((long) x & X_MASK) << X_SHIFT)
                | (((long) z & Z_MASK) << Z_SHIFT)
                | ((long) y & Y_MASK);
    }

    public static int unpackX(long packed) {
        int x = (int) (packed >> X_SHIFT);
        // Sign extend from 26 bits
        return (x << 6) >> 6;
    }

    public static int unpackY(long packed) {
        int y = (int) (packed & Y_MASK);
        // Sign extend from 12 bits
        return (y << 20) >> 20;
    }

    public static int unpackZ(long packed) {
        int z = (int) ((packed >> Z_SHIFT) & Z_MASK);
        // Sign extend from 26 bits
        return (z << 6) >> 6;
    }

    public static int[] unpack(long packed) {
        return new int[]{unpackX(packed), unpackY(packed), unpackZ(packed)};
    }
}
