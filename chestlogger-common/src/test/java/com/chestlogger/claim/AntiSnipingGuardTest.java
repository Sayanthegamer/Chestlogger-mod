package com.chestlogger.claim;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.index.IndexQueryFilter;
import com.chestlogger.query.QueryEngine;
import com.chestlogger.security.TrustManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AntiSnipingGuardTest {

    @TempDir
    Path tempDir;

    private TrustManager trustManager;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private final String dim = "minecraft:overworld";
    private final long pos = BlockPosUtil.pack(100, 64, -200);

    @BeforeEach
    void setUp() {
        trustManager = new TrustManager(tempDir.resolve("trust.json"));
    }

    @Test
    @DisplayName("Admin always passes anti-sniping check")
    void testAdminAlwaysPasses() {
        List<TransactionLogEntry> history = List.of(
                new TransactionLogEntry(1L, System.currentTimeMillis(), UUID.randomUUID(), ActionType.CONTAINER_PLACE, ActorType.PLAYER, alice, "Alice", dim, pos, List.of())
        );

        var result = AntiSnipingGuard.evaluateClaim(history, trustManager, dim, pos, stranger, true);
        assertThat(result.allowed()).isTrue();
    }

    @Test
    @DisplayName("Unclaimed container with no history passes for any player")
    void testNoHistoryPasses() {
        List<TransactionLogEntry> history = List.of();

        var result = AntiSnipingGuard.evaluateClaim(history, trustManager, dim, pos, stranger, false);
        assertThat(result.allowed()).isTrue();
    }

    @Test
    @DisplayName("Original placer can claim their own container")
    void testPlacerPasses() {
        List<TransactionLogEntry> history = List.of(
                new TransactionLogEntry(1L, System.currentTimeMillis(), UUID.randomUUID(), ActionType.CONTAINER_PLACE, ActorType.PLAYER, alice, "Alice", dim, pos, List.of())
        );

        var result = AntiSnipingGuard.evaluateClaim(history, trustManager, dim, pos, alice, false);
        assertThat(result.allowed()).isTrue();
    }

    @Test
    @DisplayName("Stranger is blocked from claiming container placed by Alice")
    void testStrangerBlockedFromClaimingPlacerContainer() {
        List<TransactionLogEntry> history = List.of(
                new TransactionLogEntry(1L, System.currentTimeMillis(), UUID.randomUUID(), ActionType.CONTAINER_PLACE, ActorType.PLAYER, alice, "Alice", dim, pos, List.of())
        );

        var result = AntiSnipingGuard.evaluateClaim(history, trustManager, dim, pos, stranger, false);
        assertThat(result.allowed()).isFalse();
        assertThat(result.primaryOwnerUuid()).isEqualTo(alice);
        assertThat(result.primaryOwnerName()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("Trusted friend Bob can claim container placed by Alice")
    void testTrustedFriendCanClaim() {
        trustManager.trust(alice, bob);
        List<TransactionLogEntry> history = List.of(
                new TransactionLogEntry(1L, System.currentTimeMillis(), UUID.randomUUID(), ActionType.CONTAINER_PLACE, ActorType.PLAYER, alice, "Alice", dim, pos, List.of())
        );

        var result = AntiSnipingGuard.evaluateClaim(history, trustManager, dim, pos, bob, false);
        assertThat(result.allowed()).isTrue();
    }
}
