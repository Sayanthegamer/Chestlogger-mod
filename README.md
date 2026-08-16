# ChestLogger

**High-Performance, Crash-Resilient, Server-Authoritative Container Audit Logging Mod for Minecraft 26.2 (Fabric)**

---

## 📌 Architecture Overview

ChestLogger is built from the ground up for **Minecraft 26.2** using modern Fabric Loom with unobfuscated Mojang mappings, Java 25, and high-throughput zero-allocation concurrency patterns.

```
       +---------------------------------------------+
       |   Minecraft 26.2 Server / Container Mixins   |
       |  (Player Click / Hoppers / Double Chests)   |
       +---------------------------------------------+
                             |
                   non-blocking offer()
                             v
       +---------------------------------------------+
       | Lock-Free Bounded MPSC Event Queue (64k)    |
       |  - Atomic depth & drop overflow counters    |
       |  - Zero Main Thread Disk I/O                |
       +---------------------------------------------+
                             |
                     async batch drain()
                             v
       +---------------------------------------------+
       |   Dedicated Background Log Writer Thread    |
       |  - StringTableDictionary interning          |
       |  - LZ4 / Zstd compressed frame blocking     |
       |  - Append-only .clog segment rotation       |
       +---------------------------------------------+
              |                               |
              v                               v
+-------------------------------+  +-------------------------------+
|  Persistent Inverted Index    |  |  Crash Tail Recovery Engine   |
|  - Spatial (64-bit BlockPos)  |  |  - Block CRC32 validation     |
|  - Temporal (Time interval)   |  |  - Uncommitted tail trim      |
|  - Player (UUID) & Item       |  |  - Two-pass dictionary rebuild|
|  - Atomic .cidx checkpoint    |  +-------------------------------+
+-------------------------------+
              |
              v
+-------------------------------+
|  Command & Compensation Suite |
|  - /chestlog inspect [pos]    |
|  - /chestlog rollback <pos>   |
|  - /chestlog stats            |
|  - /chestlog purge <days>     |
+-------------------------------+
```

---

## 🚀 Key Features

1. **Zero Main Thread Disk I/O**:
   - Container interactions are snapshotted and queued into a lock-free bounded MPSC ring buffer.
   - Server tick threads NEVER perform disk I/O, compression, or index updates.

2. **Compact Framed Binary Storage (`.clog`)**:
   - Delta-encoded timestamps and sequence IDs using ZigZag VarInt/VarLong serialization.
   - Bi-directional String Table Dictionary interning for item identifiers, dimensions, and player names.
   - Framed LZ4 block compression with individual CRC32 checksums per block.
   - Achieving **< 30 bytes per transaction** (>4x space reduction over raw JSON).

3. **Multi-Dimensional Spatial & Temporal Indexing (`.cidx`)**:
   - Inverted indexes for instantaneous lookup by 64-bit packed `BlockPos`, radius bounding box, player `UUID`, `itemId`, and time range.
   - Direct-seek O(1) random-access retrieval from `.clog` files with zero full-table scans.
   - Atomic checkpoints with `.cidx.tmp` swapping and CRC32 header validation.

4. **Self-Healing Crash Recovery**:
   - `TailRecoveryEngine`: Automatically detects and truncates uncommitted trailing bytes caused by abrupt power loss or SIGKILL, preserving all previously committed blocks with clean sequence ID resumption.
   - `IndexRebuilder`: Rebuilds the full multi-dimensional index directly from raw `.clog` files if index files are missing or corrupted.

5. **Non-Destructive Compensation Rollback**:
   - Rollback NEVER rewinds or erases storage logs.
   - Computes inverse slot deltas and safely relocates items if target slots are occupied to avoid item deletion.
   - Emits auditable `ROLLBACK_COMPENSATION` events attributed to the executing administrator.

6. **Singleplayer & Dedicated Server Isolation**:
   - World storage paths are dynamically bound to the active level save directory (`saves/<world>/chestlogger/` in singleplayer or `./world/chestlogger/` on dedicated servers).
   - Clean shutdown hooks (`ServerLifecycleEvents.SERVER_STOPPING`) guarantee complete queue evacuation and fsync flushes.

---

## 🛠️ Administrative Commands

All administrative commands require Operator permission (Level 2+).

| Command | Description |
|---|---|
| `/chestlog inspect [pos] [page]` | Displays transaction history for the target container. Supports pagination. |
| `/chestlog inspect <pos> <player> [page]` | Filters container transaction history for a specific player. |
| `/chestlog rollback <pos> <seconds> [targetPlayer]` | Calculates a dry-run rollback plan, displaying affected items, conflicts, and issues an ephemeral confirmation token. |
| `/chestlog rollback <pos> <seconds> confirm <token>` | Safely executes non-destructive slot compensation on the live container. |
| `/chestlog stats` | Displays real-time operational metrics (queue depth, enqueued counts, dropped overflow counter, drained records, index size). |
| `/chestlog purge <days> [confirmToken]` | Trims log segments older than the specified retention threshold with a 2-step confirmation token safeguard. |

---

## 📊 Storage Profiles

ChestLogger provides three pre-tuned hardware profiles:

- **`BALANCED` (Default)**: 64k queue capacity, 1000 events/block, 1000ms flush interval, 64MB segment rotation.
- **`HDD`**: 128k queue capacity, 5000 events/block, 5000ms flush interval, 128MB segment rotation for minimal rotational write amplification.
- **`SSD`**: 32k queue capacity, 250 events/block, 150ms flush interval, eager fsync for maximum durability.

---

## 🔨 Building & Verification

### Prerequisites
- **JDK 25** (`jdk-25.0.4` or newer)
- **Gradle 9.5.1** (Included wrapper)

### Build Commands
```bash
# Run complete test suite (43+ unit, integration, recovery, and benchmark tests)
./gradlew test

# Assemble release mod JAR
./gradlew build
```

Built artifacts are located in `build/libs/`:
- `chestlogger-<version>.jar` (Fabric mod binary)
- `chestlogger-<version>-sources.jar`

---

## 📜 License
ChestLogger is released under the **MIT License**.
