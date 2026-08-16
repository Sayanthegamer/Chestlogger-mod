package com.chestlogger.web;

import com.chestlogger.ChestLoggerMod;
import com.chestlogger.event.TransactionEventQueue;
import com.chestlogger.index.PersistentIndexManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Handles GET /api/v1/stats.
 * Returns server queue metrics, index size, and uptime in JSON format.
 */
public class StatsHttpHandler implements HttpHandler {
    private final WebConfig config;
    private final Supplier<TransactionEventQueue> queueSupplier;
    private final Supplier<PersistentIndexManager> indexManagerSupplier;
    private final long serverStartTimeMs;

    public StatsHttpHandler(WebConfig config) {
        this(
                config,
                ChestLoggerMod::getEventQueue,
                ChestLoggerMod::getIndexManager,
                System.currentTimeMillis()
        );
    }

    public StatsHttpHandler(
            WebConfig config,
            Supplier<TransactionEventQueue> queueSupplier,
            Supplier<PersistentIndexManager> indexManagerSupplier,
            long serverStartTimeMs
    ) {
        this.config = config != null ? config : new WebConfig();
        this.queueSupplier = queueSupplier != null ? queueSupplier : ChestLoggerMod::getEventQueue;
        this.indexManagerSupplier = indexManagerSupplier != null ? indexManagerSupplier : ChestLoggerMod::getIndexManager;
        this.serverStartTimeMs = serverStartTimeMs;
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

        TransactionEventQueue queue = queueSupplier.get();
        PersistentIndexManager indexManager = indexManagerSupplier.get();

        int depth = (queue != null) ? queue.getDepth() : 0;
        int capacity = (queue != null) ? queue.getCapacity() : 0;
        long enqueued = (queue != null) ? queue.getEnqueuedCount() : 0L;
        long dropped = (queue != null) ? queue.getDroppedCount() : 0L;
        long drained = (queue != null) ? queue.getDrainedCount() : 0L;

        int indexSize = (indexManager != null) ? indexManager.size() : 0;
        long uptimeMs = Math.max(0L, System.currentTimeMillis() - serverStartTimeMs);

        String json = "{"
                + "\"queue\":{"
                + "\"depth\":" + depth + ","
                + "\"capacity\":" + capacity + ","
                + "\"enqueued\":" + enqueued + ","
                + "\"dropped\":" + dropped + ","
                + "\"drained\":" + drained
                + "},"
                + "\"index\":{"
                + "\"size\":" + indexSize
                + "},"
                + "\"uptimeMs\":" + uptimeMs
                + "}";

        byte[] responseBytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] bytes = ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
