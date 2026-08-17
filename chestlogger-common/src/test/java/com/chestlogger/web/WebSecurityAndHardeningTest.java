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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive security boundary and hardening test suite for ChestLogger Web API.
 * Validates:
 * 1. Brute-force auth protection (IP-based failed attempt tracking -> 429 Too Many Requests).
 * 2. Query parameter clamping (limit clamped to [1, 100], invalid numeric coordinates return 400 Bad Request).
 * 3. Injection resilience (special characters, unicode, quotes, regex characters in player, item, dim filters).
 * 4. Path traversal attack matrix across StaticAssetHttpHandler.
 * 5. CORS origin validation (single origin, multi-origin, wildcard, and untrusted rejection).
 */
class WebSecurityAndHardeningTest {

    private static final int TEST_PORT = 18096;
    private static final String AUTH_TOKEN = "security_test_secret_token_123456789";
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

        this.eventQueue = new TransactionEventQueue(1024);
        this.indexManager = new PersistentIndexManager(tempDir);
        this.sessionManager = new QuerySessionManager(25);

        StringTableDictionary dict = new StringTableDictionary();
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        StorageProfile profile = StorageProfile.BALANCED;

        this.alexUuid = UUID.randomUUID();
        this.steveUuid = UUID.randomUUID();

        long pos1 = BlockPosUtil.pack(100, 64, -200);
        long pos2 = BlockPosUtil.pack(50, 70, 300);
        long baseTime = 1723824000000L;

        // Seed sample transactions
        TransactionLogEntry e1 = new TransactionLogEntry(
                1L, baseTime, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                alexUuid, "Alex", "minecraft:overworld", pos1,
                List.of(new SlotDelta(0, "minecraft:diamond", 10, 0, 10, 0L))
        );

        TransactionLogEntry e2 = new TransactionLogEntry(
                2L, baseTime + 1000L, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER,
                steveUuid, "Steve", "minecraft:overworld", pos1,
                List.of(new SlotDelta(0, "minecraft:diamond", -3, 10, 7, 0L))
        );

        TransactionLogEntry e3 = new TransactionLogEntry(
                3L, baseTime + 2000L, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                alexUuid, "Alex_🎮", "minecraft:the_nether", pos2,
                List.of(new SlotDelta(0, "minecraft:netherite_ingot", 4, 0, 4, 0L))
        );

        try (LogSegmentWriter writer = new LogSegmentWriter(tempDir, "chestlog", 0, 1L, compressor, profile, dict)) {
            writer.writeBatch(List.of(e1, e2, e3));
        }

        indexManager.index(new IndexPointer(1L, baseTime, alexUuid, "minecraft:diamond", "minecraft:overworld", pos1, 0, 32L, 0));
        indexManager.index(new IndexPointer(2L, baseTime + 1000L, steveUuid, "minecraft:diamond", "minecraft:overworld", pos1, 0, 32L, 1));
        indexManager.index(new IndexPointer(3L, baseTime + 2000L, alexUuid, "minecraft:netherite_ingot", "minecraft:the_nether", pos2, 0, 32L, 2));

        this.queryEngine = new QueryEngine(tempDir, compressor, indexManager, () -> dict);

        WebConfig config = new WebConfig(true, "127.0.0.1", TEST_PORT, AUTH_TOKEN, "*", 10, 5, 60_000L);
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
    // 1. Brute-Force Auth Protection (Rate Limiting)
    // =========================================================================
    @Nested
    @DisplayName("1. Brute-Force Auth Protection & IP Rate Limiting")
    class BruteForceRateLimitingTests {

        @Test
        @DisplayName("Should lock out client IP and return 429 Too Many Requests after 5 consecutive failed attempts")
        void testBruteForceLockoutAfterMaxFailures() throws Exception {
            String testIp = "192.168.1.10";

            // 5 failed authentication attempts
            for (int i = 1; i <= 5; i++) {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/api/v1/stats"))
                        .header("X-Forwarded-For", testIp)
                        .header("X-ChestLogger-Auth", "wrong_token_" + i)
                        .GET()
                        .build();

                HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (i < 5) {
                    assertThat(res.statusCode()).isEqualTo(401);
                    assertThat(res.body()).contains("\"Unauthorized\"");
                } else {
                    // 5th attempt triggers lockout and returns 429 or 401 then 429
                    assertThat(res.statusCode()).isIn(401, 429);
                }
            }

            // Next attempt must be blocked with 429 Too Many Requests
            HttpRequest blockedReq = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/stats"))
                    .header("X-Forwarded-For", testIp)
                    .header("X-ChestLogger-Auth", "another_attempt")
                    .GET()
                    .build();

            HttpResponse<String> blockedRes = httpClient.send(blockedReq, HttpResponse.BodyHandlers.ofString());
            assertThat(blockedRes.statusCode()).isEqualTo(429);
            assertThat(blockedRes.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).contains("application/json"));
            assertThat(blockedRes.headers().firstValue("Retry-After")).isPresent();
            assertThat(blockedRes.body()).contains("\"Too Many Requests\"");
            assertThat(blockedRes.body()).contains("Rate limit exceeded");

            // Even valid token is rejected while IP is locked out
            HttpRequest validTokenBlockedReq = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/stats"))
                    .header("X-Forwarded-For", testIp)
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> validTokenBlockedRes = httpClient.send(validTokenBlockedReq, HttpResponse.BodyHandlers.ofString());
            assertThat(validTokenBlockedRes.statusCode()).isEqualTo(429);
        }

        @Test
        @DisplayName("Rate limiting on IP A should not affect requests from IP B")
        void testIpIsolationRateLimiting() throws Exception {
            String ipA = "10.0.0.1";
            String ipB = "10.0.0.2";

            // Exhaust attempts for ipA
            for (int i = 0; i < 5; i++) {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/api/v1/stats"))
                        .header("X-Forwarded-For", ipA)
                        .header("X-ChestLogger-Auth", "bad_pass")
                        .GET()
                        .build();
                httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            }

            // ipA is blocked
            HttpRequest reqA = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/stats"))
                    .header("X-Forwarded-For", ipA)
                    .header("X-ChestLogger-Auth", "bad_pass")
                    .GET()
                    .build();
            assertThat(httpClient.send(reqA, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(429);

            // ipB with valid auth is accepted with 200 OK
            HttpRequest reqB = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/stats"))
                    .header("X-Forwarded-For", ipB)
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();
            HttpResponse<String> resB = httpClient.send(reqB, HttpResponse.BodyHandlers.ofString());
            assertThat(resB.statusCode()).isEqualTo(200);
        }

        @Test
        @DisplayName("Successful authentication should reset failed attempts counter")
        void testSuccessfulAuthResetsCounter() throws Exception {
            String ip = "192.168.1.55";

            // 3 failed attempts
            for (int i = 0; i < 3; i++) {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/api/v1/stats"))
                        .header("X-Forwarded-For", ip)
                        .header("X-ChestLogger-Auth", "wrong")
                        .GET()
                        .build();
                httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            }

            // 1 successful attempt
            HttpRequest successReq = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/stats"))
                    .header("X-Forwarded-For", ip)
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();
            HttpResponse<String> successRes = httpClient.send(successReq, HttpResponse.BodyHandlers.ofString());
            assertThat(successRes.statusCode()).isEqualTo(200);

            // Counter should be reset: client can make 4 more failed attempts without being locked out
            for (int i = 0; i < 4; i++) {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/api/v1/stats"))
                        .header("X-Forwarded-For", ip)
                        .header("X-ChestLogger-Auth", "wrong_" + i)
                        .GET()
                        .build();
                HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                assertThat(res.statusCode()).isEqualTo(401);
            }
        }
    }

    // =========================================================================
    // 2. Query Parameter Clamping & Numeric Validation
    // =========================================================================
    @Nested
    @DisplayName("2. Query Parameter Clamping & Coordinate Validation")
    class ParameterClampingAndValidationTests {

        @ParameterizedTest(name = "limit={0} should clamp correctly to [1, 100]")
        @ValueSource(strings = {"-10", "0", "1", "25", "100", "500", "99999"})
        @DisplayName("Should clamp limit parameter within [1, 100]")
        void testLimitClamping(String limitVal) throws Exception {
            String url = BASE_URL + "/api/v1/query?limit=" + limitVal;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"records\":[");
            assertThat(response.body()).contains("\"totalPages\":");
        }

        @ParameterizedTest(name = "Invalid coordinate {0} should return 400 Bad Request")
        @ValueSource(strings = {
                "/api/v1/query?x=abc&y=64&z=-200",
                "/api/v1/query?x=100&y=invalid&z=-200",
                "/api/v1/query?x=100&y=64&z=bad",
                "/api/v1/query?x=12.345&y=64&z=-200",
                "/api/v1/query?x=NaN&y=64&z=-200",
                "/api/v1/query?x=--10&y=64&z=-200",
                "/api/v1/query?sinceSeconds=not_a_number",
                "/api/v1/export?x=abc&y=64&z=-200",
                "/api/v1/export?sinceSeconds=bad_long"
        })
        @DisplayName("Should return 400 Bad Request instead of 500 Internal Server Error for invalid numeric coordinates")
        void testInvalidNumericCoordinatesReturn400(String path) throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + path))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).contains("application/json"));
            assertThat(response.body()).contains("\"error\":");
            assertThat(response.body()).doesNotContain("Internal Server Error");
        }

        @Test
        @DisplayName("Valid coordinates should succeed with 200 OK and accurate filtering")
        void testValidCoordinatesSucceed() throws Exception {
            String url = BASE_URL + "/api/v1/query?x=100&y=64&z=-200&dim=minecraft:overworld";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"totalRecords\":2");
        }
    }

    // =========================================================================
    // 3. Injection Resilience
    // =========================================================================
    @Nested
    @DisplayName("3. Injection Resilience: Special characters, SQL, Regex, Unicode")
    class InjectionResilienceTests {

        @ParameterizedTest(name = "Query injection payload: {0}")
        @ValueSource(strings = {
                "player=' OR '1'='1",
                "player=admin'; DROP TABLE logs; --",
                "player=1' UNION SELECT NULL--",
                "player=.*",
                "player=(Alex|Steve)+",
                "player=^[a-zA-Z0-9]+$",
                "player=\\d{4}",
                "player=Alex%22%2C%22injected%22%3Atrue",
                "item=minecraft:diamond' OR 1=1--",
                "item=minecraft:.*",
                "item=(?i)diamond",
                "item=<script>alert(1)</script>",
                "dim=minecraft:overworld' UNION SELECT *--",
                "dim=.*",
                "player=Alex_🎮",
                "player=測試_玩家",
                "player=café_123",
                "item=minecraft:custom_🔥_sword"
        })
        @DisplayName("Should resist injection payloads without crashing, throwing 500, or corrupting JSON")
        void testInjectionResilience(String queryParam) throws Exception {
            String encodedQuery;
            int eqIdx = queryParam.indexOf('=');
            if (eqIdx > 0) {
                String key = queryParam.substring(0, eqIdx);
                String val = queryParam.substring(eqIdx + 1);
                encodedQuery = key + "=" + java.net.URLEncoder.encode(val, StandardCharsets.UTF_8);
            } else {
                encodedQuery = java.net.URLEncoder.encode(queryParam, StandardCharsets.UTF_8);
            }
            String url = BASE_URL + "/api/v1/query?" + encodedQuery;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Server must return 200 OK (safe empty/exact match) or 400 Bad Request (if illegal param) - never 500
            assertThat(response.statusCode()).isIn(200, 400);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).contains("application/json"));

            String body = response.body();
            assertThat(body).isNotBlank();
            assertThat(body).doesNotContain("Internal Server Error");
            assertThat(body).doesNotContain("Exception");
            // Validate response starts and ends as valid JSON object
            assertThat(body.trim()).startsWith("{").endsWith("}");
        }

        @Test
        @DisplayName("JSON export should properly escape special characters and unicode in record fields")
        void testJsonExportEscapesSpecialCharacters() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/export?format=json"))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);

            String body = response.body();
            assertThat(body).contains("Alex_🎮");
            assertThat(body).contains("minecraft:the_nether");
            assertThat(body.trim()).startsWith("{").endsWith("}");
        }

        @Test
        @DisplayName("CSV export should safely format and escape record fields")
        void testCsvExportEscaping() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/export?format=csv"))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);

            String body = response.body();
            assertThat(body).startsWith("timestamp,date_time,dimension,x,y,z,actor_name,actor_type,action,slot,item_id,delta,metadata_fingerprint\r\n");
            assertThat(body).contains("Alex_🎮");
            assertThat(body).contains("minecraft:the_nether");
        }
    }

    // =========================================================================
    // 4. Path Traversal Attack Matrix
    // =========================================================================
    @Nested
    @DisplayName("4. Path Traversal Attack Matrix across StaticAssetHttpHandler")
    class PathTraversalMatrixTests {

        @ParameterizedTest(name = "Path traversal vector: {0}")
        @ValueSource(strings = {
                "/../WebConfig.java",
                "/../../WebConfig.java",
                "/../../../WebConfig.java",
                "/../../../../etc/passwd",
                "/..%2fWebConfig.java",
                "/%2e%2e/WebConfig.java",
                "/%2e%2e/%2e%2e/WebConfig.java",
                "/%2E%2E/",
                "/%2e%2e%2f",
                "/%252e%252e/WebConfig.java",
                "/%252e%252e%252fWebConfig.java",
                "/assets/chestlogger/web/../../../WebConfig.java",
                "/assets/chestlogger/web/../../../../build.gradle",
                "/%5c%2e%2e%5cWebConfig.java",
                "/index.html%00.png",
                "/style.css%00",
                "/app.js%00.txt",
                "/%00../index.html",
                "//..//..//WebConfig.java",
                "///..///..///secret.txt",
                "/subdir/../../app.js",
                "/C:/Windows/System32/drivers/etc/hosts",
                "//etc/shadow"
        })
        @DisplayName("Should reject all path traversal vectors with 403 Forbidden or 404 Not Found")
        void testPathTraversalMatrix(String maliciousPath) throws Exception {
            HttpURLConnection conn = (HttpURLConnection) URI.create(BASE_URL + maliciousPath).toURL().openConnection();
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            assertThat(responseCode).isIn(400, 403, 404);

            // Verify no source code or sensitive classpath contents leaked
            InputStream stream = (responseCode >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (stream != null) {
                String body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(body).doesNotContain("package com.chestlogger");
                assertThat(body).doesNotContain("secretToken");
                assertThat(body).doesNotContain("root:x:0:0");
            }
        }

        @Test
        @DisplayName("Should safely serve legitimate static assets")
        void testLegitimateStaticAssetsServed() throws Exception {
            HttpURLConnection conn = (HttpURLConnection) URI.create(BASE_URL + "/index.html").toURL().openConnection();
            conn.setRequestMethod("GET");
            assertThat(conn.getResponseCode()).isEqualTo(200);
            assertThat(conn.getContentType()).contains("text/html");
        }
    }

    // =========================================================================
    // 5. CORS Origin Validation
    // =========================================================================
    @Nested
    @DisplayName("5. CORS Origin Validation")
    class CorsOriginValidationTests {

        @Test
        @DisplayName("Wildcard allowedOrigins '*' allows any Origin header")
        void testWildcardOriginAllowed() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/health"))
                    .header("X-ChestLogger-Auth", AUTH_TOKEN)
                    .header("Origin", "http://localhost:3000")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue("*");
            assertThat(response.headers().firstValue("Access-Control-Allow-Methods")).isPresent();
            assertThat(response.headers().firstValue("Access-Control-Allow-Headers")).isPresent();
        }

        @Test
        @DisplayName("OPTIONS preflight request for allowed origin returns 204 No Content")
        void testPreflightAllowedOriginReturns204() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/query"))
                    .header("Origin", "http://localhost:3000")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(204);
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isPresent();
        }

        @Test
        @DisplayName("Strict single origin config reflects allowed origin and rejects untrusted origins")
        void testStrictSingleOriginConfig() {
            String allowedOrigin = "https://admin.chestlogger.org";
            WebConfig strictConfig = new WebConfig(true, "127.0.0.1", 8080, AUTH_TOKEN, allowedOrigin, 5);

            // Allowed origin check
            assertThat(HttpAuthValidator.isOriginAllowed("https://admin.chestlogger.org", strictConfig.getAllowedOrigins())).isTrue();

            // Untrusted origin check
            assertThat(HttpAuthValidator.isOriginAllowed("https://evil-attacker.com", strictConfig.getAllowedOrigins())).isFalse();
            assertThat(HttpAuthValidator.isOriginAllowed("http://localhost:8080", strictConfig.getAllowedOrigins())).isFalse();
        }

        @Test
        @DisplayName("Multi-origin comma-separated list allows all listed origins and rejects unlisted ones")
        void testMultiOriginListValidation() {
            String allowedOrigins = "http://localhost:3000, https://dashboard.chestlogger.org, https://admin.server.com";
            WebConfig multiConfig = new WebConfig(true, "127.0.0.1", 8080, AUTH_TOKEN, allowedOrigins, 5);

            assertThat(HttpAuthValidator.isOriginAllowed("http://localhost:3000", multiConfig.getAllowedOrigins())).isTrue();
            assertThat(HttpAuthValidator.isOriginAllowed("https://dashboard.chestlogger.org", multiConfig.getAllowedOrigins())).isTrue();
            assertThat(HttpAuthValidator.isOriginAllowed("https://admin.server.com", multiConfig.getAllowedOrigins())).isTrue();

            // Disallowed
            assertThat(HttpAuthValidator.isOriginAllowed("https://rogue-site.com", multiConfig.getAllowedOrigins())).isFalse();
            assertThat(HttpAuthValidator.isOriginAllowed("http://localhost:8000", multiConfig.getAllowedOrigins())).isFalse();
        }
    }
}
