# Specification: Custom In-Game Configuration Menu & Hot-Reload Suite

## 1. Overview
This track delivers a 100% custom, zero-dependency in-game administrative configuration suite for Fabric and Paper. It eliminates manual JSON file editing by providing dedicated in-game graphical interfaces (a custom Fabric client `Screen` with `EditBox` text inputs for Discord webhook URLs/ports/names, and a lightweight Paper Bukkit inventory GUI with chat-assisted input) paired with a live in-memory hot-reloading engine that applies configuration updates immediately without restarting the Minecraft server.

## 2. Functional Requirements

### 2.1 In-Game Command Interface
- Admin command: `/chestlog config` and `/chestlog settings` (permission: `chestlogger.admin` or OP).
- Command line management subcommands:
  - `/chestlog config reload`: Hot-reloads all JSON configuration files (`chestlogger_alerts.json`, `config/chestlogger_web.json`) from disk.
  - `/chestlog config get <category> <key>`: Displays current setting value in chat.
  - `/chestlog config set <category> <key> <value>`: Updates a setting value, saves to disk, and triggers instant hot-reloading.

### 2.2 Fabric Custom Client-Server GUI (`ChestLogConfigScreen`)
- **Zero Third-Party GUI Dependencies**: Built purely with vanilla Minecraft screen rendering methods and Fabric networking payloads (`net.minecraft.client.gui.screens.Screen`, `net.minecraft.client.gui.components.EditBox`).
- **Category Navigation Tabs**:
  1. 🚨 **Alerts & Security**:
     - Discord Webhook URL text `EditBox` (supports pasting long URLs).
     - Bot Username & Avatar URL `EditBox` inputs.
     - Toggle buttons for Discord Alerts (ON/OFF), In-Game Chat Broadcasts (ON/OFF), Action-Bar HUD notice (ON/OFF).
     - Stepper / adjusters for Alert Cooldown seconds (5s–300s) and Max Owner Alert Distance (10m–500m).
  2. 💎 **Tracked Items**:
     - Visual grid / slot list displaying tracked high-value item IDs.
     - Add/remove items directly by selecting or typing item identifiers (e.g. `minecraft:diamond`, `minecraft:elytra`, `minecraft:netherite_ingot`).
  3. ⚙️ **General & Claims**:
     - Auto-claim container on placement toggle (ON/OFF).
     - Wand inspection tool item selector (e.g. `minecraft:stick`).
  4. 🌐 **Web Server Controls**:
     - Web dashboard enabled toggle (ON/OFF).
     - Server Port `EditBox` (1024–65535) and Host input.
     - "Regenerate Secret Token" action button.
- **Save & Apply Action**:
  - Clicking "Save & Apply" encodes the modified settings into a `ChestLogConfigUpdatePayload` and transmits to the server.
  - Server verifies sender admin permissions, saves to disk atomically, updates runtime instances, and responds with a success toast.

### 2.3 Paper In-Game Configuration GUI (`PaperChestConfigView`)
- Lightweight, zero-dependency Bukkit inventory GUI with custom click handlers.
- Visual toggle indicators (e.g. Lime Dye = Enabled, Gray Dye = Disabled).
- Interactive chat-prompt state machine for text entry (e.g. clicking "Edit Webhook URL" prompts the admin to type or paste the URL into chat within 30 seconds).

### 2.4 Live Hot-Reloading & In-Memory Synchronization
- Unified `ConfigManager` managing `AlertConfig` and `WebConfig` state.
- Thread-safe listener architecture that notifies registered subsystems on update:
  - `SmartTheftEvaluator` (updates alert config and tracked items set).
  - `DiscordAlertDispatcher` (updates webhook URL and bot username).
  - `FabricSecurityAlertBroadcaster` & `PaperSecurityAlertBroadcaster` (updates HUD / chat notification settings).
  - `EmbeddedHttpServer` (updates port, host, and secret token).

## 3. Non-Functional Requirements
- **Security & Authorization**: All packet handlers, GUI openers, and CLI commands strictly require `chestlogger.admin` or OP permissions. Non-admins cannot view or alter configurations.
- **Data Integrity**: Configuration updates use `.tmp` files with atomic move operations to prevent corruption during unexpected crashes.
- **Zero Web Editing**: Configuration management is strictly restricted to in-game administrator tools.

## 4. Acceptance Criteria
- Unit tests verify serialization, validation, atomic saving, and live listener notifications.
- Fabric packet codecs serialize and deserialize full config payloads without data loss.
- In-game commands `/chestlog config` open the respective custom GUI on Fabric and Paper.
- Updating settings via GUI or `/chestlog config set` updates runtime behavior immediately without server restarts.
- 100% pass on `./gradlew test` across all subprojects.
