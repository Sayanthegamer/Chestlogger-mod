package com.chestlogger.storage;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * High-efficiency VarInt and VarLong variable-byte serialization utilities.
 */
public final class VarIntUtil {
    private VarIntUtil() {}

    public static void writeVarInt(OutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    public static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            int b = in.read();
            if (b == -1) {
                throw new EOFException("Unexpected EOF while reading VarInt");
            }
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) {
                break;
            }
            position += 7;
            if (position >= 32) {
                throw new IllegalArgumentException("VarInt exceeds 32 bits");
            }
        }
        return value;
    }

    public static void writeSignedVarInt(OutputStream out, int value) throws IOException {
        writeVarInt(out, (value << 1) ^ (value >> 31)); // ZigZag
    }

    public static int readSignedVarInt(InputStream in) throws IOException {
        int raw = readVarInt(in);
        return (raw >>> 1) ^ -(raw & 1); // ZigZag decode
    }

    public static void writeVarLong(OutputStream out, long value) throws IOException {
        while ((value & ~0x7FL) != 0L) {
            out.write((int) ((value & 0x7FL) | 0x80L));
            value >>>= 7;
        }
        out.write((int) value);
    }

    public static long readVarLong(InputStream in) throws IOException {
        long value = 0;
        int position = 0;
        while (true) {
            int b = in.read();
            if (b == -1) {
                throw new EOFException("Unexpected EOF while reading VarLong");
            }
            value |= (long) (b & 0x7F) << position;
            if ((b & 0x80) == 0) {
                break;
            }
            position += 7;
            if (position >= 64) {
                throw new IllegalArgumentException("VarLong exceeds 64 bits");
            }
        }
        return value;
    }

    public static void writeVarInt(ByteBuffer buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.put((byte) value);
    }

    public static int readVarInt(ByteBuffer buf) {
        int value = 0;
        int position = 0;
        while (true) {
            byte b = buf.get();
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) break;
            position += 7;
            if (position >= 32) throw new IllegalArgumentException("VarInt exceeds 32 bits");
        }
        return value;
    }
}
