package com.chestlogger.query;

import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.index.IndexPointer;
import com.chestlogger.index.IndexQueryFilter;
import com.chestlogger.index.PersistentIndexManager;
import com.chestlogger.storage.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.*;

/**
 * Fast disk retrieval engine resolving IndexPointers into TransactionLogEntry instances
 * with O(1) random-access file channel seeking.
 */
public final class QueryEngine {
    private final File logDir;
    private final BlockCompressor compressor;
    private final PersistentIndexManager indexManager;
    private final Map<Integer, StringTableDictionary> segmentDictionaries = new HashMap<>();

    public QueryEngine(File logDir, BlockCompressor compressor, PersistentIndexManager indexManager) {
        this.logDir = Objects.requireNonNull(logDir, "logDir cannot be null");
        this.compressor = Objects.requireNonNull(compressor, "compressor cannot be null");
        this.indexManager = Objects.requireNonNull(indexManager, "indexManager cannot be null");
    }

    public List<TransactionLogEntry> fetchRecords(IndexQueryFilter filter) throws IOException {
        List<IndexPointer> pointers = indexManager.query(filter);
        List<TransactionLogEntry> records = new ArrayList<>(pointers.size());

        for (IndexPointer ptr : pointers) {
            TransactionLogEntry entry = fetchSingleRecord(ptr);
            if (entry != null) {
                records.add(entry);
            }
        }
        return records;
    }

    public PagedResult<TransactionLogEntry> queryPaged(IndexQueryFilter filter, int pageNumber, int pageSize) throws IOException {
        List<TransactionLogEntry> all = fetchRecords(filter);
        return PagedResult.of(all, pageNumber, pageSize);
    }

    public synchronized TransactionLogEntry fetchSingleRecord(IndexPointer ptr) throws IOException {
        File[] files = logDir.listFiles((dir, name) -> name.endsWith(".clog"));
        if (files == null || ptr.segmentIndex() >= files.length) {
            return null;
        }

        Arrays.sort(files, Comparator.comparing(File::getName));
        File segmentFile = files[ptr.segmentIndex()];

        StringTableDictionary dict = getOrLoadDictionary(segmentFile, ptr.segmentIndex());

        try (RandomAccessFile raf = new RandomAccessFile(segmentFile, "r")) {
            byte[] headerBytes = new byte[BinaryLogHeader.HEADER_SIZE];
            raf.readFully(headerBytes);
            BinaryLogHeader header = BinaryLogHeader.readFrom(new ByteArrayInputStream(headerBytes));

            raf.seek(ptr.blockOffset());

            byte[] blockHeaderBytes = new byte[BlockFrameHeader.HEADER_SIZE];
            raf.readFully(blockHeaderBytes);
            BlockFrameHeader blockHeader = BlockFrameHeader.readFrom(new ByteArrayInputStream(blockHeaderBytes));

            byte[] payloadBytes = new byte[blockHeader.compressedLength()];
            raf.readFully(payloadBytes);

            byte[] rawBytes;
            boolean isCompressed = (blockHeader.flags() & 0x01) != 0;
            if (isCompressed) {
                rawBytes = compressor.decompress(payloadBytes, blockHeader.uncompressedLength());
            } else {
                rawBytes = payloadBytes;
            }

            ByteArrayInputStream bais = new ByteArrayInputStream(rawBytes);
            long prevSeq = blockHeader.minSequenceId();
            long prevTime = header.creationEpochMs() + blockHeader.minTimestampDelta();

            for (int r = 0; r <= ptr.recordIndexInBlock(); r++) {
                if (bais.available() == 0) break;
                TransactionLogEntry entry = BinaryRecordCodec.decode(bais, dict, prevSeq, prevTime);
                if (r == ptr.recordIndexInBlock()) {
                    return entry;
                }
                prevSeq = entry.sequenceId();
                prevTime = entry.timestampMs();
            }
        }

        return null;
    }

    private synchronized StringTableDictionary getOrLoadDictionary(File segmentFile, int segmentIndex) throws IOException {
        StringTableDictionary existing = segmentDictionaries.get(segmentIndex);
        if (existing != null) {
            return existing;
        }

        StringTableDictionary dict = new StringTableDictionary();
        try (FileInputStream fis = new FileInputStream(segmentFile)) {
            if (fis.available() >= BinaryLogHeader.HEADER_SIZE) {
                BinaryLogHeader.readFrom(fis);
                while (fis.available() >= BlockFrameHeader.HEADER_SIZE) {
                    BlockFrameHeader bh = BlockFrameHeader.readFrom(fis);
                    byte[] payload = fis.readNBytes(bh.compressedLength());
                    if (bh.blockType() == BlockFrameHeader.TYPE_DICTIONARY) {
                        StringTableDictionary loaded = StringTableDictionary.readFrom(new ByteArrayInputStream(payload));
                        for (int i = 0; i < loaded.size(); i++) {
                            dict.getOrAssign(loaded.getString(i));
                        }
                    }
                }
            }
        }
        segmentDictionaries.put(segmentIndex, dict);
        return dict;
    }

    public PersistentIndexManager getIndexManager() {
        return indexManager;
    }
}
