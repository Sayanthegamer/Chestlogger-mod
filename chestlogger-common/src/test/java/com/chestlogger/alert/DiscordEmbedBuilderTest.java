package com.chestlogger.alert;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.security.IncidentClassification;
import com.chestlogger.security.OwnerPresenceState;
import com.chestlogger.security.SecurityIncident;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordEmbedBuilderTest {

    private final AlertConfig config = new AlertConfig(
            true,
            "https://discord.com/api/webhooks/test",
            "ChestLogger Alerts",
            "https://example.com/avatar.png",
            64,
            Set.of("minecraft:diamond", "minecraft:netherite_ingot"),
            true,
            true,
            30
    );

    @Test
    @DisplayName("Critical Raid incident builds embed with crimson color (10038562) and raid burst flag")
    void testCriticalRaidEmbedFormatting() {
        UUID actorUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID ownerUuid = UUID.fromString("00000000-0000-0000-0000-000000000002");
        long packedPos = BlockPosUtil.pack(100, 64, -200);

        SecurityIncident incident = new SecurityIncident(
                1723849000000L,
                42L,
                IncidentClassification.CRITICAL_RAID,
                actorUuid,
                "RaidLeader",
                ownerUuid,
                "BaseOwner",
                OwnerPresenceState.offline(),
                packedPos,
                "minecraft:overworld",
                "minecraft:diamond",
                -128,
                "Critical raid burst detected: RaidLeader extracted 128x minecraft:diamond across multiple containers (Owner: BaseOwner)",
                true
        );

        String json = DiscordEmbedBuilder.buildWebhookPayload(incident, config);

        assertThat(json).contains("\"username\":\"ChestLogger Alerts\"");
        assertThat(json).contains("\"avatar_url\":\"https://example.com/avatar.png\"");
        assertThat(json).contains("\"color\":10038562"); // Crimson Red 0x992D22
        assertThat(json).contains("CRITICAL RAID");
        assertThat(json).contains("RaidLeader");
        assertThat(json).contains(actorUuid.toString());
        assertThat(json).contains("BaseOwner");
        assertThat(json).contains("🔴 Offline");
        assertThat(json).contains("100, 64, -200");
        assertThat(json).contains("minecraft:overworld");
        assertThat(json).contains("-128x");
        assertThat(json).contains("minecraft:diamond");
        assertThat(json).contains("Raid Burst");
        assertThat(json).contains("Seq #42");
    }

    @Test
    @DisplayName("Offline Theft incident formats red color (15158332) and offline presence badge")
    void testOfflineTheftEmbedFormatting() {
        UUID actorUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        long packedPos = BlockPosUtil.pack(-50, 12, 300);

        SecurityIncident incident = new SecurityIncident(
                1723849000000L,
                101L,
                IncidentClassification.OFFLINE_THEFT,
                actorUuid,
                "SneakyPlayer",
                ownerUuid,
                "VictimPlayer",
                OwnerPresenceState.offline(),
                packedPos,
                "minecraft:the_nether",
                "minecraft:netherite_ingot",
                -4,
                "Offline theft detected: SneakyPlayer extracted 4x minecraft:netherite_ingot from offline owner VictimPlayer",
                false
        );

        String json = DiscordEmbedBuilder.buildWebhookPayload(incident, config);

        assertThat(json).contains("\"color\":15158332"); // Crimson Red 0xE74C3C
        assertThat(json).contains("Offline");
        assertThat(json).contains("🔴 Offline");
        assertThat(json).contains("SneakyPlayer");
        assertThat(json).contains("VictimPlayer");
        assertThat(json).contains("-50, 12, 300");
        assertThat(json).contains("minecraft:the_nether");
        assertThat(json).contains("-4x");
        assertThat(json).contains("minecraft:netherite_ingot");
    }

    @Test
    @DisplayName("Absent Owner Theft incident formats orange color (15105570) and absent presence badge with distance")
    void testAbsentOwnerTheftEmbedFormatting() {
        UUID actorUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        long packedPos = BlockPosUtil.pack(0, 70, 0);

        SecurityIncident incident = new SecurityIncident(
                1723849000000L,
                202L,
                IncidentClassification.ABSENT_OWNER_THEFT,
                actorUuid,
                "Intruder",
                ownerUuid,
                "DistantOwner",
                OwnerPresenceState.onlineAbsent(350.5),
                packedPos,
                "minecraft:overworld",
                "minecraft:beacon",
                -1,
                "Absent owner theft detected: Intruder extracted 1x minecraft:beacon while owner DistantOwner is absent (350.5 blocks away)",
                false
        );

        String json = DiscordEmbedBuilder.buildWebhookPayload(incident, config);

        assertThat(json).contains("\"color\":15105570"); // Orange 0xE67E22
        assertThat(json).contains("🟡 Absent (~351m away)");
        assertThat(json).contains("Intruder");
        assertThat(json).contains("DistantOwner");
        assertThat(json).contains("minecraft:beacon");
    }

    @Test
    @DisplayName("Consensual Proximity or Info formats nearby presence badge and gold color (15844367)")
    void testConsensualProximityEmbedFormatting() {
        UUID actorUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        long packedPos = BlockPosUtil.pack(10, 64, 10);

        SecurityIncident incident = new SecurityIncident(
                1723849000000L,
                303L,
                IncidentClassification.CONSENSUAL_PROXIMITY,
                actorUuid,
                "Friend",
                ownerUuid,
                "NearbyOwner",
                OwnerPresenceState.onlineNearby(12.3),
                packedPos,
                "minecraft:overworld",
                "minecraft:diamond",
                -10,
                "Consensual proximity interaction: Friend extracted 10x minecraft:diamond near owner NearbyOwner (12.3 blocks away)",
                false
        );

        String json = DiscordEmbedBuilder.buildWebhookPayload(incident, config);

        assertThat(json).contains("\"color\":15844367"); // Gold 0xF1C40F
        assertThat(json).contains("🟢 Nearby (~12m away)");
        assertThat(json).contains("Friend");
        assertThat(json).contains("NearbyOwner");
    }

    @Test
    @DisplayName("Owner presence formatting helper handles all states gracefully")
    void testOwnerPresenceFormattingHelper() {
        assertThat(DiscordEmbedBuilder.formatOwnerPresence(null)).isEqualTo("🔴 Offline");
        assertThat(DiscordEmbedBuilder.formatOwnerPresence(OwnerPresenceState.offline())).isEqualTo("🔴 Offline");
        assertThat(DiscordEmbedBuilder.formatOwnerPresence(OwnerPresenceState.onlineNearby(5.0))).isEqualTo("🟢 Nearby (~5m away)");
        assertThat(DiscordEmbedBuilder.formatOwnerPresence(OwnerPresenceState.onlineAbsent(250.0))).isEqualTo("🟡 Absent (~250m away)");
        assertThat(DiscordEmbedBuilder.formatOwnerPresence(OwnerPresenceState.onlineAbsent(-1.0))).isEqualTo("🟡 Absent");
    }

    @Test
    @DisplayName("Special characters in player names or summaries are properly JSON-escaped")
    void testSpecialCharacterEscaping() {
        UUID actorUuid = UUID.randomUUID();
        long packedPos = BlockPosUtil.pack(0, 0, 0);

        SecurityIncident incident = new SecurityIncident(
                1723849000000L,
                404L,
                IncidentClassification.OFFLINE_THEFT,
                actorUuid,
                "Player\"With\"Quotes",
                null,
                "Owner\\With\\Backslashes\nNewline",
                OwnerPresenceState.offline(),
                packedPos,
                "minecraft:overworld",
                "minecraft:diamond",
                -1,
                "Line 1\nLine 2 \"quoted\"",
                false
        );

        String json = DiscordEmbedBuilder.buildWebhookPayload(incident, config);
        assertThat(json).contains("Player\\\"With\\\"Quotes");
        assertThat(json).contains("Owner\\\\With\\\\Backslashes\\nNewline");
        assertThat(json).contains("Line 1\\nLine 2 \\\"quoted\\\"");
    }

    @Test
    @DisplayName("Legacy TransactionLogEntry overload maintains backwards compatibility")
    void testLegacyTransactionLogEntryOverload() {
        TransactionLogEntry entry = new TransactionLogEntry(
                500L,
                1723849000000L,
                UUID.randomUUID(),
                ActionType.PICKUP,
                ActorType.PLAYER,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "LegacyPlayer",
                "minecraft:overworld",
                BlockPosUtil.pack(10, 20, 30),
                List.of(new SlotDelta(0, "minecraft:diamond", -10, 10, 0, 0L))
        );

        String json = DiscordEmbedBuilder.buildWebhookPayload(entry, config);
        assertThat(json).contains("LegacyPlayer");
        assertThat(json).contains("minecraft:diamond");
        assertThat(json).contains("10, 20, 30");
        assertThat(json).contains("Seq #500");
    }
}
