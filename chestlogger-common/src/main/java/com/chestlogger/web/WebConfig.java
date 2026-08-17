package com.chestlogger.web;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Configuration model for the embedded web admin dashboard.
 *
 * Enforces strict security defaults:
 * 1. Disabled by default (enabled: false).
 * 2. Localhost binding only by default (host: 127.0.0.1).
 * 3. Auto-generated cryptographically secure 32-character hex secretToken.
 */
public class WebConfig {
    private static final SecureRandom RANDOM = new SecureRandom();

    private boolean enabled = false;
    private String host = "127.0.0.1";
    private int port = 8080;
    private String secretToken;
    private String allowedOrigins = "*";
    private int maxConnections = 20;
    private int maxFailedAuthAttempts = 5;
    private long authLockoutDurationMs = 60_000L;

    public WebConfig() {
        this.secretToken = generateRandomToken();
    }

    public WebConfig(boolean enabled, String host, int port, String secretToken, String allowedOrigins, int maxConnections) {
        this(enabled, host, port, secretToken, allowedOrigins, maxConnections, 5, 60_000L);
    }

    public WebConfig(boolean enabled, String host, int port, String secretToken, String allowedOrigins, int maxConnections, int maxFailedAuthAttempts, long authLockoutDurationMs) {
        this.enabled = enabled;
        this.host = (host != null && !host.isBlank()) ? host.trim() : "127.0.0.1";
        this.port = (port > 0 && port <= 65535) ? port : 8080;
        this.secretToken = (secretToken != null && !secretToken.isBlank()) ? secretToken.trim() : generateRandomToken();
        this.allowedOrigins = (allowedOrigins != null && !allowedOrigins.isBlank()) ? allowedOrigins.trim() : "*";
        this.maxConnections = (maxConnections > 0) ? maxConnections : 20;
        this.maxFailedAuthAttempts = (maxFailedAuthAttempts > 0) ? maxFailedAuthAttempts : 5;
        this.authLockoutDurationMs = (authLockoutDurationMs > 0) ? authLockoutDurationMs : 60_000L;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getSecretToken() {
        return secretToken;
    }

    public void setSecretToken(String secretToken) {
        this.secretToken = secretToken;
    }

    public String getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getMaxFailedAuthAttempts() {
        return maxFailedAuthAttempts;
    }

    public void setMaxFailedAuthAttempts(int maxFailedAuthAttempts) {
        this.maxFailedAuthAttempts = (maxFailedAuthAttempts > 0) ? maxFailedAuthAttempts : 5;
    }

    public long getAuthLockoutDurationMs() {
        return authLockoutDurationMs;
    }

    public void setAuthLockoutDurationMs(long authLockoutDurationMs) {
        this.authLockoutDurationMs = (authLockoutDurationMs > 0) ? authLockoutDurationMs : 60_000L;
    }

    public static String generateRandomToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static WebConfig load(File file) {
        if (file == null || !file.exists()) {
            WebConfig config = new WebConfig();
            if (file != null) {
                try {
                    config.save(file);
                } catch (Exception ignored) {}
            }
            return config;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line).append('\n');
            }
            return parseJson(json.toString());
        } catch (Exception e) {
            WebConfig fallback = new WebConfig();
            try {
                fallback.save(file);
            } catch (Exception ignored) {}
            return fallback;
        }
    }

    public void save(File file) throws IOException {
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        String json = toJson();
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write(json);
        }
    }

    public String toJson() {
        return "{\n" +
                "  \"enabled\": " + enabled + ",\n" +
                "  \"host\": \"" + escape(host) + "\",\n" +
                "  \"port\": " + port + ",\n" +
                "  \"secretToken\": \"" + escape(secretToken) + "\",\n" +
                "  \"allowedOrigins\": \"" + escape(allowedOrigins) + "\",\n" +
                "  \"maxConnections\": " + maxConnections + ",\n" +
                "  \"maxFailedAuthAttempts\": " + maxFailedAuthAttempts + ",\n" +
                "  \"authLockoutDurationMs\": " + authLockoutDurationMs + "\n" +
                "}";
    }

    private static WebConfig parseJson(String json) {
        boolean enabled = extractBoolean(json, "enabled", false);
        String host = extractString(json, "host", "127.0.0.1");
        int port = extractInt(json, "port", 8080);
        String secretToken = extractString(json, "secretToken", generateRandomToken());
        String allowedOrigins = extractString(json, "allowedOrigins", "*");
        int maxConnections = extractInt(json, "maxConnections", 20);
        int maxFailedAuthAttempts = extractInt(json, "maxFailedAuthAttempts", 5);
        long authLockoutDurationMs = extractLong(json, "authLockoutDurationMs", 60_000L);

        return new WebConfig(enabled, host, port, secretToken, allowedOrigins, maxConnections, maxFailedAuthAttempts, authLockoutDurationMs);
    }

    private static long extractLong(String json, String key, long defaultValue) {
        String pattern = "\"" + key + "\"\\s*:\\s*(\\d+)";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static String extractString(String json, String key, String defaultValue) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return defaultValue;
    }

    private static boolean extractBoolean(String json, String key, boolean defaultValue) {
        String pattern = "\"" + key + "\"\\s*:\\s*(true|false)";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(json);
        if (m.find()) {
            return Boolean.parseBoolean(m.group(1));
        }
        return defaultValue;
    }

    private static int extractInt(String json, String key, int defaultValue) {
        String pattern = "\"" + key + "\"\\s*:\\s*(\\d+)";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
