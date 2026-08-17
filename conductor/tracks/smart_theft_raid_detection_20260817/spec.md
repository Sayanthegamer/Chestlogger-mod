# Specification: Smart Theft & Raid Detection Engine (`smart_theft_raid_detection_20260817`)

## 1. Overview
An intelligent, context-aware anti-theft and raid detection engine for Minecraft 26.2 (Fabric & Paper). Distinguishes legitimate cooperative gameplay from real griefing/theft using container placement ownership, explicit player trust lists, owner proximity co-presence, and sliding-window raid velocity heuristics.

## 2. Core Security Heuristics & Incident Classifications
- **Container Ownership Model**:
  - Ground-truth ownership established via `CONTAINER_PLACE` events.
  - World-gen containers (dungeons, villages, mineshafts) default to `UNCLAIMED_NATURAL`.
- **Explicit Trust Model**:
  - `/chestlog trust <player>` and `/chestlog untrust <player>`.
  - Persistent per-player trust lists stored in `chestlogger/trust_data.json`.
- **Incident Classifications**:
  - `CRITICAL_RAID`: Rapid foreign container draining ($\ge 3$ unique container positions within 5 minutes) by an untrusted actor.
  - `OFFLINE_THEFT`: Untrusted actor withdrawing tracked valuables or breaking a container while the owner is **OFFLINE** (High Severity).
  - `ABSENT_OWNER_THEFT`: Untrusted actor withdrawing tracked valuables while the owner is **ONLINE but distant** ($> 200$ blocks away).
  - `CONSENSUAL_PROXIMITY`: Owner is within 24 blocks of actor during withdrawal/break $\implies$ classified as `INFO` and suppressed from alert spam.
- **Safe Staged Rollbacks**:
  - All Web UI and In-Game rollback shortcuts stage a preview requiring explicit confirmation (`/chestlog rollback <pos> <sec> confirm <token>`).

## 3. Multi-Channel Security Telemetry
- **In-Game Admin HUD & Chat**: Online admins with `chestlogger.admin` receive subtle Action-Bar alerts and rich interactive chat notifications with clickable `[Teleport]` and `[Inspect]` buttons.
- **Discord Webhook**: Rich embeds showing exact Owner Status (`Alice: Offline`, `Alice: 1,200m away`, `Alice: Nearby`), Incident Classification, item deltas, and coordinates.
- **Web Dashboard Security Feed**: Live `/api/v1/incidents` feed with staged rollback shortcuts.

## 4. Acceptance Criteria
- [ ] 100% unit test coverage for `TrustManager`, `RaidVelocityTracker`, and `SmartTheftEvaluator`.
- [ ] In-game trust commands (`/chestlog trust`, `/chestlog untrust`, `/chestlog trustlist`) working on Paper and Fabric.
- [ ] In-game admin action-bar HUD and interactive clickable chat pings.
- [ ] Discord webhook embeds with distinct status tags for Offline vs Absent Owner.
- [ ] Web dashboard security incidents feed with confirmation-guarded rollback previews.
