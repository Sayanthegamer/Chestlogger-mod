# Specification: Item Provenance & Chain-of-Custody Engine (`item_provenance_20260817`)

## 1. Overview
A zero-world-mutation, non-invasive Item Provenance & Chain-of-Custody Engine for Minecraft 26.2 (Fabric & Paper). Reconstructs and visualizes the complete 'Life Journey' of an item across container deposits/withdrawals, player thefts, ground drops, golem/hopper transfers, and block destructions.

## 2. Core Principles & Refinements
- **Zero-World-Mutation Model**: World saves and item NBT are 100% vanilla-unmodified (preserving vanilla stack merging). DAG computation is strictly asynchronous off-thread.
- **Component Fingerprint Matching**: 64-bit `metadataFingerprint` identifies component equality (enchantments, repair cost, trims, shulker contents).
- **Explicit Heuristic Confidence Scoring**:
  - `EXACT_LINKAGE` (100%): Direct continuous custody transfer by single actor.
  - `HIGH_CONFIDENCE` (80-95%): Item withdrawal immediately followed by deposit into nearby container within temporal threshold $\Delta t$.
  - `PROBABLE` (50-75%): Stack split/merge or multiple candidates in close temporal proximity.
- **Cycle-Safe Directed Event Graph**: Traversal handles spatial cycles (Chest A $\to$ Chest B $\to$ Chest A) with visited event IDs, maximum search depth (default: 50 hops), and bounded temporal windows.
- **Unified Provenance Model**: A single shared `ProvenanceGraph` model in `chestlogger-common` consumed identically by:
  - In-Game 54-Slot GUI (`PaperProvenanceGuiView` & Fabric Screen).
  - Web Admin Observability Graph (`/api/v1/provenance`).

## 3. Interactive Scopes & Commands
- `/chestlog trace <x> <y> <z> [slot]` and `/chestlog trace hand` (also `/cl trace`).
- In-Game GUI displays confidence badges, actor tags, step numbers, delta quantities, time deltas, and coordinates.
- Web UI renders interactive node-link graph with zoom, confidence filters, and step inspection drawer.

## 4. Acceptance Criteria
- [x] 100% test coverage for graph cycle protection, confidence scoring, and temporal lookahead/lookbehind.
- [x] In-game GUI displays accurate chronological cards with click safety.
- [x] Web REST API `/api/v1/provenance` returns valid structured node-link payloads.
- [x] Worst-case performance benchmark on 100,000+ transaction segments executes within < 50ms without main thread impact.
