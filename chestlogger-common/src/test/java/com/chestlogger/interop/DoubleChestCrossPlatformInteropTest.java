package com.chestlogger.interop;

import com.chestlogger.container.ContainerSnapshot;
import com.chestlogger.event.*;
import com.chestlogger.index.IndexPointer;
import com.chestlogger.index.IndexQueryFilter;
import com.chestlogger.index.PersistentIndexManager;
import com.chestlogger.query.QueryEngine;
import com.chestlogger.rollback.RollbackEngine;
import com.chestlogger.rollback.RollbackPlan;
import com.chestlogger.rollback.RollbackResult;
import com.chestlogger.storage.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Double Chest Cross-Platform Interoperability & Adaptive Rollback Tests")
class DoubleChestCrossPlatformInteropTest {

    @Test
    @DisplayName("Paper double chest logs are parsed, queried, and adaptively rolled back by Fabric engine")
    void testPaperDoubleChestToFabricAdaptiveRollback(@TempDir File sharedLogDir) throws IOException {
        BlockCompressor compressor = new LZ4BlockCompressor();
        StringTableDictionary stringDictionary = new StringTableDictionary();
        StorageProfile profile = StorageProfile.BALANCED;

        long leftPos = BlockPosUtil.pack(200, 64, -500);
        long rightPos = BlockPosUtil.pack(201, 64, -500);
        String dimension = "minecraft:overworld";
        UUID thiefUuid = UUID.randomUUID();

        // 1. Paper generates a 54-slot theft transaction
        TransactionLogEntry paperTheft = new TransactionLogEntry(
                1L,
                System.currentTimeMillis(),
                UUID.randomUUID(),
                ActionType.SHIFT_CLICK_EXTRACT,
                ActorType.PLAYER,
                thiefUuid,
                "PaperGriefer",
                dimension,
                leftPos,
                List.of(
                        new SlotDelta(0, "minecraft:diamond_block", -10, 10, 0, 0L),
                        new SlotDelta(45, "minecraft:netherite_block", -5, 5, 0, 0L)
                )
        );

        // Write log from Paper
        try (LogSegmentWriter paperWriter = new LogSegmentWriter(sharedLogDir, "chestlog", 0, 1L, compressor, profile, stringDictionary)) {
            paperWriter.writeBatch(List.of(paperTheft));
        }

        // 2. Fabric indexes both coordinates
        PersistentIndexManager fabricIndex = new PersistentIndexManager(sharedLogDir);
        fabricIndex.index(new IndexPointer(1L, paperTheft.timestampMs(), thiefUuid, "minecraft:diamond_block", dimension, leftPos, 0, 32L, 0));
        fabricIndex.index(new IndexPointer(1L, paperTheft.timestampMs(), thiefUuid, "minecraft:netherite_block", dimension, rightPos, 0, 32L, 0));

        // Query from right position (54-slot transaction found!)
        QueryEngine fabricQuery = new QueryEngine(sharedLogDir, compressor, fabricIndex, () -> stringDictionary);
        List<TransactionLogEntry> matches = fabricQuery.fetchRecords(
                IndexQueryFilter.builder().dimension(dimension).exactBlockPos(rightPos).build()
        );
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).actorName()).isEqualTo("PaperGriefer");

        // 3. Scenario: Right half was broken, so container only has 27 slots remaining (slots 0-26)
        ContainerSnapshot singleHalfContainer = new ContainerSnapshot(27);
        RollbackEngine rollbackEngine = new RollbackEngine();
        RollbackPlan plan = rollbackEngine.createPlan(matches, singleHalfContainer);

        TransactionEventQueue auditQueue = new TransactionEventQueue(100);
        RollbackResult result = rollbackEngine.applyRollback(
                plan, singleHalfContainer, auditQueue, UUID.randomUUID(), "Admin", dimension, leftPos
        );

        assertThat(result.success()).isTrue();
        assertThat(result.appliedSteps()).isEqualTo(2);

        // Slot 0 restored
        assertThat(singleHalfContainer.getSlot(0).itemId()).isEqualTo("minecraft:diamond_block");
        assertThat(singleHalfContainer.getSlot(0).count()).isEqualTo(10);

        // Slot 45 adaptively relocated to first available empty slot (slot 1)
        assertThat(singleHalfContainer.getSlot(1).itemId()).isEqualTo("minecraft:netherite_block");
        assertThat(singleHalfContainer.getSlot(1).count()).isEqualTo(5);
    }
}
