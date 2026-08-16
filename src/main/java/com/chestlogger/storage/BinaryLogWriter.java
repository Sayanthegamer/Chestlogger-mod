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
 * Low-overhead append-only binary segmented log writer.
 */
public final class BinaryLogWriter implements Closeable {
    private final File file;
    private final FileOutputStream fos;
    private final FileChannel channel;
    private final StringTableDictionary stringDictionary;
    private final long startSequenceId;
    private final long creationEpochMs;
    private long currentSequenceId;
    private boolean headerWritten;

    public BinaryLogWriter(File file, long startSequenceId, StringTableDictionary stringDictionary) throws IOException {
        this.file = Objects.requireNonNull(file, "file cannot be null");
        this.stringDictionary = Objects.requireNonNull(stringDictionary, "stringDictionary cannot be null");
        this.startSequenceId = startSequenceId;
        this.currentSequenceId = startSequenceId;
        this.creationEpochMs = System.currentTimeMillis();
        this.fos = new FileOutputStream(file, true);
        this.channel = fos.getChannel();
        this.headerWritten = file.length() >= BinaryLogHeader.HEADER_SIZE;

        if (!headerWritten) {
            writeHeader();
        }
    }

    private synchronized void writeHeader() throws IOException {
        BinaryLogHeader header = new BinaryLogHeader(
                BinaryLogHeader.CURRENT_VERSION,
                (byte) 0x00, // Uncompressed raw frames by default (or LZ4 in compressed writer)
                (byte) 0x00,
                creationEpochMs,
                startSequenceId
        );
        ByteArrayOutputStream baos = new ByteArrayOutputStream(BinaryLogHeader.HEADER_SIZE);
        header.writeTo(baos);
        byte[] headerBytes = baos.toByteArray();
        fos.write(headerBytes);
        fos.flush();
        headerWritten = true;
    }

    /**
     * Appends a batch of records as a single framed block.
     */
    public synchronized int writeRecordBlock(List<TransactionLogEntry> records) throws IOException {
        if (records == null || records.isEmpty()) {
            return 0;
        }

        long minSeq = records.get(0).sequenceId();
        long minTime = records.get(0).timestampMs();
        int timeDelta = (int) Math.max(0, minTime - creationEpochMs);

        ByteArrayOutputStream payloadStream = new ByteArrayOutputStream();
        long prevSeq = minSeq;
        long prevTime = minTime;

        for (TransactionLogEntry record : records) {
            BinaryRecordCodec.encode(payloadStream, record, stringDictionary, prevSeq, prevTime);
            prevSeq = record.sequenceId();
            prevTime = record.timestampMs();
            currentSequenceId = Math.max(currentSequenceId, record.sequenceId());
        }

        byte[] payloadBytes = payloadStream.toByteArray();

        CRC32 crc = new CRC32();
        crc.update(payloadBytes);
        int checksum = (int) crc.getValue();

        BlockFrameHeader frameHeader = new BlockFrameHeader(
                BlockFrameHeader.BLOCK_MAGIC,
                BlockFrameHeader.TYPE_RECORDS,
                (byte) 0x00, // Raw/Uncompressed flag
                payloadBytes.length,
                payloadBytes.length,
                records.size(),
                minSeq,
                checksum,
                timeDelta
        );

        ByteArrayOutputStream frameStream = new ByteArrayOutputStream(BlockFrameHeader.HEADER_SIZE + payloadBytes.length);
        frameHeader.writeTo(frameStream);
        frameStream.write(payloadBytes);

        byte[] fullBlock = frameStream.toByteArray();
        fos.write(fullBlock);
        fos.flush();

        return fullBlock.length;
    }

    public FileChannel getChannel() {
        return channel;
    }

    public File getFile() {
        return file;
    }

    public long getCurrentSequenceId() {
        return currentSequenceId;
    }

    @Override
    public synchronized void close() throws IOException {
        fos.flush();
        fos.close();
    }
}
