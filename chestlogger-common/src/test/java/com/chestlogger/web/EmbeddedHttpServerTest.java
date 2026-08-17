package com.chestlogger.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedHttpServerTest {

    @Test
    @DisplayName("Should not start or bind when enabled is false")
    void testDisabledByDefault() {
        WebConfig config = new WebConfig(false, "127.0.0.1", 18080, "secret_123", "*", 5);
        EmbeddedHttpServer server = new EmbeddedHttpServer(config);

        server.start();
        assertThat(server.isRunning()).isFalse();
        server.stop();
    }

    @Test
    @DisplayName("Should start on 127.0.0.1 and enforce token authentication on /api/v1/health")
    void testStartAndAuthentication() throws IOException {
        // Use high ephemeral port for test isolation
        int testPort = 18081;
        String token = "test_secret_token_123456";
        WebConfig config = new WebConfig(true, "127.0.0.1", testPort, token, "*", 5);
        EmbeddedHttpServer server = new EmbeddedHttpServer(config);

        try {
            server.start();
            assertThat(server.isRunning()).isTrue();

            // 1. Request without token -> 401 Unauthorized
            HttpURLConnection conn1 = (HttpURLConnection) URI.create("http://127.0.0.1:" + testPort + "/api/v1/health").toURL().openConnection();
            conn1.setRequestMethod("GET");
            assertThat(conn1.getResponseCode()).isEqualTo(401);

            // 2. Request with wrong token -> 401 Unauthorized
            HttpURLConnection conn2 = (HttpURLConnection) URI.create("http://127.0.0.1:" + testPort + "/api/v1/health").toURL().openConnection();
            conn2.setRequestMethod("GET");
            conn2.setRequestProperty("X-ChestLogger-Auth", "wrong_token");
            assertThat(conn2.getResponseCode()).isEqualTo(401);

            // 3. Request with valid header token -> 200 OK
            HttpURLConnection conn3 = (HttpURLConnection) URI.create("http://127.0.0.1:" + testPort + "/api/v1/health").toURL().openConnection();
            conn3.setRequestMethod("GET");
            conn3.setRequestProperty("X-ChestLogger-Auth", token);
            assertThat(conn3.getResponseCode()).isEqualTo(200);

            try (InputStream in = conn3.getInputStream()) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(body).contains("\"status\":\"UP\"");
            }

            // 4. Request with query param ?token= -> 401 Unauthorized (strictly rejected for URL security)
            HttpURLConnection conn4 = (HttpURLConnection) URI.create("http://127.0.0.1:" + testPort + "/api/v1/health?token=" + token).toURL().openConnection();
            conn4.setRequestMethod("GET");
            assertThat(conn4.getResponseCode()).isEqualTo(401);

        } finally {
            server.stop();
            assertThat(server.isRunning()).isFalse();
        }
    }
}
