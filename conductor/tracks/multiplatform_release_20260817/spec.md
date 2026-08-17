# Specification: Multi-Platform Server Releases (Fabric 26.2 & Paper 26.2)

## 1. Overview & Vision
ChestLogger's next major release establishes a production-grade multi-platform architecture providing two fully validated, native server release artifacts for Minecraft 26.2:
1. **Fabric Mod (`chestlogger-fabric`)**: Reference implementation preserving mixin-based container tracking, integrated/dedicated server hooks, client-server GUI networking, and unobfuscated Mojang mappings.
2. **Paper Plugin (`chestlogger-paper`)**: Native Paper plugin leveraging `paperweight-userdev` with a pinned 26.2 dev bundle, unobfuscated Mojmap runtime semantics (no legacy reobf), native event listeners with an explicit event-to-transaction model, and Paper scheduler hooks.

Both platforms share a pure Java core engine (`chestlogger-common`) handling the binary `.chlog` v1 format, lockless MPSC queuing, compressed batch I/O, spatial indexing, rollback planning, and embedded web administration dashboard with zero main-thread disk blocking.

---

## 2. Architectural Structure & Module Boundaries

### 2.1 Multi-Module Gradle Hierarchy
```
Chestlogger/
├── chestlogger-common/        # Pure Java 25 library (Zero Minecraft/Bukkit/Fabric dependencies)
│   ├── event/                 # Normalized immutable TransactionLogEntry, SlotDelta, ActionType
│   ├── storage/               # BinaryLogWriter, LZ4/ZSTD block compressors, BinaryRecordCodec
│   ├── index/                 # Spatial/player/time indexing & segment management
│   ├── query/                 # Platform-agnostic QueryEngine, pagination & formatters
│   ├── rollback/              # Pure compensation RollbackPlanner & math engine (dry-run & plans)
│   ├── recovery/              # Tail recovery, checksum verification & quarantine
│   ├── web/                   # Embedded HttpServer core, auth, live tail, REST endpoints
│   └── config/                # Platform-agnostic storage & web configuration models
├── chestlogger-fabric/        # Fabric 26.2 Mod (Fabric Loom 1.17, Loader 0.19.3, Gradle 9.5.1, Java 25, Mojmap)
│   ├── ChestLoggerMod.java    # ModInitializer & DedicatedServerModInitializer
│   ├── mixin/                 # Container menu & hopper mixins
│   ├── command/               # Brigadier command registration & permission checks
│   ├── lifecycle/             # Server lifecycle hooks & integrated singleplayer handling
│   ├── rollback/              # FabricRollbackExecutor (Server-thread block entity mutation)
│   └── network/               # Client-server payload networking for History GUI
└── chestlogger-paper/         # Paper 26.2 Plugin (Paperweight-userdev, pinned 26.2 dev bundle, Mojmap)
    ├── ChestLoggerPlugin.java # JavaPlugin lifecycle bootstrap (standard plugin.yml preferred)
    ├── listener/              # Paper event listeners with explicit event-to-transaction delta modeling
    ├── command/               # Paper Brigadier / Command tree & permission checks
    ├── rollback/              # PaperRollbackExecutor (Server-thread Inventory mutation)
    └── scheduler/             # Paper async task executor / Folia-ready scheduler binding
```

---

## 3. Strict Thread Safety & Event Extraction Contract

### 3.1 Server Main Thread (Synchronous)
- Intercepts player clicks, drags, hopper transfers, and container open/close events.
- Captures normalized transaction snapshots using an explicit event-to-transaction model across all interaction variants:
  - **Click types**: pickup, placement, shift-click, hotbar number swaps, double-clicks.
  - **Drag distributions**: single-slot, multi-slot, and cursor-split distributions.
  - **Automation**: `InventoryMoveItemEvent` (hoppers & hopper minecarts) with source/destination container tracking.
  - **Multi-Viewer state**: simultaneous container access by multiple players.
  - **Filtering**: explicit rejection of cancelled events and non-container interactions.
- Extracts immutable primitive data (Player UUID, player name, container world UID, coordinates `(x,y,z)`, slot indices, item IDs, count delta, component hash).
- Enqueues immutable `TransactionLogEntry` into bounded MPSC `TransactionEventQueue`.
- **CRITICAL HARD REQUIREMENT**: Zero disk I/O, zero block compression, zero index operations on the main thread. Verified via thread-assertion instrumentation in `BinaryLogWriter`, `BlockCompressor`, and `PersistentIndexManager`.

### 3.2 Worker Thread (Asynchronous Background)
- Drains batches from `TransactionEventQueue`.
- Encodes entries using `BinaryRecordCodec`.
- Compresses contiguous blocks with `LZ4BlockCompressor` (or `Zstd`).
- Executes sequential writes to `.chlog` files via `FileChannel` without random seek storms.
- Periodically flushes index checkpoints and emits telemetry metrics.
- **CRITICAL HARD REQUIREMENT**: Zero Bukkit/Minecraft live world, inventory, player, or block entity queries.

---

## 4. Platform Specifications & Tooling

### 4.1 Fabric 26.2 Mod (`chestlogger-fabric`)
- **Loader**: Fabric Loader 0.19.3+, Loom 1.17 (`net.fabricmc.fabric-loom`), Gradle 9.5.1, Java 25.
- **Mapping**: Official Mojang unobfuscated mappings.
- **Rollback**: `FabricRollbackExecutor` applying compensation deltas on the main server thread.
- **Environments**: Dedicated servers & singleplayer integrated server with per-world log separation.

### 4.2 Paper 26.2 Plugin (`chestlogger-paper`)
- **Tooling**: `paperweight-userdev` with pinned Paper 26.2 dev bundle (no duplicate Paper API dependency).
- **Runtime**: Mojmap runtime semantics (unobfuscated 26.1+ standard, no legacy `reobfJar`).
- **Plugin Descriptor**: Standard `plugin.yml` preferred for stable production deployment, retaining compatibility with Paper 26.2.
- **Interception**: Event listeners with explicit before/after mutation reconciliation.
- **Rollback**: `PaperRollbackExecutor` applying compensation deltas on the main server thread via `Inventory.setItem()`.
- **Authoritative Testing**: Real headless Paper 26.2 dedicated server integration test harness.

---

## 5. Storage Interoperability, Golden Fixtures & Rollback

- **Unified `.chlog` v1 Format**: Byte-for-byte identical binary encoding across Fabric and Paper.
- **Golden `.chlog` Fixtures**:
  - Valid transaction stream fixtures verifying byte-for-byte cross-platform parity.
  - Truncated and corrupted tail fixtures verifying identical error detection and tail recovery on both platforms.
- **Cross-Platform Rollback**: Logs recorded on Paper can be parsed and rolled back on Fabric, and vice versa.
- **Web Dashboard**: Embedded HTTP server (`com.sun.net.httpserver`) running independently of platform with zero npm/CDN dependencies.

---

## 6. Compatibility Matrix & Risk Register

| Platform | Target Version | Tooling / Build | Java Version | Mapping Scheme | Thread Model |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Fabric** | Minecraft 26.2 | Loom 1.17 / Loader 0.19.3 / Gradle 9.5.1 | Java 25 | Mojang Unobfuscated | Integrated & Dedicated Server Thread |
| **Paper** | Paper 26.2 (Pinned Build) | Paperweight-Userdev | Java 25 | Mojang Unobfuscated (No Reobf) | Server Main Thread + Async Tasks |

### Risk Register
1. **Bukkit Async Catchers**: Worker thread accessing Bukkit API causes server crash. *Mitigation*: Complete compiler isolation in `chestlogger-common` with zero Bukkit imports.
2. **Main Thread Disk Blocking**: Accidental filesystem calls on main server thread. *Mitigation*: Automated runtime thread instrumentation asserting zero I/O on the server thread.
3. **Paper 26.2 MockBukkit Desync**: MockBukkit registry gaps on 26.2. *Mitigation*: Authoritative validation performed via real headless Paper 26.2 dedicated server harness.
4. **Multi-Viewer Inventory Desync**: Simultaneous container mutation by multiple players. *Mitigation*: Synchronous slot snapshots with normalized pre/post delta reconciliation.
5. **Aternos / Low-End HDD Seek Storms**: Random disk access degrading low-end server performance. *Mitigation*: Append-only sequential batch writes with configurable flush thresholds.

---

## 7. Acceptance Criteria
- Both `:chestlogger-fabric` and `:chestlogger-paper` compile and pass automated tests on JDK 25.
- Both platforms produce byte-for-byte compatible `.chlog` logs validated against golden fixtures.
- Inspection and rollback work seamlessly with logs generated from either platform.
- Zero main-thread disk blocking verified via automated thread instrumentation.
- Authoritative headless Paper 26.2 and Fabric 26.2 dedicated server integration suites pass.
- GitHub Actions CI workflow compiles, tests, and publishes release jars for both targets.
