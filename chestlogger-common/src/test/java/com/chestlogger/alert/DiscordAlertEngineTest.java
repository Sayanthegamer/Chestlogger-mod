package com.chestlogger.alert;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordAlertEngineTest {

    @Test
    @DisplayName("AlertConfig parses JSON and provides sensible defaults")
    void testAlertConfigDefaultsAndParsing() {
        AlertConfig config = AlertConfig.defaults();
        assertThat(config.enabled()).isFalse();
        assertThat(config.botUsername()).isEqualTo("ChestLogger Alerts");
        assertThat(config.valuableItems()).contains("minecraft:diamond", "minecraft:netherite_ingot");
        assertThat(config.quantityThreshold()).isEqualTo(64);
        assertThat(config.rateLimitPerMinute()).isEqualTo(30);

        String json = """
        {
          "enabled": true,
          "webhookUrl": "https://discord.com/api/webhooks/123/xyz",
          "botUsername": "Custom Guard",
          "quantityThreshold": 32,
          "valuableItems": ["minecraft:emerald", "minecraft:netherite_block"],
          "alertOnContainerBreak": true,
          "rateLimitPerMinute": 20
        }
        """;

        AlertConfig parsed = AlertConfig.fromJson(json);
        assertThat(parsed.enabled()).isTrue();
        assertThat(parsed.webhookUrl()).isEqualTo("https://discord.com/api/webhooks/123/xyz");
        assertThat(parsed.botUsername()).isEqualTo("Custom Guard");
        assertThat(parsed.quantityThreshold()).isEqualTo(32);
        assertThat(parsed.valuableItems()).containsExactlyInAnyOrder("minecraft:emerald", "minecraft:netherite_block");
        assertThat(parsed.rateLimitPerMinute()).isEqualTo(20);
    }

    @Test
    @DisplayName("DiscordAlertDispatcher accurately detects suspicious transactions")
    void testSuspiciousRuleEvaluation() {
        AlertConfig config = new AlertConfig(
                true,
                "https://discord.com/api/webhooks/test",
                "ChestLogger",
                "",
                32,
                Set.of("minecraft:diamond", "minecraft:netherite_ingot"),
                true,
                true,
                30
        );

        UUID playerUuid = UUID.randomUUID();
        long pos = BlockPosUtil.pack(10, 60, -10);

        // 1. Benign deposit of 10 dirt -> Not suspicious
        TransactionLogEntry benignDeposit = new TransactionLogEntry(
                1L, System.currentTimeMillis(), UUID.randomUUID(),
                ActionType.PLACE, ActorType.PLAYER, playerUuid, "Alex", "minecraft:overworld", pos,
                List.of(new SlotDelta(0, "minecraft:dirt", 10, 0, 10, 0L))
        );
        assertThat(DiscordAlertDispatcher.isSuspicious(benignDeposit, config)).isFalse();

        // 2. Theft of 1 valuable netherite ingot -> Suspicious
        TransactionLogEntry valuableTheft = new TransactionLogEntry(
                2L, System.currentTimeMillis(), UUID.randomUUID(),
                ActionType.PICKUP, ActorType.PLAYER, playerUuid, "Griefer", "minecraft:overworld", pos,
                List.of(new SlotDelta(0, "minecraft:netherite_ingot", -1, 1, 0, 0L))
        );
        assertThat(DiscordAlertDispatcher.isSuspicious(valuableTheft, config)).isTrue();

        // 3. Mass withdrawal of 32 non-valuable items -> Suspicious (meets quantity threshold)
        TransactionLogEntry massWithdraw = new TransactionLogEntry(
                3L, System.currentTimeMillis(), UUID.randomUUID(),
                ActionType.SHIFT_CLICK_EXTRACT, ActorType.PLAYER, playerUuid, "Alex", "minecraft:overworld", pos,
                List.of(new SlotDelta(0, "minecraft:gold_ingot", -32, 32, 0, 0L))
        );
        assertThat(DiscordAlertDispatcher.isSuspicious(massWithdraw, config)).isTrue();

        // 4. Container break with contents -> Suspicious
        TransactionLogEntry breakChest = new TransactionLogEntry(
                4L, System.currentTimeMillis(), UUID.randomUUID(),
                ActionType.CONTAINER_BREAK, ActorType.PLAYER, playerUuid, "Griefer", "minecraft:overworld", pos,
                List.of(new SlotDelta(0, "minecraft:iron_ingot", -5, 5, 0, 0L))
        );
        assertThat(DiscordAlertDispatcher.isSuspicious(breakChest, config)).isTrue();
    }

    @Test
    @DisplayName("DiscordEmbedBuilder produces valid Discord webhook JSON")
    void testEmbedJsonFormatting() {
        AlertConfig config = new AlertConfig(
                true,
                "https://discord.com/api/webhooks/test",
                "ChestLogger Alerts",
                "https://example.com/avatar.png",
                64,
                Set.of("minecraft:diamond"),
                true,
                true,
                30
        );

        TransactionLogEntry entry = new TransactionLogEntry(
                100L,
                1723849000000L,
                UUID.randomUUID(),
                ActionType.PICKUP,
                ActorType.PLAYER,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "SuspiciousPlayer",
                "minecraft:overworld",
                BlockPosUtil.pack(100, 64, -200),
                List.of(new SlotDelta(0, "minecraft:diamond", -64, 64, 0, 0L))
        );

        String jsonPayload = DiscordEmbedBuilder.buildWebhookPayload(entry, config);

        assertThat(jsonPayload).contains("\"username\":\"ChestLogger Alerts\"");
        assertThat(jsonPayload).contains("SuspiciousPlayer");
        assertThat(jsonPayload).contains("minecraft:diamond");
        assertThat(jsonPayload).contains("-64");
        assertThat(jsonPayload).contains("100, 64, -200");
    }
}
