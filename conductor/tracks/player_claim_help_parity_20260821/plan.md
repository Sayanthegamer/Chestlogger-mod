# Implementation Plan: Player Container Claiming Usability & Command Help Parity

## Phase 1: Fabric Root Command & Help Menu Implementation [checkpoint: c7be9f4]
- [x] Task: Write Unit Tests for Fabric `/chestlog` Root Execution & Player Help Menu [e750dde]
  - [x] Create test for non-op execution of `/chestlog` rendering player help commands
  - [x] Create test for op execution of `/chestlog` toggling inspect mode
  - [x] Create test for `/chestlog help` subcommand node
- [x] Task: Implement Fabric Root `/chestlog` Handler & Help Command [c7be9f4]
  - [x] Add root `.executes(...)` handler in `ChestLoggerCommands.java`
  - [x] Implement `executeRootOrHelp(CommandSourceStack source)` with permission checks
  - [x] Register explicit `help` sub-command node in `ChestLoggerCommands.java`
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 2: Paper Command & Help Menu Parity [checkpoint: 54146f6]
- [x] Task: Write Unit Tests for Paper `/chestlog help` & Tab Completion [cbb7ca6]
  - [x] Test non-op tab completion and `/chestlog help` command output
  - [x] Test operator `/chestlog` toggle vs `/chestlog help` display
- [x] Task: Implement Paper `/chestlog help` and Command Parity Updates [54146f6]
  - [x] Add explicit `case "help"` in `PaperCommandExecutor.java`
  - [x] Update `sendHelp` and tab-completion for `help` subcommand
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 3: Claiming Ergonomics & End-to-End Verification
- [x] Task: Write Unit Tests for Anti-Sniping Pre-Mod Container Claiming Scenarios [76dc3fd]
  - [x] Test claiming pre-existing container with empty history (allowed)
  - [x] Test claiming pre-existing container with past interaction by claimant (allowed)
  - [x] Test claiming pre-existing container with past interaction by another player (blocked)
  - [x] Test mass claiming radius limits for players vs admins
- [x] Task: Verify & Refine Anti-Sniping and Claim Feedback Messages [65ddf24]
  - [x] Ensure descriptive error messaging and clear success notifications
- [ ] Task: Full Test Suite & Build Verification
  - [ ] Run `./gradlew check` across all modules
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)
