# Specification: ChestLogger MVP (Minecraft 26.2)

## 1. Overview
ChestLogger is a production-quality, server-authoritative container transaction and looting tracker Fabric mod for Minecraft 26.2 (Fabric Loader 0.19.3+, Loom 1.17, Java 25, Gradle 9.5.1, unobfuscated Mojang mappings). It captures container inventory modifications caused by players and automation systems without blocking the main server thread, storing events in a compact, append-only, compressed binary log with persistent indexing, crash resiliency, and compensation rollback.

## 2. Functional Requirements
- **FR-1: Minecraft 26.2 Fabric Bootstrap**: Unobfuscated Mojang mappings, Fabric Loader 0.19.3+, Java 25, Loom 1.17.
- **FR-2: Container & Transaction Interception**:
  - Unified container abstraction covering: Single Chest, Double Chest, Trapped Chest, Barrel, Shulker Box, Hopper, and Hopper Minecart.
  - Track player actions (pickup, place, shift-click, drag, double-click, hotbar swap, quick move, verified close sync) and automation transfers.
  - Compute slot item deltas (+/-) and record monotonic sequence ID, timestamp, transaction UUID, player UUID/name (or automation indicator), dimension, BlockPos, slot, item ID, and metadata fingerprint.
- **FR-3: Asynchronous Binary Storage Engine**:
  - Zero blocking disk I/O on the main server thread; writer thread completely owns persistence via bounded MPSC queue.
  - Versioned binary format with VarInt/VarLong encoding, dictionary metadata, block headers, and CRC32 checksums.
  - LZ4 block compression (default) with configurable storage profiles (`balanced`, `hdd`, `ssd`).
  - Size-based log rotation and configurable retention cleanup.
- **FR-4: Persistent Indexing & Queries**:
  - Periodic atomic index checkpoints indexing by time, player UUID, container block pos, and log segment byte offset.
  - Automatic index rebuild from log metadata if index is stale or corrupted.
- **FR-5: Crash Safety & Recovery**:
  - Startup segment validation, corrupted/partial block tail truncation/quarantine, and clean sequence resumption.
- **FR-6: Non-Destructive Rollback**:
  - Safe compensation mechanism reversing item deltas with inventory state pre-validation, no item duplication, and full audit trail.
- **FR-7: Command Suite & Administration**:
  - `/chestlog inspect [<player> | <pos> | <time>]` with pagination.
  - `/chestlog rollback` with dry-run preview and safety validation.
  - `/chestlog purge` with confirmation safeguard.
  - `/chestlog stats` for queue depth, batch sizes, compression ratio, and I/O metrics.
- **FR-8: Singleplayer & Dedicated Lifecycle**:
  - Per-world storage directory isolation and clean thread shutdown on world exit / server stop.

## 3. Acceptance Criteria
- `./gradlew check` builds cleanly and passes all unit, fuzz, and integration tests.
- Zero disk blocking on the server thread during high-frequency container transactions.
- Logs survive server kill/crash with automatic block recovery on restart.
- Rollback accurately restores container states without duplicating or deleting unrelated items.
