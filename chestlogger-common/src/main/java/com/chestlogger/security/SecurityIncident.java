package com.chestlogger.security;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable domain model representing a security incident detected and classified by the Smart Theft Engine.
 *
 * @param timestampMs Epoch timestamp in milliseconds when the incident occurred.
 * @param sequenceId Sequence ID corresponding to the underlying transaction log entry.
 * @param classification Categorization of the incident (e.g. CRITICAL_RAID, OFFLINE_THEFT).
 * @param actorUuid UUID of the player who performed the action.
 * @param actorName Username or display name of the actor.
 * @param ownerUuid UUID of the container placer / owner (may be null if unowned).
 * @param ownerName Username of the container placer / owner.
 * @param ownerPresence Presence and distance state of the container owner at incident time.
 * @param packedPos 64-bit packed block coordinates of the container.
 * @param dimension Dimension identifier (e.g. "minecraft:overworld").
 * @param itemId Resource identifier of the primary item involved (e.g. "minecraft:diamond").
 * @param deltaQuantity Quantity change (+ for insertion, - for extraction).
 * @param summary Human-readable descriptive summary of the incident.
 * @param isRaidBurst Whether this incident was part of a multi-container rapid raid burst.
 */
public record SecurityIncident(
        long timestampMs,
        long sequenceId,
        IncidentClassification classification,
        UUID actorUuid,
        String actorName,
        UUID ownerUuid,
        String ownerName,
        OwnerPresenceState ownerPresence,
        long packedPos,
        String dimension,
        String itemId,
        int deltaQuantity,
        String summary,
        boolean isRaidBurst
) {
    public SecurityIncident {
        Objects.requireNonNull(classification, "classification cannot be null");
        Objects.requireNonNull(dimension, "dimension cannot be null");
        if (ownerPresence == null) {
            ownerPresence = OwnerPresenceState.offline();
        }
        if (actorName == null) {
            actorName = actorUuid != null ? actorUuid.toString() : "Unknown";
        }
        if (ownerName == null) {
            ownerName = ownerUuid != null ? ownerUuid.toString() : "Unknown";
        }
        if (itemId == null) {
            itemId = "minecraft:air";
        }
        if (summary == null) {
            summary = "";
        }
    }

    /**
     * Whether this incident represents an actionable theft or raid event.
     */
    public boolean isTheft() {
        return classification.isTheft();
    }

    /**
     * Whether this incident was mitigated due to consensual proximity.
     */
    public boolean isMitigated() {
        return classification == IncidentClassification.CONSENSUAL_PROXIMITY;
    }
}
