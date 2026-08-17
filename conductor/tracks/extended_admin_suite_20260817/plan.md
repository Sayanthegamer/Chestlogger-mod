# Implementation Plan: Interactive Wand, Paper GUI Parity, Extended Containers & Discord Alerting

## Phase 1: Interactive Wand & Click Inspection Mode (Fabric & Paper)
- [x] Task: Phase 1 Test Harness - Mode Tracking & Wand Matcher Unit Tests [98ddffa]
  - [x] Write unit tests for `InspectModeManager` (player toggle state, timeouts, wand item matching) in `chestlogger-common` [98ddffa]
- [x] Task: Implement `InspectModeManager` & Configuration in Common Core [98ddffa]
  - [x] Implement `InspectModeManager` with concurrent player session tracking and `WandConfig` parsing [98ddffa]
- [~] Task: Implement Paper Click & Wand Interaction Interception
  - [~] Write unit test for `PaperWandListener`
  - [ ] Implement `PlayerInteractEvent` and `BlockDamageEvent` interception in `chestlogger-paper`
- [ ] Task: Implement Fabric Click & Wand Interaction Interception
  - [ ] Implement `UseBlockCallback` and `AttackBlockCallback` hooks in `chestlogger-fabric`
- [ ] Task: Add Command Aliases & Ergonomics (`/chestlog i`, `/chestlog wand`)
  - [ ] Register `/chestlog i` / `/chestlog wand` commands on both Fabric and Paper
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 2: Paper In-Game Inspection GUI Parity
- [ ] Task: Phase 2 Test Harness - Bukkit GUI View Model Unit Tests
  - [ ] Write unit tests for `PaperChestHistoryView` layout, item metadata, and pagination slicing
- [ ] Task: Implement `PaperChestHistoryView` 54-Slot GUI Layout
  - [ ] Implement 54-slot custom Inventory builder with item icons, lore formatting, and half indicators (`L`/`R`)
- [ ] Task: Implement Paper GUI Click Listener & Action Handlers
  - [ ] Intercept clicks on GUI slots, handle pagination navigation, and wire non-destructive rollback confirmation
- [ ] Task: Connect Wand Right-Click to Paper GUI
  - [ ] Automatically launch `PaperChestHistoryView` when right-clicking container with inspect mode / wand on Paper
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 3: Extended Container & Block Lifecycle Tracking
- [ ] Task: Phase 3 Test Harness - Lifecycle Event & Extended Container Tests
  - [ ] Write unit tests in `chestlogger-common` for `CONTAINER_BREAK` and `CONTAINER_PLACE` action types and delta extraction
- [ ] Task: Add Extended Container Support & Lifecycle Types in Common Core
  - [ ] Add `CONTAINER_BREAK` and `CONTAINER_PLACE` to `ActionType`, update binary serializer and query filters
- [ ] Task: Implement Paper Extended Container & Lifecycle Event Interception
  - [ ] Track Crafter, Dispenser, Dropper, Decorated Pot, Chiseled Bookshelf, Furnaces, Brewing Stands, and `BlockBreakEvent`/`BlockPlaceEvent`
- [ ] Task: Implement Fabric Extended Container & Lifecycle Interception
  - [ ] Implement Mixins and callbacks for extended block entities and block destruction with inventory contents
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 4: Discord Webhook & Suspicious Activity Alert Engine
- [ ] Task: Phase 4 Test Harness - Alert Config & Discord Embed Builder Tests
  - [ ] Write unit tests for `AlertConfig`, suspicious threshold evaluations, and JSON embed payload formatting
- [ ] Task: Implement `DiscordAlertDispatcher` with Non-Blocking `HttpClient`
  - [ ] Implement asynchronous queue worker, rate limiting, and webhook HTTP dispatch in `chestlogger-common`
- [ ] Task: Wire Transaction Stream to Alert Dispatcher
  - [ ] Hook `TransactionEventQueue` flush listeners to evaluate suspicious rules and trigger alerts off-thread
- [ ] Task: Add Platform Lifecycle Binding & Configuration
  - [ ] Load `config/chestlogger_alerts.json` on startup in Fabric and Paper, ensure clean daemon shutdown
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 5: Cross-Platform End-to-End Integration & CI Verification
- [ ] Task: Cross-Platform Regression & Integration Test Suite
  - [ ] Run and verify complete integration tests across `chestlogger-common`, `chestlogger-fabric`, and `chestlogger-paper`
- [ ] Task: Documentation & Release Version Bump
  - [ ] Update `README.md`, `product.md`, `tech-stack.md`, and bump version to `2.2.0`
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)
