package com.chestlogger.paper;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaperHistoryGuiModelTest {

    @Test
    @DisplayName("Calculate total pages accurately for 45-item pages")
    void testTotalPagesCalculation() {
        assertThat(PaperHistoryGuiModel.calculateTotalPages(0)).isEqualTo(1);
        assertThat(PaperHistoryGuiModel.calculateTotalPages(1)).isEqualTo(1);
        assertThat(PaperHistoryGuiModel.calculateTotalPages(45)).isEqualTo(1);
        assertThat(PaperHistoryGuiModel.calculateTotalPages(46)).isEqualTo(2);
        assertThat(PaperHistoryGuiModel.calculateTotalPages(90)).isEqualTo(2);
        assertThat(PaperHistoryGuiModel.calculateTotalPages(91)).isEqualTo(3);
    }

    @Test
    @DisplayName("Page slicing extracts correct 45-item sublist for given page")
    void testPageSlicing() {
        List<TransactionLogEntry> entries = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            entries.add(createSampleEntry(i, "minecraft:diamond", -1));
        }

        List<TransactionLogEntry> page1 = PaperHistoryGuiModel.getPageItems(entries, 1, 45);
        assertThat(page1).hasSize(45);
        assertThat(page1.get(0).sequenceId()).isEqualTo(0);
        assertThat(page1.get(44).sequenceId()).isEqualTo(44);

        List<TransactionLogEntry> page2 = PaperHistoryGuiModel.getPageItems(entries, 2, 45);
        assertThat(page2).hasSize(45);
        assertThat(page2.get(0).sequenceId()).isEqualTo(45);

        List<TransactionLogEntry> page3 = PaperHistoryGuiModel.getPageItems(entries, 3, 45);
        assertThat(page3).hasSize(10);
        assertThat(page3.get(0).sequenceId()).isEqualTo(90);
    }

    @Test
    @DisplayName("Material mapping resolves known item IDs and falls back cleanly")
    void testMaterialMapping() {
        assertThat(PaperHistoryGuiModel.resolveMaterial("minecraft:diamond")).isEqualTo(org.bukkit.Material.DIAMOND);
        assertThat(PaperHistoryGuiModel.resolveMaterial("minecraft:chest")).isEqualTo(org.bukkit.Material.CHEST);
        assertThat(PaperHistoryGuiModel.resolveMaterial("minecraft:netherite_ingot")).isEqualTo(org.bukkit.Material.NETHERITE_INGOT);
        assertThat(PaperHistoryGuiModel.resolveMaterial("minecraft:non_existent_item_xyz")).isEqualTo(org.bukkit.Material.BARREL);
    }

    @Test
    @DisplayName("Format lore generates action, delta, and double-chest indicators")
    void testLoreFormatting() {
        TransactionLogEntry entry = createSampleEntry(1, "minecraft:diamond", -5);
        List<String> lore = PaperHistoryGuiModel.buildItemLore(entry, 0, 54);

        assertThat(lore).anyMatch(line -> line.contains("Delta:") && line.contains("-5"));
        assertThat(lore).anyMatch(line -> line.contains("Left Half"));
    }

    private static TransactionLogEntry createSampleEntry(long seq, String item, int delta) {
        SlotDelta slotDelta = new SlotDelta(0, item, delta, delta < 0 ? -delta : 0, delta > 0 ? delta : 0, 0L);
        return new TransactionLogEntry(
                seq,
                System.currentTimeMillis() - 5000,
                UUID.randomUUID(),
                delta < 0 ? ActionType.PICKUP : ActionType.PLACE,
                ActorType.PLAYER,
                UUID.randomUUID(),
                "Alex",
                "minecraft:overworld",
                123456789L,
                List.of(slotDelta)
        );
    }
}
