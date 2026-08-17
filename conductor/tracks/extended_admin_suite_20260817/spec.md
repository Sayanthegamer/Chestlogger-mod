# Specification: Interactive Wand, Paper GUI Parity, Extended Containers & Discord Alerting

## 1. Overview & Strategic Goals
This track introduces a comprehensive suite of high-impact administrator tools, platform parity enhancements, extended container coverage, and proactive security alerting for ChestLogger (v2.2.0) across Minecraft 26.2 (Fabric & Paper):
1. **Interactive Inspection Wand & Toggle Mode (`/chestlog i` / Wand)**: Zero-friction container inspection by direct interaction (left-click for chat history, right-click to open inspection GUI) via command toggle and configurable wand item (default: `minecraft:stick`).
2. **Paper In-Game Inspection GUI Parity**: Full 54-slot interactive Bukkit inventory GUI matching the Fabric Phase 11 GUI with live pagination, item previews, half indicators, and rollback confirmation.
3. **Extended Container & Block Lifecycle Tracking**: Comprehensive tracking for 1.21/26.2 Crafters, Dispensers, Droppers, Decorated Pots, Chiseled Bookshelves, Furnaces/Smokers/Blast Furnaces, Brewing Stands, and container destruction/placement events (capturing dropped/stored inventory deltas).
4. **Asynchronous Discord Webhook & Anti-Theft Alerting**: Non-blocking asynchronous HTTP dispatcher with rich embed notifications, rate-limiting, and configurable suspicious thresholds (e.g. mass withdrawals of diamonds/netherite).

---

## 2. Functional Requirements

### 2.1 Interactive Click / Wand Inspection Mode
- **Toggle Mode (`/chestlog i`)**:
  - Operators can toggle inspection mode on/off per-player.
  - While active, left-clicking any supported container performs a spatial inspection and displays recent transaction history in chat.
  - While active, right-clicking any supported container opens the in-game ChestLogger GUI for that container position.
  - Actions do not destroy or open the container normally while in inspect mode.
- **Wand Item Mode (`/chestlog wand` / Configurable Item)**:
  - Default wand item: `minecraft:stick` (configurable in `config/chestlogger_inspect.json` or plugin config).
  - When holding the designated wand item, clicking a container triggers inspection even without `/chestlog i` active.
- **Platform Support**: Identical behavior on Fabric 26.2 and Paper 26.2.

### 2.2 Paper In-Game Inspection GUI Parity
- **54-Slot Bukkit Chest GUI (`PaperChestHistoryView`)**:
  - Top 45 slots display paginated transaction entries with item icon, count, timestamp, player name, action type (`+` / `-`), and half indicator (`L` / `R`).
  - Bottom row control bar:
    - Slot 45: `[< Previous Page]` arrow button.
    - Slot 48: Info paper showing coordinates, dimension, total log count, and current page.
    - Slot 50: Non-destructive Rollback trigger button (prompts `/chestlog rollback <x> <y> <z> 3600` confirmation).
    - Slot 53: `[Next Page >]` arrow button.
- **Click Handling & Sound Effects**: Click cancellation prevents item theft from GUI; plays click/page-turn sounds.

### 2.3 Extended Container & Block Lifecycle Tracking
- **Container Types**:
  - `Crafter` (1.21/26.2 grid craft events and inventory moves).
  - `Dispenser` & `Dropper` (hopper moves, dispense/drop extractions, player insertions).
  - `Decorated Pot` (insertions and breaks with stored items).
  - `Chiseled Bookshelf` (book placement and retrieval).
  - `Furnace`, `Smoker`, `Blast Furnace` (smelting output extractions, fuel insertions).
  - `Brewing Stand` (potion ingredient and bottle insertions/extractions).
- **Lifecycle Events**:
  - `CONTAINER_BREAK`: Logs player or explosion destroying a container and records all dropped slot contents.
  - `CONTAINER_PLACE`: Logs container placement.
- **Storage & Index Compatibility**: Uses existing binary `.clog` / `.cidx` formats with `ActionType.CONTAINER_BREAK` and `ActionType.CONTAINER_PLACE`.

### 2.4 Asynchronous Discord Webhook & Anti-Theft Alerts
- **Alert Dispatcher (`DiscordAlertDispatcher`)**:
  - Runs in background daemon thread without blocking the server main tick.
  - Uses `java.net.http.HttpClient` (Java 25 built-in, zero external dependencies).
  - Supports configurable Discord Webhook URL in `config/chestlogger_alerts.json`.
- **Threshold Rules**:
  - Filter by valuable item IDs (e.g., `minecraft:diamond`, `minecraft:netherite_ingot`, `minecraft:elytra`, `minecraft:shulker_box`).
  - Quantity threshold (e.g., extracting $\ge 32$ valuable items within $N$ seconds).
  - Mass clearing threshold (extracting items from $\ge 10$ slots in a single transaction).
- **Rich Embed Design**:
  - Color coded: Red (`#E74C3C`) for theft alerts, Orange (`#E67E22`) for container destruction, Green (`#2ECC71`) for massive rollbacks.
  - Displays: Player Name & UUID, Dimension & Coordinates, Action Type, Item & Quantity, Timestamp.
- **Rate-Limiting & Burst Protection**: Token-bucket rate limiter to prevent Discord HTTP 429 webhook throttling.

---

## 3. Non-Functional Requirements
- **Performance**: Zero-blocking I/O on the main server thread. Webhooks and disk operations run strictly off-thread.
- **Safety**: Rollbacks and GUI menus remain non-destructive with inventory validation.
- **Platform Matrix**: Fabric 26.2 and Paper 26.2 compiled with JDK 25 and full test coverage (>80%).

---

## 4. Acceptance Criteria
- [ ] `/chestlog i` and wand item successfully inspect containers on left-click and open GUI on right-click on Fabric and Paper.
- [ ] Paper 26.2 provides an interactive 54-slot Bukkit chest GUI matching Fabric's feature set.
- [ ] Crafters, Dispensers, Droppers, Pots, Bookshelves, Furnaces, and Container Break/Place events are tracked and rollback-capable.
- [ ] Discord Webhook asynchronously sends formatted embeds upon suspicious transaction triggers with zero tick lag.
- [ ] Full test suite `./gradlew check` passes on JDK 25 across `chestlogger-common`, `chestlogger-fabric`, and `chestlogger-paper`.
