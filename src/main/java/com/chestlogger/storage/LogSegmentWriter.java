package com.chestlogger.storage;

import com.chestlogger.event.TransactionLogEntry;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Production compressed log segment writer supporting LZ4 block compression,
 * dictionary persistence, and automated segment rotation.
 */
public final class LogSegmentWriter implements Closeable {
    private final File logDir;
    private final String segmentPrefix;
    private final BlockCompressor compressor;
    private final StorageProfile profile;
    private final StringTableDictionary stringDictionary;

    private int segmentIndex;
    private File currentSegmentFile;
    private FileOutputStream currentFos;
    private FileChannel currentChannel;
    private long startSeqId;
    private long currentSeqId;
    private long segmentCreationTimeMs;
    private long bytesWrittenToCurrentSegment;

    public LogSegmentWriter(
            File logDir,
            String segmentPrefix,
            int initialSegmentIndex,
            long initialStartSeqId,
            BlockCompressor compressor,
            StorageProfile profile,
            StringTableDictionary stringDictionary
    ) throws IOException {
        this.logDir = Objects.requireNonNull(logDir, "logDir cannot be null");
        this.segmentPrefix = Objects.requireNonNull(segmentPrefix, "segmentPrefix cannot be null");
        this.compressor = Objects.requireNonNull(compressor, "compressor cannot be null");
        this.profile = Objects.requireNonNull(profile, "profile cannot be null");
        this.stringDictionary = Objects.requireNonNull(stringDictionary, "stringDictionary cannot be null");

        this.segmentIndex = initialSegmentIndex;
        this.startSeqId = initialStartSeqId;
        this.currentSeqId = initialStartSeqId;

        if (!logDir.exists()) {
            logDir.mkdirs();
        }

        openNextSegment();
    }

    private void openNextSegment() throws IOException {
        if (currentFos != null) {
            flushDictionaryBlock();
            currentFos.flush();
            if (profile.forceFsync()) {
                currentChannel.force(false);
            }
            currentFos.close();
        }

        String fileName = String.format("%s_%06d.clog", segmentPrefix, segmentIndex);
        currentSegmentFile = new File(logDir, fileName);
        this.currentFos = new FileOutputStream(currentSegmentFile, false);
        this.currentChannel = currentFos.getChannel();
        this.segmentCreationTimeMs = System.currentTimeMillis();
        this.bytesWrittenToCurrentSegment = 0L;

        // Write 32-byte header
        BinaryLogHeader header = new BinaryLogHeader(
                BinaryLogHeader.CURRENT_VERSION,
                compressor.getCompressionType(),
                (byte) 0x01, // Has dictionary flag
                segmentCreationTimeMs,
                startSeqId
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream(BinaryLogHeader.HEADER_SIZE);
        header.writeTo(baos);
        byte[] headerBytes = baos.toByteArray();
        currentFos.write(headerBytes);
        bytesWrittenToCurrentSegment += headerBytes.length;
    }

    /**
     * Appends an uncompressed dictionary frame to the segment.
     */
    public synchronized void flushDictionaryBlock() throws IOException {
        ByteArrayOutputStream dictStream = new ByteArrayOutputStream();
        stringDictionary.writeTo(dictStream);
        byte[] dictBytes = dictStream.toByteArray();

        CRC32 crc = new CRC32();
        crc.update(dictBytes);
        int checksum = (int) crc.getValue();

        BlockFrameHeader frameHeader = new BlockFrameHeader(
                BlockFrameHeader.BLOCK_MAGIC,
                BlockFrameHeader.TYPE_DICTIONARY,
                (byte) 0x00, // uncompressed dict
                dictBytes.length,
                dictBytes.length,
                stringDictionary.size(),
                startSeqId,
                checksum,
                0
        );

        ByteArrayOutputStream frameStream = new ByteArrayOutputStream(BlockFrameHeader.HEADER_SIZE + dictBytes.length);
        frameHeader.writeTo(frameStream);
        frameStream.write(dictBytes);

        byte[] frameBytes = frameStream.toByteArray();
        currentFos.write(frameBytes);
        bytesWrittenToCurrentSegment += frameBytes.length;
    }

    /**
     * Writes a batch of transaction events as a compressed framed block.
     * Rotates segment if maxSegmentSizeBytes is exceeded.
     */
    public synchronized int writeBatch(List<TransactionLogEntry> records) throws IOException {
        if (records == null || records.isEmpty()) {
            return 0;
        }

        // Check if rotation needed
        if (bytesWrittenToCurrentSegment >= profile.maxSegmentSizeBytes()) {
            segmentIndex++;
            startSeqId = currentSeqId + 1;
            openNextSegment();
        }

        long minSeq = records.get(0).sequenceId();
        long minTime = records.get(0).timestampMs();
        int timeDelta = (int) Math.max(0, minTime - segmentCreationTimeMs);

        // Serialize uncompressed payload
        ByteArrayOutputStream payloadStream = new ByteArrayOutputStream();
        long prevSeq = minSeq;
        long prevTime = minTime;

        for (TransactionLogEntry record : records) {
            BinaryRecordCodec.encode(payloadStream, record, stringDictionary, prevSeq, prevTime);
            prevSeq = record.sequenceId();
            prevTime = record.timestampMs();
            currentSeqId = Math.max(currentSeqId, record.sequenceId());
        }

        byte[] rawBytes = payloadStream.toByteArray();
        byte[] compressedBytes = compressor.compress(rawBytes);

        CRC32 crc = new CRC32();
        crc.update(compressedBytes);
        int checksum = (int) crc.getValue();

        BlockFrameHeader frameHeader = new BlockFrameHeader(
                BlockFrameHeader.BLOCK_MAGIC,
                BlockFrameHeader.TYPE_RECORDS,
                (byte) 0x01, // isCompressed = true
                compressedBytes.length,
                rawBytes.length,
                records.size(),
                minSeq,
                checksum,
                timeDelta
        );

        ByteArrayOutputStream frameStream = new ByteArrayOutputStream(BlockFrameHeader.HEADER_SIZE + compressedBytes.length);
        frameHeader.writeTo(frameStream);
        frameStream.write(compressedBytes);

        byte[] blockBytes = frameStream.toByteArray();
        currentFos.write(blockBytes);
        bytesWrittenToCurrentSegment += blockBytes.length;

        if (profile.forceFsync()) {
            currentChannel.force(false);
        }

        return blockBytes.length;
    }

    public File getCurrentSegmentFile() {
        return currentSegmentFile;
    }

    public int getSegmentIndex() {
        return segmentIndex;
    }

    public long getCurrentSeqId() {
        return currentSeqId;
    }

    public long getBytesWrittenToCurrentSegment() {
        return bytesWrittenToCurrentSegment;
    }

    @Override
    public synchronized void close() throws IOException {
        if (currentFos != null) {
            flushDictionaryBlock();
            currentFos.flush();
            if (profile.forceFsync()) {
                currentChannel.force(false);
            }
            currentFos.close();
            currentFos = null;
        }
    }
}
