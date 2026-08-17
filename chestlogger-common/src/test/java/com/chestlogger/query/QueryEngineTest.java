package com.chestlogger.query;

import com.chestlogger.event.*;
import com.chestlogger.index.IndexPointer;
import com.chestlogger.index.IndexQueryFilter;
import com.chestlogger.index.PersistentIndexManager;
import com.chestlogger.storage.LZ4BlockCompressor;
import com.chestlogger.storage.LogSegmentWriter;
import com.chestlogger.storage.StorageProfile;
import com.chestlogger.storage.StringTableDictionary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueryEngineTest {

    @Test
    @DisplayName("Should paginate query results cleanly")
    void testPagination() {
        List<String> items = List.of("A", "B", "C", "D", "E", "F", "G");
        PagedResult<String> page1 = PagedResult.of(items, 1, 3);

        assertThat(page1.pageNumber()).isEqualTo(1);
        assertThat(page1.pageSize()).isEqualTo(3);
        assertThat(page1.totalPages()).isEqualTo(3);
        assertThat(page1.totalElements()).isEqualTo(7);
        assertThat(page1.items()).containsExactly("A", "B", "C");

        PagedResult<String> page3 = PagedResult.of(items, 3, 3);
        assertThat(page3.items()).containsExactly("G");

        PagedResult<String> pageOut = PagedResult.of(items, 99, 3);
        assertThat(pageOut.items()).isEmpty();
    }

    @Test
    @DisplayName("Should format TransactionLogEntry into clean administrative log text")
    void testTransactionFormatting() {
        UUID playerUuid = UUID.randomUUID();
        long timeMs = 1723824000000L; // Fixed epoch
        SlotDelta delta = new SlotDelta(0, "minecraft:diamond", 64, 0, 64, 0L);

        TransactionLogEntry entry = new TransactionLogEntry(
                1L, timeMs, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                playerUuid, "Alex", "minecraft:overworld", BlockPosUtil.pack(100, 64, -200),
                List.of(delta)
        );

        String line = TransactionFormatter.formatLine(entry);
        assertThat(line).contains("Alex");
        assertThat(line).contains("+64");
        assertThat(line).contains("minecraft:diamond");
        assertThat(line).contains("PLACE");
    }

    @Test
    @DisplayName("Should retrieve exact TransactionLogEntry from disk using IndexPointer")
    void testRecordRetrievalFromDisk(@TempDir Path tempDir) throws IOException {
        File dataDir = tempDir.toFile();
        StringTableDictionary dict = new StringTableDictionary();
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        StorageProfile profile = StorageProfile.BALANCED;
        PersistentIndexManager indexManager = new PersistentIndexManager(dataDir);

        UUID player = UUID.randomUUID();
        long pos = BlockPosUtil.pack(10, 20, 30);
        long now = System.currentTimeMillis();

        TransactionLogEntry e1 = new TransactionLogEntry(1L, now, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER, player, "Steve", "minecraft:overworld", pos, List.of(new SlotDelta(0, "minecraft:emerald", 10, 0, 10, 0L)));
        TransactionLogEntry e2 = new TransactionLogEntry(2L, now + 100, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER, player, "Steve", "minecraft:overworld", pos, List.of(new SlotDelta(0, "minecraft:emerald", -5, 10, 5, 0L)));

        try (LogSegmentWriter writer = new LogSegmentWriter(dataDir, "chestlog", 0, 1L, compressor, profile, dict)) {
            writer.writeBatch(List.of(e1, e2));
        }

        // Manually build pointers or index
        IndexPointer ptr1 = new IndexPointer(1L, now, player, "minecraft:emerald", "minecraft:overworld", pos, 0, 32L, 0);
        IndexPointer ptr2 = new IndexPointer(2L, now + 100, player, "minecraft:emerald", "minecraft:overworld", pos, 0, 32L, 1);
        indexManager.index(ptr1);
        indexManager.index(ptr2);

        QueryEngine queryEngine = new QueryEngine(dataDir, compressor, indexManager);

        List<TransactionLogEntry> records = queryEngine.fetchRecords(IndexQueryFilter.builder().exactBlockPos(pos).build());
        assertThat(records).hasSize(2);
        assertThat(records.get(0).sequenceId()).isEqualTo(1L);
        assertThat(records.get(0).deltas().get(0).deltaQuantity()).isEqualTo(10);
        assertThat(records.get(1).sequenceId()).isEqualTo(2L);
        assertThat(records.get(1).deltas().get(0).deltaQuantity()).isEqualTo(-5);
    }
}
