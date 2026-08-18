# Implementation Plan: Custom In-Game Configuration Menu & Hot-Reload Suite

## Phase 1: Core Configuration Model & Live Hot-Reload Engine (TDD) [checkpoint: 40ca164]
- [x] Task: Write unit tests for configuration hot-reloading and listener propagation (`ConfigHotReloadTest.java`) [8c18f97]
  - [x] Test `ConfigManager` managing `AlertConfig` and `WebConfig`
  - [x] Test atomic file saving and serialization validation
  - [x] Test listener notification propagation on configuration update
- [x] Task: Implement `ConfigManager` in `chestlogger-common` [40ca164]
  - [x] Create `ConfigManager.java` managing configuration lifecycles and thread-safe listeners
  - [x] Integrate `ConfigManager` with `SmartTheftEvaluator`, `DiscordAlertDispatcher`, and Broadcasters
  - [x] Verify all unit tests pass (Green Phase)
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) [40ca164]

## Phase 2: Fabric Custom Networking & Client-Side Configuration Screen
- [x] Task: Write unit tests for `ChestLogConfigPayload` and `ChestLogConfigUpdatePayload` codecs [36bd745]
  - [x] Test roundtrip packet serialization and deserialization
  - [x] Test payload validation and permission checking
- [x] Task: Implement Fabric network packet payloads and server packet handlers [b8a1b14]
  - [x] Register `ChestLogConfigPayload` and `ChestLogConfigUpdatePayload` in `ChestLogNetworking`
  - [x] Implement server packet receiver with admin permission gating and atomic config saving
- [~] Task: Implement client-side `ChestLogConfigScreen`
  - [ ] Build custom `Screen` with tab navigation, text `EditBox` inputs (Discord webhook URL, port, bot name), toggle buttons, and tracked items editor
  - [ ] Register client networking receiver to open screen on receiving server payload
- [ ] Task: Register `/chestlog config` command in Fabric Brigadier command suite
  - [ ] Add `/chestlog config` and `/chestlog config reload/get/set` in `ChestLoggerCommands.java`
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 3: Paper In-Game Configuration GUI & Chat-Input Handler
- [ ] Task: Write unit tests for Paper config GUI components and layout
  - [ ] Test inventory slot layouts and category tab actions
  - [ ] Test chat prompt string editor state machine
- [ ] Task: Implement `PaperChestConfigView` and `PaperChestConfigListener`
  - [ ] Create 54-slot zero-dependency Bukkit inventory GUI with category tabs and visual toggle states
  - [ ] Implement click actions for adjusting toggles, cooldowns, and distances
- [ ] Task: Implement chat-assisted text editor for Discord Webhook URLs and string values
  - [ ] Implement temporary chat listener capturing admin text input with timeout and cancellation
- [ ] Task: Register `/chestlog config` in Paper command suite
  - [ ] Add `/chestlog config` and `/chestlog config reload/get/set` in `PaperCommandExecutor.java`
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 4: System Integration, Test Verification & Quality Gates
- [ ] Task: Run automated test suite and verify coverage
  - [ ] Execute `./gradlew test` across all modules (`common`, `fabric`, `paper`)
  - [ ] Verify 100% test pass rate
- [ ] Task: Track Completion Review & Verification
  - [ ] Verify all acceptance criteria from `spec.md`
  - [ ] Perform manual and automated verification checkpoint (Refer to workflow.md)
