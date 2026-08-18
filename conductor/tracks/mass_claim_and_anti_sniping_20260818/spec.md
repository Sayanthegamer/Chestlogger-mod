# Specification: Player Mass-Claiming, Anti-Sniping Historical Protection & Claim Transfers

## 1. Overview
Empower players to mass-claim multiple containers across large bases and warehouses in a single command (`/chestlog claim <radius>`), while implementing multi-layered anti-sniping defenses to prevent claim theft on fresh installations and pre-existing worlds:
1. Transaction history placer verification (checking `CONTAINER_PLACE` and previous interaction logs).
2. Third-party land claim integration (GriefPrevention, WorldGuard, Towny, Lands).
3. Container ownership transfer command (`/chestlog claim transfer <newOwner>` or `/chestlog transfer <newOwner>`).

## 2. Functional Requirements
1. **Player-Safe Mass Claiming (`/chestlog claim [radius]`)**:
   - Regular players can specify radius up to 16 blocks (e.g. `/chestlog claim 10`).
   - Mass claiming safely claims only containers that are placed by the claimant or unowned in wilderness, automatically skipping containers owned or placed by other players.
   - Admins can specify radius up to 32 blocks with force-override.
2. **Historical Placer Anti-Sniping Protection**:
   - When attempting to claim an unclaimed container, ChestLogger queries the transaction log for `CONTAINER_PLACE` and interaction history.
   - If the container was placed/used by another player and the claimant is not trusted by that player (and is not an admin), the claim is blocked.
3. **Land Claim Provider Hook (Paper)**:
   - Provide a safe, reflection-based `LandClaimProvider` that checks if target coordinates fall within another player's land claim in GriefPrevention, WorldGuard, Towny, or Lands.
   - Blocks unauthorized players from claiming containers within another player's protected region.
4. **Claim Transfer System (`/chestlog claim transfer <newOwner>` or `/chestlog transfer <newOwner>`)**:
   - Container owner or Admin can transfer single or double chest claims to `<newOwner>`.
   - Atomically updates `claims.json` and updates owner indexes.

## 3. Acceptance Criteria
- Unit and integration tests verify player mass-claiming bounds, anti-sniping history rejection, claim transfers, and double-chest synchronization.
- `./gradlew test` passes 100% across all subprojects.
