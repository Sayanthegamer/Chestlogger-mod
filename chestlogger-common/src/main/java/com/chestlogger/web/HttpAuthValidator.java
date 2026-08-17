package com.chestlogger.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Validates authentication tokens, enforces IP-based rate limiting on failed authentication attempts,
 * and applies strict CORS origin validation for HTTP requests.
 */
public final class HttpAuthValidator {
    public static final int DEFAULT_MAX_FAILED_ATTEMPTS = 5;
    public static final long DEFAULT_LOCKOUT_DURATION_MS = 60_000L;
    public static final long DEFAULT_WINDOW_MS = 60_000L;

    private static final Map<String, ClientAttemptTracker> ATTEMPT_TRACKERS = new ConcurrentHashMap<>();

    private HttpAuthValidator() {}

    public static boolean validate(HttpExchange exchange, WebConfig config) throws IOException {
        if (config == null) {
            config = new WebConfig();
        }

        // Apply CORS headers and origin validation
        String originHeader = exchange.getRequestHeaders().getFirst("Origin");
        boolean originAllowed = isOriginAllowed(originHeader, config.getAllowedOrigins());

        if (originAllowed) {
            String originToSet = "*".equals(config.getAllowedOrigins().trim())
                    ? "*"
                    : (originHeader != null && !originHeader.isBlank() ? originHeader.trim() : config.getAllowedOrigins().trim());
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", originToSet);
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-ChestLogger-Auth, Authorization");
            if (!"*".equals(originToSet)) {
                exchange.getResponseHeaders().set("Vary", "Origin");
            }
        }

        // Allow pre-flight OPTIONS requests
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            if (!originAllowed) {
                exchange.sendResponseHeaders(403, -1);
                return false;
            }
            exchange.sendResponseHeaders(204, -1);
            return false;
        }

        String expectedToken = config.getSecretToken();
        if (expectedToken == null || expectedToken.isBlank()) {
            return true; // No token configured
        }

        String clientIp = extractClientIp(exchange);
        int maxAttempts = config.getMaxFailedAuthAttempts();
        long lockoutDurationMs = config.getAuthLockoutDurationMs();

        // 1. Check if client is currently rate-limited
        ClientAttemptTracker tracker = ATTEMPT_TRACKERS.computeIfAbsent(clientIp, k -> new ClientAttemptTracker());
        long now = System.currentTimeMillis();
        if (tracker.isLockedOut(now)) {
            sendTooManyRequests(exchange, tracker.getRemainingLockoutSeconds(now));
            return false;
        }

        // 2. Check X-ChestLogger-Auth header
        String authHeader = exchange.getRequestHeaders().getFirst("X-ChestLogger-Auth");
        if (authHeader != null && constantTimeEquals(authHeader.trim(), expectedToken)) {
            tracker.recordSuccess();
            return true;
        }

        // 3. Check Authorization: Bearer <token>
        String bearerHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (bearerHeader != null && bearerHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = bearerHeader.substring(7).trim();
            if (constantTimeEquals(token, expectedToken)) {
                tracker.recordSuccess();
                return true;
            }
        }

        // 4. Check query param: ?token=<token>
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null && !query.isBlank()) {
            for (String param : query.split("&")) {
                int eqIdx = param.indexOf('=');
                if (eqIdx > 0) {
                    try {
                        String key = URLDecoder.decode(param.substring(0, eqIdx), StandardCharsets.UTF_8);
                        String val = URLDecoder.decode(param.substring(eqIdx + 1), StandardCharsets.UTF_8);
                        if ("token".equalsIgnoreCase(key) && constantTimeEquals(val.trim(), expectedToken)) {
                            tracker.recordSuccess();
                            return true;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        // Authentication failed -> record failure & check lockout
        boolean locked = tracker.recordFailure(now, maxAttempts, lockoutDurationMs, DEFAULT_WINDOW_MS);
        if (locked) {
            sendTooManyRequests(exchange, tracker.getRemainingLockoutSeconds(System.currentTimeMillis()));
        } else {
            sendUnauthorized(exchange);
        }
        return false;
    }

    public static boolean isOriginAllowed(String origin, String allowedOriginsConfig) {
        if (allowedOriginsConfig == null || allowedOriginsConfig.isBlank() || "*".equals(allowedOriginsConfig.trim())) {
            return true;
        }
        if (origin == null || origin.isBlank()) {
            return true;
        }
        String trimmedOrigin = origin.trim();
        String[] allowedList = allowedOriginsConfig.split("[,;\\s]+");
        for (String allowed : allowedList) {
            if (allowed.trim().equalsIgnoreCase(trimmedOrigin) || "*".equals(allowed.trim())) {
                return true;
            }
        }
        return false;
    }

    public static String extractClientIp(HttpExchange exchange) {
        if (exchange == null) {
            return "127.0.0.1";
        }
        String xForwardedFor = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            int commaIdx = xForwardedFor.indexOf(',');
            String firstIp = (commaIdx > 0 ? xForwardedFor.substring(0, commaIdx) : xForwardedFor).trim();
            if (!firstIp.isEmpty()) {
                return firstIp;
            }
        }
        InetSocketAddress remoteAddress = exchange.getRemoteAddress();
        if (remoteAddress != null) {
            if (remoteAddress.getAddress() != null) {
                return remoteAddress.getAddress().getHostAddress();
            }
            return remoteAddress.getHostString();
        }
        return "127.0.0.1";
    }

    public static boolean isRateLimited(String clientIp) {
        if (clientIp == null) return false;
        ClientAttemptTracker tracker = ATTEMPT_TRACKERS.get(clientIp);
        return tracker != null && tracker.isLockedOut(System.currentTimeMillis());
    }

    public static int getFailedAttempts(String clientIp) {
        if (clientIp == null) return 0;
        ClientAttemptTracker tracker = ATTEMPT_TRACKERS.get(clientIp);
        return tracker != null ? tracker.failedAttempts.get() : 0;
    }

    public static long getRemainingLockoutSeconds(String clientIp) {
        if (clientIp == null) return 0L;
        ClientAttemptTracker tracker = ATTEMPT_TRACKERS.get(clientIp);
        return tracker != null ? tracker.getRemainingLockoutSeconds(System.currentTimeMillis()) : 0L;
    }

    public static void recordFailedAttempt(String clientIp, int maxAttempts, long lockoutDurationMs) {
        if (clientIp == null) return;
        ClientAttemptTracker tracker = ATTEMPT_TRACKERS.computeIfAbsent(clientIp, k -> new ClientAttemptTracker());
        tracker.recordFailure(System.currentTimeMillis(), maxAttempts, lockoutDurationMs, DEFAULT_WINDOW_MS);
    }

    public static void resetRateLimiter() {
        ATTEMPT_TRACKERS.clear();
    }

    public static void resetRateLimiter(String clientIp) {
        if (clientIp != null) {
            ATTEMPT_TRACKERS.remove(clientIp);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
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

    public static void sendTooManyRequests(HttpExchange exchange, long retryAfterSeconds) throws IOException {
        byte[] response = ("{\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Too many failed authentication attempts. Please retry after " + retryAfterSeconds + " seconds.\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Retry-After", String.valueOf(retryAfterSeconds));
        exchange.sendResponseHeaders(429, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    private static final class ClientAttemptTracker {
        private final AtomicInteger failedAttempts = new AtomicInteger(0);
        private volatile long firstFailedAttemptTimeMs = 0L;
        private volatile long lockedUntilMs = 0L;

        public boolean isLockedOut(long now) {
            return now < lockedUntilMs;
        }

        public long getRemainingLockoutSeconds(long now) {
            return Math.max(1L, (lockedUntilMs - now + 999L) / 1000L);
        }

        public synchronized boolean recordFailure(long now, int maxAttempts, long lockoutDurationMs, long windowMs) {
            if (now < lockedUntilMs) {
                return true;
            }
            if (firstFailedAttemptTimeMs == 0L || (now - firstFailedAttemptTimeMs) > windowMs) {
                firstFailedAttemptTimeMs = now;
                failedAttempts.set(1);
            } else {
                int attempts = failedAttempts.incrementAndGet();
                if (attempts >= maxAttempts) {
                    lockedUntilMs = now + lockoutDurationMs;
                    return true;
                }
            }
            return false;
        }

        public synchronized void recordSuccess() {
            failedAttempts.set(0);
            firstFailedAttemptTimeMs = 0L;
            lockedUntilMs = 0L;
        }
    }
}
