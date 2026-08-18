# Implementation Plan: Unclaimed Natural Containers, Claim System & Trust UX (`unclaimed_natural_and_claims_20260818`)

## Phase 1: Security Core Hardening & `UNCLAIMED_NATURAL` Classification (TDD)
- [ ] Task: Write failing unit tests for `UNCLAIMED_NATURAL` classification and unowned raid burst suppression
  - [ ] Write unit tests for `IncidentClassification.UNCLAIMED_NATURAL` (`isTheft() == false`, `isAlertWorthy() == false`)
  - [ ] Write unit tests for `SmartTheftEvaluator.evaluate()` and `classify()` when `ownerUuid == null`
  - [ ] Write unit tests asserting `RaidVelocityTracker` is not updated when container is unowned
- [ ] Task: Implement `UNCLAIMED_NATURAL` and unowned container gating in `SmartTheftEvaluator`
  - [ ] Add `UNCLAIMED_NATURAL` enum value to `IncidentClassification`
  - [ ] Update `SmartTheftEvaluator.evaluate()` and `classify()` to return `UNCLAIMED_NATURAL` for unowned containers without recording raid velocity bursts
  - [ ] Verify all unit tests pass (Green Phase)
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 2: Claim Management Engine & Persistent Claim Store (TDD)
- [ ] Task: Write unit tests for `ClaimManager` and Double Chest claiming
  - [ ] Write tests for `ClaimManager` registering, unclaiming, checking ownership, and batch claiming nearby containers
  - [ ] Write tests for linked double chest claim propagation (claiming one half claims both)
  - [ ] Write tests for atomic JSON serialization/deserialization of `claims.json`
- [ ] Task: Implement `ClaimManager` in `chestlogger-common`
  - [ ] Implement `ClaimManager` with thread-safe maps, double-chest linking, and JSON persistence
  - [ ] Integrate `ClaimManager` with `FabricSecurityAlertBroadcaster` and `PaperSecurityAlertBroadcaster`
  - [ ] Verify all unit tests pass (Green Phase)
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 3: In-Game Commands, Wand Claiming & Multi-Channel Trust UX
- [ ] Task: Write tests for Claim Commands and One-Click Trust actions
  - [ ] Unit & Integration tests for `/chestlog claim`, `/chestlog unclaim`, and wand claim interactions
  - [ ] Tests for one-click `[Trust]` chat component generation in broadcasters
  - [ ] Tests for Web API `POST /api/v1/trust` endpoint
- [ ] Task: Implement Claim commands & Interactive Wand claiming on Fabric & Paper
  - [ ] Register `/chestlog claim [radius]` and `/chestlog unclaim` in Fabric and Paper command executors
  - [ ] Implement wand sneak-right-click container claiming in `FabricWandListener` and `PaperWandListener`
  - [ ] Add clickable `[Trust Player]` button to `FabricSecurityAlertBroadcaster` and `PaperSecurityAlertBroadcaster`
- [ ] Task: Implement Web UI and REST API One-Click Trust Actions
  - [ ] Add `POST /api/v1/trust` handler in `com.chestlogger.web.HttpApiServer`
  - [ ] Add "Trust Actor" action button to Web UI Incidents table with live feedback
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 4: System Integration, Test Verification & Quality Gates
- [ ] Task: Run automated test suite and verify coverage
  - [ ] Execute `./gradlew test` across all modules
  - [ ] Verify >80% code coverage on new security and claim classes
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)
