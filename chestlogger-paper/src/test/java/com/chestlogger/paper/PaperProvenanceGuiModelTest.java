package com.chestlogger.paper;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.provenance.ConfidenceLevel;
import com.chestlogger.provenance.ProvenanceEdge;
import com.chestlogger.provenance.ProvenanceGraph;
import com.chestlogger.provenance.ProvenanceNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaperProvenanceGuiModelTest {

    @Test
    @DisplayName("Calculate total pages accurately for 45-item provenance pages")
    void testTotalPagesCalculation() {
        assertThat(PaperProvenanceGuiModel.calculateTotalPages(0)).isEqualTo(1);
        assertThat(PaperProvenanceGuiModel.calculateTotalPages(1)).isEqualTo(1);
        assertThat(PaperProvenanceGuiModel.calculateTotalPages(45)).isEqualTo(1);
        assertThat(PaperProvenanceGuiModel.calculateTotalPages(46)).isEqualTo(2);
        assertThat(PaperProvenanceGuiModel.calculateTotalPages(90)).isEqualTo(2);
        assertThat(PaperProvenanceGuiModel.calculateTotalPages(91)).isEqualTo(3);
    }

    @Test
    @DisplayName("Page slicing extracts correct 45-item sublist for given page")
    void testPageSlicing() {
        List<ProvenanceNode> nodes = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            nodes.add(createSampleNode(i, "minecraft:netherite_sword", -1, ConfidenceLevel.EXACT_LINKAGE, "Note " + i));
        }

        List<ProvenanceNode> page1 = PaperProvenanceGuiModel.getPageItems(nodes, 1, 45);
        assertThat(page1).hasSize(45);
        assertThat(page1.get(0).stepIndex()).isEqualTo(0);
        assertThat(page1.get(44).stepIndex()).isEqualTo(44);

        List<ProvenanceNode> page2 = PaperProvenanceGuiModel.getPageItems(nodes, 2, 45);
        assertThat(page2).hasSize(45);
        assertThat(page2.get(0).stepIndex()).isEqualTo(45);

        List<ProvenanceNode> page3 = PaperProvenanceGuiModel.getPageItems(nodes, 3, 45);
        assertThat(page3).hasSize(10);
        assertThat(page3.get(0).stepIndex()).isEqualTo(90);

        // Edge cases
        assertThat(PaperProvenanceGuiModel.getPageItems(nodes, 4, 45)).isEmpty();
        assertThat(PaperProvenanceGuiModel.getPageItems(nodes, 0, 45)).hasSize(45);
        assertThat(PaperProvenanceGuiModel.getPageItems(List.of(), 1, 45)).isEmpty();
        assertThat(PaperProvenanceGuiModel.getPageItems(null, 1, 45)).isEmpty();
    }

    @Test
    @DisplayName("Material mapping resolves known item IDs and falls back cleanly")
    void testMaterialMapping() {
        assertThat(PaperProvenanceGuiModel.resolveMaterial("minecraft:diamond")).isEqualTo(org.bukkit.Material.DIAMOND);
        assertThat(PaperProvenanceGuiModel.resolveMaterial("minecraft:netherite_sword")).isEqualTo(org.bukkit.Material.NETHERITE_SWORD);
        assertThat(PaperProvenanceGuiModel.resolveMaterial("minecraft:chest")).isEqualTo(org.bukkit.Material.CHEST);
        assertThat(PaperProvenanceGuiModel.resolveMaterial("minecraft:non_existent_item_xyz")).isEqualTo(org.bukkit.Material.BARREL);
        assertThat(PaperProvenanceGuiModel.resolveMaterial(null)).isEqualTo(org.bukkit.Material.BARREL);
        assertThat(PaperProvenanceGuiModel.resolveMaterial("")).isEqualTo(org.bukkit.Material.BARREL);
    }

    @Test
    @DisplayName("Confidence badges render correct color codes and brackets")
    void testConfidenceBadgeColors() {
        assertThat(PaperProvenanceGuiModel.formatConfidenceBadge(ConfidenceLevel.EXACT_LINKAGE))
                .isEqualTo("§a[EXACT_LINKAGE]");
        assertThat(PaperProvenanceGuiModel.formatConfidenceBadge(ConfidenceLevel.HIGH_CONFIDENCE))
                .isEqualTo("§e[HIGH_CONFIDENCE]");
        assertThat(PaperProvenanceGuiModel.formatConfidenceBadge(ConfidenceLevel.PROBABLE))
                .isEqualTo("§6[PROBABLE]");
        assertThat(PaperProvenanceGuiModel.formatConfidenceBadge(null))
                .isEqualTo("§7[UNKNOWN]");
    }

    @Test
    @DisplayName("Format lore generates step number, action, actor, delta, time, and coordinates")
    void testLoreFormatting() {
        ProvenanceNode node = createSampleNode(0, "minecraft:diamond", -5, ConfidenceLevel.EXACT_LINKAGE, "Stolen from chest");
        List<String> lore = PaperProvenanceGuiModel.buildNodeLore(node);

        assertThat(lore).anyMatch(line -> line.contains("Step:") && line.contains("#1"));
        assertThat(lore).anyMatch(line -> line.contains("Action:") && line.contains("PICKUP"));
        assertThat(lore).anyMatch(line -> line.contains("Confidence:") && line.contains("§a[EXACT_LINKAGE]"));
        assertThat(lore).anyMatch(line -> line.contains("Actor:") && line.contains("Alex"));
        assertThat(lore).anyMatch(line -> line.contains("Delta:") && line.contains("-5"));
        assertThat(lore).anyMatch(line -> line.contains("Pos:"));
        assertThat(lore).anyMatch(line -> line.contains("Time:"));
        assertThat(lore).anyMatch(line -> line.contains("Stolen from chest"));
    }

    @Test
    @DisplayName("Format lore handles positive delta and blank notes correctly")
    void testPositiveDeltaLoreFormatting() {
        ProvenanceNode node = createSampleNode(1, "minecraft:iron_ingot", 10, ConfidenceLevel.HIGH_CONFIDENCE, "");
        List<String> lore = PaperProvenanceGuiModel.buildNodeLore(node);

        assertThat(lore).anyMatch(line -> line.contains("Step:") && line.contains("#2"));
        assertThat(lore).anyMatch(line -> line.contains("Delta:") && line.contains("+10"));
        assertThat(lore).noneMatch(line -> line.startsWith("§8"));
    }

    @Test
    @DisplayName("Summary lore reflects total steps, item id, and overall confidence")
    void testSummaryItemLore() {
        ProvenanceNode node1 = createSampleNode(0, "minecraft:diamond", -1, ConfidenceLevel.EXACT_LINKAGE, "First");
        ProvenanceNode node2 = createSampleNode(1, "minecraft:diamond", 1, ConfidenceLevel.HIGH_CONFIDENCE, "Second");
        ProvenanceEdge edge = new ProvenanceEdge(node1, node2, 1000L, ConfidenceLevel.HIGH_CONFIDENCE, "CONTAINER_HANDOFF");

        ProvenanceGraph graph = new ProvenanceGraph(
                "minecraft:diamond",
                123456789L,
                List.of(node1, node2),
                List.of(edge),
                2,
                ConfidenceLevel.HIGH_CONFIDENCE
        );

        List<String> lore = PaperProvenanceGuiModel.buildSummaryLore(graph);
        assertThat(lore).isNotNull();
        assertThat(lore).anyMatch(line -> line.contains("minecraft:diamond"));
        assertThat(lore).anyMatch(line -> line.contains("Total Steps: §a2"));
        assertThat(lore).anyMatch(line -> line.contains("§e[HIGH_CONFIDENCE]"));
        assertThat(lore).anyMatch(line -> line.contains("Target Pos:"));
        assertThat(lore).anyMatch(line -> line.contains("First Recorded:"));
        assertThat(lore).anyMatch(line -> line.contains("Last Recorded:"));
    }

    @Test
    @DisplayName("Summary lore on empty graph renders clean zero step info")
    void testEmptySummaryItemLore() {
        ProvenanceGraph emptyGraph = ProvenanceGraph.empty("minecraft:stick", 0L);
        List<String> lore = PaperProvenanceGuiModel.buildSummaryLore(emptyGraph);

        assertThat(lore).anyMatch(line -> line.contains("minecraft:stick"));
        assertThat(lore).anyMatch(line -> line.contains("Total Steps: §a0"));
    }

    private static ProvenanceNode createSampleNode(int step, String item, int delta, ConfidenceLevel confidence, String notes) {
        return new ProvenanceNode(
                step,
                (long) step,
                System.currentTimeMillis() - (step * 60000L),
                delta < 0 ? ActionType.PICKUP : ActionType.PLACE,
                ActorType.PLAYER,
                UUID.randomUUID(),
                "Alex",
                "minecraft:overworld",
                123456789L,
                item,
                delta,
                0L,
                confidence,
                notes
        );
    }
}
