# Implementation Plan: Mod Menu Integration, Metadata Enrichment & Brand Icon Assets

## Phase 1: Icon Assets & Metadata Enrichment (`fabric.mod.json`) [checkpoint: 96208d0]
- [x] Task: Write Unit Tests for `fabric.mod.json` Schema, Icon & Metadata Presence [a19b66a]
  - [x] Test that `fabric.mod.json` includes valid icon path, author, contact issues, and description
  - [x] Test that `assets/chestlogger/icon.png` is present and loadable as valid image bytes
- [x] Task: Place `icon.png` Brand Asset & Update `fabric.mod.json` Metadata [96208d0]
  - [x] Copy user logo to `chestlogger-fabric/src/main/resources/assets/chestlogger/icon.png`
  - [x] Update `fabric.mod.json` with icon, author (`Sayanthegamer`), homepage, sources, issues, and badges
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 2: Mod Menu API Entrypoint & In-Game Config Hook [checkpoint: f05bf26]
- [x] Task: Write Unit Tests for Mod Menu Entrypoint Resolution [39479cc]
  - [x] Test that `ChestLoggerModMenu` class exists and implements config screen factory method
- [x] Task: Implement `ChestLoggerModMenu` and Register in `fabric.mod.json` [f05bf26]
  - [x] Add `modmenu` compileOnly dependency / interface integration
  - [x] Implement `ChestLoggerModMenu` providing `ChestLogConfigScreen` factory
  - [x] Register `modmenu` entrypoint in `fabric.mod.json`
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 3: Packaging & End-to-End Build Verification
- [ ] Task: Verify JAR Asset Packaging & Dependency Isolation
  - [ ] Verify `chestlogger-fabric.jar` contains `assets/chestlogger/icon.png` and valid `fabric.mod.json`
- [ ] Task: Full Test Suite & Build Verification
  - [ ] Run `./gradlew check` across all modules
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)
