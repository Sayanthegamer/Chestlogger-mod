<div align="center">

<img src="docs/banner.png" alt="ChestLogger Hero Banner" width="100%" />

# 📦 ChestLogger

**High-Performance, Zero-Blocking Container Audit & Rollback System for Minecraft 26.2**
### ⚡ Dual Server Releases: Fabric Mod & Paper Plugin

[![Build & Test](https://img.shields.io/github/actions/workflow/status/Sayanthegamer/Chestlogger-mod/ci.yml?branch=master&style=for-the-badge&logo=github&label=Build%20%26%20Test)](https://github.com/Sayanthegamer/Chestlogger-mod/actions)
[![Minecraft Version](https://img.shields.io/badge/Minecraft-26.2-blue?style=for-the-badge&logo=minecraft)](https://papermc.io/)
[![Fabric Loader](https://img.shields.io/badge/Fabric%20Loader-0.19.3%2B-blueviolet?style=for-the-badge)](https://fabricmc.net/)
[![Paper API](https://img.shields.io/badge/Paper-26.2-green?style=for-the-badge)](https://papermc.io/)
[![Java Version](https://img.shields.io/badge/Java-25-orange?style=for-the-badge&logo=openjdk)](https://openjdk.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=for-the-badge)](https://opensource.org/licenses/MIT)

[**Why ChestLogger?**](#-why-chestlogger) • [**Platforms**](#-platform-matrix) • [**Features**](#-features) • [**Commands & Permissions**](#-commands--permissions) • [**Web Dashboard**](#-embedded-web-dashboard--rest-api) • [**Installation**](#-installation) • [**Building**](#-building--compilation)

</div>

---

## ✨ Why ChestLogger?

ChestLogger is an ultra-fast, zero-lag container transaction tracker and anti-griefing system built natively for **Minecraft 26.2** on **Fabric** and **Paper**. It records every player action (pickup, placement, shift-click, drag, hotbar swap, inventory sync) and automation interaction (hoppers, hopper minecarts, droppers) across chests, trapped chests, barrels, double chests, and shulker boxes with zero blocking I/O on the main server thread.

| Feature | 📦 ChestLogger (Fabric & Paper) | 📜 Traditional SQL Loggers |
|---|---|---|
| **Server Tick Impact** | **0 ms** (Lock-Free MPSC Ring Buffer) | Main thread blocking / JDBC pool stalls |
| **Storage Engine** | Unified Append-Only Binary (`.clog` LZ4) | Heavy SQLite / MySQL / PostgreSQL setup |
| **Crash Safety** | Self-Healing Tail CRC32 Checkpoints | Database lock corruptions on SIGKILL / crash |
| **Web Dashboard** | **Zero-Dependency Built-In REST & Web UI** | External PHP/Node web servers required |
| **Rollback Safety** | Non-Destructive Slot Compensation | Hard overwrite (risks deleting newer items) |
| **Low-End HDD Tuning** | Sequential batched writes (Zero seek storms) | Random disk I/O freezes under Aternos / HDD |

---

## 🚀 Platform Matrix

ChestLogger provides two independent, native server platform releases sharing a 100% pure-Java common core:

| Target Platform | Package Artifact | Subproject | Requirements |
|---|---|---|---|
| **Fabric 26.2 Mod** | `chestlogger-fabric-1.0.0.jar` | `:chestlogger-fabric` | Fabric Loader 0.19.3+, Fabric API, Java 25 |
| **Paper 26.2 Plugin** | `chestlogger-paper-1.0.0.jar` | `:chestlogger-paper` | Paper 26.2 Server, Java 25 |

Both platforms share identical binary `.clog` log formatting, spatial index layout (`.cidx`), recovery behavior, and rollback compensation algorithms. Logs generated on a Paper server can be directly inspected or restored on a Fabric server and vice-versa.

---

## 🎮 Commands & Permissions

> 🔒 *On Fabric: All commands require Operator Level 2+.*  
> 🔒 *On Paper: Commands are protected via Bukkit permission nodes.*

| Command | Permission (Paper) | Description |
|---|---|---|
| `/chestlog inspect <X> <Y> <Z> [page]` | `chestlogger.inspect` | Inspect container transaction history at target coords |
| `/chestlog inspect <X> <Y> <Z> <player> [page]` | `chestlogger.inspect` | Filter inspection results by player |
| `/chestlog rollback <X> <Y> <Z> <seconds> [player]` | `chestlogger.rollback` | Calculate and preview rollback compensation plan |
| `/chestlog rollback <X> <Y> <Z> <sec> confirm <token>` | `chestlogger.rollback` | Execute live non-destructive rollback compensation |
| `/chestlog stats` | `chestlogger.stats` | Display real-time queue depth, throughput, and index size |
| `/chestlog purge <days> [confirmToken]` | `chestlogger.purge` | Safe two-step segment cleanup of old audit logs |

---

## 🌐 Embedded Web Dashboard & REST API

To access the browser dashboard:
1. Set `"enabled": true` in `config/chestlogger_web.json` (or plugin data folder).
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

### Fabric Server / Client
1. Download `chestlogger-fabric-1.0.0.jar` from [**Releases**](https://github.com/Sayanthegamer/Chestlogger-mod/releases).
2. Place into your server or client `mods/` directory.
3. Requires Fabric API for 26.2.

### Paper Server
1. Download `chestlogger-paper-1.0.0.jar` from [**Releases**](https://github.com/Sayanthegamer/Chestlogger-mod/releases).
2. Place into your server `plugins/` directory.
3. Restart or reload Paper server.

---

## 🔨 Building & Compilation

### Requirements
- **JDK 25** (`jdk-25.0.4` or newer)
- **Gradle 9.5.1** (Wrapper included)

```bash
# Run all unit, integration, durability, and thread-safety tests across all subprojects
./gradlew test

# Build dual release artifacts (Fabric & Paper)
./gradlew build
```

Compiled binaries are located in:
- `chestlogger-fabric/build/libs/chestlogger-fabric-1.0.0.jar` (Fabric Mod)
- `chestlogger-paper/build/libs/chestlogger-paper-1.0.0.jar` (Paper Plugin)

---

## 📜 License & Community

- **License**: [MIT License](LICENSE) © 2026 Sayanthegamer
- **Code of Conduct**: [Contributor Covenant](CODE_OF_CONDUCT.md)
- **Issues & Support**: [GitHub Issue Tracker](https://github.com/Sayanthegamer/Chestlogger-mod/issues)
