package com.chestlogger.claim;

import com.chestlogger.event.BlockPosUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClaimManager & Container Claim Storage Unit Tests")
class ClaimManagerTest {

    @TempDir
    Path tempDir;

    private ClaimManager claimManager;
    private UUID alice;
    private UUID bob;
    private UUID charlie;

    private static final String DIM_OVERWORLD = "minecraft:overworld";
    private static final String DIM_NETHER = "minecraft:the_nether";

    @BeforeEach
    void setUp() {
        claimManager = new ClaimManager(tempDir.resolve("claims.json"));
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        charlie = UUID.randomUUID();
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Claiming a single container
    // ---------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Claiming a single container registers ownership, name and query lookups")
    void testSingleContainerClaimLifecycle() {
        long pos = BlockPosUtil.pack(100, 64, -200);

        // Initial state: not claimed
        assertThat(claimManager.isClaimed(DIM_OVERWORLD, pos)).isFalse();
        assertThat(claimManager.getOwner(DIM_OVERWORLD, pos)).isNull();
        assertThat(claimManager.getOwnerName(DIM_OVERWORLD, pos)).isNull();
        assertThat(claimManager.isOwner(DIM_OVERWORLD, pos, alice)).isFalse();
        assertThat(claimManager.getClaim(DIM_OVERWORLD, pos)).isNull();

        // Claim container
        boolean claimed = claimManager.claim(DIM_OVERWORLD, pos, alice, "Alice");
        assertThat(claimed).isTrue();

        // Post-claim verification
        assertThat(claimManager.isClaimed(DIM_OVERWORLD, pos)).isTrue();
        assertThat(claimManager.getOwner(DIM_OVERWORLD, pos)).isEqualTo(alice);
        assertThat(claimManager.getOwnerName(DIM_OVERWORLD, pos)).isEqualTo("Alice");
        assertThat(claimManager.isOwner(DIM_OVERWORLD, pos, alice)).isTrue();
        assertThat(claimManager.isOwner(DIM_OVERWORLD, pos, bob)).isFalse();
        assertThat(claimManager.getClaimCount()).isEqualTo(1);

        ClaimEntry entry = claimManager.getClaim(DIM_OVERWORLD, pos);
        assertThat(entry).isNotNull();
        assertThat(entry.dimension()).isEqualTo(DIM_OVERWORLD);
        assertThat(entry.packedBlockPos()).isEqualTo(pos);
        assertThat(entry.ownerUuid()).isEqualTo(alice);
        assertThat(entry.ownerName()).isEqualTo("Alice");
        assertThat(entry.partnerPackedPos()).isNull();
    }

    @Test
    @DisplayName("Claiming with invalid or null inputs fails safely")
    void testNullAndInvalidInputHandling() {
        long pos = BlockPosUtil.pack(50, 70, 50);

        assertThat(claimManager.claim(null, pos, alice, "Alice")).isFalse();
        assertThat(claimManager.claim(DIM_OVERWORLD, pos, null, "Alice")).isFalse();
        assertThat(claimManager.isClaimed(null, pos)).isFalse();
        assertThat(claimManager.getOwner(null, pos)).isNull();
        assertThat(claimManager.getOwnerName(null, pos)).isNull();
        assertThat(claimManager.isOwner(null, pos, alice)).isFalse();
        assertThat(claimManager.isOwner(DIM_OVERWORLD, pos, null)).isFalse();
        assertThat(claimManager.unclaim(null, pos)).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Unclaiming a container
    // ---------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Unclaiming a container clears claim state and resets ownership")
    void testUnclaimContainer() {
        long pos = BlockPosUtil.pack(12, 65, 34);

        claimManager.claim(DIM_OVERWORLD, pos, alice, "Alice");
        assertThat(claimManager.isClaimed(DIM_OVERWORLD, pos)).isTrue();

        // Unclaim
        boolean removed = claimManager.unclaim(DIM_OVERWORLD, pos);
        assertThat(removed).isTrue();

        // State is completely cleared
        assertThat(claimManager.isClaimed(DIM_OVERWORLD, pos)).isFalse();
        assertThat(claimManager.getOwner(DIM_OVERWORLD, pos)).isNull();
        assertThat(claimManager.getOwnerName(DIM_OVERWORLD, pos)).isNull();
        assertThat(claimManager.isOwner(DIM_OVERWORLD, pos, alice)).isFalse();
        assertThat(claimManager.getClaim(DIM_OVERWORLD, pos)).isNull();
        assertThat(claimManager.getClaimCount()).isEqualTo(0);

        // Subsequent unclaim returns false
        assertThat(claimManager.unclaim(DIM_OVERWORLD, pos)).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Overwriting or updating an existing claim
    // ---------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Overwriting an existing container claim updates ownership and owner display name")
    void testOverwriteExistingClaim() {
        long pos = BlockPosUtil.pack(10, 64, 10);

        // Alice claims pos
        claimManager.claim(DIM_OVERWORLD, pos, alice, "Alice");
        assertThat(claimManager.getOwner(DIM_OVERWORLD, pos)).isEqualTo(alice);
        assertThat(claimManager.getOwnerName(DIM_OVERWORLD, pos)).isEqualTo("Alice");

        // Bob overwrites claim on pos
        boolean updated = claimManager.claim(DIM_OVERWORLD, pos, bob, "Bob");
        assertThat(updated).isTrue();

        // Ownership transferred to Bob
        assertThat(claimManager.getOwner(DIM_OVERWORLD, pos)).isEqualTo(bob);
        assertThat(claimManager.getOwnerName(DIM_OVERWORLD, pos)).isEqualTo("Bob");
        assertThat(claimManager.isOwner(DIM_OVERWORLD, pos, alice)).isFalse();
        assertThat(claimManager.isOwner(DIM_OVERWORLD, pos, bob)).isTrue();
        assertThat(claimManager.getClaimCount()).isEqualTo(1);

        // Alice's claims list no longer contains pos, Bob's claims list does
        assertThat(claimManager.getClaimsByOwner(alice)).isEmpty();
        assertThat(claimManager.getClaimsByOwner(bob)).hasSize(1);
    }

    @Test
    @DisplayName("Updating claim with updated username preserves UUID ownership")
    void testUpdateOwnerDisplayName() {
        long pos = BlockPosUtil.pack(10, 64, 10);

        claimManager.claim(DIM_OVERWORLD, pos, alice, "AliceOldName");
        assertThat(claimManager.getOwnerName(DIM_OVERWORLD, pos)).isEqualTo("AliceOldName");

        claimManager.claim(DIM_OVERWORLD, pos, alice, "AliceNewName");
        assertThat(claimManager.getOwner(DIM_OVERWORLD, pos)).isEqualTo(alice);
        assertThat(claimManager.getOwnerName(DIM_OVERWORLD, pos)).isEqualTo("AliceNewName");
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Double-chest partner claiming
    // ---------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Claiming a double chest registers partner coordinates and updates both halves")
    void testDoubleChestPartnerClaiming() {
        long left = BlockPosUtil.pack(100, 64, -200);
        long right = BlockPosUtil.pack(101, 64, -200);

        boolean claimed = claimManager.claim(DIM_OVERWORLD, left, right, alice, "Alice");
        assertThat(claimed).isTrue();

        // Both halves report claimed by Alice
        assertThat(claimManager.isClaimed(DIM_OVERWORLD, left)).isTrue();
        assertThat(claimManager.isClaimed(DIM_OVERWORLD, right)).isTrue();
        assertThat(claimManager.getOwner(DIM_OVERWORLD, left)).isEqualTo(alice);
        assertThat(claimManager.getOwner(DIM_OVERWORLD, right)).isEqualTo(alice);
        assertThat(claimManager.getOwnerName(DIM_OVERWORLD, left)).isEqualTo("Alice");
        assertThat(claimManager.getOwnerName(DIM_OVERWORLD, right)).isEqualTo("Alice");

        // Partner pointers match symmetrically
        ClaimEntry leftEntry = claimManager.getClaim(DIM_OVERWORLD, left);
        ClaimEntry rightEntry = claimManager.getClaim(DIM_OVERWORLD, right);
        assertThat(leftEntry).isNotNull();
        assertThat(rightEntry).isNotNull();
        assertThat(leftEntry.partnerPackedPos()).isEqualTo(right);
        assertThat(rightEntry.partnerPackedPos()).isEqualTo(left);

        // Total claim count reflects both blocks
        assertThat(claimManager.getClaimCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Unclaiming one half of a double chest unclaims both halves atomically")
    void testDoubleChestAtomicUnclaim() {
        long left = BlockPosUtil.pack(100, 64, -200);
        long right = BlockPosUtil.pack(101, 64, -200);

        claimManager.claim(DIM_OVERWORLD, left, right, alice, "Alice");
        assertThat(claimManager.getClaimCount()).isEqualTo(2);

        // Unclaim by targeting the right half
        boolean removed = claimManager.unclaim(DIM_OVERWORLD, right);
        assertThat(removed).isTrue();

        // Both halves must now be unclaimed
        assertThat(claimManager.isClaimed(DIM_OVERWORLD, left)).isFalse();
        assertThat(claimManager.isClaimed(DIM_OVERWORLD, right)).isFalse();
        assertThat(claimManager.getOwner(DIM_OVERWORLD, left)).isNull();
        assertThat(claimManager.getOwner(DIM_OVERWORLD, right)).isNull();
        assertThat(claimManager.getClaim(DIM_OVERWORLD, left)).isNull();
        assertThat(claimManager.getClaim(DIM_OVERWORLD, right)).isNull();
        assertThat(claimManager.getClaimCount()).isEqualTo(0);
        assertThat(claimManager.getClaimsByOwner(alice)).isEmpty();
    }

    @Test
    @DisplayName("Overwriting a double chest with a new owner updates both halves atomically")
    void testDoubleChestAtomicOverwrite() {
        long left = BlockPosUtil.pack(100, 64, -200);
        long right = BlockPosUtil.pack(101, 64, -200);

        claimManager.claim(DIM_OVERWORLD, left, right, alice, "Alice");
        assertThat(claimManager.getOwner(DIM_OVERWORLD, left)).isEqualTo(alice);

        // Bob claims the double chest
        claimManager.claim(DIM_OVERWORLD, left, right, bob, "Bob");

        assertThat(claimManager.getOwner(DIM_OVERWORLD, left)).isEqualTo(bob);
        assertThat(claimManager.getOwner(DIM_OVERWORLD, right)).isEqualTo(bob);
        assertThat(claimManager.getOwnerName(DIM_OVERWORLD, left)).isEqualTo("Bob");
        assertThat(claimManager.getOwnerName(DIM_OVERWORLD, right)).isEqualTo("Bob");
        assertThat(claimManager.getClaimsByOwner(alice)).isEmpty();
        assertThat(claimManager.getClaimsByOwner(bob)).hasSize(2);
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Querying claims by owner UUID
    // ---------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Querying claims by owner UUID returns all active claims for that player")
    void testGetClaimsByOwner() {
        long pos1 = BlockPosUtil.pack(10, 64, 10);
        long pos2 = BlockPosUtil.pack(20, 64, 20);
        long pos3 = BlockPosUtil.pack(30, 64, 30);

        // Alice claims pos1 and pos2
        claimManager.claim(DIM_OVERWORLD, pos1, alice, "Alice");
        claimManager.claim(DIM_OVERWORLD, pos2, alice, "Alice");

        // Bob claims pos3
        claimManager.claim(DIM_OVERWORLD, pos3, bob, "Bob");

        Collection<ClaimEntry> aliceClaims = claimManager.getClaimsByOwner(alice);
        assertThat(aliceClaims).hasSize(2);
        assertThat(aliceClaims).extracting(ClaimEntry::packedBlockPos).containsExactlyInAnyOrder(pos1, pos2);

        Collection<ClaimEntry> bobClaims = claimManager.getClaimsByOwner(bob);
        assertThat(bobClaims).hasSize(1);
        assertThat(bobClaims).extracting(ClaimEntry::packedBlockPos).containsExactly(pos3);

        Collection<ClaimEntry> charlieClaims = claimManager.getClaimsByOwner(charlie);
        assertThat(charlieClaims).isEmpty();

        assertThat(claimManager.getClaimsByOwner(null)).isEmpty();

        // Unclaim pos1 from Alice
        claimManager.unclaim(DIM_OVERWORLD, pos1);
        assertThat(claimManager.getClaimsByOwner(alice)).hasSize(1);
        assertThat(claimManager.getClaimsByOwner(alice)).extracting(ClaimEntry::packedBlockPos).containsExactly(pos2);
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Radius-based claim lookups
    // ---------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Radius-based claim lookup correctly filters by spatial proximity and dimension")
    void testFindClaimsInRadius() {
        long posCenter = BlockPosUtil.pack(100, 64, 100);
        long posNear = BlockPosUtil.pack(105, 64, 102);     // dx=5, dz=2 -> inside radius 10
        long posFar = BlockPosUtil.pack(130, 64, 100);      // dx=30 -> outside radius 10
        long posNether = BlockPosUtil.pack(100, 64, 100);   // same coords but Nether

        claimManager.claim(DIM_OVERWORLD, posCenter, alice, "Alice");
        claimManager.claim(DIM_OVERWORLD, posNear, alice, "Alice");
        claimManager.claim(DIM_OVERWORLD, posFar, bob, "Bob");
        claimManager.claim(DIM_NETHER, posNether, charlie, "Charlie");

        // Lookup with coordinates (x, y, z, radius)
        List<ClaimEntry> foundByCoords = claimManager.findClaimsInRadius(DIM_OVERWORLD, 100, 64, 100, 10);
        assertThat(foundByCoords).hasSize(2);
        assertThat(foundByCoords).extracting(ClaimEntry::packedBlockPos).containsExactlyInAnyOrder(posCenter, posNear);

        // Lookup with packed pos overload
        List<ClaimEntry> foundByPacked = claimManager.findClaimsInRadius(DIM_OVERWORLD, posCenter, 10);
        assertThat(foundByPacked).hasSize(2);
        assertThat(foundByPacked).extracting(ClaimEntry::packedBlockPos).containsExactlyInAnyOrder(posCenter, posNear);

        // Lookup in Nether dimension
        List<ClaimEntry> foundNether = claimManager.findClaimsInRadius(DIM_NETHER, 100, 64, 100, 10);
        assertThat(foundNether).hasSize(1);
        assertThat(foundNether.get(0).packedBlockPos()).isEqualTo(posNether);
        assertThat(foundNether.get(0).ownerUuid()).isEqualTo(charlie);

        // Zero radius returns only exact match
        List<ClaimEntry> exactOnly = claimManager.findClaimsInRadius(DIM_OVERWORLD, 100, 64, 100, 0);
        assertThat(exactOnly).hasSize(1);
        assertThat(exactOnly.get(0).packedBlockPos()).isEqualTo(posCenter);
    }

    // ---------------------------------------------------------------------------------------------
    // 7. JSON serialization and deserialization
    // ---------------------------------------------------------------------------------------------
    @Test
    @DisplayName("JSON serialization and deserialization preserves claims, partner links, and owner names")
    void testJsonSerializationAndDeserialization() {
        long singlePos = BlockPosUtil.pack(10, 64, 10);
        long left = BlockPosUtil.pack(100, 64, 100);
        long right = BlockPosUtil.pack(101, 64, 100);

        claimManager.claim(DIM_OVERWORLD, singlePos, alice, "Alice");
        claimManager.claim(DIM_OVERWORLD, left, right, bob, "Bob");

        String json = claimManager.toJson();
        assertThat(json).isNotNull().isNotBlank();
        assertThat(json).contains(alice.toString());
        assertThat(json).contains("Alice");
        assertThat(json).contains(bob.toString());
        assertThat(json).contains("Bob");
        assertThat(json).contains(DIM_OVERWORLD);

        // Deserialize into a fresh manager
        ClaimManager freshManager = new ClaimManager();
        freshManager.fromJson(json);

        assertThat(freshManager.getClaimCount()).isEqualTo(3);
        assertThat(freshManager.isClaimed(DIM_OVERWORLD, singlePos)).isTrue();
        assertThat(freshManager.getOwner(DIM_OVERWORLD, singlePos)).isEqualTo(alice);
        assertThat(freshManager.getOwnerName(DIM_OVERWORLD, singlePos)).isEqualTo("Alice");

        assertThat(freshManager.isClaimed(DIM_OVERWORLD, left)).isTrue();
        assertThat(freshManager.isClaimed(DIM_OVERWORLD, right)).isTrue();
        assertThat(freshManager.getOwner(DIM_OVERWORLD, left)).isEqualTo(bob);
        assertThat(freshManager.getOwner(DIM_OVERWORLD, right)).isEqualTo(bob);
        assertThat(freshManager.getClaim(DIM_OVERWORLD, left).partnerPackedPos()).isEqualTo(right);
        assertThat(freshManager.getClaim(DIM_OVERWORLD, right).partnerPackedPos()).isEqualTo(left);
    }

    @Test
    @DisplayName("Persistent save and load to disk preserves all claim state")
    void testSaveAndLoadPersistence() throws IOException {
        Path file = tempDir.resolve("persistent_claims.json");
        ClaimManager manager1 = new ClaimManager(file);

        long singlePos = BlockPosUtil.pack(15, 64, 15);
        long left = BlockPosUtil.pack(50, 64, 50);
        long right = BlockPosUtil.pack(51, 64, 50);

        manager1.claim(DIM_OVERWORLD, singlePos, alice, "Alice");
        manager1.claim(DIM_OVERWORLD, left, right, bob, "Bob");
        manager1.save();

        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.size(file)).isGreaterThan(0);

        // Load into a new manager instance
        ClaimManager manager2 = new ClaimManager(file);
        manager2.load();

        assertThat(manager2.getClaimCount()).isEqualTo(3);
        assertThat(manager2.isClaimed(DIM_OVERWORLD, singlePos)).isTrue();
        assertThat(manager2.getOwner(DIM_OVERWORLD, singlePos)).isEqualTo(alice);
        assertThat(manager2.getClaimsByOwner(alice)).hasSize(1);
        assertThat(manager2.getClaimsByOwner(bob)).hasSize(2);

        // Double chest unclaim works on loaded manager
        manager2.unclaim(DIM_OVERWORLD, left);
        assertThat(manager2.isClaimed(DIM_OVERWORLD, left)).isFalse();
        assertThat(manager2.isClaimed(DIM_OVERWORLD, right)).isFalse();
        assertThat(manager2.getClaimCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Loading non-existent, empty, or corrupted JSON files degrades gracefully")
    void testGracefulDegradationOnCorruptedData() throws IOException {
        Path missing = tempDir.resolve("missing_claims.json");
        ClaimManager manager = new ClaimManager(missing);
        manager.load();
        assertThat(manager.getClaimCount()).isEqualTo(0);

        Path empty = tempDir.resolve("empty_claims.json");
        Files.writeString(empty, "");
        manager = new ClaimManager(empty);
        manager.load();
        assertThat(manager.getClaimCount()).isEqualTo(0);

        Path corrupted = tempDir.resolve("corrupted_claims.json");
        Files.writeString(corrupted, "{ invalid json @@ ## ");
        manager = new ClaimManager(corrupted);
        manager.load();
        assertThat(manager.getClaimCount()).isEqualTo(0);

        // fromJson with null or blank
        manager.fromJson(null);
        assertThat(manager.getClaimCount()).isEqualTo(0);
        manager.fromJson("");
        assertThat(manager.getClaimCount()).isEqualTo(0);
    }

    // ---------------------------------------------------------------------------------------------
    // 8. Thread safety under concurrent claim and unclaim operations
    // ---------------------------------------------------------------------------------------------
    @Test
    @DisplayName("Thread safety under concurrent claim, unclaim, and spatial query operations")
    void testConcurrentClaimAndUnclaimOperations() throws InterruptedException {
        int threadCount = 16;
        int operationsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        List<UUID> owners = List.of(alice, bob, charlie);
        List<String> names = List.of("Alice", "Bob", "Charlie");

        List<Long> positions = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            positions.add(BlockPosUtil.pack(i * 10, 64, i * 10));
        }

        AtomicInteger successCount = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            int threadId = t;
            executor.submit(() -> {
                try {
                    Random rand = new Random(threadId);
                    for (int i = 0; i < operationsPerThread; i++) {
                        int ownerIdx = rand.nextInt(owners.size());
                        UUID owner = owners.get(ownerIdx);
                        String name = names.get(ownerIdx);
                        long pos = positions.get(rand.nextInt(positions.size()));
                        int op = rand.nextInt(7);

                        switch (op) {
                            case 0 -> claimManager.claim(DIM_OVERWORLD, pos, owner, name);
                            case 1 -> claimManager.unclaim(DIM_OVERWORLD, pos);
                            case 2 -> claimManager.isClaimed(DIM_OVERWORLD, pos);
                            case 3 -> claimManager.getOwner(DIM_OVERWORLD, pos);
                            case 4 -> claimManager.getClaimsByOwner(owner);
                            case 5 -> claimManager.findClaimsInRadius(DIM_OVERWORLD, 100, 64, 100, 50);
                            case 6 -> claimManager.getClaimCount();
                        }
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(threadCount * operationsPerThread);
    }
}
