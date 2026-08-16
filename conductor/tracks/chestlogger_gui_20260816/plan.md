# Implementation Plan: ChestLogger Phase 11 History GUI (Minecraft 26.2)

## Phase 1: Networking Protocol & Custom Payloads (TDD) [checkpoint: 3794cde]
- [x] Task: Write unit tests for `ChestLogPagePayload` and `ChestLogPageRequestPayload` serialization and round-trip decoding. (3794cde)
- [x] Task: Implement payload records (`ChestLogPagePayload`, `ChestLogPageRequestPayload`, `DisplayRecord`, `ContainerHeader`). (3794cde)
- [x] Task: Register payload types in `PayloadTypeRegistry.clientboundPlay()` and `serverboundPlay()` for Fabric 26.2. (3794cde)
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) (3794cde)

## Phase 2: Server-Side Query Session Manager & Command Integration
- [x] Task: Write unit tests for `QuerySessionManager` (session expiration, bounded page slicing, permission validation). (cdf5fa7)
- [x] Task: Implement `QuerySessionManager` integrating with `QueryEngine` to serve bounded 25-record pages. (cdf5fa7)
- [x] Task: Connect `/chestlog inspect` to dispatch initial `ChestLogPagePayload` when invoked by players. (cdf5fa7)
- [x] Task: Implement serverbound receiver handling `ChestLogPageRequestPayload` pagination and filter requests. (cdf5fa7)
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 3: Client GUI Foundation & Modular Layout
- [ ] Task: Implement `ChestLogScreen extends Screen` with responsive dimensions, title, and container metadata header.
- [ ] Task: Implement `ChestLogPaginationWidget` supporting first/prev/next/last controls with boundary disabling.
- [ ] Task: Implement `ChestLogFilterWidget` with search inputs for player name and item identifier.
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 4: Real Item Icon Resolution & Rich Log Row Rendering
- [ ] Task: Implement client item resolver binding logged item identifiers to `ItemStack` via `BuiltInRegistries.ITEM`.
- [ ] Task: Implement `ChestLogEntryWidget` rendering timestamp, actor badge, action type, slot, and color-coded signed quantities.
- [ ] Task: Implement real item icon drawing with `guiGraphics.item()` and hover tooltip rendering (`guiGraphics.setTooltipForNextFrame`).
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 5: Interactive Pagination, Filtering & End-to-End Hardening
- [ ] Task: Wire client pagination and filter interactions to `ClientPlayNetworking.send(ChestLogPageRequestPayload)`.
- [ ] Task: Implement client receiver updating `ChestLogScreen` state on new incoming `ChestLogPagePayload`.
- [ ] Task: Implement integration tests validating permission checks, malformed payload rejections, and zero thread leaks.
- [ ] Task: Run full regression test suite (Phases 1 through 11).
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)
