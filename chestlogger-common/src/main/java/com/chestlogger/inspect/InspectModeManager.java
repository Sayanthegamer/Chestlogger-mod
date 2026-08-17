package com.chestlogger.inspect;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe manager handling interactive inspection mode sessions and wand matching.
 */
public final class InspectModeManager {

    private final Set<UUID> activeInspectors = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> lastClickTimestamps = new ConcurrentHashMap<>();
    private final WandConfig config;

    public InspectModeManager() {
        this(WandConfig.DEFAULT);
    }

    public InspectModeManager(WandConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    public WandConfig getConfig() {
        return config;
    }

    /**
     * Toggles inspection mode for the specified player.
     *
     * @param playerUuid player UUID
     * @return true if mode is now active, false if deactivated
     */
    public boolean toggleInspect(UUID playerUuid) {
        if (playerUuid == null) return false;
        if (activeInspectors.contains(playerUuid)) {
            activeInspectors.remove(playerUuid);
            return false;
        } else {
            activeInspectors.add(playerUuid);
            return true;
        }
    }

    /**
     * Checks if inspect mode is currently active for the player.
     */
    public boolean isInspectActive(UUID playerUuid) {
        if (playerUuid == null) return false;
        return activeInspectors.contains(playerUuid);
    }

    /**
     * Explicitly sets inspect mode active state for the player.
     */
    public void setInspectActive(UUID playerUuid, boolean active) {
        if (playerUuid == null) return;
        if (active) {
            activeInspectors.add(playerUuid);
        } else {
            activeInspectors.remove(playerUuid);
        }
    }

    /**
     * Clears active inspect mode for the player (e.g. on disconnect).
     */
    public void clear(UUID playerUuid) {
        if (playerUuid != null) {
            activeInspectors.remove(playerUuid);
        }
    }

    /**
     * Checks if the given item ID matches the configured wand item.
     */
    public boolean isWandItem(String itemId) {
        if (itemId == null || itemId.isBlank()) return false;
        String normalizedTarget = config.getWandItem().toLowerCase();
        String normalizedCheck = itemId.toLowerCase();
        if (normalizedCheck.equals(normalizedTarget)) return true;
        if (!normalizedCheck.contains(":") && ("minecraft:" + normalizedCheck).equals(normalizedTarget)) return true;
        if (!normalizedTarget.contains(":") && ("minecraft:" + normalizedTarget).equals(normalizedCheck)) return true;
        return false;
    }

    /**
     * Evaluates if an inspection action should trigger based on active toggle mode OR held wand item.
     */
    public boolean shouldInspect(UUID playerUuid, String heldItemId) {
        if (isInspectActive(playerUuid)) return true;
        if (config.isWandEnabled() && isWandItem(heldItemId)) return true;
        return false;
    }

    /**
     * Applies a debounce window per player and block position.
     *
     * @param playerUuid player UUID
     * @param packedPos packed block coordinates
     * @return true if the action is allowed, false if blocked by debounce
     */
    public boolean tryDebounce(UUID playerUuid, long packedPos) {
        if (playerUuid == null) return false;
        long now = System.currentTimeMillis();
        String key = playerUuid + ":" + packedPos;
        Long previous = lastClickTimestamps.get(key);
        if (previous != null && (now - previous) < config.getDebounceMs()) {
            return false;
        }
        lastClickTimestamps.put(key, now);
        return true;
    }
}
