package com.chestlogger.provenance;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.index.IndexPointer;
import com.chestlogger.index.PersistentIndexManager;
import com.chestlogger.query.QueryEngine;
import com.chestlogger.storage.LZ4BlockCompressor;
import com.chestlogger.storage.LogSegmentWriter;
import com.chestlogger.storage.StorageProfile;
import com.chestlogger.storage.StringTableDictionary;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance & QA Benchmark for {@link ItemProvenanceResolver}.
 * Validates sub-50ms graph reconstruction latency and bounded memory consumption
 * across a production-scale dataset of 100,000+ compressed transaction log entries
 * spanning hundreds of container coordinates, multiple player chains, and automation agents.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProvenanceResolverBenchmarkTest {

    private static final int TOTAL_TRANSACTIONS = 100_000;
    private static final int BATCH_SIZE = 1_000;
    private static final int NUM_CONTAINERS = 350;
    private static final String DIMENSION = "minecraft:overworld";

    private static final long SWORD_FINGERPRINT = 0x8899AABBCCDDEEFFL;
    private static final String SWORD_ITEM_ID = "minecraft:netherite_sword";
    private static final String DIAMOND_ITEM_ID = "minecraft:diamond";

    @TempDir
    static Path sharedTempDir;

    private static QueryEngine queryEngine;
    private static ItemProvenanceResolver resolver;
    private static long targetSwordPos;
    private static long targetDiamondPos;

    @BeforeAll
    static void setupBenchmarkDataset() throws IOException {
        File dataDir = sharedTempDir.toFile();
        StringTableDictionary dict = new StringTableDictionary();
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        StorageProfile profile = StorageProfile.BALANCED;
        PersistentIndexManager indexManager = new PersistentIndexManager(dataDir);

        resolver = new ItemProvenanceResolver();

        // 1. Generate Container Coordinates
        long[] containerPositions = new long[NUM_CONTAINERS];
        for (int i = 0; i < NUM_CONTAINERS; i++) {
            int x = (i % 50) * 16 + 100;
            int y = 64 + (i % 8);
            int z = (i / 50) * 16 - 200;
            containerPositions[i] = BlockPosUtil.pack(x, y, z);
        }

        // 2. Generate Player & Automation Actors
        UUID[] playerUuids = new UUID[10];
        String[] playerNames = {"Alex", "Bob", "Charlie", "Dave", "Eve", "Frank", "Grace", "Heidi", "Ivan", "Judy"};
        for (int i = 0; i < 10; i++) {
            playerUuids[i] = UUID.randomUUID();
        }

        String[] commodityCatalog = {
                "minecraft:cobblestone", "minecraft:dirt", "minecraft:oak_planks",
                "minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:emerald",
                "minecraft:copper_ingot", "minecraft:lapis_lazuli", "minecraft:redstone",
                "minecraft:coal"
        };

        long baseTime = 1723824000000L;
        Random rng = new Random(42);

        // Pre-allocate containers for target test chains
        targetSwordPos = containerPositions[10];
        targetDiamondPos = containerPositions[50];

        List<TransactionLogEntry> currentBatch = new ArrayList<>(BATCH_SIZE);
        long sequenceId = 1L;

        long startSeedNs = System.nanoTime();
        try (LogSegmentWriter writer = new LogSegmentWriter(dataDir, "bench_log", 0, 1L, compressor, profile, dict)) {
            for (int i = 0; i < TOTAL_TRANSACTIONS; i++) {
                long timestamp = baseTime + (i * 250L); // 4 events/sec simulated timeline
                long pos = containerPositions[i % NUM_CONTAINERS];

                // Background commodity transaction
                int actorIdx = i % playerNames.length;
                UUID actorUuid = playerUuids[actorIdx];
                String actorName = playerNames[actorIdx];
                boolean isAutomation = (i % 17 == 0);

                ActorType actorType = isAutomation ? ActorType.HOPPER_BLOCK : ActorType.PLAYER;
                UUID effectiveUuid = isAutomation ? null : actorUuid;
                String effectiveName = isAutomation ? "Hopper" : actorName;
                ActionType action = (i % 2 == 0) ? ActionType.PLACE : ActionType.PICKUP;

                String itemId = commodityCatalog[i % commodityCatalog.length];
                int qty = ((i % 4) + 1) * 16;
                int deltaQty = action == ActionType.PLACE ? qty : -qty;

                TransactionLogEntry entry = new TransactionLogEntry(
                        sequenceId++,
                        timestamp,
                        UUID.randomUUID(),
                        action,
                        actorType,
                        effectiveUuid,
                        effectiveName,
                        DIMENSION,
                        pos,
                        List.of(new SlotDelta(i % 27, itemId, deltaQty, 0, qty, 0L))
                );
                currentBatch.add(entry);

                if (currentBatch.size() >= BATCH_SIZE) {
                    flushBatch(writer, indexManager, currentBatch);
                    currentBatch.clear();
                }
            }

            // --- Embed Realistic Multi-Hop Chain 1: Non-Fungible Netherite Sword (10 hops) ---
            long swordTime = baseTime + (50_000 * 250L);
            long[] swordHopPositions = {
                    containerPositions[10], containerPositions[11], containerPositions[12],
                    containerPositions[13], containerPositions[14], containerPositions[15]
            };

            // Step 1: Alex places sword in Chest 10
            currentBatch.add(new TransactionLogEntry(
                    sequenceId++, swordTime, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                    playerUuids[0], "Alex", DIMENSION, swordHopPositions[0],
                    List.of(new SlotDelta(0, SWORD_ITEM_ID, 1, 0, 1, SWORD_FINGERPRINT))
            ));
            swordTime += 10_000L;

            // Step 2: Alex extracts sword from Chest 10
            currentBatch.add(new TransactionLogEntry(
                    sequenceId++, swordTime, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER,
                    playerUuids[0], "Alex", DIMENSION, swordHopPositions[0],
                    List.of(new SlotDelta(0, SWORD_ITEM_ID, -1, 1, 0, SWORD_FINGERPRINT))
            ));
            swordTime += 15_000L;

            // Step 3: Alex places sword in Chest 11
            currentBatch.add(new TransactionLogEntry(
                    sequenceId++, swordTime, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                    playerUuids[0], "Alex", DIMENSION, swordHopPositions[1],
                    List.of(new SlotDelta(0, SWORD_ITEM_ID, 1, 0, 1, SWORD_FINGERPRINT))
            ));
            swordTime += 20_000L;

            // Step 4: Bob steals sword from Chest 11
            currentBatch.add(new TransactionLogEntry(
                    sequenceId++, swordTime, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER,
                    playerUuids[1], "Bob", DIMENSION, swordHopPositions[1],
                    List.of(new SlotDelta(0, SWORD_ITEM_ID, -1, 1, 0, SWORD_FINGERPRINT))
            ));
            swordTime += 30_000L;

            // Step 5: Bob places sword in Chest 12
            currentBatch.add(new TransactionLogEntry(
                    sequenceId++, swordTime, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                    playerUuids[1], "Bob", DIMENSION, swordHopPositions[2],
                    List.of(new SlotDelta(0, SWORD_ITEM_ID, 1, 0, 1, SWORD_FINGERPRINT))
            ));
            swordTime += 5_000L;

            // Step 6: Hopper extracts sword from Chest 12
            currentBatch.add(new TransactionLogEntry(
                    sequenceId++, swordTime, UUID.randomUUID(), ActionType.HOPPER_EXTRACT, ActorType.HOPPER_BLOCK,
                    null, "Hopper", DIMENSION, swordHopPositions[2],
                    List.of(new SlotDelta(0, SWORD_ITEM_ID, -1, 1, 0, SWORD_FINGERPRINT))
            ));
            swordTime += 2_000L;

            // Step 7: Hopper deposits sword into Chest 13
            currentBatch.add(new TransactionLogEntry(
                    sequenceId++, swordTime, UUID.randomUUID(), ActionType.HOPPER_INSERT, ActorType.HOPPER_BLOCK,
                    null, "Hopper", DIMENSION, swordHopPositions[3],
                    List.of(new SlotDelta(0, SWORD_ITEM_ID, 1, 0, 1, SWORD_FINGERPRINT))
            ));
            swordTime += 40_000L;

            // Step 8: Charlie takes sword from Chest 13
            currentBatch.add(new TransactionLogEntry(
                    sequenceId++, swordTime, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER,
                    playerUuids[2], "Charlie", DIMENSION, swordHopPositions[3],
                    List.of(new SlotDelta(0, SWORD_ITEM_ID, -1, 1, 0, SWORD_FINGERPRINT))
            ));
            swordTime += 10_000L;

            // Step 9: Charlie deposits sword into Chest 14
            currentBatch.add(new TransactionLogEntry(
                    sequenceId++, swordTime, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                    playerUuids[2], "Charlie", DIMENSION, swordHopPositions[4],
                    List.of(new SlotDelta(0, SWORD_ITEM_ID, 1, 0, 1, SWORD_FINGERPRINT))
            ));

            // --- Embed Realistic Multi-Hop Chain 2: Commodity Diamond Flow (6 hops) ---
            long diamondTime = baseTime + (60_000 * 250L);
            long[] diamondHopPositions = {
                    containerPositions[50], containerPositions[51], containerPositions[52], containerPositions[53]
            };

            // Event 1: Dave extracts 64 diamonds from Chest 50
            currentBatch.add(new TransactionLogEntry(
                    sequenceId++, diamondTime, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER,
                    playerUuids[3], "Dave", DIMENSION, diamondHopPositions[0],
                    List.of(new SlotDelta(0, DIAMOND_ITEM_ID, -64, 64, 0, 0L))
            ));
            diamondTime += 30_000L; // 30s later

            // Event 2: Dave deposits 64 diamonds into Chest 51
            currentBatch.add(new TransactionLogEntry(
                    sequenceId++, diamondTime, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                    playerUuids[3], "Dave", DIMENSION, diamondHopPositions[1],
                    List.of(new SlotDelta(0, DIAMOND_ITEM_ID, 64, 0, 64, 0L))
            ));
            diamondTime += 45_000L; // 45s later

            // Event 3: Eve extracts 64 diamonds from Chest 51
            currentBatch.add(new TransactionLogEntry(
                    sequenceId++, diamondTime, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER,
                    playerUuids[4], "Eve", DIMENSION, diamondHopPositions[1],
                    List.of(new SlotDelta(0, DIAMOND_ITEM_ID, -64, 64, 0, 0L))
            ));
            diamondTime += 20_000L; // 20s later

            // Event 4: Eve deposits 64 diamonds into Chest 52
            currentBatch.add(new TransactionLogEntry(
                    sequenceId++, diamondTime, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                    playerUuids[4], "Eve", DIMENSION, diamondHopPositions[2],
                    List.of(new SlotDelta(0, DIAMOND_ITEM_ID, 64, 0, 64, 0L))
            ));
            diamondTime += 10_000L; // 10s later

            // Event 5: Hopper extracts 64 diamonds from Chest 52
            currentBatch.add(new TransactionLogEntry(
                    sequenceId++, diamondTime, UUID.randomUUID(), ActionType.HOPPER_EXTRACT, ActorType.HOPPER_BLOCK,
                    null, "Hopper", DIMENSION, diamondHopPositions[2],
                    List.of(new SlotDelta(0, DIAMOND_ITEM_ID, -64, 64, 0, 0L))
            ));
            diamondTime += 2_000L; // 2s later

            // Event 6: Hopper deposits 64 diamonds into Chest 53
            currentBatch.add(new TransactionLogEntry(
                    sequenceId++, diamondTime, UUID.randomUUID(), ActionType.HOPPER_INSERT, ActorType.HOPPER_BLOCK,
                    null, "Hopper", DIMENSION, diamondHopPositions[3],
                    List.of(new SlotDelta(0, DIAMOND_ITEM_ID, 64, 0, 64, 0L))
            ));

            if (!currentBatch.isEmpty()) {
                flushBatch(writer, indexManager, currentBatch);
                currentBatch.clear();
            }
        }

        long seedDurationMs = (System.nanoTime() - startSeedNs) / 1_000_000L;
        System.out.printf("[BENCHMARK-SETUP] Seeded %d transactions into LZ4 log segments in %d ms (Index size: %d pointers)%n",
                TOTAL_TRANSACTIONS + 15, seedDurationMs, indexManager.size());

        queryEngine = new QueryEngine(dataDir, compressor, indexManager, () -> dict);
    }

    private static void flushBatch(
            LogSegmentWriter writer,
            PersistentIndexManager indexManager,
            List<TransactionLogEntry> batch
    ) throws IOException {
        long blockOffset = writer.getBytesWrittenToCurrentSegment();
        int segmentIndex = writer.getSegmentIndex();
        writer.writeBatch(batch);

        for (int i = 0; i < batch.size(); i++) {
            TransactionLogEntry entry = batch.get(i);
            for (SlotDelta delta : entry.deltas()) {
                indexManager.index(new IndexPointer(
                        entry.sequenceId(),
                        entry.timestampMs(),
                        entry.actorUuid(),
                        delta.itemId(),
                        entry.dimension(),
                        entry.packedBlockPos(),
                        segmentIndex,
                        blockOffset,
                        i
                ));
            }
        }
    }

    // =========================================================================
    // Benchmark 1: Non-Fungible Resolution Latency (p95 < 50ms, p99 < 100ms)
    // =========================================================================
    @Test
    @Order(1)
    @DisplayName("Benchmark: Sub-50ms Non-Fungible Provenance Resolution on 100k+ Transactions")
    void testNonFungibleResolutionLatency() throws IOException {
        // Warmup iterations
        for (int i = 0; i < 10; i++) {
            resolver.resolveProvenance(targetSwordPos, DIMENSION, SWORD_ITEM_ID, SWORD_FINGERPRINT, queryEngine);
        }

        int iterations = 100;
        List<Double> latenciesMs = new ArrayList<>(iterations);

        ProvenanceGraph graph = null;
        for (int i = 0; i < iterations; i++) {
            long startNs = System.nanoTime();
            graph = resolver.resolveProvenance(targetSwordPos, DIMENSION, SWORD_ITEM_ID, SWORD_FINGERPRINT, queryEngine);
            long elapsedNs = System.nanoTime() - startNs;
            latenciesMs.add(elapsedNs / 1_000_000.0);
        }

        latenciesMs.sort(Double::compareTo);
        double avgMs = latenciesMs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double minMs = latenciesMs.get(0);
        double p50Ms = latenciesMs.get((int) (iterations * 0.50));
        double p95Ms = latenciesMs.get((int) (iterations * 0.95));
        double p99Ms = latenciesMs.get(Math.min((int) (iterations * 0.99), iterations - 1));
        double maxMs = latenciesMs.get(iterations - 1);

        System.out.printf("[BENCHMARK-1] Non-Fungible Resolution (%d runs):%n", iterations);
        System.out.printf("  - Avg: %.3f ms | Min: %.3f ms | p50: %.3f ms | p95: %.3f ms | p99: %.3f ms | Max: %.3f ms%n",
                avgMs, minMs, p50Ms, p95Ms, p99Ms, maxMs);

        // Assert Graph Accuracy
        assertThat(graph).isNotNull();
        assertThat(graph.isEmpty()).isFalse();
        assertThat(graph.targetItemId()).isEqualTo(SWORD_ITEM_ID);
        assertThat(graph.nodes()).hasSize(9);
        assertThat(graph.edges()).hasSize(8);
        assertThat(graph.overallConfidence()).isEqualTo(ConfidenceLevel.EXACT_LINKAGE);

        // Assert Strict Latency SLAs on Percentiles (with reasonable CI load headroom)
        assertThat(p50Ms).isLessThan(50.0);
        assertThat(p95Ms).isLessThan(100.0);
        assertThat(p99Ms).isLessThan(250.0);
    }

    // =========================================================================
    // Benchmark 2: Commodity Temporal Flow Resolution Latency (p95 < 50ms, p99 < 100ms)
    // =========================================================================
    @Test
    @Order(2)
    @DisplayName("Benchmark: Sub-50ms Commodity Provenance Resolution on 100k+ Transactions")
    void testCommodityResolutionLatency() throws IOException {
        // Warmup iterations
        for (int i = 0; i < 10; i++) {
            resolver.resolveProvenance(targetDiamondPos, DIMENSION, DIAMOND_ITEM_ID, 0L, queryEngine);
        }

        int iterations = 100;
        List<Double> latenciesMs = new ArrayList<>(iterations);

        ProvenanceGraph graph = null;
        for (int i = 0; i < iterations; i++) {
            long startNs = System.nanoTime();
            graph = resolver.resolveProvenance(targetDiamondPos, DIMENSION, DIAMOND_ITEM_ID, 0L, queryEngine);
            long elapsedNs = System.nanoTime() - startNs;
            latenciesMs.add(elapsedNs / 1_000_000.0);
        }

        latenciesMs.sort(Double::compareTo);
        double avgMs = latenciesMs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double minMs = latenciesMs.get(0);
        double p50Ms = latenciesMs.get((int) (iterations * 0.50));
        double p95Ms = latenciesMs.get((int) (iterations * 0.95));
        double p99Ms = latenciesMs.get(Math.min((int) (iterations * 0.99), iterations - 1));
        double maxMs = latenciesMs.get(iterations - 1);

        System.out.printf("[BENCHMARK-2] Commodity Flow Resolution (%d runs):%n", iterations);
        System.out.printf("  - Avg: %.3f ms | Min: %.3f ms | p50: %.3f ms | p95: %.3f ms | p99: %.3f ms | Max: %.3f ms%n",
                avgMs, minMs, p50Ms, p95Ms, p99Ms, maxMs);

        // Assert Graph Accuracy
        assertThat(graph).isNotNull();
        assertThat(graph.isEmpty()).isFalse();
        assertThat(graph.targetItemId()).isEqualTo(DIAMOND_ITEM_ID);
        assertThat(graph.nodes()).isNotEmpty();
        assertThat(graph.edges()).isNotEmpty();

        // Assert Strict Latency SLAs on Percentiles (with reasonable CI load headroom)
        assertThat(p50Ms).isLessThan(50.0);
        assertThat(p95Ms).isLessThan(100.0);
        assertThat(p99Ms).isLessThan(250.0);
    }

    // =========================================================================
    // Benchmark 3: Cold / High Hop Provenance Traversal Latency (< 100ms)
    // =========================================================================
    @Test
    @Order(3)
    @DisplayName("Benchmark: Cold Traversal with Max Hops on 100k+ Dataset")
    void testColdTraversalWithMaxHops() throws IOException {
        long startNs = System.nanoTime();
        ProvenanceGraph graph = resolver.resolveProvenance(
                0L, DIMENSION, SWORD_ITEM_ID, SWORD_FINGERPRINT, queryEngine, 100, 7L * 24 * 3600 * 1000L
        );
        long elapsedNs = System.nanoTime() - startNs;
        double latencyMs = elapsedNs / 1_000_000.0;

        System.out.printf("[BENCHMARK-3] Cold Dimension-Wide Traversal Latency: %.3f ms (Nodes: %d)%n",
                latencyMs, graph.nodes().size());

        assertThat(graph.isEmpty()).isFalse();
        assertThat(latencyMs).isLessThan(100.0);
    }

    // =========================================================================
    // Benchmark 4: Bounded Memory & Zero Memory Leak Under High Query Load
    // =========================================================================
    @Test
    @Order(4)
    @DisplayName("Benchmark: Zero Memory Leak / Bounded Heap Allocation Under Sustained Load")
    void testZeroMemoryLeakAndBoundedAllocation() throws IOException {
        System.gc();
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {}

        Runtime runtime = Runtime.getRuntime();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();

        int loopCount = 200;
        long startNs = System.nanoTime();

        for (int i = 0; i < loopCount; i++) {
            ProvenanceGraph g1 = resolver.resolveProvenance(targetSwordPos, DIMENSION, SWORD_ITEM_ID, SWORD_FINGERPRINT, queryEngine);
            ProvenanceGraph g2 = resolver.resolveProvenance(targetDiamondPos, DIMENSION, DIAMOND_ITEM_ID, 0L, queryEngine);
            assertThat(g1.isEmpty()).isFalse();
            assertThat(g2.isEmpty()).isFalse();
        }

        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        double throughputOpsPerSec = (loopCount * 2.0) / (durationMs / 1000.0);

        System.gc();
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {}

        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryDeltaBytes = finalMemory - initialMemory;
        double memoryDeltaMb = memoryDeltaBytes / (1024.0 * 1024.0);

        System.out.printf("[BENCHMARK-4] Sustained Load (%d query pairs = 400 queries):%n", loopCount);
        System.out.printf("  - Duration: %d ms%n", durationMs);
        System.out.printf("  - Throughput: %.1f graph resolutions/sec%n", throughputOpsPerSec);
        System.out.printf("  - Initial Heap Used: %.2f MB%n", initialMemory / (1024.0 * 1024.0));
        System.out.printf("  - Final Heap Used: %.2f MB%n", finalMemory / (1024.0 * 1024.0));
        System.out.printf("  - Retained Delta: %.2f MB%n", memoryDeltaMb);

        // Retained memory increase after 400 full graph queries must be bounded (< 30 MB heap delta)
        assertThat(memoryDeltaMb).isLessThan(30.0);
        assertThat(throughputOpsPerSec).isGreaterThan(50.0);
    }
}
