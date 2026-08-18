# Specification: Unclaimed Natural Containers, Claim System & Trust UX (`unclaimed_natural_and_claims_20260818`)

## 1. Overview
This track eliminates false theft/raid alerts on unowned world-gen containers (dungeons, mineshafts, villages, ocean ruins) by implementing strict `UNCLAIMED_NATURAL` classification and raid velocity suppression. It also introduces a full container claim management engine (`/chestlog claim`, `/chestlog unclaim`, and interactive wand sneak-right-click claiming) with persistent storage and double chest linking, plus one-click trust shortcuts across in-game chat, Discord, and Web telemetry feeds.

## 2. Functional Requirements

### 2.1 Unclaimed Natural Container Handling (`UNCLAIMED_NATURAL`)
- **Classification**: Introduce `IncidentClassification.UNCLAIMED_NATURAL` where `isTheft() == false` and `isAlertWorthy() == false`.
- **Alert Suppression**: In `SmartTheftEvaluator`, if `ownerUuid == null`, classify interactions as `UNCLAIMED_NATURAL` and do not generate alert-worthy incidents.
- **Raid Burst Bypass**: `RaidVelocityTracker.recordAndCheckBurst()` must NOT record or count unowned container access towards multi-container raid bursts, preventing dungeon crawling from triggering `CRITICAL_RAID`.
- **Audit Logging Preservation**: All transactions on unowned containers remain fully logged to binary storage for `/chestlog inspect` and `/chestlog rollback` history.

### 2.2 Container Claiming Engine (`/chestlog claim`, `/chestlog unclaim`, and Interactive Wand)
- **Command-Based Claiming**:
  - `/chestlog claim`: Raycasts the container targeted at the player's crosshairs (single chest, double chest, barrel, shulker box) and assigns ownership to the executing player.
  - `/chestlog claim <radius>` (Permission: `chestlogger.admin`): Batch claims all containers within `<radius>` blocks (max 32).
  - `/chestlog unclaim`: Clears ownership claim from the targeted container (allowed for container owner or admins).
- **Interactive Wand Claiming**:
  - Sneak + Right-Click with the ChestLogger Wand (or `/chestlog wand` mode) claims the clicked container and notifies the player via action-bar HUD.
- **Double Chest Linking**:
  - Claiming or unclaiming one half of a double chest automatically updates both halves atomically.
- **Persistent Claim Store**:
  - Manual claims are persisted atomically to `chestlogger/claims.json` and populated into memory alongside `CONTAINER_PLACE` logs on startup.

### 2.3 Multi-Channel One-Click Trust UX
- **In-Game Chat Alert Cards**: Clickable `[Trust Player]` button running `/chestlog trust <actorName>` with hover tooltip.
- **Discord Webhook Alerts**: Dynamic trust action notes and command suggestions.
- **Web Admin Dashboard Incidents Feed**: "Trust Actor" button executing `POST /api/v1/trust` with immediate UI confirmation toast.

## 3. Acceptance Criteria
- [ ] World-gen chests with `ownerUuid == null` classify as `UNCLAIMED_NATURAL` and bypass `RaidVelocityTracker` and alert dispatchers.
- [ ] `/chestlog claim` and wand right-click claiming properly assign ownership to single chests, double chests, barrels, and shulker boxes.
- [ ] Ownership persists across server restarts via persistent claim store (`claims.json`).
- [ ] One-click `[Trust]` shortcut works in in-game chat, Web dashboard incident feed, and Discord alerts.
- [ ] 100% test coverage on `SmartTheftEvaluatorTest`, `ClaimManagerTest`, and security broadcaster tests.
