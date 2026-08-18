package com.chestlogger.web;

import com.chestlogger.security.TrustManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Trust REST API HTTP Endpoint Tests")
class TrustHttpApiTest {

    @TempDir
    Path tempDir;

    private EmbeddedHttpServer server;
    private TrustManager trustManager;
    private WebConfig config;
    private HttpClient client;
    private int port;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        HttpAuthValidator.resetRateLimiter();
        trustManager = new TrustManager(tempDir.resolve("trust_data.json"));

        config = new WebConfig();
        config.setEnabled(true);
        config.setHost("127.0.0.1");
        config.setPort(0);
        config.setSecretToken("test-secret-token-32-chars-long!");

        server = new EmbeddedHttpServer(
                config,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> trustManager,
                () -> null
        );
        server.start();

        port = config.getPort();
        if (port == 0) {
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
    @DisplayName("POST /api/v1/trust without auth token returns 401 Unauthorized")
    void testPostTrustWithoutAuth() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/trust"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"ownerUuid\":\"" + UUID.randomUUID() + "\",\"trustedUuid\":\"" + UUID.randomUUID() + "\"}"))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("POST /api/v1/trust with valid auth grants trust and updates TrustManager")
    void testPostTrustSuccess() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID trusted = UUID.randomUUID();

        String payload = String.format("{\"ownerUuid\":\"%s\",\"trustedUuid\":\"%s\"}", owner, trusted);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/trust"))
                .header("X-ChestLogger-Auth", config.getSecretToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"ok\"");
        assertThat(response.body()).contains(owner.toString());
        assertThat(response.body()).contains(trusted.toString());

        // Verify TrustManager state
        assertThat(trustManager.isTrusted(owner, trusted)).isTrue();
    }

    @Test
    @DisplayName("GET /api/v1/trust returns trust list for an owner")
    void testGetTrustList() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID trusted = UUID.randomUUID();
        trustManager.trust(owner, trusted);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/trust?owner=" + owner))
                .header("X-ChestLogger-Auth", config.getSecretToken())
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(owner.toString());
        assertThat(response.body()).contains(trusted.toString());
    }
}
