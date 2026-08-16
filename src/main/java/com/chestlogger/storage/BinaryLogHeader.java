package com.chestlogger.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/**
 * Fixed 32-Byte segment file header.
 * Layout:
 *   [0x00..0x03] Magic ('CHST' = 0x43 0x48 0x53 0x54)
 *   [0x04..0x05] Format Version (uint16 = 1)
 *   [0x06]       Compression Type (0=None, 1=LZ4, 2=Zstd)
 *   [0x07]       Flags (Bit 0: Has Dict)
 *   [0x08..0x0F] Creation Epoch Millis (int64)
 *   [0x10..0x17] Start Sequence ID (int64)
 *   [0x18..0x1B] Reserved (uint32 = 0)
 *   [0x1C..0x1F] CRC32 Checksum over bytes 0x00..0x1B
 */
public record BinaryLogHeader(
        short formatVersion,
        byte compressionType,
        byte flags,
        long creationEpochMs,
        long startSequenceId
) {
    public static final byte[] MAGIC = new byte[]{'C', 'H', 'S', 'T'};
    public static final int HEADER_SIZE = 32;
    public static final short CURRENT_VERSION = 1;

    public void writeTo(OutputStream out) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE);
        buf.put(MAGIC);
        buf.putShort(formatVersion);
        buf.put(compressionType);
        buf.put(flags);
        buf.putLong(creationEpochMs);
        buf.putLong(startSequenceId);
        buf.putInt(0); // Reserved

        CRC32 crc = new CRC32();
        crc.update(buf.array(), 0, 28);
        buf.putInt((int) crc.getValue());

        out.write(buf.array());
    }

    public static BinaryLogHeader readFrom(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(HEADER_SIZE);
        if (bytes.length < HEADER_SIZE) {
            throw new IOException("Incomplete file header");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        byte[] magic = new byte[4];
        buf.get(magic);
        if (magic[0] != 'C' || magic[1] != 'H' || magic[2] != 'S' || magic[3] != 'T') {
            throw new IOException("Invalid file magic");
        }

        short version = buf.getShort();
        byte compression = buf.get();
        byte flags = buf.get();
        long epoch = buf.getLong();
        long startSeq = buf.getLong();
        buf.getInt(); // Reserved
        int expectedCrc = buf.getInt();

        CRC32 crc = new CRC32();
        crc.update(bytes, 0, 28);
        if ((int) crc.getValue() != expectedCrc) {
            throw new IOException("File header CRC32 mismatch!");
        }

        return new BinaryLogHeader(version, compression, flags, epoch, startSeq);
    }
}
