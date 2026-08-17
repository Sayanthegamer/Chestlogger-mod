package com.chestlogger.provenance;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProvenanceGraphTest {

    @Test
    @DisplayName("Should enforce non-null invariants on ProvenanceNode")
    void testProvenanceNodeValidation() {
        UUID actor = UUID.randomUUID();
        long pos = BlockPosUtil.pack(10, 64, -20);

        assertThatThrownBy(() -> new ProvenanceNode(0, 1L, 1000L, null, ActorType.PLAYER, actor, "Steve", "minecraft:overworld", pos, "minecraft:diamond", 1, 0L, ConfidenceLevel.HIGH_CONFIDENCE, "test"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new ProvenanceNode(0, 1L, 1000L, ActionType.PLACE, null, actor, "Steve", "minecraft:overworld", pos, "minecraft:diamond", 1, 0L, ConfidenceLevel.HIGH_CONFIDENCE, "test"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new ProvenanceNode(0, 1L, 1000L, ActionType.PLACE, ActorType.PLAYER, actor, "Steve", null, pos, "minecraft:diamond", 1, 0L, ConfidenceLevel.HIGH_CONFIDENCE, "test"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new ProvenanceNode(0, 1L, 1000L, ActionType.PLACE, ActorType.PLAYER, actor, "Steve", "minecraft:overworld", pos, null, 1, 0L, ConfidenceLevel.HIGH_CONFIDENCE, "test"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new ProvenanceNode(0, 1L, 1000L, ActionType.PLACE, ActorType.PLAYER, actor, "Steve", "minecraft:overworld", pos, "minecraft:diamond", 1, 0L, null, "test"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should supply default actorName and notes when null in ProvenanceNode")
    void testProvenanceNodeDefaults() {
        long pos = BlockPosUtil.pack(0, 64, 0);

        ProvenanceNode node1 = new ProvenanceNode(
                0, 1L, 1000L, ActionType.PICKUP, ActorType.PLAYER, null, null, "minecraft:overworld", pos, "minecraft:diamond", -1, 0L, ConfidenceLevel.HIGH_CONFIDENCE, null
        );
        assertThat(node1.actorName()).isEqualTo("Unknown Player");
        assertThat(node1.notes()).isEmpty();

        ProvenanceNode node2 = new ProvenanceNode(
                1, 2L, 2000L, ActionType.HOPPER_EXTRACT, ActorType.HOPPER_BLOCK, null, null, "minecraft:overworld", pos, "minecraft:diamond", -1, 0L, ConfidenceLevel.HIGH_CONFIDENCE, null
        );
        assertThat(node2.actorName()).isEqualTo("HOPPER_BLOCK");
    }

    @Test
    @DisplayName("Should correctly calculate edge time delta and enforce non-null invariants")
    void testProvenanceEdgeValidation() {
        long pos = BlockPosUtil.pack(10, 64, 10);
        ProvenanceNode node1 = new ProvenanceNode(
                0, 1L, 1000L, ActionType.PICKUP, ActorType.PLAYER, UUID.randomUUID(), "Alex", "minecraft:overworld", pos, "minecraft:diamond", -1, 0L, ConfidenceLevel.EXACT_LINKAGE, ""
        );
        ProvenanceNode node2 = new ProvenanceNode(
                1, 2L, 2500L, ActionType.PLACE, ActorType.PLAYER, node1.actorUuid(), "Alex", "minecraft:overworld", pos, "minecraft:diamond", 1, 0L, ConfidenceLevel.EXACT_LINKAGE, ""
        );

        ProvenanceEdge edge = ProvenanceEdge.between(node1, node2, "DIRECT_CUSTODY", ConfidenceLevel.EXACT_LINKAGE);
        assertThat(edge.from()).isEqualTo(node1);
        assertThat(edge.to()).isEqualTo(node2);
        assertThat(edge.timeDeltaMs()).isEqualTo(1500L);
        assertThat(edge.confidence()).isEqualTo(ConfidenceLevel.EXACT_LINKAGE);
        assertThat(edge.transitionType()).isEqualTo("DIRECT_CUSTODY");

        assertThatThrownBy(() -> new ProvenanceEdge(null, node2, 1000L, ConfidenceLevel.HIGH_CONFIDENCE, "DIRECT_CUSTODY"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProvenanceEdge(node1, null, 1000L, ConfidenceLevel.HIGH_CONFIDENCE, "DIRECT_CUSTODY"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProvenanceEdge(node1, node2, 1000L, null, "DIRECT_CUSTODY"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProvenanceEdge(node1, node2, 1000L, ConfidenceLevel.HIGH_CONFIDENCE, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should combine confidence levels properly (weakest link principle)")
    void testConfidenceLevelCombining() {
        assertThat(ConfidenceLevel.EXACT_LINKAGE.combine(ConfidenceLevel.EXACT_LINKAGE)).isEqualTo(ConfidenceLevel.EXACT_LINKAGE);
        assertThat(ConfidenceLevel.EXACT_LINKAGE.combine(ConfidenceLevel.HIGH_CONFIDENCE)).isEqualTo(ConfidenceLevel.HIGH_CONFIDENCE);
        assertThat(ConfidenceLevel.EXACT_LINKAGE.combine(ConfidenceLevel.PROBABLE)).isEqualTo(ConfidenceLevel.PROBABLE);

        assertThat(ConfidenceLevel.HIGH_CONFIDENCE.combine(ConfidenceLevel.EXACT_LINKAGE)).isEqualTo(ConfidenceLevel.HIGH_CONFIDENCE);
        assertThat(ConfidenceLevel.HIGH_CONFIDENCE.combine(ConfidenceLevel.HIGH_CONFIDENCE)).isEqualTo(ConfidenceLevel.HIGH_CONFIDENCE);
        assertThat(ConfidenceLevel.HIGH_CONFIDENCE.combine(ConfidenceLevel.PROBABLE)).isEqualTo(ConfidenceLevel.PROBABLE);

        assertThat(ConfidenceLevel.PROBABLE.combine(ConfidenceLevel.EXACT_LINKAGE)).isEqualTo(ConfidenceLevel.PROBABLE);
        assertThat(ConfidenceLevel.PROBABLE.combine(null)).isEqualTo(ConfidenceLevel.PROBABLE);
    }

    @Test
    @DisplayName("Should enforce immutability on ProvenanceGraph nodes and edges")
    void testProvenanceGraphImmutability() {
        long pos = BlockPosUtil.pack(5, 64, 5);
        ProvenanceNode node1 = new ProvenanceNode(
                0, 1L, 1000L, ActionType.PICKUP, ActorType.PLAYER, UUID.randomUUID(), "Alex", "minecraft:overworld", pos, "minecraft:diamond", -1, 0L, ConfidenceLevel.EXACT_LINKAGE, ""
        );
        ProvenanceNode node2 = new ProvenanceNode(
                1, 2L, 2000L, ActionType.PLACE, ActorType.PLAYER, node1.actorUuid(), "Alex", "minecraft:overworld", pos, "minecraft:diamond", 1, 0L, ConfidenceLevel.EXACT_LINKAGE, ""
        );
        ProvenanceEdge edge = ProvenanceEdge.between(node1, node2, "DIRECT_CUSTODY", ConfidenceLevel.EXACT_LINKAGE);

        List<ProvenanceNode> mutableNodes = new ArrayList<>(List.of(node1, node2));
        List<ProvenanceEdge> mutableEdges = new ArrayList<>(List.of(edge));

        ProvenanceGraph graph = new ProvenanceGraph("minecraft:diamond", pos, mutableNodes, mutableEdges, 2, ConfidenceLevel.EXACT_LINKAGE);

        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.edges()).hasSize(1);
        assertThat(graph.rootNode()).isEqualTo(node1);
        assertThat(graph.terminalNode()).isEqualTo(node2);
        assertThat(graph.isEmpty()).isFalse();

        // Modifying source lists must not affect the graph
        mutableNodes.clear();
        mutableEdges.clear();
        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.edges()).hasSize(1);

        // Returned lists must be unmodifiable
        assertThatThrownBy(() -> graph.nodes().add(node1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> graph.edges().add(edge)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should handle empty ProvenanceGraph")
    void testEmptyGraph() {
        ProvenanceGraph empty = ProvenanceGraph.empty("minecraft:netherite_sword", 0L);
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.nodes()).isEmpty();
        assertThat(empty.edges()).isEmpty();
        assertThat(empty.totalSteps()).isEqualTo(0);
        assertThat(empty.rootNode()).isNull();
        assertThat(empty.terminalNode()).isNull();
        assertThat(empty.overallConfidence()).isEqualTo(ConfidenceLevel.EXACT_LINKAGE);
    }
}
