package com.chestlogger.index;

import com.chestlogger.event.BlockPosUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PersistentIndexTest {

    @Test
    @DisplayName("Should query index by spatial BlockPos")
    void testSpatialIndexQuery(@TempDir Path tempDir) throws IOException {
        PersistentIndexManager indexManager = new PersistentIndexManager(tempDir.toFile());

        long pos1 = BlockPosUtil.pack(100, 64, 200);
        long pos2 = BlockPosUtil.pack(105, 64, 200);
        UUID p1 = UUID.randomUUID();
        long t1 = System.currentTimeMillis();

        IndexPointer ptr1 = new IndexPointer(1L, t1, p1, "minecraft:diamond", "minecraft:overworld", pos1, 0, 32L, 0);
        IndexPointer ptr2 = new IndexPointer(2L, t1 + 10, p1, "minecraft:iron_ingot", "minecraft:overworld", pos1, 0, 32L, 1);
        IndexPointer ptr3 = new IndexPointer(3L, t1 + 20, p1, "minecraft:gold_ingot", "minecraft:overworld", pos2, 0, 128L, 0);

        indexManager.index(ptr1);
        indexManager.index(ptr2);
        indexManager.index(ptr3);

        IndexQueryFilter filterPos1 = IndexQueryFilter.builder()
                .dimension("minecraft:overworld")
                .exactBlockPos(pos1)
                .build();

        List<IndexPointer> results1 = indexManager.query(filterPos1);
        assertThat(results1).hasSize(2);
        assertThat(results1).containsExactly(ptr1, ptr2);

        // Radius query (pos2 is 5 blocks away from pos1)
        IndexQueryFilter filterRadius = IndexQueryFilter.builder()
                .dimension("minecraft:overworld")
                .centerBlockPos(pos1, 6)
                .build();

        List<IndexPointer> resultsRadius = indexManager.query(filterRadius);
        assertThat(resultsRadius).hasSize(3);
    }

    @Test
    @DisplayName("Should query index by Player UUID and Time Range")
    void testPlayerAndTemporalIndexQuery(@TempDir Path tempDir) throws IOException {
        PersistentIndexManager indexManager = new PersistentIndexManager(tempDir.toFile());

        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        long baseTime = 1_000_000L;
        long pos = BlockPosUtil.pack(0, 64, 0);

        IndexPointer ptrA1 = new IndexPointer(1L, baseTime + 100, playerA, "minecraft:diamond", "minecraft:overworld", pos, 0, 32L, 0);
        IndexPointer ptrA2 = new IndexPointer(2L, baseTime + 500, playerA, "minecraft:diamond", "minecraft:overworld", pos, 0, 32L, 1);
        IndexPointer ptrB1 = new IndexPointer(3L, baseTime + 300, playerB, "minecraft:stone", "minecraft:overworld", pos, 0, 32L, 2);

        indexManager.index(ptrA1);
        indexManager.index(ptrA2);
        indexManager.index(ptrB1);

        // Query Player A within [baseTime, baseTime + 200]
        IndexQueryFilter filter = IndexQueryFilter.builder()
                .actorUuid(playerA)
                .timeRange(baseTime, baseTime + 200)
                .build();

        List<IndexPointer> results = indexManager.query(filter);
        assertThat(results).hasSize(1);
        assertThat(results.get(0)).isEqualTo(ptrA1);
    }

    @Test
    @DisplayName("Should checkpoint and persist index to disk atomically")
    void testIndexPersistenceAndReload(@TempDir Path tempDir) throws IOException {
        File dir = tempDir.toFile();
        PersistentIndexManager indexManager = new PersistentIndexManager(dir);

        UUID player = UUID.randomUUID();
        long pos = BlockPosUtil.pack(10, 20, 30);
        IndexPointer ptr = new IndexPointer(100L, 5000L, player, "minecraft:netherite_ingot", "minecraft:overworld", pos, 1, 64L, 0);

        indexManager.index(ptr);
        indexManager.saveCheckpoint();

        // Reload fresh manager from disk
        PersistentIndexManager reloaded = new PersistentIndexManager(dir);
        reloaded.loadCheckpoint();

        List<IndexPointer> results = reloaded.query(IndexQueryFilter.builder().actorUuid(player).build());
        assertThat(results).hasSize(1);
        assertThat(results.get(0)).isEqualTo(ptr);
    }
}
