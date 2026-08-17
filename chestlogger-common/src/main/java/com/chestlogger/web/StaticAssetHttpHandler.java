package com.chestlogger.web;

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
import java.util.Locale;

/**
 * Serves static web dashboard assets (HTML, CSS, JS, images) embedded in the classpath.
 * Enforces strict path traversal controls, accurate MIME resolution, and cache headers.
 */
public class StaticAssetHttpHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("ChestLogger-Web-Static");

    public static final String DEFAULT_RESOURCE_BASE = "/assets/chestlogger/web";
    private final String resourceBase;

    public StaticAssetHttpHandler() {
        this(DEFAULT_RESOURCE_BASE);
    }

    public StaticAssetHttpHandler(String resourceBase) {
        String base = (resourceBase != null && !resourceBase.isBlank()) ? resourceBase.trim() : DEFAULT_RESOURCE_BASE;
        if (!base.startsWith("/")) {
            base = "/" + base;
        }
        while (base.endsWith("/") && base.length() > 1) {
            base = base.substring(0, base.length() - 1);
        }
        this.resourceBase = base;
    }

    public String getResourceBase() {
        return resourceBase;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }

        URI requestUri = exchange.getRequestURI();
        String rawPath = requestUri.getRawPath();
        if (rawPath == null || rawPath.isBlank()) {
            rawPath = "/";
        }

        // 1. Strict Security: Block raw null bytes, backslashes, or encoded null/backslash
        if (rawPath.contains("%00") || rawPath.indexOf('\0') >= 0 ||
            rawPath.contains("\\") || rawPath.toLowerCase(Locale.ROOT).contains("%5c")) {
            sendError(exchange, 403, "Forbidden");
            return;
        }

        // 2. Decode URL path
        String decodedPath;
        try {
            decodedPath = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            sendError(exchange, 400, "Bad Request");
            return;
        }

        // 3. Strict Security: Check decoded path for null bytes, backslashes, or path traversal segments
        if (decodedPath.indexOf('\0') >= 0 || decodedPath.contains("\\")) {
            sendError(exchange, 403, "Forbidden");
            return;
        }

        // Check for double-encoding traversal attempts (e.g. %252e%252e)
        try {
            String doubleDecoded = URLDecoder.decode(decodedPath, StandardCharsets.UTF_8);
            if (doubleDecoded.contains("..") || doubleDecoded.indexOf('\0') >= 0 || doubleDecoded.contains("\\")) {
                sendError(exchange, 403, "Forbidden");
                return;
            }
        } catch (Exception ignored) {
        }

        // Check path segments for ".."
        String[] segments = decodedPath.split("/+");
        for (String segment : segments) {
            if ("..".equals(segment)) {
                sendError(exchange, 403, "Forbidden");
                return;
            }
        }

        // Normalize path
        String normalizedPath = normalizePath(decodedPath);
        if (normalizedPath == null || normalizedPath.contains("..")) {
            sendError(exchange, 403, "Forbidden");
            return;
        }

        // Collapse multiple slashes
        normalizedPath = normalizedPath.replaceAll("/+", "/");

        // Map root / or /index.html to /index.html
        if (normalizedPath.equals("/") || normalizedPath.equalsIgnoreCase("/index.html")) {
            normalizedPath = "/index.html";
        } else if (normalizedPath.endsWith("/")) {
            normalizedPath = normalizedPath + "index.html";
        }

        String fullResourcePath = resourceBase + (normalizedPath.startsWith("/") ? normalizedPath : "/" + normalizedPath);

        // Security sanity check: Ensure fullResourcePath strictly stays inside resourceBase
        if (!fullResourcePath.startsWith(resourceBase + "/")) {
            sendError(exchange, 403, "Forbidden");
            return;
        }

        // Load resource from classpath
        byte[] content = loadResourceBytes(fullResourcePath);
        if (content == null) {
            sendError(exchange, 404, "404 Not Found: " + normalizedPath);
            return;
        }

        String mimeType = resolveMimeType(normalizedPath);
        exchange.getResponseHeaders().set("Content-Type", mimeType);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");

        if ("HEAD".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(200, -1);
        } else {
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content);
            }
        }
    }

    private String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        try {
            URI uri = URI.create("http://localhost" + (path.startsWith("/") ? path : "/" + path)).normalize();
            String p = uri.getPath();
            return p != null ? p : path;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] loadResourceBytes(String path) {
        String cleanPath = path.startsWith("/") ? path : "/" + path;
        String noSlash = cleanPath.substring(1);

        try (InputStream is = findResourceStream(cleanPath, noSlash)) {
            if (is == null) {
                return null;
            }
            return is.readAllBytes();
        } catch (IOException e) {
            LOGGER.warn("[ChestLogger] Failed to read static asset: {}", path, e);
            return null;
        }
    }

    private InputStream findResourceStream(String withSlash, String withoutSlash) {
        InputStream is = StaticAssetHttpHandler.class.getResourceAsStream(withSlash);
        if (is != null) {
            return is;
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl != null) {
            is = cl.getResourceAsStream(withoutSlash);
            if (is != null) {
                return is;
            }
        }
        is = StaticAssetHttpHandler.class.getClassLoader().getResourceAsStream(withoutSlash);
        if (is != null) {
            return is;
        }
        return ClassLoader.getSystemResourceAsStream(withoutSlash);
    }

    public static String resolveMimeType(String path) {
        if (path == null) {
            return "application/octet-stream";
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html; charset=utf-8";
        } else if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        } else if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
            return "application/javascript; charset=utf-8";
        } else if (lower.endsWith(".json")) {
            return "application/json";
        } else if (lower.endsWith(".png")) {
            return "image/png";
        } else if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        } else if (lower.endsWith(".ico")) {
            return "image/x-icon";
        } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (lower.endsWith(".gif")) {
            return "image/gif";
        } else if (lower.endsWith(".webp")) {
            return "image/webp";
        } else if (lower.endsWith(".woff")) {
            return "font/woff";
        } else if (lower.endsWith(".woff2")) {
            return "font/woff2";
        } else if (lower.endsWith(".ttf")) {
            return "font/ttf";
        } else if (lower.endsWith(".txt")) {
            return "text/plain; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
