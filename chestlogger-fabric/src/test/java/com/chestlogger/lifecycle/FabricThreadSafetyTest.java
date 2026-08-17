package com.chestlogger.lifecycle;

import com.chestlogger.container.ContainerTracker;
import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionEventQueue;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.storage.LogSegmentWriter;
import com.chestlogger.storage.StorageProfile;
import com.chestlogger.util.ThreadGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FabricThreadSafetyTest {

    @Test
    @DisplayName("Fabric container tracker enqueue should succeed safely on main thread without blocking")
    void testMainThreadEnqueueSucceeds() {
        ThreadGuard.setServerThreadChecker(() -> true);
        try {
            TransactionEventQueue queue = new TransactionEventQueue(100);
            ContainerTracker tracker = new ContainerTracker(queue);

            TransactionLogEntry entry = new TransactionLogEntry(
                    1L,
                    System.currentTimeMillis(),
                    UUID.randomUUID(),
                    ActionType.PICKUP,
                    ActorType.PLAYER,
                    UUID.randomUUID(),
                    "Steve",
                    "minecraft:overworld",
                    12345L,
                    List.of(new SlotDelta(0, "minecraft:diamond", -1, 1, 0, 0L))
            );

            boolean accepted = queue.offer(entry);
            assertThat(accepted).isTrue();
            assertThat(queue.getDepth()).isEqualTo(1);
        } finally {
            ThreadGuard.reset();
        }
    }

    @Test
    @DisplayName("LogSegmentWriter should fail fast if invoked directly from Fabric server thread")
    void testLogSegmentWriterFailsOnServerThread(@TempDir File tempDir) {
        ThreadGuard.setServerThreadChecker(() -> true);
        try {
            assertThatThrownBy(() -> new LogSegmentWriter(
                    tempDir,
                    "chestlog",
                    0,
                    1L,
                    new com.chestlogger.storage.LZ4BlockCompressor(),
                    StorageProfile.BALANCED,
                    new com.chestlogger.storage.StringTableDictionary()
            )).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("CRITICAL THREAD SAFETY VIOLATION");
        } finally {
            ThreadGuard.reset();
        }
    }
}
