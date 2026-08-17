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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test suite for ChestLogger Embedded HTTP REST API.
 * Validates telemetry stats, multi-dimensional queries, CSV/JSON log exports, and authentication security.
 */
class RestApiIntegrationTest {

    private static final int TEST_PORT = 18095;
    private static final String AUTH_TOKEN = "test_auth_secret_token_abcdef123456";
    private static final String BASE_URL = "http://127.0.0.1:" + TEST_PORT;

    private EmbeddedHttpServer server;
    private TransactionEventQueue eventQueue;
    private PersistentIndexManager indexManager;
    private QueryEngine queryEngine;
    private QuerySessionManager sessionManager;
    private HttpClient httpClient;

    private UUID alexUuid;
    private UUID steveUuid;

    @BeforeEach
    void setUp(@TempDir File tempDir) throws Exception {
        HttpAuthValidator.resetRateLimiter();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        this.eventQueue = new TransactionEventQueue(2048);
        this.indexManager = new PersistentIndexManager(tempDir);
        this.sessionManager = new QuerySessionManager(25);

        StringTableDictionary dict = new StringTableDictionary();
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        StorageProfile profile = StorageProfile.BALANCED;

        this.alexUuid = UUID.randomUUID();
        this.steveUuid = UUID.randomUUID();

        long pos1 = BlockPosUtil.pack(100, 64, -200);
        long pos2 = BlockPosUtil.pack(50, 70, 300);
        long baseTime = 1723824000000L; // Fixed reference epoch

        // 1. Alex deposits 10 diamonds at pos1
        TransactionLogEntry e1 = new TransactionLogEntry(
                1L, baseTime, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                alexUuid, "Alex", "minecraft:overworld", pos1,
                List.of(new SlotDelta(0, "minecraft:diamond", 10, 0, 10, 0L))
        );

        // 2. Alex deposits 5 emeralds at pos1
        TransactionLogEntry e2 = new TransactionLogEntry(
                2L, baseTime + 1000L, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                alexUuid, "Alex", "minecraft:overworld", pos1,
                List.of(new SlotDelta(1, "minecraft:emerald", 5, 0, 5, 0L))
        );

        // 3. Steve takes 3 diamonds from pos1
        TransactionLogEntry e3 = new TransactionLogEntry(
                3L, baseTime + 2000L, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER,
                steveUuid, "Steve", "minecraft:overworld", pos1,
                List.of(new SlotDelta(0, "minecraft:diamond", -3, 10, 7, 0L))
        );

        // 4. Steve deposits 12 gold ingots at pos2
        TransactionLogEntry e4 = new TransactionLogEntry(
                4L, baseTime + 3000L, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                steveUuid, "Steve", "minecraft:overworld", pos2,
                List.of(new SlotDelta(0, "minecraft:gold_ingot", 12, 0, 12, 0L))
        );

        // 5. Hopper extracts 1 iron ingot at pos2
        TransactionLogEntry e5 = new TransactionLogEntry(
                5L, baseTime + 4000L, UUID.randomUUID(), ActionType.PICKUP, ActorType.HOPPER_BLOCK,
                null, "Hopper", "minecraft:overworld", pos2,
                List.of(new SlotDelta(0, "minecraft:iron_ingot", -1, 1, 0, 0L))
        );

        List<TransactionLogEntry> allEntries = List.of(e1, e2, e3, e4, e5);

        // Write batch to clog segment
        try (LogSegmentWriter writer = new LogSegmentWriter(tempDir, "chestlog", 0, 1L, compressor, profile, dict)) {
            writer.writeBatch(allEntries);
        }

        // Index pointers
        indexManager.index(new IndexPointer(1L, baseTime, alexUuid, "minecraft:diamond", "minecraft:overworld", pos1, 0, 32L, 0));
        indexManager.index(new IndexPointer(2L, baseTime + 1000L, alexUuid, "minecraft:emerald", "minecraft:overworld", pos1, 0, 32L, 1));
        indexManager.index(new IndexPointer(3L, baseTime + 2000L, steveUuid, "minecraft:diamond", "minecraft:overworld", pos1, 0, 32L, 2));
        indexManager.index(new IndexPointer(4L, baseTime + 3000L, steveUuid, "minecraft:gold_ingot", "minecraft:overworld", pos2, 0, 32L, 3));
        indexManager.index(new IndexPointer(5L, baseTime + 4000L, null, "minecraft:iron_ingot", "minecraft:overworld", pos2, 0, 32L, 4));

        this.queryEngine = new QueryEngine(tempDir, compressor, indexManager, () -> dict);

        // Configure and start server
        WebConfig config = new WebConfig(true, "127.0.0.1", TEST_PORT, AUTH_TOKEN, "*", 10);
        this.server = new EmbeddedHttpServer(
                config,
                () -> eventQueue,
                () -> indexManager,
                () -> queryEngine,
                () -> sessionManager
        );
        this.server.start();
        assertThat(server.isRunning()).isTrue();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        HttpAuthValidator.resetRateLimiter();
    }

    // =========================================================================
    // 1. /api/v1/stats Telemetry
    // =========================================================================
    @Nested
    @DisplayName("1. Telemetry API: GET /api/v1/stats")
    class StatsEndpointTests {

        @Test
        @DisplayName("Should return accurate telemetry metrics in JSON format matching queue and index state")
        void testStatsReturnsAccurateTelemetry() throws Exception {
            // Seed event queue with simulated in-memory transactions
            for (int i = 0; i < 7; i++) {
                eventQueue.offer(new TransactionLogEntry(
                        100L + i, System.currentTimeMillis(), UUID.randomUUID(),
                        ActionType.PLACE, ActorType.PLAYER, alexUuid, "Alex",
                        "minecraft:overworld", 0L,
                        List.of(new SlotDelta(0, "minecraft:dirt", 1, 0, 1, 0L))
                ));
            }
            // Drain 2 transactions
            List<TransactionLogEntry> drained = new ArrayList<>();
            eventQueue.drain(drained, 2);

            // Fetch /api/v1/stats using standard HttpClient
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/stats"))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).contains("application/json"));

            String body = response.body();
            assertThat(body).isNotBlank();

            // Verify queue metrics: depth=5, capacity=2048, enqueued=7, drained=2, dropped=0
            assertThat(body).contains("\"queue\":{");
            assertThat(body).contains("\"depth\":5");
            assertThat(body).contains("\"capacity\":2048");
            assertThat(body).contains("\"enqueued\":7");
            assertThat(body).contains("\"dropped\":0");
            assertThat(body).contains("\"drained\":2");

            // Verify index metrics: size=5 (pre-indexed entries)
            assertThat(body).contains("\"index\":{\"size\":5}");

            // Verify server uptime presence
            assertThat(body).contains("\"uptimeMs\":");
        }

        @Test
        @DisplayName("Should reflect real-time queue changes on successive stats queries")
        void testStatsDynamicUpdates() throws Exception {
            // Check initial depth
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/stats"))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> res1 = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(res1.body()).contains("\"depth\":0");
            assertThat(res1.body()).contains("\"enqueued\":0");

            // Offer 3 events
            eventQueue.offer(new TransactionLogEntry(201L, System.currentTimeMillis(), UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER, alexUuid, "Alex", "minecraft:overworld", 0L, List.of(new SlotDelta(0, "minecraft:stone", 1, 0, 1, 0L))));
            eventQueue.offer(new TransactionLogEntry(202L, System.currentTimeMillis(), UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER, alexUuid, "Alex", "minecraft:overworld", 0L, List.of(new SlotDelta(0, "minecraft:stone", 1, 0, 1, 0L))));
            eventQueue.offer(new TransactionLogEntry(203L, System.currentTimeMillis(), UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER, alexUuid, "Alex", "minecraft:overworld", 0L, List.of(new SlotDelta(0, "minecraft:stone", 1, 0, 1, 0L))));

            HttpResponse<String> res2 = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(res2.body()).contains("\"depth\":3");
            assertThat(res2.body()).contains("\"enqueued\":3");
        }
    }

    // =========================================================================
    // 2. /api/v1/query Search & Pagination
    // =========================================================================
    @Nested
    @DisplayName("2. Query API: GET /api/v1/query")
    class QueryEndpointTests {

        @Test
        @DisplayName("Should query by exact coordinates and return structured JSON records")
        void testQueryByCoordinates() throws Exception {
            String url = BASE_URL + "/api/v1/query?x=100&y=64&z=-200&dim=minecraft:overworld";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).contains("application/json"));

            String body = response.body();
            assertThat(body).contains("\"totalRecords\":3");
            assertThat(body).contains("\"totalPages\":1");
            assertThat(body).contains("\"page\":1");
            assertThat(body).contains("\"records\":[");
            assertThat(body).contains("\"actorName\":\"Alex\"");
            assertThat(body).contains("\"actorName\":\"Steve\"");
            assertThat(body).contains("\"item\":\"minecraft:diamond\"");
            assertThat(body).contains("\"item\":\"minecraft:emerald\"");
            assertThat(body).contains("\"delta\":10");
            assertThat(body).contains("\"delta\":5");
            assertThat(body).contains("\"delta\":-3");
            // pos2 records should not be present
            assertThat(body).doesNotContain("minecraft:gold_ingot");
            assertThat(body).doesNotContain("minecraft:iron_ingot");
        }

        @Test
        @DisplayName("Should filter records by player actor name")
        void testQueryByPlayer() throws Exception {
            String url = BASE_URL + "/api/v1/query?player=Alex";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);

            String body = response.body();
            assertThat(body).contains("\"totalRecords\":2");
            assertThat(body).contains("\"actorName\":\"Alex\"");
            assertThat(body).doesNotContain("\"actorName\":\"Steve\"");
            assertThat(body).doesNotContain("\"actorName\":\"Hopper\"");
        }

        @Test
        @DisplayName("Should filter records by item identifier")
        void testQueryByItem() throws Exception {
            String url = BASE_URL + "/api/v1/query?item=minecraft:gold_ingot";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);

            String body = response.body();
            assertThat(body).contains("\"totalRecords\":1");
            assertThat(body).contains("\"item\":\"minecraft:gold_ingot\"");
            assertThat(body).contains("\"delta\":12");
            assertThat(body).contains("\"actorName\":\"Steve\"");
        }

        @Test
        @DisplayName("Should paginate query results across multiple pages with limit parameter")
        void testQueryPagination() throws Exception {
            // Query pos1 (3 total records) with pageSize=2
            String page1Url = BASE_URL + "/api/v1/query?x=100&y=64&z=-200&page=1&limit=2";
            HttpRequest req1 = HttpRequest.newBuilder()
                    .uri(URI.create(page1Url))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> res1 = httpClient.send(req1, HttpResponse.BodyHandlers.ofString());
            assertThat(res1.statusCode()).isEqualTo(200);

            String body1 = res1.body();
            assertThat(body1).contains("\"totalRecords\":3");
            assertThat(body1).contains("\"totalPages\":2");
            assertThat(body1).contains("\"page\":1");

            // Extract queryId for session continuation
            int qIdStart = body1.indexOf("\"queryId\":\"") + 11;
            int qIdEnd = body1.indexOf("\"", qIdStart);
            String queryId = body1.substring(qIdStart, qIdEnd);
            assertThat(queryId).isNotBlank();

            // Request Page 2
            String page2Url = BASE_URL + "/api/v1/query?queryId=" + queryId + "&page=2";
            HttpRequest req2 = HttpRequest.newBuilder()
                    .uri(URI.create(page2Url))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> res2 = httpClient.send(req2, HttpResponse.BodyHandlers.ofString());
            assertThat(res2.statusCode()).isEqualTo(200);

            String body2 = res2.body();
            assertThat(body2).contains("\"queryId\":\"" + queryId + "\"");
            assertThat(body2).contains("\"page\":2");
            assertThat(body2).contains("\"totalRecords\":3");
            assertThat(body2).contains("\"totalPages\":2");
        }
    }

    // =========================================================================
    // 3. /api/v1/export CSV Stream (RFC 4180)
    // =========================================================================
    @Nested
    @DisplayName("3. Export API (CSV): GET /api/v1/export?format=csv")
    class ExportCsvEndpointTests {

        @Test
        @DisplayName("Should export transaction records as valid RFC 4180 compliant CSV stream with attachment header")
        void testExportCsvValidRfc4180() throws Exception {
            HttpURLConnection conn = (HttpURLConnection) URI.create(BASE_URL + "/api/v1/export?format=csv").toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-ChestLogger-Auth", AUTH_TOKEN);

            assertThat(conn.getResponseCode()).isEqualTo(200);
            assertThat(conn.getContentType()).contains("text/csv");
            assertThat(conn.getHeaderField("Content-Disposition"))
                    .startsWith("attachment; filename=\"chestlogger_export_")
                    .endsWith(".csv\"");

            String csvContent;
            try (InputStream in = conn.getInputStream()) {
                csvContent = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            // Verify header
            String expectedHeader = "timestamp,date_time,dimension,x,y,z,actor_name,actor_type,action,slot,item_id,delta,metadata_fingerprint";
            assertThat(csvContent).startsWith(expectedHeader + "\r\n");

            // Split into lines (RFC 4180 CRLF)
            String[] lines = csvContent.split("\r\n");
            // 1 header + 5 data rows = 6 lines
            assertThat(lines.length).isEqualTo(6);

            // Validate header columns count
            assertThat(lines[0].split(",").length).isEqualTo(13);

            // Validate individual rows contain expected data points
            assertThat(lines[1]).contains("minecraft:overworld,100,64,-200,Alex,PLAYER,PLACE,0,minecraft:diamond,10,0");
            assertThat(lines[2]).contains("minecraft:overworld,100,64,-200,Alex,PLAYER,PLACE,1,minecraft:emerald,5,0");
            assertThat(lines[3]).contains("minecraft:overworld,100,64,-200,Steve,PLAYER,PICKUP,0,minecraft:diamond,-3,0");
            assertThat(lines[4]).contains("minecraft:overworld,50,70,300,Steve,PLAYER,PLACE,0,minecraft:gold_ingot,12,0");
            assertThat(lines[5]).contains("minecraft:overworld,50,70,300,Hopper,HOPPER_BLOCK,PICKUP,0,minecraft:iron_ingot,-1,0");
        }

        @Test
        @DisplayName("Should export filtered CSV matching query parameters")
        void testFilteredCsvExport() throws Exception {
            String url = BASE_URL + "/api/v1/export?format=csv&x=50&y=70&z=300";
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-ChestLogger-Auth", AUTH_TOKEN);

            assertThat(conn.getResponseCode()).isEqualTo(200);

            String csvContent;
            try (InputStream in = conn.getInputStream()) {
                csvContent = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            String[] lines = csvContent.split("\r\n");
            // 1 header + 2 data rows for pos2
            assertThat(lines.length).isEqualTo(3);
            assertThat(csvContent).contains("minecraft:gold_ingot");
            assertThat(csvContent).contains("minecraft:iron_ingot");
            assertThat(csvContent).doesNotContain("minecraft:diamond");
        }
    }

    // =========================================================================
    // 4. /api/v1/export JSON Format
    // =========================================================================
    @Nested
    @DisplayName("4. Export API (JSON): GET /api/v1/export?format=json")
    class ExportJsonEndpointTests {

        @Test
        @DisplayName("Should export records as structured JSON payload with Content-Disposition")
        void testExportJson() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/export?format=json"))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).contains("application/json"));
            assertThat(response.headers().firstValue("Content-Disposition")).hasValueSatisfying(cd -> {
                assertThat(cd).startsWith("attachment; filename=\"chestlogger_export_");
                assertThat(cd).endsWith(".json\"");
            });

            String body = response.body();
            assertThat(body).contains("\"exportTimestamp\":");
            assertThat(body).contains("\"totalRecords\":5");
            assertThat(body).contains("\"records\":[");

            // Verify record fields
            assertThat(body).contains("\"actorName\":\"Alex\"");
            assertThat(body).contains("\"actorName\":\"Steve\"");
            assertThat(body).contains("\"actorName\":\"Hopper\"");
            assertThat(body).contains("\"itemId\":\"minecraft:diamond\"");
            assertThat(body).contains("\"itemId\":\"minecraft:emerald\"");
            assertThat(body).contains("\"itemId\":\"minecraft:gold_ingot\"");
            assertThat(body).contains("\"itemId\":\"minecraft:iron_ingot\"");
            assertThat(body).contains("\"delta\":10");
            assertThat(body).contains("\"delta\":-3");
            assertThat(body).contains("\"delta\":12");
        }

        @Test
        @DisplayName("Should filter JSON export by actor name")
        void testFilteredJsonExport() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/export?format=json&player=Steve"))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);

            String body = response.body();
            assertThat(body).contains("\"totalRecords\":2");
            assertThat(body).contains("\"actorName\":\"Steve\"");
            assertThat(body).doesNotContain("\"actorName\":\"Alex\"");
            assertThat(body).doesNotContain("\"actorName\":\"Hopper\"");
        }
    }

    // =========================================================================
    // 5. Token Authentication Security on All Endpoints
    // =========================================================================
    @Nested
    @DisplayName("5. Authentication & Security: Enforce auth tokens on all endpoints")
    class AuthenticationSecurityTests {

        @ParameterizedTest(name = "Endpoint {0} must reject missing auth token with 401")
        @ValueSource(strings = {
                "/api/v1/health",
                "/api/v1/stats",
                "/api/v1/query",
                "/api/v1/provenance",
                "/api/v1/export"
        })
        void testMissingTokenReturns401(String path) throws IOException {
            HttpURLConnection conn = (HttpURLConnection) URI.create(BASE_URL + path).toURL().openConnection();
            conn.setRequestMethod("GET");

            assertThat(conn.getResponseCode()).isEqualTo(401);
            assertThat(conn.getContentType()).contains("application/json");

            try (InputStream in = conn.getErrorStream()) {
                String errorBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(errorBody).contains("\"error\":\"Unauthorized\"");
            }
        }

        @ParameterizedTest(name = "Endpoint {0} must reject invalid auth token with 401")
        @ValueSource(strings = {
                "/api/v1/health",
                "/api/v1/stats",
                "/api/v1/query",
                "/api/v1/provenance",
                "/api/v1/export"
        })
        void testInvalidTokenReturns401(String path) throws IOException {
            HttpURLConnection conn = (HttpURLConnection) URI.create(BASE_URL + path).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-ChestLogger-Auth", "invalid_forged_token_0000");

            assertThat(conn.getResponseCode()).isEqualTo(401);
            assertThat(conn.getContentType()).contains("application/json");

            try (InputStream in = conn.getErrorStream()) {
                String errorBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(errorBody).contains("\"error\":\"Unauthorized\"");
            }
        }

        @ParameterizedTest(name = "Endpoint {0} accepts valid X-ChestLogger-Auth header with 200")
        @ValueSource(strings = {
                "/api/v1/health",
                "/api/v1/stats",
                "/api/v1/query",
                "/api/v1/provenance?item=minecraft:diamond",
                "/api/v1/export"
        })
        void testValidHeaderTokenReturns200(String path) throws IOException {
            HttpURLConnection conn = (HttpURLConnection) URI.create(BASE_URL + path).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-ChestLogger-Auth", AUTH_TOKEN);

            assertThat(conn.getResponseCode()).isEqualTo(200);
        }

        @ParameterizedTest(name = "Endpoint {0} accepts valid Authorization: Bearer token with 200")
        @ValueSource(strings = {
                "/api/v1/health",
                "/api/v1/stats",
                "/api/v1/query",
                "/api/v1/provenance?item=minecraft:diamond",
                "/api/v1/export"
        })
        void testValidBearerTokenReturns200(String path) throws IOException {
            HttpURLConnection conn = (HttpURLConnection) URI.create(BASE_URL + path).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + AUTH_TOKEN);

            assertThat(conn.getResponseCode()).isEqualTo(200);
        }

        @ParameterizedTest(name = "Endpoint {0} accepts valid query param ?token= with 200")
        @ValueSource(strings = {
                "/api/v1/health",
                "/api/v1/stats",
                "/api/v1/query",
                "/api/v1/provenance?item=minecraft:diamond",
                "/api/v1/export"
        })
        void testValidQueryParamTokenReturns200(String path) throws IOException {
            String separator = path.contains("?") ? "&" : "?";
            HttpURLConnection conn = (HttpURLConnection) URI.create(BASE_URL + path + separator + "token=" + AUTH_TOKEN).toURL().openConnection();
            conn.setRequestMethod("GET");

            assertThat(conn.getResponseCode()).isEqualTo(200);
        }
    }
}
