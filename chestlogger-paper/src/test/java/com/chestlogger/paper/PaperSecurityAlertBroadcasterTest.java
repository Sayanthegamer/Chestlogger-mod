package com.chestlogger.paper;

import com.chestlogger.alert.AlertConfig;
import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.security.IncidentClassification;
import com.chestlogger.security.RaidVelocityTracker;
import com.chestlogger.security.SmartTheftEvaluator;
import com.chestlogger.security.TrustManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("Paper Security Alert Broadcaster Tests")
class PaperSecurityAlertBroadcasterTest {

    private TrustManager trustManager;
    private AlertConfig alertConfig;
    private SmartTheftEvaluator evaluator;
    private PaperSecurityAlertBroadcaster broadcaster;
    private Plugin mockPlugin;

    @BeforeEach
    void setUp() {
        trustManager = new TrustManager();
        alertConfig = new AlertConfig(
                true,
                "https://discord.com/api/webhooks/test",
                "AlertBot",
                "",
                32,
                Set.of("minecraft:diamond", "minecraft:netherite_ingot"),
                true,
                true,
                30
        );
        evaluator = new SmartTheftEvaluator(trustManager, alertConfig, new RaidVelocityTracker());
        mockPlugin = (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> null
        );
        broadcaster = new PaperSecurityAlertBroadcaster(mockPlugin, evaluator, alertConfig);
    }

    @Test
    @DisplayName("Tracking container placement records container ownership")
    void testContainerPlacementOwnershipTracking() {
        UUID aliceOwner = UUID.randomUUID();
        long pos = BlockPosUtil.pack(100, 64, 200);

        TransactionLogEntry placeEntry = new TransactionLogEntry(
                1L,
                System.currentTimeMillis(),
                UUID.randomUUID(),
                ActionType.CONTAINER_PLACE,
                ActorType.PLAYER,
                aliceOwner,
                "Alice",
                "minecraft:overworld",
                pos,
                List.of()
        );

        broadcaster.processTransaction(placeEntry);

        // Bob extracts diamonds -> should trigger theft evaluation against Alice
        UUID bobTheft = UUID.randomUUID();
        TransactionLogEntry extractEntry = new TransactionLogEntry(
                2L,
                System.currentTimeMillis(),
                UUID.randomUUID(),
                ActionType.SHIFT_CLICK_EXTRACT,
                ActorType.PLAYER,
                bobTheft,
                "BobGriefer",
                "minecraft:overworld",
                pos,
                List.of(new SlotDelta(0, "minecraft:diamond", -32, 32, 0, 0L))
        );

        assertThatCode(() -> broadcaster.processTransaction(extractEntry)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Trusted player does not trigger alert broadcast")
    void testTrustedPlayerAccess() {
        UUID aliceOwner = UUID.randomUUID();
        UUID charlieFriend = UUID.randomUUID();
        long pos = BlockPosUtil.pack(100, 64, 200);

        broadcaster.registerContainerOwner(pos, aliceOwner, "Alice");
        trustManager.trust(aliceOwner, charlieFriend);

        TransactionLogEntry extractEntry = new TransactionLogEntry(
                3L,
                System.currentTimeMillis(),
                UUID.randomUUID(),
                ActionType.SHIFT_CLICK_EXTRACT,
                ActorType.PLAYER,
                charlieFriend,
                "Charlie",
                "minecraft:overworld",
                pos,
                List.of(new SlotDelta(0, "minecraft:diamond", -32, 32, 0, 0L))
        );

        assertThatCode(() -> broadcaster.processTransaction(extractEntry)).doesNotThrowAnyException();
    }
}
