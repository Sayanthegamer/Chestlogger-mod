package com.chestlogger.security;

import com.chestlogger.alert.AlertConfig;
import com.chestlogger.event.ActionType;
import com.chestlogger.event.ActorType;
import com.chestlogger.event.BlockPosUtil;
import com.chestlogger.event.SlotDelta;
import com.chestlogger.event.TransactionLogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency stress test verifying thread-safety, zero deadlocks, zero memory leaks,
 * and sub-20ms evaluation latency under high thread contention for the Smart Theft & Raid Detection Engine.
 */
class SmartTheftConcurrencyTest {

    private static final int THREAD_COUNT = 16;
    private static final int TOTAL_ACTIONS = 500;
    private static final int ACTOR_COUNT = 50;
    private static final int CONTAINER_COUNT = 100;

    private ExecutorService executor;
    private TrustManager trustManager;
    private RaidVelocityTracker raidTracker;
    private AlertConfig alertConfig;
    private SmartTheftEvaluator evaluator;
    private IncidentRingBuffer incidentBuffer;

    private List<UUID> actors;
    private List<Long> containerPositions;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(THREAD_COUNT);
        trustManager = new TrustManager();
        raidTracker = new RaidVelocityTracker(300_000L, 3);
        alertConfig = new AlertConfig(
                true,
                "https://discord.com/api/webhooks/test",
                "GuardBot",
                "",
                32,
                Set.of("minecraft:diamond", "minecraft:netherite_ingot", "minecraft:elytra", "minecraft:beacon"),
                true,
                true,
                30
        );
        evaluator = new SmartTheftEvaluator(trustManager, alertConfig, raidTracker);
        incidentBuffer = new IncidentRingBuffer(200);

        actors = new ArrayList<>(ACTOR_COUNT);
        for (int i = 0; i < ACTOR_COUNT; i++) {
            actors.add(UUID.randomUUID());
        }

        containerPositions = new ArrayList<>(CONTAINER_COUNT);
        for (int i = 0; i < CONTAINER_COUNT; i++) {
            containerPositions.add(BlockPosUtil.pack((i % 10) * 16, 64, (i / 10) * 16));
        }
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("500 concurrent player actions across 50 actors and 100 containers with 16 parallel threads")
    void testConcurrent500ActionsStress() throws InterruptedException {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(TOTAL_ACTIONS);

        ConcurrentLinkedQueue<Long> evaluationLatenciesNs = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger incidentsGenerated = new AtomicInteger(0);

        long baseTimestamp = 1_700_000_000_000L;

        for (int i = 0; i < TOTAL_ACTIONS; i++) {
            final int actionId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    UUID actor = actors.get(actionId % ACTOR_COUNT);
                    UUID owner = actors.get((actionId + 7) % ACTOR_COUNT);
                    long pos = containerPositions.get((actionId * 3) % CONTAINER_COUNT);
                    long timestamp = baseTimestamp + (actionId * 100L);

                    // 1. Interleave dynamic trust operations
                    if (actionId % 5 == 0) {
                        trustManager.trust(owner, actor);
                    } else if (actionId % 7 == 0) {
                        trustManager.untrust(owner, actor);
                    }
                    trustManager.isTrusted(owner, actor);
                    trustManager.getTrustList(owner);

                    // 2. Build representative container action
                    String itemId = switch (actionId % 4) {
                        case 0 -> "minecraft:diamond";
                        case 1 -> "minecraft:netherite_ingot";
                        case 2 -> "minecraft:elytra";
                        default -> "minecraft:gold_ingot";
                    };
                    int quantity = (actionId % 32) + 1;

                    TransactionLogEntry entry = new TransactionLogEntry(
                            (long) actionId + 1,
                            timestamp,
                            UUID.randomUUID(),
                            actionId % 10 == 0 ? ActionType.CONTAINER_BREAK : ActionType.SHIFT_CLICK_EXTRACT,
                            ActorType.PLAYER,
                            actor,
                            "Player_" + (actionId % ACTOR_COUNT),
                            "minecraft:overworld",
                            pos,
                            List.of(new SlotDelta(actionId % 27, itemId, -quantity, quantity, 0, 0L))
                    );

                    // 3. Select owner presence state
                    OwnerPresenceState presence = switch (actionId % 3) {
                        case 0 -> OwnerPresenceState.offline();
                        case 1 -> OwnerPresenceState.online(150.0); // absent (>24 blocks)
                        default -> OwnerPresenceState.online(6.5);   // consensual nearby (<=24 blocks)
                    };

                    // 4. Measure SmartTheftEvaluator latency under contention
                    long evalStartNs = System.nanoTime();
                    Optional<SecurityIncident> incident = evaluator.evaluate(
                            entry,
                            owner,
                            "Owner_" + ((actionId + 7) % ACTOR_COUNT),
                            presence
                    );
                    long latencyNs = System.nanoTime() - evalStartNs;
                    evaluationLatenciesNs.add(latencyNs);

                    // 5. Store incident in bounded buffer if detected
                    if (incident.isPresent()) {
                        incidentsGenerated.incrementAndGet();
                        incidentBuffer.add(incident.get());
                    }

                    // 6. Concurrently prune expired raid entries
                    if (actionId % 50 == 0) {
                        raidTracker.pruneExpired(timestamp);
                    }

                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all 16 worker threads simultaneously
        startLatch.countDown();

        // Assert zero deadlocks: all 500 actions finish within 10 seconds
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        assertThat(completed)
                .withFailMessage("Evaluation deadlocked or did not complete in 10s timeout")
                .isTrue();

        // Assert zero race conditions or runtime exceptions
        assertThat(errors).isEmpty();
        assertThat(successCount.get()).isEqualTo(TOTAL_ACTIONS);

        // Latency assertions: sub-20ms latency under high thread contention
        assertThat(evaluationLatenciesNs).isNotEmpty();
        List<Long> sortedLatencies = new ArrayList<>(evaluationLatenciesNs);
        Collections.sort(sortedLatencies);

        long maxLatencyNs = sortedLatencies.get(sortedLatencies.size() - 1);
        double maxLatencyMs = maxLatencyNs / 1_000_000.0;

        long p99LatencyNs = sortedLatencies.get((int) (sortedLatencies.size() * 0.99));
        double p99LatencyMs = p99LatencyNs / 1_000_000.0;

        double avgLatencyMs = sortedLatencies.stream().mapToLong(Long::longValue).average().orElse(0.0) / 1_000_000.0;

        System.out.printf("[CONCURRENCY TEST] 500 actions (16 threads) - Avg: %.3f ms | p99: %.3f ms | Max: %.3f ms | Incidents: %d%n",
                avgLatencyMs, p99LatencyMs, maxLatencyMs, incidentsGenerated.get());

        assertThat(avgLatencyMs)
                .withFailMessage("Average evaluation latency exceeded 20ms: %.3f ms", avgLatencyMs)
                .isLessThan(20.0);

        assertThat(p99LatencyMs)
                .withFailMessage("99th percentile evaluation latency exceeded 20ms: %.3f ms", p99LatencyMs)
                .isLessThan(20.0);

        // Memory leak / capacity bounds assertion: IncidentRingBuffer strictly bounded
        assertThat(incidentBuffer.size()).isLessThanOrEqualTo(200);
        assertThat(incidentBuffer.capacity()).isEqualTo(200);
    }

    @Test
    @DisplayName("Sustained high-throughput evaluation maintains bounded memory and zero leaks")
    void testSustainedLoadBoundedMemory() throws InterruptedException {
        int operations = 2_000;
        CountDownLatch latch = new CountDownLatch(operations);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        long baseTimestamp = 1_000_000L;

        for (int i = 0; i < operations; i++) {
            final int op = i;
            executor.submit(() -> {
                try {
                    UUID actor = actors.get(op % ACTOR_COUNT);
                    UUID owner = actors.get((op + 3) % ACTOR_COUNT);
                    long pos = containerPositions.get(op % CONTAINER_COUNT);
                    long timestamp = baseTimestamp + (op * 50L);

                    TransactionLogEntry entry = new TransactionLogEntry(
                            (long) op + 1,
                            timestamp,
                            UUID.randomUUID(),
                            ActionType.SHIFT_CLICK_EXTRACT,
                            ActorType.PLAYER,
                            actor,
                            "Player_" + (op % ACTOR_COUNT),
                            "minecraft:overworld",
                            pos,
                            List.of(new SlotDelta(0, "minecraft:diamond", -1, 1, 0, 0L))
                    );

                    Optional<SecurityIncident> incident = evaluator.evaluate(
                            entry,
                            owner,
                            "Owner",
                            OwnerPresenceState.offline()
                    );
                    incident.ifPresent(incidentBuffer::add);

                    if (op % 100 == 0) {
                        raidTracker.pruneExpired(timestamp);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();

        // Memory bounds verification: RingBuffer bounded at 200 items despite 2,000 additions
        assertThat(incidentBuffer.size()).isEqualTo(200);
        assertThat(incidentBuffer.getAll()).hasSize(200);

        // Pruning clears old tracking history
        raidTracker.pruneExpired(baseTimestamp + (operations * 50L) + 400_000L);
        assertThat(raidTracker.getTrackedActorsCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Concurrent multi-container raid bursts correctly identify burst state under 16 parallel threads")
    void testConcurrentRaidBurstDetection() throws InterruptedException {
        int actorsTestingRaid = 10;
        int accessesPerActor = 5; // accesses 5 distinct containers -> triggers raid burst
        int totalTasks = actorsTestingRaid * accessesPerActor;

        CountDownLatch latch = new CountDownLatch(totalTasks);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        AtomicInteger raidIncidentsDetected = new AtomicInteger(0);

        long baseTime = 1_000_000L;

        for (int a = 0; a < actorsTestingRaid; a++) {
            UUID actor = actors.get(a);
            for (int step = 0; step < accessesPerActor; step++) {
                final int stepIndex = step;
                final long pos = containerPositions.get((a * 10) + step);
                final long time = baseTime + (stepIndex * 1_000L);

                executor.submit(() -> {
                    try {
                        TransactionLogEntry entry = new TransactionLogEntry(
                                (long) (stepIndex + 1),
                                time,
                                UUID.randomUUID(),
                                ActionType.SHIFT_CLICK_EXTRACT,
                                ActorType.PLAYER,
                                actor,
                                "Raider",
                                "minecraft:overworld",
                                pos,
                                List.of(new SlotDelta(0, "minecraft:netherite_ingot", -5, 5, 0, 0L))
                        );

                        Optional<SecurityIncident> incident = evaluator.evaluate(
                                entry,
                                UUID.randomUUID(),
                                "Victim",
                                OwnerPresenceState.offline()
                        );

                        if (incident.isPresent() && incident.get().classification() == IncidentClassification.CRITICAL_RAID) {
                            raidIncidentsDetected.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();
        assertThat(raidIncidentsDetected.get()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Concurrent trust modifications and evaluation consistency")
    void testConcurrentTrustAndEvaluationConsistency() throws InterruptedException {
        int operations = 600;
        CountDownLatch latch = new CountDownLatch(operations);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        UUID owner = UUID.randomUUID();
        UUID friend = UUID.randomUUID();

        for (int i = 0; i < operations; i++) {
            final int op = i;
            executor.submit(() -> {
                try {
                    if (op % 2 == 0) {
                        trustManager.trust(owner, friend);
                    } else {
                        trustManager.untrust(owner, friend);
                    }

                    TransactionLogEntry entry = new TransactionLogEntry(
                            (long) op,
                            1_000_000L + op,
                            UUID.randomUUID(),
                            ActionType.SHIFT_CLICK_EXTRACT,
                            ActorType.PLAYER,
                            friend,
                            "Friend",
                            "minecraft:overworld",
                            containerPositions.get(0),
                            List.of(new SlotDelta(0, "minecraft:diamond", -1, 1, 0, 0L))
                    );

                    // Evaluator must execute cleanly regardless of trustMap mutation state
                    evaluator.evaluate(entry, owner, "Owner", OwnerPresenceState.offline());
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();
    }
}
