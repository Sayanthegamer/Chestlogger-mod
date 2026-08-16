package com.chestlogger.client.gui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ItemResolverTest {

    @BeforeEach
    void setUp() {
        ItemResolver.clearCache();
    }

    @Test
    @DisplayName("Should correctly format item identifier strings into title-cased fallback names")
    void testFormatFallbackName() {
        assertThat(ItemResolver.formatFallbackName("minecraft:ancient_debris")).isEqualTo("Ancient Debris");
        assertThat(ItemResolver.formatFallbackName("minecraft:diamond_chestplate")).isEqualTo("Diamond Chestplate");
        assertThat(ItemResolver.formatFallbackName("mod_id:super_energy_conduit_v2")).isEqualTo("Super Energy Conduit V2");
        assertThat(ItemResolver.formatFallbackName("golden_apple")).isEqualTo("Golden Apple");
        assertThat(ItemResolver.formatFallbackName("")).isEqualTo("Unknown");
        assertThat(ItemResolver.formatFallbackName(null)).isEqualTo("Unknown");
    }

    @Test
    @DisplayName("Should return empty stack safely when itemId is null or blank")
    void testResolveNullOrBlank() {
        assertThat(ItemResolver.resolve(null).isEmpty()).isTrue();
        assertThat(ItemResolver.resolve("").isEmpty()).isTrue();
        assertThat(ItemResolver.resolve("   ").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("Should return fallback display name component for unknown items")
    void testGetDisplayNameForUnknown() {
        assertThat(ItemResolver.getDisplayName("minecraft:unknown_item_xyz").getString()).isEqualTo("Unknown Item Xyz");
        assertThat(ItemResolver.getDisplayName(null).getString()).isEqualTo("Unknown Item");
        assertThat(ItemResolver.getDisplayName("").getString()).isEqualTo("Unknown Item");
    }

    @Test
    @DisplayName("Should clear cache without exceptions")
    void testClearCache() {
        ItemResolver.formatFallbackName("minecraft:diamond");
        ItemResolver.clearCache();
        assertThat(ItemResolver.formatFallbackName("minecraft:gold_ingot")).isEqualTo("Gold Ingot");
    }
}
