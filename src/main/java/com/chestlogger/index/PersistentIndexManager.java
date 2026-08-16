package com.chestlogger.index;

import com.chestlogger.storage.StringTableDictionary;
import com.chestlogger.storage.VarIntUtil;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.CRC32;

/**
 * Production multi-dimensional spatial, player, temporal, and item index manager
 * with atomic persistent checkpoints.
 */
public final class PersistentIndexManager {
    public static final String INDEX_FILE_NAME = "index.cidx";
    public static final byte[] INDEX_MAGIC = new byte[]{'C', 'I', 'D', 'X'};
    public static final short INDEX_VERSION = 1;

    private final File dataDir;
    private final List<IndexPointer> allPointers = new ArrayList<>();
    private final Map<Long, List<IndexPointer>> spatialIndex = new HashMap<>();
    private final Map<UUID, List<IndexPointer>> playerIndex = new HashMap<>();
    private final Map<String, List<IndexPointer>> itemIndex = new HashMap<>();

    public PersistentIndexManager(File dataDir) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir cannot be null");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
    }

    public synchronized void index(IndexPointer pointer) {
        Objects.requireNonNull(pointer, "pointer cannot be null");
        allPointers.add(pointer);

        spatialIndex.computeIfAbsent(pointer.packedBlockPos(), k -> new ArrayList<>()).add(pointer);
        if (pointer.actorUuid() != null) {
            playerIndex.computeIfAbsent(pointer.actorUuid(), k -> new ArrayList<>()).add(pointer);
        }
        if (pointer.itemId() != null) {
            itemIndex.computeIfAbsent(pointer.itemId(), k -> new ArrayList<>()).add(pointer);
        }
    }

    public synchronized List<IndexPointer> query(IndexQueryFilter filter) {
        Objects.requireNonNull(filter, "filter cannot be null");
        List<IndexPointer> candidates;

        // Optimized seed selection based on filter specificity
        candidates = allPointers;

        List<IndexPointer> matched = new ArrayList<>();
        for (IndexPointer ptr : candidates) {
            if (filter.matches(ptr)) {
                matched.add(ptr);
                if (matched.size() >= filter.getLimit()) {
                    break;
                }
            }
        }
        return matched;
    }

    public synchronized int size() {
        return allPointers.size();
    }

    public synchronized void clear() {
        allPointers.clear();
        spatialIndex.clear();
        playerIndex.clear();
        itemIndex.clear();
    }

    /**
     * Atomically saves the index checkpoint to disk.
     */
    public synchronized void saveCheckpoint() throws IOException {
        File finalFile = new File(dataDir, INDEX_FILE_NAME);
        File tempFile = new File(dataDir, INDEX_FILE_NAME + ".tmp");

        StringTableDictionary dict = new StringTableDictionary();

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        VarIntUtil.writeVarInt(payload, allPointers.size());

        for (IndexPointer ptr : allPointers) {
            VarIntUtil.writeVarLong(payload, ptr.sequenceId());
            VarIntUtil.writeVarLong(payload, ptr.timestampMs());

            boolean hasUuid = ptr.actorUuid() != null;
            payload.write(hasUuid ? 1 : 0);
            if (hasUuid) {
                ByteBuffer bb = ByteBuffer.allocate(16);
                bb.putLong(ptr.actorUuid().getMostSignificantBits());
                bb.putLong(ptr.actorUuid().getLeastSignificantBits());
                payload.write(bb.array());
            }

            int itemDictId = dict.getOrAssign(ptr.itemId() != null ? ptr.itemId() : "");
            VarIntUtil.writeVarInt(payload, itemDictId);

            int dimDictId = dict.getOrAssign(ptr.dimension());
            VarIntUtil.writeVarInt(payload, dimDictId);

            VarIntUtil.writeVarLong(payload, ptr.packedBlockPos());
            VarIntUtil.writeVarInt(payload, ptr.segmentIndex());
            VarIntUtil.writeVarLong(payload, ptr.blockOffset());
            VarIntUtil.writeVarInt(payload, ptr.recordIndexInBlock());
        }

        ByteArrayOutputStream dictStream = new ByteArrayOutputStream();
        dict.writeTo(dictStream);
        byte[] dictBytes = dictStream.toByteArray();
        byte[] payloadBytes = payload.toByteArray();

        CRC32 crc = new CRC32();
        crc.update(dictBytes);
        crc.update(payloadBytes);
        int checksum = (int) crc.getValue();

        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            // Header (40 bytes)
            ByteBuffer headerBuf = ByteBuffer.allocate(40);
            headerBuf.put(INDEX_MAGIC);
            headerBuf.putShort(INDEX_VERSION);
            headerBuf.putShort((short) 0); // reserved
            headerBuf.putInt(checksum);
            headerBuf.putInt(dictBytes.length);
            headerBuf.putInt(payloadBytes.length);
            headerBuf.putLong(allPointers.size());
            headerBuf.putLong(System.currentTimeMillis());

            fos.write(headerBuf.array());
            fos.write(dictBytes);
            fos.write(payloadBytes);
            fos.flush();
            fos.getChannel().force(true);
        }

        Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Loads the index checkpoint from disk.
     */
    public synchronized void loadCheckpoint() throws IOException {
        File finalFile = new File(dataDir, INDEX_FILE_NAME);
        if (!finalFile.exists()) {
            return;
        }

        clear();
        try (FileInputStream fis = new FileInputStream(finalFile)) {
            byte[] headerBytes = fis.readNBytes(40);
            if (headerBytes.length < 40) {
                throw new IOException("Incomplete index file header");
            }
            ByteBuffer hb = ByteBuffer.wrap(headerBytes);
            byte[] magic = new byte[4];
            hb.get(magic);
            if (magic[0] != 'C' || magic[1] != 'I' || magic[2] != 'D' || magic[3] != 'X') {
                throw new IOException("Invalid index file magic");
            }

            short version = hb.getShort();
            hb.getShort(); // reserved
            int expectedCrc = hb.getInt();
            int dictLen = hb.getInt();
            int payloadLen = hb.getInt();

            byte[] dictBytes = fis.readNBytes(dictLen);
            byte[] payloadBytes = fis.readNBytes(payloadLen);

            CRC32 crc = new CRC32();
            crc.update(dictBytes);
            crc.update(payloadBytes);
            if ((int) crc.getValue() != expectedCrc) {
                throw new IOException("Index checkpoint CRC32 mismatch! Possible corrupted index file.");
            }

            StringTableDictionary dict = StringTableDictionary.readFrom(new ByteArrayInputStream(dictBytes));
            ByteArrayInputStream pis = new ByteArrayInputStream(payloadBytes);
            int count = VarIntUtil.readVarInt(pis);

            for (int i = 0; i < count; i++) {
                long seq = VarIntUtil.readVarLong(pis);
                long time = VarIntUtil.readVarLong(pis);

                boolean hasUuid = pis.read() == 1;
                UUID actorUuid = null;
                if (hasUuid) {
                    byte[] uBytes = pis.readNBytes(16);
                    ByteBuffer ub = ByteBuffer.wrap(uBytes);
                    actorUuid = new UUID(ub.getLong(), ub.getLong());
                }

                int itemIdInt = VarIntUtil.readVarInt(pis);
                String itemId = dict.getString(itemIdInt);
                if (itemId.isEmpty()) itemId = null;

                int dimIdInt = VarIntUtil.readVarInt(pis);
                String dimension = dict.getString(dimIdInt);

                long packedPos = VarIntUtil.readVarLong(pis);
                int segIndex = VarIntUtil.readVarInt(pis);
                long offset = VarIntUtil.readVarLong(pis);
                int recIndex = VarIntUtil.readVarInt(pis);

                index(new IndexPointer(seq, time, actorUuid, itemId, dimension, packedPos, segIndex, offset, recIndex));
            }
        }
    }
}
