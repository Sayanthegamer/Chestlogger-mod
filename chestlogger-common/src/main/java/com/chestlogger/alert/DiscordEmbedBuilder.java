package com.chestlogger.alert;

import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.security.IncidentClassification;
import com.chestlogger.security.OwnerPresenceState;
import com.chestlogger.security.SecurityIncident;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Formats Discord Webhook JSON payloads containing security embeds.
 */
public final class DiscordEmbedBuilder {

    public static final int COLOR_CRITICAL_RAID = 10038562;       // 0x992D22 Crimson Red
    public static final int COLOR_OFFLINE_THEFT = 15158332;       // 0xE74C3C Crimson Red
    public static final int COLOR_ABSENT_OWNER_THEFT = 15105570;  // 0xE67E22 Orange
    public static final int COLOR_DEFAULT_GOLD = 15844367;        // 0xF1C40F Gold

    private DiscordEmbedBuilder() {}

    /**
     * Formats the container owner presence status into an emoji badge string.
     *
     * @param presence OwnerPresenceState instance (or null).
     * @return Formatted presence badge string.
     */
    public static String formatOwnerPresence(OwnerPresenceState presence) {
        if (presence == null || !presence.isOnline()) {
            return "🔴 Offline";
        }
        double dist = presence.distanceBlocks();
        if (presence.isNearby()) {
            if (dist >= 0) {
                return String.format(Locale.ROOT, "🟢 Nearby (~%dm away)", Math.round(dist));
            }
            return "🟢 Nearby";
        } else {
            if (dist >= 0 && dist < 1_000_000) {
                return String.format(Locale.ROOT, "🟡 Absent (~%dm away)", Math.round(dist));
            }
            return "🟡 Absent";
        }
    }

    /**
     * Returns the integer color code corresponding to an incident classification.
     */
    public static int getEmbedColor(IncidentClassification classification) {
        if (classification == null) {
            return COLOR_DEFAULT_GOLD;
        }
        return switch (classification) {
            case CRITICAL_RAID -> COLOR_CRITICAL_RAID;
            case OFFLINE_THEFT -> COLOR_OFFLINE_THEFT;
            case ABSENT_OWNER_THEFT -> COLOR_ABSENT_OWNER_THEFT;
            default -> COLOR_DEFAULT_GOLD;
        };
    }

    /**
     * Builds a Discord webhook JSON payload from a SecurityIncident.
     *
     * @param incident SecurityIncident to format.
     * @param config AlertConfig containing webhook credentials and styling.
     * @return JSON string payload ready for HTTP POST dispatch.
     */
    public static String buildWebhookPayload(SecurityIncident incident, AlertConfig config) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{");

        // Webhook username & avatar
        if (config.botUsername() != null && !config.botUsername().isBlank()) {
            sb.append("\"username\":\"").append(escapeJson(config.botUsername())).append("\",");
        }
        if (config.avatarUrl() != null && !config.avatarUrl().isBlank()) {
            sb.append("\"avatar_url\":\"").append(escapeJson(config.avatarUrl())).append("\",");
        }

        sb.append("\"embeds\":[{");

        // Title based on classification
        String title = switch (incident.classification()) {
            case CRITICAL_RAID -> "🚨 CRITICAL RAID ALERT";
            case OFFLINE_THEFT -> "🚨 Offline Theft Detected";
            case ABSENT_OWNER_THEFT -> "⚠️ Absent Owner Theft Detected";
            case CONSENSUAL_PROXIMITY -> "ℹ️ Consensual Container Interaction";
            case UNCLAIMED_NATURAL -> "ℹ️ Unclaimed Container Interaction";
            case INFO -> "ℹ️ Container Security Event";
        };
        sb.append("\"title\":\"").append(escapeJson(title)).append("\",");

        if (incident.summary() != null && !incident.summary().isBlank()) {
            sb.append("\"description\":\"").append(escapeJson(incident.summary())).append("\",");
        }

        // Color
        sb.append("\"color\":").append(getEmbedColor(incident.classification())).append(",");

        // ISO Timestamp
        String isoTime = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(incident.timestampMs()));
        sb.append("\"timestamp\":\"").append(isoTime).append("\",");

        // Fields
        sb.append("\"fields\":[");

        // Field 1: Actor
        String actorName = (incident.actorName() != null && !incident.actorName().isBlank()) ? incident.actorName() : "Unknown";
        String actorUuid = incident.actorUuid() != null ? incident.actorUuid().toString() : "N/A";
        sb.append("{");
        sb.append("\"name\":\"👤 Actor\",");
        sb.append("\"value\":\"**").append(escapeJson(actorName)).append("**\\nUUID: `").append(actorUuid).append("`\",");
        sb.append("\"inline\":true");
        sb.append("},");

        // Field 2: Owner & Presence
        String ownerName = (incident.ownerName() != null && !incident.ownerName().isBlank()) ? incident.ownerName() : "Unclaimed / Wilderness";
        String presenceBadge = formatOwnerPresence(incident.ownerPresence());
        sb.append("{");
        sb.append("\"name\":\"👑 Owner & Status\",");
        sb.append("\"value\":\"**").append(escapeJson(ownerName)).append("**\\n").append(presenceBadge).append("\",");
        sb.append("\"inline\":true");
        sb.append("},");

        // Field 3: Location
        int[] coords = BlockPosUtil.unpack(incident.packedPos());
        sb.append("{");
        sb.append("\"name\":\"📍 Location\",");
        sb.append("\"value\":\"`").append(coords[0]).append(", ").append(coords[1]).append(", ").append(coords[2])
                .append("`\\nDim: `").append(escapeJson(incident.dimension())).append("`\",");
        sb.append("\"inline\":true");
        sb.append("},");

        // Field 4: Item Deltas & Burst status
        sb.append("{");
        sb.append("\"name\":\"📦 Item Deltas\",");
        sb.append("\"value\":\"");
        String sign = incident.deltaQuantity() > 0 ? "+" : "";
        sb.append("• `").append(sign).append(incident.deltaQuantity()).append("x` ").append(escapeJson(incident.itemId()));
        if (incident.isRaidBurst()) {
            sb.append("\\n🔥 **Raid Burst Flagged** (Rapid multi-container drain)");
        }
        sb.append("\",");
        sb.append("\"inline\":false");
        sb.append("}");

        sb.append("],"); // end fields

        // Footer
        sb.append("\"footer\":{\"text\":\"ChestLogger Security Engine • Seq #").append(incident.sequenceId()).append("\"}");

        sb.append("}]"); // end embeds
        sb.append("}");
        return sb.toString();
    }

    /**
     * Builds a Discord webhook JSON payload from a raw TransactionLogEntry (legacy overload).
     */
    public static String buildWebhookPayload(TransactionLogEntry entry, AlertConfig config) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{");

        // Webhook username & avatar
        if (config.botUsername() != null && !config.botUsername().isBlank()) {
            sb.append("\"username\":\"").append(escapeJson(config.botUsername())).append("\",");
        }
        if (config.avatarUrl() != null && !config.avatarUrl().isBlank()) {
            sb.append("\"avatar_url\":\"").append(escapeJson(config.avatarUrl())).append("\",");
        }

        sb.append("\"embeds\":[{");
        sb.append("\"title\":\"🚨 Suspicious Container Activity Detected\",");
        sb.append("\"color\":15158332,"); // Crimson Red 0xE74C3C

        // ISO Timestamp
        String isoTime = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(entry.timestampMs()));
        sb.append("\"timestamp\":\"").append(isoTime).append("\",");

        // Fields
        sb.append("\"fields\":[");

        // Field 1: Actor
        String actorName = (entry.actorName() != null && !entry.actorName().isBlank()) ? entry.actorName() : entry.actorType().name();
        String actorUuid = entry.actorUuid() != null ? entry.actorUuid().toString() : "N/A";
        sb.append("{");
        sb.append("\"name\":\"👤 Actor\",");
        sb.append("\"value\":\"**").append(escapeJson(actorName)).append("**\\nUUID: `").append(actorUuid).append("`\",");
        sb.append("\"inline\":true");
        sb.append("},");

        // Field 2: Action
        sb.append("{");
        sb.append("\"name\":\"⚙️ Action\",");
        sb.append("\"value\":\"`").append(entry.actionType().name()).append("`\",");
        sb.append("\"inline\":true");
        sb.append("},");

        // Field 3: Location
        int[] coords = BlockPosUtil.unpack(entry.packedBlockPos());
        sb.append("{");
        sb.append("\"name\":\"📍 Location\",");
        sb.append("\"value\":\"`").append(coords[0]).append(", ").append(coords[1]).append(", ").append(coords[2])
                .append("`\\nDim: `").append(escapeJson(entry.dimension())).append("`\",");
        sb.append("\"inline\":true");
        sb.append("},");

        // Field 4: Item Transactions / Deltas
        sb.append("{");
        sb.append("\"name\":\"📦 Item Deltas\",");
        sb.append("\"value\":\"");
        List<SlotDelta> deltas = entry.deltas();
        if (deltas == null || deltas.isEmpty()) {
            sb.append("No specific item deltas");
        } else {
            int displayCount = Math.min(deltas.size(), 10);
            for (int i = 0; i < displayCount; i++) {
                SlotDelta d = deltas.get(i);
                String sign = d.deltaQuantity() > 0 ? "+" : "";
                sb.append("• `").append(sign).append(d.deltaQuantity()).append("x` ").append(escapeJson(d.itemId())).append("\\n");
            }
            if (deltas.size() > 10) {
                sb.append("• ... and ").append(deltas.size() - 10).append(" more items");
            }
        }
        sb.append("\",");
        sb.append("\"inline\":false");
        sb.append("}");

        sb.append("],"); // end fields

        // Footer
        sb.append("\"footer\":{\"text\":\"ChestLogger Security Engine • Seq #").append(entry.sequenceId()).append("\"}");

        sb.append("}]"); // end embeds
        sb.append("}");
        return sb.toString();
    }

    public static String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}
