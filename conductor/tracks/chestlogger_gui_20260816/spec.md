# Specification: ChestLogger Phase 11 History GUI (Minecraft 26.2)

## Overview
Introduce a polished, informative, client-side Graphical History Viewer (`ChestLogScreen`) for **Minecraft 26.2** that enables players and server administrators to visually browse and filter container audit logs with real item icons, color-coded quantities, and server-backed pagination.

---

## 🔒 Hard Architectural Constraints

1. **Pure Informational Client Screen (`extends Screen`)**:
   - **MUST NOT** use `AbstractContainerMenu`, `MenuType`, `Slot`, `HandledScreen`, fake chest inventories, or fake item stacks for UI placeholders.
   - The GUI is purely a client-side presentation widget receiving bounded data frames from the server.
   - The client never directly accesses `.clog` or `.cidx` disk files.

2. **Real Item Icon Rendering**:
   - Resolve logged item identifiers through the client item registry (`BuiltInRegistries.ITEM`).
   - Render real item models using `guiGraphics.item(ItemStack, x, y)`.
   - Never substitute barrier blocks as placeholders unless the logged item itself is `minecraft:barrier`.
   - If an item identifier is unknown, display a neutral fallback indicator with the raw item string.

3. **Server-Authoritative Networking Protocol**:
   - Serverbound: `ChestLogPageRequestPayload` (UUID queryId, int requestedPage, QueryFilters).
   - Clientbound: `ChestLogPagePayload` (UUID queryId, int pageIndex, int totalPages, int totalRecords, ContainerHeader, List<DisplayLogRecord>).
   - Server strictly enforces permission checks, rate limits, page bounds, and filter validation.

4. **Zero Backend Regression**:
   - Reuses the existing `QueryEngine`, `PersistentIndexManager`, and `TransactionEventQueue`.
   - All Phase 1 through 10 storage, indexing, rollback, crash recovery, and lifecycle tests must remain 100% passing.

---

## 🎨 GUI Functional Requirements

1. **Header & Context Information**:
   - Container block type (e.g. Chest, Trapped Chest, Barrel, Shulker Box).
   - Block coordinates `(X, Y, Z)` and Dimension identifier.
   - Current page indicator: `Page X / Y` and total record count.

2. **Log Entry Table Rows**:
   - Timestamp (formatted `HH:mm:ss` / `YYYY-MM-DD`).
   - Actor Name & Type badge (Player vs Automation Hopper/Minecart).
   - Action type (Insert, Extract, Shift Click, Swap, Rollback Compensation).
   - Real item icon with hover tooltip showing display name and count.
   - Signed quantity with color conventions: Green (`+N`) for insertions, Red (`-N`) for extractions.
   - Target Slot index.

3. **Pagination & Navigation Controls**:
   - `[First]`, `[Previous]`, `[Next]`, `[Last]`, `[Refresh]`, `[Close]`.
   - Page size bounded to 25 entries per page.
   - Buttons dynamically enabled/disabled based on page bounds.
   - Mouse scroll wheel support for smooth row scrolling.

4. **Interactive Filters**:
   - Search inputs for Player Name, Item ID, and Time Window.
   - Filter changes trigger serverbound requests to evaluate queries server-side.

5. **Error & Fallback Handling**:
   - Friendly in-GUI feedback for expired queries, permission denial, or empty query results.
   - Non-fatal recovery if optional metadata is missing.
   - Console command `/chestlog inspect` continues to function in headless/console mode.

---

## 🧪 Acceptance Criteria
- Executing `/chestlog inspect` opens `ChestLogScreen` directly on the player's client.
- Zero container menu open/close sounds or fake container synchronization events.
- Item icons accurately represent logged items (e.g., diamond, chest, iron ingot).
- Pagination smoothly moves between pages with server-backed updates.
- All 44 existing test suites continue to pass without regression.
