package com.chestlogger.provenance;

import java.util.Objects;

/**
 * Represents a directional custody transition between two provenance nodes.
 */
public record ProvenanceEdge(
        ProvenanceNode from,
        ProvenanceNode to,
        long timeDeltaMs,
        ConfidenceLevel confidence,
        String transitionType
) {
    public ProvenanceEdge {
        Objects.requireNonNull(from, "from cannot be null");
        Objects.requireNonNull(to, "to cannot be null");
        Objects.requireNonNull(confidence, "confidence cannot be null");
        Objects.requireNonNull(transitionType, "transitionType cannot be null");
    }

    public static ProvenanceEdge between(ProvenanceNode from, ProvenanceNode to, String transitionType, ConfidenceLevel confidence) {
        long delta = Math.abs(to.timestampMs() - from.timestampMs());
        return new ProvenanceEdge(from, to, delta, confidence, transitionType);
    }
}
