package com.chestlogger.web;

import com.chestlogger.ChestLoggerMod;
import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.index.IndexQueryFilter;
import com.chestlogger.network.ChestLogPagePayload;
import com.chestlogger.network.DisplayRecord;
import com.chestlogger.query.QueryEngine;
import com.chestlogger.query.QuerySessionManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/**
 * Handles GET /api/v1/query.
 * Parses query parameters, searches transaction records using QueryEngine,
 * manages paginated GUI/API sessions via QuerySessionManager, and returns JSON.
 */
public class QueryHttpHandler implements HttpHandler {
    private final WebConfig config;
    private final Supplier<QueryEngine> queryEngineSupplier;
    private final Supplier<QuerySessionManager> sessionManagerSupplier;

    public QueryHttpHandler(WebConfig config) {
        this(config, ChestLoggerMod::getQueryEngine, ChestLoggerMod::getSessionManager);
    }

    public QueryHttpHandler(
            WebConfig config,
            Supplier<QueryEngine> queryEngineSupplier,
            Supplier<QuerySessionManager> sessionManagerSupplier
    ) {
        this.config = config != null ? config : new WebConfig();
        this.queryEngineSupplier = queryEngineSupplier != null ? queryEngineSupplier : ChestLoggerMod::getQueryEngine;
        this.sessionManagerSupplier = sessionManagerSupplier != null ? sessionManagerSupplier : ChestLoggerMod::getSessionManager;
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

            int page = parseIntParam(params, "page", 1);
            if (page < 1) page = 1;

            int limit = parseIntParam(params, "limit", 25);
            if (limit < 1) limit = 1;
            if (limit > 100) limit = 100;

            String queryIdParam = params.get("queryId");
            UUID queryId = null;
            if (queryIdParam != null && !queryIdParam.isBlank()) {
                try {
                    queryId = UUID.fromString(queryIdParam.trim());
                } catch (IllegalArgumentException ignored) {
                }
            }

            QuerySessionManager sessionManager = sessionManagerSupplier.get();
            ChestLogPagePayload pagePayload = null;

            if (queryId != null && sessionManager != null && sessionManager.hasSession(queryId)) {
                pagePayload = sessionManager.getPage(queryId, page);
            }

            if (pagePayload == null) {
                // Perform a new query search
                IndexQueryFilter.Builder filterBuilder = IndexQueryFilter.builder();

                Integer x = parseNullableInt(params, "x");
                Integer y = parseNullableInt(params, "y");
                Integer z = parseNullableInt(params, "z");
                long packedPos = 0L;
                if (x != null && y != null && z != null) {
                    packedPos = BlockPosUtil.pack(x, y, z);
                    filterBuilder.exactBlockPos(packedPos);
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
                        // Will filter by player name in-memory after fetching
                    }
                }

                String item = params.get("item");
                if (item != null && !item.isBlank()) {
                    filterBuilder.itemId(item.trim());
                }

                Long sinceSeconds = parseNullableLong(params, "sinceSeconds");
                if (sinceSeconds != null && sinceSeconds > 0) {
                    long minTimeMs = System.currentTimeMillis() - (sinceSeconds * 1000L);
                    filterBuilder.timeRange(minTimeMs, Long.MAX_VALUE);
                }

                // Fetch up to 10,000 candidate records for pagination
                filterBuilder.limit(10_000);

                QueryEngine engine = queryEngineSupplier.get();
                List<TransactionLogEntry> records = (engine != null)
                        ? engine.fetchRecords(filterBuilder.build())
                        : Collections.emptyList();

                // If player was a name and not UUID, filter in-memory
                if (player != null && !player.isBlank() && playerUuid == null) {
                    String finalPlayerName = player.trim();
                    records = records.stream()
                            .filter(r -> r.actorName() != null && r.actorName().equalsIgnoreCase(finalPlayerName))
                            .toList();
                }

                UUID sessionQueryId = (queryId != null) ? queryId : UUID.randomUUID();
                String dimensionStr = (dim != null && !dim.isBlank()) ? dim.trim() : "minecraft:overworld";

                if (sessionManager != null) {
                    pagePayload = sessionManager.createSession(
                            sessionQueryId,
                            "Container",
                            dimensionStr,
                            packedPos,
                            records,
                            page,
                            limit
                    );
                } else {
                    pagePayload = new ChestLogPagePayload(
                            sessionQueryId,
                            1,
                            1,
                            0,
                            "Container",
                            dimensionStr,
                            packedPos,
                            Collections.emptyList()
                    );
                }
            }

            String jsonResponse = serializePagePayload(pagePayload);
            byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        } catch (Exception e) {
            sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    private static String serializePagePayload(ChestLogPagePayload payload) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{");
        sb.append("\"queryId\":\"").append(payload.queryId()).append("\",");
        sb.append("\"page\":").append(payload.pageIndex()).append(",");
        sb.append("\"totalPages\":").append(payload.totalPages()).append(",");
        sb.append("\"totalRecords\":").append(payload.totalRecords()).append(",");
        sb.append("\"records\":[");

        List<DisplayRecord> records = payload.records();
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) sb.append(",");
            DisplayRecord r = records.get(i);
            sb.append("{");
            sb.append("\"sequenceId\":").append(r.sequenceId()).append(",");
            sb.append("\"timestamp\":").append(r.timestampMs()).append(",");
            sb.append("\"actorUuid\":\"").append(r.actorUuid()).append("\",");
            sb.append("\"actorName\":\"").append(escapeJson(r.actorName())).append("\",");
            sb.append("\"actorType\":\"").append(ActorType.fromWireId(r.actorType()).name()).append("\",");
            sb.append("\"action\":\"").append(ActionType.fromWireId(r.actionType()).name()).append("\",");
            sb.append("\"slot\":").append(r.slotIndex()).append(",");
            sb.append("\"item\":\"").append(escapeJson(r.itemId())).append("\",");
            sb.append("\"delta\":").append(r.quantityDelta()).append(",");
            sb.append("\"metadataFingerprint\":").append(r.metadataFingerprint());
            sb.append("}");
        }

        sb.append("]}");
        return sb.toString();
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

    private static Integer parseNullableInt(Map<String, String> params, String key) {
        String val = params.get(key);
        if (val == null || val.isBlank()) return null;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseNullableLong(Map<String, String> params, String key) {
        String val = params.get(key);
        if (val == null || val.isBlank()) return null;
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
