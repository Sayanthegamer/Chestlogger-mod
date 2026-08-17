package com.chestlogger.alert;

import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Formats Discord Webhook JSON payloads containing security embeds.
 */
public final class DiscordEmbedBuilder {

    private DiscordEmbedBuilder() {}

    public static String buildWebhookPayload(TransactionLogEntry entry, AlertConfig config) {
        StringBuilder sb = new StringBuilder();
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

    private static String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}
