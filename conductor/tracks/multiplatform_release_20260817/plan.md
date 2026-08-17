# Implementation Plan: Multi-Platform Server Releases (Fabric 26.2 & Paper 26.2)

## Phase 1: Multi-Module Architecture & Common Core Extraction
- [x] Task: Configure root multi-project Gradle build (`chestlogger-common`, `chestlogger-fabric`, `chestlogger-paper`) 4b94d7e
  - [x] Update `settings.gradle` with subproject declarations and centralized plugin repositories
  - [x] Create `chestlogger-common/build.gradle` (pure Java 25, LZ4, Zstandard, JUnit 5, AssertJ, zero platform dependencies)
  - [x] Configure root build orchestration and task dependencies
- [x] Task: (TDD Red) Create test fixtures & golden `.chlog` format test harness in `chestlogger-common` f1fdbff
  - [x] Create byte-for-byte valid and truncated/corrupted golden `.chlog` test fixtures in common test resources
  - [x] Write unit tests for `BinaryRecordCodec`, `LZ4BlockCompressor`, `TransactionEventQueue`, `RollbackPlanner`, and `TailRecoveryEngine`
  - [x] Instrument storage and compression layers with main-thread assertions that fail if invoked on a server thread
- [x] Task: (TDD Green) Extract core engines into `chestlogger-common` f1fdbff
  - [x] Extract `storage`, `event`, `index`, `query`, `recovery`, `rollback` (planner/math), and `web` (HTTP server & handlers) into `chestlogger-common`
  - [x] Decouple platform executors (keep `RollbackPlanner` in common; prepare platform executor interfaces)
  - [x] Run `./gradlew :chestlogger-common:test` and verify 100% tests pass
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 2: Paper 26.2 Plugin Scaffold & Event Interception Implementation
- [x] Task: Configure `chestlogger-paper` subproject with Paperweight Userdev e1b53b2
  - [x] Configure `chestlogger-paper/build.gradle` using `paperweight-userdev` with pinned Paper 26.2 dev bundle and Mojmap runtime (no legacy reobf)
  - [x] Determine plugin descriptor strategy and configure production `plugin.yml` (and optional `paper-plugin.yml`) with commands and permissions
- [x] Task: (TDD Red) Create unit tests for Paper transaction snapshotting & event delta calculation e1b53b2
  - [x] Write unit tests for click types (pickup, place, shift-click, hotbar swap, double-click, drag distributions)
  - [x] Write unit tests for `InventoryMoveItemEvent` (hoppers) and multi-viewer container synchronization
  - [x] Write thread-assertion test ensuring event handlers never trigger disk I/O or block compression
- [x] Task: (TDD Green) Implement Paper event listeners & `PaperRollbackExecutor` e1b53b2
  - [x] Implement `PaperChestEventListener` with explicit event-to-transaction delta modeling (before/after state capture)
  - [x] Implement `PaperRollbackExecutor` executing inventory rollbacks safely on the main server thread via `Inventory.setItem()`
  - [x] Enqueue immutable `TransactionLogEntry` to `TransactionEventQueue`
- [x] Task: (TDD Red) Create tests for Paper `/chestlog` command tree and permissions e1b53b2
  - [x] Write tests for `/chestlog inspect`, `/chestlog rollback`, `/chestlog stats`, `/chestlog web`
- [x] Task: (TDD Green) Implement Paper command handler and permission integration e1b53b2
  - [x] Implement Paper command tree with async query formatting and permission nodes
- [x] Task: (TDD Red) Create tests for Paper plugin lifecycle and async shutdown flush e1b53b2
  - [x] Write tests verifying thread-safe queue draining and block flush barrier on `onDisable()`
- [x] Task: (TDD Green) Implement `ChestLoggerPlugin` lifecycle & Paper scheduler binding e1b53b2
  - [x] Implement `onEnable()` / `onDisable()` with deterministic background worker flush barrier
  - [x] Run `./gradlew :chestlogger-paper:test`
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 3: Fabric 26.2 Reference Mod Refactor & Parity Verification
- [x] Task: Configure `chestlogger-fabric` subproject 54682f1
  - [x] Configure `chestlogger-fabric/build.gradle` with `net.fabricmc.fabric-loom` 1.17, Loader 0.19.3, Gradle 9.5.1, and `chestlogger-common` dependency
  - [x] Relocate Fabric-specific mixins, commands, lifecycle hooks, `FabricRollbackExecutor`, and GUI networking
- [x] Task: (TDD Red) Create regression test suite for Fabric 26.2 integration 54682f1
  - [x] Write tests for Fabric lifecycle events, container mixin capture, singleplayer separation, and GUI packet codecs
  - [x] Write thread-assertion test ensuring Fabric mixins never trigger disk I/O on the main thread
- [x] Task: (TDD Green) Wire Fabric entrypoints to `chestlogger-common` 54682f1
  - [x] Update `ChestLoggerMod`, `ChestLoggerLifecycleManager`, and `ChestLoggerCommands` to use `chestlogger-common`
  - [x] Run `./gradlew :chestlogger-fabric:test` and verify zero regressions
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 4: Cross-Platform Interoperability, Durability & Rollback Validation
- [x] Task: (TDD Red) Create cross-platform golden `.chlog` compatibility tests ba6dd44
  - [x] Write tests validating that `.chlog` files generated on Paper are parsed, indexed, and rolled back identically on Fabric and vice versa using golden fixtures
  - [x] Validate truncated/corrupted tail recovery parity across both platforms
- [x] Task: (TDD Green) Validate bi-directional format parity and rollback execution ba6dd44
  - [x] Ensure byte-for-byte serialization consistency and coordinate/item namespace uniformity
  - [x] Verify cross-platform rollback dry-run and execution parity
- [x] Task: (TDD Red) Create HDD sequential I/O durability and crash-safety tests ba6dd44
  - [x] Test simulated queue overflow, Aternos crash conditions, tail recovery, and thread safety assertions
- [x] Task: (TDD Green) Optimize sequential batch writer and backpressure metrics ba6dd44
  - [x] Verify zero main-thread disk seek storms and zero disk blocking across both platform profiles
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)

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
