# Implementation Plan: Fabric GUI Visual Polish & Column Alignment

## Phase 1: Provenance Screen Layout & Action Formatting Overhaul
- [ ] Task: Refactor `ChestLogProvenanceScreen.java`
  - [ ] Implement `formatActionName(String rawAction)` mapping long enum strings to clean labels (`Shift Extract`, `Hopper Out`, etc.)
  - [ ] Format confidence badges concisely (`[EXACT]`, `[HIGH]`, `[PROB]`)
  - [ ] Redefine proportional column coordinates (`getColumnX`) with safety margins
  - [ ] Add item name truncation and hover tooltip for complete unclipped details
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 2: Configuration Screen Container Panel, Field Labels & Layout Polish
- [ ] Task: Overhaul `ChestLogConfigScreen.java` visual structure
  - [ ] Add `renderPanelBackground` with dark container box, borders, and section dividers
  - [ ] Add explicit text labels above text input fields (`Discord Webhook URL:`, `Bot Username:`, `Web Server Port:`)
  - [ ] Align tab bar, steppers, toggles, and Save & Close footer buttons within the container panel
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 3: Multi-Module Test Suite & Final Release Verification
- [ ] Task: Run automated test suite across all modules (`./gradlew test --rerun-tasks`)
- [ ] Task: Build release binaries (`./gradlew build`)
- [ ] Task: Track Completion Review & Verification
