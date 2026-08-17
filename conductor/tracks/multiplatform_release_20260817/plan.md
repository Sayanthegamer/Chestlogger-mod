# Implementation Plan: Multi-Platform Server Releases (Fabric 26.2 & Paper 26.2)

## Phase 1: Multi-Module Architecture & Common Core Extraction
- [x] Task: Configure root multi-project Gradle build (`chestlogger-common`, `chestlogger-fabric`, `chestlogger-paper`) 4b94d7e
  - [x] Update `settings.gradle` with subproject declarations and centralized plugin repositories
  - [x] Create `chestlogger-common/build.gradle` (pure Java 25, LZ4, Zstandard, JUnit 5, AssertJ, zero platform dependencies)
  - [x] Configure root build orchestration and task dependencies
- [ ] Task: (TDD Red) Create test fixtures & golden `.chlog` format test harness in `chestlogger-common`
  - [ ] Create byte-for-byte valid and truncated/corrupted golden `.chlog` test fixtures in common test resources
  - [ ] Write unit tests for `BinaryRecordCodec`, `LZ4BlockCompressor`, `TransactionEventQueue`, `RollbackPlanner`, and `TailRecoveryEngine`
  - [ ] Instrument storage and compression layers with main-thread assertions that fail if invoked on a server thread
- [ ] Task: (TDD Green) Extract core engines into `chestlogger-common`
  - [ ] Extract `storage`, `event`, `index`, `query`, `recovery`, `rollback` (planner/math), and `web` (HTTP server & handlers) into `chestlogger-common`
  - [ ] Decouple platform executors (keep `RollbackPlanner` in common; prepare platform executor interfaces)
  - [ ] Run `./gradlew :chestlogger-common:test` and verify 100% tests pass
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 2: Paper 26.2 Plugin Scaffold & Event Interception Implementation
- [ ] Task: Configure `chestlogger-paper` subproject with Paperweight Userdev
  - [ ] Configure `chestlogger-paper/build.gradle` using `paperweight-userdev` with pinned Paper 26.2 dev bundle and Mojmap runtime (no legacy reobf)
  - [ ] Determine plugin descriptor strategy and configure production `plugin.yml` (and optional `paper-plugin.yml`) with commands and permissions
- [ ] Task: (TDD Red) Create unit tests for Paper transaction snapshotting & event delta calculation
  - [ ] Write unit tests for click types (pickup, place, shift-click, hotbar swap, double-click, drag distributions)
  - [ ] Write unit tests for `InventoryMoveItemEvent` (hoppers) and multi-viewer container synchronization
  - [ ] Write thread-assertion test ensuring event handlers never trigger disk I/O or block compression
- [ ] Task: (TDD Green) Implement Paper event listeners & `PaperRollbackExecutor`
  - [ ] Implement `PaperChestEventListener` with explicit event-to-transaction delta modeling (before/after state capture)
  - [ ] Implement `PaperRollbackExecutor` executing inventory rollbacks safely on the main server thread via `Inventory.setItem()`
  - [ ] Enqueue immutable `TransactionLogEntry` to `TransactionEventQueue`
- [ ] Task: (TDD Red) Create tests for Paper `/chestlog` command tree and permissions
  - [ ] Write tests for `/chestlog inspect`, `/chestlog rollback`, `/chestlog stats`, `/chestlog web`
- [ ] Task: (TDD Green) Implement Paper command handler and permission integration
  - [ ] Implement Paper command tree with async query formatting and permission nodes
- [ ] Task: (TDD Red) Create tests for Paper plugin lifecycle and async shutdown flush
  - [ ] Write tests verifying thread-safe queue draining and block flush barrier on `onDisable()`
- [ ] Task: (TDD Green) Implement `ChestLoggerPlugin` lifecycle & Paper scheduler binding
  - [ ] Implement `onEnable()` / `onDisable()` with deterministic background worker flush barrier
  - [ ] Run `./gradlew :chestlogger-paper:test`
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 3: Fabric 26.2 Reference Mod Refactor & Parity Verification
- [ ] Task: Configure `chestlogger-fabric` subproject
  - [ ] Configure `chestlogger-fabric/build.gradle` with `net.fabricmc.fabric-loom` 1.17, Loader 0.19.3, Gradle 9.5.1, and `chestlogger-common` dependency
  - [ ] Relocate Fabric-specific mixins, commands, lifecycle hooks, `FabricRollbackExecutor`, and GUI networking
- [ ] Task: (TDD Red) Create regression test suite for Fabric 26.2 integration
  - [ ] Write tests for Fabric lifecycle events, container mixin capture, singleplayer separation, and GUI packet codecs
  - [ ] Write thread-assertion test ensuring Fabric mixins never trigger disk I/O on the main thread
- [ ] Task: (TDD Green) Wire Fabric entrypoints to `chestlogger-common`
  - [ ] Update `ChestLoggerMod`, `ChestLoggerLifecycleManager`, and `ChestLoggerCommands` to use `chestlogger-common`
  - [ ] Run `./gradlew :chestlogger-fabric:test` and verify zero regressions
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 4: Cross-Platform Interoperability, Durability & Rollback Validation
- [ ] Task: (TDD Red) Create cross-platform golden `.chlog` compatibility tests
  - [ ] Write tests validating that `.chlog` files generated on Paper are parsed, indexed, and rolled back identically on Fabric and vice versa using golden fixtures
  - [ ] Validate truncated/corrupted tail recovery parity across both platforms
- [ ] Task: (TDD Green) Validate bi-directional format parity and rollback execution
  - [ ] Ensure byte-for-byte serialization consistency and coordinate/item namespace uniformity
  - [ ] Verify cross-platform rollback dry-run and execution parity
- [ ] Task: (TDD Red) Create HDD sequential I/O durability and crash-safety tests
  - [ ] Test simulated queue overflow, Aternos crash conditions, tail recovery, and thread safety assertions
- [ ] Task: (TDD Green) Optimize sequential batch writer and backpressure metrics
  - [ ] Verify zero main-thread disk seek storms and zero disk blocking across both platform profiles
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 5: Automated Test Harnesses, CI Pipeline & Release Packaging
- [ ] Task: Build automated Paper 26.2 headless dedicated server test harness
  - [ ] Implement headless Paper 26.2 server test script validating plugin load, real container mutations, shutdown flush, and `.chlog` generation
- [ ] Task: Build automated Fabric 26.2 dedicated server verification harness
  - [ ] Implement Fabric 26.2 dedicated server test script validating mod load, container events, and `.chlog` output
- [ ] Task: Update GitHub Actions CI workflow (`.github/workflows/ci.yml`)
  - [ ] Configure multi-project build verification for `:chestlogger-common`, `:chestlogger-fabric`, and `:chestlogger-paper`
  - [ ] Configure dual-artifact release packaging for `chestlogger-fabric-<version>.jar` and `chestlogger-paper-<version>.jar`
- [ ] Task: Complete documentation & migration guides
  - [ ] Document Paper installation, permissions, config options, and cross-platform log inspection
  - [ ] Update `README.md` and conductor documentation
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)
