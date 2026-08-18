# Implementation Plan: Repository Housekeeping & Custom GUI Audit

## Phase 1: Repository Housekeeping & Obsolete Code Purge [checkpoint: db084a5]
- [x] Task: Remove legacy root `src/` directory and unreferenced leftover files [db084a5]
  - [x] Delete root `src/main/` and `src/test/` directories
  - [x] Clean up any orphaned root test logs or temporary artifacts
- [x] Task: Verify build and test integrity post-removal [db084a5]
  - [x] Execute `./gradlew test` to ensure zero compilation or classpath breakage
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) [db084a5]

## Phase 2: Fabric Provenance Networking & TDD Unit Tests [checkpoint: 2bb49cc]
- [x] Task: Write unit tests for `ChestLogProvenancePayload` codec (`ChestLogProvenancePayloadTest.java`) [b6cd594]
  - [x] Test roundtrip encoding and decoding of provenance graph metadata and node list
- [x] Task: Implement `ChestLogProvenancePayload.java` and register in `ChestLogNetworking` [2bb49cc]
  - [x] Register clientbound payload type in `ChestLogNetworking.java`
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) [2bb49cc]

## Phase 3: Fabric Client-Side `ChestLogProvenanceScreen` & Command Dispatch
- [x] Task: Implement client-side `ChestLogProvenanceScreen` [3ed2b79]
  - [x] Build custom `Screen` with visual node cards, confidence badges (`[EXACT_LINKAGE]`, `[HIGH_CONFIDENCE]`, `[PROBABLE]`), timestamp formatting, item icons, and pagination
- [x] Task: Register client packet receiver in `ChestLoggerClient.java` [3ed2b79]
  - [x] Open `ChestLogProvenanceScreen` upon receiving `ChestLogProvenancePayload`
- [x] Task: Update `/chestlog trace` on Fabric to dispatch packet to player [3ed2b79]
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 4: System Integration, Test Verification & Quality Gates
- [ ] Task: Run automated test suite across all modules (`./gradlew test --rerun-tasks`)
  - [ ] Execute `./gradlew test` and verify 100% test pass rate
- [ ] Task: Track Completion Review & Verification
  - [ ] Verify all acceptance criteria from `spec.md`
  - [ ] Perform manual and automated verification checkpoint (Refer to workflow.md)
