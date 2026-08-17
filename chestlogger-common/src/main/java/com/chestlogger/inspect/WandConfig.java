package com.chestlogger.inspect;

import java.util.Objects;

/**
 * Immutable configuration for the inspection wand and interactive click inspector.
 */
public record WandConfig(
        boolean wandEnabled,
        String wandItem,
        boolean leftClickChatInspect,
        boolean rightClickGuiInspect,
        long debounceMs
) {
    public static final WandConfig DEFAULT = new WandConfig(
            true,
            "minecraft:stick",
            true,
            true,
            200L
    );

    public WandConfig {
        Objects.requireNonNull(wandItem, "wandItem cannot be null");
        if (debounceMs < 0) {
            debounceMs = 0;
        }
    }

    public boolean isWandEnabled() {
        return wandEnabled;
    }

    public String getWandItem() {
        return wandItem;
    }

    public boolean isLeftClickChatInspect() {
        return leftClickChatInspect;
    }

    public boolean isRightClickGuiInspect() {
        return rightClickGuiInspect;
    }

    public long getDebounceMs() {
        return debounceMs;
    }
}
