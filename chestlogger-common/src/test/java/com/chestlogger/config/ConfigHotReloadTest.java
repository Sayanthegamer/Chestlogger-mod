package com.chestlogger.config;

import com.chestlogger.alert.AlertConfig;
import com.chestlogger.web.WebConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ConfigHotReloadTest {

    @TempDir
    Path tempDir;

    private ConfigManager configManager;

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager(tempDir);
    }

    @Test
    @DisplayName("1. Instantiation loads default AlertConfig and WebConfig when files do not exist and writes them to disk")
    void testInstantiationWithDefaults() {
        assertThat(configManager.getDataDirectory()).isEqualTo(tempDir);

        AlertConfig alertConfig = configManager.getAlertConfig();
        assertThat(alertConfig).isNotNull();
        assertThat(alertConfig.enabled()).isFalse();
        assertThat(alertConfig.botUsername()).isEqualTo("ChestLogger Alerts");
        assertThat(alertConfig.quantityThreshold()).isEqualTo(64);
        assertThat(alertConfig.valuableItems()).contains("minecraft:diamond", "minecraft:netherite_ingot");

        WebConfig webConfig = configManager.getWebConfig();
        assertThat(webConfig).isNotNull();
        assertThat(webConfig.isEnabled()).isFalse();
        assertThat(webConfig.getHost()).isEqualTo("127.0.0.1");
        assertThat(webConfig.getPort()).isEqualTo(8080);
        assertThat(webConfig.getSecretToken()).isNotNull().hasSize(32);

        // Verify default config files were created on disk
        File alertFile = tempDir.resolve("chestlogger_alerts.json").toFile();
        File webFile = tempDir.resolve("chestlogger_web.json").toFile();
        assertThat(alertFile).exists().isFile();
        assertThat(webFile).exists().isFile();
    }

    @Test
    @DisplayName("2. Loading existing AlertConfig and WebConfig from disk preserves custom values")
    void testLoadExistingConfigsFromDisk(@TempDir Path customDir) throws IOException {
        String customAlertJson = """
                {
                  "enabled": true,
                  "webhookUrl": "https://discord.com/api/webhooks/123/initial",
                  "botUsername": "Custom Guard Bot",
                  "avatarUrl": "https://example.com/avatar.png",
                  "quantityThreshold": 16,
                  "alertOnContainerBreak": false,
                  "alertOnValuableTheft": true,
                  "rateLimitPerMinute": 15,
                  "valuableItems": ["minecraft:emerald", "minecraft:nether_star"]
                }
                """;
        Files.writeString(customDir.resolve("chestlogger_alerts.json"), customAlertJson, StandardCharsets.UTF_8);

        String customWebJson = """
                {
                  "enabled": true,
                  "host": "0.0.0.0",
                  "port": 9090,
                  "secretToken": "custom_secret_token_1234567890ab",
                  "allowedOrigins": "https://dashboard.example.com",
                  "maxConnections": 50,
                  "maxFailedAuthAttempts": 3,
                  "authLockoutDurationMs": 120000
                }
                """;
        Files.writeString(customDir.resolve("chestlogger_web.json"), customWebJson, StandardCharsets.UTF_8);

        ConfigManager customManager = new ConfigManager(customDir);

        AlertConfig loadedAlert = customManager.getAlertConfig();
        assertThat(loadedAlert.enabled()).isTrue();
        assertThat(loadedAlert.webhookUrl()).isEqualTo("https://discord.com/api/webhooks/123/initial");
        assertThat(loadedAlert.botUsername()).isEqualTo("Custom Guard Bot");
        assertThat(loadedAlert.avatarUrl()).isEqualTo("https://example.com/avatar.png");
        assertThat(loadedAlert.quantityThreshold()).isEqualTo(16);
        assertThat(loadedAlert.alertOnContainerBreak()).isFalse();
        assertThat(loadedAlert.valuableItems()).containsExactlyInAnyOrder("minecraft:emerald", "minecraft:nether_star");
        assertThat(loadedAlert.rateLimitPerMinute()).isEqualTo(15);

        WebConfig loadedWeb = customManager.getWebConfig();
        assertThat(loadedWeb.isEnabled()).isTrue();
        assertThat(loadedWeb.getHost()).isEqualTo("0.0.0.0");
        assertThat(loadedWeb.getPort()).isEqualTo(9090);
        assertThat(loadedWeb.getSecretToken()).isEqualTo("custom_secret_token_1234567890ab");
        assertThat(loadedWeb.getAllowedOrigins()).isEqualTo("https://dashboard.example.com");
        assertThat(loadedWeb.getMaxConnections()).isEqualTo(50);
        assertThat(loadedWeb.getMaxFailedAuthAttempts()).isEqualTo(3);
        assertThat(loadedWeb.getAuthLockoutDurationMs()).isEqualTo(120000L);
    }

    @Test
    @DisplayName("3. Atomic file writes ensure data integrity on disk without leaving temporary files")
    void testAtomicSavingAndDataIntegrity() throws IOException {
        configManager.setDiscordWebhookUrl("https://discord.com/api/webhooks/atomic/test");
        configManager.setWebPort(8888);
        configManager.saveAll();

        Path alertFilePath = tempDir.resolve("chestlogger_alerts.json");
        Path webFilePath = tempDir.resolve("chestlogger_web.json");

        assertThat(Files.exists(alertFilePath)).isTrue();
        assertThat(Files.exists(webFilePath)).isTrue();

        String alertContent = Files.readString(alertFilePath, StandardCharsets.UTF_8);
        String webContent = Files.readString(webFilePath, StandardCharsets.UTF_8);

        assertThat(alertContent).contains("https://discord.com/api/webhooks/atomic/test");
        assertThat(webContent).contains("8888");

        // Verify no stray .tmp files exist in data directory
        try (var stream = Files.list(tempDir)) {
            List<Path> tmpFiles = stream.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList();
            assertThat(tmpFiles).isEmpty();
        }
    }

    @Test
    @DisplayName("4. Listener registration triggers callbacks on AlertConfig, WebConfig updates, and reload")
    void testListenersTriggeredOnUpdates() {
        AtomicReference<AlertConfig> receivedAlertConfig = new AtomicReference<>();
        AtomicInteger alertNotificationCount = new AtomicInteger(0);
        Consumer<AlertConfig> alertListener = config -> {
            receivedAlertConfig.set(config);
            alertNotificationCount.incrementAndGet();
        };

        AtomicReference<WebConfig> receivedWebConfig = new AtomicReference<>();
        AtomicInteger webNotificationCount = new AtomicInteger(0);
        Consumer<WebConfig> webListener = config -> {
            receivedWebConfig.set(config);
            webNotificationCount.incrementAndGet();
        };

        AtomicInteger reloadCount = new AtomicInteger(0);
        Runnable reloadListener = reloadCount::incrementAndGet;

        configManager.addAlertConfigListener(alertListener);
        configManager.addWebConfigListener(webListener);
        configManager.addReloadListener(reloadListener);

        // Mutate alert config
        configManager.setDiscordWebhookUrl("https://discord.com/api/webhooks/listener/test");
        assertThat(alertNotificationCount.get()).isEqualTo(1);
        assertThat(receivedAlertConfig.get()).isNotNull();
        assertThat(receivedAlertConfig.get().webhookUrl()).isEqualTo("https://discord.com/api/webhooks/listener/test");

        // Mutate web config
        configManager.setWebPort(9191);
        assertThat(webNotificationCount.get()).isEqualTo(1);
        assertThat(receivedWebConfig.get()).isNotNull();
        assertThat(receivedWebConfig.get().getPort()).isEqualTo(9191);

        // Trigger reload
        configManager.reloadFromDisk();
        assertThat(reloadCount.get()).isEqualTo(1);
        assertThat(alertNotificationCount.get()).isGreaterThanOrEqualTo(2);
        assertThat(webNotificationCount.get()).isGreaterThanOrEqualTo(2);

        // Remove listeners and verify no further invocations
        configManager.removeAlertConfigListener(alertListener);
        configManager.removeWebConfigListener(webListener);
        configManager.removeReloadListener(reloadListener);

        configManager.setDiscordWebhookUrl("https://discord.com/api/webhooks/unregistered");
        configManager.setWebPort(9292);
        configManager.reloadFromDisk();

        assertThat(alertNotificationCount.get()).isEqualTo(2);
        assertThat(webNotificationCount.get()).isEqualTo(2);
        assertThat(reloadCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("5. Dynamic mutation helpers update AlertConfig fields and persist changes")
    void testAlertConfigDynamicMutationHelpers() {
        // Test setDiscordWebhookUrl
        configManager.setDiscordWebhookUrl("https://discord.com/api/webhooks/helpers/url");
        assertThat(configManager.getAlertConfig().webhookUrl()).isEqualTo("https://discord.com/api/webhooks/helpers/url");

        // Test setAlertCooldownSeconds (maps to rateLimitPerMinute / cooldown)
        configManager.setAlertCooldownSeconds(45);
        assertThat(configManager.getAlertConfig().rateLimitPerMinute()).isEqualTo(45);

        // Test addTrackedItem
        configManager.addTrackedItem("minecraft:nether_star");
        assertThat(configManager.getAlertConfig().valuableItems()).contains("minecraft:nether_star");

        // Test removeTrackedItem
        configManager.removeTrackedItem("minecraft:diamond");
        assertThat(configManager.getAlertConfig().valuableItems()).doesNotContain("minecraft:diamond");

        // Test setTrackedItems
        Set<String> customItems = Set.of("minecraft:elytra", "minecraft:beacon", "minecraft:dragon_egg");
        configManager.setTrackedItems(customItems);
        assertThat(configManager.getAlertConfig().valuableItems()).containsExactlyInAnyOrderElementsOf(customItems);

        // Test setActionBarNoticeEnabled
        configManager.setActionBarNoticeEnabled(false);
        assertThat(configManager.isActionBarNoticeEnabled()).isFalse();
        configManager.setActionBarNoticeEnabled(true);
        assertThat(configManager.isActionBarNoticeEnabled()).isTrue();

        // Test setInGameChatAlertEnabled
        configManager.setInGameChatAlertEnabled(false);
        assertThat(configManager.isInGameChatAlertEnabled()).isFalse();
        configManager.setInGameChatAlertEnabled(true);
        assertThat(configManager.isInGameChatAlertEnabled()).isTrue();

        // Test setMaxOwnerAlertDistance
        configManager.setMaxOwnerAlertDistance(150);
        assertThat(configManager.getMaxOwnerAlertDistance()).isEqualTo(150);

        // Test updateAlertConfig direct object / consumer
        AlertConfig replacement = new AlertConfig(
                true,
                "https://discord.com/api/webhooks/replaced",
                "Replaced Guard",
                "https://example.com/icon.png",
                10,
                Set.of("minecraft:ancient_debris"),
                true,
                true,
                60
        );
        configManager.updateAlertConfig(replacement);
        assertThat(configManager.getAlertConfig().botUsername()).isEqualTo("Replaced Guard");
        assertThat(configManager.getAlertConfig().valuableItems()).containsExactly("minecraft:ancient_debris");
        assertThat(configManager.getAlertConfig().quantityThreshold()).isEqualTo(10);
    }

    @Test
    @DisplayName("5b. Dynamic mutation helpers update WebConfig fields and persist changes")
    void testWebConfigDynamicMutationHelpers() {
        // Test setWebEnabled
        configManager.setWebEnabled(true);
        assertThat(configManager.getWebConfig().isEnabled()).isTrue();

        // Test setWebPort
        configManager.setWebPort(8443);
        assertThat(configManager.getWebConfig().getPort()).isEqualTo(8443);

        // Test updateWebConfig consumer
        configManager.updateWebConfig(web -> {
            web.setHost("192.168.1.100");
            web.setMaxConnections(100);
            web.setAllowedOrigins("http://localhost:3000");
        });

        assertThat(configManager.getWebConfig().getHost()).isEqualTo("192.168.1.100");
        assertThat(configManager.getWebConfig().getMaxConnections()).isEqualTo(100);
        assertThat(configManager.getWebConfig().getAllowedOrigins()).isEqualTo("http://localhost:3000");

        // Verify changes are persistent on disk
        ConfigManager reloadedManager = new ConfigManager(tempDir);
        assertThat(reloadedManager.getWebConfig().isEnabled()).isTrue();
        assertThat(reloadedManager.getWebConfig().getPort()).isEqualTo(8443);
        assertThat(reloadedManager.getWebConfig().getHost()).isEqualTo("192.168.1.100");
    }

    @Test
    @DisplayName("6. reloadFromDisk re-reads JSON files from disk and triggers listeners")
    void testReloadFromDisk() throws IOException {
        AtomicBoolean alertListenerTriggered = new AtomicBoolean(false);
        AtomicBoolean webListenerTriggered = new AtomicBoolean(false);
        AtomicBoolean reloadListenerTriggered = new AtomicBoolean(false);

        configManager.addAlertConfigListener(cfg -> alertListenerTriggered.set(true));
        configManager.addWebConfigListener(cfg -> webListenerTriggered.set(true));
        configManager.addReloadListener(() -> reloadListenerTriggered.set(true));

        // Overwrite disk files externally
        String externalAlertJson = """
                {
                  "enabled": true,
                  "webhookUrl": "https://discord.com/api/webhooks/external/reload",
                  "botUsername": "External Reloader",
                  "quantityThreshold": 99,
                  "rateLimitPerMinute": 10,
                  "valuableItems": ["minecraft:netherite_sword"]
                }
                """;
        Files.writeString(tempDir.resolve("chestlogger_alerts.json"), externalAlertJson, StandardCharsets.UTF_8);

        String externalWebJson = """
                {
                  "enabled": true,
                  "host": "10.0.0.1",
                  "port": 7070,
                  "secretToken": "external_reloaded_token_12345678",
                  "allowedOrigins": "*",
                  "maxConnections": 40
                }
                """;
        Files.writeString(tempDir.resolve("chestlogger_web.json"), externalWebJson, StandardCharsets.UTF_8);

        // Perform reload
        configManager.reloadFromDisk();

        assertThat(reloadListenerTriggered.get()).isTrue();
        assertThat(alertListenerTriggered.get()).isTrue();
        assertThat(webListenerTriggered.get()).isTrue();

        assertThat(configManager.getAlertConfig().webhookUrl()).isEqualTo("https://discord.com/api/webhooks/external/reload");
        assertThat(configManager.getAlertConfig().botUsername()).isEqualTo("External Reloader");
        assertThat(configManager.getAlertConfig().quantityThreshold()).isEqualTo(99);
        assertThat(configManager.getAlertConfig().valuableItems()).containsExactly("minecraft:netherite_sword");

        assertThat(configManager.getWebConfig().getHost()).isEqualTo("10.0.0.1");
        assertThat(configManager.getWebConfig().getPort()).isEqualTo(7070);
        assertThat(configManager.getWebConfig().getSecretToken()).isEqualTo("external_reloaded_token_12345678");
    }

    @Test
    @DisplayName("7. Concurrent listener registration, unregistration, and updates operate safely without race conditions")
    void testConcurrentOperationsThreadSafety() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Throwable> exceptions = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Consumer<AlertConfig> listener = cfg -> {};
                    Runnable reloadListener = () -> {};

                    for (int j = 0; j < operationsPerThread; j++) {
                        // Register listener
                        configManager.addAlertConfigListener(listener);
                        configManager.addReloadListener(reloadListener);

                        // Mutate configs
                        configManager.setDiscordWebhookUrl("https://discord.com/api/webhooks/thread/" + threadId + "/" + j);
                        configManager.setWebPort(8000 + (j % 100));
                        configManager.addTrackedItem("minecraft:item_" + threadId + "_" + j);
                        configManager.setActionBarNoticeEnabled(j % 2 == 0);
                        configManager.setInGameChatAlertEnabled(j % 2 != 0);

                        // Unregister listener
                        configManager.removeAlertConfigListener(listener);
                        configManager.removeReloadListener(reloadListener);

                        if (j % 10 == 0) {
                            configManager.reloadFromDisk();
                        }
                    }
                } catch (Throwable t) {
                    exceptions.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(exceptions).isEmpty();
        assertThat(configManager.getAlertConfig()).isNotNull();
        assertThat(configManager.getWebConfig()).isNotNull();
    }
}
