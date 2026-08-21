package com.chestlogger.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Server-to-client payload carrying the live server configuration for the in-game config screen.
 */
public record ChestLogConfigPayload(
        boolean alertEnabled,
        String discordWebhookUrl,
        String botUsername,
        String avatarUrl,
        int alertCooldownSeconds,
        boolean actionBarNoticeEnabled,
        boolean inGameChatAlertEnabled,
        int maxOwnerAlertDistance,
        List<String> trackedItems,
        boolean webEnabled,
        String webHost,
        int webPort,
        String secretToken
) implements CustomPacketPayload {

    public static final Type<ChestLogConfigPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("chestlogger", "config_payload"));

    public static final StreamCodec<FriendlyByteBuf, ChestLogConfigPayload> STREAM_CODEC = CustomPacketPayload.codec(
            ChestLogConfigPayload::write,
            ChestLogConfigPayload::read
    );

    public static ChestLogConfigPayload createDefault() {
        return new ChestLogConfigPayload(
                true,
                "",
                "ChestLogger Security Bot",
                "",
                30,
                true,
                true,
                32,
                List.of("minecraft:diamond", "minecraft:netherite_ingot", "minecraft:elytra", "minecraft:beacon"),
                true,
                "127.0.0.1",
                8080,
                ""
        );
    }

    public ChestLogConfigPayload {
        discordWebhookUrl = discordWebhookUrl != null ? discordWebhookUrl : "";
        botUsername = botUsername != null ? botUsername : "";
        avatarUrl = avatarUrl != null ? avatarUrl : "";
        trackedItems = trackedItems != null ? List.copyOf(trackedItems) : List.of();
        webHost = webHost != null ? webHost : "127.0.0.1";
        secretToken = secretToken != null ? secretToken : "";
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(alertEnabled);
        buf.writeUtf(discordWebhookUrl);
        buf.writeUtf(botUsername);
        buf.writeUtf(avatarUrl);
        buf.writeVarInt(alertCooldownSeconds);
        buf.writeBoolean(actionBarNoticeEnabled);
        buf.writeBoolean(inGameChatAlertEnabled);
        buf.writeVarInt(maxOwnerAlertDistance);

        buf.writeVarInt(trackedItems.size());
        for (String item : trackedItems) {
            buf.writeUtf(item);
        }

        buf.writeBoolean(webEnabled);
        buf.writeUtf(webHost);
        buf.writeVarInt(webPort);
        buf.writeUtf(secretToken);
    }

    public static ChestLogConfigPayload read(FriendlyByteBuf buf) {
        boolean alertEnabled = buf.readBoolean();
        String discordWebhookUrl = buf.readUtf();
        String botUsername = buf.readUtf();
        String avatarUrl = buf.readUtf();
        int alertCooldownSeconds = buf.readVarInt();
        boolean actionBarNoticeEnabled = buf.readBoolean();
        boolean inGameChatAlertEnabled = buf.readBoolean();
        int maxOwnerAlertDistance = buf.readVarInt();

        int trackedSize = buf.readVarInt();
        List<String> trackedItems = new ArrayList<>(trackedSize);
        for (int i = 0; i < trackedSize; i++) {
            trackedItems.add(buf.readUtf());
        }

        boolean webEnabled = buf.readBoolean();
        String webHost = buf.readUtf();
        int webPort = buf.readVarInt();
        String secretToken = buf.readUtf();

        return new ChestLogConfigPayload(
                alertEnabled,
                discordWebhookUrl,
                botUsername,
                avatarUrl,
                alertCooldownSeconds,
                actionBarNoticeEnabled,
                inGameChatAlertEnabled,
                maxOwnerAlertDistance,
                trackedItems,
                webEnabled,
                webHost,
                webPort,
                secretToken
        );
    }
}
