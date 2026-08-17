package com.chestlogger.util;

import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;
import com.chestlogger.index.PersistentIndexManager;
import com.chestlogger.storage.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates that storage engine, compressor, and index persistence operations
 * immediately throw IllegalStateException when called from the registered server thread.
 */
public class ThreadGuardTest {

    private Thread fakeServerThread;

    @BeforeEach
    void setUp() {
        fakeServerThread = Thread.currentThread();
        ThreadGuard.setServerThreadChecker(() -> Thread.currentThread() == fakeServerThread);
    }

    @AfterEach
    void tearDown() {
        ThreadGuard.reset();
    }

    @Test
    void testThreadGuardThrowsOnServerThreadForStorageIO(@TempDir Path tempDir) {
        File file = tempDir.resolve("test.clog").toFile();
        StringTableDictionary dict = new StringTableDictionary();

        // 1. BinaryLogWriter constructor / writeHeader should throw on server thread
        assertThatThrownBy(() -> new BinaryLogWriter(file, 1L, dict))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRITICAL THREAD SAFETY VIOLATION");

        // 2. LZ4BlockCompressor should throw on server thread
        LZ4BlockCompressor compressor = new LZ4BlockCompressor();
        assertThatThrownBy(() -> compressor.compress(new byte[]{1, 2, 3}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRITICAL THREAD SAFETY VIOLATION");

        assertThatThrownBy(() -> compressor.decompress(new byte[]{1, 2, 3}, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRITICAL THREAD SAFETY VIOLATION");

        // 3. PersistentIndexManager should throw on server thread
        PersistentIndexManager indexManager = new PersistentIndexManager(tempDir.toFile());
        assertThatThrownBy(indexManager::saveCheckpoint)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRITICAL THREAD SAFETY VIOLATION");

        assertThatThrownBy(indexManager::loadCheckpoint)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRITICAL THREAD SAFETY VIOLATION");
    }

    @Test
    void testThreadGuardPassesOnWorkerThread(@TempDir Path tempDir) throws Exception {
        Thread workerThread = new Thread(() -> {
            assertThatCode(() -> {
                LZ4BlockCompressor compressor = new LZ4BlockCompressor();
                byte[] raw = "hello world test payload".getBytes();
                byte[] compressed = compressor.compress(raw);
                byte[] restored = compressor.decompress(compressed, raw.length);
                org.assertj.core.api.Assertions.assertThat(restored).isEqualTo(raw);
            }).doesNotThrowAnyException();
        });

        workerThread.start();
        workerThread.join();
    }
}
