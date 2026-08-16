package com.chestlogger.index;

import com.chestlogger.event.BlockPosUtil;

import java.util.Objects;
import java.util.UUID;

/**
 * Multi-dimensional query filter for searching transaction records.
 */
public final class IndexQueryFilter {
    private final String dimension;
    private final Long exactBlockPos;
    private final Long centerPos;
    private final int radius;
    private final UUID actorUuid;
    private final String itemId;
    private final long minTimeMs;
    private final long maxTimeMs;
    private final int limit;

    private IndexQueryFilter(
            String dimension,
            Long exactBlockPos,
            Long centerPos,
            int radius,
            UUID actorUuid,
            String itemId,
            long minTimeMs,
            long maxTimeMs,
            int limit
    ) {
        this.dimension = dimension;
        this.exactBlockPos = exactBlockPos;
        this.centerPos = centerPos;
        this.radius = radius;
        this.actorUuid = actorUuid;
        this.itemId = itemId;
        this.minTimeMs = minTimeMs;
        this.maxTimeMs = maxTimeMs;
        this.limit = limit;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean matches(IndexPointer ptr) {
        if (dimension != null && !dimension.equals(ptr.dimension())) {
            return false;
        }
        if (exactBlockPos != null && ptr.packedBlockPos() != exactBlockPos) {
            return false;
        }
        if (centerPos != null) {
            int cx = BlockPosUtil.unpackX(centerPos);
            int cy = BlockPosUtil.unpackY(centerPos);
            int cz = BlockPosUtil.unpackZ(centerPos);

            int px = BlockPosUtil.unpackX(ptr.packedBlockPos());
            int py = BlockPosUtil.unpackY(ptr.packedBlockPos());
            int pz = BlockPosUtil.unpackZ(ptr.packedBlockPos());

            int dx = Math.abs(px - cx);
            int dy = Math.abs(py - cy);
            int dz = Math.abs(pz - cz);

            if (dx > radius || dy > radius || dz > radius) {
                return false;
            }
        }
        if (actorUuid != null && !Objects.equals(actorUuid, ptr.actorUuid())) {
            return false;
        }
        if (itemId != null && !Objects.equals(itemId, ptr.itemId())) {
            return false;
        }
        if (ptr.timestampMs() < minTimeMs || ptr.timestampMs() > maxTimeMs) {
            return false;
        }
        return true;
    }

    public int getLimit() {
        return limit;
    }

    public static class Builder {
        private String dimension;
        private Long exactBlockPos;
        private Long centerPos;
        private int radius = 0;
        private UUID actorUuid;
        private String itemId;
        private long minTimeMs = 0L;
        private long maxTimeMs = Long.MAX_VALUE;
        private int limit = 100;

        public Builder dimension(String dimension) {
            this.dimension = dimension;
            return this;
        }

        public Builder exactBlockPos(long packedPos) {
            this.exactBlockPos = packedPos;
            return this;
        }

        public Builder centerBlockPos(long centerPos, int radius) {
            this.centerPos = centerPos;
            this.radius = radius;
            return this;
        }

        public Builder actorUuid(UUID actorUuid) {
            this.actorUuid = actorUuid;
            return this;
        }

        public Builder itemId(String itemId) {
            this.itemId = itemId;
            return this;
        }

        public Builder timeRange(long minTimeMs, long maxTimeMs) {
            this.minTimeMs = minTimeMs;
            this.maxTimeMs = maxTimeMs;
            return this;
        }

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public IndexQueryFilter build() {
            return new IndexQueryFilter(dimension, exactBlockPos, centerPos, radius, actorUuid, itemId, minTimeMs, maxTimeMs, limit);
        }
    }
}
