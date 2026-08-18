# Implementation Plan: Fabric GUI Visual Polish & Column Alignment

## Phase 1: Provenance Screen Layout & Action Formatting Overhaul [checkpoint: a7bbdee]
- [x] Task: Refactor `ChestLogProvenanceScreen.java` [a7bbdee]
  - [x] Implement `formatActionName(String rawAction)` mapping long enum strings to clean labels (`Shift Extract`, `Hopper Out`, etc.)
  - [x] Format confidence badges concisely (`[EXACT]`, `[HIGH]`, `[PROB]`)
  - [x] Redefine proportional column coordinates (`getColumnX`) with safety margins
  - [x] Add item name truncation and hover tooltip for complete unclipped details
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) [a7bbdee]

## Phase 2: Configuration Screen Container Panel, Field Labels & Layout Polish [checkpoint: 0807842]
- [x] Task: Overhaul `ChestLogConfigScreen.java` visual structure [0807842]
  - [x] Add `renderPanelBackground` with dark container box, borders, and section dividers
  - [x] Add explicit text labels above text input fields (`Discord Webhook URL:`, `Bot Username:`, `Web Server Port:`)
  - [x] Align tab bar, steppers, toggles, and Save & Close footer buttons within the container panel
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) [0807842]

## Phase 3: Multi-Module Test Suite & Final Release Verification
- [ ] Task: Run automated test suite across all modules (`./gradlew test --rerun-tasks`)
- [ ] Task: Build release binaries (`./gradlew build`)
- [ ] Task: Track Completion Review & Verification
