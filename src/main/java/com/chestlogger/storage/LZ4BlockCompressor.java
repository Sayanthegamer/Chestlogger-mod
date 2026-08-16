package com.chestlogger.storage;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import java.io.IOException;
import java.util.Arrays;

/**
 * High-performance LZ4 block compressor utilizing lz4-java fast compression instances.
 */
public final class LZ4BlockCompressor implements BlockCompressor {
    public static final byte COMPRESSION_TYPE_LZ4 = 0x01;

    private final LZ4Compressor compressor;
    private final LZ4FastDecompressor decompressor;

    public LZ4BlockCompressor() {
        LZ4Factory factory = LZ4Factory.fastestInstance();
        this.compressor = factory.fastCompressor();
        this.decompressor = factory.fastDecompressor();
    }

    @Override
    public byte getCompressionType() {
        return COMPRESSION_TYPE_LZ4;
    }

    @Override
    public byte[] compress(byte[] uncompressed) throws IOException {
        if (uncompressed == null || uncompressed.length == 0) {
            return new byte[0];
        }
        int maxCompressedLength = compressor.maxCompressedLength(uncompressed.length);
        byte[] compressed = new byte[maxCompressedLength];
        int compressedLength = compressor.compress(uncompressed, 0, uncompressed.length, compressed, 0, maxCompressedLength);
        return Arrays.copyOf(compressed, compressedLength);
    }

    @Override
    public byte[] decompress(byte[] compressed, int uncompressedLength) throws IOException {
        if (uncompressedLength == 0) {
            return new byte[0];
        }
        byte[] restored = new byte[uncompressedLength];
        decompressor.decompress(compressed, 0, restored, 0, uncompressedLength);
        return restored;
    }
}
