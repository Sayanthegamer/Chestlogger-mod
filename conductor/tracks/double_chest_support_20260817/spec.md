# Specification: Double Chest (54-Slot) Linked Container Support

## 1. Overview
This track implements complete end-to-end support for linked **Double Chests** and **Trapped Double Chests** across both **Fabric 26.2** and **Paper 26.2** server targets. It guarantees unified 54-slot compound inventory tracking, dual-block spatial indexing (inspecting either half retrieves the full combined transaction history), and adaptive rollback execution even if one half of the double chest was broken.

---

## 2. Functional Requirements

### FR-1: Dual-Platform Double Chest Detection & Snapshotting
- **Fabric**: Intercept `DoubleInventory` / compound screen handlers; capture 54-slot snapshots with slot range mapping (0–26 primary/left, 27–53 secondary/right).
- **Paper**: Detect `DoubleChestInventory` and `DoubleChest` inventory holders; snapshot both halves into unified 54-slot transaction frames.

### FR-2: Dual-Block Coordinate Linking & Spatial Indexing
- Transaction logs record the primary coordinate and partner coordinate.
- The `PersistentIndexManager` indexes transactions under both block coordinates (or transparently resolves partner blocks) so `/chestlog inspect <X> <Y> <Z>` on *either* the left or right block returns the complete 54-slot activity log.

### FR-3: Trapped Double Chest Support
- Identical 54-slot compound tracking for both regular `minecraft:chest` and `minecraft:trapped_chest`.

### FR-4: Adaptive Non-Destructive Rollback Execution
- When rolling back a double chest:
  - If both blocks exist: restore all 54 slots into their respective halves non-destructively.
  - If one half is missing/destroyed: adaptively restore valid slots into the surviving half and safely drop or report unplaceable overflow items.

### FR-5: GUI & Web Dashboard 54-Slot Compatibility
- Fabric in-game History Viewer GUI and the Embedded Web Dashboard render slots 0–53 with clear half-indicators (Left Half / Right Half).

---

## 3. Non-Functional Requirements & Performance
- **Zero Main-Thread Blocking**: All coordinate partner resolution and compound indexing occur off-thread in the asynchronous logging pipeline.
- **Binary Compatibility**: Fully backward-compatible with existing `.clog` v1 format.
- **TDD Requirement**: Comprehensive test suites verifying 54-slot delta calculation, dual-coordinate indexing, and partial-block rollback recovery.

---

## 4. Acceptance Criteria
- [ ] Inspecting left or right block of a double chest returns identical complete 54-slot audit history.
- [ ] Shift-clicking and dragging items across the 0–26 and 27–53 boundary generates accurate `SlotDelta` entries.
- [ ] Rollback on a 54-slot double chest correctly restores items to both physical blocks.
- [ ] Breaking one half of a double chest records the event without breaking index consistency.
- [ ] 100% test pass rate across `:chestlogger-common`, `:chestlogger-fabric`, and `:chestlogger-paper`.
