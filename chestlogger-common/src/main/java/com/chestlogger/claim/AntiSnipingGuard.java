package com.chestlogger.claim;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.index.IndexQueryFilter;
import com.chestlogger.query.QueryEngine;
import com.chestlogger.security.TrustManager;

import java.util.List;
import java.util.UUID;

/**
 * Validates container claiming attempts against historical transaction provenance
 * to prevent claim sniping on pre-existing or unclaimed containers.
 */
public final class AntiSnipingGuard {

    private AntiSnipingGuard() {}

    public record SnipingCheckResult(boolean allowed, UUID primaryOwnerUuid, String primaryOwnerName, String reason) {
        public static SnipingCheckResult pass() {
            return new SnipingCheckResult(true, null, null, null);
        }

        public static SnipingCheckResult blocked(UUID ownerUuid, String ownerName, String reason) {
            return new SnipingCheckResult(false, ownerUuid, ownerName, reason);
        }
    }

    /**
     * Evaluates claim with an existing history list.
     */
    public static SnipingCheckResult evaluateClaim(
            List<TransactionLogEntry> history,
            TrustManager trustManager,
            String dimension,
            long packedPos,
            UUID claimantUuid,
            boolean isClaimantAdmin
    ) {
        if (isClaimantAdmin) {
            return SnipingCheckResult.pass();
        }

        if (history == null || history.isEmpty()) {
            return SnipingCheckResult.pass();
        }

        // 1. Check for original CONTAINER_PLACE
        for (TransactionLogEntry entry : history) {
            if (entry.actionType() == ActionType.CONTAINER_PLACE && entry.actorType() == ActorType.PLAYER) {
                UUID placer = entry.actorUuid();
                if (placer != null && !placer.equals(claimantUuid)) {
                    if (trustManager != null && trustManager.isTrusted(placer, claimantUuid)) {
                        return SnipingCheckResult.pass();
                    }
                    return SnipingCheckResult.blocked(placer, entry.actorName(), "Container was placed by " + (entry.actorName() != null ? entry.actorName() : "another player"));
                }
            }
        }

        // 2. Check for prominent interaction actor if no place event exists
        for (TransactionLogEntry entry : history) {
            if (entry.actorType() == ActorType.PLAYER && entry.actorUuid() != null) {
                UUID actor = entry.actorUuid();
                if (!actor.equals(claimantUuid)) {
                    if (trustManager != null && trustManager.isTrusted(actor, claimantUuid)) {
                        return SnipingCheckResult.pass();
                    }
                    return SnipingCheckResult.blocked(actor, entry.actorName(), "Container transaction history belongs to " + (entry.actorName() != null ? entry.actorName() : "another player"));
                }
            }
        }

        return SnipingCheckResult.pass();
    }

    /**
     * Evaluates if a player is allowed to claim an unclaimed container based on historical transaction logs.
     */
    public static SnipingCheckResult evaluateClaim(
            QueryEngine queryEngine,
            TrustManager trustManager,
            String dimension,
            long packedPos,
            UUID claimantUuid,
            boolean isClaimantAdmin
    ) {
        if (isClaimantAdmin) {
            return SnipingCheckResult.pass();
        }

        if (queryEngine == null) {
            return SnipingCheckResult.pass();
        }

        IndexQueryFilter filter = IndexQueryFilter.builder()
                .dimension(dimension)
                .exactBlockPos(packedPos)
                .timeRange(0L, Long.MAX_VALUE)
                .limit(100)
                .build();

        List<TransactionLogEntry> history;
        try {
            history = queryEngine.fetchRecords(filter);
        } catch (Exception e) {
            return SnipingCheckResult.pass();
        }

        return evaluateClaim(history, trustManager, dimension, packedPos, claimantUuid, isClaimantAdmin);
    }
}
