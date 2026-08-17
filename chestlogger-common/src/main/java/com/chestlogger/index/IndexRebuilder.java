package com.chestlogger.index;

import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.storage.*;
import com.chestlogger.util.ThreadGuard;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Resilient index rebuilder reconstructing the entire multi-dimensional index
 * directly from raw binary .clog segment logs using 2-pass dictionary resolution.
 */
public final class IndexRebuilder {
    private final BlockCompressor compressor;

    public IndexRebuilder(BlockCompressor compressor) {
        this.compressor = Objects.requireNonNull(compressor, "compressor cannot be null");
    }

    public int rebuild(File logDir, PersistentIndexManager indexManager) throws IOException {
        ThreadGuard.assertNotServerThread("IndexRebuilder.rebuild");
        Objects.requireNonNull(logDir, "logDir cannot be null");
        Objects.requireNonNull(indexManager, "indexManager cannot be null");

        if (!logDir.exists() || !logDir.isDirectory()) {
            return 0;
        }

        File[] files = logDir.listFiles((dir, name) -> name.endsWith(".clog"));
        if (files == null || files.length == 0) {
            return 0;
        }

        Arrays.sort(files, Comparator.comparing(File::getName));
        int totalRebuilt = 0;
        StringTableDictionary stringDict = new StringTableDictionary();

        for (int segIdx = 0; segIdx < files.length; segIdx++) {
            File logFile = files[segIdx];
            totalRebuilt += rebuildSegment(logFile, segIdx, stringDict, indexManager);
        }

        indexManager.saveCheckpoint();
        return totalRebuilt;
    }

    private int rebuildSegment(File logFile, int segmentIndex, StringTableDictionary stringDict, PersistentIndexManager indexManager) {
        int recordCount = 0;

        // Pass 1: Extract all dictionary frames
        try (FileInputStream fis = new FileInputStream(logFile)) {
            if (fis.available() < BinaryLogHeader.HEADER_SIZE) {
                return 0;
            }
            BinaryLogHeader.readFrom(fis);

            while (fis.available() >= BlockFrameHeader.HEADER_SIZE) {
                BlockFrameHeader blockHeader;
                try {
                    blockHeader = BlockFrameHeader.readFrom(fis);
                } catch (Exception e) {
                    break;
                }

                int payloadLen = blockHeader.compressedLength();
                if (payloadLen <= 0 || payloadLen > 64 * 1024 * 1024) break;

                byte[] payloadBytes = fis.readNBytes(payloadLen);
                if (payloadBytes.length < payloadLen) break;

                if (blockHeader.blockType() == BlockFrameHeader.TYPE_DICTIONARY) {
                    try {
                        StringTableDictionary loadedDict = StringTableDictionary.readFrom(new ByteArrayInputStream(payloadBytes));
                        for (int i = 0; i < loadedDict.size(); i++) {
                            stringDict.getOrAssign(loadedDict.getString(i));
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // Pass 2: Decode record blocks with full dictionary available
        try (FileInputStream fis = new FileInputStream(logFile)) {
            BinaryLogHeader header = BinaryLogHeader.readFrom(fis);
            long currentOffset = BinaryLogHeader.HEADER_SIZE;

            while (fis.available() >= BlockFrameHeader.HEADER_SIZE) {
                long blockStartOffset = currentOffset;
                BlockFrameHeader blockHeader;
                try {
                    blockHeader = BlockFrameHeader.readFrom(fis);
                } catch (Exception e) {
                    break;
                }
                currentOffset += BlockFrameHeader.HEADER_SIZE;

                int payloadLen = blockHeader.compressedLength();
                if (payloadLen <= 0 || payloadLen > 64 * 1024 * 1024) break;

                byte[] payloadBytes = fis.readNBytes(payloadLen);
                currentOffset += payloadBytes.length;
                if (payloadBytes.length < payloadLen) break;

                CRC32 crc = new CRC32();
                crc.update(payloadBytes);
                if ((int) crc.getValue() != blockHeader.checksum()) {
                    break; // Corrupted frame
                }

                if (blockHeader.blockType() == BlockFrameHeader.TYPE_RECORDS) {
                    byte[] rawBytes;
                    boolean isCompressed = (blockHeader.flags() & 0x01) != 0;
                    if (isCompressed) {
                        try {
                            rawBytes = compressor.decompress(payloadBytes, blockHeader.uncompressedLength());
                        } catch (Exception e) {
                            break;
                        }
                    } else {
                        rawBytes = payloadBytes;
                    }

                    ByteArrayInputStream bais = new ByteArrayInputStream(rawBytes);
                    long prevSeq = blockHeader.minSequenceId();
                    long prevTime = header.creationEpochMs() + blockHeader.minTimestampDelta();

                    for (int recIdx = 0; recIdx < blockHeader.recordCount(); recIdx++) {
                        if (bais.available() == 0) break;
                        try {
                            TransactionLogEntry record = BinaryRecordCodec.decode(bais, stringDict, prevSeq, prevTime);
                            prevSeq = record.sequenceId();
                            prevTime = record.timestampMs();

                            String primaryItem = null;
                            if (!record.deltas().isEmpty()) {
                                primaryItem = record.deltas().get(0).itemId();
                            }

                            IndexPointer ptr = new IndexPointer(
                                    record.sequenceId(),
                                    record.timestampMs(),
                                    record.actorUuid(),
                                    primaryItem,
                                    record.dimension(),
                                    record.packedBlockPos(),
                                    segmentIndex,
                                    blockStartOffset,
                                    recIdx
                            );
                            indexManager.index(ptr);
                            recordCount++;
                        } catch (Exception e) {
                            break;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return recordCount;
    }
}
