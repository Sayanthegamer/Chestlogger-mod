# Specification: Player Container Claiming Usability & Command Help Parity

## 1. Overview
Resolve player confusion around container claiming accessibility and pre-existing container protection by implementing seamless command execution parity between Fabric and Paper. Regular players must be able to view an intuitive, permission-filtered help menu when running `/chestlog` (or `/cl`) with no arguments, easily claim pre-existing / pre-mod containers via raycast (`/chestlog claim`) or radius mass-claiming (`/chestlog claim <radius>`), and receive clear in-game guidance on unclaimed containers.

## 2. Functional Requirements
1. **Root `/chestlog` Command Handling & Permission Filtered Help**:
   - On Fabric and Paper, executing `/chestlog` or `/cl` with no arguments by a non-op player must display a formatted help menu listing all available player commands:
     - `/chestlog claim [radius]` - Claim targeted container or area of containers (up to 16 blocks)
     - `/chestlog unclaim` - Remove claim from targeted container
     - `/chestlog transfer <newOwner>` - Transfer container claim to another player
     - `/chestlog trust <player>` - Allow a player to access your containers
     - `/chestlog untrust <player>` - Revoke container access from a player
     - `/chestlog trustlist` - View your list of trusted players
     - `/chestlog trace [hand]` - Trace the history and provenance of the item held in your main hand
   - For operators / administrators with inspect permissions, running `/chestlog` with no arguments continues to toggle inspect mode (or `/chestlog help` can display full command listings).
   - Add explicit `/chestlog help` sub-command node on Fabric and Paper matching permission levels.

2. **Pre-Existing Container Claim Ergonomics & Anti-Sniping**:
   - Ensure singular raycast claiming (`/chestlog claim`) and radius mass-claiming (`/chestlog claim <radius>`) work identically across Fabric and Paper.
   - Maintain anti-sniping protection (`AntiSnipingGuard`) allowing players to claim pre-mod / unclaimed wilderness containers while blocking claiming if another non-trusted player has transaction history in the container.
   - Double-chest pairing is preserved and claimed atomically in both single and radius modes.

3. **In-Game Guidance**:
   - Ensure clear chat feedback when a claim attempt succeeds, is blocked by anti-sniping, or when looking at non-containers.

## 3. Non-Functional & Quality Requirements
- Zero overhead on server tick loop; raycasts and spatial scans bounded to max 16 blocks for players (32 for admins).
- Backward compatibility with existing `claims.json` format and storage engines.
- Complete unit test coverage for Fabric command dispatching, Paper command executor, and anti-sniping guard validation.

## 4. Acceptance Criteria
- Running `/chestlog` or `/cl` as a regular (non-op) player on Fabric and Paper displays the friendly player help guide without syntax errors.
- Running `/chestlog` as an admin toggles inspect mode.
- Regular players can claim pre-existing containers via `/chestlog claim` (targeted) and `/chestlog claim <radius>` (up to 16 blocks).
- `./gradlew test` passes 100% across all subprojects (`chestlogger-common`, `chestlogger-fabric`, `chestlogger-paper`).

## 5. Out of Scope
- Modifying the underlying binary transaction log storage format.
- Adding third-party economy integration for claim blocks.
