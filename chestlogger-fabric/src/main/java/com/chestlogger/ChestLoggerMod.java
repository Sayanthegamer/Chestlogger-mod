package com.chestlogger;

import com.chestlogger.container.ContainerTracker;
import com.chestlogger.event.TransactionEventQueue;
import com.chestlogger.index.PersistentIndexManager;
import com.chestlogger.lifecycle.ChestLoggerLifecycleManager;
import com.chestlogger.network.ChestLogNetworking;
import com.chestlogger.query.QueryEngine;
import com.chestlogger.query.QuerySessionManager;
import com.chestlogger.rollback.RollbackEngine;
import com.chestlogger.storage.BlockCompressor;
import com.chestlogger.storage.LZ4BlockCompressor;
import com.chestlogger.storage.StorageProfile;
import com.chestlogger.web.EmbeddedHttpServer;
import com.chestlogger.web.WebConfig;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Main mod entrypoint for ChestLogger on Fabric (Minecraft 26.2).
 */
public class ChestLoggerMod implements ModInitializer {
    public static final String MOD_ID = "chestlogger";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static TransactionEventQueue eventQueue;
    private static ContainerTracker tracker;
    private static BlockCompressor compressor;
    private static StorageProfile profile;
    private static RollbackEngine rollbackEngine;
    private static ChestLoggerLifecycleManager lifecycleManager;
    private static QuerySessionManager sessionManager = new QuerySessionManager();
    private static WebConfig webConfig;
    private static EmbeddedHttpServer httpServer;
    private static com.chestlogger.inspect.InspectModeManager inspectModeManager = new com.chestlogger.inspect.InspectModeManager();

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing ChestLogger for Minecraft 26.2...");
        eventQueue = new TransactionEventQueue(65536);
        tracker = new ContainerTracker(eventQueue);
        compressor = new LZ4BlockCompressor();
        profile = StorageProfile.BALANCED;
        rollbackEngine = new RollbackEngine();

        // Load Web Config (disabled by default, 127.0.0.1 binding)
        File configFile = new File("config/chestlogger_web.json");
        webConfig = WebConfig.load(configFile);
        httpServer = new EmbeddedHttpServer(
                webConfig,
                () -> eventQueue,
                ChestLoggerMod::getIndexManager,
                ChestLoggerMod::getQueryEngine,
                ChestLoggerMod::getSessionManager,
                () -> null,
                ChestLoggerMod::getTrustManager,
                ChestLoggerMod::getClaimManager
        );

        ChestLogNetworking.init();
        lifecycleManager = new ChestLoggerLifecycleManager(eventQueue, compressor, profile);
        ChestLoggerLifecycleManager.registerServerEvents(lifecycleManager);
        com.chestlogger.inspect.FabricWandListener.register();
        com.chestlogger.command.ChestLoggerCommands.register();
    }

    public static ContainerTracker getTracker() {
        if (tracker == null) {
            eventQueue = new TransactionEventQueue(65536);
            tracker = new ContainerTracker(eventQueue);
        }
        return tracker;
    }

    public static TransactionEventQueue getEventQueue() {
        if (eventQueue == null) {
            getTracker();
        }
        return eventQueue;
    }

    public static PersistentIndexManager getIndexManager() {
        return lifecycleManager != null ? lifecycleManager.getIndexManager() : null;
    }

    public static QueryEngine getQueryEngine() {
        return lifecycleManager != null ? lifecycleManager.getQueryEngine() : null;
    }

    public static RollbackEngine getRollbackEngine() {
        if (rollbackEngine == null) {
            rollbackEngine = new RollbackEngine();
        }
        return rollbackEngine;
    }

    public static ChestLoggerLifecycleManager getLifecycleManager() {
        return lifecycleManager;
    }

    public static QuerySessionManager getSessionManager() {
        if (sessionManager == null) {
            sessionManager = new QuerySessionManager();
        }
        return sessionManager;
    }

    public static WebConfig getWebConfig() {
        if (webConfig == null) {
            webConfig = new WebConfig();
        }
        return webConfig;
    }

    public static EmbeddedHttpServer getHttpServer() {
        if (httpServer == null) {
            httpServer = new EmbeddedHttpServer(
                    getWebConfig(),
                    () -> eventQueue,
                    ChestLoggerMod::getIndexManager,
                    ChestLoggerMod::getQueryEngine,
                    ChestLoggerMod::getSessionManager
            );
        }
        return httpServer;
    }

    public static com.chestlogger.inspect.InspectModeManager getInspectModeManager() {
        if (inspectModeManager == null) {
            inspectModeManager = new com.chestlogger.inspect.InspectModeManager();
        }
        return inspectModeManager;
    }

    public static com.chestlogger.claim.ClaimManager getClaimManager() {
        return lifecycleManager != null ? lifecycleManager.getClaimManager() : null;
    }

    public static com.chestlogger.security.TrustManager getTrustManager() {
        return lifecycleManager != null ? lifecycleManager.getTrustManager() : null;
    }

    public static com.chestlogger.security.SmartTheftEvaluator getTheftEvaluator() {
        return lifecycleManager != null ? lifecycleManager.getTheftEvaluator() : null;
    }

    public static com.chestlogger.alert.FabricSecurityAlertBroadcaster getSecurityBroadcaster() {
        return lifecycleManager != null ? lifecycleManager.getSecurityBroadcaster() : null;
    }
}
