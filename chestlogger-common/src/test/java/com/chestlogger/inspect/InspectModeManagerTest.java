package com.chestlogger.inspect;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InspectModeManagerTest {

    private InspectModeManager manager;
    private final UUID player1 = UUID.randomUUID();
    private final UUID player2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        manager = new InspectModeManager(new WandConfig(true, "minecraft:stick", true, true, 200));
    }

    @Test
    @DisplayName("Toggle inspect mode turns mode on and off per player")
    void testToggleInspectMode() {
        assertFalse(manager.isInspectActive(player1));
        assertFalse(manager.isInspectActive(player2));

        assertTrue(manager.toggleInspect(player1));
        assertTrue(manager.isInspectActive(player1));
        assertFalse(manager.isInspectActive(player2));

        assertFalse(manager.toggleInspect(player1));
        assertFalse(manager.isInspectActive(player1));
    }

    @Test
    @DisplayName("Wand item matching identifies designated wand correctly")
    void testWandItemMatching() {
        assertTrue(manager.isWandItem("minecraft:stick"));
        assertTrue(manager.isWandItem("stick"));
        assertFalse(manager.isWandItem("minecraft:diamond_sword"));
        assertFalse(manager.isWandItem(null));
        assertFalse(manager.isWandItem(""));
    }

    @Test
    @DisplayName("Should inspect triggers if either toggle mode is active OR holding wand item")
    void testShouldInspect() {
        // Not active, no wand
        assertFalse(manager.shouldInspect(player1, "minecraft:diamond_sword"));

        // Holding wand item, toggle inactive
        assertTrue(manager.shouldInspect(player1, "minecraft:stick"));

        // Toggle active, no wand item held
        manager.setInspectActive(player1, true);
        assertTrue(manager.shouldInspect(player1, "minecraft:diamond_sword"));
        assertTrue(manager.shouldInspect(player1, null));
    }

    @Test
    @DisplayName("Debounce check prevents rapid repeated clicks on same block")
    void testDebounce() {
        long pos = 123456789L;
        assertTrue(manager.tryDebounce(player1, pos));
        assertFalse(manager.tryDebounce(player1, pos)); // Immediately again -> blocked by debounce

        // Different pos -> allowed
        assertTrue(manager.tryDebounce(player1, 987654321L));

        // Different player -> allowed
        assertTrue(manager.tryDebounce(player2, pos));
    }

    @Test
    @DisplayName("Custom wand configuration is honored")
    void testCustomWandConfig() {
        WandConfig custom = new WandConfig(false, "minecraft:blaze_rod", true, false, 500);
        InspectModeManager customManager = new InspectModeManager(custom);

        assertFalse(customManager.isWandItem("minecraft:stick"));
        assertTrue(customManager.isWandItem("minecraft:blaze_rod"));
        assertFalse(customManager.getConfig().isWandEnabled());
    }
}
