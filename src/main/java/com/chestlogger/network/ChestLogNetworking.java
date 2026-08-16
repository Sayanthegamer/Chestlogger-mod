package com.chestlogger.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Handles payload type registrations for Fabric Networking.
 */
public final class ChestLogNetworking {
    private ChestLogNetworking() {}

    public static void init() {
        // Register clientbound payload (Server -> Client)
        PayloadTypeRegistry.clientboundPlay().register(
                ChestLogPagePayload.TYPE,
                ChestLogPagePayload.STREAM_CODEC
        );

        // Register serverbound payload (Client -> Server)
        PayloadTypeRegistry.serverboundPlay().register(
                ChestLogPageRequestPayload.TYPE,
                ChestLogPageRequestPayload.STREAM_CODEC
        );
    }
}
