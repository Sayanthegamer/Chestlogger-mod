package com.chestlogger.web;

import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.security.IncidentClassification;
import com.chestlogger.security.IncidentRingBuffer;
import com.chestlogger.security.OwnerPresenceState;
import com.chestlogger.security.SecurityIncident;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentsHttpApiTest {

    private EmbeddedHttpServer server;
    private IncidentRingBuffer incidentBuffer;
    private WebConfig config;
    private HttpClient client;
    private int port;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        HttpAuthValidator.resetRateLimiter();
        incidentBuffer = new IncidentRingBuffer(200);

        config = new WebConfig();
        config.setEnabled(true);
        config.setHost("127.0.0.1");
        config.setPort(0); // auto-bind random available port
        config.setSecretToken("test-secret-token-32-chars-long!");

        server = new EmbeddedHttpServer(
                config,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> incidentBuffer
        );
        server.start();

        // Query reflection or socket address to find bound port
        port = config.getPort();
        if (port == 0) {
            // Find actual port from server socket
            try {
                var serverField = EmbeddedHttpServer.class.getDeclaredField("server");
                serverField.setAccessible(true);
                com.sun.net.httpserver.HttpServer rawServer = (com.sun.net.httpserver.HttpServer) serverField.get(server);
                port = rawServer.getAddress().getPort();
            } catch (Exception e) {
                port = 8080;
            }
        }
        baseUrl = "http://127.0.0.1:" + port;

        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        HttpAuthValidator.resetRateLimiter();
    }

    @Test
    @DisplayName("GET /api/v1/incidents without auth token returns 401 Unauthorized")
    void testGetIncidentsWithoutAuth() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/incidents"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("Unauthorized");
    }

    @Test
    @DisplayName("GET /api/v1/incidents with query parameter token is strictly rejected (401)")
    void testGetIncidentsWithQueryTokenRejected() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/incidents?token=" + config.getSecretToken()))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("GET /api/v1/incidents with valid X-ChestLogger-Auth header returns 200 and JSON array")
    void testGetIncidentsWithAuthHeader() throws Exception {
        UUID actorUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID ownerUuid = UUID.fromString("00000000-0000-0000-0000-000000000002");
        long packedPos = BlockPosUtil.pack(100, 64, -200);

        SecurityIncident incident = new SecurityIncident(
                1723849000000L,
                101L,
                IncidentClassification.CRITICAL_RAID,
                actorUuid,
                "RaidLeader",
                ownerUuid,
                "VictimOwner",
                OwnerPresenceState.offline(),
                packedPos,
                "minecraft:overworld",
                "minecraft:diamond",
                -64,
                "Critical raid burst detected across multiple containers",
                true
        );
        incidentBuffer.add(incident);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/incidents"))
                .header("X-ChestLogger-Auth", config.getSecretToken())
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).isPresent().hasValueSatisfying(ct -> assertThat(ct).contains("application/json"));

        String body = response.body();
        assertThat(body).startsWith("[");
        assertThat(body).endsWith("]");
        assertThat(body).contains("\"classification\":\"CRITICAL_RAID\"");
        assertThat(body).contains("\"actorName\":\"RaidLeader\"");
        assertThat(body).contains("\"ownerName\":\"VictimOwner\"");
        assertThat(body).contains("🔴 Offline");
        assertThat(body).contains("\"x\":100");
        assertThat(body).contains("\"y\":64");
        assertThat(body).contains("\"z\":-200");
        assertThat(body).contains("\"dimension\":\"minecraft:overworld\"");
        assertThat(body).contains("\"itemId\":\"minecraft:diamond\"");
        assertThat(body).contains("\"deltaQuantity\":-64");
        assertThat(body).contains("\"isRaidBurst\":true");
    }

    @Test
    @DisplayName("GET /api/v1/incidents with valid Authorization Bearer header returns 200")
    void testGetIncidentsWithBearerAuth() throws Exception {
        incidentBuffer.add(new SecurityIncident(
                1723849000000L,
                102L,
                IncidentClassification.ABSENT_OWNER_THEFT,
                UUID.randomUUID(),
                "Thief",
                UUID.randomUUID(),
                "AwayOwner",
                OwnerPresenceState.onlineAbsent(350.0),
                BlockPosUtil.pack(10, 20, 30),
                "minecraft:overworld",
                "minecraft:elytra",
                -1,
                "Absent owner theft",
                false
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/incidents"))
                .header("Authorization", "Bearer " + config.getSecretToken())
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("ABSENT_OWNER_THEFT");
        assertThat(response.body()).contains("🟡 Absent (~350m away)");
        assertThat(response.body()).contains("minecraft:elytra");
    }

    @Test
    @DisplayName("GET /api/v1/incidents supports limit and classification query filters")
    void testGetIncidentsWithFilters() throws Exception {
        incidentBuffer.add(new SecurityIncident(
                1723849000000L, 1L, IncidentClassification.CRITICAL_RAID,
                UUID.randomUUID(), "Alice", UUID.randomUUID(), "Bob",
                OwnerPresenceState.offline(), BlockPosUtil.pack(1, 2, 3), "minecraft:overworld",
                "minecraft:diamond", -10, "Raid 1", true
        ));
        incidentBuffer.add(new SecurityIncident(
                1723849001000L, 2L, IncidentClassification.OFFLINE_THEFT,
                UUID.randomUUID(), "Charlie", UUID.randomUUID(), "Dave",
                OwnerPresenceState.offline(), BlockPosUtil.pack(4, 5, 6), "minecraft:overworld",
                "minecraft:netherite_ingot", -2, "Offline 1", false
        ));
        incidentBuffer.add(new SecurityIncident(
                1723849002000L, 3L, IncidentClassification.CRITICAL_RAID,
                UUID.randomUUID(), "Eve", UUID.randomUUID(), "Frank",
                OwnerPresenceState.offline(), BlockPosUtil.pack(7, 8, 9), "minecraft:overworld",
                "minecraft:emerald", -50, "Raid 2", true
        ));

        // Filter by classification=OFFLINE_THEFT
        HttpRequest filterReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/incidents?classification=OFFLINE_THEFT"))
                .header("X-ChestLogger-Auth", config.getSecretToken())
                .GET()
                .build();

        HttpResponse<String> filterResp = client.send(filterReq, HttpResponse.BodyHandlers.ofString());
        assertThat(filterResp.statusCode()).isEqualTo(200);
        assertThat(filterResp.body()).contains("OFFLINE_THEFT");
        assertThat(filterResp.body()).doesNotContain("CRITICAL_RAID");

        // Filter by limit=1
        HttpRequest limitReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/incidents?limit=1"))
                .header("X-ChestLogger-Auth", config.getSecretToken())
                .GET()
                .build();

        HttpResponse<String> limitResp = client.send(limitReq, HttpResponse.BodyHandlers.ofString());
        assertThat(limitResp.statusCode()).isEqualTo(200);
        // Newest was Eve (Raid 2)
        assertThat(limitResp.body()).contains("Eve");
        assertThat(limitResp.body()).doesNotContain("Alice");
    }

    @Test
    @DisplayName("IncidentRingBuffer maintains thread-safe bounded capacity of 200 incidents")
    void testIncidentRingBufferCapacityAndEviction() {
        IncidentRingBuffer buffer = new IncidentRingBuffer(200);
        assertThat(buffer.isEmpty()).isTrue();
        assertThat(buffer.capacity()).isEqualTo(200);

        for (int i = 1; i <= 250; i++) {
            buffer.add(new SecurityIncident(
                    1723849000000L + i,
                    (long) i,
                    IncidentClassification.OFFLINE_THEFT,
                    UUID.randomUUID(),
                    "Actor" + i,
                    UUID.randomUUID(),
                    "Owner" + i,
                    OwnerPresenceState.offline(),
                    BlockPosUtil.pack(i, 64, i),
                    "minecraft:overworld",
                    "minecraft:diamond",
                    -1,
                    "Incident " + i,
                    false
            ));
        }

        assertThat(buffer.size()).isEqualTo(200);
        List<SecurityIncident> all = buffer.getAll();
        assertThat(all).hasSize(200);

        // First item in newest-first list should be 250
        assertThat(all.get(0).sequenceId()).isEqualTo(250L);
        // Last item in newest-first list should be 51 (1 to 50 evicted)
        assertThat(all.get(199).sequenceId()).isEqualTo(51L);
    }

    @Test
    @DisplayName("IncidentRingBuffer is thread-safe under high concurrent additions")
    void testIncidentRingBufferConcurrency() throws InterruptedException {
        IncidentRingBuffer buffer = new IncidentRingBuffer(200);
        int threadCount = 8;
        int operationsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        buffer.add(new SecurityIncident(
                                System.currentTimeMillis(),
                                (long) (threadId * 1000 + i),
                                IncidentClassification.CRITICAL_RAID,
                                UUID.randomUUID(),
                                "Actor" + threadId,
                                UUID.randomUUID(),
                                "Owner" + threadId,
                                OwnerPresenceState.offline(),
                                BlockPosUtil.pack(threadId, 64, i),
                                "minecraft:overworld",
                                "minecraft:diamond",
                                -1,
                                "Concurrent " + i,
                                true
                        ));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(buffer.size()).isEqualTo(200);
        assertThat(buffer.getAll()).hasSize(200);
    }
}
