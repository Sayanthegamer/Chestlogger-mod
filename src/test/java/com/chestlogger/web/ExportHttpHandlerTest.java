package com.chestlogger.web;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.index.IndexPointer;
import com.chestlogger.index.PersistentIndexManager;
import com.chestlogger.query.QueryEngine;
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

class ExportHttpHandlerTest {

    private EmbeddedHttpServer server;
    private int testPort = 18092;
    private String token = "secret_export_token";
    private QueryEngine queryEngine;

    @BeforeEach
    void setUp(@TempDir File tempDir) throws Exception {
        StringTableDictionary dict = new StringTableDictionary();
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        StorageProfile profile = StorageProfile.BALANCED;
        PersistentIndexManager indexManager = new PersistentIndexManager(tempDir);

        UUID playerUuid = UUID.randomUUID();
        long pos = BlockPosUtil.pack(50, 70, -150);
        long now = 1723824000000L;

        TransactionLogEntry e1 = new TransactionLogEntry(
                1L, now, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                playerUuid, "Alex", "minecraft:overworld", pos,
                List.of(new SlotDelta(0, "minecraft:diamond", 64, 0, 64, 0L))
        );
        TransactionLogEntry e2 = new TransactionLogEntry(
                2L, now + 1000, UUID.randomUUID(), ActionType.PICKUP, ActorType.HOPPER_BLOCK,
                playerUuid, "Hopper", "minecraft:overworld", pos,
                List.of(new SlotDelta(1, "minecraft:gold_ingot", -10, 10, 0, 0L))
        );

        try (LogSegmentWriter writer = new LogSegmentWriter(tempDir, "chestlog", 0, 1L, compressor, profile, dict)) {
            writer.writeBatch(List.of(e1, e2));
        }

        indexManager.index(new IndexPointer(1L, now, playerUuid, "minecraft:diamond", "minecraft:overworld", pos, 0, 32L, 0));
        indexManager.index(new IndexPointer(2L, now + 1000, playerUuid, "minecraft:gold_ingot", "minecraft:overworld", pos, 0, 32L, 1));

        queryEngine = new QueryEngine(tempDir, compressor, indexManager, () -> dict);

        WebConfig config = new WebConfig(true, "127.0.0.1", testPort, token, "*", 5);
        server = new EmbeddedHttpServer(
                config,
                null,
                null,
                () -> queryEngine,
                null
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
    @DisplayName("Should require authentication on /api/v1/export")
    void testAuth() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + testPort + "/api/v1/export").toURL().openConnection();
        conn.setRequestMethod("GET");
        assertThat(conn.getResponseCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("Should export transaction records as CSV stream with exact header and Content-Disposition")
    void testCsvExport() throws Exception {
        String url = "http://127.0.0.1:" + testPort + "/api/v1/export?format=csv";
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("X-ChestLogger-Auth", token);

        assertThat(conn.getResponseCode()).isEqualTo(200);
        assertThat(conn.getContentType()).contains("text/csv");
        assertThat(conn.getHeaderField("Content-Disposition")).contains("attachment; filename=\"chestlogger_export_");
        assertThat(conn.getHeaderField("Content-Disposition")).endsWith(".csv\"");

        String body;
        try (InputStream in = conn.getInputStream()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(body).startsWith("timestamp,date_time,dimension,x,y,z,actor_name,actor_type,action,slot,item_id,delta,metadata_fingerprint\r\n");
        assertThat(body).contains("minecraft:overworld,50,70,-150,Alex,PLAYER,PLACE,0,minecraft:diamond,64,0");
        assertThat(body).contains("Hopper,HOPPER_BLOCK,PICKUP,1,minecraft:gold_ingot,-10,0");
    }

    @Test
    @DisplayName("Should export transaction records as JSON format")
    void testJsonExport() throws Exception {
        String url = "http://127.0.0.1:" + testPort + "/api/v1/export?format=json";
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("X-ChestLogger-Auth", token);

        assertThat(conn.getResponseCode()).isEqualTo(200);
        assertThat(conn.getContentType()).contains("application/json");
        assertThat(conn.getHeaderField("Content-Disposition")).contains("attachment; filename=\"chestlogger_export_");
        assertThat(conn.getHeaderField("Content-Disposition")).endsWith(".json\"");

        String body;
        try (InputStream in = conn.getInputStream()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(body).contains("\"exportTimestamp\":");
        assertThat(body).contains("\"totalRecords\":2");
        assertThat(body).contains("\"records\":[");
        assertThat(body).contains("\"actorName\":\"Alex\"");
        assertThat(body).contains("\"itemId\":\"minecraft:diamond\"");
        assertThat(body).contains("\"delta\":64");
    }
}
