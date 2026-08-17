<div align="center">

<img src="docs/banner.png" alt="ChestLogger Hero Banner" width="100%" />

# 📦 ChestLogger

**High-Performance, Zero-Blocking Container Audit, Rollback, Item Provenance & Smart Theft Detection System for Minecraft 26.2**
### ⚡ Dual Server Releases: Fabric Mod & Paper Plugin

[![Build & Test](https://img.shields.io/github/actions/workflow/status/Sayanthegamer/Chestlogger-mod/ci.yml?branch=master&style=for-the-badge&logo=github&label=Build%20%26%20Test)](https://github.com/Sayanthegamer/Chestlogger-mod/actions)
[![Minecraft Version](https://img.shields.io/badge/Minecraft-26.2-blue?style=for-the-badge&logo=minecraft)](https://papermc.io/)
[![Fabric Loader](https://img.shields.io/badge/Fabric%20Loader-0.19.3%2B-blueviolet?style=for-the-badge)](https://fabricmc.net/)
[![Paper API](https://img.shields.io/badge/Paper-26.2-green?style=for-the-badge)](https://papermc.io/)
[![Java Version](https://img.shields.io/badge/Java-25-orange?style=for-the-badge&logo=openjdk)](https://openjdk.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=for-the-badge)](https://opensource.org/licenses/MIT)

[**Why ChestLogger?**](#-why-chestlogger) • [**Platforms**](#-platform-matrix) • [**Commands & Permissions**](#-commands--permissions) • [**Smart Theft Engine**](#-smart-theft--raid-detection-engine) • [**Item Provenance**](#-item-provenance--chain-of-custody) • [**Discord Alerts**](#-discord-webhook-security-alerts) • [**Web Dashboard**](#-embedded-web-dashboard--rest-api) • [**Installation**](#-installation) • [**Building**](#-building--compilation)

</div>

---

## ✨ Why ChestLogger?

ChestLogger is an ultra-fast, zero-lag container transaction tracker, anti-griefing system, smart theft detection suite, and item provenance engine built natively for **Minecraft 26.2** on **Fabric** and **Paper**. It records every player action (pickup, placement, shift-click, drag, hotbar swap, inventory sync) and automation interaction (hoppers, hopper minecarts, droppers, golems) across chests, trapped chests, barrels, double chests, and shulker boxes with zero blocking I/O on the main server thread.

| Feature | 📦 ChestLogger (Fabric & Paper) | 📜 Traditional SQL Loggers |
|---|---|---|
| **Server Tick Impact** | **0 ms** (Lock-Free MPSC Ring Buffer) | Main thread blocking / JDBC pool stalls |
| **Storage Engine** | Unified Append-Only Binary (`.clog` LZ4) | Heavy SQLite / MySQL / PostgreSQL setup |
| **Crash Safety** | Self-Healing Tail CRC32 Checkpoints | Database lock corruptions on SIGKILL / crash |
| **Smart Theft & Raid Engine** | **Sliding Velocity Burst & Distance Heuristics** | Basic alert spam on any container interaction |
| **Item Provenance** | **Zero-World-Mutation DAG Traversal & Journey Visualizer** | None (Flat tables without custody linking) |
| **Web Dashboard** | **Zero-Dependency Built-In REST & Web UI** | External PHP/Node web servers required |
| **Rollback Safety** | Non-Destructive Slot Compensation & Staged Confirmation | Hard overwrite (risks deleting newer items) |
| **Low-End HDD Tuning** | Sequential batched writes (Zero seek storms) | Random disk I/O freezes under Aternos / HDD |

---

## 🚀 Platform Matrix

ChestLogger provides two independent, native server platform releases sharing a 100% pure-Java common core:

| Target Platform | Package Artifact | Subproject | Requirements |
|---|---|---|---|
| **Fabric 26.2 Mod** | `chestlogger-fabric-2.4.1.jar` | `:chestlogger-fabric` | Fabric Loader 0.19.3+, Fabric API, Java 25 |
| **Paper 26.2 Plugin** | `chestlogger-paper-2.4.1.jar` | `:chestlogger-paper` | Paper 26.2 Server, Java 25 |

Both platforms share identical binary `.clog` log formatting, spatial index layout (`.cidx`), recovery behavior, item provenance resolution, smart theft evaluation, and rollback compensation algorithms. Logs generated on a Paper server can be directly inspected or restored on a Fabric server and vice-versa.

---

## 🎮 Commands & Permissions

> 🔒 *On Fabric: Administrative commands require Operator Level 2+; trust commands are open to all players.*  
> 🔒 *On Paper: Commands are protected via granular Bukkit permission nodes.*  
> 💡 *Aliased as `/cl` or `/chestlog`.*

| Command | Permission (Paper) | Description |
|---|---|---|
| `/chestlog i` (or `/chestlog inspect`) | `chestlogger.inspect` | Toggle click inspection mode (Left-click for chat, Right-click for GUI) |
| `/chestlog wand` | `chestlogger.inspect` | Displays wand item info and quick usage instructions |
| `/chestlog inspect <X> <Y> <Z> [page]` | `chestlogger.inspect` | Inspect container transaction history at target coords |
| `/chestlog inspect <X> <Y> <Z> <player> [page]` | `chestlogger.inspect` | Filter inspection results by player |
| `/chestlog trace <X> <Y> <Z> [slot]` | `chestlogger.inspect` | Reconstruct and visualize the item journey at target container slot |
| `/chestlog trace [hand]` | `chestlogger.inspect` | Reconstruct chain-of-custody for the item held in your main hand |
| `/chestlog trust <player>` | `chestlogger.trust` | Grant trusted access to a teammate to exempt them from theft alerts |
| `/chestlog untrust <player>` | `chestlogger.trust` | Revoke container trust from a player |
| `/chestlog trustlist` | `chestlogger.trust` | View all players currently in your trust list |
| `/chestlog rollback <X> <Y> <Z> <seconds> [player]` | `chestlogger.rollback` | Calculate and preview rollback compensation plan |
| `/chestlog rollback <X> <Y> <Z> <sec> confirm <token>` | `chestlogger.rollback` | Execute live non-destructive rollback compensation |
| `/chestlog stats` | `chestlogger.stats` | Display real-time queue depth, throughput, and index size |
| `/chestlog purge <days> [confirmToken]` | `chestlogger.purge` | Safe two-step segment cleanup of old audit logs |

---

## 🛡️ Smart Theft & Raid Detection Engine

ChestLogger 2.4.1 features an intelligent, context-aware anti-theft and raid detection engine that distinguishes legitimate cooperative gameplay from malicious griefing and looting in sub-20ms latency.

### Core Security Heuristics:
- **Container Ownership Model**: Ground-truth container ownership is automatically established via `CONTAINER_PLACE` transactions. Unclaimed world-gen containers default to wilderness.
- **Directional Player Trust Graphs**: Persistent per-player trust lists stored in `trust_data.json`. Trusted teammates can share items freely without triggering administrative alarms.
- **Sliding-Window Raid Velocity Tracking**: Dynamically monitors multi-container extraction velocity. When an actor touches $\ge 3$ distinct container positions within a 300-second window, a `CRITICAL_RAID` alert is immediately triggered.
- **Contextual Owner Presence & Distance States**:
  - `CRITICAL_RAID`: Rapid multi-container raid burst detected across multiple locations.
  - `OFFLINE_THEFT`: Valuable extraction or container destruction while the container owner is **🔴 Offline**.
  - `ABSENT_OWNER_THEFT`: Valuable extraction while the container owner is **🟡 Absent** (>24 blocks away).
  - `CONSENSUAL_PROXIMITY`: Interaction occurring while the owner is **🟢 Nearby** ($\le 24$ blocks away) $\implies$ classified as consensual trading and suppressed from alert spam.
- **Real-Time In-Game Admin Telemetry**: Online admins receive Action-Bar HUD notifications and interactive chat alert cards with clickable `[Teleport]` and `[Inspect]` actions.

---

## 🔍 Item Provenance & Chain-of-Custody

ChestLogger includes a zero-world-mutation **Item Provenance Engine** capable of resolving full chains of custody across container deposits, withdrawals, player handoffs, and automation routes in sub-50ms latency.

### Key Capabilities:
- **Non-Fungible 64-Bit Component Fingerprinting**: Uniquely tracks valuable gear (Elytra, Netherite Armor, Enchanted Tools, Shulker Boxes) across world interactions by component equality without modifying item NBT.
- **Commodity Temporal Flow Analysis**: Probabilistically reconstructs the movement of bulk fungible commodities (Diamonds, Iron, Gold) using spatial distance and temporal proximity analysis ($\Delta t$).
- **Multi-Tier Confidence Scoring**:
  - `EXACT_LINKAGE` (100%): Exact component fingerprint match or unbroken direct player custody.
  - `HIGH_CONFIDENCE` (80–95%): Tight temporal and quantity custody transitions across nearby containers or automation.
  - `PROBABLE` (50–75%): Plausible spatial/temporal movement across multiple candidate transfer points.
- **In-Game 54-Slot Interactive Journey GUI**: Chronological step-by-step display with confidence badges, actor tags, delta quantities, time deltas, and coordinates.
- **Web UI Journey Visualizer**: Interactive node-link graph with zooming, panning, confidence filtering, and a slide-out step inspection drawer.

---

## 🔔 Discord Webhook Security Alerts

ChestLogger features real-time, non-blocking suspicious activity detection with Discord webhook rich embed dispatch.

1. Automatically generates `config/chestlogger_alerts.json` (or plugin data folder).
2. Configure your webhook URL and thresholds:
```json
{
  "enabled": true,
  "webhookUrl": "https://discord.com/api/webhooks/...",
  "botUsername": "ChestLogger Security Bot",
  "quantityThreshold": 32,
  "alertOnContainerBreak": true,
  "alertOnValuableTheft": true,
  "rateLimitPerMinute": 30,
  "valuableItems": [
    "minecraft:diamond",
    "minecraft:netherite_ingot",
    "minecraft:elytra",
    "minecraft:beacon"
  ]
}
```
3. Webhook embeds feature classification-specific coloring (Crimson for `CRITICAL_RAID` and `OFFLINE_THEFT`, Orange for `ABSENT_OWNER_THEFT`), full item deltas, coordinates, and dynamic **Owner Presence Badges**:
   - `🔴 Offline`: Owner is disconnected from the server.
   - `🟡 Absent (~240m away)`: Owner is online elsewhere in the world.
   - `🟢 Nearby (~8m away)`: Owner is co-present nearby.

---

## 🌐 Embedded Web Dashboard & REST API

To access the browser dashboard:
1. Set `"enabled": true` in `config/chestlogger_web.json` (or plugin data folder).
2. Open `http://localhost:8080/` in any browser.
3. Authenticate using your generated `secretToken`.

### Web UI Security Features:
- **Live Incident Feed**: Real-time stream of security incidents (`/api/v1/incidents`) with classification badges, owner presence tags, and quick-filter buttons.
- **Staged Rollback Modal**: Safe, two-step compensation rollback preview dialog with generated confirmation safety tokens before applying world modifications.

```bash
# Fetch recent security incidents via REST API:
curl -H "X-ChestLogger-Auth: YOUR_SECRET_TOKEN" \
     "http://localhost:8080/api/v1/incidents?classification=CRITICAL_RAID&limit=50"

# Query container transaction logs:
curl -H "X-ChestLogger-Auth: YOUR_SECRET_TOKEN" \
     "http://localhost:8080/api/v1/query?x=-29&y=64&z=8&dim=minecraft:overworld"

# Resolve item provenance graph via REST API:
curl -H "X-ChestLogger-Auth: YOUR_SECRET_TOKEN" \
     "http://localhost:8080/api/v1/provenance?x=100&y=64&z=-200&dim=minecraft:overworld&item=minecraft:diamond"
```

| Endpoint | Method | Description |
|---|---|---|
| `/` | `GET` | Serves the single-page Web Admin Dashboard directly from inside the JAR. |
| `/api/v1/health` | `GET` | Health status and authentication verification check. |
| `/api/v1/stats` | `GET` | Real-time queue saturation, throughput, and index metrics. |
| `/api/v1/incidents` | `GET` | Bounded stream of evaluated security incidents with filters. |
| `/api/v1/query` | `GET` | Paginated spatial, temporal, player, and item query engine. |
| `/api/v1/provenance` | `GET` | Reconstructs directed item provenance graph with nodes, edges, and confidence levels. |
| `/api/v1/export` | `GET` | Streams filtered transaction logs as RFC 4180 CSV or structured JSON. |

---

## 📦 Installation

### Fabric Server / Client
1. Download `chestlogger-fabric-2.4.1.jar` from [**Releases**](https://github.com/Sayanthegamer/Chestlogger-mod/releases).
2. Place into your server or client `mods/` directory.
3. Requires Fabric API for 26.2.

### Paper Server
1. Download `chestlogger-paper-2.4.1.jar` from [**Releases**](https://github.com/Sayanthegamer/Chestlogger-mod/releases).
2. Place into your server `plugins/` directory.
3. Restart or reload Paper server.

---

## 🔨 Building & Compilation

### Requirements
- **JDK 25** (`jdk-25.0.4` or newer)
- **Gradle 9.5.1** (Wrapper included)

```bash
# Run all unit, integration, durability, and benchmark tests across all subprojects
./gradlew test

# Build dual release artifacts (Fabric & Paper)
./gradlew build
```

Compiled binaries are located in:
- `chestlogger-fabric/build/libs/chestlogger-fabric-2.4.1.jar` (Fabric Mod)
- `chestlogger-paper/build/libs/chestlogger-paper-2.4.1.jar` (Paper Plugin)

---

## 📜 License & Community

- **License**: [MIT License](LICENSE) © 2026 Sayanthegamer
- **Code of Conduct**: [Contributor Covenant](CODE_OF_CONDUCT.md)
- **Issues & Support**: [GitHub Issue Tracker](https://github.com/Sayanthegamer/Chestlogger-mod/issues)
