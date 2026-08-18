package com.chestlogger.web;

import com.chestlogger.security.TrustManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/**
 * Handles GET, POST, and DELETE requests for /api/v1/trust.
 * Enables one-click player trust authorization from the Web Dashboard.
 */
public class TrustHttpHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("ChestLogger-TrustApi");

    private final WebConfig config;
    private final Supplier<TrustManager> trustManagerSupplier;

    public TrustHttpHandler(WebConfig config, Supplier<TrustManager> trustManagerSupplier) {
        this.config = config != null ? config : new WebConfig();
        this.trustManagerSupplier = trustManagerSupplier != null ? trustManagerSupplier : () -> null;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!HttpAuthValidator.validate(exchange, config)) {
            return;
        }

        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        switch (method) {
            case "GET" -> handleGet(exchange);
            case "POST" -> handlePost(exchange);
            case "DELETE" -> handleDelete(exchange);
            default -> sendError(exchange, 405, "Method Not Allowed");
        }
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        TrustManager trustManager = trustManagerSupplier.get();
        if (trustManager == null) {
            sendError(exchange, 503, "TrustManager is not available");
            return;
        }

        Map<String, String> params = parseQueryParams(exchange.getRequestURI());
        String ownerParam = params.get("owner");
        if (ownerParam == null || ownerParam.isBlank()) {
            ownerParam = params.get("ownerUuid");
        }

        if (ownerParam != null && !ownerParam.isBlank()) {
            try {
                UUID ownerUuid = UUID.fromString(ownerParam.trim());
                Set<UUID> trustedList = trustManager.getTrustList(ownerUuid);
                StringBuilder sb = new StringBuilder();
                sb.append("{\"ownerUuid\":\"").append(ownerUuid).append("\",\"trusted\":[");
                int count = 0;
                for (UUID t : trustedList) {
                    if (count++ > 0) sb.append(",");
                    sb.append("\"").append(t).append("\"");
                }
                sb.append("]}");
                sendJsonResponse(exchange, 200, sb.toString());
            } catch (IllegalArgumentException e) {
                sendError(exchange, 400, "Invalid owner UUID format: " + ownerParam);
            }
        } else {
            String json = String.format(Locale.ROOT, "{\"status\":\"ok\",\"ownerCount\":%d}", trustManager.getOwnerCount());
            sendJsonResponse(exchange, 200, json);
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        TrustManager trustManager = trustManagerSupplier.get();
        if (trustManager == null) {
            sendError(exchange, 503, "TrustManager is not available");
            return;
        }

        String requestBody;
        try (InputStream is = exchange.getRequestBody()) {
            requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        if (requestBody.isBlank()) {
            sendError(exchange, 400, "Missing request body");
            return;
        }

        UUID ownerUuid = extractUuidField(requestBody, "ownerUuid", "owner");
        UUID trustedUuid = extractUuidField(requestBody, "trustedUuid", "trusted");

        if (ownerUuid == null || trustedUuid == null) {
            sendError(exchange, 400, "Missing or invalid ownerUuid or trustedUuid in JSON payload");
            return;
        }

        trustManager.trust(ownerUuid, trustedUuid);
        try {
            trustManager.save();
        } catch (Exception e) {
            LOGGER.warn("Failed to persist trust database to disk: {}", e.getMessage());
        }

        String json = String.format(Locale.ROOT,
                "{\"status\":\"ok\",\"message\":\"Player trusted successfully\",\"ownerUuid\":\"%s\",\"trustedUuid\":\"%s\"}",
                ownerUuid, trustedUuid);
        sendJsonResponse(exchange, 200, json);
    }

    private void handleDelete(HttpExchange exchange) throws IOException {
        TrustManager trustManager = trustManagerSupplier.get();
        if (trustManager == null) {
            sendError(exchange, 503, "TrustManager is not available");
            return;
        }

        Map<String, String> params = parseQueryParams(exchange.getRequestURI());
        String ownerParam = params.get("owner");
        String trustedParam = params.get("trusted");

        if (ownerParam == null || trustedParam == null) {
            sendError(exchange, 400, "Query parameters 'owner' and 'trusted' are required for DELETE");
            return;
        }

        try {
            UUID ownerUuid = UUID.fromString(ownerParam.trim());
            UUID trustedUuid = UUID.fromString(trustedParam.trim());
            trustManager.untrust(ownerUuid, trustedUuid);
            try {
                trustManager.save();
            } catch (Exception e) {
                LOGGER.warn("Failed to persist trust database to disk: {}", e.getMessage());
            }
            sendJsonResponse(exchange, 200, "{\"status\":\"ok\",\"message\":\"Trust revoked successfully\"}");
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, "Invalid UUID format: " + e.getMessage());
        }
    }

    private UUID extractUuidField(String json, String... fieldNames) {
        for (String field : fieldNames) {
            String pattern = "\"" + field + "\"\\s*:\\s*\"([^\"]+)\"";
            java.util.regex.Pattern r = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = r.matcher(json);
            if (m.find()) {
                try {
                    return UUID.fromString(m.group(1).trim());
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return null;
    }

    private Map<String, String> parseQueryParams(URI uri) {
        Map<String, String> map = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return map;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                map.put(key, val);
            }
        }
        return map;
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        String json = String.format(Locale.ROOT, "{\"error\":\"%s\"}", message.replace("\"", "\\\""));
        sendJsonResponse(exchange, statusCode, json);
    }
}
