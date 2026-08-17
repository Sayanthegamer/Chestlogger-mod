package com.chestlogger.provenance;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.index.IndexQueryFilter;
import com.chestlogger.query.QueryEngine;

import java.io.IOException;
import java.util.*;

/**
 * High-performance graph resolver reconstructing item provenance and chains of custody
 * across players, containers, and automation agents over time.
 */
public class ItemProvenanceResolver {

    public static final int DEFAULT_MAX_HOPS = 50;
    public static final long DEFAULT_MAX_TIME_WINDOW_MS = 7L * 24 * 60 * 60 * 1000L; // 7 days (604,800,000 ms)
    public static final long TIGHT_CUSTODY_WINDOW_MS = 5L * 60 * 1000L; // 5 minutes
    public static final long AUTOMATION_TIGHT_WINDOW_MS = 60 * 1000L; // 1 minute

    /**
     * Resolves the provenance graph for an item given its container coordinate, dimension, item ID, and metadata fingerprint.
     *
     * @param packedPos Container block position packed as long (0L to search dimension-wide)
     * @param dimension Dimension identifier (e.g. "minecraft:overworld")
     * @param itemId Item resource identifier (e.g. "minecraft:diamond_pickaxe")
     * @param metadataFingerprint 64-bit component fingerprint (0L for fungible commodities)
     * @param queryEngine QueryEngine instance to retrieve transaction logs from
     * @param maxHops Maximum number of graph traversal steps / nodes
     * @param maxTimeWindowMs Maximum lookback / lookahead time window in milliseconds
     * @return Resolved ProvenanceGraph
     * @throws IOException If disk or index I/O fails
     */
    public ProvenanceGraph resolveProvenance(
            long packedPos,
            String dimension,
            String itemId,
            long metadataFingerprint,
            QueryEngine queryEngine,
            int maxHops,
            long maxTimeWindowMs
    ) throws IOException {
        Objects.requireNonNull(itemId, "itemId cannot be null");
        Objects.requireNonNull(queryEngine, "queryEngine cannot be null");

        int effectiveMaxHops = maxHops > 0 ? maxHops : DEFAULT_MAX_HOPS;
        long effectiveTimeWindow = maxTimeWindowMs > 0 ? maxTimeWindowMs : DEFAULT_MAX_TIME_WINDOW_MS;
        String effectiveDim = (dimension != null && !dimension.isBlank()) ? dimension : "minecraft:overworld";

        // Query all candidate records for itemId in dimension
        IndexQueryFilter filter = IndexQueryFilter.builder()
                .dimension(effectiveDim)
                .itemId(itemId)
                .timeRange(0L, Long.MAX_VALUE)
                .limit(10_000)
                .build();

        List<TransactionLogEntry> allRecords = queryEngine.fetchRecords(filter);
        if (allRecords == null || allRecords.isEmpty()) {
            return ProvenanceGraph.empty(itemId, packedPos);
        }

        if (metadataFingerprint != 0L) {
            return resolveNonFungible(packedPos, effectiveDim, itemId, metadataFingerprint, allRecords, effectiveMaxHops, effectiveTimeWindow);
        } else {
            return resolveCommodity(packedPos, effectiveDim, itemId, allRecords, effectiveMaxHops, effectiveTimeWindow);
        }
    }

    /**
     * Resolves provenance using default max hops (50) and default time window (7 days).
     */
    public ProvenanceGraph resolveProvenance(
            long packedPos,
            String dimension,
            String itemId,
            long metadataFingerprint,
            QueryEngine queryEngine
    ) throws IOException {
        return resolveProvenance(packedPos, dimension, itemId, metadataFingerprint, queryEngine, DEFAULT_MAX_HOPS, DEFAULT_MAX_TIME_WINDOW_MS);
    }

    /**
     * Resolves commodity provenance with metadataFingerprint = 0L using defaults.
     */
    public ProvenanceGraph resolveProvenance(
            long packedPos,
            String dimension,
            String itemId,
            QueryEngine queryEngine
    ) throws IOException {
        return resolveProvenance(packedPos, dimension, itemId, 0L, queryEngine, DEFAULT_MAX_HOPS, DEFAULT_MAX_TIME_WINDOW_MS);
    }

    // =========================================================================
    // Non-Fungible Resolution (Matching 64-bit component metadata fingerprint)
    // =========================================================================

    private ProvenanceGraph resolveNonFungible(
            long targetPackedPos,
            String dimension,
            String itemId,
            long metadataFingerprint,
            List<TransactionLogEntry> allRecords,
            int maxHops,
            long maxTimeWindowMs
    ) {
        List<TransactionLogEntry> matchingEntries = new ArrayList<>();
        for (TransactionLogEntry entry : allRecords) {
            for (SlotDelta delta : entry.deltas()) {
                if (itemId.equals(delta.itemId()) && delta.metadataFingerprint() == metadataFingerprint) {
                    matchingEntries.add(entry);
                    break;
                }
            }
        }

        if (matchingEntries.isEmpty()) {
            return ProvenanceGraph.empty(itemId, targetPackedPos);
        }

        // Sort chronologically
        matchingEntries.sort(Comparator.comparingLong(TransactionLogEntry::timestampMs)
                .thenComparingLong(TransactionLogEntry::sequenceId));

        // Apply cycle safeguard via visited sequence IDs and max hops
        Set<Long> visitedSequenceIds = new HashSet<>();
        List<ProvenanceNode> nodes = new ArrayList<>();

        for (TransactionLogEntry entry : matchingEntries) {
            if (!visitedSequenceIds.add(entry.sequenceId())) {
                continue; // Prevent duplicate/cyclic sequence entries
            }
            if (nodes.size() >= maxHops) {
                break;
            }

            int netDelta = calculateNetDelta(entry, itemId, metadataFingerprint);
            String notes = formatNotes(entry, itemId, netDelta);

            ProvenanceNode node = new ProvenanceNode(
                    nodes.size(),
                    entry.sequenceId(),
                    entry.timestampMs(),
                    entry.actionType(),
                    entry.actorType(),
                    entry.actorUuid(),
                    entry.actorName(),
                    entry.dimension(),
                    entry.packedBlockPos(),
                    itemId,
                    netDelta,
                    metadataFingerprint,
                    ConfidenceLevel.EXACT_LINKAGE,
                    notes
            );
            nodes.add(node);
        }

        List<ProvenanceEdge> edges = buildEdges(nodes, true);
        ConfidenceLevel overall = computeOverallConfidence(nodes, edges, ConfidenceLevel.EXACT_LINKAGE);

        return new ProvenanceGraph(itemId, targetPackedPos, nodes, edges, nodes.size(), overall);
    }

    // =========================================================================
    // Commodity Flow Resolution (Spatial + Temporal Lookback / Lookahead)
    // =========================================================================

    private ProvenanceGraph resolveCommodity(
            long targetPackedPos,
            String dimension,
            String itemId,
            List<TransactionLogEntry> allRecords,
            int maxHops,
            long maxTimeWindowMs
    ) {
        // Filter candidate records for itemId with metadataFingerprint == 0L
        List<TransactionLogEntry> commodityRecords = new ArrayList<>();
        for (TransactionLogEntry entry : allRecords) {
            for (SlotDelta delta : entry.deltas()) {
                if (itemId.equals(delta.itemId())) {
                    commodityRecords.add(entry);
                    break;
                }
            }
        }

        if (commodityRecords.isEmpty()) {
            return ProvenanceGraph.empty(itemId, targetPackedPos);
        }

        // Index records by container position and actor
        Map<Long, List<TransactionLogEntry>> containerEvents = new HashMap<>();
        Map<UUID, List<TransactionLogEntry>> playerEvents = new HashMap<>();
        Map<String, List<TransactionLogEntry>> automationEvents = new HashMap<>();

        for (TransactionLogEntry entry : commodityRecords) {
            containerEvents.computeIfAbsent(entry.packedBlockPos(), k -> new ArrayList<>()).add(entry);
            if (entry.actorUuid() != null) {
                playerEvents.computeIfAbsent(entry.actorUuid(), k -> new ArrayList<>()).add(entry);
            }
            if (entry.isAutomation()) {
                String key = entry.actorName() != null ? entry.actorName() : entry.actorType().name();
                automationEvents.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
            }
        }

        // Sort sub-lists chronologically
        Comparator<TransactionLogEntry> comp = Comparator.comparingLong(TransactionLogEntry::timestampMs)
                .thenComparingLong(TransactionLogEntry::sequenceId);
        containerEvents.values().forEach(list -> list.sort(comp));
        playerEvents.values().forEach(list -> list.sort(comp));
        automationEvents.values().forEach(list -> list.sort(comp));

        // Determine seed transactions
        List<TransactionLogEntry> seedEntries = targetPackedPos != 0L
                ? containerEvents.get(targetPackedPos)
                : commodityRecords;

        if (seedEntries == null || seedEntries.isEmpty()) {
            return ProvenanceGraph.empty(itemId, targetPackedPos);
        }

        Set<Long> visitedSequenceIds = new HashSet<>();
        Set<TransactionLogEntry> collectedEntries = new LinkedHashSet<>();

        for (TransactionLogEntry seed : seedEntries) {
            if (collectedEntries.size() >= maxHops) break;
            traverseLookback(seed, itemId, containerEvents, playerEvents, automationEvents, visitedSequenceIds, collectedEntries, maxHops, maxTimeWindowMs, 0);
            collectedEntries.add(seed);
            traverseLookahead(seed, itemId, containerEvents, playerEvents, automationEvents, visitedSequenceIds, collectedEntries, maxHops, maxTimeWindowMs, 0);
        }

        if (collectedEntries.isEmpty()) {
            return ProvenanceGraph.empty(itemId, targetPackedPos);
        }

        List<TransactionLogEntry> sortedChain = new ArrayList<>(collectedEntries);
        sortedChain.sort(comp);

        if (sortedChain.size() > maxHops) {
            sortedChain = sortedChain.subList(0, maxHops);
        }

        List<ProvenanceNode> nodes = new ArrayList<>(sortedChain.size());
        for (int i = 0; i < sortedChain.size(); i++) {
            TransactionLogEntry entry = sortedChain.get(i);
            int netDelta = calculateNetDelta(entry, itemId, 0L);
            String notes = formatNotes(entry, itemId, netDelta);
            ConfidenceLevel nodeConfidence = ConfidenceLevel.HIGH_CONFIDENCE;

            ProvenanceNode node = new ProvenanceNode(
                    i,
                    entry.sequenceId(),
                    entry.timestampMs(),
                    entry.actionType(),
                    entry.actorType(),
                    entry.actorUuid(),
                    entry.actorName(),
                    entry.dimension(),
                    entry.packedBlockPos(),
                    itemId,
                    netDelta,
                    0L,
                    nodeConfidence,
                    notes
            );
            nodes.add(node);
        }

        List<ProvenanceEdge> edges = buildEdges(nodes, false);
        ConfidenceLevel overall = computeOverallConfidence(nodes, edges, ConfidenceLevel.HIGH_CONFIDENCE);

        return new ProvenanceGraph(itemId, targetPackedPos, nodes, edges, nodes.size(), overall);
    }

    // =========================================================================
    // Lookback / Lookahead Recursive Traversal
    // =========================================================================

    private void traverseLookback(
            TransactionLogEntry current,
            String itemId,
            Map<Long, List<TransactionLogEntry>> containerEvents,
            Map<UUID, List<TransactionLogEntry>> playerEvents,
            Map<String, List<TransactionLogEntry>> automationEvents,
            Set<Long> visited,
            Set<TransactionLogEntry> collected,
            int maxHops,
            long maxTimeWindowMs,
            int depth
    ) {
        if (depth >= maxHops || collected.size() >= maxHops || (depth > 0 && visited.contains(current.sequenceId()))) {
            return;
        }
        visited.add(current.sequenceId());

        int delta = calculateNetDelta(current, itemId, 0L);
        long t = current.timestampMs();
        long minTime = Math.max(0L, t - maxTimeWindowMs);

        if (delta > 0) {
            // Insertion into current container -> look for prior extraction by same actor
            if (current.actorUuid() != null && playerEvents.containsKey(current.actorUuid())) {
                List<TransactionLogEntry> pList = playerEvents.get(current.actorUuid());
                for (int i = pList.size() - 1; i >= 0; i--) {
                    TransactionLogEntry candidate = pList.get(i);
                    if (candidate.timestampMs() <= t && candidate.timestampMs() >= minTime
                            && candidate.sequenceId() != current.sequenceId()
                            && !visited.contains(candidate.sequenceId())) {
                        int cDelta = calculateNetDelta(candidate, itemId, 0L);
                        if (cDelta < 0) {
                            collected.add(candidate);
                            traverseLookback(candidate, itemId, containerEvents, playerEvents, automationEvents, visited, collected, maxHops, maxTimeWindowMs, depth + 1);
                            break;
                        }
                    }
                }
            } else if (current.isAutomation()) {
                String key = current.actorName() != null ? current.actorName() : current.actorType().name();
                List<TransactionLogEntry> aList = automationEvents.get(key);
                if (aList != null) {
                    for (int i = aList.size() - 1; i >= 0; i--) {
                        TransactionLogEntry candidate = aList.get(i);
                        if (candidate.timestampMs() <= t && candidate.timestampMs() >= minTime
                                && candidate.sequenceId() != current.sequenceId()
                                && !visited.contains(candidate.sequenceId())) {
                            int cDelta = calculateNetDelta(candidate, itemId, 0L);
                            if (cDelta < 0) {
                                collected.add(candidate);
                                traverseLookback(candidate, itemId, containerEvents, playerEvents, automationEvents, visited, collected, maxHops, maxTimeWindowMs, depth + 1);
                                break;
                            }
                        }
                    }
                }
            }
        } else if (delta < 0) {
            // Extraction from current container -> look for prior deposit into current container
            List<TransactionLogEntry> cList = containerEvents.get(current.packedBlockPos());
            if (cList != null) {
                for (int i = cList.size() - 1; i >= 0; i--) {
                    TransactionLogEntry candidate = cList.get(i);
                    if (candidate.timestampMs() <= t && candidate.timestampMs() >= minTime
                            && candidate.sequenceId() != current.sequenceId()
                            && !visited.contains(candidate.sequenceId())) {
                        int cDelta = calculateNetDelta(candidate, itemId, 0L);
                        if (cDelta > 0) {
                            collected.add(candidate);
                            traverseLookback(candidate, itemId, containerEvents, playerEvents, automationEvents, visited, collected, maxHops, maxTimeWindowMs, depth + 1);
                            break;
                        }
                    }
                }
            }
        }
    }

    private void traverseLookahead(
            TransactionLogEntry current,
            String itemId,
            Map<Long, List<TransactionLogEntry>> containerEvents,
            Map<UUID, List<TransactionLogEntry>> playerEvents,
            Map<String, List<TransactionLogEntry>> automationEvents,
            Set<Long> visited,
            Set<TransactionLogEntry> collected,
            int maxHops,
            long maxTimeWindowMs,
            int depth
    ) {
        if (depth >= maxHops || collected.size() >= maxHops || (depth > 0 && visited.contains(current.sequenceId()))) {
            return;
        }
        visited.add(current.sequenceId());

        int delta = calculateNetDelta(current, itemId, 0L);
        long t = current.timestampMs();
        long maxTime = t + maxTimeWindowMs;

        if (delta < 0) {
            // Extraction from current container -> look for next deposit by same actor
            if (current.actorUuid() != null && playerEvents.containsKey(current.actorUuid())) {
                List<TransactionLogEntry> pList = playerEvents.get(current.actorUuid());
                for (TransactionLogEntry candidate : pList) {
                    if (candidate.timestampMs() >= t && candidate.timestampMs() <= maxTime
                            && candidate.sequenceId() != current.sequenceId()
                            && !visited.contains(candidate.sequenceId())) {
                        int cDelta = calculateNetDelta(candidate, itemId, 0L);
                        if (cDelta > 0) {
                            collected.add(candidate);
                            traverseLookahead(candidate, itemId, containerEvents, playerEvents, automationEvents, visited, collected, maxHops, maxTimeWindowMs, depth + 1);
                            break;
                        }
                    }
                }
            } else if (current.isAutomation()) {
                String key = current.actorName() != null ? current.actorName() : current.actorType().name();
                List<TransactionLogEntry> aList = automationEvents.get(key);
                if (aList != null) {
                    for (TransactionLogEntry candidate : aList) {
                        if (candidate.timestampMs() >= t && candidate.timestampMs() <= maxTime
                                && candidate.sequenceId() != current.sequenceId()
                                && !visited.contains(candidate.sequenceId())) {
                            int cDelta = calculateNetDelta(candidate, itemId, 0L);
                            if (cDelta > 0) {
                                collected.add(candidate);
                                traverseLookahead(candidate, itemId, containerEvents, playerEvents, automationEvents, visited, collected, maxHops, maxTimeWindowMs, depth + 1);
                                break;
                            }
                        }
                    }
                }
            }
        } else if (delta > 0) {
            // Deposit into current container -> look for next extraction from current container
            List<TransactionLogEntry> cList = containerEvents.get(current.packedBlockPos());
            if (cList != null) {
                for (TransactionLogEntry candidate : cList) {
                    if (candidate.timestampMs() >= t && candidate.timestampMs() <= maxTime
                            && candidate.sequenceId() != current.sequenceId()
                            && !visited.contains(candidate.sequenceId())) {
                        int cDelta = calculateNetDelta(candidate, itemId, 0L);
                        if (cDelta < 0) {
                            collected.add(candidate);
                            traverseLookahead(candidate, itemId, containerEvents, playerEvents, automationEvents, visited, collected, maxHops, maxTimeWindowMs, depth + 1);
                            break;
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // Edge Construction & Confidence Scoring
    // =========================================================================

    private List<ProvenanceEdge> buildEdges(List<ProvenanceNode> nodes, boolean isNonFungible) {
        List<ProvenanceEdge> edges = new ArrayList<>();
        for (int i = 0; i < nodes.size() - 1; i++) {
            ProvenanceNode from = nodes.get(i);
            ProvenanceNode to = nodes.get(i + 1);
            long timeDelta = Math.abs(to.timestampMs() - from.timestampMs());

            String transitionType = determineTransitionType(from, to, isNonFungible);
            ConfidenceLevel confidence = determineEdgeConfidence(from, to, transitionType, timeDelta, isNonFungible);

            edges.add(new ProvenanceEdge(from, to, timeDelta, confidence, transitionType));
        }
        return edges;
    }

    private String determineTransitionType(ProvenanceNode from, ProvenanceNode to, boolean isNonFungible) {
        if (isNonFungible) {
            if (from.actorType() == ActorType.AUTOMATION || to.actorType() == ActorType.AUTOMATION) {
                if (isGolem(from.actorName()) || isGolem(to.actorName())) {
                    return "GOLEM_TRANSFER";
                }
                return "AUTOMATION_TRANSFER";
            }
            if (isHopper(from.actorType()) || isHopper(to.actorType())) {
                return "AUTOMATION_TRANSFER";
            }
            if (from.packedPos() == to.packedPos()) {
                return "CONTAINER_HANDOFF";
            }
            if (from.actorUuid() != null && from.actorUuid().equals(to.actorUuid())) {
                return "DIRECT_CUSTODY";
            }
            return "FINGERPRINT_MATCH";
        }

        // Commodity transitions
        if (from.actorType() == ActorType.AUTOMATION || to.actorType() == ActorType.AUTOMATION) {
            if (isGolem(from.actorName()) || isGolem(to.actorName())) {
                return "GOLEM_TRANSFER";
            }
            return "AUTOMATION_TRANSFER";
        }
        if (isHopper(from.actorType()) || isHopper(to.actorType())) {
            return "AUTOMATION_TRANSFER";
        }
        if (from.packedPos() == to.packedPos()) {
            return "CONTAINER_HANDOFF";
        }
        if (from.actorUuid() != null && from.actorUuid().equals(to.actorUuid())) {
            return "DIRECT_CUSTODY";
        }
        return "PROBABLE_FLOW";
    }

    private ConfidenceLevel determineEdgeConfidence(
            ProvenanceNode from,
            ProvenanceNode to,
            String transitionType,
            long timeDelta,
            boolean isNonFungible
    ) {
        if (isNonFungible) {
            return ConfidenceLevel.EXACT_LINKAGE;
        }

        boolean quantitiesMatch = Math.abs(from.deltaQuantity()) == Math.abs(to.deltaQuantity());

        switch (transitionType) {
            case "DIRECT_CUSTODY" -> {
                if (timeDelta <= TIGHT_CUSTODY_WINDOW_MS && quantitiesMatch) {
                    return ConfidenceLevel.HIGH_CONFIDENCE;
                }
                return ConfidenceLevel.PROBABLE;
            }
            case "GOLEM_TRANSFER", "AUTOMATION_TRANSFER" -> {
                if (timeDelta <= AUTOMATION_TIGHT_WINDOW_MS && quantitiesMatch) {
                    return ConfidenceLevel.HIGH_CONFIDENCE;
                }
                return ConfidenceLevel.PROBABLE;
            }
            case "CONTAINER_HANDOFF" -> {
                if (timeDelta <= TIGHT_CUSTODY_WINDOW_MS && quantitiesMatch) {
                    return ConfidenceLevel.HIGH_CONFIDENCE;
                }
                return ConfidenceLevel.PROBABLE;
            }
            default -> {
                return ConfidenceLevel.PROBABLE;
            }
        }
    }

    private ConfidenceLevel computeOverallConfidence(
            List<ProvenanceNode> nodes,
            List<ProvenanceEdge> edges,
            ConfidenceLevel initial
    ) {
        ConfidenceLevel result = initial;
        for (ProvenanceNode node : nodes) {
            result = result.combine(node.confidence());
        }
        for (ProvenanceEdge edge : edges) {
            result = result.combine(edge.confidence());
        }
        return result;
    }

    // =========================================================================
    // Utility Helpers
    // =========================================================================

    private int calculateNetDelta(TransactionLogEntry entry, String itemId, long metadataFingerprint) {
        int net = 0;
        for (SlotDelta delta : entry.deltas()) {
            if (itemId.equals(delta.itemId())) {
                if (metadataFingerprint == 0L || delta.metadataFingerprint() == metadataFingerprint) {
                    net += delta.deltaQuantity();
                }
            }
        }
        return net;
    }

    private String formatNotes(TransactionLogEntry entry, String itemId, int netDelta) {
        String actionStr = netDelta < 0 ? "Extracted " + Math.abs(netDelta) : "Deposited " + netDelta;
        int x = BlockPosUtil.unpackX(entry.packedBlockPos());
        int y = BlockPosUtil.unpackY(entry.packedBlockPos());
        int z = BlockPosUtil.unpackZ(entry.packedBlockPos());
        return String.format("%s %s at [%d, %d, %d] by %s", actionStr, itemId, x, y, z, entry.actorName());
    }

    private boolean isGolem(String actorName) {
        if (actorName == null) return false;
        String lower = actorName.toLowerCase();
        return lower.contains("golem") || lower.contains("allay");
    }

    private boolean isHopper(ActorType actorType) {
        return actorType == ActorType.HOPPER_BLOCK || actorType == ActorType.HOPPER_MINECART;
    }
}
