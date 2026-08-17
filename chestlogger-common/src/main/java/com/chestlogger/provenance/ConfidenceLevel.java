package com.chestlogger.provenance;

/**
 * Confidence level representing the mathematical and heuristic certainty
 * of an item custody linkage in the provenance graph.
 */
public enum ConfidenceLevel {
    /**
     * Exact cryptographic or deterministic linkage: matching 64-bit metadata component
     * fingerprint, atomic transactional pairing, or exact same inventory sequence.
     */
    EXACT_LINKAGE,

    /**
     * High confidence linkage: direct player extraction followed by insertion within
     * a tight temporal custody window (e.g. <= 5 minutes) and matching quantities,
     * or direct hopper/automation flow between contiguous coordinates.
     */
    HIGH_CONFIDENCE,

    /**
     * Probable heuristic linkage: temporal flow matching for raw commodities,
     * multi-hop transfer chains, or transactions across wider time windows.
     */
    PROBABLE;

    /**
     * Combines this confidence level with another, returning the lower (weaker) confidence.
     */
    public ConfidenceLevel combine(ConfidenceLevel other) {
        if (other == null) {
            return this;
        }
        return this.ordinal() >= other.ordinal() ? this : other;
    }
}
