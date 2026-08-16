# Implementation Plan: ChestLogger MVP (Minecraft 26.2)

## Phase 1: Project Bootstrap & Build Verification [checkpoint: 382f236]
- [x] Task: Verify official Minecraft 26.2 Fabric ecosystem dependencies (Loader 0.19.3+, Loom 1.17, Fabric API 26.2, Java 25, Gradle 9.5.1 wrapper). (af5b860)
- [x] Task: Scaffold root gradle build scripts (`build.gradle`, `settings.gradle`, `gradle.properties`, `fabric.mod.json`). (4443b36)
- [x] Task: Validate clean build and compile execution with `./gradlew build`. (382f236)
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) (382f236)

## Phase 2: Core Event Model, Metadata Fingerprinting & Event Queue [checkpoint: e04f678]
- [x] Task: Write unit tests for Transaction models, Slot Delta calculations, and Item Metadata Fingerprint hashing. (fbdb394)
- [x] Task: Implement `TransactionLogEntry`, `ItemDelta`, `ActionType`, and compact metadata fingerprinting. (d4770db)
- [x] Task: Write unit tests for the bounded non-blocking MPSC queue and overflow policies. (f2b025b)
- [x] Task: Implement bounded `EventQueue` with diagnostic counters (depth, dropped events, total enqueued). (e04f678)
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) (e04f678)

## Phase 3: Binary Storage Engine, Block Compression & Disk Profiles [checkpoint: 908b49d]
- [x] Task: Write unit tests for VarInt/VarLong encoding, string table dictionaries, and binary block serialization. (8042d23)
- [x] Task: Implement versioned binary serializer (`BinaryLogWriter`) with block checksums (CRC32). (36b2d09)
- [x] Task: Write unit tests for LZ4 block compressor and decompression round-trips. (df66e53)
- [x] Task: Implement LZ4 block compressor and profile presets (`balanced`, `hdd`, `ssd`) with configurable batch flushing. (908b49d)
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) (908b49d)

## Phase 4: Unified Container Abstraction & Server-Authoritative Transaction Capture [checkpoint: a568800]
- [x] Task: Write unit tests for unified container abstraction and transaction state diffing. (b736116)
- [x] Task: Implement unified container detection abstraction (`ContainerTracker`, `ContainerType`) for Single/Double Chests, Trapped Chests, Barrels, Shulker Boxes, Hoppers, and Hopper Minecarts. (5361e59)
- [x] Task: Implement Mixins for player container screen handling (pickup, shift-click, drag, number keys, container close verification). (782abe8)
- [x] Task: Implement unified automation transaction capture for Hopper and Hopper Minecart transfers within the container abstraction. (42c657a)
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) (a568800)

## Phase 5: Persistent Multi-Dimensional Indexing [checkpoint: 95d1962]
- [x] Task: Write unit tests for index entry serialization and multi-dimensional query filters (time, player UUID, BlockPos, segment offset). (b94e7d6)
- [x] Task: Implement `PersistentIndexManager` with atomic checkpoints and disk batching. (8686323)
- [x] Task: Write unit tests for index recovery and rebuilding from binary log metadata. (d3d4ea1)
- [x] Task: Implement automatic index rebuild engine for corrupted/missing index files. (95d1962)
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) (95d1962)

## Phase 6: Crash Safety & Segment Tail Recovery
- [x] Task: Write unit tests for corrupted block detection, partial writes, and tail truncation. (e7d4805)
- [x] Task: Implement startup log segment validator, corrupted tail truncation/quarantine, and monotonic sequence resumption. (265bf06)
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 7: Administrative Query Engine & Commands
- [ ] Task: Write unit tests for query pagination and formatted display output.
- [ ] Task: Implement `/chestlog inspect` command with player, location, and time range filters.
- [ ] Task: Implement `/chestlog stats` displaying live queue, flush, and compression metrics.
- [ ] Task: Implement `/chestlog purge` with confirmation token safeguards.
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 8: Non-Destructive Compensation Rollback Engine
- [ ] Task: Write unit tests for rollback dry-run calculation, safety validation, and inverse delta application.
- [ ] Task: Implement `RollbackEngine` with pre-validation (refusing occupied/modified invalid slots, preventing item dupe/deletion) and audit trail logging.
- [ ] Task: Implement `/chestlog rollback` command interface with dry-run summary and confirmation.
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 9: Singleplayer & Dedicated Server Lifecycle Integration
- [ ] Task: Implement world lifecycle listeners for per-world storage directory resolution (`saves/<world>/chestlogger/` vs `server_root/world/chestlogger/`).
- [ ] Task: Implement deterministic flush and writer thread termination on server stop or world leave.
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 10: End-to-End Testing, Microbenchmarks & Hardening
- [ ] Task: Implement integration tests simulating concurrent container transactions, hopper automation, and crash-restart cycles.
- [ ] Task: Implement microbenchmarks for throughput (100 to 1M events) across HDD and SSD profiles.
- [ ] Task: Complete documentation (`README.md`, configuration comments) and verify release artifact packaging.
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)
