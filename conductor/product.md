# Product Definition: ChestLogger

## Vision & Summary
ChestLogger is a production-quality, server-authoritative container transaction and looting tracker Fabric mod for Minecraft 26.2 (Fabric Loader 0.19.3+, Loom 1.17, Java 25, Gradle 9.5.1, unobfuscated Mojang mappings). It accurately records container inventory transactions caused by players (pickup, placement, shift-click, drag, double-click, hotbar number swaps, quick-move, and synchronization events verified via testing) and automation systems (hoppers, hopper minecarts) with zero blocking I/O on the main server thread.

## Target Environment & Compatibility
- **Minecraft**: 26.2
- **Fabric Loader**: 0.19.3+
- **Fabric Loom**: 1.17
- **Gradle**: 9.5.1
- **Java**: 25
- **Mapping**: Official unobfuscated Mojang mappings (no legacy Yarn/Intermediary remapping)
- **Environments**: Dedicated Fabric servers & Singleplayer integrated servers

## Core Functional Requirements
1. **Supported Containers**: Chests, Trapped Chests, Double Chests, Barrels, Shulker Boxes, and extensible container abstraction.
2. **Transaction & Event Model**: Slot delta tracking (+/- quantity deltas), monotonic sequence numbers, transaction UUIDs, player UUID/name, dimension, block coordinates, action types, slot index, item ID, and metadata fingerprints. (Note: container close / sync interactions must be verified empirically before logging).
3. **Storage Engine**: Append-only versioned binary log format with LZ4 (default) or Zstd block compression, block checksums, and profile-based I/O (HDD sequential batches vs SSD fast flushes).
4. **Crash Safety & Resiliency**: Automatic startup tail validation, block repair/quarantine, and metadata-driven index rebuilding.
5. **Persistent Indexing & Queries**: Multi-dimensional indexing (time, player, location, segment) supporting paginated `/chestlog inspect` queries.
6. **Compensation-based Rollback**: Non-destructive, append-only rollback mechanism with inventory state pre-validation, no item duplication, and full audit logging.
7. **Commands & Permissions**: `/chestlog inspect`, `/chestlog rollback`, `/chestlog purge`, `/chestlog stats` with permission safeguards.
8. **Integrated Server Lifecycle**: Clean lifecycle hook binding for singleplayer worlds, per-world data separation, and deterministic background thread shutdown.
9. **Embedded Web Admin Dashboard & REST API**: Zero-dependency embedded HTTP server (`com.sun.net.httpserver`) providing live telemetry stats, spatial/player queries, downloadable CSV/JSON exports, and a high-density, professional dark-carbon log observability dashboard with expandable transaction inspection, live auto-tail streaming, and quick-filter chips for browser-based administration.
10. **Item Provenance & Chain-of-Custody Engine**: Zero-world-mutation directed graph resolver reconstructing the life journey of non-fungible gear (via 64-bit component metadata fingerprints) and fungible commodities (via spatio-temporal flow heuristics) across container positions, player transfers, and automation hops with in-game 54-slot interactive GUI, `/chestlog trace` commands, REST API (`/api/v1/provenance`), and embedded Web UI node-link visualizer.
