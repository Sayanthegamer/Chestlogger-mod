package com.chestlogger.web;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.SlotDelta;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueryHttpHandlerTest {

    private EmbeddedHttpServer server;
    private int testPort = 18091;
    private String token = "secret_query_token";
    private QueryEngine queryEngine;
    private QuerySessionManager sessionManager;
    private UUID playerUuid;

    @BeforeEach
    void setUp(@TempDir File tempDir) throws Exception {
        StringTableDictionary dict = new StringTableDictionary();
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        StorageProfile profile = StorageProfile.BALANCED;
        PersistentIndexManager indexManager = new PersistentIndexManager(tempDir);

        playerUuid = UUID.randomUUID();
        long pos = BlockPosUtil.pack(100, 64, -200);
        long now = System.currentTimeMillis();

        TransactionLogEntry e1 = new TransactionLogEntry(
                1L, now, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                playerUuid, "Alex", "minecraft:overworld", pos,
                List.of(new SlotDelta(0, "minecraft:diamond", 64, 0, 64, 0L))
        );
        TransactionLogEntry e2 = new TransactionLogEntry(
                2L, now + 500, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER,
                playerUuid, "Alex", "minecraft:overworld", pos,
                List.of(new SlotDelta(0, "minecraft:diamond", -32, 64, 32, 0L))
        );

        try (LogSegmentWriter writer = new LogSegmentWriter(tempDir, "chestlog", 0, 1L, compressor, profile, dict)) {
            writer.writeBatch(List.of(e1, e2));
        }

        indexManager.index(new IndexPointer(1L, now, playerUuid, "minecraft:diamond", "minecraft:overworld", pos, 0, 32L, 0));
        indexManager.index(new IndexPointer(2L, now + 500, playerUuid, "minecraft:diamond", "minecraft:overworld", pos, 0, 32L, 1));

        queryEngine = new QueryEngine(tempDir, compressor, indexManager, () -> dict);
        sessionManager = new QuerySessionManager(25);

        WebConfig config = new WebConfig(true, "127.0.0.1", testPort, token, "*", 5);
        server = new EmbeddedHttpServer(
                config,
                null,
                null,
                () -> queryEngine,
                () -> sessionManager
        );
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("Should require authentication on /api/v1/query")
    void testAuth() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + testPort + "/api/v1/query").toURL().openConnection();
        conn.setRequestMethod("GET");
        assertThat(conn.getResponseCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("Should query transactions by coordinate and item filters with pagination metadata")
    void testQueryWithFilters() throws Exception {
        String url = "http://127.0.0.1:" + testPort + "/api/v1/query?x=100&y=64&z=-200&dim=minecraft:overworld&item=minecraft:diamond&page=1&limit=25";
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("X-ChestLogger-Auth", token);

        assertThat(conn.getResponseCode()).isEqualTo(200);
        assertThat(conn.getContentType()).contains("application/json");

        String body;
        try (InputStream in = conn.getInputStream()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(body).contains("\"queryId\":");
        assertThat(body).contains("\"page\":1");
        assertThat(body).contains("\"totalPages\":1");
        assertThat(body).contains("\"totalRecords\":2");
        assertThat(body).contains("\"records\":[");
        assertThat(body).contains("\"actorName\":\"Alex\"");
        assertThat(body).contains("\"item\":\"minecraft:diamond\"");
        assertThat(body).contains("\"delta\":64");
        assertThat(body).contains("\"delta\":-32");
    }

    @Test
    @DisplayName("Should support pagination across existing query session via queryId")
    void testQuerySessionPagination() throws Exception {
        // First query
        String url1 = "http://127.0.0.1:" + testPort + "/api/v1/query?x=100&y=64&z=-200";
        HttpURLConnection conn1 = (HttpURLConnection) URI.create(url1).toURL().openConnection();
        conn1.setRequestMethod("GET");
        conn1.setRequestProperty("X-ChestLogger-Auth", token);

        String body1;
        try (InputStream in = conn1.getInputStream()) {
            body1 = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        int qIdStart = body1.indexOf("\"queryId\":\"") + 11;
        int qIdEnd = body1.indexOf("\"", qIdStart);
        String queryId = body1.substring(qIdStart, qIdEnd);

        // Subsequent query on same queryId
        String url2 = "http://127.0.0.1:" + testPort + "/api/v1/query?queryId=" + queryId + "&page=1";
        HttpURLConnection conn2 = (HttpURLConnection) URI.create(url2).toURL().openConnection();
        conn2.setRequestMethod("GET");
        conn2.setRequestProperty("X-ChestLogger-Auth", token);

        assertThat(conn2.getResponseCode()).isEqualTo(200);
        String body2;
        try (InputStream in = conn2.getInputStream()) {
            body2 = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(body2).contains("\"queryId\":\"" + queryId + "\"");
        assertThat(body2).contains("\"totalRecords\":2");
    }
}
