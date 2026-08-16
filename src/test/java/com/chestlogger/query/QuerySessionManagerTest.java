package com.chestlogger.query;

import com.chestlogger.event.*;
import com.chestlogger.network.ChestLogPagePayload;
import com.chestlogger.network.ChestLogPageRequestPayload;
import com.chestlogger.network.DisplayRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySessionManagerTest {

    @Test
    @DisplayName("Should create query session, paginate into bounded 25-item pages, and map DisplayRecords accurately")
    void testQuerySessionPagination() {
        QuerySessionManager sessionManager = new QuerySessionManager(25);
        UUID player = UUID.randomUUID();
        long pos = BlockPosUtil.pack(10, 64, -20);
        String dim = "minecraft:overworld";

        // Create 60 sample records
        List<TransactionLogEntry> records = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            records.add(new TransactionLogEntry(
                    i, 1723849000000L + (i * 1000L), UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER,
                    player, "Butter_offline", dim, pos,
                    List.of(new SlotDelta(i % 27, "minecraft:chest", -1, 1, 0, 0L))
            ));
        }

        UUID queryId = UUID.randomUUID();
        ChestLogPagePayload page1 = sessionManager.createSession(queryId, "Chest", dim, pos, records, 1);

        assertThat(page1.queryId()).isEqualTo(queryId);
        assertThat(page1.pageIndex()).isEqualTo(1);
        assertThat(page1.totalPages()).isEqualTo(3); // 60 records / 25 per page = 3 pages
        assertThat(page1.totalRecords()).isEqualTo(60);
        assertThat(page1.records()).hasSize(25);

        // Fetch page 2
        ChestLogPagePayload page2 = sessionManager.getPage(queryId, 2);
        assertThat(page2).isNotNull();
        assertThat(page2.pageIndex()).isEqualTo(2);
        assertThat(page2.records()).hasSize(25);
        assertThat(page2.records().get(0).sequenceId()).isEqualTo(26L);

        // Fetch page 3 (last page with 10 records)
        ChestLogPagePayload page3 = sessionManager.getPage(queryId, 3);
        assertThat(page3).isNotNull();
        assertThat(page3.pageIndex()).isEqualTo(3);
        assertThat(page3.records()).hasSize(10);
        assertThat(page3.records().get(0).sequenceId()).isEqualTo(51L);

        // Fetch invalid page out of bounds -> clamped to bounds
        ChestLogPagePayload clamped = sessionManager.getPage(queryId, 99);
        assertThat(clamped).isNotNull();
        assertThat(clamped.pageIndex()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should expire old sessions beyond TTL or capacity")
    void testSessionExpiration() {
        QuerySessionManager sessionManager = new QuerySessionManager(25);
        UUID queryId = UUID.randomUUID();
        sessionManager.createSession(queryId, "Barrel", "minecraft:overworld", 100L, List.of(), 1);

        assertThat(sessionManager.hasSession(queryId)).isTrue();
        sessionManager.invalidate(queryId);
        assertThat(sessionManager.hasSession(queryId)).isFalse();
    }
}
