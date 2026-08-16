package com.chestlogger;

import com.chestlogger.container.ContainerTracker;
import com.chestlogger.event.TransactionEventQueue;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChestLoggerMod implements ModInitializer {
    public static final String MOD_ID = "chestlogger";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static TransactionEventQueue eventQueue;
    private static ContainerTracker tracker;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing ChestLogger for Minecraft 26.2...");
        eventQueue = new TransactionEventQueue(65536);
        tracker = new ContainerTracker(eventQueue);
    }

    public static ContainerTracker getTracker() {
        if (tracker == null) {
            eventQueue = new TransactionEventQueue(65536);
            tracker = new ContainerTracker(eventQueue);
        }
        return tracker;
    }

    public static TransactionEventQueue getEventQueue() {
        if (eventQueue == null) {
            getTracker();
        }
        return eventQueue;
    }
}
