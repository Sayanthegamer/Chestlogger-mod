package com.chestlogger.alert;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.security.SecurityIncident;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asynchronous, non-blocking dispatcher evaluating transactions against security rules
 * and sending formatted Discord Webhook alerts with token-bucket rate limiting.
 */
public final class DiscordAlertDispatcher implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger("ChestLoggerAlerts");

    private final AlertConfig config;
    private final HttpClient httpClient;
    private final BlockingQueue<String> dispatchQueue;
    private final ExecutorService workerExecutor;
    private final ScheduledExecutorService rateLimiterExecutor;
    private final AtomicInteger availableTokens;
    private volatile boolean running;

    public DiscordAlertDispatcher(AlertConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.dispatchQueue = new LinkedBlockingQueue<>(500);
        this.availableTokens = new AtomicInteger(Math.max(1, config.rateLimitPerMinute()));
        this.running = true;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        this.workerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ChestLogger-Discord-Worker");
            t.setDaemon(true);
            return t;
        });

        this.rateLimiterExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ChestLogger-Alert-RateLimiter");
            t.setDaemon(true);
            return t;
        });

        if (config.enabled() && config.webhookUrl() != null && !config.webhookUrl().isBlank()) {
            startWorker();
            startTokenRefill();
        }
    }

    private void startWorker() {
        workerExecutor.submit(() -> {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    String payload = dispatchQueue.take();
                    sendWebhookPayload(payload);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error processing Discord alert queue: " + e.getMessage());
                }
            }
        });
    }

    private void startTokenRefill() {
        rateLimiterExecutor.scheduleAtFixedRate(() -> {
            availableTokens.set(Math.max(1, config.rateLimitPerMinute()));
        }, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * Evaluates and dispatches a security incident to Discord if alerts are enabled and rate limits permit.
     *
     * @param incident SecurityIncident to dispatch.
     */
    public void evaluateAndDispatch(SecurityIncident incident) {
        if (incident == null || !config.enabled() || !running) {
            return;
        }

        if (config.webhookUrl() == null || config.webhookUrl().isBlank()) {
            return;
        }

        // Only alert-worthy incidents should trigger Discord webhook
        if (!incident.classification().isAlertWorthy()) {
            return;
        }

        // Rate limit check
        int tokens = availableTokens.decrementAndGet();
        if (tokens < 0) {
            return;
        }

        String payload = DiscordEmbedBuilder.buildWebhookPayload(incident, config);
        dispatchQueue.offer(payload);
    }

    /**
     * Direct alias for evaluateAndDispatch(SecurityIncident).
     */
    public void dispatchIncident(SecurityIncident incident) {
        evaluateAndDispatch(incident);
    }

    /**
     * Legacy evaluation method for raw TransactionLogEntry.
     */
    public void evaluateAndDispatch(TransactionLogEntry entry) {
        if (!config.enabled() || !running) {
            return;
        }

        if (config.webhookUrl() == null || config.webhookUrl().isBlank()) {
            return;
        }

        if (!isSuspicious(entry, config)) {
            return;
        }

        // Rate limit check
        int tokens = availableTokens.decrementAndGet();
        if (tokens < 0) {
            // Rate limit exceeded for this minute
            return;
        }

        String payload = DiscordEmbedBuilder.buildWebhookPayload(entry, config);
        dispatchQueue.offer(payload);
    }

    private void sendWebhookPayload(String payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.webhookUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(t -> {
                        LOGGER.log(Level.FINE, "Failed to send Discord alert: " + t.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Invalid webhook request: " + e.getMessage());
        }
    }

    public static boolean isSuspicious(TransactionLogEntry entry, AlertConfig config) {
        if (entry == null || config == null || !config.enabled()) {
            return false;
        }

        // 1. Container Break Alert
        if (entry.actionType() == ActionType.CONTAINER_BREAK && config.alertOnContainerBreak()) {
            if (entry.deltas() != null && !entry.deltas().isEmpty()) {
                return true;
            }
        }

        if (entry.deltas() == null || entry.deltas().isEmpty()) {
            return false;
        }

        // 2. Valuable theft or mass extraction check
        for (SlotDelta delta : entry.deltas()) {
            if (delta.deltaQuantity() < 0) {
                int absQty = Math.abs(delta.deltaQuantity());

                // Check valuable items
                if (config.alertOnValuableTheft() && config.valuableItems() != null) {
                    if (config.valuableItems().contains(delta.itemId())) {
                        return true;
                    }
                }

                // Check mass extraction quantity threshold
                if (config.quantityThreshold() > 0 && absQty >= config.quantityThreshold()) {
                    return true;
                }
            }
        }

        return false;
    }

    public AlertConfig getConfig() {
        return config;
    }

    @Override
    public void close() {
        running = false;
        workerExecutor.shutdownNow();
        rateLimiterExecutor.shutdownNow();
    }
}
