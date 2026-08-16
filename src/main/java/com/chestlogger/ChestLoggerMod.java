package com.chestlogger;

import com.chestlogger.command.ChestLoggerCommands;
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
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing ChestLogger for Minecraft 26.2...");
        eventQueue = new TransactionEventQueue(65536);
        tracker = new ContainerTracker(eventQueue);
        compressor = new LZ4BlockCompressor();
        profile = StorageProfile.BALANCED;
        rollbackEngine = new RollbackEngine();

        ChestLogNetworking.init();
        lifecycleManager = new ChestLoggerLifecycleManager(eventQueue, compressor, profile);
        ChestLoggerLifecycleManager.registerServerEvents(lifecycleManager);
        ChestLoggerCommands.register();
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

    public static BlockCompressor getCompressor() {
        if (compressor == null) {
            compressor = new LZ4BlockCompressor();
        }
        return compressor;
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
}
