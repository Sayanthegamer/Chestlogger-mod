# Implementation Plan: Item Provenance & Chain-of-Custody Engine (`item_provenance_20260817`)

## Phase 1: Core Provenance Domain Model & Graph Resolver (`chestlogger-common`)
- [ ] Task: Phase 1 Test Harness - Graph Cycle & Confidence Scoring Unit Tests
  - [ ] Write unit tests for `ProvenanceGraph`, `ConfidenceLevel`, and cycle-safe graph traversal
- [ ] Task: Implement Domain Models in Common Core
  - [ ] Implement `ProvenanceNode`, `ProvenanceEdge`, `ConfidenceLevel`, and `ProvenanceGraph`
- [ ] Task: Implement `ItemProvenanceResolver` Engine
  - [ ] Implement temporal lookahead/lookbehind search across indexed `.clog` streams, component fingerprint equality, visited event safeguards, and confidence computation
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 2: In-Game Provenance GUI (`chestlogger-paper` & `chestlogger-fabric`)
- [ ] Task: Phase 2 Test Harness - In-Game GUI Layout & Slot Paging Tests
  - [ ] Write unit tests for `PaperProvenanceGuiModel` item icons, lore formatting, and confidence badges
- [ ] Task: Implement 54-Slot In-Game Provenance View & Click Listener
  - [ ] Implement `PaperProvenanceGuiView` and `PaperProvenanceGuiListener` on Paper
- [ ] Task: Register `/chestlog trace` and `/cl trace` Commands
  - [ ] Support `/chestlog trace <x> <y> <z> [slot]` and `/chestlog trace hand` on Paper and Fabric
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 3: Web REST API & Interactive Observability Graph (`chestlogger-common`)
- [ ] Task: Phase 3 Test Harness - Provenance REST API Tests
  - [ ] Write unit tests for `/api/v1/provenance` JSON responses
- [ ] Task: Implement `/api/v1/provenance` in Embedded HTTP Server
  - [ ] Serialize `ProvenanceGraph` into structured JSON nodes and links
- [ ] Task: Implement Web UI Visual Node-Link Graph & Timeline Drawer
  - [ ] Add interactive Canvas/SVG journey visualizer with confidence filters to `index.html`
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 4: Cross-Platform Regression, Benchmark & CI Release Verification
- [ ] Task: Provenance Resolver Performance Benchmark
  - [ ] Implement `ProvenanceResolverBenchmarkTest` verifying sub-50ms execution on 100,000+ transaction segments
- [ ] Task: Cross-Platform Regression Test Suite
  - [ ] Run full test suite across common, fabric, and paper
- [ ] Task: Documentation & Release Bump to `2.3.0`
  - [ ] Update `README.md`, `product.md`, `tech-stack.md`
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)
