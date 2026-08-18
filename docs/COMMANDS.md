# 📋 ChestLogger — Master Command Sheet & Placeholders Reference

> **The Definitive Cheat Sheet for Players, Builders, Moderators & Administrators**  
> *Compatible with Fabric Mod & Paper Plugin for Minecraft 26.2*  
> *Base Command Aliases: `/chestlog` or `/cl`*

---

## 📑 Quick Navigation
1. [Placeholder Legend (Read This First!)](#1-placeholder-legend-read-this-first)
2. [Player & Builder Commands (No Permissions Required)](#2-player--builder-commands-no-permissions-required)
3. [Moderator & Investigation Commands](#3-moderator--investigation-commands)
4. [Admin & Rollback Commands](#4-admin--rollback-commands)
5. [Configuration & Settings Commands](#5-configuration--settings-commands)
6. [Time & Duration Converter](#6-time--duration-converter)
7. [Common Mistakes & Troubleshooting](#7-common-mistakes--troubleshooting)

---

## 1. Placeholder Legend (Read This First!)

When reading commands in this manual:
* `<angle_brackets>` = **REQUIRED** argument (you MUST provide this).
* `[square_brackets]` = **OPTIONAL** argument (if omitted, default values are used).
* `|` = **OR** (choose one of the listed options).

### 🏷️ Placeholder Dictionary:
| Placeholder | Type / Format | Allowed Range / Example | Description |
|---|---|---|---|
| `<player>` | Text (Username) | `Steve`, `Alex`, `Sayanthegamer` | The exact Minecraft player username. Tab-completion works automatically! |
| `<x> <y> <z>` | Integer Coordinates | `120 64 -300` or `~ ~ ~` | The block coordinates in the world of the target container. |
| `[page]` | Integer Number | `1`, `2`, `3`... *(Default: 1)* | The page number to view when container history has many entries. |
| `[slot]` | Integer Number | `0` to `53` *(Default: 0)* | Specific slot index inside a container (Chest = 0–26, Double Chest = 0–53). |
| `[radius]` | Integer Number | `1` to `32` blocks | Radius around the player for admin batch claiming. |
| `<seconds>` | Integer Number | `60`, `3600`, `86400` | Time window into the past in seconds for rollback calculation. |
| `<token>` | 6-Character String | `a3f9b2`, `7c01e9` | The random temporary safety confirmation code generated during rollback preview. |
| `<days>` | Integer Number | `1` to `3650` | Number of days of log history to keep when purging old segments. |
| `<key>` | Setting Key Name | `webhook`, `cooldown`, `hud` | The configuration setting key to query or modify. |
| `<value>` | Value | `true`, `false`, `30`, `https://...` | The new value to set for a configuration key. |

---

## 2. Player & Builder Commands (No Permissions Required)

These commands can be run by **ANY player** on the server to protect their chests, manage friends, and inspect their own storage.

### 🛡️ Container Claiming
| Command Syntax | Who Can Run | Description | Example Command |
|---|---|---|---|
| `/chestlog claim` | Player | Claims ownership of the container you are currently looking at (up to 6 blocks away). | `/chestlog claim` |
| `/chestlog unclaim` | Player | Removes your claim from the container you are looking at. | `/chestlog unclaim` |

---

### 🤝 Friend Trust System
| Command Syntax | Who Can Run | Description | Example Command |
|---|---|---|---|
| `/chestlog trust <player>` | Player | Adds `<player>` to your trust list so they can withdraw from your chests without triggering alerts. | `/chestlog trust Alex` |
| `/chestlog untrust <player>` | Player | Removes `<player>` from your trust list. | `/chestlog untrust Alex` |
| `/chestlog trustlist` | Player | Displays a list of all players you currently trust. | `/chestlog trustlist` |

---

### 🔍 Self-Inspection & Item Tracing
| Command Syntax | Who Can Run | Description | Example Command |
|---|---|---|---|
| `/chestlog i` *(or `/cl i`)* | Player | Toggles inspection click mode (Left-click container for Chat, Right-click for GUI). | `/chestlog i` |
| `/chestlog trace` *(or `trace hand`)* | Player | Traces the full provenance/custody history of the item held in your main hand. | `/chestlog trace` |

---

## 3. Moderator & Investigation Commands

These commands are used by **staff & moderators** to investigate griefing, check historical logs, and track stolen goods.  
*Paper Permission:* `chestlogger.inspect` (or `chestlogger.admin`)  
*Fabric:* Operator Level 2+

### 🔎 Coordinate & Filtered Inspection
| Command Syntax | Who Can Run | Description | Example Command |
|---|---|---|---|
| `/chestlog inspect <x> <y> <z>` | Player / Console | Views recent transaction logs for container at `<x> <y> <z>`. | `/chestlog inspect 120 64 -300` |
| `/chestlog inspect <x> <y> <z> [page]` | Player / Console | Views a specific page of transactions at `<x> <y> <z>`. | `/chestlog inspect 120 64 -300 2` |
| `/chestlog inspect <x> <y> <z> <player>` | Player / Console | Filters history at `<x> <y> <z>` to only show actions by `<player>`. | `/chestlog inspect 120 64 -300 ThiefX` |
| `/chestlog inspect <x> <y> <z> <player> [page]` | Player / Console | Filters history at `<x> <y> <z>` for `<player>` on page `[page]`. | `/chestlog inspect 120 64 -300 ThiefX 2` |
| `/chestlog wand` | Player / Console | Shows wand item info and usage tips. | `/chestlog wand` |

---

### 🕵️ Item Provenance & Slot Tracing
| Command Syntax | Who Can Run | Description | Example Command |
|---|---|---|---|
| `/chestlog trace <x> <y> <z>` | Player / Console | Traces the origin of the item in **Slot 0** of container at `<x> <y> <z>`. | `/chestlog trace 120 64 -300` |
| `/chestlog trace <x> <y> <z> <slot>` | Player / Console | Traces the origin of the item in specific **Slot `<slot>`** of container at `<x> <y> <z>`. | `/chestlog trace 120 64 -300 14` |

---

## 4. Admin & Rollback Commands

These commands allow administrators to execute **safe, non-destructive rollbacks**, view server telemetry, and purge old logs.  
*Paper Permissions:* `chestlogger.rollback`, `chestlogger.stats`, `chestlogger.purge`, `chestlogger.web`  
*Fabric:* Operator Level 2+

### ⏪ Two-Stage Staged Rollback System
> ⚠️ **Notice:** Rollbacks require 2 steps so you can preview changes and avoid accidental overwrites!

| Step | Command Syntax | Description | Real Example |
|---|---|---|---|
| **Step 1: Preview** | `/chestlog rollback <x> <y> <z> <seconds> [player]` | Analyzes the past `<seconds>` and generates a 6-character safety token. | `/chestlog rollback 120 64 -300 3600 ThiefX` |
| **Step 2: Confirm** | `/chestlog rollback <x> <y> <z> <seconds> confirm <token>` | Applies the compensation plan using the token generated in Step 1. | `/chestlog rollback 120 64 -300 3600 confirm a3f9b2` |

---

### 📊 Telemetry, Claim Radius & Maintenance
| Command Syntax | Permission | Description | Example Command |
|---|---|---|---|
| `/chestlog claim <radius>` | `chestlogger.admin` | Batch-claims all containers within a `<radius>` (1–32) block radius. | `/chestlog claim 15` |
| `/chestlog stats` | `chestlogger.stats` | Displays live telemetry: queue saturation, enqueued/drained counts, index size. | `/chestlog stats` |
| `/chestlog purge <days>` | `chestlogger.purge` | **Step 1:** Generates a confirmation token to delete logs older than `<days>`. | `/chestlog purge 90` |
| `/chestlog purge <days> <token>` | `chestlogger.purge` | **Step 2:** Permanently deletes log segments older than `<days>`. | `/chestlog purge 90 c81e4b` |
| `/chestlog web [start\|stop]` | `chestlogger.web` | *(Paper)* Checks status or toggles the embedded Web Admin Dashboard. | `/chestlog web start` |

---

## 5. Configuration & Settings Commands

Admins can customize ChestLogger in real-time without restarting the server.  
*Paper Permission:* `chestlogger.admin` | *Fabric:* Op Level 2+

| Command Syntax | Description | Example Command |
|---|---|---|
| `/chestlog config` *(or `/cl settings`)* | Opens the interactive In-Game Configuration GUI (Players only). | `/chestlog config` |
| `/chestlog config reload` | Hot-reloads all JSON configuration files from disk. | `/chestlog config reload` |
| `/chestlog config get <key>` | Queries the current value of a configuration setting `<key>`. | `/chestlog config get webhook` |
| `/chestlog config set <key> <value>` | Updates `<key>` to `<value>` and hot-swaps it in memory immediately. | `/chestlog config set owner_distance 32` |

### ⚙️ Supported Configuration Keys:
| Key Name | Accepted Values | Default | What It Controls |
|---|---|---|---|
| `alert_enabled` | `true` \| `false` | `true` | Enables/disables the security alert engine. |
| `webhook` | URL String | `""` | Discord Webhook URL for security embed notifications. |
| `bot_username` | Text | `"ChestLogger Security Bot"` | Display name for the Discord bot webhook. |
| `cooldown` | Integer (seconds) | `30` | Rate limit cooldown for Discord alerts. |
| `hud` | `true` \| `false` | `true` | Action-Bar HUD warnings for online staff. |
| `chat` | `true` \| `false` | `true` | Interactive clickable chat alert cards for staff. |
| `owner_distance` | Integer (blocks) | `24` | Maximum distance for consensual trade proximity. |
| `web_enabled` | `true` \| `false` | `false` | Enables/disables the embedded HTTP Web Dashboard. |
| `web_port` | Integer (Port) | `8080` | HTTP port for the web dashboard. |

---

## 6. Time & Duration Converter

When using `<seconds>` in `/chestlog rollback`, use this quick reference table:

| Desired Time Range | Value to use for `<seconds>` | Example Command |
|---|---|---|
| **5 Minutes** | `300` | `/chestlog rollback 120 64 -300 300` |
| **15 Minutes** | `900` | `/chestlog rollback 120 64 -300 900` |
| **30 Minutes** | `1800` | `/chestlog rollback 120 64 -300 1800` |
| **1 Hour** | `3600` | `/chestlog rollback 120 64 -300 3600` |
| **6 Hours** | `21600` | `/chestlog rollback 120 64 -300 21600` |
| **12 Hours** | `43200` | `/chestlog rollback 120 64 -300 43200` |
| **24 Hours (1 Day)** | `86400` | `/chestlog rollback 120 64 -300 86400` |
| **3 Days** | `259200` | `/chestlog rollback 120 64 -300 259200` |
| **7 Days (1 Week)** | `604800` | `/chestlog rollback 120 64 -300 604800` |

---

## 7. Common Mistakes & Troubleshooting

### ❌ Mistake 1: Typing brackets literally
* **Wrong:** `/chestlog trust <Alex>`
* **Correct:** `/chestlog trust Alex`

### ❌ Mistake 2: Missing coordinates in rollback
* **Wrong:** `/chestlog rollback 3600` *(On Fabric console)*
* **Correct:** `/chestlog rollback 120 64 -300 3600`

### ❌ Mistake 3: Forgetting to confirm a rollback
* **Wrong:** Running `/chestlog rollback 120 64 -300 3600` and expecting items to restore immediately.
* **Correct:** Check the chat for the preview token (e.g. `a3f9b2`) and type:  
  `/chestlog rollback 120 64 -300 3600 confirm a3f9b2`

### ❌ Mistake 4: Trying to trust yourself
* **Wrong:** `/chestlog trust YourOwnName`
* **Correct:** You automatically own your chests. Only trust **other** players!
