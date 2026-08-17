package com.chestlogger.web;

import com.chestlogger.alert.DiscordEmbedBuilder;
import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.index.IndexQueryFilter;
import com.chestlogger.query.DisplayRecord;
import com.chestlogger.query.PagedResult;
import com.chestlogger.query.QueryEngine;
import com.chestlogger.query.QuerySessionManager;
import com.chestlogger.provenance.ConfidenceLevel;
import com.chestlogger.provenance.ItemProvenanceResolver;
import com.chestlogger.provenance.ProvenanceEdge;
import com.chestlogger.provenance.ProvenanceGraph;
import com.chestlogger.provenance.ProvenanceNode;
import com.chestlogger.security.IncidentRingBuffer;
import com.chestlogger.security.OwnerPresenceState;
import com.chestlogger.security.SecurityIncident;
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
 * Handles GET /api/v1/query, GET /api/v1/provenance, and GET /api/v1/incidents.
 * Parses query parameters, searches transaction records using QueryEngine,
 * resolves item journey graphs via ItemProvenanceResolver, retrieves security incidents
 * from IncidentRingBuffer, manages paginated sessions, and returns JSON.
 */
public class QueryHttpHandler implements HttpHandler {
    private final WebConfig config;
    private final Supplier<QueryEngine> queryEngineSupplier;
    private final Supplier<QuerySessionManager> sessionManagerSupplier;
    private final Supplier<IncidentRingBuffer> incidentBufferSupplier;

    public QueryHttpHandler(WebConfig config) {
        this(config, () -> null, () -> null, () -> null);
    }

    public QueryHttpHandler(
            WebConfig config,
            Supplier<QueryEngine> queryEngineSupplier,
            Supplier<QuerySessionManager> sessionManagerSupplier
    ) {
        this(config, queryEngineSupplier, sessionManagerSupplier, () -> null);
    }

    public QueryHttpHandler(
            WebConfig config,
            Supplier<QueryEngine> queryEngineSupplier,
            Supplier<QuerySessionManager> sessionManagerSupplier,
            Supplier<IncidentRingBuffer> incidentBufferSupplier
    ) {
        this.config = config != null ? config : new WebConfig();
        this.queryEngineSupplier = queryEngineSupplier != null ? queryEngineSupplier : () -> null;
        this.sessionManagerSupplier = sessionManagerSupplier != null ? sessionManagerSupplier : () -> null;
        this.incidentBufferSupplier = incidentBufferSupplier != null ? incidentBufferSupplier : () -> null;
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

        String path = exchange.getRequestURI().getPath();
        if (path != null && (path.endsWith("/incidents") || path.contains("/incidents"))) {
            handleIncidents(exchange);
        } else if (path != null && (path.endsWith("/provenance") || path.contains("/provenance"))) {
            handleProvenance(exchange);
        } else {
            handleQuery(exchange);
        }
    }

    private void handleIncidents(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> params = parseQueryParams(exchange.getRequestURI());
            int limit = parseIntParam(params, "limit", 200);
            if (limit < 1) limit = 1;
            if (limit > 200) limit = 200;

            String classificationFilter = params.get("classification");
            String actorFilter = params.get("actor");
            if (actorFilter == null || actorFilter.isBlank()) {
                actorFilter = params.get("player");
            }

            IncidentRingBuffer buffer = incidentBufferSupplier.get();
            List<SecurityIncident> allIncidents = (buffer != null) ? buffer.getAll() : Collections.emptyList();

            List<SecurityIncident> filtered = new ArrayList<>();
            for (SecurityIncident inc : allIncidents) {
                if (classificationFilter != null && !classificationFilter.isBlank()) {
                    if (!inc.classification().name().equalsIgnoreCase(classificationFilter.trim())) {
                        continue;
                    }
                }
                if (actorFilter != null && !actorFilter.isBlank()) {
                    String aName = inc.actorName();
                    String aUuid = inc.actorUuid() != null ? inc.actorUuid().toString() : "";
                    if (!aName.equalsIgnoreCase(actorFilter.trim()) && !aUuid.equalsIgnoreCase(actorFilter.trim())) {
                        continue;
                    }
                }
                filtered.add(inc);
                if (filtered.size() >= limit) {
                    break;
                }
            }

            String jsonResponse = serializeIncidents(filtered);
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

    public static String serializeIncidents(List<SecurityIncident> incidents) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("[");
        if (incidents != null) {
            for (int i = 0; i < incidents.size(); i++) {
                if (i > 0) sb.append(",");
                SecurityIncident inc = incidents.get(i);
                int x = BlockPosUtil.unpackX(inc.packedPos());
                int y = BlockPosUtil.unpackY(inc.packedPos());
                int z = BlockPosUtil.unpackZ(inc.packedPos());
                OwnerPresenceState pres = inc.ownerPresence();
                String presStatus = DiscordEmbedBuilder.formatOwnerPresence(pres);

                sb.append("{");
                sb.append("\"timestamp\":").append(inc.timestampMs()).append(",");
                sb.append("\"timestampMs\":").append(inc.timestampMs()).append(",");
                sb.append("\"sequenceId\":").append(inc.sequenceId()).append(",");
                sb.append("\"classification\":\"").append(inc.classification().name()).append("\",");
                if (inc.actorUuid() != null) {
                    sb.append("\"actorUuid\":\"").append(inc.actorUuid()).append("\",");
                } else {
                    sb.append("\"actorUuid\":null,");
                }
                sb.append("\"actorName\":\"").append(escapeJson(inc.actorName())).append("\",");
                if (inc.ownerUuid() != null) {
                    sb.append("\"ownerUuid\":\"").append(inc.ownerUuid()).append("\",");
                } else {
                    sb.append("\"ownerUuid\":null,");
                }
                sb.append("\"ownerName\":\"").append(escapeJson(inc.ownerName())).append("\",");
                sb.append("\"ownerPresence\":{");
                sb.append("\"isOnline\":").append(pres.isOnline()).append(",");
                sb.append("\"distance\":").append(pres.distanceBlocks()).append(",");
                sb.append("\"isNearby\":").append(pres.isNearby()).append(",");
                sb.append("\"status\":\"").append(escapeJson(presStatus)).append("\"");
                sb.append("},");
                sb.append("\"presenceStatus\":\"").append(escapeJson(presStatus)).append("\",");
                sb.append("\"x\":").append(x).append(",");
                sb.append("\"y\":").append(y).append(",");
                sb.append("\"z\":").append(z).append(",");
                sb.append("\"packedPos\":").append(inc.packedPos()).append(",");
                sb.append("\"dimension\":\"").append(escapeJson(inc.dimension())).append("\",");
                sb.append("\"itemId\":\"").append(escapeJson(inc.itemId())).append("\",");
                sb.append("\"item\":\"").append(escapeJson(inc.itemId())).append("\",");
                sb.append("\"deltaQuantity\":").append(inc.deltaQuantity()).append(",");
                sb.append("\"delta\":").append(inc.deltaQuantity()).append(",");
                sb.append("\"summary\":\"").append(escapeJson(inc.summary())).append("\",");
                sb.append("\"isRaidBurst\":").append(inc.isRaidBurst());
                sb.append("}");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private void handleProvenance(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> params = parseQueryParams(exchange.getRequestURI());

            String item = params.get("item");
            if (item == null || item.isBlank()) {
                item = params.get("itemId");
            }
            if (item == null || item.isBlank()) {
                sendError(exchange, 400, "Bad Request: Missing required parameter 'item' or 'itemId'");
                return;
            }
            String itemId = item.trim();

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

            if ((x != null || y != null || z != null) && (x == null || y == null || z == null)) {
                sendError(exchange, 400, "Bad Request: Coordinates must include all of x, y, and z if specified.");
                return;
            }

            long packedPos = (x != null && y != null && z != null) ? BlockPosUtil.pack(x, y, z) : 0L;

            String dim = params.get("dim");
            if (dim == null || dim.isBlank()) {
                dim = params.get("dimension");
            }
            String dimensionStr = (dim != null && !dim.isBlank()) ? dim.trim() : "minecraft:overworld";

            long fingerprint = 0L;
            if (params.containsKey("fingerprint")) {
                String fpStr = params.get("fingerprint");
                if (fpStr != null && !fpStr.isBlank()) {
                    try {
                        fingerprint = Long.parseLong(fpStr.trim());
                    } catch (NumberFormatException e) {
                        sendError(exchange, 400, "Bad Request: Invalid numeric value for parameter 'fingerprint': " + fpStr);
                        return;
                    }
                }
            }

            int maxHops = ItemProvenanceResolver.DEFAULT_MAX_HOPS;
            if (params.containsKey("maxHops")) {
                String hopsStr = params.get("maxHops");
                if (hopsStr != null && !hopsStr.isBlank()) {
                    try {
                        maxHops = Integer.parseInt(hopsStr.trim());
                        if (maxHops < 1) maxHops = 1;
                        if (maxHops > 50) maxHops = 50;
                    } catch (NumberFormatException e) {
                        sendError(exchange, 400, "Bad Request: Invalid numeric value for parameter 'maxHops': " + hopsStr);
                        return;
                    }
                }
            }

            QueryEngine engine = queryEngineSupplier.get();
            ProvenanceGraph graph;
            if (engine != null) {
                ItemProvenanceResolver resolver = new ItemProvenanceResolver();
                graph = resolver.resolveProvenance(packedPos, dimensionStr, itemId, fingerprint, engine, maxHops, ItemProvenanceResolver.DEFAULT_MAX_TIME_WINDOW_MS);
            } else {
                graph = ProvenanceGraph.empty(itemId, packedPos);
            }

            String jsonResponse = serializeProvenanceGraph(graph);
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

    private void handleQuery(HttpExchange exchange) throws IOException {
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
            PagedResult<DisplayRecord> pageResult = null;
            UUID activeQueryId = (queryId != null) ? queryId : UUID.randomUUID();
            String containerType = "Container";
            String dimensionStr = "minecraft:overworld";
            long packedPos = 0L;

            if (queryId != null && sessionManager != null && sessionManager.hasSession(queryId)) {
                pageResult = sessionManager.getPage(queryId, page);
            }

            if (pageResult == null) {
                // Perform a new query search
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
                    packedPos = BlockPosUtil.pack(x, y, z);
                    filterBuilder.exactBlockPos(packedPos);
                }

                String dim = params.get("dim");
                if (dim != null && !dim.isBlank()) {
                    dimensionStr = dim.trim();
                    filterBuilder.dimension(dimensionStr);
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

                filterBuilder.limit(10_000);

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

                if (sessionManager != null) {
                    pageResult = sessionManager.createSession(
                            activeQueryId,
                            containerType,
                            dimensionStr,
                            packedPos,
                            records,
                            page,
                            limit
                    );
                } else {
                    pageResult = new PagedResult<>(Collections.emptyList(), 1, limit, 1, 0);
                }
            }

            String jsonResponse = serializePageResult(activeQueryId, containerType, dimensionStr, packedPos, pageResult);
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

    private static String serializePageResult(UUID queryId, String containerType, String dimension, long packedPos, PagedResult<DisplayRecord> result) {
        StringBuilder sb = new StringBuilder(1024);
        int defX = BlockPosUtil.unpackX(packedPos);
        int defY = BlockPosUtil.unpackY(packedPos);
        int defZ = BlockPosUtil.unpackZ(packedPos);
        String defDim = dimension != null ? dimension : "minecraft:overworld";

        sb.append("{");
        sb.append("\"queryId\":\"").append(queryId).append("\",");
        sb.append("\"page\":").append(result.pageNumber()).append(",");
        sb.append("\"totalPages\":").append(result.totalPages()).append(",");
        sb.append("\"totalRecords\":").append(result.totalElements()).append(",");
        sb.append("\"containerType\":\"").append(escapeJson(containerType)).append("\",");
        sb.append("\"dimension\":\"").append(escapeJson(defDim)).append("\",");
        sb.append("\"x\":").append(defX).append(",");
        sb.append("\"y\":").append(defY).append(",");
        sb.append("\"z\":").append(defZ).append(",");
        sb.append("\"records\":[");

        List<DisplayRecord> records = result.items();
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) sb.append(",");
            DisplayRecord r = records.get(i);
            long packed = (r.packedBlockPos() != 0L) ? r.packedBlockPos() : packedPos;
            int recX = BlockPosUtil.unpackX(packed);
            int recY = BlockPosUtil.unpackY(packed);
            int recZ = BlockPosUtil.unpackZ(packed);
            String recDim = (r.dimension() != null && !r.dimension().isBlank()) ? r.dimension() : defDim;

            sb.append("{");
            sb.append("\"sequenceId\":").append(r.sequenceId()).append(",");
            sb.append("\"timestamp\":").append(r.timestampMs()).append(",");
            sb.append("\"actorUuid\":\"").append(r.actorUuid()).append("\",");
            sb.append("\"actorName\":\"").append(escapeJson(r.actorName())).append("\",");
            sb.append("\"actorType\":\"").append(ActorType.fromWireId(r.actorType()).name()).append("\",");
            sb.append("\"action\":\"").append(ActionType.fromWireId(r.actionType()).name()).append("\",");
            sb.append("\"slot\":").append(r.slotIndex()).append(",");
            sb.append("\"item\":\"").append(escapeJson(r.itemId())).append("\",");
            sb.append("\"itemId\":\"").append(escapeJson(r.itemId())).append("\",");
            sb.append("\"x\":").append(recX).append(",");
            sb.append("\"y\":").append(recY).append(",");
            sb.append("\"z\":").append(recZ).append(",");
            sb.append("\"dimension\":\"").append(escapeJson(recDim)).append("\",");
            sb.append("\"delta\":").append(r.quantityDelta()).append(",");
            sb.append("\"metadataFingerprint\":").append(r.metadataFingerprint());
            sb.append("}");
        }

        sb.append("]}");
        return sb.toString();
    }

    public static String serializeProvenanceGraph(ProvenanceGraph graph) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("{");
        sb.append("\"targetItemId\":\"").append(escapeJson(graph.targetItemId())).append("\",");
        sb.append("\"targetPackedPos\":").append(graph.targetPackedPos()).append(",");
        sb.append("\"totalSteps\":").append(graph.totalSteps()).append(",");
        sb.append("\"overallConfidence\":\"").append(graph.overallConfidence().name()).append("\",");

        sb.append("\"nodes\":[");
        List<ProvenanceNode> nodes = graph.nodes();
        int maxNodeCount = Math.min(nodes.size(), 50);
        for (int i = 0; i < maxNodeCount; i++) {
            if (i > 0) sb.append(",");
            ProvenanceNode node = nodes.get(i);
            int x = BlockPosUtil.unpackX(node.packedPos());
            int y = BlockPosUtil.unpackY(node.packedPos());
            int z = BlockPosUtil.unpackZ(node.packedPos());

            sb.append("{");
            sb.append("\"stepIndex\":").append(node.stepIndex()).append(",");
            sb.append("\"sequenceId\":").append(node.sequenceId()).append(",");
            sb.append("\"timestampMs\":").append(node.timestampMs()).append(",");
            sb.append("\"actionType\":\"").append(node.actionType().name()).append("\",");
            sb.append("\"actorType\":\"").append(node.actorType().name()).append("\",");
            if (node.actorUuid() != null) {
                sb.append("\"actorUuid\":\"").append(node.actorUuid()).append("\",");
            } else {
                sb.append("\"actorUuid\":null,");
            }
            sb.append("\"actorName\":\"").append(escapeJson(node.actorName())).append("\",");
            sb.append("\"dimension\":\"").append(escapeJson(node.dimension())).append("\",");
            sb.append("\"x\":").append(x).append(",");
            sb.append("\"y\":").append(y).append(",");
            sb.append("\"z\":").append(z).append(",");
            sb.append("\"itemId\":\"").append(escapeJson(node.itemId())).append("\",");
            sb.append("\"deltaQuantity\":").append(node.deltaQuantity()).append(",");
            sb.append("\"confidence\":\"").append(node.confidence().name()).append("\",");
            sb.append("\"notes\":\"").append(escapeJson(node.notes())).append("\"");
            sb.append("}");
        }
        sb.append("],");

        sb.append("\"edges\":[");
        List<ProvenanceEdge> edges = graph.edges();
        int edgeWritten = 0;
        for (int i = 0; i < edges.size(); i++) {
            ProvenanceEdge edge = edges.get(i);
            if (edge.from().stepIndex() >= maxNodeCount || edge.to().stepIndex() >= maxNodeCount) {
                continue;
            }
            if (edgeWritten > 0) sb.append(",");
            sb.append("{");
            sb.append("\"fromIndex\":").append(edge.from().stepIndex()).append(",");
            sb.append("\"toIndex\":").append(edge.to().stepIndex()).append(",");
            sb.append("\"timeDeltaMs\":").append(edge.timeDeltaMs()).append(",");
            sb.append("\"confidence\":\"").append(edge.confidence().name()).append("\",");
            sb.append("\"transitionType\":\"").append(escapeJson(edge.transitionType())).append("\"");
            sb.append("}");
            edgeWritten++;
        }
        sb.append("]");

        sb.append("}");
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

    private static Integer parseCoordinateParam(Map<String, String> params, String key) throws IllegalArgumentException {
        String val = params.get(key);
        if (val == null || val.isBlank()) return null;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid numeric value for coordinate '" + key + "': " + val);
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
