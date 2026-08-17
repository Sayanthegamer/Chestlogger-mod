package com.chestlogger.web;

import com.chestlogger.event.TransactionEventQueue;
import com.chestlogger.index.PersistentIndexManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class StatsHttpHandlerTest {

    private EmbeddedHttpServer server;
    private int testPort = 18090;
    private String token = "secret_stats_token";
    private TransactionEventQueue queue;
    private PersistentIndexManager indexManager;

    @BeforeEach
    void setUp(@TempDir File tempDir) {
        HttpAuthValidator.resetRateLimiter();
        queue = new TransactionEventQueue(1024);
        indexManager = new PersistentIndexManager(tempDir);

        WebConfig config = new WebConfig(true, "127.0.0.1", testPort, token, "*", 5);
        server = new EmbeddedHttpServer(
                config,
                () -> queue,
                () -> indexManager,
                null,
                null
        );
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        HttpAuthValidator.resetRateLimiter();
    }

    @Test
    @DisplayName("Should return 401 Unauthorized when missing secret token")
    void testUnauthorized() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + testPort + "/api/v1/stats").toURL().openConnection();
        conn.setRequestMethod("GET");
        assertThat(conn.getResponseCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("Should return telemetry stats JSON with expected structure")
    void testStatsResponse() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + testPort + "/api/v1/stats").toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("X-ChestLogger-Auth", token);

        assertThat(conn.getResponseCode()).isEqualTo(200);
        assertThat(conn.getContentType()).contains("application/json");

        String body;
        try (InputStream in = conn.getInputStream()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(body).contains("\"queue\":{");
        assertThat(body).contains("\"depth\":0");
        assertThat(body).contains("\"capacity\":1024");
        assertThat(body).contains("\"enqueued\":0");
        assertThat(body).contains("\"dropped\":0");
        assertThat(body).contains("\"drained\":0");
        assertThat(body).contains("\"index\":{\"size\":0}");
        assertThat(body).contains("\"uptimeMs\":");
    }
}
