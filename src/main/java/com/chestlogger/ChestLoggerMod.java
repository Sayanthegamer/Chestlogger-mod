package com.chestlogger;

import com.chestlogger.command.ChestLoggerCommands;
import com.chestlogger.container.ContainerTracker;
import com.chestlogger.event.TransactionEventQueue;
import com.chestlogger.index.PersistentIndexManager;
import com.chestlogger.query.QueryEngine;
import com.chestlogger.rollback.RollbackEngine;
import com.chestlogger.storage.BlockCompressor;
import com.chestlogger.storage.LZ4BlockCompressor;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class ChestLoggerMod implements ModInitializer {
    public static final String MOD_ID = "chestlogger";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static TransactionEventQueue eventQueue;
    private static ContainerTracker tracker;
    private static PersistentIndexManager indexManager;
    private static QueryEngine queryEngine;
    private static RollbackEngine rollbackEngine;
    private static BlockCompressor compressor;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing ChestLogger for Minecraft 26.2...");
        eventQueue = new TransactionEventQueue(65536);
        tracker = new ContainerTracker(eventQueue);
        compressor = new LZ4BlockCompressor();
        rollbackEngine = new RollbackEngine();

        File dataDir = new File("chestlogger_data");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        indexManager = new PersistentIndexManager(dataDir);
        queryEngine = new QueryEngine(dataDir, compressor, indexManager);

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
        return indexManager;
    }

    public static QueryEngine getQueryEngine() {
        return queryEngine;
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
}
