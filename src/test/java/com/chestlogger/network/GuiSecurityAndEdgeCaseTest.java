package com.chestlogger.network;

import com.chestlogger.event.*;
import com.chestlogger.index.IndexPointer;
import com.chestlogger.index.IndexQueryFilter;
import com.chestlogger.query.PagedResult;
import com.chestlogger.query.QuerySessionManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@DisplayName("GUI Networking, Security & Edge Case Tests")
class GuiSecurityAndEdgeCaseTest {

    // =========================================================================
    // 1. BOUNDARY CLAMPING
    // =========================================================================
    @Nested
    @DisplayName("1. Boundary Clamping Tests")
    class BoundaryClampingTests {

        @ParameterizedTest(name = "Requested page {0} should clamp to page 1")
        @ValueSource(ints = {0, -1, -50, -9999, Integer.MIN_VALUE})
        @DisplayName("Should clamp requested page <= 0 to page 1 during session creation and retrieval")
        void testRequestedPageZeroOrNegativeClampedToPageOne(int invalidPage) {
            QuerySessionManager sessionManager = new QuerySessionManager(25);
            UUID queryId = UUID.randomUUID();
            UUID actorUuid = UUID.randomUUID();
            long pos = BlockPosUtil.pack(10, 64, 10);
            String dim = "minecraft:overworld";

            // 60 records -> 3 pages (25, 25, 10)
            List<TransactionLogEntry> records = createSampleRecords(60, actorUuid, dim, pos);

            // Test on initial session creation
            ChestLogPagePayload initialPayload = sessionManager.createSession(queryId, "Chest", dim, pos, records, invalidPage);
            assertThat(initialPayload.pageIndex()).isEqualTo(1);
            assertThat(initialPayload.totalPages()).isEqualTo(3);
            assertThat(initialPayload.totalRecords()).isEqualTo(60);
            assertThat(initialPayload.records()).hasSize(25);
            assertThat(initialPayload.records().get(0).sequenceId()).isEqualTo(1L);

            // Test on subsequent getPage call
            ChestLogPagePayload getPayload = sessionManager.getPage(queryId, invalidPage);
            assertThat(getPayload).isNotNull();
            assertThat(getPayload.pageIndex()).isEqualTo(1);
            assertThat(getPayload.records()).hasSize(25);
            assertThat(getPayload.records().get(0).sequenceId()).isEqualTo(1L);
        }

        @ParameterizedTest(name = "Requested page {0} should clamp to last page (page 3)")
        @ValueSource(ints = {4, 5, 100, 9999, Integer.MAX_VALUE})
        @DisplayName("Should clamp requested page > totalPages to the last valid page")
        void testRequestedPageGreaterThanTotalPagesClampedToLastPage(int outOfBoundsPage) {
            QuerySessionManager sessionManager = new QuerySessionManager(25);
            UUID queryId = UUID.randomUUID();
            UUID actorUuid = UUID.randomUUID();
            long pos = BlockPosUtil.pack(10, 64, 10);
            String dim = "minecraft:overworld";

            // 60 records -> 3 pages of 25 (page 1: 25, page 2: 25, page 3: 10)
            List<TransactionLogEntry> records = createSampleRecords(60, actorUuid, dim, pos);

            // Test on session creation
            ChestLogPagePayload initialPayload = sessionManager.createSession(queryId, "Barrel", dim, pos, records, outOfBoundsPage);
            assertThat(initialPayload.pageIndex()).isEqualTo(3);
            assertThat(initialPayload.totalPages()).isEqualTo(3);
            assertThat(initialPayload.totalRecords()).isEqualTo(60);
            assertThat(initialPayload.records()).hasSize(10);
            assertThat(initialPayload.records().get(0).sequenceId()).isEqualTo(51L);
            assertThat(initialPayload.records().get(9).sequenceId()).isEqualTo(60L);

            // Test on getPage call
            ChestLogPagePayload getPayload = sessionManager.getPage(queryId, outOfBoundsPage);
            assertThat(getPayload).isNotNull();
            assertThat(getPayload.pageIndex()).isEqualTo(3);
            assertThat(getPayload.records()).hasSize(10);
            assertThat(getPayload.records().get(0).sequenceId()).isEqualTo(51L);
        }

        @Test
        @DisplayName("Should handle boundary clamping for single-page datasets (totalPages = 1)")
        void testSinglePageDatasetBoundaryClamping() {
            QuerySessionManager sessionManager = new QuerySessionManager(25);
            UUID queryId = UUID.randomUUID();
            UUID actorUuid = UUID.randomUUID();
            long pos = BlockPosUtil.pack(0, 0, 0);
            String dim = "minecraft:the_nether";

            List<TransactionLogEntry> records = createSampleRecords(10, actorUuid, dim, pos);

            // Request page 0 -> clamped to 1
            ChestLogPagePayload page0 = sessionManager.createSession(queryId, "Dispenser", dim, pos, records, 0);
            assertThat(page0.pageIndex()).isEqualTo(1);
            assertThat(page0.totalPages()).isEqualTo(1);
            assertThat(page0.records()).hasSize(10);

            // Request page 2 -> clamped to 1
            ChestLogPagePayload page2 = sessionManager.getPage(queryId, 2);
            assertThat(page2).isNotNull();
            assertThat(page2.pageIndex()).isEqualTo(1);
            assertThat(page2.records()).hasSize(10);

            // Request page -999 -> clamped to 1
            ChestLogPagePayload pageNeg = sessionManager.getPage(queryId, -999);
            assertThat(pageNeg).isNotNull();
            assertThat(pageNeg.pageIndex()).isEqualTo(1);
            assertThat(pageNeg.records()).hasSize(10);
        }

        @Test
        @DisplayName("PagedResult.of should return empty list when requested page is out of bounds")
        void testPagedResultBoundaryClamping() {
            List<String> dataset = List.of("Entry1", "Entry2", "Entry3", "Entry4", "Entry5");

            // Page 0 (out of bounds)
            PagedResult<String> page0 = PagedResult.of(dataset, 0, 2);
            assertThat(page0.items()).isEmpty();
            assertThat(page0.pageNumber()).isEqualTo(0);
            assertThat(page0.totalPages()).isEqualTo(3);
            assertThat(page0.totalElements()).isEqualTo(5);

            // Negative page
            PagedResult<String> pageNeg = PagedResult.of(dataset, -5, 2);
            assertThat(pageNeg.items()).isEmpty();
            assertThat(pageNeg.pageNumber()).isEqualTo(-5);

            // Page beyond totalPages
            PagedResult<String> page99 = PagedResult.of(dataset, 99, 2);
            assertThat(page99.items()).isEmpty();
            assertThat(page99.pageNumber()).isEqualTo(99);
            assertThat(page99.totalPages()).isEqualTo(3);

            // Valid page 1
            PagedResult<String> page1 = PagedResult.of(dataset, 1, 2);
            assertThat(page1.items()).containsExactly("Entry1", "Entry2");
            assertThat(page1.pageNumber()).isEqualTo(1);
        }
    }

    // =========================================================================
    // 2. EMPTY TRANSACTION LOG HISTORY
    // =========================================================================
    @Nested
    @DisplayName("2. Empty Transaction Log History Tests")
    class EmptyHistoryEdgeCaseTests {

        @Test
        @DisplayName("Empty record list should yield Page 1/1 with 0 items without throwing exceptions")
        void testEmptyRecordListYieldsPageOneOfOne() {
            QuerySessionManager sessionManager = new QuerySessionManager(25);
            UUID queryId = UUID.randomUUID();
            long pos = BlockPosUtil.pack(100, 64, -200);
            String dim = "minecraft:overworld";

            ChestLogPagePayload payload = sessionManager.createSession(
                    queryId, "Chest", dim, pos, Collections.emptyList(), 1
            );

            assertThat(payload).isNotNull();
            assertThat(payload.queryId()).isEqualTo(queryId);
            assertThat(payload.pageIndex()).isEqualTo(1);
            assertThat(payload.totalPages()).isEqualTo(1);
            assertThat(payload.totalRecords()).isEqualTo(0);
            assertThat(payload.containerType()).isEqualTo("Chest");
            assertThat(payload.dimension()).isEqualTo(dim);
            assertThat(payload.packedBlockPos()).isEqualTo(pos);
            assertThat(payload.records()).isNotNull().isEmpty();

            // Slicing out of bounds on empty session remains safe
            ChestLogPagePayload page0 = sessionManager.getPage(queryId, 0);
            assertThat(page0).isNotNull();
            assertThat(page0.pageIndex()).isEqualTo(1);
            assertThat(page0.totalPages()).isEqualTo(1);
            assertThat(page0.totalRecords()).isEqualTo(0);
            assertThat(page0.records()).isEmpty();

            ChestLogPagePayload page99 = sessionManager.getPage(queryId, 99);
            assertThat(page99).isNotNull();
            assertThat(page99.pageIndex()).isEqualTo(1);
            assertThat(page99.totalPages()).isEqualTo(1);
            assertThat(page99.totalRecords()).isEqualTo(0);
            assertThat(page99.records()).isEmpty();
        }

        @Test
        @DisplayName("Null record list in createSession should be handled gracefully as empty")
        void testNullRecordListHandledSafely() {
            QuerySessionManager sessionManager = new QuerySessionManager(25);
            UUID queryId = UUID.randomUUID();

            ChestLogPagePayload payload = sessionManager.createSession(
                    queryId, "Shulker_Box", "minecraft:the_end", 555L, null, 1
            );

            assertThat(payload).isNotNull();
            assertThat(payload.pageIndex()).isEqualTo(1);
            assertThat(payload.totalPages()).isEqualTo(1);
            assertThat(payload.totalRecords()).isEqualTo(0);
            assertThat(payload.records()).isEmpty();
        }

        @Test
        @DisplayName("TransactionLogEntry with empty SlotDeltas should generate safe air placeholder DisplayRecord")
        void testEmptySlotDeltasInLogEntryHandledSafely() {
            QuerySessionManager sessionManager = new QuerySessionManager(25);
            UUID queryId = UUID.randomUUID();
            UUID actorUuid = UUID.randomUUID();
            long pos = BlockPosUtil.pack(5, 70, 5);

            TransactionLogEntry entryWithNoDeltas = new TransactionLogEntry(
                    1L,
                    System.currentTimeMillis(),
                    UUID.randomUUID(),
                    ActionType.CONTAINER_OPEN,
                    ActorType.PLAYER,
                    actorUuid,
                    "Steve",
                    "minecraft:overworld",
                    pos,
                    Collections.emptyList() // No deltas
            );

            ChestLogPagePayload payload = sessionManager.createSession(
                    queryId, "Chest", "minecraft:overworld", pos, List.of(entryWithNoDeltas), 1
            );

            assertThat(payload.totalRecords()).isEqualTo(1);
            assertThat(payload.records()).hasSize(1);

            DisplayRecord displayRecord = payload.records().get(0);
            assertThat(displayRecord.sequenceId()).isEqualTo(1L);
            assertThat(displayRecord.itemId()).isEqualTo("minecraft:air");
            assertThat(displayRecord.quantityDelta()).isEqualTo(0);
            assertThat(displayRecord.slotIndex()).isEqualTo(0);
            assertThat(displayRecord.actorName()).isEqualTo("Steve");
        }

        @Test
        @DisplayName("PagedResult.of should safely return 1 page with 0 elements for empty or null collections")
        void testPagedResultEmptyAndNullHandling() {
            PagedResult<String> emptyResult = PagedResult.of(Collections.emptyList(), 1, 25);
            assertThat(emptyResult.items()).isEmpty();
            assertThat(emptyResult.pageNumber()).isEqualTo(1);
            assertThat(emptyResult.totalPages()).isEqualTo(1);
            assertThat(emptyResult.totalElements()).isEqualTo(0);

            PagedResult<String> nullResult = PagedResult.of(null, 1, 25);
            assertThat(nullResult.items()).isEmpty();
            assertThat(nullResult.pageNumber()).isEqualTo(1);
            assertThat(nullResult.totalPages()).isEqualTo(1);
            assertThat(nullResult.totalElements()).isEqualTo(0);
        }

        @Test
        @DisplayName("Codec should serialize and deserialize empty ChestLogPagePayload round-trip without corruption")
        void testEmptyPagePayloadCodecRoundTrip() {
            UUID queryId = UUID.randomUUID();
            ChestLogPagePayload emptyPayload = new ChestLogPagePayload(
                    queryId,
                    1,
                    1,
                    0,
                    "EmptyChest",
                    "minecraft:overworld",
                    12345L,
                    Collections.emptyList()
            );

            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            ChestLogPagePayload.STREAM_CODEC.encode(buf, emptyPayload);

            ChestLogPagePayload decoded = ChestLogPagePayload.STREAM_CODEC.decode(buf);

            assertThat(decoded.queryId()).isEqualTo(queryId);
            assertThat(decoded.pageIndex()).isEqualTo(1);
            assertThat(decoded.totalPages()).isEqualTo(1);
            assertThat(decoded.totalRecords()).isEqualTo(0);
            assertThat(decoded.containerType()).isEqualTo("EmptyChest");
            assertThat(decoded.dimension()).isEqualTo("minecraft:overworld");
            assertThat(decoded.packedBlockPos()).isEqualTo(12345L);
            assertThat(decoded.records()).isEmpty();
        }
    }

    // =========================================================================
    // 3. MASSIVE RECORD LOAD (10,000+ RECORDS)
    // =========================================================================
    @Nested
    @DisplayName("3. Massive Record Load (10,000+ Records)")
    class MassiveRecordLoadTests {

        @Test
        @DisplayName("Should paginate 10,000 records into exactly 400 pages with fast O(1) random-access slicing")
        void testTenThousandRecordsPaginatedInto400Pages() {
            QuerySessionManager sessionManager = new QuerySessionManager(25);
            UUID queryId = UUID.randomUUID();
            UUID actorUuid = UUID.randomUUID();
            long pos = BlockPosUtil.pack(50, 100, -50);
            String dim = "minecraft:overworld";

            int totalCount = 10_000;
            List<TransactionLogEntry> massiveRecords = createSampleRecords(totalCount, actorUuid, dim, pos);

            // Session creation on page 1
            long startCreate = System.nanoTime();
            ChestLogPagePayload page1 = sessionManager.createSession(queryId, "Vault", dim, pos, massiveRecords, 1);
            long createDurationMs = (System.nanoTime() - startCreate) / 1_000_000;

            assertThat(createDurationMs).isLessThan(500L); // Fast session generation
            assertThat(page1.queryId()).isEqualTo(queryId);
            assertThat(page1.pageIndex()).isEqualTo(1);
            assertThat(page1.totalPages()).isEqualTo(400); // 10,000 / 25 = 400 pages
            assertThat(page1.totalRecords()).isEqualTo(10_000);
            assertThat(page1.records()).hasSize(25);
            assertThat(page1.records().get(0).sequenceId()).isEqualTo(1L);
            assertThat(page1.records().get(24).sequenceId()).isEqualTo(25L);

            // Slicing page 200 (middle page)
            ChestLogPagePayload page200 = sessionManager.getPage(queryId, 200);
            assertThat(page200).isNotNull();
            assertThat(page200.pageIndex()).isEqualTo(200);
            assertThat(page200.records()).hasSize(25);
            // Page 200 fromIndex = 199 * 25 = 4975 -> sequenceId 4976 to 5000
            assertThat(page200.records().get(0).sequenceId()).isEqualTo(4976L);
            assertThat(page200.records().get(24).sequenceId()).isEqualTo(5000L);

            // Slicing page 400 (last page)
            ChestLogPagePayload page400 = sessionManager.getPage(queryId, 400);
            assertThat(page400).isNotNull();
            assertThat(page400.pageIndex()).isEqualTo(400);
            assertThat(page400.records()).hasSize(25);
            // Page 400 fromIndex = 399 * 25 = 9975 -> sequenceId 9976 to 10000
            assertThat(page400.records().get(0).sequenceId()).isEqualTo(9976L);
            assertThat(page400.records().get(24).sequenceId()).isEqualTo(10_000L);
        }

        @Test
        @DisplayName("Should paginate 10,005 records into 401 pages with partial last page")
        void testTenThousandPlusRecordsWithPartialLastPage() {
            QuerySessionManager sessionManager = new QuerySessionManager(25);
            UUID queryId = UUID.randomUUID();
            UUID actorUuid = UUID.randomUUID();
            long pos = BlockPosUtil.pack(0, 64, 0);

            List<TransactionLogEntry> records = createSampleRecords(10_005, actorUuid, "minecraft:overworld", pos);
            ChestLogPagePayload payload = sessionManager.createSession(queryId, "Chest", "minecraft:overworld", pos, records, 401);

            assertThat(payload.totalPages()).isEqualTo(401);
            assertThat(payload.totalRecords()).isEqualTo(10_005);
            assertThat(payload.pageIndex()).isEqualTo(401);
            assertThat(payload.records()).hasSize(5);
            assertThat(payload.records().get(0).sequenceId()).isEqualTo(10_001L);
            assertThat(payload.records().get(4).sequenceId()).isEqualTo(10_005L);
        }

        @Test
        @DisplayName("Concurrent page requests on massive dataset should execute without thread leaks or race conditions")
        void testConcurrentMassivePageRequestsNoThreadLeaks() throws InterruptedException {
            QuerySessionManager sessionManager = new QuerySessionManager(25);
            UUID queryId = UUID.randomUUID();
            UUID actorUuid = UUID.randomUUID();
            long pos = BlockPosUtil.pack(12, 34, 56);

            List<TransactionLogEntry> records = createSampleRecords(10_000, actorUuid, "minecraft:overworld", pos);
            sessionManager.createSession(queryId, "Chest", "minecraft:overworld", pos, records, 1);

            int threadCount = 10;
            int queriesPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch finishLatch = new CountDownLatch(threadCount);
            AtomicInteger successCounter = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final int threadNum = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int q = 0; q < queriesPerThread; q++) {
                            // Request varied pages: 1, middle, last, out-of-bounds
                            int targetPage = ((threadNum * queriesPerThread + q) % 410) - 5;
                            ChestLogPagePayload page = sessionManager.getPage(queryId, targetPage);
                            if (page != null && page.records() != null && !page.records().isEmpty()) {
                                successCounter.incrementAndGet();
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        finishLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = finishLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
            assertThat(successCounter.get()).isEqualTo(threadCount * queriesPerThread);
            assertThat(sessionManager.hasSession(queryId)).isTrue();
        }

        @Test
        @DisplayName("PagedResult.of should support massive lists (15,000 items) with instant sublist slicing")
        void testPagedResultWithMassiveCollection() {
            List<Integer> largeList = new ArrayList<>(15_000);
            for (int i = 0; i < 15_000; i++) {
                largeList.add(i);
            }

            PagedResult<Integer> paged = PagedResult.of(largeList, 300, 25);
            assertThat(paged.pageNumber()).isEqualTo(300);
            assertThat(paged.totalPages()).isEqualTo(600);
            assertThat(paged.totalElements()).isEqualTo(15_000);
            assertThat(paged.items()).hasSize(25);
            assertThat(paged.items().get(0)).isEqualTo(299 * 25); // 7475
        }
    }

    // =========================================================================
    // 4. SPECIAL CHARACTERS & WHITESPACE IN SEARCH FILTERS
    // =========================================================================
    @Nested
    @DisplayName("4. Special Characters & Whitespace Handling")
    class SearchFilterSpecialCharactersAndSecurityTests {

        @Test
        @DisplayName("Should encode and decode filter strings with leading/trailing whitespace")
        void testWhitespaceInSearchFiltersRoundTrip() {
            UUID queryId = UUID.randomUUID();
            ChestLogPageRequestPayload request = new ChestLogPageRequestPayload(
                    queryId,
                    1,
                    100L,
                    "minecraft:overworld",
                    "   Alex_The_Builder   ",
                    "   minecraft:diamond_sword   "
            );

            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            ChestLogPageRequestPayload.STREAM_CODEC.encode(buf, request);

            ChestLogPageRequestPayload decoded = ChestLogPageRequestPayload.STREAM_CODEC.decode(buf);

            assertThat(decoded.filterPlayer()).isEqualTo("   Alex_The_Builder   ");
            assertThat(decoded.filterItem()).isEqualTo("   minecraft:diamond_sword   ");
        }

        @ParameterizedTest(name = "Blank filter {0} should be normalized to null in codec")
        @ValueSource(strings = {"", "   ", "\t", "\n", "\r\n", "     \t \n   "})
        @DisplayName("Blank or whitespace-only filter strings should encode as absent (decoded as null)")
        void testBlankFiltersNormalizedToNullInCodec(String blankFilter) {
            UUID queryId = UUID.randomUUID();
            ChestLogPageRequestPayload request = new ChestLogPageRequestPayload(
                    queryId,
                    2,
                    200L,
                    "minecraft:overworld",
                    blankFilter,
                    blankFilter
            );

            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            ChestLogPageRequestPayload.STREAM_CODEC.encode(buf, request);

            ChestLogPageRequestPayload decoded = ChestLogPageRequestPayload.STREAM_CODEC.decode(buf);

            // Because ChestLogPageRequestPayload uses `!filter.isBlank()`, blank strings encode as false and decode as null
            assertThat(decoded.filterPlayer()).isNull();
            assertThat(decoded.filterItem()).isNull();
        }

        @Test
        @DisplayName("Should safely encode and decode Unicode, emojis, and foreign characters in filter payloads")
        void testUnicodeAndMultilingualFilterPayloadRoundTrip() {
            UUID queryId = UUID.randomUUID();
            String unicodePlayer = "Ñöçtürñe_99_🔥_スティーブ";
            String unicodeItem = "mod:legendary_item_§c§lFire_剣";

            ChestLogPageRequestPayload request = new ChestLogPageRequestPayload(
                    queryId,
                    1,
                    300L,
                    "minecraft:overworld",
                    unicodePlayer,
                    unicodeItem
            );

            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            ChestLogPageRequestPayload.STREAM_CODEC.encode(buf, request);

            ChestLogPageRequestPayload decoded = ChestLogPageRequestPayload.STREAM_CODEC.decode(buf);

            assertThat(decoded.filterPlayer()).isEqualTo(unicodePlayer);
            assertThat(decoded.filterItem()).isEqualTo(unicodeItem);
        }

        @ParameterizedTest(name = "Malicious injection string: {0}")
        @ValueSource(strings = {
                "' OR '1'='1",
                "'; DROP TABLE transactions; --",
                "<script>alert('xss')</script>",
                "$(rm -rf /)",
                "{{7*7}}",
                "\\0\\r\\n\\t",
                "%s%s%s%s%s%n",
                "../../../etc/passwd"
        })
        @DisplayName("SQL, script, and command injection strings should serialize without corrupting packet codecs")
        void testInjectionStringsInFiltersDoNotCorruptCodecs(String injectionString) {
            UUID queryId = UUID.randomUUID();
            ChestLogPageRequestPayload request = new ChestLogPageRequestPayload(
                    queryId,
                    1,
                    400L,
                    "minecraft:overworld",
                    injectionString,
                    injectionString
            );

            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            ChestLogPageRequestPayload.STREAM_CODEC.encode(buf, request);

            ChestLogPageRequestPayload decoded = ChestLogPageRequestPayload.STREAM_CODEC.decode(buf);

            assertThat(decoded.filterPlayer()).isEqualTo(injectionString);
            assertThat(decoded.filterItem()).isEqualTo(injectionString);
        }

        @Test
        @DisplayName("Regex metacharacters in filter queries should not throw PatternSyntaxException during string filtering")
        void testRegexSpecialCharactersDoNotCrashFiltering() {
            List<TransactionLogEntry> records = List.of(
                    new TransactionLogEntry(
                            1L, System.currentTimeMillis(), UUID.randomUUID(), ActionType.PLACE, ActorType.PLAYER,
                            UUID.randomUUID(), "[Admin] Steve (VIP)", "minecraft:overworld", 100L,
                            List.of(new SlotDelta(0, "minecraft:diamond", 1, 0, 1, 0L))
                    ),
                    new TransactionLogEntry(
                            2L, System.currentTimeMillis(), UUID.randomUUID(), ActionType.PICKUP, ActorType.PLAYER,
                            UUID.randomUUID(), "RegularPlayer", "minecraft:overworld", 100L,
                            List.of(new SlotDelta(0, "minecraft:gold_ingot", -1, 1, 0, 0L))
                    )
            );

            // Searching for regex characters like "[Admin]", "(VIP)", ".*+", "^$"
            String searchPattern = " [ADMIN] ";
            String normalizedSearch = searchPattern.trim().toLowerCase();

            List<TransactionLogEntry> matched = records.stream()
                    .filter(r -> r.actorName() != null && r.actorName().toLowerCase().contains(normalizedSearch))
                    .toList();

            assertThat(matched).hasSize(1);
            assertThat(matched.get(0).actorName()).isEqualTo("[Admin] Steve (VIP)");

            // Special character query with unmatched brackets
            String unclosedRegex = "[VIP(";
            String unclosedNormalized = unclosedRegex.trim().toLowerCase();

            assertThatCode(() -> {
                records.stream()
                        .filter(r -> r.actorName() != null && r.actorName().toLowerCase().contains(unclosedNormalized))
                        .toList();
            }).doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // 5. CUSTOM / UNUSUAL DIMENSION IDENTIFIERS
    // =========================================================================
    @Nested
    @DisplayName("5. Custom Dimension Identifiers")
    class CustomDimensionIdentifierTests {

        @ParameterizedTest(name = "Dimension identifier: {0}")
        @ValueSource(strings = {
                "minecraft:overworld",
                "minecraft:the_nether",
                "minecraft:the_end",
                "mythic_realms:dark_dimension",
                "a_custom_mod:deep_underground_caverns_v2",
                "mod-with-hyphens:dimension-with-hyphens",
                "complex_namespace.sub:dim_name_12345",
                "custom:very/deep/nested/dimension/path"
        })
        @DisplayName("Should encode and decode payloads with diverse and modded dimension identifiers")
        void testCustomModdedDimensionIdentifiersInPayloads(String dimension) {
            UUID queryId = UUID.randomUUID();
            UUID actorUuid = UUID.randomUUID();

            // Request payload
            ChestLogPageRequestPayload request = new ChestLogPageRequestPayload(
                    queryId, 1, 99999L, dimension, "Player", "minecraft:chest"
            );

            FriendlyByteBuf reqBuf = new FriendlyByteBuf(Unpooled.buffer());
            ChestLogPageRequestPayload.STREAM_CODEC.encode(reqBuf, request);
            ChestLogPageRequestPayload decodedReq = ChestLogPageRequestPayload.STREAM_CODEC.decode(reqBuf);

            assertThat(decodedReq.dimension()).isEqualTo(dimension);

            // Page response payload
            DisplayRecord record = new DisplayRecord(
                    1L, System.currentTimeMillis(), actorUuid, "Actor", (byte) 0, (byte) 1, 0, "minecraft:stone", 1, 0L
            );
            ChestLogPagePayload pagePayload = new ChestLogPagePayload(
                    queryId, 1, 1, 1, "Chest", dimension, 99999L, List.of(record)
            );

            FriendlyByteBuf pageBuf = new FriendlyByteBuf(Unpooled.buffer());
            ChestLogPagePayload.STREAM_CODEC.encode(pageBuf, pagePayload);
            ChestLogPagePayload decodedPage = ChestLogPagePayload.STREAM_CODEC.decode(pageBuf);

            assertThat(decodedPage.dimension()).isEqualTo(dimension);
        }

        @Test
        @DisplayName("Should support very long custom dimension identifier strings (256+ characters)")
        void testVeryLongCustomDimensionIdentifierInPayloads() {
            String longDimension = "custom_universe_" + "a".repeat(100) + ":" + "dimension_path_" + "b".repeat(140);
            UUID queryId = UUID.randomUUID();

            ChestLogPageRequestPayload request = new ChestLogPageRequestPayload(
                    queryId, 1, 12345L, longDimension, null, null
            );

            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            ChestLogPageRequestPayload.STREAM_CODEC.encode(buf, request);
            ChestLogPageRequestPayload decoded = ChestLogPageRequestPayload.STREAM_CODEC.decode(buf);

            assertThat(decoded.dimension()).isEqualTo(longDimension);
        }

        @Test
        @DisplayName("IndexQueryFilter and IndexPointer should accurately filter by custom dimensions")
        void testIndexQueryFilterWithCustomDimension() {
            String customDim = "mythic_realms:dark_dimension";
            IndexQueryFilter filter = IndexQueryFilter.builder()
                    .dimension(customDim)
                    .build();

            IndexPointer matchPointer = new IndexPointer(
                    1L, 1000L, UUID.randomUUID(), "minecraft:diamond", customDim, 100L, 0, 32L, 0
            );
            IndexPointer mismatchPointer = new IndexPointer(
                    2L, 1000L, UUID.randomUUID(), "minecraft:diamond", "minecraft:overworld", 100L, 0, 32L, 0
            );

            assertThat(filter.matches(matchPointer)).isTrue();
            assertThat(filter.matches(mismatchPointer)).isFalse();
        }

        @Test
        @DisplayName("QuerySessionManager should preserve custom dimension identifier across all page slices")
        void testQuerySessionDimensionPreservation() {
            QuerySessionManager sessionManager = new QuerySessionManager(25);
            UUID queryId = UUID.randomUUID();
            UUID actorUuid = UUID.randomUUID();
            String customDim = "mythic_realms:dark_dimension";
            long pos = BlockPosUtil.pack(10, 20, 30);

            List<TransactionLogEntry> records = createSampleRecords(50, actorUuid, customDim, pos);
            ChestLogPagePayload page1 = sessionManager.createSession(queryId, "MythicChest", customDim, pos, records, 1);
            assertThat(page1.dimension()).isEqualTo(customDim);

            ChestLogPagePayload page2 = sessionManager.getPage(queryId, 2);
            assertThat(page2).isNotNull();
            assertThat(page2.dimension()).isEqualTo(customDim);
        }
    }

    // =========================================================================
    // 6. PAYLOAD CODEC RESILIENCE & EXTREME BOUNDS
    // =========================================================================
    @Nested
    @DisplayName("6. Payload Codec Resilience & Extreme Bounds")
    class PayloadCodecResilienceAndExtremeBoundsTests {

        @Test
        @DisplayName("DisplayRecord codec should handle extreme positive and negative long/int/byte values")
        void testDisplayRecordWithExtremeValues() {
            UUID actorUuid = UUID.randomUUID();

            // Extreme Max Values
            DisplayRecord maxRecord = new DisplayRecord(
                    Long.MAX_VALUE,
                    Long.MAX_VALUE,
                    actorUuid,
                    "MaxActorName_" + "X".repeat(50),
                    Byte.MAX_VALUE,
                    Byte.MAX_VALUE,
                    Integer.MAX_VALUE,
                    "mod:max_item_id_" + "Y".repeat(100),
                    Integer.MAX_VALUE,
                    Long.MAX_VALUE
            );

            FriendlyByteBuf maxBuf = new FriendlyByteBuf(Unpooled.buffer());
            DisplayRecord.write(maxBuf, maxRecord);
            DisplayRecord decodedMax = DisplayRecord.read(maxBuf);

            assertThat(decodedMax.sequenceId()).isEqualTo(Long.MAX_VALUE);
            assertThat(decodedMax.timestampMs()).isEqualTo(Long.MAX_VALUE);
            assertThat(decodedMax.actorUuid()).isEqualTo(actorUuid);
            assertThat(decodedMax.actorName()).isEqualTo(maxRecord.actorName());
            assertThat(decodedMax.actorType()).isEqualTo(Byte.MAX_VALUE);
            assertThat(decodedMax.actionType()).isEqualTo(Byte.MAX_VALUE);
            assertThat(decodedMax.slotIndex()).isEqualTo(Integer.MAX_VALUE);
            assertThat(decodedMax.itemId()).isEqualTo(maxRecord.itemId());
            assertThat(decodedMax.quantityDelta()).isEqualTo(Integer.MAX_VALUE);
            assertThat(decodedMax.metadataFingerprint()).isEqualTo(Long.MAX_VALUE);

            // Extreme Min / Negative Values
            DisplayRecord minRecord = new DisplayRecord(
                    0L,
                    0L,
                    actorUuid,
                    "MinActor",
                    Byte.MIN_VALUE,
                    Byte.MIN_VALUE,
                    Integer.MIN_VALUE,
                    "minecraft:air",
                    Integer.MIN_VALUE,
                    Long.MIN_VALUE
            );

            FriendlyByteBuf minBuf = new FriendlyByteBuf(Unpooled.buffer());
            DisplayRecord.write(minBuf, minRecord);
            DisplayRecord decodedMin = DisplayRecord.read(minBuf);

            assertThat(decodedMin.sequenceId()).isEqualTo(0L);
            assertThat(decodedMin.timestampMs()).isEqualTo(0L);
            assertThat(decodedMin.actorType()).isEqualTo(Byte.MIN_VALUE);
            assertThat(decodedMin.actionType()).isEqualTo(Byte.MIN_VALUE);
            assertThat(decodedMin.slotIndex()).isEqualTo(Integer.MIN_VALUE);
            assertThat(decodedMin.quantityDelta()).isEqualTo(Integer.MIN_VALUE);
            assertThat(decodedMin.metadataFingerprint()).isEqualTo(Long.MIN_VALUE);
        }

        @Test
        @DisplayName("DisplayRecord should reject null required constructor fields")
        void testDisplayRecordNullConstructorValidation() {
            UUID actorUuid = UUID.randomUUID();

            assertThatNullPointerException()
                    .isThrownBy(() -> new DisplayRecord(1L, 0L, null, "Steve", (byte) 0, (byte) 0, 0, "minecraft:stone", 1, 0L))
                    .withMessageContaining("actorUuid");

            assertThatNullPointerException()
                    .isThrownBy(() -> new DisplayRecord(1L, 0L, actorUuid, null, (byte) 0, (byte) 0, 0, "minecraft:stone", 1, 0L))
                    .withMessageContaining("actorName");

            assertThatNullPointerException()
                    .isThrownBy(() -> new DisplayRecord(1L, 0L, actorUuid, "Steve", (byte) 0, (byte) 0, 0, null, 1, 0L))
                    .withMessageContaining("itemId");
        }

        @Test
        @DisplayName("ChestLogPagePayload should reject null required constructor fields")
        void testChestLogPagePayloadNullConstructorValidation() {
            UUID queryId = UUID.randomUUID();

            assertThatNullPointerException()
                    .isThrownBy(() -> new ChestLogPagePayload(null, 1, 1, 0, "Chest", "minecraft:overworld", 0L, List.of()))
                    .withMessageContaining("queryId");

            assertThatNullPointerException()
                    .isThrownBy(() -> new ChestLogPagePayload(queryId, 1, 1, 0, null, "minecraft:overworld", 0L, List.of()))
                    .withMessageContaining("containerType");

            assertThatNullPointerException()
                    .isThrownBy(() -> new ChestLogPagePayload(queryId, 1, 1, 0, "Chest", null, 0L, List.of()))
                    .withMessageContaining("dimension");

            assertThatNullPointerException()
                    .isThrownBy(() -> new ChestLogPagePayload(queryId, 1, 1, 0, "Chest", "minecraft:overworld", 0L, null))
                    .withMessageContaining("records");
        }

        @Test
        @DisplayName("ChestLogPageRequestPayload should reject null required constructor fields")
        void testChestLogPageRequestPayloadNullConstructorValidation() {
            UUID queryId = UUID.randomUUID();

            assertThatNullPointerException()
                    .isThrownBy(() -> new ChestLogPageRequestPayload(null, 1, 0L, "minecraft:overworld", null, null))
                    .withMessageContaining("queryId");

            assertThatNullPointerException()
                    .isThrownBy(() -> new ChestLogPageRequestPayload(queryId, 1, 0L, null, null, null))
                    .withMessageContaining("dimension");
        }

        @Test
        @DisplayName("ChestLogPagePayload codec should handle large batches of DisplayRecords (200+ records) without buffer overflow")
        void testLargePayloadWithManyDisplayRecords() {
            UUID queryId = UUID.randomUUID();
            UUID actorUuid = UUID.randomUUID();
            int recordCount = 250;

            List<DisplayRecord> records = new ArrayList<>(recordCount);
            for (int i = 1; i <= recordCount; i++) {
                records.add(new DisplayRecord(
                        i,
                        1723849000000L + (i * 1000L),
                        actorUuid,
                        "Player_" + (i % 10),
                        (byte) (i % 6),
                        (byte) (i % 13),
                        i % 54,
                        "minecraft:item_" + i,
                        (i % 2 == 0) ? i : -i,
                        (long) i * 123456789L
                ));
            }

            ChestLogPagePayload largePayload = new ChestLogPagePayload(
                    queryId, 1, 1, recordCount, "MassiveChest", "minecraft:overworld", 987654321L, records
            );

            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            ChestLogPagePayload.STREAM_CODEC.encode(buf, largePayload);

            ChestLogPagePayload decoded = ChestLogPagePayload.STREAM_CODEC.decode(buf);

            assertThat(decoded.records()).hasSize(recordCount);
            assertThat(decoded.records().get(0).sequenceId()).isEqualTo(1L);
            assertThat(decoded.records().get(recordCount - 1).sequenceId()).isEqualTo(recordCount);
            assertThat(decoded.records().get(recordCount - 1).itemId()).isEqualTo("minecraft:item_" + recordCount);
        }

        @Test
        @DisplayName("Should encode and decode maximum length UTF-8 strings in containerType, actorName, and itemId")
        void testVeryLongStringFieldsInPayloads() {
            UUID queryId = UUID.randomUUID();
            UUID actorUuid = UUID.randomUUID();

            String longContainerType = "Reinforced_Diamond_Shulker_Box_" + "A".repeat(200);
            String longActorName = "Super_Long_Player_Name_" + "B".repeat(150);
            String longItemId = "custom_mod_id:super_deeply_nested_item_identifier_name_" + "C".repeat(250);

            DisplayRecord record = new DisplayRecord(
                    1L,
                    System.currentTimeMillis(),
                    actorUuid,
                    longActorName,
                    (byte) 0,
                    (byte) 2,
                    0,
                    longItemId,
                    64,
                    0L
            );

            ChestLogPagePayload payload = new ChestLogPagePayload(
                    queryId,
                    1,
                    1,
                    1,
                    longContainerType,
                    "minecraft:overworld",
                    12345L,
                    List.of(record)
            );

            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            ChestLogPagePayload.STREAM_CODEC.encode(buf, payload);

            ChestLogPagePayload decoded = ChestLogPagePayload.STREAM_CODEC.decode(buf);

            assertThat(decoded.containerType()).isEqualTo(longContainerType);
            assertThat(decoded.records().get(0).actorName()).isEqualTo(longActorName);
            assertThat(decoded.records().get(0).itemId()).isEqualTo(longItemId);
        }
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================
    private static List<TransactionLogEntry> createSampleRecords(int count, UUID actorUuid, String dimension, long pos) {
        List<TransactionLogEntry> list = new ArrayList<>(count);
        long baseTime = 1723849000000L;
        for (int i = 1; i <= count; i++) {
            list.add(new TransactionLogEntry(
                    i,
                    baseTime + (i * 1000L),
                    UUID.randomUUID(),
                    (i % 2 == 0) ? ActionType.PLACE : ActionType.PICKUP,
                    ActorType.PLAYER,
                    actorUuid,
                    "Tester_" + (i % 5),
                    dimension,
                    pos,
                    List.of(new SlotDelta(
                            i % 27,
                            (i % 3 == 0) ? "minecraft:diamond" : "minecraft:gold_ingot",
                            (i % 2 == 0) ? 1 : -1,
                            10,
                            (i % 2 == 0) ? 11 : 9,
                            0L
                    ))
            ));
        }
        return list;
    }
}
