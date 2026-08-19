# Implementation Plan: Fix Rollback Container Persistence on Fabric & Paper Servers

## Phase 1: Fabric Live Container Rollback Executor

- [ ] Task: Write failing tests for `FabricRollbackExecutor` that verify items are set on a mock `Container` via `setItem()` and `setChanged()` is called
  - [ ] Test: Positive delta (item restoration) writes `ItemStack` to correct slot
  - [ ] Test: Negative delta (item removal) sets AIR to slot
  - [ ] Test: `setChanged()` is called after mutations
  - [ ] Test: Audit `ROLLBACK_COMPENSATION` entry is enqueued only after successful mutation
- [ ] Task: Implement `FabricRollbackExecutor` in `chestlogger-fabric` that operates on live `Container` instances
- [ ] Task: Refactor `ChestLoggerCommands.executeRollbackConfirm()` to use `FabricRollbackExecutor` instead of `RollbackEngine.applyRollback()` with detached snapshot
- [ ] Task: Refactor `ChestLoggerCommands.executeRollbackPreview()` to resolve full double-chest `CompoundContainer` via `ChestBlock.getContainer()`
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 2: Paper BlockState Persistence & Stale State Fix

- [ ] Task: Write failing tests for Paper rollback persistence verifying `container.update(true, true)` is invoked
  - [ ] Test: `BlockState.update(true, true)` is called after inventory modification
  - [ ] Test: Fresh `BlockState` is captured on sync thread after async query
- [ ] Task: Fix `PaperCommandExecutor.handleRollback()` to re-capture `BlockState` on the sync server thread after async query completes
- [ ] Task: Fix `PaperRollbackExecutor.execute()` — caller must invoke `container.update(true, true)` after execution
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 3: Double Chest Full 54-Slot Resolution

- [ ] Task: Write failing tests for double-chest resolution on both platforms
  - [ ] Test (Fabric): `ChestBlock.getContainer()` returns 54-slot `CompoundContainer`
  - [ ] Test (Paper): `DoubleChestInventory` detection returns full 54-slot inventory
- [ ] Task: Implement Fabric double-chest resolution in rollback command using `ChestBlock.getContainer(state, level, pos, true)`
- [ ] Task: Implement Paper double-chest resolution via `DoubleChestInventory` detection in `PaperCommandExecutor`
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 4: Component/Metadata Restoration

- [ ] Task: Write failing tests for metadata-aware `ItemStack` reconstruction
  - [ ] Test: Restored items carry component data when fingerprint matches stored components
  - [ ] Test: Warning logged when component reconstruction impossible (hash-only, no blob)
- [ ] Task: Implement component restoration lookup in `FabricRollbackExecutor` using `MetadataFingerprint` matching against original transaction log component data
- [ ] Task: Implement component restoration in `PaperRollbackExecutor` for Bukkit `ItemMeta`
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 5: Audit Integrity & Final Integration Tests

- [ ] Task: Write test verifying `ROLLBACK_COMPENSATION` is only logged after confirmed world mutation
- [ ] Task: Write test verifying partial rollback (e.g., block entity removed mid-rollback) logs partial application correctly
- [ ] Task: Write end-to-end integration test covering full rollback flow on both platforms
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)
