package com.chestlogger.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChestLogConfigPayloadTest {

    @Test
    @DisplayName("Should encode and decode ChestLogConfigPayload round-trip accurately")
    void testChestLogConfigPayloadRoundTrip() {
        ChestLogConfigPayload original = new ChestLogConfigPayload(
                true,
                "https://discord.com/api/webhooks/123/abc",
                "ChestAlertBot",
                "https://example.com/avatar.png",
                30,
                true,
                false,
                64,
                List.of("minecraft:diamond", "minecraft:netherite_ingot", "minecraft:elytra"),
                true,
                "127.0.0.1",
                8080,
                "secret-token-12345"
        );

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ChestLogConfigPayload.STREAM_CODEC.encode(buf, original);

        ChestLogConfigPayload decoded = ChestLogConfigPayload.STREAM_CODEC.decode(buf);

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.alertEnabled()).isTrue();
        assertThat(decoded.discordWebhookUrl()).isEqualTo("https://discord.com/api/webhooks/123/abc");
        assertThat(decoded.botUsername()).isEqualTo("ChestAlertBot");
        assertThat(decoded.avatarUrl()).isEqualTo("https://example.com/avatar.png");
        assertThat(decoded.alertCooldownSeconds()).isEqualTo(30);
        assertThat(decoded.actionBarNoticeEnabled()).isTrue();
        assertThat(decoded.inGameChatAlertEnabled()).isFalse();
        assertThat(decoded.maxOwnerAlertDistance()).isEqualTo(64);
        assertThat(decoded.trackedItems()).containsExactly("minecraft:diamond", "minecraft:netherite_ingot", "minecraft:elytra");
        assertThat(decoded.webEnabled()).isTrue();
        assertThat(decoded.webHost()).isEqualTo("127.0.0.1");
        assertThat(decoded.webPort()).isEqualTo(8080);
        assertThat(decoded.secretToken()).isEqualTo("secret-token-12345");
    }

    @Test
    @DisplayName("Should encode and decode ChestLogConfigUpdatePayload round-trip accurately")
    void testChestLogConfigUpdatePayloadRoundTrip() {
        ChestLogConfigUpdatePayload original = new ChestLogConfigUpdatePayload(
                false,
                "https://discord.com/api/webhooks/999/xyz",
                "UpdatedBot",
                "https://example.com/updated_avatar.png",
                45,
                false,
                true,
                128,
                List.of("minecraft:ancient_debris", "minecraft:beacon"),
                false,
                "0.0.0.0",
                9090
        );

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ChestLogConfigUpdatePayload.STREAM_CODEC.encode(buf, original);

        ChestLogConfigUpdatePayload decoded = ChestLogConfigUpdatePayload.STREAM_CODEC.decode(buf);

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.alertEnabled()).isFalse();
        assertThat(decoded.discordWebhookUrl()).isEqualTo("https://discord.com/api/webhooks/999/xyz");
        assertThat(decoded.botUsername()).isEqualTo("UpdatedBot");
        assertThat(decoded.avatarUrl()).isEqualTo("https://example.com/updated_avatar.png");
        assertThat(decoded.alertCooldownSeconds()).isEqualTo(45);
        assertThat(decoded.actionBarNoticeEnabled()).isFalse();
        assertThat(decoded.inGameChatAlertEnabled()).isTrue();
        assertThat(decoded.maxOwnerAlertDistance()).isEqualTo(128);
        assertThat(decoded.trackedItems()).containsExactly("minecraft:ancient_debris", "minecraft:beacon");
        assertThat(decoded.webEnabled()).isFalse();
        assertThat(decoded.webHost()).isEqualTo("0.0.0.0");
        assertThat(decoded.webPort()).isEqualTo(9090);
    }
}
