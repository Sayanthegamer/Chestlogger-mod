package com.chestlogger.security;

import com.chestlogger.alert.AlertConfig;
import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SmartTheftEvaluatorTest {

    private TrustManager trustManager;
    private RaidVelocityTracker raidTracker;
    private AlertConfig alertConfig;
    private SmartTheftEvaluator evaluator;

    private UUID aliceOwner;
    private UUID bobGriefer;
    private UUID charlieFriend;

    @BeforeEach
    void setUp() {
        trustManager = new TrustManager();
        raidTracker = new RaidVelocityTracker(300_000L, 3);
        alertConfig = new AlertConfig(
                true,
                "https://discord.com/api/webhooks/test",
                "GuardBot",
                "",
                32,
                Set.of("minecraft:diamond", "minecraft:netherite_ingot", "minecraft:elytra"),
                true,
                true,
                30
        );
        evaluator = new SmartTheftEvaluator(trustManager, alertConfig, raidTracker);

        aliceOwner = UUID.randomUUID();
        bobGriefer = UUID.randomUUID();
        charlieFriend = UUID.randomUUID();
    }

    private TransactionLogEntry createExtractionEntry(UUID actorUuid, String actorName, long packedPos, long timestampMs, String itemId, int count) {
        return new TransactionLogEntry(
                100L,
                timestampMs,
                UUID.randomUUID(),
                ActionType.SHIFT_CLICK_EXTRACT,
                ActorType.PLAYER,
                actorUuid,
                actorName,
                "minecraft:overworld",
                packedPos,
                List.of(new SlotDelta(0, itemId, -count, count, 0, 0L))
        );
    }

    @Test
    @DisplayName("Offline theft: theft from an offline owner is classified as OFFLINE_THEFT")
    void testOfflineTheftClassification() {
        long pos = BlockPosUtil.pack(100, 64, 100);
        TransactionLogEntry entry = createExtractionEntry(bobGriefer, "BobGriefer", pos, 1_000_000L, "minecraft:diamond", 32);

        OwnerPresenceState offlineState = OwnerPresenceState.offline();
        Optional<SecurityIncident> result = evaluator.evaluate(entry, aliceOwner, "Alice", offlineState);

        assertThat(result).isPresent();
        SecurityIncident incident = result.get();
        assertThat(incident.classification()).isEqualTo(IncidentClassification.OFFLINE_THEFT);
        assertThat(incident.isTheft()).isTrue();
        assertThat(incident.isMitigated()).isFalse();
        assertThat(incident.isRaidBurst()).isFalse();
        assertThat(incident.actorUuid()).isEqualTo(bobGriefer);
        assertThat(incident.ownerUuid()).isEqualTo(aliceOwner);
        assertThat(incident.itemId()).isEqualTo("minecraft:diamond");
        assertThat(incident.deltaQuantity()).isEqualTo(-32);
        assertThat(incident.summary()).contains("Offline theft detected");
        assertThat(incident.summary()).contains("BobGriefer");
        assertThat(incident.summary()).contains("Alice");
    }

    @Test
    @DisplayName("Absent owner theft: theft from an online owner far away (>24 blocks) is classified as ABSENT_OWNER_THEFT")
    void testAbsentOwnerTheftClassification() {
        long pos = BlockPosUtil.pack(100, 64, 100);
        TransactionLogEntry entry = createExtractionEntry(bobGriefer, "BobGriefer", pos, 1_000_000L, "minecraft:elytra", 1);

        // Alice is online, but 120 blocks away
        OwnerPresenceState absentState = OwnerPresenceState.online(120.0);
        assertThat(absentState.isNearby()).isFalse();

        Optional<SecurityIncident> result = evaluator.evaluate(entry, aliceOwner, "Alice", absentState);

        assertThat(result).isPresent();
        SecurityIncident incident = result.get();
        assertThat(incident.classification()).isEqualTo(IncidentClassification.ABSENT_OWNER_THEFT);
        assertThat(incident.isTheft()).isTrue();
        assertThat(incident.isMitigated()).isFalse();
        assertThat(incident.isRaidBurst()).isFalse();
        assertThat(incident.itemId()).isEqualTo("minecraft:elytra");
        assertThat(incident.deltaQuantity()).isEqualTo(-1);
        assertThat(incident.summary()).contains("Absent owner theft detected");
        assertThat(incident.summary()).contains("120.0 blocks away");
    }

    @Test
    @DisplayName("Consensual proximity: owner nearby (<=24 blocks) mitigates theft to CONSENSUAL_PROXIMITY")
    void testConsensualProximityMitigation() {
        long pos = BlockPosUtil.pack(100, 64, 100);
        TransactionLogEntry entry = createExtractionEntry(bobGriefer, "BobGriefer", pos, 1_000_000L, "minecraft:diamond", 10);

        // Alice is standing right next to the chest (5.2 blocks away)
        OwnerPresenceState nearbyState = OwnerPresenceState.online(5.2);
        assertThat(nearbyState.isNearby()).isTrue();

        Optional<SecurityIncident> result = evaluator.evaluate(entry, aliceOwner, "Alice", nearbyState);

        assertThat(result).isPresent();
        SecurityIncident incident = result.get();
        assertThat(incident.classification()).isEqualTo(IncidentClassification.CONSENSUAL_PROXIMITY);
        assertThat(incident.isMitigated()).isTrue();
        assertThat(incident.isTheft()).isFalse();
        assertThat(incident.summary()).contains("Consensual proximity interaction");
        assertThat(incident.summary()).contains("5.2 blocks away");
    }

    @Test
    @DisplayName("Critical raid: 3+ distinct containers accessed within 300s window triggers CRITICAL_RAID")
    void testCriticalRaidBurst() {
        long pos1 = BlockPosUtil.pack(10, 64, 10);
        long pos2 = BlockPosUtil.pack(20, 64, 20);
        long pos3 = BlockPosUtil.pack(30, 64, 30);

        long t0 = 1_000_000L;
        long t1 = 1_010_000L;
        long t2 = 1_020_000L;

        OwnerPresenceState offline = OwnerPresenceState.offline();

        // 1st chest access -> OFFLINE_THEFT
        TransactionLogEntry entry1 = createExtractionEntry(bobGriefer, "BobGriefer", pos1, t0, "minecraft:diamond", 10);
        Optional<SecurityIncident> inc1 = evaluator.evaluate(entry1, aliceOwner, "Alice", offline);
        assertThat(inc1).isPresent();
        assertThat(inc1.get().classification()).isEqualTo(IncidentClassification.OFFLINE_THEFT);

        // 2nd chest access -> OFFLINE_THEFT
        TransactionLogEntry entry2 = createExtractionEntry(bobGriefer, "BobGriefer", pos2, t1, "minecraft:diamond", 10);
        Optional<SecurityIncident> inc2 = evaluator.evaluate(entry2, aliceOwner, "Alice", offline);
        assertThat(inc2).isPresent();
        assertThat(inc2.get().classification()).isEqualTo(IncidentClassification.OFFLINE_THEFT);

        // 3rd chest access -> CRITICAL_RAID!
        TransactionLogEntry entry3 = createExtractionEntry(bobGriefer, "BobGriefer", pos3, t2, "minecraft:netherite_ingot", 5);
        Optional<SecurityIncident> inc3 = evaluator.evaluate(entry3, aliceOwner, "Alice", offline);
        assertThat(inc3).isPresent();
        SecurityIncident raidIncident = inc3.get();
        assertThat(raidIncident.classification()).isEqualTo(IncidentClassification.CRITICAL_RAID);
        assertThat(raidIncident.isRaidBurst()).isTrue();
        assertThat(raidIncident.isTheft()).isTrue();
        assertThat(raidIncident.itemId()).isEqualTo("minecraft:netherite_ingot");
        assertThat(raidIncident.summary()).contains("Critical raid burst detected");
    }

    @Test
    @DisplayName("Trusted teammate exemption: trusted player access yields empty evaluation")
    void testTrustedTeammateExemption() {
        // Alice trusts Charlie
        trustManager.trust(aliceOwner, charlieFriend);
        assertThat(trustManager.isTrusted(aliceOwner, charlieFriend)).isTrue();

        long pos = BlockPosUtil.pack(100, 64, 100);
        TransactionLogEntry entry = createExtractionEntry(charlieFriend, "Charlie", pos, 1_000_000L, "minecraft:diamond", 64);

        // Even though Alice is offline, Charlie is trusted, so no theft incident is produced
        Optional<SecurityIncident> result = evaluator.evaluate(entry, aliceOwner, "Alice", OwnerPresenceState.offline());
        assertThat(result).isEmpty();

        // Classify method returns INFO
        IncidentClassification classification = evaluator.classify(entry, aliceOwner, OwnerPresenceState.offline());
        assertThat(classification).isEqualTo(IncidentClassification.INFO);
    }

    @Test
    @DisplayName("Self access exemption: container owner extracting their own items yields empty evaluation")
    void testSelfAccessExemption() {
        long pos = BlockPosUtil.pack(100, 64, 100);
        TransactionLogEntry entry = createExtractionEntry(aliceOwner, "Alice", pos, 1_000_000L, "minecraft:netherite_ingot", 10);

        Optional<SecurityIncident> result = evaluator.evaluate(entry, aliceOwner, "Alice", OwnerPresenceState.online(0.0));
        assertThat(result).isEmpty();

        IncidentClassification classification = evaluator.classify(entry, aliceOwner, OwnerPresenceState.online(0.0));
        assertThat(classification).isEqualTo(IncidentClassification.INFO);
    }

    @Test
    @DisplayName("Benign deposits and non-extraction actions do not produce security incidents")
    void testBenignDepositExemption() {
        long pos = BlockPosUtil.pack(100, 64, 100);

        // Deposit: positive delta quantity
        TransactionLogEntry depositEntry = new TransactionLogEntry(
                101L,
                1_000_000L,
                UUID.randomUUID(),
                ActionType.PLACE,
                ActorType.PLAYER,
                bobGriefer,
                "Bob",
                "minecraft:overworld",
                pos,
                List.of(new SlotDelta(0, "minecraft:dirt", 64, 0, 64, 0L))
        );

        Optional<SecurityIncident> result = evaluator.evaluate(depositEntry, aliceOwner, "Alice", OwnerPresenceState.offline());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Container break: breaking someone else's container triggers security incident")
    void testContainerBreakEvaluation() {
        long pos = BlockPosUtil.pack(100, 64, 100);

        TransactionLogEntry breakEntry = new TransactionLogEntry(
                102L,
                1_000_000L,
                UUID.randomUUID(),
                ActionType.CONTAINER_BREAK,
                ActorType.PLAYER,
                bobGriefer,
                "BobGriefer",
                "minecraft:overworld",
                pos,
                List.of(new SlotDelta(0, "minecraft:diamond", -10, 10, 0, 0L))
        );

        Optional<SecurityIncident> result = evaluator.evaluate(breakEntry, aliceOwner, "Alice", OwnerPresenceState.offline());
        assertThat(result).isPresent();
        SecurityIncident incident = result.get();
        assertThat(incident.classification()).isEqualTo(IncidentClassification.OFFLINE_THEFT);
        assertThat(incident.itemId()).isEqualTo("minecraft:diamond");
    }

    @Test
    @DisplayName("Valuable item extraction is prioritized over non-valuable items in incident report")
    void testValuableItemPriorityExtraction() {
        long pos = BlockPosUtil.pack(100, 64, 100);

        // Player extracts 64 cobblestone and 1 netherite ingot
        TransactionLogEntry mixedEntry = new TransactionLogEntry(
                103L,
                1_000_000L,
                UUID.randomUUID(),
                ActionType.SHIFT_CLICK_EXTRACT,
                ActorType.PLAYER,
                bobGriefer,
                "BobGriefer",
                "minecraft:overworld",
                pos,
                List.of(
                        new SlotDelta(0, "minecraft:cobblestone", -64, 64, 0, 0L),
                        new SlotDelta(1, "minecraft:netherite_ingot", -1, 1, 0, 0L)
                )
        );

        Optional<SecurityIncident> result = evaluator.evaluate(mixedEntry, aliceOwner, "Alice", OwnerPresenceState.offline());
        assertThat(result).isPresent();
        SecurityIncident incident = result.get();
        assertThat(incident.itemId()).isEqualTo("minecraft:netherite_ingot");
        assertThat(incident.deltaQuantity()).isEqualTo(-1);
    }

    @Test
    @DisplayName("Unclaimed natural container: looting unowned world-gen chest is classified as UNCLAIMED_NATURAL and not alert worthy")
    void testUnclaimedNaturalContainerSuppression() {
        long pos = BlockPosUtil.pack(50, 64, 50);
        TransactionLogEntry dungeonChestEntry = createExtractionEntry(bobGriefer, "BobExplorer", pos, 1_000_000L, "minecraft:diamond", 8);

        // ownerUuid is null (world-gen container)
        IncidentClassification classification = evaluator.classify(dungeonChestEntry, null, OwnerPresenceState.offline());
        assertThat(classification).isEqualTo(IncidentClassification.UNCLAIMED_NATURAL);
        assertThat(classification.isTheft()).isFalse();
        assertThat(classification.isAlertWorthy()).isFalse();

        Optional<SecurityIncident> result = evaluator.evaluate(dungeonChestEntry, null, null, OwnerPresenceState.offline());
        // Either empty or contains an UNCLAIMED_NATURAL non-alert incident
        if (result.isPresent()) {
            SecurityIncident inc = result.get();
            assertThat(inc.classification()).isEqualTo(IncidentClassification.UNCLAIMED_NATURAL);
            assertThat(inc.isTheft()).isFalse();
            assertThat(inc.classification().isAlertWorthy()).isFalse();
        }
    }

    @Test
    @DisplayName("Unclaimed natural container does not trigger CRITICAL_RAID across multiple dungeon chests")
    void testUnclaimedContainersDoNotTriggerRaidBurst() {
        long dungeon1 = BlockPosUtil.pack(100, 20, 100);
        long dungeon2 = BlockPosUtil.pack(200, 20, 200);
        long dungeon3 = BlockPosUtil.pack(300, 20, 300);

        long t0 = 1_000_000L;
        long t1 = 1_010_000L;
        long t2 = 1_020_000L;

        // Bob loots 3 dungeon chests in rapid succession
        TransactionLogEntry e1 = createExtractionEntry(bobGriefer, "Bob", dungeon1, t0, "minecraft:golden_apple", 2);
        TransactionLogEntry e2 = createExtractionEntry(bobGriefer, "Bob", dungeon2, t1, "minecraft:diamond", 5);
        TransactionLogEntry e3 = createExtractionEntry(bobGriefer, "Bob", dungeon3, t2, "minecraft:enchanted_golden_apple", 1);

        evaluator.evaluate(e1, null, null, OwnerPresenceState.offline());
        evaluator.evaluate(e2, null, null, OwnerPresenceState.offline());
        Optional<SecurityIncident> inc3 = evaluator.evaluate(e3, null, null, OwnerPresenceState.offline());

        // Should NOT trigger CRITICAL_RAID
        if (inc3.isPresent()) {
            assertThat(inc3.get().classification()).isNotEqualTo(IncidentClassification.CRITICAL_RAID);
            assertThat(inc3.get().isRaidBurst()).isFalse();
        }
        assertThat(raidTracker.getDistinctContainerCount(bobGriefer, t2)).isEqualTo(0);
    }
}

