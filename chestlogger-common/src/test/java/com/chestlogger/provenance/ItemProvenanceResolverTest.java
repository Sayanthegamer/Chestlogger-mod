package com.chestlogger.provenance;

import com.chestlogger.event.*;
import com.chestlogger.index.IndexPointer;
import com.chestlogger.index.PersistentIndexManager;
import com.chestlogger.query.QueryEngine;
import com.chestlogger.storage.LZ4BlockCompressor;
import com.chestlogger.storage.LogSegmentWriter;
import com.chestlogger.storage.StorageProfile;
import com.chestlogger.storage.StringTableDictionary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ItemProvenanceResolverTest {

    private QueryEngine createQueryEngine(File dataDir, List<TransactionLogEntry> entries) throws IOException {
        StringTableDictionary dict = new StringTableDictionary();
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        StorageProfile profile = StorageProfile.BALANCED;
        PersistentIndexManager indexManager = new PersistentIndexManager(dataDir);

        if (!entries.isEmpty()) {
            try (LogSegmentWriter writer = new LogSegmentWriter(dataDir, "chestlog", 0, entries.get(0).sequenceId(), compressor, profile, dict)) {
                writer.writeBatch(entries);
            }

            for (int i = 0; i < entries.size(); i++) {
                TransactionLogEntry e = entries.get(i);
                for (SlotDelta d : e.deltas()) {
                    indexManager.index(new IndexPointer(
                            e.sequenceId(),
                            e.timestampMs(),
                            e.actorUuid(),
                            d.itemId(),
                            e.dimension(),
                            e.packedBlockPos(),
                            0,
                            32L,
                            i
                    ));
                }
            }
        }

        return new QueryEngine(dataDir, compressor, indexManager);
    }

    @Test
    @DisplayName("Should resolve non-fungible gear provenance matching 64-bit component metadata fingerprint")
    void testNonFungibleGearProvenance(@TempDir Path tempDir) throws IOException {
        long fingerprint = 0xABCD1234EFA56789L;
        String itemId = "minecraft:elytra";
        String dim = "minecraft:overworld";

        UUID playerAlex = UUID.randomUUID();
        UUID playerBob = UUID.randomUUID();

        long chest1Pos = BlockPosUtil.pack(100, 64, 100);
        long chest2Pos = BlockPosUtil.pack(200, 64, 200);
        long chest3Pos = BlockPosUtil.pack(300, 64, 300);

        long t0 = 10_000L;
        long t1 = t0 + 50_000L; // 50s later
        long t2 = t1 + 100_000L; // 100s later
        long t3 = t2 + 60_000L; // 60s later

        // Step 1: Alex crafts/deposits Elytra into Chest 1
        TransactionLogEntry e1 = new TransactionLogEntry(
                1L, t0, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                playerAlex, "Alex", dim, chest1Pos,
                List.of(new SlotDelta(0, itemId, 1, 0, 1, fingerprint))
        );

        // Step 2: Alex takes Elytra from Chest 1
        TransactionLogEntry e2 = new TransactionLogEntry(
                2L, t1, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER,
                playerAlex, "Alex", dim, chest1Pos,
                List.of(new SlotDelta(0, itemId, -1, 1, 0, fingerprint))
        );

        // Step 3: Alex places Elytra into Chest 2
        TransactionLogEntry e3 = new TransactionLogEntry(
                3L, t2, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                playerAlex, "Alex", dim, chest2Pos,
                List.of(new SlotDelta(0, itemId, 1, 0, 1, fingerprint))
        );

        // Step 4: Bob steals Elytra from Chest 2 and moves to Chest 3
        TransactionLogEntry e4 = new TransactionLogEntry(
                4L, t3, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER,
                playerBob, "Bob", dim, chest2Pos,
                List.of(new SlotDelta(0, itemId, -1, 1, 0, fingerprint))
        );

        QueryEngine engine = createQueryEngine(tempDir.toFile(), List.of(e1, e2, e3, e4));
        ItemProvenanceResolver resolver = new ItemProvenanceResolver();

        ProvenanceGraph graph = resolver.resolveProvenance(chest2Pos, dim, itemId, fingerprint, engine);

        assertThat(graph.isEmpty()).isFalse();
        assertThat(graph.nodes()).hasSize(4);
        assertThat(graph.edges()).hasSize(3);
        assertThat(graph.overallConfidence()).isEqualTo(ConfidenceLevel.EXACT_LINKAGE);

        // Verify Node sequence
        assertThat(graph.nodes().get(0).sequenceId()).isEqualTo(1L);
        assertThat(graph.nodes().get(0).actorName()).isEqualTo("Alex");
        assertThat(graph.nodes().get(0).deltaQuantity()).isEqualTo(1);
        assertThat(graph.nodes().get(0).confidence()).isEqualTo(ConfidenceLevel.EXACT_LINKAGE);

        assertThat(graph.nodes().get(1).sequenceId()).isEqualTo(2L);
        assertThat(graph.nodes().get(1).deltaQuantity()).isEqualTo(-1);

        assertThat(graph.nodes().get(2).sequenceId()).isEqualTo(3L);
        assertThat(graph.nodes().get(2).packedPos()).isEqualTo(chest2Pos);

        assertThat(graph.nodes().get(3).sequenceId()).isEqualTo(4L);
        assertThat(graph.nodes().get(3).actorName()).isEqualTo("Bob");

        // Verify Edges
        ProvenanceEdge edge0 = graph.edges().get(0);
        assertThat(edge0.transitionType()).isEqualTo("CONTAINER_HANDOFF");
        assertThat(edge0.confidence()).isEqualTo(ConfidenceLevel.EXACT_LINKAGE);

        ProvenanceEdge edge1 = graph.edges().get(1);
        assertThat(edge1.transitionType()).isEqualTo("DIRECT_CUSTODY");
        assertThat(edge1.confidence()).isEqualTo(ConfidenceLevel.EXACT_LINKAGE);

        ProvenanceEdge edge2 = graph.edges().get(2);
        assertThat(edge2.transitionType()).isEqualTo("CONTAINER_HANDOFF");
        assertThat(edge2.confidence()).isEqualTo(ConfidenceLevel.EXACT_LINKAGE);
    }

    @Test
    @DisplayName("Should resolve commodity temporal flow with high confidence on direct custody")
    void testCommodityTemporalFlowDirectCustody(@TempDir Path tempDir) throws IOException {
        String itemId = "minecraft:diamond";
        String dim = "minecraft:overworld";

        UUID playerAlex = UUID.randomUUID();
        UUID playerBob = UUID.randomUUID();

        long chest1Pos = BlockPosUtil.pack(10, 64, 10);
        long chest2Pos = BlockPosUtil.pack(20, 64, 20);
        long chest3Pos = BlockPosUtil.pack(30, 64, 30);

        long t0 = 100_000L;
        long t1 = t0 + 60_000L;  // +1 min (Alex takes from chest 1 and puts in chest 2)
        long t2 = t1 + 30_000L;  // +30s (Bob takes from chest 2)
        long t3 = t2 + 45_000L;  // +45s (Bob puts into chest 3)

        // Event 1: Alex extracts 64 diamonds from Chest 1
        TransactionLogEntry e1 = new TransactionLogEntry(
                1L, t0, UUID.randomUUID(), ActionType.SHIFT_CLICK_EXTRACT, ActorType.PLAYER,
                playerAlex, "Alex", dim, chest1Pos,
                List.of(new SlotDelta(0, itemId, -64, 64, 0, 0L))
        );

        // Event 2: Alex deposits 64 diamonds into Chest 2
        TransactionLogEntry e2 = new TransactionLogEntry(
                2L, t1, UUID.randomUUID(), ActionType.SHIFT_CLICK_INSERT, ActorType.PLAYER,
                playerAlex, "Alex", dim, chest2Pos,
                List.of(new SlotDelta(0, itemId, 64, 0, 64, 0L))
        );

        // Event 3: Bob extracts 64 diamonds from Chest 2
        TransactionLogEntry e3 = new TransactionLogEntry(
                3L, t2, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER,
                playerBob, "Bob", dim, chest2Pos,
                List.of(new SlotDelta(0, itemId, -64, 64, 0, 0L))
        );

        // Event 4: Bob deposits 64 diamonds into Chest 3
        TransactionLogEntry e4 = new TransactionLogEntry(
                4L, t3, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                playerBob, "Bob", dim, chest3Pos,
                List.of(new SlotDelta(0, itemId, 64, 0, 64, 0L))
        );

        QueryEngine engine = createQueryEngine(tempDir.toFile(), List.of(e1, e2, e3, e4));
        ItemProvenanceResolver resolver = new ItemProvenanceResolver();

        // Trace starting from Chest 3
        ProvenanceGraph graph = resolver.resolveProvenance(chest3Pos, dim, itemId, 0L, engine, 50, 604_800_000L);

        assertThat(graph.isEmpty()).isFalse();
        assertThat(graph.nodes()).hasSize(4);
        assertThat(graph.edges()).hasSize(3);

        // Check custody transitions
        assertThat(graph.edges().get(0).transitionType()).isEqualTo("DIRECT_CUSTODY");
        assertThat(graph.edges().get(0).confidence()).isEqualTo(ConfidenceLevel.HIGH_CONFIDENCE);

        assertThat(graph.edges().get(1).transitionType()).isEqualTo("CONTAINER_HANDOFF");
        assertThat(graph.edges().get(1).confidence()).isEqualTo(ConfidenceLevel.HIGH_CONFIDENCE);

        assertThat(graph.edges().get(2).transitionType()).isEqualTo("DIRECT_CUSTODY");
        assertThat(graph.edges().get(2).confidence()).isEqualTo(ConfidenceLevel.HIGH_CONFIDENCE);

        assertThat(graph.overallConfidence()).isEqualTo(ConfidenceLevel.HIGH_CONFIDENCE);
    }

    @Test
    @DisplayName("Should assign PROBABLE confidence when temporal gap exceeds tight window or quantities differ")
    void testProbableConfidenceScoring(@TempDir Path tempDir) throws IOException {
        String itemId = "minecraft:iron_ingot";
        String dim = "minecraft:overworld";

        UUID playerAlex = UUID.randomUUID();
        long chest1Pos = BlockPosUtil.pack(10, 64, 10);
        long chest2Pos = BlockPosUtil.pack(20, 64, 20);

        long t0 = 100_000L;
        long t1 = t0 + 600_000L; // 10 minutes later (> 5 min tight window)

        // Alex extracts 64 ingots from Chest 1
        TransactionLogEntry e1 = new TransactionLogEntry(
                1L, t0, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER,
                playerAlex, "Alex", dim, chest1Pos,
                List.of(new SlotDelta(0, itemId, -64, 64, 0, 0L))
        );

        // Alex deposits only 16 ingots into Chest 2 10 minutes later (quantity mismatch + time delay)
        TransactionLogEntry e2 = new TransactionLogEntry(
                2L, t1, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                playerAlex, "Alex", dim, chest2Pos,
                List.of(new SlotDelta(0, itemId, 16, 0, 16, 0L))
        );

        QueryEngine engine = createQueryEngine(tempDir.toFile(), List.of(e1, e2));
        ItemProvenanceResolver resolver = new ItemProvenanceResolver();

        ProvenanceGraph graph = resolver.resolveProvenance(chest2Pos, dim, itemId, 0L, engine);

        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.edges()).hasSize(1);
        assertThat(graph.edges().get(0).confidence()).isEqualTo(ConfidenceLevel.PROBABLE);
        assertThat(graph.overallConfidence()).isEqualTo(ConfidenceLevel.PROBABLE);
    }

    @Test
    @DisplayName("Should prevent infinite loops in cyclic Golem -> Chest A -> Player -> Chest B transfers")
    void testCycleSafetySafeguard(@TempDir Path tempDir) throws IOException {
        String itemId = "minecraft:copper_ingot";
        String dim = "minecraft:overworld";

        UUID playerAlex = UUID.randomUUID();
        UUID golemUuid = UUID.randomUUID();

        long chestAPos = BlockPosUtil.pack(100, 64, 100);
        long chestBPos = BlockPosUtil.pack(200, 64, 200);

        List<TransactionLogEntry> entries = new ArrayList<>();
        long time = 1000L;
        long seq = 1L;

        // Simulate 20 continuous cycles between Chest A and Chest B
        for (int cycle = 0; cycle < 20; cycle++) {
            // Golem extracts from Chest B
            entries.add(new TransactionLogEntry(
                    seq++, time, UUID.randomUUID(), ActionType.PICKUP, ActorType.AUTOMATION,
                    golemUuid, "Iron Golem", dim, chestBPos,
                    List.of(new SlotDelta(0, itemId, -16, 16, 0, 0L))
            ));
            time += 1000L;

            // Golem deposits into Chest A
            entries.add(new TransactionLogEntry(
                    seq++, time, UUID.randomUUID(), ActionType.PLACE, ActorType.AUTOMATION,
                    golemUuid, "Iron Golem", dim, chestAPos,
                    List.of(new SlotDelta(0, itemId, 16, 0, 16, 0L))
            ));
            time += 1000L;

            // Player extracts from Chest A
            entries.add(new TransactionLogEntry(
                    seq++, time, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER,
                    playerAlex, "Alex", dim, chestAPos,
                    List.of(new SlotDelta(0, itemId, -16, 16, 0, 0L))
            ));
            time += 1000L;

            // Player deposits into Chest B
            entries.add(new TransactionLogEntry(
                    seq++, time, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                    playerAlex, "Alex", dim, chestBPos,
                    List.of(new SlotDelta(0, itemId, 16, 0, 16, 0L))
            ));
            time += 1000L;
        }

        QueryEngine engine = createQueryEngine(tempDir.toFile(), entries);
        ItemProvenanceResolver resolver = new ItemProvenanceResolver();

        // Max hops set to 10
        ProvenanceGraph graph = resolver.resolveProvenance(chestAPos, dim, itemId, 0L, engine, 10, 604_800_000L);

        // Traversal MUST terminate safely and not exceed maxHops
        assertThat(graph.nodes().size()).isLessThanOrEqualTo(10);
        assertThat(graph.edges().size()).isLessThanOrEqualTo(9);
        assertThat(graph.isEmpty()).isFalse();

        // Check transition types
        boolean hasGolemTransfer = graph.edges().stream().anyMatch(e -> e.transitionType().equals("GOLEM_TRANSFER"));
        boolean hasDirectCustody = graph.edges().stream().anyMatch(e -> e.transitionType().equals("DIRECT_CUSTODY"));
        assertThat(hasGolemTransfer || hasDirectCustody).isTrue();
    }

    @Test
    @DisplayName("Should recognize Automation and Hopper transitions")
    void testHopperAndAutomationTransitions(@TempDir Path tempDir) throws IOException {
        String itemId = "minecraft:redstone";
        String dim = "minecraft:overworld";

        long hopperPos = BlockPosUtil.pack(50, 60, 50);
        long chestPos = BlockPosUtil.pack(50, 59, 50);

        long t0 = 5000L;
        long t1 = t0 + 2000L;

        TransactionLogEntry e1 = new TransactionLogEntry(
                1L, t0, UUID.randomUUID(), ActionType.HOPPER_EXTRACT, ActorType.HOPPER_BLOCK,
                null, "Hopper", dim, hopperPos,
                List.of(new SlotDelta(0, itemId, -5, 5, 0, 0L))
        );

        TransactionLogEntry e2 = new TransactionLogEntry(
                2L, t1, UUID.randomUUID(), ActionType.HOPPER_INSERT, ActorType.HOPPER_BLOCK,
                null, "Hopper", dim, chestPos,
                List.of(new SlotDelta(0, itemId, 5, 0, 5, 0L))
        );

        QueryEngine engine = createQueryEngine(tempDir.toFile(), List.of(e1, e2));
        ItemProvenanceResolver resolver = new ItemProvenanceResolver();

        ProvenanceGraph graph = resolver.resolveProvenance(chestPos, dim, itemId, 0L, engine);

        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.edges()).hasSize(1);
        assertThat(graph.edges().get(0).transitionType()).isEqualTo("AUTOMATION_TRANSFER");
        assertThat(graph.edges().get(0).confidence()).isEqualTo(ConfidenceLevel.HIGH_CONFIDENCE);
    }

    @Test
    @DisplayName("Should return empty graph when no matching items exist")
    void testEmptyResultWhenItemNotFound(@TempDir Path tempDir) throws IOException {
        QueryEngine engine = createQueryEngine(tempDir.toFile(), List.of());
        ItemProvenanceResolver resolver = new ItemProvenanceResolver();

        ProvenanceGraph graph = resolver.resolveProvenance(BlockPosUtil.pack(0, 64, 0), "minecraft:overworld", "minecraft:beacon", 0L, engine);

        assertThat(graph.isEmpty()).isTrue();
        assertThat(graph.nodes()).isEmpty();
        assertThat(graph.edges()).isEmpty();
    }
}
