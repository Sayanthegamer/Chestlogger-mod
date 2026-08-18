package com.chestlogger.paper;

import com.chestlogger.event.TransactionEventQueue;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.index.IndexPointer;
import com.chestlogger.index.IndexRebuilder;
import com.chestlogger.index.PersistentIndexManager;
import com.chestlogger.query.QueryEngine;
import com.chestlogger.query.QuerySessionManager;
import com.chestlogger.recovery.RecoveryReport;
import com.chestlogger.recovery.TailRecoveryEngine;
import com.chestlogger.storage.*;
import com.chestlogger.util.ThreadGuard;
import com.chestlogger.web.EmbeddedHttpServer;
import com.chestlogger.web.WebConfig;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Paper 26.2 Server Plugin Entrypoint for ChestLogger.
 */
public final class ChestLoggerPlugin extends JavaPlugin {

    private static final int QUEUE_CAPACITY = 65_536;
    private static final int BATCH_SIZE = 1000;

    private TransactionEventQueue eventQueue;
    private LZ4BlockCompressor compressor;
    private StringTableDictionary stringDictionary;
    private StorageProfile storageProfile;
    private PersistentIndexManager indexManager;
    private LogSegmentWriter segmentWriter;
    private QueryEngine queryEngine;
    private QuerySessionManager sessionManager;
    private PaperRollbackExecutor rollbackExecutor;
    private EmbeddedHttpServer webServer;
    private WebConfig webConfig;
    private com.chestlogger.inspect.InspectModeManager inspectModeManager;
    private com.chestlogger.alert.DiscordAlertDispatcher alertDispatcher;
    private com.chestlogger.security.TrustManager trustManager;
    private com.chestlogger.claim.ClaimManager claimManager;
    private com.chestlogger.security.SmartTheftEvaluator theftEvaluator;
    private PaperSecurityAlertBroadcaster securityBroadcaster;

    private BukkitTask workerTask;
    private final AtomicLong sequenceGenerator = new AtomicLong(0L);
    private final AtomicBoolean isFlushing = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        // Register ThreadGuard assertion against Paper's primary server thread
        ThreadGuard.setServerThreadChecker(() -> Bukkit.isPrimaryThread());

        File dataDir = getDataFolder();
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }

        File logsDir = new File(dataDir, "logs");
        if (!logsDir.exists()) {
            logsDir.mkdirs();
        }

        File indexDir = new File(dataDir, "index");
        if (!indexDir.exists()) {
            indexDir.mkdirs();
        }

        getLogger().info("[ChestLogger] Initializing core engines and recovering logs...");

        this.eventQueue = new TransactionEventQueue(QUEUE_CAPACITY);
        this.compressor = new LZ4BlockCompressor();
        this.stringDictionary = new StringTableDictionary();
        this.storageProfile = StorageProfile.BALANCED;
        this.indexManager = new PersistentIndexManager(indexDir);

        // 1. Startup Tail Recovery
        try {
            TailRecoveryEngine recoveryEngine = new TailRecoveryEngine(compressor);
            RecoveryReport report = recoveryEngine.recoverAndValidate(logsDir);
            if (report.hasCorruptions()) {
                getLogger().warning(String.format("[ChestLogger] Repaired log tail (%d truncated bytes, issues: %s)",
                        report.totalTruncatedBytes(), String.join("; ", report.issues())));
            }
            this.sequenceGenerator.set(report.maxSequenceId());
        } catch (IOException e) {
            getLogger().severe("[ChestLogger] Failed to perform tail recovery: " + e.getMessage());
        }

        // 2. Load or rebuild index
        try {
            this.indexManager.loadCheckpoint();
            if (this.indexManager.size() == 0) {
                IndexRebuilder rebuilder = new IndexRebuilder(compressor);
                int rebuilt = rebuilder.rebuild(logsDir, indexManager);
                getLogger().info("[ChestLogger] Rebuilt persistent index with " + rebuilt + " entries.");
            }
        } catch (IOException e) {
            getLogger().warning("[ChestLogger] Error loading index checkpoint, rebuilding: " + e.getMessage());
            IndexRebuilder rebuilder = new IndexRebuilder(compressor);
            try {
                rebuilder.rebuild(logsDir, indexManager);
            } catch (IOException ex) {
                getLogger().severe("[ChestLogger] Index rebuild failed: " + ex.getMessage());
            }
        }

        // 3. Initialize segment writer
        try {
            this.segmentWriter = new LogSegmentWriter(
                    logsDir,
                    "paper",
                    0,
                    sequenceGenerator.get() + 1,
                    compressor,
                    storageProfile,
                    stringDictionary
            );
        } catch (IOException e) {
            getLogger().severe("[ChestLogger] Failed to initialize segment writer: " + e.getMessage());
        }

        // 4. Query and Rollback Engines
        this.queryEngine = new QueryEngine(logsDir, compressor, indexManager, () -> stringDictionary);
        this.sessionManager = new QuerySessionManager();
        this.rollbackExecutor = new PaperRollbackExecutor(eventQueue);

        // 5. Embedded Web Admin Server
        File webConfigFile = new File(dataDir, "web.json");
        this.webConfig = WebConfig.load(webConfigFile);
        this.webServer = new EmbeddedHttpServer(
                webConfig,
                () -> eventQueue,
                () -> indexManager,
                () -> queryEngine,
                () -> sessionManager
        );
        if (webConfig.isEnabled()) {
            webServer.start();
        }

        // 6. Security Alerts & Trust Engine Initialization
        File alertConfigFile = new File(getDataFolder(), "chestlogger_alerts.json");
        com.chestlogger.alert.AlertConfig alertConfig = com.chestlogger.alert.AlertConfig.defaults();
        if (alertConfigFile.exists()) {
            try {
                alertConfig = com.chestlogger.alert.AlertConfig.fromJson(java.nio.file.Files.readString(alertConfigFile.toPath()));
            } catch (Exception e) {
                getLogger().warning("[ChestLogger] Failed to read chestlogger_alerts.json: " + e.getMessage());
            }
        } else {
            try {
                java.nio.file.Files.writeString(alertConfigFile.toPath(), alertConfig.toJson());
            } catch (Exception ignored) {}
        }
        this.alertDispatcher = new com.chestlogger.alert.DiscordAlertDispatcher(alertConfig);

        File trustFile = new File(getDataFolder(), "trust_data.json");
        this.trustManager = new com.chestlogger.security.TrustManager(trustFile.toPath());
        try {
            this.trustManager.load();
            getLogger().info("[ChestLogger] Loaded trust database with " + trustManager.getOwnerCount() + " player trust entries.");
        } catch (IOException e) {
            getLogger().warning("[ChestLogger] Failed to load trust_data.json: " + e.getMessage());
        }

        File claimsFile = new File(getDataFolder(), "claims.json");
        this.claimManager = new com.chestlogger.claim.ClaimManager(claimsFile.toPath());
        try {
            this.claimManager.load();
            getLogger().info("[ChestLogger] Loaded claims database with " + claimManager.getClaimCount() + " claimed container blocks.");
        } catch (IOException e) {
            getLogger().warning("[ChestLogger] Failed to load claims.json: " + e.getMessage());
        }

        this.theftEvaluator = new com.chestlogger.security.SmartTheftEvaluator(trustManager, alertConfig, new com.chestlogger.security.RaidVelocityTracker());
        this.securityBroadcaster = new PaperSecurityAlertBroadcaster(this, theftEvaluator, alertConfig, alertDispatcher, claimManager);

        // 7. Register Bukkit Events and Commands
        this.inspectModeManager = new com.chestlogger.inspect.InspectModeManager();

        getServer().getPluginManager().registerEvents(
                new PaperChestEventListener(this, eventQueue, sequenceGenerator),
                this
        );
        getServer().getPluginManager().registerEvents(
                new PaperWandListener(this, inspectModeManager, queryEngine, indexManager),
                this
        );
        getServer().getPluginManager().registerEvents(
                new PaperChestGuiListener(),
                this
        );
        getServer().getPluginManager().registerEvents(
                new PaperProvenanceGuiListener(),
                this
        );

        PaperCommandExecutor commandExecutor = new PaperCommandExecutor(
                this,
                queryEngine,
                indexManager,
                eventQueue,
                rollbackExecutor,
                webServer,
                inspectModeManager,
                trustManager
        );

        PluginCommand command = getCommand("chestlog");
        if (command != null) {
            command.setExecutor(commandExecutor);
            command.setTabCompleter(commandExecutor);
        }

        // 8. Start Async Background Flush Worker
        this.workerTask = getServer().getScheduler().runTaskTimerAsynchronously(this, this::flushQueueBatch, 10L, 10L);

        getLogger().info("[ChestLogger] Paper 26.2 plugin enabled successfully.");
    }

    @Override
    public void onDisable() {
        getLogger().info("[ChestLogger] Disabling plugin and initiating flush barrier...");

        if (workerTask != null) {
            workerTask.cancel();
            workerTask = null;
        }

        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }

        if (alertDispatcher != null) {
            alertDispatcher.close();
            alertDispatcher = null;
        }

        if (claimManager != null) {
            try {
                claimManager.save();
                getLogger().info("[ChestLogger] Saved claims database successfully.");
            } catch (IOException e) {
                getLogger().severe("[ChestLogger] Error saving claims.json: " + e.getMessage());
            }
        }

        if (trustManager != null) {
            try {
                trustManager.save();
                getLogger().info("[ChestLogger] Saved trust database successfully.");
            } catch (IOException e) {
                getLogger().severe("[ChestLogger] Error saving trust_data.json: " + e.getMessage());
            }
        }

        // Synchronous final flush barrier on shutdown
        drainAndFlushAll();

        try {
            if (segmentWriter != null) {
                segmentWriter.close();
                segmentWriter = null;
            }
            if (indexManager != null) {
                indexManager.saveCheckpoint();
            }
        } catch (IOException e) {
            getLogger().severe("[ChestLogger] Error closing segment writer or index: " + e.getMessage());
        }

        ThreadGuard.reset();
        getLogger().info("[ChestLogger] Shutdown complete.");
    }

    private void flushQueueBatch() {
        if (!isFlushing.compareAndSet(false, true)) {
            return;
        }

        try {
            if (eventQueue.isEmpty() || segmentWriter == null) {
                return;
            }

            List<TransactionLogEntry> batch = new ArrayList<>(BATCH_SIZE);
            eventQueue.drain(batch, BATCH_SIZE);

            if (!batch.isEmpty()) {
                if (securityBroadcaster != null) {
                    for (TransactionLogEntry entry : batch) {
                        securityBroadcaster.processTransaction(entry);
                    }
                }
                segmentWriter.writeBatch(batch);
                for (TransactionLogEntry entry : batch) {
                    for (int s = 0; s < Math.max(1, entry.deltas().size()); s++) {
                        String itemId = entry.deltas().isEmpty() ? "" : entry.deltas().get(s).itemId();
                        IndexPointer ptr = new IndexPointer(
                                entry.sequenceId(),
                                entry.timestampMs(),
                                entry.actorUuid(),
                                itemId,
                                entry.dimension(),
                                entry.packedBlockPos(),
                                segmentWriter.getActiveSegmentIndex(),
                                segmentWriter.getCurrentBlockOffset(),
                                s
                        );
                        indexManager.index(ptr);
                    }
                }
            }
        } catch (Exception e) {
            getLogger().severe("[ChestLogger] Error during async flush: " + e.getMessage());
        } finally {
            isFlushing.set(false);
        }
    }

    private void drainAndFlushAll() {
        if (eventQueue == null || segmentWriter == null) {
            return;
        }

        while (!eventQueue.isEmpty()) {
            List<TransactionLogEntry> batch = new ArrayList<>(BATCH_SIZE);
            eventQueue.drain(batch, BATCH_SIZE);
            if (batch.isEmpty()) break;

            try {
                segmentWriter.writeBatch(batch);
                for (TransactionLogEntry entry : batch) {
                    for (int s = 0; s < Math.max(1, entry.deltas().size()); s++) {
                        String itemId = entry.deltas().isEmpty() ? "" : entry.deltas().get(s).itemId();
                        IndexPointer ptr = new IndexPointer(
                                entry.sequenceId(),
                                entry.timestampMs(),
                                entry.actorUuid(),
                                itemId,
                                entry.dimension(),
                                entry.packedBlockPos(),
                                segmentWriter.getActiveSegmentIndex(),
                                segmentWriter.getCurrentBlockOffset(),
                                s
                        );
                        indexManager.index(ptr);
                    }
                }
            } catch (IOException e) {
                getLogger().severe("[ChestLogger] Error in final flush: " + e.getMessage());
            }
        }
    }

    public TransactionEventQueue getEventQueue() {
        return eventQueue;
    }

    public PersistentIndexManager getIndexManager() {
        return indexManager;
    }

    public QueryEngine getQueryEngine() {
        return queryEngine;
    }

    public com.chestlogger.claim.ClaimManager getClaimManager() {
        return claimManager;
    }

    public com.chestlogger.security.TrustManager getTrustManager() {
        return trustManager;
    }

    public com.chestlogger.security.SmartTheftEvaluator getTheftEvaluator() {
        return theftEvaluator;
    }

    public PaperSecurityAlertBroadcaster getSecurityBroadcaster() {
        return securityBroadcaster;
    }
}
