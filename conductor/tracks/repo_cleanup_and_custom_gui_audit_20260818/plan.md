# Implementation Plan: Repository Housekeeping & Custom GUI Audit

## Phase 1: Repository Housekeeping & Obsolete Code Purge
- [ ] Task: Remove legacy root `src/` directory and unreferenced leftover files
  - [ ] Delete root `src/main/` and `src/test/` directories
  - [ ] Clean up any orphaned root test logs or temporary artifacts
- [ ] Task: Verify build and test integrity post-removal
  - [ ] Execute `./gradlew test` to ensure zero compilation or classpath breakage
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 2: Fabric Provenance Networking & TDD Unit Tests
- [ ] Task: Write unit tests for `ChestLogProvenancePayload` codec (`ChestLogProvenancePayloadTest.java`)
  - [ ] Test roundtrip encoding and decoding of provenance graph metadata and node list
- [ ] Task: Implement `ChestLogProvenancePayload.java` and register in `ChestLogNetworking`
  - [ ] Register clientbound payload type in `ChestLogNetworking.java`
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 3: Fabric Client-Side `ChestLogProvenanceScreen` & Command Dispatch
- [ ] Task: Implement client-side `ChestLogProvenanceScreen`
  - [ ] Build custom `Screen` with visual node cards, confidence badges (`[EXACT_LINKAGE]`, `[HIGH_CONFIDENCE]`, `[PROBABLE]`), timestamp formatting, item icons, and pagination
- [ ] Task: Register client packet receiver in `ChestLoggerClient.java`
  - [ ] Open `ChestLogProvenanceScreen` upon receiving `ChestLogProvenancePayload`
- [ ] Task: Update `/chestlog trace` on Fabric to dispatch packet to player
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 4: System Integration, Test Verification & Quality Gates
- [ ] Task: Run automated test suite across all modules (`./gradlew test --rerun-tasks`)
  - [ ] Execute `./gradlew test` and verify 100% test pass rate
- [ ] Task: Track Completion Review & Verification
  - [ ] Verify all acceptance criteria from `spec.md`
  - [ ] Perform manual and automated verification checkpoint (Refer to workflow.md)
