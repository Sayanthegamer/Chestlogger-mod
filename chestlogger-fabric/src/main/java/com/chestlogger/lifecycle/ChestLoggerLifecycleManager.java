package com.chestlogger.lifecycle;

import com.chestlogger.ChestLoggerMod;
import com.chestlogger.event.TransactionEventQueue;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.index.IndexPointer;
import com.chestlogger.index.IndexRebuilder;
import com.chestlogger.index.PersistentIndexManager;
import com.chestlogger.query.QueryEngine;
import com.chestlogger.recovery.RecoveryReport;
import com.chestlogger.recovery.TailRecoveryEngine;
import com.chestlogger.alert.FabricSecurityAlertBroadcaster;
import com.chestlogger.security.RaidVelocityTracker;
import com.chestlogger.security.SmartTheftEvaluator;
import com.chestlogger.security.TrustManager;
import com.chestlogger.storage.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates singleplayer and dedicated server lifecycle, world path binding,
 * recovery verification, and background async writer thread execution.
 */
public final class ChestLoggerLifecycleManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("ChestLogger-Lifecycle");
    private static MinecraftServer currentServer;

    private final TransactionEventQueue eventQueue;
    private final BlockCompressor compressor;
    private final StorageProfile profile;

    private PersistentIndexManager indexManager;
    private QueryEngine queryEngine;
    private LogSegmentWriter segmentWriter;
    private StringTableDictionary stringDictionary;

    private Thread writerThread;
    private com.chestlogger.alert.DiscordAlertDispatcher alertDispatcher;
    private TrustManager trustManager;
    private SmartTheftEvaluator theftEvaluator;
    private FabricSecurityAlertBroadcaster securityBroadcaster;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CountDownLatch stopLatch = new CountDownLatch(1);

    public ChestLoggerLifecycleManager(TransactionEventQueue eventQueue, BlockCompressor compressor, StorageProfile profile) {
        this.eventQueue = Objects.requireNonNull(eventQueue, "eventQueue cannot be null");
        this.compressor = Objects.requireNonNull(compressor, "compressor cannot be null");
        this.profile = Objects.requireNonNull(profile, "profile cannot be null");
    }

    public static void setServer(MinecraftServer server) {
        currentServer = server;
    }

    public static MinecraftServer getServer() {
        return currentServer;
    }

    public static void registerServerEvents(ChestLoggerLifecycleManager lifecycleManager) {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            setServer(server);
            Path worldDir = server.getWorldPath(LevelResource.ROOT).resolve("chestlogger");
            File storageDir = worldDir.toFile();
            try {
                LOGGER.info("Starting ChestLogger for world at: {}", storageDir.getAbsolutePath());
                lifecycleManager.start(storageDir);
                try {
                    ChestLoggerMod.getHttpServer().start();
                } catch (Throwable t) {
                    LOGGER.warn("[ChestLogger] Optional web server could not be started: {}", t.getMessage());
                }
            } catch (Exception e) {
                LOGGER.error("Failed to start ChestLogger storage engine: {}", e.getMessage(), e);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Gracefully stopping ChestLogger storage engine...");
            try {
                ChestLoggerMod.getHttpServer().stop();
            } catch (Throwable t) {
                LOGGER.warn("[ChestLogger] Error stopping optional web server: {}", t.getMessage());
            }
            lifecycleManager.stop(10000);
            setServer(null);
        });
    }

    public synchronized void start(File dataDir) throws IOException {
        if (running.get()) {
            return;
        }

        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }

        // 1. Crash recovery & tail verification
        TailRecoveryEngine recovery = new TailRecoveryEngine(compressor);
        RecoveryReport report = recovery.recoverAndValidate(dataDir);
        if (report.hasCorruptions()) {
            LOGGER.warn("ChestLogger startup recovery repaired tail corruptions: {}", report.issues());
        }

        // 2. Index initialization
        indexManager = new PersistentIndexManager(dataDir);
        File indexFile = new File(dataDir, PersistentIndexManager.INDEX_FILE_NAME);
        if (indexFile.exists()) {
            try {
                indexManager.loadCheckpoint();
            } catch (Exception e) {
                LOGGER.warn("Failed loading index checkpoint, rebuilding from logs: {}", e.getMessage());
                new IndexRebuilder(compressor).rebuild(dataDir, indexManager);
            }
        } else {
            new IndexRebuilder(compressor).rebuild(dataDir, indexManager);
        }

        // 3. String dictionary & Segment writer initialization
        stringDictionary = new StringTableDictionary();

        // Populate string dictionary from existing segments to maintain ID continuity
        File[] clogFiles = dataDir.listFiles((dir, name) -> name.endsWith(".clog"));
        if (clogFiles != null && clogFiles.length > 0) {
            Arrays.sort(clogFiles, Comparator.comparing(File::getName));
            for (File cf : clogFiles) {
                try (FileInputStream fis = new FileInputStream(cf)) {
                    if (fis.available() >= BinaryLogHeader.HEADER_SIZE) {
                        BinaryLogHeader.readFrom(fis);
                        while (fis.available() >= BlockFrameHeader.HEADER_SIZE) {
                            BlockFrameHeader bh = BlockFrameHeader.readFrom(fis);
                            byte[] payload = fis.readNBytes(bh.compressedLength());
                            if (bh.blockType() == BlockFrameHeader.TYPE_DICTIONARY) {
                                StringTableDictionary loaded = StringTableDictionary.readFrom(new ByteArrayInputStream(payload));
                                for (int i = 0; i < loaded.size(); i++) {
                                    stringDictionary.getOrAssign(loaded.getString(i));
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        long nextSequenceId = report.maxSequenceId() + 1;
        int activeSegIdx = report.activeSegmentIndex();

        segmentWriter = new LogSegmentWriter(dataDir, "chestlog", activeSegIdx, nextSequenceId, compressor, profile, stringDictionary);
        queryEngine = new QueryEngine(dataDir, compressor, indexManager, () -> stringDictionary);

        // Security Alerts & Trust Engine
        File alertConfigFile = new File(dataDir, "chestlogger_alerts.json");
        com.chestlogger.alert.AlertConfig alertConfig = com.chestlogger.alert.AlertConfig.defaults();
        if (alertConfigFile.exists()) {
            try {
                alertConfig = com.chestlogger.alert.AlertConfig.fromJson(java.nio.file.Files.readString(alertConfigFile.toPath()));
            } catch (Exception e) {
                LOGGER.warn("[ChestLogger] Failed to read alert config: {}", e.getMessage());
            }
        } else {
            try {
                java.nio.file.Files.writeString(alertConfigFile.toPath(), alertConfig.toJson());
            } catch (Exception ignored) {}
        }
        this.alertDispatcher = new com.chestlogger.alert.DiscordAlertDispatcher(alertConfig);

        File trustFile = new File(dataDir, "trust_data.json");
        this.trustManager = new TrustManager(trustFile.toPath());
        try {
            this.trustManager.load();
            LOGGER.info("[ChestLogger] Loaded trust database with {} owner trust mappings.", trustManager.getOwnerCount());
        } catch (Exception e) {
            LOGGER.warn("[ChestLogger] Failed to load trust_data.json: {}", e.getMessage());
        }

        this.theftEvaluator = new SmartTheftEvaluator(trustManager, alertConfig, new RaidVelocityTracker());
        this.securityBroadcaster = new FabricSecurityAlertBroadcaster(() -> currentServer, theftEvaluator, alertConfig, alertDispatcher);

        running.set(true);

        // 4. Spawn dedicated background writer thread
        writerThread = new Thread(this::runWriterLoop, "ChestLogger-AsyncWriter");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    private void runWriterLoop() {
        List<TransactionLogEntry> batchBuffer = new ArrayList<>(profile.maxBatchEvents());

        while (running.get()) {
            try {
                int drained = eventQueue.drain(batchBuffer, profile.maxBatchEvents());
                if (drained > 0) {
                    processBatch(batchBuffer);
                    batchBuffer.clear();
                } else {
                    Thread.sleep(profile.flushIntervalMs());
                }
            } catch (InterruptedException e) {
                // Wakeup for shutdown
                break;
            } catch (Exception e) {
                LOGGER.error("Error in background log writer loop: {}", e.getMessage(), e);
            }
        }

        // Clear interrupted status so shutdown file writes/flushes are not aborted by NIO
        Thread.interrupted();

        // Drain any leftover events during shutdown
        while (eventQueue.getDepth() > 0) {
            int drained = eventQueue.drain(batchBuffer, profile.maxBatchEvents());
            if (drained > 0) {
                processBatch(batchBuffer);
                batchBuffer.clear();
            }
        }

        try {
            if (segmentWriter != null) {
                segmentWriter.close();
            }
            if (indexManager != null) {
                indexManager.saveCheckpoint();
            }
        } catch (Exception e) {
            LOGGER.error("Error closing segment writer/saving index during shutdown: {}", e.getMessage(), e);
        } finally {
            stopLatch.countDown();
        }
    }

    private void processBatch(List<TransactionLogEntry> records) {
        if (records.isEmpty()) return;
        try {
            if (securityBroadcaster != null) {
                for (TransactionLogEntry entry : records) {
                    securityBroadcaster.processTransaction(entry);
                }
            }

            long currentBlockOffset = segmentWriter.getBytesWrittenToCurrentSegment();
            int currentSegIndex = segmentWriter.getSegmentIndex();

            segmentWriter.writeBatch(records);

            for (int i = 0; i < records.size(); i++) {
                TransactionLogEntry entry = records.get(i);
                String primaryItem = entry.deltas().isEmpty() ? null : entry.deltas().get(0).itemId();

                IndexPointer ptr = new IndexPointer(
                        entry.sequenceId(),
                        entry.timestampMs(),
                        entry.actorUuid(),
                        primaryItem,
                        entry.dimension(),
                        entry.packedBlockPos(),
                        currentSegIndex,
                        currentBlockOffset,
                        i
                );
                indexManager.index(ptr);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to write batch to segment writer: {}", e.getMessage(), e);
        }
    }

    public synchronized void stop(long timeoutMs) {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        if (alertDispatcher != null) {
            alertDispatcher.close();
            alertDispatcher = null;
        }

        if (trustManager != null) {
            try {
                trustManager.save();
                LOGGER.info("[ChestLogger] Saved trust database successfully.");
            } catch (Exception e) {
                LOGGER.error("Failed to save trust_data.json on shutdown: {}", e.getMessage());
            }
        }

        if (writerThread != null) {
            writerThread.interrupt();
            try {
                stopLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public PersistentIndexManager getIndexManager() {
        return indexManager;
    }

    public QueryEngine getQueryEngine() {
        return queryEngine;
    }

    public LogSegmentWriter getSegmentWriter() {
        return segmentWriter;
    }

    public TrustManager getTrustManager() {
        return trustManager;
    }

    public SmartTheftEvaluator getTheftEvaluator() {
        return theftEvaluator;
    }

    public FabricSecurityAlertBroadcaster getSecurityBroadcaster() {
        return securityBroadcaster;
    }
}
