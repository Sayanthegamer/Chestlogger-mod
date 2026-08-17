# Implementation Plan: Smart Theft & Raid Detection Engine (`smart_theft_raid_detection_20260817`)

## Phase 1: Core Trust & Smart Heuristic Security Engine (`chestlogger-common`)
- [ ] Task: Phase 1 Test Harness - Trust & Smart Theft Unit Tests
  - [ ] Write unit tests for `TrustManager`, `RaidVelocityTracker`, and `SmartTheftEvaluator`
- [ ] Task: Implement Trust & Ownership Domain Models
  - [ ] Implement `TrustManager`, `IncidentClassification`, `SecurityIncident`, and `OwnerPresenceState`
- [ ] Task: Implement `SmartTheftEvaluator` & `RaidVelocityTracker`
  - [ ] Implement sliding 300s multi-container velocity tracking, `OFFLINE_THEFT` vs `ABSENT_OWNER_THEFT` separation, and proximity mitigation
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 2: In-Game Trust Commands & Admin HUD Telemetry (`chestlogger-paper` & `chestlogger-fabric`)
- [ ] Task: Phase 2 Test Harness - Trust Commands & Alert Listener Tests
  - [ ] Write unit tests for trust command parsing and permission checks
- [ ] Task: Implement Player Trust Commands
  - [ ] Support `/chestlog trust <player>`, `/chestlog untrust <player>`, and `/chestlog trustlist` on Paper and Fabric
- [ ] Task: Implement Real-Time Admin Action-Bar & Clickable Chat Alerts
  - [ ] Broadcast Action-Bar HUD notice and interactive `[Teleport]` / `[Inspect]` chat cards to online admins
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 3: Discord Rich Embeds & Web Dashboard Incidents Feed (`chestlogger-common`)
- [ ] Task: Phase 3 Test Harness - Discord Embed & Incidents REST API Tests
  - [ ] Write unit tests for upgraded `DiscordEmbedBuilder` and `/api/v1/incidents` endpoint
- [ ] Task: Upgrade `DiscordAlertDispatcher` with Smart Context Badges
  - [ ] Add Owner Presence badges (`Offline`, `Absent`, `Nearby`) and severity-based coloring
- [ ] Task: Implement Web UI Security Incidents Feed & Staged Rollback Preview Modal
  - [ ] Add live incidents feed and staged rollback confirmation dialog in `index.html`
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 4: Cross-Platform Regression, Concurrent Stress Tests & CI Release
- [ ] Task: Concurrent Heuristic Stress Test
  - [ ] Implement `SmartTheftConcurrencyTest` verifying zero memory leaks under 500 simultaneous player actions
- [ ] Task: Cross-Platform Regression Test Suite
  - [ ] Run full test suite across common, fabric, and paper
- [ ] Task: Documentation & Release Bump to `2.4.0`
  - [ ] Update `README.md`, `product.md`, `tech-stack.md`
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)
