# Specification: Fix Rollback Container Persistence on Fabric & Paper Servers

## Overview

The compensation-based rollback mechanism (invoked via `/chestlog rollback`) computes correct inverse deltas but **fails to persist item changes to actual Minecraft world containers** on both Fabric and Paper dedicated servers. The rollback audit trail logs `ROLLBACK_COMPENSATION` events, giving the false impression that items were restored, while chests remain unchanged. This track fixes all identified root causes across both platforms.

## Root Causes

1. **Fabric**: `RollbackEngine.applyRollback()` mutates only a detached `ContainerSnapshot` — never writes back to the live `net.minecraft.world.Container` or calls `setChanged()`
2. **Paper**: `PaperRollbackExecutor` modifies a `BlockState` snapshot inventory but never calls `container.update(true, true)` to flush changes to the world chunk
3. **Double Chest**: Both platforms grab only one 27-slot half, silently dropping slots 27–53
4. **Paper Stale State**: `BlockState` captured *before* async query completes, so inventory may be stale by execution time
5. **Metadata Loss**: Restored `ItemStack`s are plain vanilla — no component/enchantment data reconstructed from `metadataHash`

## Functional Requirements

### FR-1: Fabric Live Container Write-Back
- Implement a `FabricRollbackExecutor` that applies rollback steps directly to the live `net.minecraft.world.Container` (or `CompoundContainer` for double chests) using `container.setItem()` and `container.setChanged()`.
- The existing `RollbackEngine.applyRollback()` that mutates only a detached `ContainerSnapshot` must be replaced by the new executor in the command handler.

### FR-2: Paper BlockState Persistence
- After modifying the `Inventory` via `setItem()`, call `container.update(true, true)` to flush the `BlockState` snapshot back to the world chunk.
- Re-capture `targetBlock.getState()` on the synchronous server thread *after* the async query completes to prevent stale state.

### FR-3: Double Chest Full-Inventory Resolution
- **Fabric**: Use `ChestBlock.getContainer(state, level, pos, true)` to obtain the full 54-slot `CompoundContainer` instead of the single 27-slot `ChestBlockEntity`.
- **Paper**: Detect `DoubleChest` via `container.getInventory() instanceof DoubleChestInventory` and operate on the full 54-slot inventory.

### FR-4: Component/Metadata Restoration
- When creating restored `ItemStack`s, reconstruct component data (enchantments, custom data) from the stored `metadataHash` / component fingerprint, rather than creating plain vanilla stacks.
- If exact component reconstruction is not possible (hash-only, no stored component blob), log a warning and restore as vanilla with an annotation.

### FR-5: Audit Integrity
- `ROLLBACK_COMPENSATION` events must only be logged *after* successful world container mutation, not before.
- If container mutation fails (e.g., block entity removed mid-rollback), the compensation log must reflect partial application.

## Acceptance Criteria

1. On a **dedicated Fabric server**: `/chestlog rollback` physically restores items to chest inventories visible to all connected players.
2. On a **dedicated Paper server**: `/chestlog rollback` physically restores items to chest inventories visible to all connected players.
3. Double chest rollbacks address all 54 slots on both platforms.
4. Restored items retain component metadata (enchantments, custom data) where stored.
5. `ROLLBACK_COMPENSATION` audit entries accurately reflect actual world mutations.
6. All existing rollback unit tests continue to pass.
7. New integration tests verify live container mutation on both platforms.

## Out of Scope

- Rollback UI/UX changes (chat formatting, confirmation flow)
- Rollback across multiple containers in a single command
- Cross-server rollback synchronization
- Component blob storage (storing full component data instead of hashes) — this is a future enhancement
