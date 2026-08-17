package com.chestlogger.paper;

import com.chestlogger.inspect.InspectModeManager;
import com.chestlogger.inspect.WandConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaperWandInspectorTest {

    private InspectModeManager inspectModeManager;
    private final UUID playerUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        inspectModeManager = new InspectModeManager(new WandConfig(true, "minecraft:stick", true, true, 100));
    }

    @Test
    @DisplayName("Paper wand inspector should detect stick as valid wand item")
    void testIsWandItem() {
        assertThat(inspectModeManager.isWandItem("minecraft:stick")).isTrue();
        assertThat(inspectModeManager.isWandItem("stick")).isTrue();
        assertThat(inspectModeManager.isWandItem("minecraft:wooden_hoe")).isFalse();
    }

    @Test
    @DisplayName("Paper wand inspector should trigger inspect for toggled player with any item")
    void testToggledPlayerInspection() {
        inspectModeManager.setInspectActive(playerUuid, true);
        assertThat(inspectModeManager.shouldInspect(playerUuid, "minecraft:diamond")).isTrue();
        assertThat(inspectModeManager.shouldInspect(playerUuid, "minecraft:air")).isTrue();
    }

    @Test
    @DisplayName("Paper wand inspector should trigger inspect for un-toggled player holding wand")
    void testUntoggledPlayerHoldingWand() {
        assertThat(inspectModeManager.isInspectActive(playerUuid)).isFalse();
        assertThat(inspectModeManager.shouldInspect(playerUuid, "minecraft:stick")).isTrue();
        assertThat(inspectModeManager.shouldInspect(playerUuid, "minecraft:dirt")).isFalse();
    }

    @Test
    @DisplayName("Debounce logic prevents duplicate queries within window")
    void testDebounceWindow() {
        long packed = 100200300L;
        assertThat(inspectModeManager.tryDebounce(playerUuid, packed)).isTrue();
        assertThat(inspectModeManager.tryDebounce(playerUuid, packed)).isFalse();
    }
}
