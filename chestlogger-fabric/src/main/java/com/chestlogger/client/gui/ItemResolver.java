package com.chestlogger.client.gui;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Safely resolves item identifier strings (e.g. "minecraft:diamond") into client-side
 * ItemStacks and localized display components using Minecraft's BuiltInRegistries.ITEM.
 *
 * Design constraints:
 * 1. Never substitutes barrier blocks unless the logged item is literally "minecraft:barrier".
 * 2. Returns ItemStack.EMPTY for unknown, invalid, or air item identifiers.
 * 3. Uses a fast thread-safe cache to avoid repeated registry lookups during render frames.
 */
public final class ItemResolver {
    private static final Map<String, ItemStack> ITEM_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Component> NAME_CACHE = new ConcurrentHashMap<>();

    private ItemResolver() {}

    /**
     * Resolves an item identifier string to an ItemStack.
     *
     * @param itemId the item identifier string (e.g. "minecraft:diamond")
     * @return a valid ItemStack representing the item, or ItemStack.EMPTY if unresolvable
     */
    public static ItemStack resolve(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return ItemStack.EMPTY;
        }

        return ITEM_CACHE.computeIfAbsent(itemId, idStr -> {
            try {
                Identifier identifier = Identifier.tryParse(idStr.trim());
                if (identifier == null) {
                    return ItemStack.EMPTY;
                }

                if (!BuiltInRegistries.ITEM.containsKey(identifier)) {
                    return ItemStack.EMPTY;
                }

                Item item = BuiltInRegistries.ITEM.getValue(identifier);
                if (item == null || item == Items.AIR) {
                    return ItemStack.EMPTY;
                }

                // If the item is barrier, only return it if the itemId actually requested barrier
                if (item == Items.BARRIER && !"minecraft:barrier".equals(identifier.toString())) {
                    return ItemStack.EMPTY;
                }

                return new ItemStack(item);
            } catch (Throwable t) {
                return ItemStack.EMPTY;
            }
        });
    }

    /**
     * Checks if the given item identifier resolves to a known registered non-air item.
     *
     * @param itemId the item identifier string
     * @return true if the item exists in the registry and is not air
     */
    public static boolean isResolvable(String itemId) {
        ItemStack stack = resolve(itemId);
        return !stack.isEmpty();
    }

    /**
     * Returns a human-friendly display name component for the item identifier.
     *
     * @param itemId the item identifier string
     * @return hover name from the resolved ItemStack, or formatted fallback name
     */
    public static Component getDisplayName(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return Component.literal("Unknown Item");
        }

        return NAME_CACHE.computeIfAbsent(itemId, idStr -> {
            try {
                ItemStack stack = resolve(idStr);
                if (!stack.isEmpty()) {
                    return stack.getHoverName();
                }
            } catch (Throwable ignored) {
            }
            return Component.literal(formatFallbackName(idStr));
        });
    }

    /**
     * Converts a raw item id like "minecraft:ancient_debris" to "Ancient Debris".
     *
     * @param itemId the item identifier string
     * @return formatted title-case item name
     */
    public static String formatFallbackName(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "Unknown";
        }
        String path = itemId;
        int colonIdx = path.indexOf(':');
        if (colonIdx >= 0 && colonIdx < path.length() - 1) {
            path = path.substring(colonIdx + 1);
        }
        String[] parts = path.replace('_', ' ').split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    sb.append(part.substring(1).toLowerCase());
                }
            }
        }
        return sb.toString();
    }

    /**
     * Clears cached ItemStack and Component instances.
     */
    public static void clearCache() {
        ITEM_CACHE.clear();
        NAME_CACHE.clear();
    }
}
