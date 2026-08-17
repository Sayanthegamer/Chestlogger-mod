# Implementation Plan: Double Chest (54-Slot) Linked Container Support

## Phase 1: Core 54-Slot Models & Dual-Coordinate Indexing (`chestlogger-common`)
- [x] Task: (TDD Red) Create unit tests for 54-slot container snapshots, compound deltas, and dual-coordinate queries 1fb9f08
  - [x] Write tests verifying `ContainerSnapshot` with 54 slots
  - [x] Write tests verifying `PersistentIndexManager` dual-block pos resolution and query matching for both chest halves
- [x] Task: (TDD Green) Extend common models and indexer for linked containers 1fb9f08
  - [x] Support linked/secondary `packedBlockPos` in index pointers and query filters
  - [x] Run `:chestlogger-common:test` to verify all tests pass
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 2: Paper 26.2 Double Chest Event Interception & Rollback Adapter (`chestlogger-paper`)
- [x] Task: (TDD Red) Create Paper double chest test suite 34c71b7
  - [x] Write unit tests simulating `DoubleChestInventory` mutations (pickup, shift-click across halves, hoppers)
  - [x] Write tests asserting partner block coordinate extraction from `DoubleChest.getLeftSide()` / `DoubleChest.getRightSide()`
- [x] Task: (TDD Green) Implement Paper double chest event capture and rollback 34c71b7
  - [x] Update `PaperChestEventListener` to detect `DoubleChest` and log both block coordinates
  - [x] Update `PaperRollbackExecutor` to restore items across both physical block locations
  - [x] Run `:chestlogger-paper:test` to verify
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 3: Fabric 26.2 Double Inventory Mixins & Rollback Adapter (`chestlogger-fabric`)
- [x] Task: (TDD Red) Create Fabric double inventory test suite 50082c9
  - [x] Write tests for `DoubleInventory` screen handlers and partner block resolution (`ChestBlock.getDoubleBlockType()`)
  - [x] Write tests verifying slot boundary tracking (0–26 left vs 27–53 right)
- [x] Task: (TDD Green) Implement Fabric double chest mixins and rollback executor 50082c9
  - [x] Update container mixins to capture `DoubleInventory` transactions
  - [x] Update `FabricRollbackExecutor` to restore across both `ChestBlockEntity` instances
  - [x] Run `:chestlogger-fabric:test` to verify
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 4: Adaptive Rollback Engine & Cross-Platform Durability
- [x] Task: (TDD Red) Create cross-platform 54-slot interop and broken-half recovery tests 98d380c
  - [x] Write tests verifying Paper double chest logs are parsed, queried, and rolled back identically by Fabric and vice versa
  - [x] Write tests verifying adaptive rollback when one half of a double chest is missing/broken
- [x] Task: (TDD Green) Implement adaptive broken-half compensation in `RollbackEngine` 98d380c
  - [x] Support fallback compensation for missing container halves without dropping unplaceable items
  - [x] Verify zero main-thread blocking under heavy multi-slot contention
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 5: GUI, Web Dashboard & Build Verification
- [x] Task: Update GUI and Web Dashboard 54-slot rendering 200dbac
  - [x] Update Fabric History Viewer GUI to display slots 0–53 with half indicators
  - [x] Update Web Dashboard transaction viewer for 54-slot container grids
- [x] Task: Final end-to-end multi-project build verification 200dbac
  - [x] Run `./gradlew test build` across all subprojects
  - [x] Update documentation and conductor registry
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)
