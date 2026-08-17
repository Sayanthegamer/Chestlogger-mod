package com.chestlogger.web;

import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.index.IndexQueryFilter;
import com.chestlogger.query.QueryEngine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;

/**
 * Handles GET /api/v1/export.
 * Exports filtered or full transaction logs as streamed CSV or JSON downloads.
 */
public class ExportHttpHandler implements HttpHandler {
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final WebConfig config;
    private final Supplier<QueryEngine> queryEngineSupplier;

    public ExportHttpHandler(WebConfig config) {
        this(config, () -> null);
    }

    public ExportHttpHandler(WebConfig config, Supplier<QueryEngine> queryEngineSupplier) {
        this.config = config != null ? config : new WebConfig();
        this.queryEngineSupplier = queryEngineSupplier != null ? queryEngineSupplier : () -> null;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!HttpAuthValidator.validate(exchange, config)) {
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }

        try {
            Map<String, String> params = parseQueryParams(exchange.getRequestURI());
            String format = params.getOrDefault("format", "csv").trim().toLowerCase(Locale.ROOT);

            IndexQueryFilter.Builder filterBuilder = IndexQueryFilter.builder();

            Integer x;
            Integer y;
            Integer z;
            try {
                x = parseCoordinateParam(params, "x");
                y = parseCoordinateParam(params, "y");
                z = parseCoordinateParam(params, "z");
            } catch (IllegalArgumentException e) {
                sendError(exchange, 400, e.getMessage());
                return;
            }

            if (x != null && y != null && z != null) {
                filterBuilder.exactBlockPos(BlockPosUtil.pack(x, y, z));
            }

            String dim = params.get("dim");
            if (dim != null && !dim.isBlank()) {
                filterBuilder.dimension(dim.trim());
            }

            String player = params.get("player");
            UUID playerUuid = null;
            if (player != null && !player.isBlank()) {
                try {
                    playerUuid = UUID.fromString(player.trim());
                    filterBuilder.actorUuid(playerUuid);
                } catch (IllegalArgumentException ignored) {
                }
            }

            String item = params.get("item");
            if (item != null && !item.isBlank()) {
                filterBuilder.itemId(item.trim());
            }

            Long sinceSeconds = null;
            if (params.containsKey("sinceSeconds")) {
                String ssVal = params.get("sinceSeconds");
                if (ssVal != null && !ssVal.isBlank()) {
                    try {
                        sinceSeconds = Long.parseLong(ssVal.trim());
                    } catch (NumberFormatException e) {
                        sendError(exchange, 400, "Bad Request: Invalid numeric value for parameter 'sinceSeconds': " + ssVal);
                        return;
                    }
                }
            }
            if (sinceSeconds != null && sinceSeconds > 0) {
                long minTimeMs = System.currentTimeMillis() - (sinceSeconds * 1000L);
                filterBuilder.timeRange(minTimeMs, Long.MAX_VALUE);
            }

            int limit = parseIntParam(params, "limit", 100_000);
            if (limit < 1) limit = 100_000;
            filterBuilder.limit(limit);

            QueryEngine engine = queryEngineSupplier.get();
            List<TransactionLogEntry> records = (engine != null)
                    ? engine.fetchRecords(filterBuilder.build())
                    : Collections.emptyList();

            if (player != null && !player.isBlank() && playerUuid == null) {
                String finalPlayerName = player.trim();
                records = records.stream()
                        .filter(r -> r.actorName() != null && r.actorName().equalsIgnoreCase(finalPlayerName))
                        .toList();
            }

            long exportTimestamp = System.currentTimeMillis();

            if ("json".equals(format)) {
                exportJson(exchange, records, exportTimestamp);
            } else {
                exportCsv(exchange, records, exportTimestamp);
            }
        } catch (Exception e) {
            sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    private void exportCsv(HttpExchange exchange, List<TransactionLogEntry> records, long timestamp) throws IOException {
        String filename = "chestlogger_export_" + timestamp + ".csv";
        exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        exchange.sendResponseHeaders(200, 0);

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8))) {
            writer.write("timestamp,date_time,dimension,x,y,z,actor_name,actor_type,action,slot,item_id,delta,metadata_fingerprint");
            writer.write("\r\n");

            for (TransactionLogEntry entry : records) {
                long timeMs = entry.timestampMs();
                String dateTime = DATE_TIME_FORMAT.format(Instant.ofEpochMilli(timeMs));
                String dimension = entry.dimension();
                int x = BlockPosUtil.unpackX(entry.packedBlockPos());
                int y = BlockPosUtil.unpackY(entry.packedBlockPos());
                int z = BlockPosUtil.unpackZ(entry.packedBlockPos());
                String actorName = escapeCsv(entry.actorName() != null ? entry.actorName() : "System");
                String actorType = entry.actorType().name();
                String action = entry.actionType().name();

                if (entry.deltas().isEmpty()) {
                    writer.write(String.format(
                            "%d,%s,%s,%d,%d,%d,%s,%s,%s,%d,%s,%d,%d\r\n",
                            timeMs, dateTime, dimension, x, y, z, actorName, actorType, action,
                            0, "minecraft:air", 0, 0L
                    ));
                } else {
                    for (SlotDelta delta : entry.deltas()) {
                        writer.write(String.format(
                                "%d,%s,%s,%d,%d,%d,%s,%s,%s,%d,%s,%d,%d\r\n",
                                timeMs, dateTime, dimension, x, y, z, actorName, actorType, action,
                                delta.slotIndex(), delta.itemId(), delta.deltaQuantity(), delta.metadataFingerprint()
                        ));
                    }
                }
            }
            writer.flush();
        }
    }

    private void exportJson(HttpExchange exchange, List<TransactionLogEntry> records, long timestamp) throws IOException {
        String filename = "chestlogger_export_" + timestamp + ".json";
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        exchange.sendResponseHeaders(200, 0);

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8))) {
            writer.write("{\"exportTimestamp\":");
            writer.write(Long.toString(timestamp));
            writer.write(",\"totalRecords\":");
            writer.write(Integer.toString(records.size()));
            writer.write(",\"records\":[");

            boolean first = true;
            for (TransactionLogEntry entry : records) {
                long timeMs = entry.timestampMs();
                String dateTime = DATE_TIME_FORMAT.format(Instant.ofEpochMilli(timeMs));
                String dimension = entry.dimension();
                int x = BlockPosUtil.unpackX(entry.packedBlockPos());
                int y = BlockPosUtil.unpackY(entry.packedBlockPos());
                int z = BlockPosUtil.unpackZ(entry.packedBlockPos());
                String actorUuid = entry.actorUuid() != null ? entry.actorUuid().toString() : "";
                String actorName = entry.actorName() != null ? entry.actorName() : "System";
                String actorType = entry.actorType().name();
                String action = entry.actionType().name();

                if (entry.deltas().isEmpty()) {
                    if (!first) writer.write(",");
                    first = false;
                    writer.write(String.format(
                            "{\"timestamp\":%d,\"dateTime\":\"%s\",\"dimension\":\"%s\",\"x\":%d,\"y\":%d,\"z\":%d,\"actorUuid\":\"%s\",\"actorName\":\"%s\",\"actorType\":\"%s\",\"action\":\"%s\",\"slot\":0,\"itemId\":\"minecraft:air\",\"delta\":0,\"metadataFingerprint\":0}",
                            timeMs, dateTime, dimension, x, y, z, actorUuid, escapeJson(actorName), actorType, action
                    ));
                } else {
                    for (SlotDelta delta : entry.deltas()) {
                        if (!first) writer.write(",");
                        first = false;
                        writer.write(String.format(
                            "{\"timestamp\":%d,\"dateTime\":\"%s\",\"dimension\":\"%s\",\"x\":%d,\"y\":%d,\"z\":%d,\"actorUuid\":\"%s\",\"actorName\":\"%s\",\"actorType\":\"%s\",\"action\":\"%s\",\"slot\":%d,\"itemId\":\"%s\",\"delta\":%d,\"metadataFingerprint\":%d}",
                            timeMs, dateTime, dimension, x, y, z, actorUuid, escapeJson(actorName), actorType, action,
                            delta.slotIndex(), delta.itemId(), delta.deltaQuantity(), delta.metadataFingerprint()
                    ));
                    }
                }
            }

            writer.write("]}");
            writer.flush();
        }
    }

    private static Map<String, String> parseQueryParams(URI uri) {
        Map<String, String> params = new HashMap<>();
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }

        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) continue;
            int eqIdx = pair.indexOf('=');
            if (eqIdx >= 0) {
                String key = URLDecoder.decode(pair.substring(0, eqIdx), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair.substring(eqIdx + 1), StandardCharsets.UTF_8);
                params.put(key, val);
            } else {
                String key = URLDecoder.decode(pair, StandardCharsets.UTF_8);
                params.put(key, "");
            }
        }
        return params;
    }

    private static int parseIntParam(Map<String, String> params, String key, int defaultValue) {
        String val = params.get(key);
        if (val == null || val.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Integer parseCoordinateParam(Map<String, String> params, String key) throws IllegalArgumentException {
        String val = params.get(key);
        if (val == null || val.isBlank()) return null;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid numeric value for coordinate '" + key + "': " + val);
        }
    }

    private static String escapeCsv(String str) {
        if (str == null) return "";
        if (str.contains(",") || str.contains("\"") || str.contains("\n") || str.contains("\r")) {
            return "\"" + str.replace("\"", "\"\"") + "\"";
        }
        return str;
    }

    private static String escapeJson(String str) {
        if (str == null) return "";
        StringBuilder sb = new StringBuilder(str.length() + 8);
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] bytes = ("{\"error\":\"" + escapeJson(message) + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
