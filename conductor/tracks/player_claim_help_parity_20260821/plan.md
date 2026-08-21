# Implementation Plan: Player Container Claiming Usability & Command Help Parity

## Phase 1: Fabric Root Command & Help Menu Implementation
- [x] Task: Write Unit Tests for Fabric `/chestlog` Root Execution & Player Help Menu [e750dde]
  - [x] Create test for non-op execution of `/chestlog` rendering player help commands
  - [x] Create test for op execution of `/chestlog` toggling inspect mode
  - [x] Create test for `/chestlog help` subcommand node
- [ ] Task: Implement Fabric Root `/chestlog` Handler & Help Command
  - [ ] Add root `.executes(...)` handler in `ChestLoggerCommands.java`
  - [ ] Implement `executeRootOrHelp(CommandSourceStack source)` with permission checks
  - [ ] Register explicit `help` sub-command node in `ChestLoggerCommands.java`
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 2: Paper Command & Help Menu Parity
- [ ] Task: Write Unit Tests for Paper `/chestlog help` & Tab Completion
  - [ ] Test non-op tab completion and `/chestlog help` command output
  - [ ] Test operator `/chestlog` toggle vs `/chestlog help` display
- [ ] Task: Implement Paper `/chestlog help` and Command Parity Updates
  - [ ] Add explicit `case "help"` in `PaperCommandExecutor.java`
  - [ ] Update `sendHelp` and tab-completion for `help` subcommand
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 3: Claiming Ergonomics & End-to-End Verification
- [ ] Task: Write Unit Tests for Anti-Sniping Pre-Mod Container Claiming Scenarios
  - [ ] Test claiming pre-existing container with empty history (allowed)
  - [ ] Test claiming pre-existing container with past interaction by claimant (allowed)
  - [ ] Test claiming pre-existing container with past interaction by another player (blocked)
  - [ ] Test mass claiming radius limits for players vs admins
- [ ] Task: Verify & Refine Anti-Sniping and Claim Feedback Messages
  - [ ] Ensure descriptive error messaging and clear success notifications
- [ ] Task: Full Test Suite & Build Verification
  - [ ] Run `./gradlew check` across all modules
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)
