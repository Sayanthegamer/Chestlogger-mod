package com.chestlogger.alert;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable configuration for Discord webhook alerts and suspicious transaction rules.
 */
public record AlertConfig(
        boolean enabled,
        String webhookUrl,
        String botUsername,
        String avatarUrl,
        int quantityThreshold,
        Set<String> valuableItems,
        boolean alertOnContainerBreak,
        boolean alertOnValuableTheft,
        int rateLimitPerMinute
) {
    public static AlertConfig defaults() {
        return new AlertConfig(
                false,
                "",
                "ChestLogger Alerts",
                "",
                64,
                Set.of(
                        "minecraft:diamond",
                        "minecraft:diamond_block",
                        "minecraft:netherite_ingot",
                        "minecraft:netherite_block",
                        "minecraft:ancient_debris",
                        "minecraft:elytra",
                        "minecraft:beacon",
                        "minecraft:enchanted_golden_apple",
                        "minecraft:shulker_box"
                ),
                true,
                true,
                30
        );
    }

    public static AlertConfig fromJson(String json) {
        if (json == null || json.isBlank()) {
            return defaults();
        }

        AlertConfig def = defaults();
        boolean enabled = extractBoolean(json, "enabled", def.enabled);
        String webhookUrl = extractString(json, "webhookUrl", def.webhookUrl);
        String botUsername = extractString(json, "botUsername", def.botUsername);
        String avatarUrl = extractString(json, "avatarUrl", def.avatarUrl);
        int quantityThreshold = extractInt(json, "quantityThreshold", def.quantityThreshold);
        boolean alertOnContainerBreak = extractBoolean(json, "alertOnContainerBreak", def.alertOnContainerBreak);
        boolean alertOnValuableTheft = extractBoolean(json, "alertOnValuableTheft", def.alertOnValuableTheft);
        int rateLimitPerMinute = extractInt(json, "rateLimitPerMinute", def.rateLimitPerMinute);
        Set<String> valuableItems = extractStringSet(json, "valuableItems", def.valuableItems);

        return new AlertConfig(
                enabled,
                webhookUrl,
                botUsername,
                avatarUrl,
                quantityThreshold,
                valuableItems,
                alertOnContainerBreak,
                alertOnValuableTheft,
                rateLimitPerMinute
        );
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"enabled\": ").append(enabled).append(",\n");
        sb.append("  \"webhookUrl\": \"").append(escapeJson(webhookUrl)).append("\",\n");
        sb.append("  \"botUsername\": \"").append(escapeJson(botUsername)).append("\",\n");
        sb.append("  \"avatarUrl\": \"").append(escapeJson(avatarUrl)).append("\",\n");
        sb.append("  \"quantityThreshold\": ").append(quantityThreshold).append(",\n");
        sb.append("  \"alertOnContainerBreak\": ").append(alertOnContainerBreak).append(",\n");
        sb.append("  \"alertOnValuableTheft\": ").append(alertOnValuableTheft).append(",\n");
        sb.append("  \"rateLimitPerMinute\": ").append(rateLimitPerMinute).append(",\n");
        sb.append("  \"valuableItems\": [");
        int count = 0;
        for (String item : valuableItems) {
            if (count > 0) sb.append(", ");
            sb.append("\"").append(escapeJson(item)).append("\"");
            count++;
        }
        sb.append("]\n}");
        return sb.toString();
    }

    private static boolean extractBoolean(String json, String key, boolean defaultValue) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return Boolean.parseBoolean(matcher.group(1));
        }
        return defaultValue;
    }

    private static int extractInt(String json, String key, int defaultValue) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private static String extractString(String json, String key, String defaultValue) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return defaultValue;
    }

    private static Set<String> extractStringSet(String json, String key, Set<String> defaultValue) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[([^\\]]*)\\]");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            String arrayContent = matcher.group(1);
            Set<String> items = new HashSet<>();
            Pattern itemPattern = Pattern.compile("\"([^\"]*)\"");
            Matcher itemMatcher = itemPattern.matcher(arrayContent);
            while (itemMatcher.find()) {
                items.add(itemMatcher.group(1));
            }
            if (!items.isEmpty()) {
                return items;
            }
        }
        return defaultValue;
    }

    private static String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
