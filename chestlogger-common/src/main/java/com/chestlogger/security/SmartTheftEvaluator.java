package com.chestlogger.security;

import com.chestlogger.alert.AlertConfig;
import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Intelligent theft and raid detection evaluator.
 * Evaluates container transaction log entries against container ownership, trust graphs,
 * owner online presence / Euclidean proximity, alert configurations, and sliding raid velocity windows
 * to accurately classify security incidents and mitigate false positives (e.g. consensual co-presence trading).
 */
public final class SmartTheftEvaluator {

    private final TrustManager trustManager;
    private final AlertConfig alertConfig;
    private final RaidVelocityTracker raidVelocityTracker;

    /**
     * Constructs a SmartTheftEvaluator with default TrustManager, AlertConfig defaults, and RaidVelocityTracker.
     */
    public SmartTheftEvaluator() {
        this(new TrustManager(), AlertConfig.defaults(), new RaidVelocityTracker());
    }

    /**
     * Constructs a SmartTheftEvaluator with custom TrustManager and RaidVelocityTracker.
     */
    public SmartTheftEvaluator(TrustManager trustManager, RaidVelocityTracker raidVelocityTracker) {
        this(trustManager, AlertConfig.defaults(), raidVelocityTracker);
    }

    /**
     * Constructs a SmartTheftEvaluator with custom TrustManager, AlertConfig, and RaidVelocityTracker.
     */
    public SmartTheftEvaluator(TrustManager trustManager, AlertConfig alertConfig, RaidVelocityTracker raidVelocityTracker) {
        this.trustManager = trustManager != null ? trustManager : new TrustManager();
        this.alertConfig = alertConfig != null ? alertConfig : AlertConfig.defaults();
        this.raidVelocityTracker = raidVelocityTracker != null ? raidVelocityTracker : new RaidVelocityTracker();
    }

    /**
     * Evaluates a transaction log entry against container ownership, owner presence, and security policies.
     *
     * @param entry TransactionLogEntry representing container interaction.
     * @param ownerUuid UUID of the container placer / owner (null if wilderness/unowned).
     * @param ownerName Display name of the container owner.
     * @param ownerPresence Presence and distance state of the container owner.
     * @return Optional containing SecurityIncident if actionable or mitigated security event, or empty if benign/exempt.
     */
    public Optional<SecurityIncident> evaluate(
            TransactionLogEntry entry,
            UUID ownerUuid,
            String ownerName,
            OwnerPresenceState ownerPresence
    ) {
        return evaluate(entry, ownerUuid, ownerName, ownerPresence, this.alertConfig);
    }

    /**
     * Evaluates a transaction log entry with custom AlertConfig rules.
     *
     * @param entry TransactionLogEntry representing container interaction.
     * @param ownerUuid UUID of the container placer / owner (null if wilderness/unowned).
     * @param ownerName Display name of the container owner.
     * @param ownerPresence Presence and distance state of the container owner.
     * @param config AlertConfig containing valuable item definitions and thresholds.
     * @return Optional containing SecurityIncident if actionable or mitigated security event, or empty if benign/exempt.
     */
    public Optional<SecurityIncident> evaluate(
            TransactionLogEntry entry,
            UUID ownerUuid,
            String ownerName,
            OwnerPresenceState ownerPresence,
            AlertConfig config
    ) {
        if (entry == null || entry.actorType() != ActorType.PLAYER) {
            return Optional.empty();
        }

        // 1. Check self-access and trusted teammate exemptions
        if (isExempt(ownerUuid, entry.actorUuid())) {
            return Optional.empty();
        }

        // 2. Check if action involves container theft or break
        if (!isTheftAction(entry)) {
            return Optional.empty();
        }

        // 3. Unclaimed / world-gen container: bypass raid velocity tracking and security alerting
        if (ownerUuid == null) {
            return Optional.empty();
        }

        AlertConfig effectiveConfig = config != null ? config : this.alertConfig;
        OwnerPresenceState presence = ownerPresence != null ? ownerPresence : OwnerPresenceState.offline();

        // 4. Track raid velocity & check multi-container burst
        boolean isRaidBurst = false;
        if (raidVelocityTracker != null) {
            isRaidBurst = raidVelocityTracker.recordAndCheckBurst(
                    entry.actorUuid(),
                    entry.packedBlockPos(),
                    entry.timestampMs()
            );
        }

        // 5. Classify incident
        IncidentClassification classification;
        if (isRaidBurst) {
            classification = IncidentClassification.CRITICAL_RAID;
        } else if (presence.isOnline() && presence.isNearby()) {
            classification = IncidentClassification.CONSENSUAL_PROXIMITY;
        } else if (!presence.isOnline()) {
            classification = IncidentClassification.OFFLINE_THEFT;
        } else if (presence.isOnline() && !presence.isNearby()) {
            classification = IncidentClassification.ABSENT_OWNER_THEFT;
        } else {
            classification = IncidentClassification.INFO;
        }

        // 6. Identify primary item involved
        PrimaryItemInfo itemInfo = extractPrimaryItem(entry, effectiveConfig);

        // 7. Build summary
        String summary = generateSummary(
                classification,
                entry.actorName(),
                ownerName,
                itemInfo.itemId(),
                itemInfo.deltaQuantity(),
                presence,
                isRaidBurst
        );

        SecurityIncident incident = new SecurityIncident(
                entry.timestampMs(),
                entry.sequenceId(),
                classification,
                entry.actorUuid(),
                entry.actorName(),
                ownerUuid,
                ownerName,
                presence,
                entry.packedBlockPos(),
                entry.dimension(),
                itemInfo.itemId(),
                itemInfo.deltaQuantity(),
                summary,
                isRaidBurst
        );

        return Optional.of(incident);
    }

    /**
     * Directly classifies the interaction into an IncidentClassification.
     *
     * @param entry TransactionLogEntry representing container interaction.
     * @param ownerUuid UUID of the container placer / owner.
     * @param ownerPresence Presence and distance state of the container owner.
     * @return IncidentClassification corresponding to the transaction.
     */
    public IncidentClassification classify(
            TransactionLogEntry entry,
            UUID ownerUuid,
            OwnerPresenceState ownerPresence
    ) {
        if (entry == null || entry.actorType() != ActorType.PLAYER) {
            return IncidentClassification.INFO;
        }
        if (ownerUuid == null) {
            return IncidentClassification.UNCLAIMED_NATURAL;
        }
        if (isExempt(ownerUuid, entry.actorUuid())) {
            return IncidentClassification.INFO;
        }
        if (!isTheftAction(entry)) {
            return IncidentClassification.INFO;
        }

        OwnerPresenceState presence = ownerPresence != null ? ownerPresence : OwnerPresenceState.offline();

        if (raidVelocityTracker != null && raidVelocityTracker.isRaidBurst(entry.actorUuid(), entry.timestampMs())) {
            return IncidentClassification.CRITICAL_RAID;
        }
        if (presence.isOnline() && presence.isNearby()) {
            return IncidentClassification.CONSENSUAL_PROXIMITY;
        }
        if (!presence.isOnline()) {
            return IncidentClassification.OFFLINE_THEFT;
        }
        if (presence.isOnline() && !presence.isNearby()) {
            return IncidentClassification.ABSENT_OWNER_THEFT;
        }
        return IncidentClassification.INFO;
    }

    /**
     * Checks if the interaction is exempt from security alerting (e.g. self-interaction or trusted teammate).
     *
     * @param ownerUuid UUID of the container owner.
     * @param actorUuid UUID of the interacting player.
     * @return true if exempt, false otherwise.
     */
    public boolean isExempt(UUID ownerUuid, UUID actorUuid) {
        if (actorUuid == null) {
            return false;
        }
        if (ownerUuid != null) {
            // Self-access check
            if (ownerUuid.equals(actorUuid)) {
                return true;
            }
            // Trust graph check
            if (trustManager != null && trustManager.isTrusted(ownerUuid, actorUuid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a transaction log entry represents a potential theft or container destruction action.
     *
     * @param entry TransactionLogEntry to evaluate.
     * @return true if container break or item extraction delta is present.
     */
    public boolean isTheftAction(TransactionLogEntry entry) {
        if (entry == null) {
            return false;
        }
        if (entry.actionType() == ActionType.CONTAINER_BREAK) {
            return true;
        }
        if (entry.deltas() != null) {
            for (SlotDelta delta : entry.deltas()) {
                if (delta.isExtraction()) {
                    return true;
                }
            }
        }
        return false;
    }

    private record PrimaryItemInfo(String itemId, int deltaQuantity) {}

    private PrimaryItemInfo extractPrimaryItem(TransactionLogEntry entry, AlertConfig config) {
        if (entry.deltas() != null && !entry.deltas().isEmpty()) {
            SlotDelta bestValuableDelta = null;
            int maxValuableQty = 0;

            SlotDelta bestExtractionDelta = null;
            int maxExtractionQty = 0;

            for (SlotDelta delta : entry.deltas()) {
                if (delta.isExtraction()) {
                    int absQty = Math.abs(delta.deltaQuantity());
                    if (config.valuableItems() != null && config.valuableItems().contains(delta.itemId())) {
                        if (bestValuableDelta == null || absQty > maxValuableQty) {
                            bestValuableDelta = delta;
                            maxValuableQty = absQty;
                        }
                    }
                    if (bestExtractionDelta == null || absQty > maxExtractionQty) {
                        bestExtractionDelta = delta;
                        maxExtractionQty = absQty;
                    }
                }
            }

            SlotDelta chosen = bestValuableDelta != null ? bestValuableDelta : bestExtractionDelta;
            if (chosen != null) {
                return new PrimaryItemInfo(chosen.itemId(), chosen.deltaQuantity());
            }

            // Fallback for break with non-negative delta representation
            SlotDelta first = entry.deltas().get(0);
            return new PrimaryItemInfo(first.itemId(), first.deltaQuantity());
        }

        if (entry.actionType() == ActionType.CONTAINER_BREAK) {
            return new PrimaryItemInfo("minecraft:chest", -1);
        }

        return new PrimaryItemInfo("minecraft:air", 0);
    }

    private static String generateSummary(
            IncidentClassification classification,
            String actorName,
            String ownerName,
            String itemId,
            int deltaQuantity,
            OwnerPresenceState presence,
            boolean isRaid
    ) {
        int absQty = Math.abs(deltaQuantity);
        String qtyStr = absQty > 0 ? (absQty + "x " + itemId) : itemId;
        String actorDisplay = (actorName != null && !actorName.isBlank()) ? actorName : "Unknown";
        String ownerDisplay = (ownerName != null && !ownerName.isBlank()) ? ownerName : "Unknown";

        return switch (classification) {
            case CRITICAL_RAID -> String.format(Locale.ROOT,
                    "Critical raid burst detected: %s extracted %s across multiple containers (Owner: %s)",
                    actorDisplay, qtyStr, ownerDisplay);
            case OFFLINE_THEFT -> String.format(Locale.ROOT,
                    "Offline theft detected: %s extracted %s from offline owner %s",
                    actorDisplay, qtyStr, ownerDisplay);
            case ABSENT_OWNER_THEFT -> String.format(Locale.ROOT,
                    "Absent owner theft detected: %s extracted %s while owner %s is absent (%.1f blocks away)",
                    actorDisplay, qtyStr, ownerDisplay, presence.distanceBlocks());
            case CONSENSUAL_PROXIMITY -> String.format(Locale.ROOT,
                    "Consensual proximity interaction: %s extracted %s near owner %s (%.1f blocks away)",
                    actorDisplay, qtyStr, ownerDisplay, presence.distanceBlocks());
            case UNCLAIMED_NATURAL -> String.format(Locale.ROOT,
                    "Unclaimed natural container interaction by %s", actorDisplay);
            case INFO -> String.format(Locale.ROOT,
                    "Benign container interaction by %s", actorDisplay);
        };
    }

    public TrustManager getTrustManager() {
        return trustManager;
    }

    public AlertConfig getAlertConfig() {
        return alertConfig;
    }

    public RaidVelocityTracker getRaidVelocityTracker() {
        return raidVelocityTracker;
    }
}
