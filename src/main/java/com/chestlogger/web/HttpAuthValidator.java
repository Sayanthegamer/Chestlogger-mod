package com.chestlogger.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Validates authentication tokens and applies CORS headers for HTTP requests.
 */
public final class HttpAuthValidator {
    private HttpAuthValidator() {}

    public static boolean validate(HttpExchange exchange, WebConfig config) throws IOException {
        // Apply CORS headers
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", config.getAllowedOrigins());
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-ChestLogger-Auth, Authorization");

        // Allow pre-flight OPTIONS requests
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return false;
        }

        String expectedToken = config.getSecretToken();
        if (expectedToken == null || expectedToken.isBlank()) {
            return true; // No token configured
        }

        // 1. Check X-ChestLogger-Auth header
        String authHeader = exchange.getRequestHeaders().getFirst("X-ChestLogger-Auth");
        if (authHeader != null && authHeader.trim().equals(expectedToken)) {
            return true;
        }

        // 2. Check Authorization: Bearer <token>
        String bearerHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (bearerHeader != null && bearerHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = bearerHeader.substring(7).trim();
            if (token.equals(expectedToken)) {
                return true;
            }
        }

        // 3. Check query param: ?token=<token>
        String query = exchange.getRequestURI().getQuery();
        if (query != null && !query.isBlank()) {
            for (String param : query.split("&")) {
                int eqIdx = param.indexOf('=');
                if (eqIdx > 0) {
                    String key = param.substring(0, eqIdx);
                    String val = param.substring(eqIdx + 1);
                    if ("token".equalsIgnoreCase(key) && val.equals(expectedToken)) {
                        return true;
                    }
                }
            }
        }

        // Unauthorized
        sendUnauthorized(exchange);
        return false;
    }

    public static void sendUnauthorized(HttpExchange exchange) throws IOException {
        byte[] response = "{\"error\":\"Unauthorized\",\"message\":\"Missing or invalid X-ChestLogger-Auth token\"}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(401, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }
}
