package com.chestlogger.provenance;

import java.util.List;
import java.util.Objects;

/**
 * Complete immutable representation of an item's resolved provenance graph and chain of custody.
 */
public record ProvenanceGraph(
        String targetItemId,
        long targetPackedPos,
        List<ProvenanceNode> nodes,
        List<ProvenanceEdge> edges,
        int totalSteps,
        ConfidenceLevel overallConfidence
) {
    public ProvenanceGraph {
        Objects.requireNonNull(targetItemId, "targetItemId cannot be null");
        Objects.requireNonNull(nodes, "nodes cannot be null");
        Objects.requireNonNull(edges, "edges cannot be null");
        Objects.requireNonNull(overallConfidence, "overallConfidence cannot be null");
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    public static ProvenanceGraph empty(String targetItemId, long targetPackedPos) {
        return new ProvenanceGraph(
                targetItemId,
                targetPackedPos,
                List.of(),
                List.of(),
                0,
                ConfidenceLevel.EXACT_LINKAGE
        );
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public ProvenanceNode rootNode() {
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    public ProvenanceNode terminalNode() {
        return nodes.isEmpty() ? null : nodes.get(nodes.size() - 1);
    }
}
