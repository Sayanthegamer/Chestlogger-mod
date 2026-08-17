<div align="center">

# 📦 ChestLogger

**High-Performance, Server-Authoritative Container Audit & Rollback System for Minecraft Fabric 26.2**

[![Build & Test](https://img.shields.io/github/actions/workflow/status/Sayanthegamer/Chestlogger-mod/ci.yml?branch=master&style=for-the-badge&logo=github&label=Build%20%26%20Test)](https://github.com/Sayanthegamer/Chestlogger-mod/actions)
[![Minecraft Version](https://img.shields.io/badge/Minecraft-26.2-blue?style=for-the-badge&logo=minecraft)](https://fabricmc.net/)
[![Fabric Loader](https://img.shields.io/badge/Fabric%20Loader-0.19.3%2B-blueviolet?style=for-the-badge)](https://fabricmc.net/)
[![Java Version](https://img.shields.io/badge/Java-25-orange?style=for-the-badge&logo=openjdk)](https://openjdk.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=for-the-badge)](https://opensource.org/licenses/MIT)

[**Why ChestLogger?**](#-why-chestlogger) • [**Features**](#-features) • [**Commands**](#-commands--quick-reference) • [**Web Dashboard**](#-embedded-web-dashboard--rest-api) • [**Installation**](#-installation) • [**Building**](#-building--compilation)

</div>

---

## ✨ Why ChestLogger?

ChestLogger is an ultra-fast, zero-lag container transaction tracker and anti-griefing mod built natively for **Minecraft 26.2** on **Fabric**. It records every player action (pickup, placement, shift-click, drag, hotbar swap, inventory sync) and automation interaction (hoppers, hopper minecarts) across chests, trapped chests, barrels, double chests, and shulker boxes with zero blocking I/O on the main server thread.

| Feature | 📦 ChestLogger (Fabric) | 📜 Traditional SQL Loggers |
|---|---|---|
| **Server Tick Impact** | **0 ms** (Lock-Free MPSC Ring Buffer) | Main thread blocking / JDBC pool stalls |
| **Storage Engine** | Native Append-Only Binary (`.clog` LZ4) | Heavy SQLite / MySQL / PostgreSQL setup |
| **Crash Safety** | Self-Healing Tail CRC32 Checkpoints | Database lock corruptions on SIGKILL / crash |
| **Web Dashboard** | **Zero-Dependency Built-In REST & Web UI** | External PHP/Node web servers required |
| **Rollback Safety** | Non-Destructive Slot Compensation | Hard overwrite (risks overwriting newer items) |

---

## 🚀 Features

### 🔍 1. In-Game & CLI Inspection
- Inspect any container instantly by targeting it or using `/chestlog inspect`.
- Paginated transaction history showing **Timestamp**, **Actor**, **Action (+/-)**, **Slot**, and **Item Identifier**.
- Filter queries by **Radius**, **Player Name / UUID**, **Item ID**, and **Time Horizon**.

### ⏪ 2. Safe, Non-Destructive Rollback Engine
- Calculates dry-run compensation plans before executing live modifications.
- Uses a **2-step ephemeral token safeguard** to prevent accidental mass rollbacks.
- Never deletes items: if target slots are occupied, items are safely relocated to free slots.
- Fully auditable `ROLLBACK_COMPENSATION` transaction logs.

### 📊 3. Embedded Web Observability Dashboard & REST API
- Built-in lightweight HTTP server (`com.sun.net.httpserver`) with **zero external dependencies**.
- High-density dark-carbon interface with **Live Auto-Tail streaming**, queue saturation meters, and quick-filter chips.
- Expandable transaction inspector with one-click `/chestlog rollback` generators and raw JSON payload copying.
- Export filtered audit streams directly as **RFC 4180 CSV** or **JSON**.

### ⚡ 4. Append-Only Compressed Binary Storage
- Delta ZigZag VarInt encoding with string dictionary interning.
- Multi-dimensional inverted spatial index (`.cidx`) for instantaneous $O(1)$ block lookups.
- Achieving **< 30 bytes per transaction** with high-density LZ4/Zstd block compression.

---

## 🎮 Commands & Quick Reference

> 🔒 *All administrative commands require Operator Permission (Level 2+).*

```bash
# 1. Inspect container at target coordinates
/chestlog inspect <X> <Y> <Z> [page]

# 2. Inspect with player filter
/chestlog inspect <X> <Y> <Z> <player> [page]

# 3. Dry-run rollback for the last 1 hour (returns a confirmation token)
/chestlog rollback <X> <Y> <Z> 3600 [targetPlayer]

# 4. Confirm and execute live rollback compensation
/chestlog rollback <X> <Y> <Z> 3600 confirm <token>

# 5. View live queue depth, RAM throughput, and index stats
/chestlog stats

# 6. Purge old binary segments with 2-step confirmation
/chestlog purge <days> [confirmToken]
```

---

## 🌐 Embedded Web Dashboard & REST API

To access the browser dashboard:
1. Set `"enabled": true` in `config/chestlogger_web.json`.
2. Open `http://localhost:8080/` in any browser.
3. Authenticate using your generated `secretToken`.

```bash
# Programmatic REST API query via curl:
curl -H "X-ChestLogger-Auth: YOUR_SECRET_TOKEN" \
     "http://localhost:8080/api/v1/query?x=-29&y=64&z=8&dim=minecraft:overworld"
```

| Endpoint | Method | Description |
|---|---|---|
| `/` | `GET` | Serves the single-page Web Admin Dashboard directly from inside the JAR. |
| `/api/v1/health` | `GET` | Health status and authentication verification check. |
| `/api/v1/stats` | `GET` | Real-time queue saturation, throughput, and index metrics. |
| `/api/v1/query` | `GET` | Paginated spatial, temporal, player, and item query engine. |
| `/api/v1/export` | `GET` | Streams filtered transaction logs as RFC 4180 CSV or structured JSON. |

---

## 📦 Installation

1. Download the latest release `.jar` from [**Releases**](https://github.com/Sayanthegamer/Chestlogger-mod/releases).
2. Install **Fabric Loader (0.19.3+)** and **Fabric API** for Minecraft 26.2.
3. Place `chestlogger-1.0.0.jar` into your `.minecraft/mods/` or server `mods/` directory.
4. Launch the game or dedicated server!

---

## 🔨 Building & Compilation

### Requirements
- **JDK 25** (`jdk-25.0.4` or newer)
- **Gradle 9.5.1** (Wrapper included)

```bash
# Run all 255+ automated unit, integration, and security tests
./gradlew check

# Build release mod JAR
./gradlew build
```

Compiled binaries are located in `build/libs/`:
- `chestlogger-1.0.0.jar` (Fabric mod binary)
- `chestlogger-1.0.0-sources.jar`

---

## 📜 License & Community

- **License**: [MIT License](LICENSE) © 2026 Sayanthegamer
- **Code of Conduct**: [Contributor Covenant](CODE_OF_CONDUCT.md)
- **Issues & Support**: [GitHub Issue Tracker](https://github.com/Sayanthegamer/Chestlogger-mod/issues)
