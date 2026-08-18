# 📦 ChestLogger — Player & User Guide

> **The Easy, Practical Guide for Server Players & Builders**  
> *Protect your chests, share items safely with teammates, track stolen loot, and check your container history.*

---

## 📑 Table of Contents
1. [⚡ Quick Start (60-Second Setup)](#1--quick-start-60-second-setup)
2. [🔒 How Your Containers Are Protected](#2--how-your-containers-are-protected)
3. [🤝 Sharing with Friends: The Trust System](#3--sharing-with-friends-the-trust-system)
4. [🔍 Checking Your Chest History (Self-Inspection)](#4--checking-your-chest-history-self-inspection)
5. [🕵️ Tracing Where an Item Came From (`/chestlog trace`)](#5-️-tracing-where-an-item-came-from-chestlog-trace)
6. [💡 Best Practices & Pro-Tips](#6--best-practices--pro-tips)
7. [⚠️ Limitations & Frequently Asked Questions](#7-️-limitations--frequently-asked-questions)
8. [📋 Player Commands Cheat Sheet](#8--player-commands-cheat-sheet)

---

## 1. ⚡ Quick Start (60-Second Setup)

ChestLogger automatically protects your containers the moment you place them down. You don't need any complicated setup or private sign locks!

```
STEP 1: Place down a Chest, Barrel, or Shulker Box.
        --> It is now automatically claimed under your name!

STEP 2: Want a friend or teammate to share items with you?
        --> Type: /chestlog trust <FriendUsername>

STEP 3: Want to see who took items from your chest?
        --> Type: /chestlog i and click your chest!
```

---

## 2. 🔒 How Your Containers Are Protected

### 1. Automatic Claiming on Placement & Safe Mass Claiming
Whenever you place a **Chest, Trapped Chest, Barrel, Shulker Box, or Crafter**, ChestLogger automatically logs you as the registered owner.
* **Double Chests:** If you place a chest next to an existing one to create a double chest, both sides are automatically linked under your ownership.
* **Mass Claiming Storage Rooms (`/chestlog claim <radius>`):** Built a massive warehouse or base? Stand in the middle and type:
  ```bash
  /chestlog claim 12
  ```
  This automatically claims all containers within 12 blocks for you in a single click (up to 16 blocks radius for players). Any chests belonging to other players or inside another player's land claims are automatically skipped and protected!
* **Transferring Chest Ownership (`/chestlog transfer <newOwner>`):** Want to hand over a chest or warehouse section to a friend? Look at the container and type:
  ```bash
  /chestlog transfer Alex
  ```

### 2. Built-in Anti-Sniping Protection (No Stolen Claims!)
What if someone tries to claim your chests before you had a chance to claim them?
* **Transaction Provenance Defense:** ChestLogger checks the original block placement history. If you placed the container, an untrusted stranger who types `/chestlog claim` will be blocked with:  
  `§c[ChestLogger] This container was placed by <YourName> and cannot be claimed by you!`
* **Land Claim Hook:** On Paper servers with GriefPrevention, Towny, Lands, or WorldGuard, ChestLogger automatically respects your territory. Strangers can never claim containers inside your land claims.

### 3. How the Server Catches Thieves
If an unauthorized stranger opens or breaks your chest:
* If you are **Offline** or **far away (>24 blocks)**, ChestLogger instantly flags the theft as a security incident and alerts online server staff.
* If someone loots multiple chests in your base rapidly, an emergency **`CRITICAL_RAID`** alert is triggered.
* Server moderators can use their rollback tools to restore all your stolen items back into your chest safely without breaking anything else you built!

---

## 3. 🤝 Sharing with Friends: The Trust System

If you live in a base with friends, you can grant them **Container Trust**. When trusted friends withdraw diamonds, armor, or resources from your chests, no security alarms or theft warnings will be triggered.

```
+-------------------------------------------------------------------+
|                        TRUST COMMANDS                             |
+-------------------------------------------------------------------+
|  /chestlog trust <player>    --> Allow a friend to take items     |
|  /chestlog untrust <player>  --> Remove a friend from trust       |
|  /chestlog trustlist         --> View everyone you currently trust|
+-------------------------------------------------------------------+
```

### Examples:
```bash
# Trust your teammate 'Alex'
/chestlog trust Alex

# Check your current trusted friends
/chestlog trustlist

# Revoke trust from 'Alex'
/chestlog untrust Alex
```

> ⚠️ **Important Security Note:** Only trust players you genuinely know and trust. Anyone in your trust list can take items from your chests without alerting the moderators.

---

## 4. 🔍 Checking Your Chest History (Self-Inspection)

Ever noticed diamonds or tools missing and wondered who took them? You can inspect your own containers in seconds.

### Step-by-Step Inspection Guide:
1. Type **`/chestlog i`** (or `/cl i`) in chat.  
   *You will see:* `[ChestLogger] Inspect mode enabled. Left-click for chat, right-click for GUI.`
2. Walk up to the container you want to inspect:
   * **LEFT-CLICK:** Prints recent transactions directly into your chat box.
   * **RIGHT-CLICK:** Opens a full visual screen showing items, player names, and timestamps.
3. When finished, type **`/chestlog i`** again to exit inspection mode.

```
+---------------------------------------------------------------------------------------------+
|                               WHAT THE CHAT LOG LOOKS LIKE                                  |
+---------------------------------------------------------------------------------------------+
| [-5m 12s] Alice SHIFT_CLICK_EXTRACT -32x minecraft:diamond (Slot 0, Remaining: 0)           |
| [-1h 30m] Bob PLACE +64x minecraft:iron_ingot (Slot 1, Remaining: 64)                       |
| [-3h 15m] Charlie PICKUP -1x minecraft:elytra (Slot 4, Remaining: 0)                        |
+---------------------------------------------------------------------------------------------+
```

### Understanding Common Action Names:
* **`SHIFT_CLICK_EXTRACT`:** Player quickly shift-clicked items into their inventory.
* **`SHIFT_CLICK_INSERT`:** Player shift-clicked items into the container.
* **`PICKUP` / `PLACE`:** Player picked up or placed items with their mouse cursor.
* **`HOTBAR_SWAP`:** Player pressed a number key (1–9) or offhand (`F`) over a slot.
* **`DOUBLE_CLICK_COLLECT`:** Player double-clicked to collect all matching items.
* **`CONTAINER_BREAK`:** The container was destroyed or mined.

---

## 5. 🕵️ Tracing Where an Item Came From (`/chestlog trace`)

ChestLogger includes an item tracking system that lets you view the entire history of an item across the server.

```
+-------------------------------------------------------------------+
|                        TRACING COMMANDS                           |
+-------------------------------------------------------------------+
|  /chestlog trace             --> Trace the item in your MAIN HAND |
|  /chestlog trace hand        --> Trace the item in your MAIN HAND |
|  /chestlog trace <x> <y> <z> --> Trace an item inside a chest slot|
+-------------------------------------------------------------------+
```

### How to Trace an Item:
1. Hold the item in your main hand (e.g. an Enchanted Sword, Armor, or Elytra).
2. Type **`/chestlog trace`**.
3. A visual journey tree will open showing:
   * **Step #1:** Who originally obtained or crafted the item.
   * **Intermediate Steps:** Every chest it was placed into, every hopper that moved it, and every player who held it.
   * **Confidence Rating:** `[EXACT]` means an exact 100% component fingerprint match!

---

## 6. 💡 Best Practices & Pro-Tips

* **Trading with Friends:** If you and an untrusted friend trade items in person, make sure you stand near the chest ($\le 24$ blocks away). ChestLogger detects that both of you are present and treats it as a friendly trade (`CONSENSUAL_PROXIMITY`), preventing false alarms.
* **Moving Base / Relocating:** When you break your own chest, your claim is automatically removed. When you place it in your new base, it is automatically claimed again.
* **Community Chests:** If you build a public giveaway chest or community farm storage, tell a server admin so they can set it to public or wilderness mode.
* **Shulker Box Safety:** Items placed inside Shulker Boxes are fully tracked. Even if a shulker box is moved between chests, ChestLogger tracks its contents!

---

## 7. ⚠️ Limitations & Frequently Asked Questions

### Q: What happens if I break my double chest?
> **Answer:** If you break one half of a double chest, your ownership claim on that half is cleared, and the remaining half remains claimed under your name.

### Q: Can someone steal from my chest using a Hopper underneath it?
> **Answer:** Hoppers cannot secretly steal your items. ChestLogger logs every `HOPPER_EXTRACT` and `HOPPER_INSERT` transaction. If an unauthorized hopper is placed under your chest, moderators can trace exactly who placed the hopper and where the items were moved.

### Q: Can I unclaim a chest if I want to give it to someone else?
> **Answer:** Yes! Look at the container and type `/chestlog unclaim`. Your friend can then look at it and type `/chestlog claim`.

### Q: What should I do if my base gets griefed or looted?
> **Answer:** 
> 1. Do not break or alter the remaining chests.
> 2. Note your base coordinates (`X, Y, Z`).
> 3. Use `/chestlog i` to see who took the items.
> 4. Contact a moderator or open a support ticket. Staff can run a staged `/chestlog rollback` to restore all your stolen items back into your chests without deleting your new items!

---

## 8. 📋 Player Commands Cheat Sheet

| Command | Shorthand | What It Does |
|---|---|---|
| `/chestlog claim` | `/cl claim` | Claim ownership of the container you are looking at |
| `/chestlog claim <radius>` | `/cl claim <radius>` | Mass-claim all containers around you (up to 16 blocks) |
| `/chestlog transfer <player>` | `/cl transfer <player>` | Transfer ownership of your container to another player |
| `/chestlog unclaim` | `/cl unclaim` | Release your claim from the container you are looking at |
| `/chestlog trust <player>` | `/cl trust <player>` | Trust a teammate so they can access your chests without alerts |
| `/chestlog untrust <player>` | `/cl untrust <player>` | Remove a player from your trust list |
| `/chestlog trustlist` | `/cl trustlist` | List all players you currently trust |
| `/chestlog trace` *(or `trace hand`)* | `/cl trace` | Trace the full history and origin of the item in your main hand |
