# Implementation Plan: Unclaimed Natural Containers, Claim System & Trust UX (`unclaimed_natural_and_claims_20260818`)

## Phase 1: Security Core Hardening & `UNCLAIMED_NATURAL` Classification (TDD) [checkpoint: edc1884]
- [x] Task: Write failing unit tests for `UNCLAIMED_NATURAL` classification and unowned raid burst suppression [843dbfd]
  - [x] Write unit tests for `IncidentClassification.UNCLAIMED_NATURAL` (`isTheft() == false`, `isAlertWorthy() == false`)
  - [x] Write unit tests for `SmartTheftEvaluator.evaluate()` and `classify()` when `ownerUuid == null`
  - [x] Write unit tests asserting `RaidVelocityTracker` is not updated when container is unowned
- [x] Task: Implement `UNCLAIMED_NATURAL` and unowned container gating in `SmartTheftEvaluator` [edc1884]
  - [x] Add `UNCLAIMED_NATURAL` enum value to `IncidentClassification`
  - [x] Update `SmartTheftEvaluator.evaluate()` and `classify()` to return `UNCLAIMED_NATURAL` for unowned containers without recording raid velocity bursts
  - [x] Verify all unit tests pass (Green Phase)
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) [edc1884]

## Phase 2: Claim Management Engine & Persistent Claim Store (TDD) [checkpoint: dc19277]
- [x] Task: Write unit tests for `ClaimManager` and Double Chest claiming [bf7a9b2]
  - [x] Write tests for `ClaimManager` registering, unclaiming, checking ownership, and batch claiming nearby containers
  - [x] Write tests for linked double chest claim propagation (claiming one half claims both)
  - [x] Write tests for atomic JSON serialization/deserialization of `claims.json`
- [x] Task: Implement `ClaimManager` in `chestlogger-common` [dc19277]
  - [x] Implement `ClaimManager` with thread-safe maps, double-chest linking, and JSON persistence
  - [x] Integrate `ClaimManager` with `FabricSecurityAlertBroadcaster` and `PaperSecurityAlertBroadcaster`
  - [x] Verify all unit tests pass (Green Phase)
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) [dc19277]

## Phase 3: In-Game Commands, Wand Claiming & Multi-Channel Trust UX
- [x] Task: Write tests for Claim Commands and One-Click Trust actions [a745cc8]
  - [x] Unit & Integration tests for `/chestlog claim`, `/chestlog unclaim`, and wand claim interactions
  - [x] Tests for one-click `[Trust]` chat component generation in broadcasters
  - [x] Tests for Web API `POST /api/v1/trust` endpoint
- [x] Task: Implement Claim commands & Interactive Wand claiming on Fabric & Paper [6f25fc3]
  - [x] Register `/chestlog claim [radius]` and `/chestlog unclaim` in Fabric and Paper command executors
  - [x] Implement wand sneak-right-click container claiming in `FabricWandListener` and `PaperWandListener`
  - [x] Add clickable `[Trust Player]` button to `FabricSecurityAlertBroadcaster` and `PaperSecurityAlertBroadcaster`
- [x] Task: Implement Web UI and REST API One-Click Trust Actions [7e10c06]
  - [x] Add `POST /api/v1/trust` handler in `com.chestlogger.web.HttpApiServer`
  - [x] Add "Trust Actor" action button to Web UI Incidents table with live feedback
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 4: System Integration, Test Verification & Quality Gates
- [ ] Task: Run automated test suite and verify coverage
  - [ ] Execute `./gradlew test` across all modules
  - [ ] Verify >80% code coverage on new security and claim classes
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)
