package com.chestlogger.config;

import com.chestlogger.alert.AlertConfig;
import com.chestlogger.web.WebConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Thread-safe configuration manager orchestrating in-memory settings, atomic JSON disk
 * persistence, and reactive hot-reload listeners across all ChestLogger subsystems.
 */
public class ConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("ChestLogger-Config");

    public static final String ALERT_CONFIG_FILENAME = "chestlogger_alerts.json";
    public static final String WEB_CONFIG_FILENAME = "chestlogger_web.json";

    private final Path dataDirectory;
    private final Path alertConfigFile;
    private final Path webConfigFile;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile AlertConfig alertConfig;
    private volatile WebConfig webConfig;
    private volatile boolean actionBarNoticeEnabled = true;
    private volatile boolean inGameChatAlertEnabled = true;
    private volatile int maxOwnerAlertDistance = 100;

    private final List<Consumer<AlertConfig>> alertListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<WebConfig>> webListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> reloadListeners = new CopyOnWriteArrayList<>();

    public ConfigManager(Path dataDirectory) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory cannot be null");
        this.alertConfigFile = dataDirectory.resolve(ALERT_CONFIG_FILENAME);
        this.webConfigFile = dataDirectory.resolve(WEB_CONFIG_FILENAME);

        initDirectoriesAndLoad();
    }

    private void initDirectoriesAndLoad() {
        lock.lock();
        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            // Load or initialize AlertConfig
            if (Files.exists(alertConfigFile)) {
                try {
                    String json = Files.readString(alertConfigFile, StandardCharsets.UTF_8);
                    this.alertConfig = AlertConfig.fromJson(json);
                } catch (Exception e) {
                    LOGGER.warn("Failed reading alerts config, falling back to defaults: {}", e.getMessage());
                    this.alertConfig = AlertConfig.defaults();
                    saveAlertConfigAtomic();
                }
            } else {
                this.alertConfig = AlertConfig.defaults();
                saveAlertConfigAtomic();
            }

            // Load or initialize WebConfig
            if (Files.exists(webConfigFile)) {
                try {
                    this.webConfig = WebConfig.load(webConfigFile.toFile());
                } catch (Exception e) {
                    LOGGER.warn("Failed reading web config, falling back to defaults: {}", e.getMessage());
                    this.webConfig = new WebConfig();
                    this.webConfig.save(webConfigFile.toFile());
                }
            } else {
                this.webConfig = new WebConfig();
                this.webConfig.save(webConfigFile.toFile());
            }
        } catch (IOException e) {
            LOGGER.error("Failed initializing configuration files in {}: {}", dataDirectory, e.getMessage(), e);
            if (this.alertConfig == null) this.alertConfig = AlertConfig.defaults();
            if (this.webConfig == null) this.webConfig = new WebConfig();
        } finally {
            lock.unlock();
        }
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public AlertConfig getAlertConfig() {
        return alertConfig;
    }

    public WebConfig getWebConfig() {
        return webConfig;
    }

    public boolean isActionBarNoticeEnabled() {
        return actionBarNoticeEnabled;
    }

    public boolean isInGameChatAlertEnabled() {
        return inGameChatAlertEnabled;
    }

    public int getMaxOwnerAlertDistance() {
        return maxOwnerAlertDistance;
    }

    // --- Listener Registration ---

    public void addAlertConfigListener(Consumer<AlertConfig> listener) {
        if (listener != null) {
            alertListeners.add(listener);
        }
    }

    public void removeAlertConfigListener(Consumer<AlertConfig> listener) {
        alertListeners.remove(listener);
    }

    public void addWebConfigListener(Consumer<WebConfig> listener) {
        if (listener != null) {
            webListeners.add(listener);
        }
    }

    public void removeWebConfigListener(Consumer<WebConfig> listener) {
        webListeners.remove(listener);
    }

    public void addReloadListener(Runnable listener) {
        if (listener != null) {
            reloadListeners.add(listener);
        }
    }

    public void removeReloadListener(Runnable listener) {
        reloadListeners.remove(listener);
    }

    private void notifyAlertListeners(AlertConfig config) {
        for (Consumer<AlertConfig> listener : alertListeners) {
            try {
                listener.accept(config);
            } catch (Throwable t) {
                LOGGER.error("Error in AlertConfig listener: {}", t.getMessage(), t);
            }
        }
    }

    private void notifyWebListeners(WebConfig config) {
        for (Consumer<WebConfig> listener : webListeners) {
            try {
                listener.accept(config);
            } catch (Throwable t) {
                LOGGER.error("Error in WebConfig listener: {}", t.getMessage(), t);
            }
        }
    }

    private void notifyReloadListeners() {
        for (Runnable listener : reloadListeners) {
            try {
                listener.run();
            } catch (Throwable t) {
                LOGGER.error("Error in Reload listener: {}", t.getMessage(), t);
            }
        }
    }

    // --- Configuration Mutations ---

    public void updateAlertConfig(AlertConfig newConfig) {
        if (newConfig == null) return;
        lock.lock();
        try {
            this.alertConfig = newConfig;
            saveAlertConfigAtomic();
        } finally {
            lock.unlock();
        }
        notifyAlertListeners(this.alertConfig);
    }

    public void updateWebConfig(WebConfig newConfig) {
        if (newConfig == null) return;
        lock.lock();
        try {
            this.webConfig = newConfig;
            saveWebConfigAtomic();
        } finally {
            lock.unlock();
        }
        notifyWebListeners(this.webConfig);
    }

    public void updateWebConfig(Consumer<WebConfig> modifier) {
        if (modifier == null) return;
        lock.lock();
        try {
            modifier.accept(this.webConfig);
            saveWebConfigAtomic();
        } finally {
            lock.unlock();
        }
        notifyWebListeners(this.webConfig);
    }

    public void setDiscordWebhookUrl(String url) {
        lock.lock();
        try {
            this.alertConfig = new AlertConfig(
                    alertConfig.enabled(),
                    url != null ? url.trim() : "",
                    alertConfig.botUsername(),
                    alertConfig.avatarUrl(),
                    alertConfig.quantityThreshold(),
                    alertConfig.valuableItems(),
                    alertConfig.alertOnContainerBreak(),
                    alertConfig.alertOnValuableTheft(),
                    alertConfig.rateLimitPerMinute()
            );
            saveAlertConfigAtomic();
        } finally {
            lock.unlock();
        }
        notifyAlertListeners(this.alertConfig);
    }

    public void setAlertCooldownSeconds(int seconds) {
        lock.lock();
        try {
            this.alertConfig = new AlertConfig(
                    alertConfig.enabled(),
                    alertConfig.webhookUrl(),
                    alertConfig.botUsername(),
                    alertConfig.avatarUrl(),
                    alertConfig.quantityThreshold(),
                    alertConfig.valuableItems(),
                    alertConfig.alertOnContainerBreak(),
                    alertConfig.alertOnValuableTheft(),
                    Math.max(1, seconds)
            );
            saveAlertConfigAtomic();
        } finally {
            lock.unlock();
        }
        notifyAlertListeners(this.alertConfig);
    }

    public void addTrackedItem(String itemId) {
        if (itemId == null || itemId.isBlank()) return;
        lock.lock();
        try {
            Set<String> items = new HashSet<>(alertConfig.valuableItems());
            items.add(itemId.trim().toLowerCase(Locale.ROOT));
            this.alertConfig = new AlertConfig(
                    alertConfig.enabled(),
                    alertConfig.webhookUrl(),
                    alertConfig.botUsername(),
                    alertConfig.avatarUrl(),
                    alertConfig.quantityThreshold(),
                    Collections.unmodifiableSet(items),
                    alertConfig.alertOnContainerBreak(),
                    alertConfig.alertOnValuableTheft(),
                    alertConfig.rateLimitPerMinute()
            );
            saveAlertConfigAtomic();
        } finally {
            lock.unlock();
        }
        notifyAlertListeners(this.alertConfig);
    }

    public void removeTrackedItem(String itemId) {
        if (itemId == null || itemId.isBlank()) return;
        lock.lock();
        try {
            Set<String> items = new HashSet<>(alertConfig.valuableItems());
            items.remove(itemId.trim().toLowerCase(Locale.ROOT));
            this.alertConfig = new AlertConfig(
                    alertConfig.enabled(),
                    alertConfig.webhookUrl(),
                    alertConfig.botUsername(),
                    alertConfig.avatarUrl(),
                    alertConfig.quantityThreshold(),
                    Collections.unmodifiableSet(items),
                    alertConfig.alertOnContainerBreak(),
                    alertConfig.alertOnValuableTheft(),
                    alertConfig.rateLimitPerMinute()
            );
            saveAlertConfigAtomic();
        } finally {
            lock.unlock();
        }
        notifyAlertListeners(this.alertConfig);
    }

    public void setTrackedItems(Set<String> items) {
        lock.lock();
        try {
            Set<String> normalized = new HashSet<>();
            if (items != null) {
                for (String i : items) {
                    if (i != null && !i.isBlank()) {
                        normalized.add(i.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
            this.alertConfig = new AlertConfig(
                    alertConfig.enabled(),
                    alertConfig.webhookUrl(),
                    alertConfig.botUsername(),
                    alertConfig.avatarUrl(),
                    alertConfig.quantityThreshold(),
                    Collections.unmodifiableSet(normalized),
                    alertConfig.alertOnContainerBreak(),
                    alertConfig.alertOnValuableTheft(),
                    alertConfig.rateLimitPerMinute()
            );
            saveAlertConfigAtomic();
        } finally {
            lock.unlock();
        }
        notifyAlertListeners(this.alertConfig);
    }

    public void setActionBarNoticeEnabled(boolean enabled) {
        this.actionBarNoticeEnabled = enabled;
    }

    public void setInGameChatAlertEnabled(boolean enabled) {
        this.inGameChatAlertEnabled = enabled;
    }

    public void setMaxOwnerAlertDistance(int distance) {
        this.maxOwnerAlertDistance = Math.max(0, distance);
    }

    public void setWebEnabled(boolean enabled) {
        updateWebConfig(web -> web.setEnabled(enabled));
    }

    public void setWebPort(int port) {
        updateWebConfig(web -> web.setPort(port));
    }

    public void saveAll() {
        lock.lock();
        try {
            saveAlertConfigAtomic();
            saveWebConfigAtomic();
        } finally {
            lock.unlock();
        }
    }

    public void reloadFromDisk() {
        lock.lock();
        try {
            if (Files.exists(alertConfigFile)) {
                try {
                    String json = Files.readString(alertConfigFile, StandardCharsets.UTF_8);
                    this.alertConfig = AlertConfig.fromJson(json);
                } catch (Exception e) {
                    LOGGER.warn("Failed reloading alerts config from disk: {}", e.getMessage());
                }
            }

            if (Files.exists(webConfigFile)) {
                try {
                    this.webConfig = WebConfig.load(webConfigFile.toFile());
                } catch (Exception e) {
                    LOGGER.warn("Failed reloading web config from disk: {}", e.getMessage());
                }
            }
        } finally {
            lock.unlock();
        }

        notifyReloadListeners();
        notifyAlertListeners(this.alertConfig);
        notifyWebListeners(this.webConfig);
    }

    private void saveAlertConfigAtomic() {
        if (alertConfig == null) return;
        Path tempFile = dataDirectory.resolve(ALERT_CONFIG_FILENAME + ".tmp");
        try {
            String json = alertConfig.toJson();
            Files.writeString(tempFile, json, StandardCharsets.UTF_8);
            try {
                Files.move(tempFile, alertConfigFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                Files.move(tempFile, alertConfigFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("Failed saving {} atomically: {}", alertConfigFile, e.getMessage(), e);
            try {
                Files.deleteIfExists(tempFile);
            } catch (Exception ignored) {}
        }
    }

    private void saveWebConfigAtomic() {
        if (webConfig == null) return;
        Path tempFile = dataDirectory.resolve(WEB_CONFIG_FILENAME + ".tmp");
        try {
            webConfig.save(tempFile.toFile());
            try {
                Files.move(tempFile, webConfigFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                Files.move(tempFile, webConfigFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("Failed saving {} atomically: {}", webConfigFile, e.getMessage(), e);
            try {
                Files.deleteIfExists(tempFile);
            } catch (Exception ignored) {}
        }
    }
}
