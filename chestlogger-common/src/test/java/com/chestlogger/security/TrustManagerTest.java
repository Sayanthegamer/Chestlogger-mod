package com.chestlogger.security;

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

class TrustManagerTest {

    @TempDir
    Path tempDir;

    private TrustManager trustManager;
    private UUID alice;
    private UUID bob;
    private UUID charlie;
    private UUID dave;

    @BeforeEach
    void setUp() {
        trustManager = new TrustManager(tempDir.resolve("test_trust.json"));
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        charlie = UUID.randomUUID();
        dave = UUID.randomUUID();
    }

    @Test
    @DisplayName("Basic trust and untrust lifecycle operations")
    void testBasicTrustAndUntrust() {
        // Initial state: stranger is not trusted
        assertThat(trustManager.isTrusted(alice, bob)).isFalse();
        assertThat(trustManager.getTrustList(alice)).isEmpty();

        // Grant trust
        assertThat(trustManager.trust(alice, bob)).isTrue();
        assertThat(trustManager.isTrusted(alice, bob)).isTrue();
        assertThat(trustManager.getTrustList(alice)).containsExactly(bob);

        // Duplicate trust grant should return false
        assertThat(trustManager.trust(alice, bob)).isFalse();

        // Self-trust is always implicitly true
        assertThat(trustManager.isTrusted(alice, alice)).isTrue();
        assertThat(trustManager.trust(alice, alice)).isFalse();

        // Revoke trust
        assertThat(trustManager.untrust(alice, bob)).isTrue();
        assertThat(trustManager.isTrusted(alice, bob)).isFalse();
        assertThat(trustManager.getTrustList(alice)).isEmpty();

        // Duplicate untrust should return false
        assertThat(trustManager.untrust(alice, bob)).isFalse();
    }

    @Test
    @DisplayName("Null safety and edge cases")
    void testNullSafety() {
        assertThat(trustManager.isTrusted(null, bob)).isFalse();
        assertThat(trustManager.isTrusted(alice, null)).isFalse();
        assertThat(trustManager.isTrusted(null, null)).isFalse();

        assertThat(trustManager.trust(null, bob)).isFalse();
        assertThat(trustManager.trust(alice, null)).isFalse();

        assertThat(trustManager.untrust(null, bob)).isFalse();
        assertThat(trustManager.untrust(alice, null)).isFalse();

        assertThat(trustManager.getTrustList(null)).isEmpty();
        assertThat(trustManager.getTrustingOwners(null)).isEmpty();
    }

    @Test
    @DisplayName("Bidirectional queries: getTrustList and getTrustingOwners")
    void testBidirectionalQueries() {
        // Alice trusts Bob and Charlie
        trustManager.trust(alice, bob);
        trustManager.trust(alice, charlie);

        // Dave also trusts Bob
        trustManager.trust(dave, bob);

        // Forward queries
        assertThat(trustManager.getTrustList(alice)).containsExactlyInAnyOrder(bob, charlie);
        assertThat(trustManager.getTrustList(dave)).containsExactly(bob);
        assertThat(trustManager.getTrustList(bob)).isEmpty();

        // Reverse / bidirectional queries (who trusts Bob?)
        assertThat(trustManager.getTrustingOwners(bob)).containsExactlyInAnyOrder(alice, dave);
        assertThat(trustManager.getTrustingOwners(charlie)).containsExactly(alice);
        assertThat(trustManager.getTrustingOwners(dave)).isEmpty();
    }

    @Test
    @DisplayName("Persistence: save and load JSON accurately preserves trust state")
    void testSaveAndLoadPersistence() throws IOException {
        Path file = tempDir.resolve("persistent_trust.json");
        TrustManager manager1 = new TrustManager(file);

        manager1.trust(alice, bob);
        manager1.trust(alice, charlie);
        manager1.trust(dave, bob);

        manager1.save();
        assertThat(Files.exists(file)).isTrue();
        String jsonContent = Files.readString(file);
        assertThat(jsonContent).contains(alice.toString());
        assertThat(jsonContent).contains(bob.toString());
        assertThat(jsonContent).contains(charlie.toString());

        // Load in a fresh manager
        TrustManager manager2 = new TrustManager(file);
        manager2.load();

        assertThat(manager2.isTrusted(alice, bob)).isTrue();
        assertThat(manager2.isTrusted(alice, charlie)).isTrue();
        assertThat(manager2.isTrusted(dave, bob)).isTrue();
        assertThat(manager2.isTrusted(alice, dave)).isFalse();
        assertThat(manager2.getTrustList(alice)).containsExactlyInAnyOrder(bob, charlie);
        assertThat(manager2.getTrustingOwners(bob)).containsExactlyInAnyOrder(alice, dave);
    }

    @Test
    @DisplayName("Loading non-existent, empty, or corrupted files degrades gracefully")
    void testGracefulDegradationOnInvalidFiles() throws IOException {
        Path missing = tempDir.resolve("missing.json");
        TrustManager manager = new TrustManager(missing);
        manager.load();
        assertThat(manager.getOwnerCount()).isEqualTo(0);

        Path empty = tempDir.resolve("empty.json");
        Files.writeString(empty, "");
        manager = new TrustManager(empty);
        manager.load();
        assertThat(manager.getOwnerCount()).isEqualTo(0);

        Path corrupted = tempDir.resolve("corrupted.json");
        Files.writeString(corrupted, "{ invalid json @@ ## ");
        manager = new TrustManager(corrupted);
        manager.load();
        assertThat(manager.getOwnerCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Thread safety under concurrent trust modifications and queries")
    void testConcurrentTrustModifications() throws InterruptedException {
        int threadCount = 16;
        int operationsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        List<UUID> owners = List.of(alice, bob, charlie, dave);
        List<UUID> targets = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            targets.add(UUID.randomUUID());
        }

        AtomicInteger successCount = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            int threadId = t;
            executor.submit(() -> {
                try {
                    Random rand = new Random(threadId);
                    for (int i = 0; i < operationsPerThread; i++) {
                        UUID owner = owners.get(rand.nextInt(owners.size()));
                        UUID target = targets.get(rand.nextInt(targets.size()));
                        int op = rand.nextInt(5);

                        switch (op) {
                            case 0 -> trustManager.trust(owner, target);
                            case 1 -> trustManager.untrust(owner, target);
                            case 2 -> trustManager.isTrusted(owner, target);
                            case 3 -> trustManager.getTrustList(owner);
                            case 4 -> trustManager.getTrustingOwners(target);
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
