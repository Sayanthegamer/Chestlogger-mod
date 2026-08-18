# Specification: Strict Player vs Admin Command & Permission Segregation

## 1. Overview
Ensure strict, unambiguous separation between regular player commands and administrative moderation commands across Fabric and Paper. Regular players must have full access to personal container protection commands (`claim`, `unclaim`, `trust`, `untrust`, `trustlist`, and main-hand `trace`), while being strictly barred from administrative inspection, telemetry, staged rollbacks, log purging, and live configuration management.

## 2. Functional Requirements
1. **Fabric Command Guards**:
   - `inspect`, `i`, `wand`, and `trace <pos> [slot]` must require Op Level 2+ / console.
   - `rollback`, `stats`, `purge`, `config`, `settings`, `claim <radius>` must remain strictly Op Level 2+ / console.
   - `claim` (single), `unclaim` (own), `trust <player>`, `untrust <player>`, `trustlist`, and `trace [hand]` must remain open to all players.
2. **Paper Command Guards & Dynamic Tab-Completion**:
   - `PaperCommandExecutor.onTabComplete` must filter suggested subcommands based on `sender.hasPermission(...)`. Regular players must not see admin subcommands in autocomplete.
   - Main hand tracing `/chestlog trace` / `/chestlog trace hand` must be permitted for all players with `chestlogger.trace` (default: true).
   - Coordinate tracing `/chestlog trace <x> <y> <z>` must strictly require `chestlogger.inspect` or `chestlogger.admin`.
3. **Plugin Metadata**:
   - Update `plugin.yml` with `chestlogger.trace` (default: true) and clear descriptions.

## 3. Acceptance Criteria
- Unit tests verify permission checks for all command sub-nodes.
- `./gradlew test` passes 100% across all subprojects.
