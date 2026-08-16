package com.chestlogger.query;

import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.network.ChestLogPagePayload;
import com.chestlogger.network.DisplayRecord;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages active GUI inspect sessions on the server, mapping transaction logs into
 * bounded, paginated payload frames.
 */
public final class QuerySessionManager {
    public static final int DEFAULT_PAGE_SIZE = 25;
    private static final long SESSION_TTL_MS = 5 * 60 * 1000L; // 5 minutes
    private static final UUID NIL_UUID = new UUID(0L, 0L);

    private final int pageSize;
    private final Map<UUID, SessionEntry> sessions = new ConcurrentHashMap<>();

    public QuerySessionManager() {
        this(DEFAULT_PAGE_SIZE);
    }

    public QuerySessionManager(int pageSize) {
        this.pageSize = Math.max(1, pageSize);
    }

    public synchronized ChestLogPagePayload createSession(
            UUID queryId,
            String containerType,
            String dimension,
            long packedBlockPos,
            List<TransactionLogEntry> records,
            int initialPage
    ) {
        cleanupExpired();

        List<DisplayRecord> displayRecords = new ArrayList<>();
        if (records != null) {
            for (TransactionLogEntry entry : records) {
                UUID actorUuid = entry.actorUuid() != null ? entry.actorUuid() : NIL_UUID;
                String actorName = entry.actorName() != null ? entry.actorName() : "System";

                if (entry.deltas().isEmpty()) {
                    displayRecords.add(new DisplayRecord(
                            entry.sequenceId(),
                            entry.timestampMs(),
                            actorUuid,
                            actorName,
                            entry.actorType().getWireId(),
                            entry.actionType().getWireId(),
                            0,
                            "minecraft:air",
                            0,
                            0L
                    ));
                } else {
                    for (SlotDelta delta : entry.deltas()) {
                        displayRecords.add(new DisplayRecord(
                                entry.sequenceId(),
                                entry.timestampMs(),
                                actorUuid,
                                actorName,
                                entry.actorType().getWireId(),
                                entry.actionType().getWireId(),
                                delta.slotIndex(),
                                delta.itemId(),
                                delta.deltaQuantity(),
                                delta.metadataFingerprint()
                        ));
                    }
                }
            }
        }

        SessionEntry session = new SessionEntry(
                queryId != null ? queryId : UUID.randomUUID(),
                containerType != null ? containerType : "Container",
                dimension != null ? dimension : "minecraft:overworld",
                packedBlockPos,
                displayRecords,
                System.currentTimeMillis()
        );
        sessions.put(session.queryId, session);

        return slicePage(session, initialPage);
    }

    public synchronized ChestLogPagePayload getPage(UUID queryId, int pageIndex) {
        if (queryId == null) {
            return null;
        }
        SessionEntry session = sessions.get(queryId);
        if (session == null) {
            return null;
        }
        session.lastAccessTimeMs = System.currentTimeMillis();
        return slicePage(session, pageIndex);
    }

    public synchronized boolean hasSession(UUID queryId) {
        return queryId != null && sessions.containsKey(queryId);
    }

    public synchronized void invalidate(UUID queryId) {
        if (queryId != null) {
            sessions.remove(queryId);
        }
    }

    private ChestLogPagePayload slicePage(SessionEntry session, int requestedPage) {
        int totalRecords = session.records.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalRecords / pageSize));
        int clampedPage = Math.max(1, Math.min(requestedPage, totalPages));

        int fromIndex = (clampedPage - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, totalRecords);

        List<DisplayRecord> pageSlice;
        if (fromIndex >= totalRecords) {
            pageSlice = Collections.emptyList();
        } else {
            pageSlice = new ArrayList<>(session.records.subList(fromIndex, toIndex));
        }

        return new ChestLogPagePayload(
                session.queryId,
                clampedPage,
                totalPages,
                totalRecords,
                session.containerType,
                session.dimension,
                session.packedBlockPos,
                pageSlice
        );
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> (now - e.getValue().lastAccessTimeMs) > SESSION_TTL_MS);
    }

    private static final class SessionEntry {
        final UUID queryId;
        final String containerType;
        final String dimension;
        final long packedBlockPos;
        final List<DisplayRecord> records;
        long lastAccessTimeMs;

        SessionEntry(UUID queryId, String containerType, String dimension, long packedBlockPos, List<DisplayRecord> records, long lastAccessTimeMs) {
            this.queryId = queryId;
            this.containerType = containerType;
            this.dimension = dimension;
            this.packedBlockPos = packedBlockPos;
            this.records = records;
            this.lastAccessTimeMs = lastAccessTimeMs;
        }
    }
}
