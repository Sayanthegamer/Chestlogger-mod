package com.chestlogger.client;

import com.chestlogger.client.gui.ChestLogScreen;
import com.chestlogger.network.ChestLogPagePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Client entrypoint registering packet listeners and GUI handlers.
 */
public class ChestLoggerClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(
                ChestLogPagePayload.TYPE,
                (payload, context) -> {
                    context.client().execute(() -> {
                        Minecraft mc = context.client();
                        if (mc.gui.screen() instanceof ChestLogScreen screen && payload.queryId().equals(screen.getQueryId())) {
                            screen.updatePage(payload);
                        } else {
                            mc.gui.setScreen(new ChestLogScreen(payload));
                        }
                    });
                }
        );
    }
}
