package com.chestlogger.e2e;

import com.chestlogger.client.gui.ChestLogFilterWidget;
import com.chestlogger.client.gui.ChestLogPaginationWidget;
import com.chestlogger.client.gui.ChestLogScreen;
import com.chestlogger.event.*;
import com.chestlogger.index.IndexPointer;
import com.chestlogger.index.IndexQueryFilter;
import com.chestlogger.index.PersistentIndexManager;
import com.chestlogger.network.ChestLogNetworking;
import com.chestlogger.network.ChestLogPagePayload;
import com.chestlogger.network.ChestLogPageRequestPayload;
import com.chestlogger.network.DisplayRecord;
import com.chestlogger.query.QueryEngine;
import com.chestlogger.query.QuerySessionManager;
import com.chestlogger.storage.LZ4BlockCompressor;
import com.chestlogger.storage.LogSegmentWriter;
import com.chestlogger.storage.StorageProfile;
import com.chestlogger.storage.StringTableDictionary;
import io.netty.buffer.Unpooled;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive client-server integration test suite for ChestLogger GUI.
 *
 * Validates:
 * 1. Query session initialization from raw transaction log records and network payload mapping.
 * 2. Client-server pagination flows (Page 1 -> Page 2 -> Page 3 -> bounded page slicing and clamping).
 * 3. Dynamic search filter evaluation (player name substring search, item identifier matching, combined filters).
 * 4. Session lifecycle management, TTL expiration, and automatic server re-query fallback.
 * 5. Architectural verification of zero AbstractContainerMenu, Slot, or fake inventory abstractions.
 * 6. Full end-to-end binary storage -> QueryEngine -> QuerySessionManager -> Network Payload pipeline.
 */
class GuiClientServerIntegrationTest {

    private QuerySessionManager sessionManager;
    private final String overworld = "minecraft:overworld";
    private final long chestPos = BlockPosUtil.pack(120, 64, -250);

    @BeforeEach
    void setUp() {
        sessionManager = new QuerySessionManager(25);
    }

    // =========================================================================
    // 1. Query Session Initialization from Transaction Records
    // =========================================================================

    @Test
    @DisplayName("1. Should accurately initialize query session from transaction records into display records & network payloads")
    void testQuerySessionInitializationFromTransactionRecords() {
        UUID queryId = UUID.randomUUID();
        UUID playerSteve = UUID.randomUUID();
        UUID playerAlex = UUID.randomUUID();
        long now = 1723850000000L;

        List<TransactionLogEntry> records = new ArrayList<>();

        // Record 1: Single slot insertion by player Steve
        records.add(new TransactionLogEntry(
                1L, now, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                playerSteve, "Steve", overworld, chestPos,
                List.of(new SlotDelta(0, "minecraft:diamond", 64, 0, 64, 0L))
        ));

        // Record 2: Shift-click extraction by player Alex with custom metadata
        records.add(new TransactionLogEntry(
                2L, now + 1000L, UUID.randomUUID(), ActionType.SHIFT_CLICK_EXTRACT, ActorType.PLAYER,
                playerAlex, "Alex", overworld, chestPos,
                List.of(new SlotDelta(1, "minecraft:emerald", -16, 32, 16, 0xABCDEF123456L))
        ));

        // Record 3: Hopper automated extraction (no player UUID)
        records.add(new TransactionLogEntry(
                3L, now + 2000L, UUID.randomUUID(), ActionType.HOPPER_EXTRACT, ActorType.HOPPER_BLOCK,
                null, "hopper", overworld, chestPos,
                List.of(new SlotDelta(2, "minecraft:iron_ingot", -1, 5, 4, 0L))
        ));

        // Record 4: Multi-delta transaction (Hotbar swap touching 2 slots)
        records.add(new TransactionLogEntry(
                4L, now + 3000L, UUID.randomUUID(), ActionType.HOTBAR_SWAP, ActorType.PLAYER,
                playerSteve, "Steve", overworld, chestPos,
                List.of(
                        new SlotDelta(3, "minecraft:gold_ingot", -10, 10, 0, 0L),
                        new SlotDelta(4, "minecraft:netherite_ingot", 5, 0, 5, 0L)
                )
        ));

        // Record 5: Empty deltas event (e.g. metadata-only or container open/close)
        records.add(new TransactionLogEntry(
                5L, now + 4000L, UUID.randomUUID(), ActionType.CONTAINER_OPEN, ActorType.PLAYER,
                playerAlex, "Alex", overworld, chestPos,
                List.of()
        ));

        // Create session on server
        ChestLogPagePayload initialPayload = toPayload(queryId, "Chest", overworld, chestPos, sessionManager.createSession(
                queryId, "Chest", overworld, chestPos, records, 1
        ));

        // Verify session header & metadata
        assertThat(initialPayload).isNotNull();
        assertThat(initialPayload.queryId()).isEqualTo(queryId);
        assertThat(initialPayload.containerType()).isEqualTo("Chest");
        assertThat(initialPayload.dimension()).isEqualTo(overworld);
        assertThat(initialPayload.packedBlockPos()).isEqualTo(chestPos);
        assertThat(initialPayload.pageIndex()).isEqualTo(1);
        assertThat(initialPayload.totalPages()).isEqualTo(1);
        // Total DisplayRecords = 1 (rec1) + 1 (rec2) + 1 (rec3) + 2 (rec4) + 1 (rec5 air placeholder) = 6
        assertThat(initialPayload.totalRecords()).isEqualTo(6);
        assertThat(initialPayload.records()).hasSize(6);

        // Verify individual DisplayRecord mappings
        DisplayRecord dr0 = initialPayload.records().get(0);
        assertThat(dr0.sequenceId()).isEqualTo(1L);
        assertThat(dr0.timestampMs()).isEqualTo(now);
        assertThat(dr0.actorUuid()).isEqualTo(playerSteve);
        assertThat(dr0.actorName()).isEqualTo("Steve");
        assertThat(dr0.actorType()).isEqualTo(ActorType.PLAYER.getWireId());
        assertThat(dr0.actionType()).isEqualTo(ActionType.PLACE.getWireId());
        assertThat(dr0.slotIndex()).isEqualTo(0);
        assertThat(dr0.itemId()).isEqualTo("minecraft:diamond");
        assertThat(dr0.quantityDelta()).isEqualTo(64);
        assertThat(dr0.metadataFingerprint()).isEqualTo(0L);

        DisplayRecord dr1 = initialPayload.records().get(1);
        assertThat(dr1.sequenceId()).isEqualTo(2L);
        assertThat(dr1.actorName()).isEqualTo("Alex");
        assertThat(dr1.itemId()).isEqualTo("minecraft:emerald");
        assertThat(dr1.quantityDelta()).isEqualTo(-16);
        assertThat(dr1.metadataFingerprint()).isEqualTo(0xABCDEF123456L);

        DisplayRecord dr2 = initialPayload.records().get(2);
        assertThat(dr2.sequenceId()).isEqualTo(3L);
        assertThat(dr2.actorType()).isEqualTo(ActorType.HOPPER_BLOCK.getWireId());
        assertThat(dr2.actionType()).isEqualTo(ActionType.HOPPER_EXTRACT.getWireId());
        assertThat(dr2.itemId()).isEqualTo("minecraft:iron_ingot");
        assertThat(dr2.quantityDelta()).isEqualTo(-1);

        // Multi-delta mapping
        DisplayRecord dr3 = initialPayload.records().get(3);
        assertThat(dr3.sequenceId()).isEqualTo(4L);
        assertThat(dr3.slotIndex()).isEqualTo(3);
        assertThat(dr3.itemId()).isEqualTo("minecraft:gold_ingot");
        assertThat(dr3.quantityDelta()).isEqualTo(-10);

        DisplayRecord dr4 = initialPayload.records().get(4);
        assertThat(dr4.sequenceId()).isEqualTo(4L);
        assertThat(dr4.slotIndex()).isEqualTo(4);
        assertThat(dr4.itemId()).isEqualTo("minecraft:netherite_ingot");
        assertThat(dr4.quantityDelta()).isEqualTo(5);

        // Empty delta expanded to neutral air record
        DisplayRecord dr5 = initialPayload.records().get(5);
        assertThat(dr5.sequenceId()).isEqualTo(5L);
        assertThat(dr5.itemId()).isEqualTo("minecraft:air");
        assertThat(dr5.quantityDelta()).isEqualTo(0);

        // Verify wire serialization round-trip across FriendlyByteBuf
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ChestLogPagePayload.STREAM_CODEC.encode(buf, initialPayload);
        ChestLogPagePayload decodedPayload = ChestLogPagePayload.STREAM_CODEC.decode(buf);

        assertThat(decodedPayload.queryId()).isEqualTo(initialPayload.queryId());
        assertThat(decodedPayload.pageIndex()).isEqualTo(initialPayload.pageIndex());
        assertThat(decodedPayload.totalPages()).isEqualTo(initialPayload.totalPages());
        assertThat(decodedPayload.totalRecords()).isEqualTo(initialPayload.totalRecords());
        assertThat(decodedPayload.containerType()).isEqualTo(initialPayload.containerType());
        assertThat(decodedPayload.dimension()).isEqualTo(initialPayload.dimension());
        assertThat(decodedPayload.packedBlockPos()).isEqualTo(initialPayload.packedBlockPos());
        assertThat(decodedPayload.records()).hasSize(initialPayload.records().size());
        for (int i = 0; i < initialPayload.records().size(); i++) {
            DisplayRecord exp = initialPayload.records().get(i);
            DisplayRecord act = decodedPayload.records().get(i);
            assertThat(act.sequenceId()).isEqualTo(exp.sequenceId());
            assertThat(act.itemId()).isEqualTo(exp.itemId());
            assertThat(act.quantityDelta()).isEqualTo(exp.quantityDelta());
            assertThat(act.metadataFingerprint()).isEqualTo(exp.metadataFingerprint());
            assertThat(act.actorName()).isEqualTo(exp.actorName());
        }
    }

    // =========================================================================
    // 2. Client-Server Pagination & Bounded Page Slicing
    // =========================================================================

    @Test
    @DisplayName("2. Should handle client pagination requests (Page 1 -> Page 2 -> Page 3 -> bounded page slicing)")
    void testClientPaginationRequestsAndBoundedSlicing() {
        UUID queryId = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();
        int totalEvents = 70; // 70 events with pageSize 25 -> Page 1 (25), Page 2 (25), Page 3 (20)

        List<TransactionLogEntry> records = new ArrayList<>();
        for (int i = 1; i <= totalEvents; i++) {
            records.add(new TransactionLogEntry(
                    (long) i,
                    1723850000000L + (i * 1000L),
                    UUID.randomUUID(),
                    (i % 2 == 0) ? ActionType.PLACE : ActionType.PICKUP,
                    ActorType.PLAYER,
                    playerUuid,
                    "Butter_offline",
                    overworld,
                    chestPos,
                    List.of(new SlotDelta(i % 27, "minecraft:diamond", (i % 2 == 0) ? 1 : -1, 10, 10 + ((i % 2 == 0) ? 1 : -1), 0L))
            ));
        }

        // Initialize session on server
        ChestLogPagePayload initialPayload = toPayload(queryId, "Double Chest", overworld, chestPos, sessionManager.createSession(
                queryId, "Double Chest", overworld, chestPos, records, 1
        ));

        assertThat(initialPayload.pageIndex()).isEqualTo(1);
        assertThat(initialPayload.totalPages()).isEqualTo(3);
        assertThat(initialPayload.totalRecords()).isEqualTo(70);
        assertThat(initialPayload.records()).hasSize(25);
        assertThat(initialPayload.records().get(0).sequenceId()).isEqualTo(1L);
        assertThat(initialPayload.records().get(24).sequenceId()).isEqualTo(25L);

        // Simulate Client -> Server Network Request for Page 2
        ChestLogPageRequestPayload reqPage2 = new ChestLogPageRequestPayload(
                queryId, 2, chestPos, overworld, null, null
        );
        ChestLogPagePayload resPage2 = simulateClientServerRoundTrip(reqPage2);

        assertThat(resPage2).isNotNull();
        assertThat(resPage2.pageIndex()).isEqualTo(2);
        assertThat(resPage2.totalPages()).isEqualTo(3);
        assertThat(resPage2.totalRecords()).isEqualTo(70);
        assertThat(resPage2.records()).hasSize(25);
        assertThat(resPage2.records().get(0).sequenceId()).isEqualTo(26L);
        assertThat(resPage2.records().get(24).sequenceId()).isEqualTo(50L);

        // Simulate Client -> Server Network Request for Page 3 (Partial Page)
        ChestLogPageRequestPayload reqPage3 = new ChestLogPageRequestPayload(
                queryId, 3, chestPos, overworld, null, null
        );
        ChestLogPagePayload resPage3 = simulateClientServerRoundTrip(reqPage3);

        assertThat(resPage3).isNotNull();
        assertThat(resPage3.pageIndex()).isEqualTo(3);
        assertThat(resPage3.totalPages()).isEqualTo(3);
        assertThat(resPage3.totalRecords()).isEqualTo(70);
        assertThat(resPage3.records()).hasSize(20);
        assertThat(resPage3.records().get(0).sequenceId()).isEqualTo(51L);
        assertThat(resPage3.records().get(19).sequenceId()).isEqualTo(70L);

        // Bounded Page Slicing: Page 0 clamped to Page 1
        ChestLogPageRequestPayload reqPage0 = new ChestLogPageRequestPayload(
                queryId, 0, chestPos, overworld, null, null
        );
        ChestLogPagePayload resPage0 = simulateClientServerRoundTrip(reqPage0);
        assertThat(resPage0.pageIndex()).isEqualTo(1);
        assertThat(resPage0.records()).hasSize(25);
        assertThat(resPage0.records().get(0).sequenceId()).isEqualTo(1L);

        // Bounded Page Slicing: Negative page clamped to Page 1
        ChestLogPageRequestPayload reqNegative = new ChestLogPageRequestPayload(
                queryId, -5, chestPos, overworld, null, null
        );
        ChestLogPagePayload resNegative = simulateClientServerRoundTrip(reqNegative);
        assertThat(resNegative.pageIndex()).isEqualTo(1);
        assertThat(resNegative.records()).hasSize(25);

        // Bounded Page Slicing: Out-of-bounds high page (Page 99) clamped to Page 3
        ChestLogPageRequestPayload reqOverflow = new ChestLogPageRequestPayload(
                queryId, 99, chestPos, overworld, null, null
        );
        ChestLogPagePayload resOverflow = simulateClientServerRoundTrip(reqOverflow);
        assertThat(resOverflow.pageIndex()).isEqualTo(3);
        assertThat(resOverflow.records()).hasSize(20);
        assertThat(resOverflow.records().get(0).sequenceId()).isEqualTo(51L);

        // Empty Session Bounded Slicing
        UUID emptyQueryId = UUID.randomUUID();
        ChestLogPagePayload emptyPayload = toPayload(emptyQueryId, "Chest", overworld, chestPos, sessionManager.createSession(
                emptyQueryId, "Chest", overworld, chestPos, Collections.emptyList(), 1
        ));
        assertThat(emptyPayload.pageIndex()).isEqualTo(1);
        assertThat(emptyPayload.totalPages()).isEqualTo(1);
        assertThat(emptyPayload.totalRecords()).isEqualTo(0);
        assertThat(emptyPayload.records()).isEmpty();

        ChestLogPagePayload emptyClamped = toPayload(emptyQueryId, "Chest", overworld, chestPos, sessionManager.getPage(emptyQueryId, 10));
        assertThat(emptyClamped.pageIndex()).isEqualTo(1);
        assertThat(emptyClamped.records()).isEmpty();
    }

    // =========================================================================
    // 3. Search & Identifier Filter Evaluation
    // =========================================================================

    @Test
    @DisplayName("3. Should evaluate player search filters and item identifier filters correctly")
    void testFilterEvaluationPlayerAndItem() {
        UUID queryId = UUID.randomUUID();
        UUID uuidAlice = UUID.randomUUID();
        UUID uuidBob = UUID.randomUUID();
        UUID uuidCharlie = UUID.randomUUID();
        long now = 1723860000000L;

        List<TransactionLogEntry> fullHistory = new ArrayList<>();
        long seq = 1;

        // 10 records: Alice + diamond
        for (int i = 0; i < 10; i++) {
            fullHistory.add(new TransactionLogEntry(
                    seq++, now + seq * 1000, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                    uuidAlice, "Alice", overworld, chestPos,
                    List.of(new SlotDelta(0, "minecraft:diamond", 1, 0, 1, 0L))
            ));
        }

        // 10 records: Alice + iron_ingot
        for (int i = 0; i < 10; i++) {
            fullHistory.add(new TransactionLogEntry(
                    seq++, now + seq * 1000, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                    uuidAlice, "Alice", overworld, chestPos,
                    List.of(new SlotDelta(1, "minecraft:iron_ingot", 5, 0, 5, 0L))
            ));
        }

        // 15 records: Bob_The_Builder + diamond
        for (int i = 0; i < 15; i++) {
            fullHistory.add(new TransactionLogEntry(
                    seq++, now + seq * 1000, UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER,
                    uuidBob, "Bob_The_Builder", overworld, chestPos,
                    List.of(new SlotDelta(0, "minecraft:diamond", -1, 10, 9, 0L))
            ));
        }

        // 5 records: bobby_tables + gold_ingot
        for (int i = 0; i < 5; i++) {
            fullHistory.add(new TransactionLogEntry(
                    seq++, now + seq * 1000, UUID.randomUUID(), ActionType.SHIFT_CLICK_INSERT, ActorType.PLAYER,
                    uuidBob, "bobby_tables", overworld, chestPos,
                    List.of(new SlotDelta(2, "minecraft:gold_ingot", 8, 0, 8, 0L))
            ));
        }

        // 10 records: Charlie + emerald
        for (int i = 0; i < 10; i++) {
            fullHistory.add(new TransactionLogEntry(
                    seq++, now + seq * 1000, UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                    uuidCharlie, "Charlie", overworld, chestPos,
                    List.of(new SlotDelta(3, "minecraft:emerald", 16, 0, 16, 0L))
            ));
        }

        // 5 records: hopper (automation) + diamond
        for (int i = 0; i < 5; i++) {
            fullHistory.add(new TransactionLogEntry(
                    seq++, now + seq * 1000, UUID.randomUUID(), ActionType.HOPPER_EXTRACT, ActorType.HOPPER_BLOCK,
                    null, "hopper", overworld, chestPos,
                    List.of(new SlotDelta(0, "minecraft:diamond", -1, 9, 8, 0L))
            ));
        }

        // Total events: 10 + 10 + 15 + 5 + 10 + 5 = 55
        assertThat(fullHistory).hasSize(55);

        // --- Test A: Item Identifier Filter ("minecraft:diamond") ---
        // Expected matching: 10 (Alice) + 15 (Bob) + 5 (hopper) = 30 records -> 2 pages (25 + 5)
        ChestLogPageRequestPayload itemReq = new ChestLogPageRequestPayload(
                queryId, 1, chestPos, overworld, null, "minecraft:diamond"
        );
        ChestLogPagePayload itemResPage1 = processServerFilterRequest(itemReq, fullHistory);

        assertThat(itemResPage1.totalRecords()).isEqualTo(30);
        assertThat(itemResPage1.totalPages()).isEqualTo(2);
        assertThat(itemResPage1.pageIndex()).isEqualTo(1);
        assertThat(itemResPage1.records()).hasSize(25);
        assertThat(itemResPage1.records()).allMatch(r -> "minecraft:diamond".equals(r.itemId()));

        // Fetch Page 2 of Item Filter
        ChestLogPageRequestPayload itemReqPage2 = new ChestLogPageRequestPayload(
                queryId, 2, chestPos, overworld, null, "minecraft:diamond"
        );
        ChestLogPagePayload itemResPage2 = processServerFilterRequest(itemReqPage2, fullHistory);
        assertThat(itemResPage2.pageIndex()).isEqualTo(2);
        assertThat(itemResPage2.records()).hasSize(5);
        assertThat(itemResPage2.records()).allMatch(r -> "minecraft:diamond".equals(r.itemId()));

        // --- Test B: Player Search Filter ("bob" - case-insensitive substring match) ---
        // Expected matching: "Bob_The_Builder" (15) + "bobby_tables" (5) = 20 records
        ChestLogPageRequestPayload playerReq = new ChestLogPageRequestPayload(
                queryId, 1, chestPos, overworld, "bob", null
        );
        ChestLogPagePayload playerRes = processServerFilterRequest(playerReq, fullHistory);

        assertThat(playerRes.totalRecords()).isEqualTo(20);
        assertThat(playerRes.totalPages()).isEqualTo(1);
        assertThat(playerRes.records()).hasSize(20);
        assertThat(playerRes.records()).allMatch(r -> r.actorName().toLowerCase().contains("bob"));
        assertThat(playerRes.records()).noneMatch(r -> "Alice".equals(r.actorName()) || "Charlie".equals(r.actorName()));

        // --- Test C: Combined Player & Item Filter (Alice + iron_ingot) ---
        // Expected matching: exactly 10 records
        ChestLogPageRequestPayload combinedReq = new ChestLogPageRequestPayload(
                queryId, 1, chestPos, overworld, "Alice", "minecraft:iron_ingot"
        );
        ChestLogPagePayload combinedRes = processServerFilterRequest(combinedReq, fullHistory);

        assertThat(combinedRes.totalRecords()).isEqualTo(10);
        assertThat(combinedRes.totalPages()).isEqualTo(1);
        assertThat(combinedRes.records()).hasSize(10);
        assertThat(combinedRes.records()).allMatch(r -> "Alice".equals(r.actorName()) && "minecraft:iron_ingot".equals(r.itemId()));

        // --- Test D: Non-Matching Filter (returns empty result payload) ---
        ChestLogPageRequestPayload noMatchReq = new ChestLogPageRequestPayload(
                queryId, 1, chestPos, overworld, "UnknownHacker", "minecraft:beacon"
        );
        ChestLogPagePayload noMatchRes = processServerFilterRequest(noMatchReq, fullHistory);

        assertThat(noMatchRes.totalRecords()).isEqualTo(0);
        assertThat(noMatchRes.totalPages()).isEqualTo(1);
        assertThat(noMatchRes.records()).isEmpty();

        // --- Test E: Blank & Whitespace Filter Strings treated as no filter ---
        ChestLogPageRequestPayload blankReq = new ChestLogPageRequestPayload(
                queryId, 1, chestPos, overworld, "   ", ""
        );
        ChestLogPagePayload blankRes = processServerFilterRequest(blankReq, fullHistory);
        assertThat(blankRes.totalRecords()).isEqualTo(55);
        assertThat(blankRes.totalPages()).isEqualTo(3);
    }

    // =========================================================================
    // 4. Session Expiration & Re-Querying Flow
    // =========================================================================

    @Test
    @DisplayName("4. Should handle session expiration, cache eviction, and automatic server re-querying")
    void testSessionExpirationAndReQuerying() {
        UUID queryId = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();

        List<TransactionLogEntry> history = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            history.add(new TransactionLogEntry(
                    (long) i,
                    System.currentTimeMillis() + i,
                    UUID.randomUUID(),
                    ActionType.PLACE,
                    ActorType.PLAYER,
                    playerUuid,
                    "Builder",
                    overworld,
                    chestPos,
                    List.of(new SlotDelta(i % 27, "minecraft:stone", 1, 0, 1, 0L))
            ));
        }

        // 1. Initialize session
        ChestLogPagePayload payload1 = toPayload(queryId, "Chest", overworld, chestPos, sessionManager.createSession(
                queryId, "Chest", overworld, chestPos, history, 1
        ));
        assertThat(payload1).isNotNull();
        assertThat(sessionManager.hasSession(queryId)).isTrue();
        assertThat(sessionManager.getPage(queryId, 1)).isNotNull();

        // 2. Invalidate / expire session (simulating 5-minute TTL expiration or cache purge)
        sessionManager.invalidate(queryId);
        assertThat(sessionManager.hasSession(queryId)).isFalse();
        assertThat(sessionManager.getPage(queryId, 1)).isNull();

        // 3. Client sends pagination request for expired queryId
        ChestLogPageRequestPayload clientRequest = new ChestLogPageRequestPayload(
                queryId, 2, chestPos, overworld, null, null
        );

        // Server detects !sessionManager.hasSession(queryId) and triggers re-query fallback
        ChestLogPagePayload requeryResponse;
        if (!sessionManager.hasSession(clientRequest.queryId())) {
            // Re-query database / storage pipeline and re-establish session
            requeryResponse = toPayload(clientRequest.queryId(), "Chest", clientRequest.dimension(), clientRequest.packedBlockPos(), sessionManager.createSession(
                    clientRequest.queryId(),
                    "Chest",
                    clientRequest.dimension(),
                    clientRequest.packedBlockPos(),
                    history,
                    clientRequest.requestedPage()
            ));
        } else {
            requeryResponse = toPayload(clientRequest.queryId(), "Chest", clientRequest.dimension(), clientRequest.packedBlockPos(), sessionManager.getPage(clientRequest.queryId(), clientRequest.requestedPage()));
        }

        assertThat(requeryResponse).isNotNull();
        assertThat(requeryResponse.queryId()).isEqualTo(queryId);
        assertThat(requeryResponse.pageIndex()).isEqualTo(2);
        assertThat(requeryResponse.totalPages()).isEqualTo(2); // 40 items with pageSize 25 -> 2 pages (25 + 15)
        assertThat(requeryResponse.totalRecords()).isEqualTo(40);
        assertThat(requeryResponse.records()).hasSize(15);
        assertThat(requeryResponse.records().get(0).sequenceId()).isEqualTo(26L);

        // Subsequent page requests within active TTL now hit session cache directly
        assertThat(sessionManager.hasSession(queryId)).isTrue();
        ChestLogPagePayload cachedPage1 = toPayload(queryId, "Chest", overworld, chestPos, sessionManager.getPage(queryId, 1));
        assertThat(cachedPage1).isNotNull();
        assertThat(cachedPage1.pageIndex()).isEqualTo(1);
        assertThat(cachedPage1.records()).hasSize(25);
    }

    // =========================================================================
    // 5. Zero AbstractContainerMenu, Slot, or Fake Inventory Verification
    // =========================================================================

    @Test
    @DisplayName("5. Should verify zero usage of AbstractContainerMenu, Slot, or fake inventory abstractions throughout entire GUI and networking pipeline")
    void testZeroContainerMenuAndSlotAbstractionsVerification() {
        // A. Verify Screen hierarchy: ChestLogScreen must extend net.minecraft.client.gui.screens.Screen directly
        assertThat(Screen.class.isAssignableFrom(ChestLogScreen.class))
                .as("ChestLogScreen must directly extend Minecraft Screen")
                .isTrue();

        assertThat(AbstractContainerMenu.class.isAssignableFrom(ChestLogScreen.class))
                .as("ChestLogScreen MUST NOT extend or implement AbstractContainerMenu")
                .isFalse();

        assertThat(Container.class.isAssignableFrom(ChestLogScreen.class))
                .as("ChestLogScreen MUST NOT implement Container inventory interface")
                .isFalse();

        // B. Verify Child Widgets hierarchy: ChestLogFilterWidget and ChestLogPaginationWidget extend AbstractWidget
        assertThat(AbstractWidget.class.isAssignableFrom(ChestLogFilterWidget.class))
                .as("ChestLogFilterWidget must extend AbstractWidget")
                .isTrue();

        assertThat(AbstractWidget.class.isAssignableFrom(ChestLogPaginationWidget.class))
                .as("ChestLogPaginationWidget must extend AbstractWidget")
                .isTrue();

        // C. Reflection Inspection: Verify NO fields in GUI or Network classes reference AbstractContainerMenu, Slot, or Container
        List<Class<?>> classesToInspect = List.of(
                ChestLogScreen.class,
                ChestLogFilterWidget.class,
                ChestLogPaginationWidget.class,
                ChestLogNetworking.class,
                QuerySessionManager.class,
                ChestLogPagePayload.class,
                ChestLogPageRequestPayload.class,
                DisplayRecord.class
        );

        List<Class<?>> forbiddenTypes = List.of(
                AbstractContainerMenu.class,
                Slot.class,
                Container.class
        );

        for (Class<?> inspectedClass : classesToInspect) {
            // Check all declared fields
            for (Field field : inspectedClass.getDeclaredFields()) {
                for (Class<?> forbidden : forbiddenTypes) {
                    assertThat(forbidden.isAssignableFrom(field.getType()))
                            .as("Class %s must not contain field '%s' of forbidden type %s",
                                    inspectedClass.getSimpleName(), field.getName(), forbidden.getSimpleName())
                            .isFalse();
                }
            }

            // Check all declared methods
            for (Method method : inspectedClass.getDeclaredMethods()) {
                for (Class<?> forbidden : forbiddenTypes) {
                    assertThat(forbidden.isAssignableFrom(method.getReturnType()))
                            .as("Class %s must not have method '%s' returning forbidden type %s",
                                    inspectedClass.getSimpleName(), method.getName(), forbidden.getSimpleName())
                            .isFalse();

                    for (Class<?> paramType : method.getParameterTypes()) {
                        assertThat(forbidden.isAssignableFrom(paramType))
                                .as("Class %s method '%s' must not accept parameter of forbidden type %s",
                                        inspectedClass.getSimpleName(), method.getName(), forbidden.getSimpleName())
                                .isFalse();
                    }
                }
            }
        }

        // D. Verify custom packet payload implementations
        assertThat(CustomPacketPayload.class.isAssignableFrom(ChestLogPagePayload.class)).isTrue();
        assertThat(CustomPacketPayload.class.isAssignableFrom(ChestLogPageRequestPayload.class)).isTrue();
    }

    // =========================================================================
    // 6. Full Disk -> QueryEngine -> SessionManager -> Network Payload E2E
    // =========================================================================

    @Test
    @DisplayName("6. Full E2E: Disk Segment -> QueryEngine -> QuerySessionManager -> Network ByteCodec round-trip")
    void testEndToEndDiskToSessionPayloadPipeline(@TempDir Path tempDir) throws IOException {
        File dataDir = tempDir.toFile();
        StringTableDictionary dictionary = new StringTableDictionary();
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        StorageProfile profile = StorageProfile.BALANCED;
        PersistentIndexManager indexManager = new PersistentIndexManager(dataDir);

        UUID playerUuid = UUID.randomUUID();
        int eventCount = 35;
        List<TransactionLogEntry> diskEntries = new ArrayList<>();

        for (int i = 1; i <= eventCount; i++) {
            diskEntries.add(new TransactionLogEntry(
                    (long) i,
                    1723870000000L + (i * 500L),
                    UUID.randomUUID(),
                    (i % 2 == 0) ? ActionType.PLACE : ActionType.PICKUP,
                    ActorType.PLAYER,
                    playerUuid,
                    "Miner_49er",
                    overworld,
                    chestPos,
                    List.of(new SlotDelta(i % 27, "minecraft:diamond_block", (i % 2 == 0) ? 2 : -1, 10, 10, 0L))
            ));
        }

        // 1. Write entries to disk segment
        try (LogSegmentWriter writer = new LogSegmentWriter(dataDir, "chestlog", 0, 1L, compressor, profile, dictionary)) {
            writer.writeBatch(diskEntries);
        }

        // 2. Index the written entries
        for (int i = 0; i < diskEntries.size(); i++) {
            TransactionLogEntry e = diskEntries.get(i);
            IndexPointer ptr = new IndexPointer(
                    e.sequenceId(),
                    e.timestampMs(),
                    e.actorUuid(),
                    "minecraft:diamond_block",
                    e.dimension(),
                    e.packedBlockPos(),
                    0,
                    32L,
                    i
            );
            indexManager.index(ptr);
        }

        // 3. Query records via QueryEngine
        QueryEngine queryEngine = new QueryEngine(dataDir, compressor, indexManager, () -> dictionary);
        List<TransactionLogEntry> queried = queryEngine.fetchRecords(
                IndexQueryFilter.builder()
                        .dimension(overworld)
                        .exactBlockPos(chestPos)
                        .limit(100)
                        .build()
        );
        assertThat(queried).hasSize(eventCount);

        // 4. Ingest into QuerySessionManager
        UUID queryId = UUID.randomUUID();
        ChestLogPagePayload page1 = toPayload(queryId, "Chest", overworld, chestPos, sessionManager.createSession(
                queryId, "Chest", overworld, chestPos, queried, 1
        ));

        assertThat(page1.totalPages()).isEqualTo(2); // 35 events / 25 per page -> 2 pages
        assertThat(page1.totalRecords()).isEqualTo(35);
        assertThat(page1.records()).hasSize(25);

        // 5. Network serialization to client
        FriendlyByteBuf clientBuf = new FriendlyByteBuf(Unpooled.buffer());
        ChestLogPagePayload.STREAM_CODEC.encode(clientBuf, page1);
        ChestLogPagePayload clientReceived = ChestLogPagePayload.STREAM_CODEC.decode(clientBuf);

        assertThat(clientReceived.queryId()).isEqualTo(queryId);
        assertThat(clientReceived.records()).hasSize(25);
        assertThat(clientReceived.records().get(0).itemId()).isEqualTo("minecraft:diamond_block");
        assertThat(clientReceived.records().get(0).actorName()).isEqualTo("Miner_49er");
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private ChestLogPagePayload simulateClientServerRoundTrip(ChestLogPageRequestPayload request) {
        // Encode client request
        FriendlyByteBuf reqBuf = new FriendlyByteBuf(Unpooled.buffer());
        ChestLogPageRequestPayload.STREAM_CODEC.encode(reqBuf, request);

        // Server decodes request
        ChestLogPageRequestPayload serverDecodedReq = ChestLogPageRequestPayload.STREAM_CODEC.decode(reqBuf);

        // Server evaluates session page
        ChestLogPagePayload serverResponse = toPayload(
                serverDecodedReq.queryId(),
                "Chest",
                "minecraft:overworld",
                0L,
                sessionManager.getPage(
                        serverDecodedReq.queryId(),
                        serverDecodedReq.requestedPage()
                )
        );

        if (serverResponse == null) {
            return null;
        }

        // Server encodes response payload
        FriendlyByteBuf resBuf = new FriendlyByteBuf(Unpooled.buffer());
        ChestLogPagePayload.STREAM_CODEC.encode(resBuf, serverResponse);

        // Client decodes response payload
        return ChestLogPagePayload.STREAM_CODEC.decode(resBuf);
    }

    private ChestLogPagePayload processServerFilterRequest(
            ChestLogPageRequestPayload request,
            List<TransactionLogEntry> fullHistory
    ) {
        boolean hasPlayerFilter = request.filterPlayer() != null && !request.filterPlayer().isBlank();
        boolean hasItemFilter = request.filterItem() != null && !request.filterItem().isBlank();

        List<TransactionLogEntry> filtered = fullHistory;

        if (hasItemFilter) {
            String itemQuery = request.filterItem().trim();
            filtered = filtered.stream()
                    .filter(entry -> entry.deltas().stream().anyMatch(d -> d.itemId().equals(itemQuery)))
                    .toList();
        }

        if (hasPlayerFilter) {
            String playerQuery = request.filterPlayer().trim().toLowerCase();
            filtered = filtered.stream()
                    .filter(entry -> entry.actorName() != null && entry.actorName().toLowerCase().contains(playerQuery))
                    .toList();
        }

        var sessionPage = sessionManager.createSession(
                request.queryId(),
                "Chest",
                request.dimension(),
                request.packedBlockPos(),
                filtered,
                request.requestedPage()
        );
        return toPayload(request.queryId(), "Chest", request.dimension(), request.packedBlockPos(), sessionPage);
    }

    private static ChestLogPagePayload toPayload(
            UUID queryId,
            String containerType,
            String dimension,
            long packedPos,
            com.chestlogger.query.PagedResult<com.chestlogger.query.DisplayRecord> paged
    ) {
        if (paged == null) return null;
        List<DisplayRecord> records = paged.items().stream()
                .map(r -> new DisplayRecord(
                        r.sequenceId(), r.timestampMs(), r.actorUuid(), r.actorName(),
                        r.actorType(), r.actionType(), r.slotIndex(), r.itemId(),
                        r.quantityDelta(), r.metadataFingerprint(), r.dimension(), r.packedBlockPos()
                )).toList();
        return new ChestLogPagePayload(
                queryId, paged.pageNumber(), paged.totalPages(), paged.totalElements(),
                containerType, dimension, packedPos, records
        );
    }
}
