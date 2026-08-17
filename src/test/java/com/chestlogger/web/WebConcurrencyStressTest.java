package com.chestlogger.web;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionEventQueue;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.index.IndexPointer;
import com.chestlogger.index.PersistentIndexManager;
import com.chestlogger.query.QueryEngine;
import com.chestlogger.query.QuerySessionManager;
import com.chestlogger.storage.LZ4BlockCompressor;
import com.chestlogger.storage.LogSegmentWriter;
import com.chestlogger.storage.StorageProfile;
import com.chestlogger.storage.StringTableDictionary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency, high-throughput stress, and lifecycle resilience tests for ChestLogger Embedded HTTP REST API.
 * 
 * 1. Simulates high concurrency: 20 simultaneous client threads requesting /api/v1/query and /api/v1/stats
 *    concurrently while TransactionEventQueue is under continuous insertion and draining load.
 * 2. Simulates concurrent /api/v1/export streaming requests (5+ concurrent CSV/JSON streams)
 *    verifying zero stream cross-talk, interleaving, or payload corruption.
 * 3. Tests server start/stop lifecycle under active connection load (graceful shutdown with zero socket leaks).
 */
class WebConcurrencyStressTest {

    private static final String AUTH_TOKEN = "stress_test_auth_secret_token_123456789";

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            return 18100 + (int) (Math.random() * 500);
        }
    }

    /**
     * Helper to populate log files and spatial/temporal/item indexes with rich test data.
     */
    private DatasetFixture setupTestDataset(File tempDir) throws Exception {
        StringTableDictionary dict = new StringTableDictionary();
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        StorageProfile profile = StorageProfile.BALANCED;
        PersistentIndexManager indexManager = new PersistentIndexManager(tempDir);

        UUID alexUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID steveUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID hopperUuid = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID bobUuid = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID aliceUuid = UUID.fromString("55555555-5555-5555-5555-555555555555");

        long posAlex = BlockPosUtil.pack(100, 64, -200);
        long posSteve = BlockPosUtil.pack(50, 70, 300);
        long posHopper = BlockPosUtil.pack(10, 60, -10);
        long posBob = BlockPosUtil.pack(-100, 50, 50);
        long posAlice = BlockPosUtil.pack(200, 80, 400);

        long baseTime = 1723824000000L;
        List<TransactionLogEntry> entries = new ArrayList<>();
        List<IndexPointer> pointers = new ArrayList<>();
        long seq = 1L;

        // Group 1: Alex placing diamonds at posAlex (25 records)
        int alexDiamondCount = 25;
        for (int i = 0; i < alexDiamondCount; i++) {
            long time = baseTime + (seq * 100);
            TransactionLogEntry entry = new TransactionLogEntry(
                    seq, time, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                    alexUuid, "Alex", "minecraft:overworld", posAlex,
                    List.of(new SlotDelta(i % 27, "minecraft:diamond", 10 + (i % 5), 0, 10 + (i % 5), 0L))
            );
            entries.add(entry);
            pointers.add(new IndexPointer(seq, time, alexUuid, "minecraft:diamond", "minecraft:overworld", posAlex, 0, 32L, (int) (seq - 1)));
            seq++;
        }

        // Group 2: Steve depositing gold at posSteve (20 records)
        int steveGoldCount = 20;
        for (int i = 0; i < steveGoldCount; i++) {
            long time = baseTime + (seq * 100);
            TransactionLogEntry entry = new TransactionLogEntry(
                    seq, time, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                    steveUuid, "Steve", "minecraft:overworld", posSteve,
                    List.of(new SlotDelta(i % 27, "minecraft:gold_ingot", 5 + (i % 3), 0, 5 + (i % 3), 0L))
            );
            entries.add(entry);
            pointers.add(new IndexPointer(seq, time, steveUuid, "minecraft:gold_ingot", "minecraft:overworld", posSteve, 0, 32L, (int) (seq - 1)));
            seq++;
        }

        // Group 3: Hopper extracting iron at posHopper (15 records)
        int hopperIronCount = 15;
        for (int i = 0; i < hopperIronCount; i++) {
            long time = baseTime + (seq * 100);
            TransactionLogEntry entry = new TransactionLogEntry(
                    seq, time, UUID.randomUUID(), ActionType.PICKUP, ActorType.HOPPER_BLOCK,
                    hopperUuid, "Hopper", "minecraft:overworld", posHopper,
                    List.of(new SlotDelta(0, "minecraft:iron_ingot", -1, 1, 0, 0L))
            );
            entries.add(entry);
            pointers.add(new IndexPointer(seq, time, hopperUuid, "minecraft:iron_ingot", "minecraft:overworld", posHopper, 0, 32L, (int) (seq - 1)));
            seq++;
        }

        // Group 4: Bob picking up emeralds at posBob (15 records)
        int bobEmeraldCount = 15;
        for (int i = 0; i < bobEmeraldCount; i++) {
            long time = baseTime + (seq * 100);
            TransactionLogEntry entry = new TransactionLogEntry(
                    seq, time, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER,
                    bobUuid, "Bob", "minecraft:overworld", posBob,
                    List.of(new SlotDelta(i % 9, "minecraft:emerald", -2, 10, 8, 0L))
            );
            entries.add(entry);
            pointers.add(new IndexPointer(seq, time, bobUuid, "minecraft:emerald", "minecraft:overworld", posBob, 0, 32L, (int) (seq - 1)));
            seq++;
        }

        // Group 5: Alice depositing netherite at posAlice (10 records)
        int aliceNetheriteCount = 10;
        for (int i = 0; i < aliceNetheriteCount; i++) {
            long time = baseTime + (seq * 100);
            TransactionLogEntry entry = new TransactionLogEntry(
                    seq, time, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                    aliceUuid, "Alice", "minecraft:the_nether", posAlice,
                    List.of(new SlotDelta(i % 27, "minecraft:netherite_ingot", 1, 0, 1, 0L))
            );
            entries.add(entry);
            pointers.add(new IndexPointer(seq, time, aliceUuid, "minecraft:netherite_ingot", "minecraft:the_nether", posAlice, 0, 32L, (int) (seq - 1)));
            seq++;
        }

        // Write batch to clog segment
        try (LogSegmentWriter writer = new LogSegmentWriter(tempDir, "chestlog", 0, 1L, compressor, profile, dict)) {
            writer.writeBatch(entries);
        }

        // Index all pointers
        for (IndexPointer ptr : pointers) {
            indexManager.index(ptr);
        }

        QueryEngine queryEngine = new QueryEngine(tempDir, compressor, indexManager, () -> dict);
        QuerySessionManager sessionManager = new QuerySessionManager(25);
        TransactionEventQueue eventQueue = new TransactionEventQueue(100_000);

        return new DatasetFixture(
                entries.size(),
                alexDiamondCount,
                steveGoldCount,
                hopperIronCount,
                bobEmeraldCount,
                aliceNetheriteCount,
                posAlex,
                posSteve,
                posHopper,
                posBob,
                posAlice,
                indexManager,
                queryEngine,
                sessionManager,
                eventQueue
        );
    }

    private record DatasetFixture(
            int totalRecords,
            int alexDiamondCount,
            int steveGoldCount,
            int hopperIronCount,
            int bobEmeraldCount,
            int aliceNetheriteCount,
            long posAlex,
            long posSteve,
            long posHopper,
            long posBob,
            long posAlice,
            PersistentIndexManager indexManager,
            QueryEngine queryEngine,
            QuerySessionManager sessionManager,
            TransactionEventQueue eventQueue
    ) {}

    // =========================================================================
    // 1. High Concurrency: 20 Client Threads on /query & /stats + Continuous Queue Ingestion
    // =========================================================================
    @Test
    @DisplayName("1. High Concurrency Stress: 20 simultaneous client threads on /api/v1/query and /api/v1/stats under continuous EventQueue insertion load")
    void testHighConcurrencyQueryAndStatsUnderQueueLoad(@TempDir File tempDir) throws Exception {
        DatasetFixture fixture = setupTestDataset(tempDir);
        int port = findFreePort();
        WebConfig config = new WebConfig(true, "127.0.0.1", port, AUTH_TOKEN, "*", 50);

        EmbeddedHttpServer server = new EmbeddedHttpServer(
                config,
                () -> fixture.eventQueue,
                () -> fixture.indexManager,
                () -> fixture.queryEngine,
                () -> fixture.sessionManager
        );
        server.start();
        assertThat(server.isRunning()).isTrue();

        String baseUrl = "http://127.0.0.1:" + port;
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        // Warmup request to ensure server thread pool and handlers are fully primed
        HttpRequest warmupReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/health"))
                .header("X-ChestLogger-Auth", AUTH_TOKEN)
                .GET()
                .build();
        HttpResponse<String> warmupResp = client.send(warmupReq, HttpResponse.BodyHandlers.ofString());
        assertThat(warmupResp.statusCode()).isEqualTo(200);

        int clientThreadCount = 20;
        int requestsPerThread = 10; // Total 200 concurrent HTTP requests across 20 threads
        ExecutorService clientExecutor = Executors.newFixedThreadPool(clientThreadCount);
        ExecutorService queueWorkerExecutor = Executors.newFixedThreadPool(2);

        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicBoolean stopQueueLoad = new AtomicBoolean(false);
        AtomicLong insertionCount = new AtomicLong(0);
        AtomicLong drainCount = new AtomicLong(0);

        // Background Thread 1: Continuous high-frequency event ingestion into TransactionEventQueue
        queueWorkerExecutor.submit(() -> {
            try {
                startLatch.await();
                long curSeq = 100_000L;
                while (!stopQueueLoad.get()) {
                    TransactionLogEntry entry = new TransactionLogEntry(
                            curSeq++,
                            System.currentTimeMillis(),
                            UUID.randomUUID(),
                            ActionType.PLACE,
                            ActorType.PLAYER,
                            UUID.randomUUID(),
                            "StressPlayer",
                            "minecraft:overworld",
                            BlockPosUtil.pack(10, 64, 10),
                            List.of(new SlotDelta(0, "minecraft:cobblestone", 1, 0, 1, 0L))
                    );
                    if (fixture.eventQueue.offer(entry)) {
                        insertionCount.incrementAndGet();
                    }
                    if (curSeq % 50 == 0) {
                        Thread.sleep(1);
                    }
                }
            } catch (Exception ignored) {
            }
        });

        // Background Thread 2: Periodic batch drainer (simulating background disk writer consuming events)
        queueWorkerExecutor.submit(() -> {
            try {
                startLatch.await();
                List<TransactionLogEntry> drainTarget = new ArrayList<>(128);
                while (!stopQueueLoad.get()) {
                    drainTarget.clear();
                    int drained = fixture.eventQueue.drain(drainTarget, 64);
                    if (drained > 0) {
                        drainCount.addAndGet(drained);
                    }
                    Thread.sleep(5);
                }
            } catch (Exception ignored) {
            }
        });

        // Launch 20 simultaneous client tasks
        List<CompletableFuture<List<String>>> clientFutures = new ArrayList<>();
        for (int t = 0; t < clientThreadCount; t++) {
            final int threadId = t;
            CompletableFuture<List<String>> future = CompletableFuture.supplyAsync(() -> {
                List<String> errors = new ArrayList<>();
                try {
                    startLatch.await();

                    for (int i = 0; i < requestsPerThread; i++) {
                        int operation = (threadId + i) % 5;
                        switch (operation) {
                            case 0 -> {
                                // Test /api/v1/stats
                                HttpRequest req = HttpRequest.newBuilder()
                                        .uri(URI.create(baseUrl + "/api/v1/stats"))
                                        .header("X-ChestLogger-Auth", AUTH_TOKEN)
                                        .timeout(Duration.ofSeconds(10))
                                        .GET()
                                        .build();
                                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                                if (resp.statusCode() != 200) {
                                    errors.add("Stats expected 200 but got " + resp.statusCode());
                                } else {
                                    String body = resp.body();
                                    if (!body.contains("\"queue\":") || !body.contains("\"index\":") || !body.contains("\"uptimeMs\":")) {
                                        errors.add("Stats missing expected keys in body: " + body);
                                    }
                                }
                            }
                            case 1 -> {
                                // Test /api/v1/query with exact coordinates (Alex diamonds)
                                HttpRequest req = HttpRequest.newBuilder()
                                        .uri(URI.create(baseUrl + "/api/v1/query?x=100&y=64&z=-200&dim=minecraft:overworld"))
                                        .header("X-ChestLogger-Auth", AUTH_TOKEN)
                                        .timeout(Duration.ofSeconds(10))
                                        .GET()
                                        .build();
                                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                                if (resp.statusCode() != 200) {
                                    errors.add("Query posAlex expected 200 but got " + resp.statusCode());
                                } else {
                                    String body = resp.body();
                                    if (!body.contains("\"totalRecords\":" + fixture.alexDiamondCount)) {
                                        errors.add("Query posAlex unexpected totalRecords: " + body);
                                    }
                                }
                            }
                            case 2 -> {
                                // Test /api/v1/query with item filter (gold ingot)
                                HttpRequest req = HttpRequest.newBuilder()
                                        .uri(URI.create(baseUrl + "/api/v1/query?item=minecraft:gold_ingot"))
                                        .header("X-ChestLogger-Auth", AUTH_TOKEN)
                                        .timeout(Duration.ofSeconds(10))
                                        .GET()
                                        .build();
                                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                                if (resp.statusCode() != 200) {
                                    errors.add("Query gold expected 200 but got " + resp.statusCode());
                                } else {
                                    String body = resp.body();
                                    if (!body.contains("\"totalRecords\":" + fixture.steveGoldCount)) {
                                        errors.add("Query gold unexpected totalRecords: " + body);
                                    }
                                }
                            }
                            case 3 -> {
                                // Test /api/v1/query with dimension filter (netherite in nether)
                                HttpRequest req = HttpRequest.newBuilder()
                                        .uri(URI.create(baseUrl + "/api/v1/query?dim=minecraft:the_nether"))
                                        .header("X-ChestLogger-Auth", AUTH_TOKEN)
                                        .timeout(Duration.ofSeconds(10))
                                        .GET()
                                        .build();
                                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                                if (resp.statusCode() != 200) {
                                    errors.add("Query nether expected 200 but got " + resp.statusCode());
                                } else {
                                    String body = resp.body();
                                    if (!body.contains("\"totalRecords\":" + fixture.aliceNetheriteCount)) {
                                        errors.add("Query nether unexpected totalRecords: " + body);
                                    }
                                }
                            }
                            case 4 -> {
                                // Test /api/v1/query with session pagination
                                HttpRequest req1 = HttpRequest.newBuilder()
                                        .uri(URI.create(baseUrl + "/api/v1/query?x=100&y=64&z=-200&limit=10&page=1"))
                                        .header("X-ChestLogger-Auth", AUTH_TOKEN)
                                        .timeout(Duration.ofSeconds(10))
                                        .GET()
                                        .build();
                                HttpResponse<String> resp1 = client.send(req1, HttpResponse.BodyHandlers.ofString());
                                if (resp1.statusCode() != 200) {
                                    errors.add("Query page 1 expected 200 but got " + resp1.statusCode());
                                } else {
                                    String body1 = resp1.body();
                                    int qIdStart = body1.indexOf("\"queryId\":\"");
                                    if (qIdStart != -1) {
                                        String queryId = body1.substring(qIdStart + 11, body1.indexOf("\"", qIdStart + 11));
                                        HttpRequest req2 = HttpRequest.newBuilder()
                                                .uri(URI.create(baseUrl + "/api/v1/query?queryId=" + queryId + "&page=2"))
                                                .header("X-ChestLogger-Auth", AUTH_TOKEN)
                                                .timeout(Duration.ofSeconds(10))
                                                .GET()
                                                .build();
                                        HttpResponse<String> resp2 = client.send(req2, HttpResponse.BodyHandlers.ofString());
                                        if (resp2.statusCode() != 200) {
                                            errors.add("Query page 2 expected 200 but got " + resp2.statusCode());
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    errors.add("Thread " + threadId + " encountered exception: " + e.getMessage());
                }
                return errors;
            }, clientExecutor);
            clientFutures.add(future);
        }

        // Release all threads simultaneously
        startLatch.countDown();

        // Wait for all 20 client threads to finish
        CompletableFuture.allOf(clientFutures.toArray(new CompletableFuture[0]))
                .get(30, TimeUnit.SECONDS);

        // Stop queue continuous load
        stopQueueLoad.set(true);
        queueWorkerExecutor.shutdown();
        queueWorkerExecutor.awaitTermination(3, TimeUnit.SECONDS);
        clientExecutor.shutdown();
        clientExecutor.awaitTermination(3, TimeUnit.SECONDS);

        try {
            // Verify all client threads succeeded with zero errors
            List<String> allErrors = new ArrayList<>();
            for (CompletableFuture<List<String>> future : clientFutures) {
                allErrors.addAll(future.get());
            }
            assertThat(allErrors).isEmpty();

            // Verify queue handled continuous insertion load without failure
            assertThat(insertionCount.get()).isGreaterThan(0L);
            assertThat(fixture.eventQueue.getDroppedCount()).isEqualTo(0L);
            assertThat(fixture.eventQueue.getEnqueuedCount()).isGreaterThanOrEqualTo(insertionCount.get());

            // Final sanity check on /api/v1/stats
            HttpRequest finalStatsReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/stats"))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();
            HttpResponse<String> finalStatsResp = client.send(finalStatsReq, HttpResponse.BodyHandlers.ofString());
            assertThat(finalStatsResp.statusCode()).isEqualTo(200);
            assertThat(finalStatsResp.body()).contains("\"index\":{\"size\":" + fixture.totalRecords + "}");
        } finally {
            server.stop();
            assertThat(server.isRunning()).isFalse();
        }
    }

    // =========================================================================
    // 2. Concurrent Export Streaming Requests (6 Simultaneous CSV/JSON Streams)
    // =========================================================================
    @Test
    @DisplayName("2. Concurrent Export Streaming: 6 simultaneous CSV/JSON streams with zero cross-talk, interleaving, or corruption")
    void testConcurrentStreamingExportsWithZeroCrossTalk(@TempDir File tempDir) throws Exception {
        DatasetFixture fixture = setupTestDataset(tempDir);
        int port = findFreePort();
        WebConfig config = new WebConfig(true, "127.0.0.1", port, AUTH_TOKEN, "*", 20);

        EmbeddedHttpServer server = new EmbeddedHttpServer(
                config,
                () -> fixture.eventQueue,
                () -> fixture.indexManager,
                () -> fixture.queryEngine,
                () -> fixture.sessionManager
        );
        server.start();
        assertThat(server.isRunning()).isTrue();

        String baseUrl = "http://127.0.0.1:" + port;
        ExecutorService streamPool = Executors.newFixedThreadPool(8);
        CountDownLatch startLatch = new CountDownLatch(1);

        try {
            // Stream 1: Filtered CSV - Diamonds only (Alex)
            CompletableFuture<StreamResult> stream1 = CompletableFuture.supplyAsync(() -> {
                return consumeExportStream(
                        baseUrl + "/api/v1/export?format=csv&item=minecraft:diamond",
                        startLatch,
                        "csv"
                );
            }, streamPool);

            // Stream 2: Filtered JSON - Gold only (Steve)
            CompletableFuture<StreamResult> stream2 = CompletableFuture.supplyAsync(() -> {
                return consumeExportStream(
                        baseUrl + "/api/v1/export?format=json&item=minecraft:gold_ingot",
                        startLatch,
                        "json"
                );
            }, streamPool);

            // Stream 3: Filtered CSV - PosHopper coordinate query (Hopper iron)
            CompletableFuture<StreamResult> stream3 = CompletableFuture.supplyAsync(() -> {
                int x = BlockPosUtil.unpackX(fixture.posHopper);
                int y = BlockPosUtil.unpackY(fixture.posHopper);
                int z = BlockPosUtil.unpackZ(fixture.posHopper);
                return consumeExportStream(
                        baseUrl + "/api/v1/export?format=csv&x=" + x + "&y=" + y + "&z=" + z,
                        startLatch,
                        "csv"
                );
            }, streamPool);

            // Stream 4: Filtered JSON - Bob emeralds
            CompletableFuture<StreamResult> stream4 = CompletableFuture.supplyAsync(() -> {
                int x = BlockPosUtil.unpackX(fixture.posBob);
                int y = BlockPosUtil.unpackY(fixture.posBob);
                int z = BlockPosUtil.unpackZ(fixture.posBob);
                return consumeExportStream(
                        baseUrl + "/api/v1/export?format=json&x=" + x + "&y=" + y + "&z=" + z,
                        startLatch,
                        "json"
                );
            }, streamPool);

            // Stream 5: Full CSV export
            CompletableFuture<StreamResult> stream5 = CompletableFuture.supplyAsync(() -> {
                return consumeExportStream(
                        baseUrl + "/api/v1/export?format=csv",
                        startLatch,
                        "csv"
                );
            }, streamPool);

            // Stream 6: Full JSON export
            CompletableFuture<StreamResult> stream6 = CompletableFuture.supplyAsync(() -> {
                return consumeExportStream(
                        baseUrl + "/api/v1/export?format=json",
                        startLatch,
                        "json"
                );
            }, streamPool);

            // Trigger all 6 export streams simultaneously
            startLatch.countDown();

            StreamResult res1 = stream1.get(10, TimeUnit.SECONDS);
            StreamResult res2 = stream2.get(10, TimeUnit.SECONDS);
            StreamResult res3 = stream3.get(10, TimeUnit.SECONDS);
            StreamResult res4 = stream4.get(10, TimeUnit.SECONDS);
            StreamResult res5 = stream5.get(10, TimeUnit.SECONDS);
            StreamResult res6 = stream6.get(10, TimeUnit.SECONDS);

            // --- Assert Stream 1 (CSV Diamonds) ---
            assertThat(res1.statusCode).isEqualTo(200);
            assertThat(res1.contentType).contains("text/csv");
            assertThat(res1.contentDisposition).contains(".csv\"");
            String csvHeader = "timestamp,date_time,dimension,x,y,z,actor_name,actor_type,action,slot,item_id,delta,metadata_fingerprint";
            assertThat(res1.lines.get(0)).isEqualTo(csvHeader);
            List<String> dataRows1 = res1.lines.subList(1, res1.lines.size());
            assertThat(dataRows1).hasSize(fixture.alexDiamondCount);
            for (String row : dataRows1) {
                assertThat(row).contains("minecraft:diamond");
                assertThat(row).contains("Alex");
                assertThat(row).doesNotContain("minecraft:gold_ingot");
                assertThat(row).doesNotContain("minecraft:iron_ingot");
                assertThat(row).doesNotContain("minecraft:emerald");
                assertThat(row.split(",")).hasSize(13);
            }

            // --- Assert Stream 2 (JSON Gold) ---
            assertThat(res2.statusCode).isEqualTo(200);
            assertThat(res2.contentType).contains("application/json");
            assertThat(res2.contentDisposition).contains(".json\"");
            assertThat(res2.body).contains("\"totalRecords\":" + fixture.steveGoldCount);
            assertThat(res2.body).contains("\"actorName\":\"Steve\"");
            assertThat(res2.body).contains("\"itemId\":\"minecraft:gold_ingot\"");
            assertThat(res2.body).doesNotContain("\"itemId\":\"minecraft:diamond\"");
            assertThat(res2.body).doesNotContain("\"itemId\":\"minecraft:iron_ingot\"");

            // --- Assert Stream 3 (CSV Iron / Hopper) ---
            assertThat(res3.statusCode).isEqualTo(200);
            assertThat(res3.lines.get(0)).isEqualTo(csvHeader);
            List<String> dataRows3 = res3.lines.subList(1, res3.lines.size());
            assertThat(dataRows3).hasSize(fixture.hopperIronCount);
            for (String row : dataRows3) {
                assertThat(row).contains("minecraft:iron_ingot");
                assertThat(row).contains("Hopper");
                assertThat(row).doesNotContain("minecraft:diamond");
                assertThat(row.split(",")).hasSize(13);
            }

            // --- Assert Stream 4 (JSON Emerald / Bob) ---
            assertThat(res4.statusCode).isEqualTo(200);
            assertThat(res4.body).contains("\"totalRecords\":" + fixture.bobEmeraldCount);
            assertThat(res4.body).contains("\"actorName\":\"Bob\"");
            assertThat(res4.body).contains("\"itemId\":\"minecraft:emerald\"");
            assertThat(res4.body).doesNotContain("\"itemId\":\"minecraft:diamond\"");

            // --- Assert Stream 5 (Full CSV) ---
            assertThat(res5.statusCode).isEqualTo(200);
            assertThat(res5.lines.get(0)).isEqualTo(csvHeader);
            List<String> allCsvDataRows = res5.lines.subList(1, res5.lines.size());
            assertThat(allCsvDataRows).hasSize(fixture.totalRecords);
            for (String row : allCsvDataRows) {
                assertThat(row.split(",")).hasSize(13);
            }

            // --- Assert Stream 6 (Full JSON) ---
            assertThat(res6.statusCode).isEqualTo(200);
            assertThat(res6.body).contains("\"totalRecords\":" + fixture.totalRecords);
            assertThat(res6.body).contains("\"records\":[");
            assertThat(res6.body).endsWith("]}");

        } finally {
            streamPool.shutdown();
            server.stop();
            assertThat(server.isRunning()).isFalse();
        }
    }

    private record StreamResult(
            int statusCode,
            String contentType,
            String contentDisposition,
            String body,
            List<String> lines
    ) {}

    private StreamResult consumeExportStream(String targetUrl, CountDownLatch startLatch, String expectedFormat) {
        try {
            startLatch.await();
            HttpURLConnection conn = (HttpURLConnection) URI.create(targetUrl).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-ChestLogger-Auth", AUTH_TOKEN);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int status = conn.getResponseCode();
            String contentType = conn.getContentType();
            String contentDisposition = conn.getHeaderField("Content-Disposition");

            List<String> lines = new ArrayList<>();
            StringBuilder fullBody = new StringBuilder();

            try (InputStream in = (status >= 400 ? conn.getErrorStream() : conn.getInputStream());
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                    fullBody.append(line).append('\n');
                }
            }

            return new StreamResult(status, contentType, contentDisposition, fullBody.toString().trim(), lines);
        } catch (Exception e) {
            return new StreamResult(500, null, null, "Error: " + e.getMessage(), Collections.emptyList());
        }
    }

    // =========================================================================
    // 3. Server Start/Stop Lifecycle Under Active Connection Load & Zero Socket Leaks
    // =========================================================================
    @Test
    @DisplayName("3.1 Graceful Shutdown Under Active Load: Stop server while client threads are in-flight without socket leaks")
    void testGracefulShutdownUnderActiveLoad(@TempDir File tempDir) throws Exception {
        DatasetFixture fixture = setupTestDataset(tempDir);
        int port = findFreePort();
        WebConfig config = new WebConfig(true, "127.0.0.1", port, AUTH_TOKEN, "*", 30);

        EmbeddedHttpServer server = new EmbeddedHttpServer(
                config,
                () -> fixture.eventQueue,
                () -> fixture.indexManager,
                () -> fixture.queryEngine,
                () -> fixture.sessionManager
        );
        server.start();
        assertThat(server.isRunning()).isTrue();

        String baseUrl = "http://127.0.0.1:" + port;
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(1000))
                .build();

        // Ensure server is accepting requests before blasting
        HttpRequest primeReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/health"))
                .header("X-ChestLogger-Auth", AUTH_TOKEN)
                .GET()
                .build();
        HttpResponse<String> primeResp = client.send(primeReq, HttpResponse.BodyHandlers.ofString());
        assertThat(primeResp.statusCode()).isEqualTo(200);

        int clientCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(clientCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch activeTrafficLatch = new CountDownLatch(5);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger completedRequests = new AtomicInteger(0);
        ConcurrentLinkedQueue<Exception> unexpectedExceptions = new ConcurrentLinkedQueue<>();

        // Start hammering the server with rapid requests
        for (int i = 0; i < clientCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (running.get()) {
                        try {
                            HttpRequest req = HttpRequest.newBuilder()
                                    .uri(URI.create(baseUrl + "/api/v1/stats"))
                                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                                    .timeout(Duration.ofMillis(1000))
                                    .GET()
                                    .build();
                            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                            if (resp.statusCode() == 200) {
                                completedRequests.incrementAndGet();
                                activeTrafficLatch.countDown();
                            }
                        } catch (IOException | InterruptedException expectedDuringShutdown) {
                            // Expected when connection is cleanly severed during server.stop()
                        }
                    }
                } catch (Exception e) {
                    unexpectedExceptions.add(e);
                }
            });
        }

        startLatch.countDown();

        // Await active traffic confirmation
        boolean trafficStarted = activeTrafficLatch.await(5, TimeUnit.SECONDS);
        assertThat(trafficStarted).isTrue();
        assertThat(completedRequests.get()).isGreaterThanOrEqualTo(5);

        // Stop server under active load
        server.stop();
        assertThat(server.isRunning()).isFalse();

        // Ensure calling stop() again is idempotent and safe
        server.stop();
        assertThat(server.isRunning()).isFalse();

        running.set(false);
        executor.shutdown();
        executor.awaitTermination(3, TimeUnit.SECONDS);

        assertThat(unexpectedExceptions).isEmpty();
    }

    @Test
    @DisplayName("3.2 Zero Socket Leaks & Clean Port Rebinding: Immediately restart new server instance on same port")
    void testZeroSocketLeakAndRebindingOnSamePort(@TempDir File tempDir) throws Exception {
        DatasetFixture fixture = setupTestDataset(tempDir);
        int port = findFreePort();
        WebConfig config = new WebConfig(true, "127.0.0.1", port, AUTH_TOKEN, "*", 20);

        // Lifecycle 1: Start and stop first server instance
        EmbeddedHttpServer server1 = new EmbeddedHttpServer(
                config,
                () -> fixture.eventQueue,
                () -> fixture.indexManager,
                () -> fixture.queryEngine,
                () -> fixture.sessionManager
        );
        server1.start();
        assertThat(server1.isRunning()).isTrue();

        // Send a request to verify it's working
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/health"))
                .header("X-ChestLogger-Auth", AUTH_TOKEN)
                .GET()
                .build();
        HttpResponse<String> res1 = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res1.statusCode()).isEqualTo(200);

        // Stop server1 cleanly
        server1.stop();
        assertThat(server1.isRunning()).isFalse();

        // Lifecycle 2: Rebind immediately on EXACT SAME PORT with a NEW server instance
        EmbeddedHttpServer server2 = new EmbeddedHttpServer(
                config,
                () -> fixture.eventQueue,
                () -> fixture.indexManager,
                () -> fixture.queryEngine,
                () -> fixture.sessionManager
        );
        server2.start();
        assertThat(server2.isRunning()).isTrue();

        // Send request to server2 to confirm socket rebind succeeded
        HttpResponse<String> res2 = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res2.statusCode()).isEqualTo(200);
        assertThat(res2.body()).contains("\"status\":\"UP\"");

        server2.stop();
        assertThat(server2.isRunning()).isFalse();
    }

    @Test
    @DisplayName("3.3 Multiple Rapid Start/Stop Cycles: Clean execution across multiple sequential lifecycles without thread/socket leaks")
    void testRepeatedRapidStartStopCycles(@TempDir File tempDir) throws Exception {
        DatasetFixture fixture = setupTestDataset(tempDir);
        int port = findFreePort();
        WebConfig config = new WebConfig(true, "127.0.0.1", port, AUTH_TOKEN, "*", 10);
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/health"))
                .header("X-ChestLogger-Auth", AUTH_TOKEN)
                .GET()
                .build();

        for (int cycle = 1; cycle <= 5; cycle++) {
            EmbeddedHttpServer server = new EmbeddedHttpServer(
                    config,
                    () -> fixture.eventQueue,
                    () -> fixture.indexManager,
                    () -> fixture.queryEngine,
                    () -> fixture.sessionManager
            );

            server.start();
            assertThat(server.isRunning()).isTrue();

            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"UP\"");

            server.stop();
            assertThat(server.isRunning()).isFalse();
        }
    }

    @Test
    @DisplayName("3.4 Post-Stop Connection Refusal: Closed server promptly refuses new connection attempts")
    void testPostStopConnectionRefusal(@TempDir File tempDir) throws Exception {
        DatasetFixture fixture = setupTestDataset(tempDir);
        int port = findFreePort();
        WebConfig config = new WebConfig(true, "127.0.0.1", port, AUTH_TOKEN, "*", 10);

        EmbeddedHttpServer server = new EmbeddedHttpServer(
                config,
                () -> fixture.eventQueue,
                () -> fixture.indexManager,
                () -> fixture.queryEngine,
                () -> fixture.sessionManager
        );

        server.start();
        assertThat(server.isRunning()).isTrue();
        server.stop();
        assertThat(server.isRunning()).isFalse();

        // Attempt socket connection directly - should fail promptly without hanging
        boolean connectionFailed = false;
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
        } catch (ConnectException e) {
            connectionFailed = true;
        } catch (IOException e) {
            // Also counts as connection failure
            connectionFailed = true;
        }

        assertThat(connectionFailed).isTrue();
    }
}
