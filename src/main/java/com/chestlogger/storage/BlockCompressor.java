package com.chestlogger.storage;

import java.io.IOException;

/**
 * Interface for pluggable block payload compression algorithms.
 */
public interface BlockCompressor {
    byte getCompressionType();

    byte[] compress(byte[] uncompressed) throws IOException;

    byte[] decompress(byte[] compressed, int uncompressedLength) throws IOException;
}
