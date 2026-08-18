package com.chestlogger.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-to-server payload transmitting user modifications made in the in-game config screen.
 */
public record ChestLogConfigUpdatePayload(
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
        int webPort
) implements CustomPacketPayload {

    public static final Type<ChestLogConfigUpdatePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("chestlogger", "config_update_payload"));

    public static final StreamCodec<FriendlyByteBuf, ChestLogConfigUpdatePayload> STREAM_CODEC = CustomPacketPayload.codec(
            ChestLogConfigUpdatePayload::write,
            ChestLogConfigUpdatePayload::read
    );

    public ChestLogConfigUpdatePayload {
        discordWebhookUrl = discordWebhookUrl != null ? discordWebhookUrl : "";
        botUsername = botUsername != null ? botUsername : "";
        avatarUrl = avatarUrl != null ? avatarUrl : "";
        trackedItems = trackedItems != null ? List.copyOf(trackedItems) : List.of();
        webHost = webHost != null ? webHost : "127.0.0.1";
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
    }

    public static ChestLogConfigUpdatePayload read(FriendlyByteBuf buf) {
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

        return new ChestLogConfigUpdatePayload(
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
                webPort
        );
    }
}
