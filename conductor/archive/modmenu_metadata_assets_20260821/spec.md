# Specification: Mod Menu Integration, Metadata Enrichment & Brand Icon Assets

## Overview
Ensure ChestLogger provides a first-class user experience when viewed inside the Fabric **Mod Menu** mod list, populating the official brand logo icon, comprehensive author/source/issue metadata, and connecting Mod Menu's in-game "Configure" button to ChestLogger's client configuration interface.

## Functional Requirements
1. **Brand Icon Asset**:
   - Embed the provided logo icon as `assets/chestlogger/icon.png` in `chestlogger-fabric` (and `chestlogger-common`).
   - Register `"icon": "assets/chestlogger/icon.png"` in `fabric.mod.json`.
2. **Metadata Enrichment**:
   - Populate author information (`Sayanthegamer`), contact links (`homepage`, `sources`, `issues` pointing to `https://github.com/Sayanthegamer/Chestlogger-mod`), license (`MIT`), and detailed multi-line description.
   - Add `custom.modmenu` metadata links and badges.
3. **Mod Menu In-Game Config Screen Integration**:
   - Provide a `modmenu` entrypoint (`com.chestlogger.client.modmenu.ChestLoggerModMenu`) implementing `ModMenuApi` via `compileOnly` / optional runtime resolution.
   - Provide `ConfigScreenFactory<?> getModConfigScreenFactory()` returning ChestLogger's client config GUI (`ChestLogConfigScreen`).

## Acceptance Criteria
- [ ] Mod Menu displays ChestLogger with the custom glowing chest icon in the mod list.
- [ ] Mod description, authors, license, and issue tracker links are fully visible in the details pane.
- [ ] Clicking "Configure" in Mod Menu opens ChestLogger's in-game config GUI (`ChestLogConfigScreen`).
- [ ] `./gradlew check` passes 100% with no regression or missing resource errors.
