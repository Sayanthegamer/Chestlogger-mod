package com.chestlogger.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class StaticAssetHttpHandlerTest {

    private EmbeddedHttpServer server;
    private final int testPort = 18095;
    private final String token = "test_secret_token";

    @BeforeEach
    void setUp() {
        WebConfig config = new WebConfig(true, "127.0.0.1", testPort, token, "*", 5);
        server = new EmbeddedHttpServer(config);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("Should serve / and /index.html with text/html and no-cache header")
    void testServeIndexHtml() throws IOException {
        // 1. Test root /
        HttpURLConnection connRoot = (HttpURLConnection) URI.create("http://127.0.0.1:" + testPort + "/").toURL().openConnection();
        connRoot.setRequestMethod("GET");
        assertThat(connRoot.getResponseCode()).isEqualTo(200);
        assertThat(connRoot.getHeaderField("Content-Type")).contains("text/html");
        assertThat(connRoot.getHeaderField("Cache-Control")).contains("no-cache");

        try (InputStream in = connRoot.getInputStream()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("<!DOCTYPE html>");
            assertThat(body).contains("ChestLogger");
        }

        // 2. Test /index.html
        HttpURLConnection connIndex = (HttpURLConnection) URI.create("http://127.0.0.1:" + testPort + "/index.html").toURL().openConnection();
        connIndex.setRequestMethod("GET");
        assertThat(connIndex.getResponseCode()).isEqualTo(200);
        assertThat(connIndex.getHeaderField("Content-Type")).contains("text/html");
        assertThat(connIndex.getHeaderField("Cache-Control")).contains("no-cache");

        try (InputStream in = connIndex.getInputStream()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("<!DOCTYPE html>");
        }
    }

    @Test
    @DisplayName("Should serve /style.css with text/css and no-cache header")
    void testServeStyleCss() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + testPort + "/style.css").toURL().openConnection();
        conn.setRequestMethod("GET");
        assertThat(conn.getResponseCode()).isEqualTo(200);
        assertThat(conn.getHeaderField("Content-Type")).contains("text/css");
        assertThat(conn.getHeaderField("Cache-Control")).contains("no-cache");

        try (InputStream in = conn.getInputStream()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("ChestLogger");
        }
    }

    @Test
    @DisplayName("Should serve /app.js with application/javascript and no-cache header")
    void testServeAppJs() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + testPort + "/app.js").toURL().openConnection();
        conn.setRequestMethod("GET");
        assertThat(conn.getResponseCode()).isEqualTo(200);
        assertThat(conn.getHeaderField("Content-Type")).contains("application/javascript");
        assertThat(conn.getHeaderField("Cache-Control")).contains("no-cache");

        try (InputStream in = conn.getInputStream()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("ChestLogger");
        }
    }

    @Test
    @DisplayName("Should serve /manifest.json with application/json")
    void testServeManifestJson() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + testPort + "/manifest.json").toURL().openConnection();
        conn.setRequestMethod("GET");
        assertThat(conn.getResponseCode()).isEqualTo(200);
        assertThat(conn.getHeaderField("Content-Type")).contains("application/json");

        try (InputStream in = conn.getInputStream()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("\"ChestLogger\"");
        }
    }

    @Test
    @DisplayName("Should serve /logo.svg with image/svg+xml")
    void testServeLogoSvg() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + testPort + "/logo.svg").toURL().openConnection();
        conn.setRequestMethod("GET");
        assertThat(conn.getResponseCode()).isEqualTo(200);
        assertThat(conn.getHeaderField("Content-Type")).contains("image/svg+xml");

        try (InputStream in = conn.getInputStream()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("<svg");
        }
    }

    @Test
    @DisplayName("Should return 404 for missing static assets")
    void testMissingAsset() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + testPort + "/nonexistent_asset.png").toURL().openConnection();
        conn.setRequestMethod("GET");
        assertThat(conn.getResponseCode()).isEqualTo(404);
    }

    @ParameterizedTest(name = "Path traversal attack test: {0}")
    @ValueSource(strings = {
            "/../WebConfig.java",
            "/..",
            "/%2e%2e/",
            "/%2e%2e/WebConfig.java",
            "/%2E%2E/",
            "/%2e%2e%2fWebConfig.java",
            "/..%2fWebConfig.java",
            "/%252e%252e/",
            "/%252e%252e/WebConfig.java",
            "/assets/chestlogger/web/../../../WebConfig.java",
            "/index.html%00",
            "/style.css%00.html",
            "/%5c%2e%2e%5cWebConfig.java",
            "/subdir/../../secret.txt"
    })
    @DisplayName("Should block path traversal attempts and return 403 or 404")
    void testPathTraversalAttacks(String maliciousPath) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + testPort + maliciousPath).toURL().openConnection();
        conn.setRequestMethod("GET");
        int responseCode = conn.getResponseCode();
        assertThat(responseCode).isIn(403, 404);
    }

    @Test
    @DisplayName("Should handle HEAD requests returning 200 with no body")
    void testHeadRequest() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + testPort + "/index.html").toURL().openConnection();
        conn.setRequestMethod("HEAD");
        assertThat(conn.getResponseCode()).isEqualTo(200);
        assertThat(conn.getHeaderField("Content-Type")).contains("text/html");
        assertThat(conn.getHeaderField("Cache-Control")).contains("no-cache");
    }

    @Test
    @DisplayName("Should return 405 Method Not Allowed for POST/PUT/DELETE requests")
    void testDisallowedMethods() throws IOException {
        HttpURLConnection connPost = (HttpURLConnection) URI.create("http://127.0.0.1:" + testPort + "/index.html").toURL().openConnection();
        connPost.setRequestMethod("POST");
        assertThat(connPost.getResponseCode()).isEqualTo(405);
        assertThat(connPost.getHeaderField("Allow")).contains("GET");

        HttpURLConnection connPut = (HttpURLConnection) URI.create("http://127.0.0.1:" + testPort + "/style.css").toURL().openConnection();
        connPut.setRequestMethod("PUT");
        assertThat(connPut.getResponseCode()).isEqualTo(405);

        HttpURLConnection connDelete = (HttpURLConnection) URI.create("http://127.0.0.1:" + testPort + "/app.js").toURL().openConnection();
        connDelete.setRequestMethod("DELETE");
        assertThat(connDelete.getResponseCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("Should correctly resolve MIME types across supported extensions")
    void testMimeTypeResolution() {
        assertThat(StaticAssetHttpHandler.resolveMimeType("index.html")).isEqualTo("text/html; charset=utf-8");
        assertThat(StaticAssetHttpHandler.resolveMimeType("index.htm")).isEqualTo("text/html; charset=utf-8");
        assertThat(StaticAssetHttpHandler.resolveMimeType("style.css")).isEqualTo("text/css; charset=utf-8");
        assertThat(StaticAssetHttpHandler.resolveMimeType("app.js")).isEqualTo("application/javascript; charset=utf-8");
        assertThat(StaticAssetHttpHandler.resolveMimeType("module.mjs")).isEqualTo("application/javascript; charset=utf-8");
        assertThat(StaticAssetHttpHandler.resolveMimeType("data.json")).isEqualTo("application/json");
        assertThat(StaticAssetHttpHandler.resolveMimeType("image.png")).isEqualTo("image/png");
        assertThat(StaticAssetHttpHandler.resolveMimeType("vector.svg")).isEqualTo("image/svg+xml");
        assertThat(StaticAssetHttpHandler.resolveMimeType("icon.ico")).isEqualTo("image/x-icon");
        assertThat(StaticAssetHttpHandler.resolveMimeType("photo.jpg")).isEqualTo("image/jpeg");
        assertThat(StaticAssetHttpHandler.resolveMimeType("photo.jpeg")).isEqualTo("image/jpeg");
        assertThat(StaticAssetHttpHandler.resolveMimeType("anim.gif")).isEqualTo("image/gif");
        assertThat(StaticAssetHttpHandler.resolveMimeType("font.woff")).isEqualTo("font/woff");
        assertThat(StaticAssetHttpHandler.resolveMimeType("font.woff2")).isEqualTo("font/woff2");
        assertThat(StaticAssetHttpHandler.resolveMimeType("font.ttf")).isEqualTo("font/ttf");
        assertThat(StaticAssetHttpHandler.resolveMimeType("notes.txt")).isEqualTo("text/plain; charset=utf-8");
        assertThat(StaticAssetHttpHandler.resolveMimeType("archive.bin")).isEqualTo("application/octet-stream");
        assertThat(StaticAssetHttpHandler.resolveMimeType(null)).isEqualTo("application/octet-stream");
    }
}
