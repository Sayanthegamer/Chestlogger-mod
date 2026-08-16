package com.chestlogger.recovery;

import com.chestlogger.storage.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.*;
import java.util.zip.CRC32;

/**
 * Validates and self-heals segmented binary log files upon system startup.
 * Automatically truncates partial tail blocks or quarantines corrupt segments.
 */
public final class TailRecoveryEngine {
    private final BlockCompressor compressor;

    public TailRecoveryEngine(BlockCompressor compressor) {
        this.compressor = Objects.requireNonNull(compressor, "compressor cannot be null");
    }

    /**
     * Scans the log directory, repairs tail corruptions, and identifies active sequence baseline.
     */
    public RecoveryReport recoverAndValidate(File logDir) throws IOException {
        Objects.requireNonNull(logDir, "logDir cannot be null");
        if (!logDir.exists() || !logDir.isDirectory()) {
            return new RecoveryReport(false, 0L, 0L, 0, List.of());
        }

        File[] files = logDir.listFiles((dir, name) -> name.endsWith(".clog"));
        if (files == null || files.length == 0) {
            return new RecoveryReport(false, 0L, 0L, 0, List.of());
        }

        Arrays.sort(files, Comparator.comparing(File::getName));

        boolean hadCorruptions = false;
        long totalTruncated = 0L;
        long maxSeq = 0L;
        int activeSegmentIndex = 0;
        List<String> issues = new ArrayList<>();

        for (int i = 0; i < files.length; i++) {
            File segmentFile = files[i];
            activeSegmentIndex = i;

            long fileLength = segmentFile.length();
            if (fileLength < BinaryLogHeader.HEADER_SIZE) {
                hadCorruptions = true;
                totalTruncated += fileLength;
                segmentFile.delete();
                issues.add("Deleted incomplete segment header: " + segmentFile.getName());
                continue;
            }

            long lastValidOffset = 0L;
            try (FileInputStream fis = new FileInputStream(segmentFile)) {
                BinaryLogHeader header;
                try {
                    header = BinaryLogHeader.readFrom(fis);
                    lastValidOffset = BinaryLogHeader.HEADER_SIZE;
                    maxSeq = Math.max(maxSeq, header.startSequenceId());
                } catch (Exception e) {
                    hadCorruptions = true;
                    totalTruncated += fileLength;
                    // Quarantine invalid header segment
                    File corrupt = new File(logDir, segmentFile.getName() + ".corrupt");
                    segmentFile.renameTo(corrupt);
                    issues.add("Quarantined corrupt header segment: " + segmentFile.getName());
                    continue;
                }

                while (fis.available() >= BlockFrameHeader.HEADER_SIZE) {
                    long blockStart = lastValidOffset;
                    BlockFrameHeader blockHeader;
                    try {
                        blockHeader = BlockFrameHeader.readFrom(fis);
                    } catch (Exception e) {
                        break;
                    }

                    int payloadLen = blockHeader.compressedLength();
                    if (payloadLen <= 0 || payloadLen > 64 * 1024 * 1024) {
                        break;
                    }

                    byte[] payloadBytes = fis.readNBytes(payloadLen);
                    if (payloadBytes.length < payloadLen) {
                        break; // Incomplete block payload
                    }

                    CRC32 crc = new CRC32();
                    crc.update(payloadBytes);
                    if ((int) crc.getValue() != blockHeader.checksum()) {
                        // Checksum mismatch -> block is corrupted!
                        break;
                    }

                    // Block is 100% valid
                    lastValidOffset = blockStart + BlockFrameHeader.HEADER_SIZE + payloadLen;
                    if (blockHeader.blockType() == BlockFrameHeader.TYPE_RECORDS) {
                        maxSeq = Math.max(maxSeq, blockHeader.minSequenceId() + blockHeader.recordCount() - 1);
                    }
                }
            }

            if (lastValidOffset < fileLength) {
                long truncatedBytes = fileLength - lastValidOffset;
                totalTruncated += truncatedBytes;
                hadCorruptions = true;
                issues.add(String.format("Truncated %d corrupted tail bytes from %s", truncatedBytes, segmentFile.getName()));

                try (RandomAccessFile raf = new RandomAccessFile(segmentFile, "rw")) {
                    raf.setLength(lastValidOffset);
                }
            }
        }

        return new RecoveryReport(hadCorruptions, totalTruncated, maxSeq, activeSegmentIndex, issues);
    }
}
