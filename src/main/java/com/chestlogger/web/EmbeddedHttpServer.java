package com.chestlogger.web;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Lightweight, zero-dependency embedded web server managing HTTP endpoints
 * for ChestLogger administration and browser dashboard.
 */
public class EmbeddedHttpServer {
    private static final Logger LOGGER = LoggerFactory.getLogger("ChestLogger-Web");

    private final WebConfig config;
    private HttpServer server;
    private ExecutorService executor;
    private volatile boolean running = false;

    private final Supplier<com.chestlogger.event.TransactionEventQueue> queueSupplier;
    private final Supplier<com.chestlogger.index.PersistentIndexManager> indexManagerSupplier;
    private final Supplier<com.chestlogger.query.QueryEngine> queryEngineSupplier;
    private final Supplier<com.chestlogger.query.QuerySessionManager> sessionManagerSupplier;

    public EmbeddedHttpServer(WebConfig config) {
        this(
                config,
                com.chestlogger.ChestLoggerMod::getEventQueue,
                com.chestlogger.ChestLoggerMod::getIndexManager,
                com.chestlogger.ChestLoggerMod::getQueryEngine,
                com.chestlogger.ChestLoggerMod::getSessionManager
        );
    }

    public EmbeddedHttpServer(
            WebConfig config,
            Supplier<com.chestlogger.event.TransactionEventQueue> queueSupplier,
            Supplier<com.chestlogger.index.PersistentIndexManager> indexManagerSupplier,
            Supplier<com.chestlogger.query.QueryEngine> queryEngineSupplier,
            Supplier<com.chestlogger.query.QuerySessionManager> sessionManagerSupplier
    ) {
        this.config = config != null ? config : new WebConfig();
        this.queueSupplier = queueSupplier != null ? queueSupplier : com.chestlogger.ChestLoggerMod::getEventQueue;
        this.indexManagerSupplier = indexManagerSupplier != null ? indexManagerSupplier : com.chestlogger.ChestLoggerMod::getIndexManager;
        this.queryEngineSupplier = queryEngineSupplier != null ? queryEngineSupplier : com.chestlogger.ChestLoggerMod::getQueryEngine;
        this.sessionManagerSupplier = sessionManagerSupplier != null ? sessionManagerSupplier : com.chestlogger.ChestLoggerMod::getSessionManager;
    }

    public synchronized void start() {
        if (running) {
            return;
        }

        if (!config.isEnabled()) {
            LOGGER.info("[ChestLogger] Web Admin Dashboard is disabled in config (enabled: false). Server will not bind to any port.");
            return;
        }

        try {
            InetSocketAddress address = new InetSocketAddress(config.getHost(), config.getPort());
            this.server = HttpServer.create(address, config.getMaxConnections());

            this.executor = Executors.newFixedThreadPool(Math.min(8, Runtime.getRuntime().availableProcessors()), r -> {
                Thread t = new Thread(r, "ChestLogger-HttpWorker");
                t.setDaemon(true);
                return t;
            });
            this.server.setExecutor(executor);

            registerDefaultEndpoints();

            this.server.start();
            this.running = true;
            LOGGER.info("[ChestLogger] Web Admin Dashboard listening on http://{}:{}", config.getHost(), config.getPort());
        } catch (IOException e) {
            LOGGER.error("[ChestLogger] Failed to start Web Admin Dashboard on {}:{} - {}", config.getHost(), config.getPort(), e.getMessage());
        }
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }

        LOGGER.info("[ChestLogger] Stopping Web Admin Dashboard...");
        if (server != null) {
            server.stop(1); // 1 second grace period
            server = null;
        }

        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executor = null;
        }

        running = false;
        LOGGER.info("[ChestLogger] Web Admin Dashboard stopped cleanly.");
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public WebConfig getConfig() {
        return config;
    }

    private final java.util.Map<String, HttpHandler> customContexts = new java.util.concurrent.ConcurrentHashMap<>();

    public synchronized void createContext(String path, HttpHandler handler) {
        if (path != null && handler != null) {
            customContexts.put(path, handler);
            if (server != null) {
                server.createContext(path, handler);
            }
        }
    }

    private void registerDefaultEndpoints() {
        // Register all pre-configured custom contexts first
        for (var entry : customContexts.entrySet()) {
            if (server != null) {
                server.createContext(entry.getKey(), entry.getValue());
            }
        }

        // Simple health endpoint (if not overridden)
        if (!customContexts.containsKey("/api/v1/health")) {
            createContext("/api/v1/health", exchange -> {
                if (!HttpAuthValidator.validate(exchange, config)) {
                    return;
                }
                byte[] response = "{\"status\":\"UP\",\"service\":\"ChestLogger\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, response.length);
                try (var os = exchange.getResponseBody()) {
                    os.write(response);
                }
            });
        }

        // REST API endpoints (if not overridden)
        if (!customContexts.containsKey("/api/v1/stats")) {
            createContext("/api/v1/stats", new StatsHttpHandler(config, queueSupplier, indexManagerSupplier, System.currentTimeMillis()));
        }
        if (!customContexts.containsKey("/api/v1/query")) {
            createContext("/api/v1/query", new QueryHttpHandler(config, queryEngineSupplier, sessionManagerSupplier));
        }
        if (!customContexts.containsKey("/api/v1/export")) {
            createContext("/api/v1/export", new ExportHttpHandler(config, queryEngineSupplier));
        }

        // Static asset handler for embedded web dashboard
        if (!customContexts.containsKey("/")) {
            createContext("/", new StaticAssetHttpHandler());
        }
    }
}
