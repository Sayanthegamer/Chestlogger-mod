# Specification: Repository Housekeeping, Obsolete Code Purge & Custom GUI Audit

## 1. Overview
This track performs a comprehensive repository cleanup to remove obsolete, legacy, and orphaned files across the codebase, audits all GUI implementations across Fabric and Paper to enforce zero-dependency custom screens, and delivers the missing client-side **Item Provenance Screen** (`ChestLogProvenanceScreen`) on Fabric for visual chain-of-custody parity with Paper.

## 2. Functional & Technical Requirements

### 2.1 Obsolete File Purge
- **Remove Root `src/` Directory**: Purge the redundant, unreferenced pre-modularization root `src/` folder (`src/main/java`, `src/test/java`).
- **Remove Orphaned Root Logs**: Clean up root runtime `logs/` artifacts and ensure `.gitignore` excludes runtime test directories properly.
- **Audit All Modules**: Verify that all remaining source files belong strictly to `chestlogger-common`, `chestlogger-fabric`, and `chestlogger-paper`.

### 2.2 GUI Slot & Custom Architecture Audit
- **Paper Bukkit Views**:
  - `PaperChestHistoryView.java`: 54 slots (double chest), pure vanilla Bukkit `InventoryHolder`, zero container menu/slot hacks.
  - `PaperProvenanceGuiView.java`: 54 slots, interactive node timeline, confidence badges.
  - `PaperChestConfigView.java`: 54 slots, categorized navigation tabs, visual dye toggles.
- **Fabric Custom Client Screens**:
  - `ChestLogScreen.java`: Pure `Screen` subclass, responsive pagination, zero `ContainerMenu` or fake inventory.
  - `ChestLogConfigScreen.java`: Pure `Screen` subclass with native `EditBox` inputs and tab switching.
  - **New Screen: `ChestLogProvenanceScreen.java`**: Pure vanilla `Screen` for Fabric clients visualizing chain-of-custody provenance graphs with node cards, confidence badges, timestamps, step numbers, and scrollable/paginated controls.

### 2.3 Fabric Provenance Networking
- **`ChestLogProvenancePayload.java`**: Server-to-client custom networking payload carrying `targetItemId`, `totalSteps`, `overallConfidence`, and serialized `ProvenanceNode` entries.
- **Server Dispatcher**: Update `/chestlog trace` and `/cl trace` in `ChestLoggerCommands.java` to dispatch `ChestLogProvenancePayload` to the player, triggering `ChestLogProvenanceScreen` client-side.

## 3. Non-Functional Requirements
- **Zero Third-Party GUI Dependencies**: All GUIs use only vanilla Minecraft screens or Bukkit inventories.
- **Zero Build Regressions**: Cleaning up legacy files must not break any active subproject or test suite.

## 4. Acceptance Criteria
- [ ] Root legacy `src/` directory and orphaned files are completely removed.
- [ ] Full build and test suite (`./gradlew test`) passes 100% after obsolete file removal.
- [ ] Fabric `/chestlog trace` opens a 100% custom `ChestLogProvenanceScreen` client-side.
- [ ] Paper and Fabric GUIs adhere to zero-dependency standards.
