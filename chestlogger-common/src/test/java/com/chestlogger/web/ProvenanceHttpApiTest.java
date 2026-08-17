package com.chestlogger.web;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.index.IndexPointer;
import com.chestlogger.index.PersistentIndexManager;
import com.chestlogger.provenance.ConfidenceLevel;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit & integration test suite for REST API endpoint GET /api/v1/provenance.
 * Verifies parameter validation (400 Bad Request), authentication (401 Unauthorized),
 * HTTP method gating (405 Method Not Allowed), and full JSON graph serialization (200 OK).
 */
class ProvenanceHttpApiTest {

    private static final int TEST_PORT = 18097;
    private static final String AUTH_TOKEN = "test_provenance_secret_token_123";
    private static final String BASE_URL = "http://127.0.0.1:" + TEST_PORT;

    private EmbeddedHttpServer server;
    private PersistentIndexManager indexManager;
    private QueryEngine queryEngine;
    private QuerySessionManager sessionManager;
    private HttpClient httpClient;

    private UUID alexUuid;
    private UUID steveUuid;
    private long pos1;
    private long pos2;
    private long pos3;

    @BeforeEach
    void setUp(@TempDir File tempDir) throws Exception {
        HttpAuthValidator.resetRateLimiter();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        this.indexManager = new PersistentIndexManager(tempDir);
        this.sessionManager = new QuerySessionManager(25);

        StringTableDictionary dict = new StringTableDictionary();
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        StorageProfile profile = StorageProfile.BALANCED;

        this.alexUuid = UUID.randomUUID();
        this.steveUuid = UUID.randomUUID();

        this.pos1 = BlockPosUtil.pack(100, 64, -200);
        this.pos2 = BlockPosUtil.pack(105, 64, -200);
        this.pos3 = BlockPosUtil.pack(110, 64, -200);

        long baseTime = 1723824000000L;

        // --- Commodity Flow Seed Records (minecraft:diamond) ---
        // 1. Alex extracts 64 diamonds from pos1 at T=0
        TransactionLogEntry e1 = new TransactionLogEntry(
                1L, baseTime, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER,
                alexUuid, "Alex", "minecraft:overworld", pos1,
                List.of(new SlotDelta(0, "minecraft:diamond", -64, 64, 0, 0L))
        );

        // 2. Alex deposits 64 diamonds into pos2 at T=60s (within 5min tight custody window)
        TransactionLogEntry e2 = new TransactionLogEntry(
                2L, baseTime + 60_000L, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                alexUuid, "Alex", "minecraft:overworld", pos2,
                List.of(new SlotDelta(0, "minecraft:diamond", 64, 0, 64, 0L))
        );

        // 3. Hopper extracts 64 diamonds from pos2 at T=90s
        TransactionLogEntry e3 = new TransactionLogEntry(
                3L, baseTime + 90_000L, UUID.randomUUID(), ActionType.PICKUP, ActorType.HOPPER_BLOCK,
                null, "Hopper", "minecraft:overworld", pos2,
                List.of(new SlotDelta(0, "minecraft:diamond", -64, 64, 0, 0L))
        );

        // --- Non-Fungible Seed Records (minecraft:netherite_sword with fingerprint 9876543210123L) ---
        long swordFingerprint = 9876543210123L;
        // 4. Steve deposits named sword into pos3 at T=120s
        TransactionLogEntry e4 = new TransactionLogEntry(
                4L, baseTime + 120_000L, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                steveUuid, "Steve", "minecraft:overworld", pos3,
                List.of(new SlotDelta(0, "minecraft:netherite_sword", 1, 0, 1, swordFingerprint))
        );

        // 5. Steve extracts named sword from pos3 at T=180s
        TransactionLogEntry e5 = new TransactionLogEntry(
                5L, baseTime + 180_000L, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER,
                steveUuid, "Steve", "minecraft:overworld", pos3,
                List.of(new SlotDelta(0, "minecraft:netherite_sword", -1, 1, 0, swordFingerprint))
        );

        List<TransactionLogEntry> allEntries = List.of(e1, e2, e3, e4, e5);

        try (LogSegmentWriter writer = new LogSegmentWriter(tempDir, "chestlog", 0, 1L, compressor, profile, dict)) {
            writer.writeBatch(allEntries);
        }

        for (int i = 0; i < allEntries.size(); i++) {
            TransactionLogEntry e = allEntries.get(i);
            for (SlotDelta d : e.deltas()) {
                indexManager.index(new IndexPointer(
                        e.sequenceId(),
                        e.timestampMs(),
                        e.actorUuid(),
                        d.itemId(),
                        e.dimension(),
                        e.packedBlockPos(),
                        0,
                        32L,
                        i
                ));
            }
        }

        this.queryEngine = new QueryEngine(tempDir, compressor, indexManager);

        WebConfig config = new WebConfig(true, "127.0.0.1", TEST_PORT, AUTH_TOKEN, "*", 10);
        this.server = new EmbeddedHttpServer(
                config,
                () -> null,
                () -> indexManager,
                () -> queryEngine,
                () -> sessionManager
        );
        this.server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        HttpAuthValidator.resetRateLimiter();
    }

    // =========================================================================
    // 1. Authentication Tests
    // =========================================================================
    @Nested
    @DisplayName("1. Authentication & Security")
    class AuthenticationTests {

        @Test
        @DisplayName("Should reject unauthenticated requests with 401 Unauthorized")
        void testUnauthenticatedReturns401() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/provenance?item=minecraft:diamond"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).contains("\"error\":\"Unauthorized\"");
        }

        @Test
        @DisplayName("Should reject invalid token with 401 Unauthorized")
        void testInvalidTokenReturns401() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/provenance?item=minecraft:diamond"))
                    .header("X-ChestLogger-Auth", "invalid_forged_secret")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
        }

        @Test
        @DisplayName("Should accept valid token in header with 200 OK")
        void testValidHeaderAuth() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/provenance?item=minecraft:diamond"))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).contains("application/json"));
        }
    }

    // =========================================================================
    // 2. HTTP Method & Parameter Validation Tests (400 / 405)
    // =========================================================================
    @Nested
    @DisplayName("2. Parameter Validation & Error Handling")
    class ParameterValidationTests {

        @Test
        @DisplayName("Should reject missing item / itemId parameter with 400 Bad Request")
        void testMissingItemParameterReturns400() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/provenance?x=100&y=64&z=-200"))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("Missing required parameter 'item' or 'itemId'");
        }

        @ParameterizedTest(name = "Invalid numeric parameter path: {0}")
        @ValueSource(strings = {
                "/api/v1/provenance?item=minecraft:diamond&x=abc&y=64&z=-200",
                "/api/v1/provenance?item=minecraft:diamond&x=100&y=invalid&z=-200",
                "/api/v1/provenance?item=minecraft:diamond&x=100&y=64&z=bad",
                "/api/v1/provenance?item=minecraft:diamond&x=100&y=64", // incomplete coords
                "/api/v1/provenance?item=minecraft:diamond&fingerprint=not_a_number",
                "/api/v1/provenance?item=minecraft:diamond&maxHops=invalid_hop"
        })
        @DisplayName("Should reject invalid or incomplete numeric parameters with 400 Bad Request")
        void testInvalidNumericParamsReturn400(String path) throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + path))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("\"error\":");
            assertThat(response.body()).doesNotContain("Internal Server Error");
        }

        @Test
        @DisplayName("Should reject non-GET methods with 405 Method Not Allowed")
        void testDisallowedMethodsReturn405() throws Exception {
            HttpRequest postReq = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/provenance?item=minecraft:diamond"))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> postRes = httpClient.send(postReq, HttpResponse.BodyHandlers.ofString());
            assertThat(postRes.statusCode()).isEqualTo(405);
        }
    }

    // =========================================================================
    // 3. Provenance Graph Resolution & JSON Schema Validation
    // =========================================================================
    @Nested
    @DisplayName("3. Provenance Graph Resolution & JSON Serialization")
    class ProvenanceResolutionTests {

        @Test
        @DisplayName("Should resolve commodity provenance graph and return complete JSON schema")
        void testCommodityProvenanceResolution() throws Exception {
            String url = BASE_URL + "/api/v1/provenance?x=105&y=64&z=-200&dim=minecraft:overworld&item=minecraft:diamond";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).contains("application/json"));

            String body = response.body();
            assertThat(body).isNotBlank();

            // Validate root graph properties
            assertThat(body).contains("\"targetItemId\":\"minecraft:diamond\"");
            assertThat(body).contains("\"totalSteps\":3");
            assertThat(body).contains("\"overallConfidence\":");

            // Validate nodes array and fields
            assertThat(body).contains("\"nodes\":[");
            assertThat(body).contains("\"stepIndex\":0");
            assertThat(body).contains("\"stepIndex\":1");
            assertThat(body).contains("\"stepIndex\":2");
            assertThat(body).contains("\"actorName\":\"Alex\"");
            assertThat(body).contains("\"actorName\":\"Hopper\"");
            assertThat(body).contains("\"actionType\":\"PICKUP\"");
            assertThat(body).contains("\"actionType\":\"PLACE\"");
            assertThat(body).contains("\"actorType\":\"PLAYER\"");
            assertThat(body).contains("\"actorType\":\"HOPPER_BLOCK\"");
            assertThat(body).contains("\"dimension\":\"minecraft:overworld\"");
            assertThat(body).contains("\"x\":100");
            assertThat(body).contains("\"x\":105");
            assertThat(body).contains("\"deltaQuantity\":-64");
            assertThat(body).contains("\"deltaQuantity\":64");
            assertThat(body).contains("\"notes\":");

            // Validate edges array and fields
            assertThat(body).contains("\"edges\":[");
            assertThat(body).contains("\"fromIndex\":0");
            assertThat(body).contains("\"toIndex\":1");
            assertThat(body).contains("\"timeDeltaMs\":60000");
            assertThat(body).contains("\"transitionType\":\"DIRECT_CUSTODY\"");
            assertThat(body).contains("\"transitionType\":\"AUTOMATION_TRANSFER\"");
        }

        @Test
        @DisplayName("Should resolve non-fungible provenance using 64-bit metadata fingerprint with EXACT_LINKAGE")
        void testNonFungibleProvenanceResolution() throws Exception {
            long swordFingerprint = 9876543210123L;
            String url = BASE_URL + "/api/v1/provenance?x=110&y=64&z=-200&dim=minecraft:overworld&item=minecraft:netherite_sword&fingerprint=" + swordFingerprint;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);

            String body = response.body();
            assertThat(body).contains("\"targetItemId\":\"minecraft:netherite_sword\"");
            assertThat(body).contains("\"totalSteps\":2");
            assertThat(body).contains("\"overallConfidence\":\"EXACT_LINKAGE\"");
            assertThat(body).contains("\"actorName\":\"Steve\"");
            assertThat(body).contains("\"confidence\":\"EXACT_LINKAGE\"");
            assertThat(body).contains("\"transitionType\":\"CONTAINER_HANDOFF\"");
        }

        @Test
        @DisplayName("Should return valid empty graph with totalSteps=0 for nonexistent item")
        void testEmptyProvenanceGraph() throws Exception {
            String url = BASE_URL + "/api/v1/provenance?item=minecraft:beacon&x=999&y=99&z=999";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);

            String body = response.body();
            assertThat(body).contains("\"targetItemId\":\"minecraft:beacon\"");
            assertThat(body).contains("\"totalSteps\":0");
            assertThat(body).contains("\"nodes\":[]");
            assertThat(body).contains("\"edges\":[]");
        }

        @Test
        @DisplayName("Should support parameter aliases 'itemId' and 'dimension'")
        void testParameterAliases() throws Exception {
            String url = BASE_URL + "/api/v1/provenance?itemId=minecraft:diamond&dimension=minecraft:overworld&x=105&y=64&z=-200&maxHops=25";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);

            String body = response.body();
            assertThat(body).contains("\"targetItemId\":\"minecraft:diamond\"");
            assertThat(body).contains("\"totalSteps\":3");
        }
    }
}
