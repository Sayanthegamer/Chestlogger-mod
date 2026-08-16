package com.chestlogger.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Fixed 32-Byte header preceding every compressed/raw block chunk.
 * Layout:
 *   [0x00..0x01] Block Magic (0xAA55)
 *   [0x02]       Block Type (1=Records, 2=Dictionary, 3=IndexCheckpoint)
 *   [0x03]       Flags (Bit 0: Is Compressed)
 *   [0x04..0x07] Compressed Length (int32)
 *   [0x08..0x0B] Uncompressed Length (int32)
 *   [0x0C..0x0F] Record Count (int32)
 *   [0x10..0x17] Min Sequence ID (int64)
 *   [0x18..0x1B] Block Checksum (uint32 CRC32 over payload)
 *   [0x1C..0x1F] Min Timestamp Delta (uint32)
 */
public record BlockFrameHeader(
        short magic,
        byte blockType,
        byte flags,
        int compressedLength,
        int uncompressedLength,
        int recordCount,
        long minSequenceId,
        int checksum,
        int minTimestampDelta
) {
    public static final short BLOCK_MAGIC = (short) 0xAA55;
    public static final int HEADER_SIZE = 32;
    public static final byte TYPE_RECORDS = 0x01;
    public static final byte TYPE_DICTIONARY = 0x02;

    public void writeTo(OutputStream out) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE);
        buf.putShort(BLOCK_MAGIC);
        buf.put(blockType);
        buf.put(flags);
        buf.putInt(compressedLength);
        buf.putInt(uncompressedLength);
        buf.putInt(recordCount);
        buf.putLong(minSequenceId);
        buf.putInt(checksum);
        buf.putInt(minTimestampDelta);
        out.write(buf.array());
    }

    public static BlockFrameHeader readFrom(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(HEADER_SIZE);
        if (bytes.length < HEADER_SIZE) {
            throw new IOException("Incomplete block frame header");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        short magic = buf.getShort();
        if (magic != BLOCK_MAGIC) {
            throw new IOException(String.format("Invalid block magic: 0x%04X", magic));
        }

        byte type = buf.get();
        byte flags = buf.get();
        int compLen = buf.getInt();
        int uncompLen = buf.getInt();
        int recCount = buf.getInt();
        long minSeq = buf.getLong();
        int crc = buf.getInt();
        int minTimeDelta = buf.getInt();

        return new BlockFrameHeader(magic, type, flags, compLen, uncompLen, recCount, minSeq, crc, minTimeDelta);
    }
}
