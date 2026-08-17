package com.chestlogger.paper;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionEventQueue;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.util.ThreadGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class PaperThreadSafetyTest {

    private Thread simulatedPaperServerThread;

    @BeforeEach
    void setUp() {
        simulatedPaperServerThread = Thread.currentThread();
        ThreadGuard.setServerThreadChecker(() -> Thread.currentThread() == simulatedPaperServerThread);
    }

    @AfterEach
    void tearDown() {
        ThreadGuard.reset();
    }

    @Test
    @DisplayName("Paper event handler enqueue must NEVER trigger ThreadGuard violation on server thread")
    void testPaperEventInterceptionZeroThreadGuardViolation() {
        TransactionEventQueue queue = new TransactionEventQueue(1000);

        // Simulate main thread event interception
        assertThatCode(() -> {
            TransactionLogEntry entry = PaperInventoryDeltaCalculator.buildEntry(
                    1L,
                    UUID.randomUUID(),
                    ActionType.PICKUP,
                    ActorType.PLAYER,
                    UUID.randomUUID(),
                    "Steve",
                    "minecraft:overworld",
                    12345L,
                    List.of(new SlotDelta(0, "minecraft:diamond", -1, 64, 63, 0L))
            );

            // Ring buffer offer on server thread
            boolean offered = queue.offer(entry);
            assertThat(offered).isTrue();
            assertThat(queue.getDepth()).isEqualTo(1);
        }).doesNotThrowAnyException();
    }
}
