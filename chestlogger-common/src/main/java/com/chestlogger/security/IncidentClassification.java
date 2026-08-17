package com.chestlogger.security;

/**
 * Classification types for security incidents evaluated by the Smart Theft & Raid Detection engine.
 */
public enum IncidentClassification {
    /**
     * Rapid multi-container raid burst detected across multiple distinct container locations.
     */
    CRITICAL_RAID,

    /**
     * Container theft occurring while the container owner is offline.
     */
    OFFLINE_THEFT,

    /**
     * Container theft occurring while the container owner is online but far away / absent from the container.
     */
    ABSENT_OWNER_THEFT,

    /**
     * Interaction occurring while the container owner is co-present / nearby (within proximity threshold),
     * indicating consensual trading or cooperative item sharing.
     */
    CONSENSUAL_PROXIMITY,

    /**
     * Benign or low-priority information event (e.g. self-access, trusted teammate interaction, deposits).
     */
    INFO;

    /**
     * Whether this classification represents a hostile theft or raid event.
     */
    public boolean isTheft() {
        return this == CRITICAL_RAID || this == OFFLINE_THEFT || this == ABSENT_OWNER_THEFT;
    }

    /**
     * Whether this classification warrants a high-priority security alert.
     */
    public boolean isAlertWorthy() {
        return this == CRITICAL_RAID || this == OFFLINE_THEFT || this == ABSENT_OWNER_THEFT;
    }
}
