# Implementation Plan: Strict Player vs Admin Command & Permission Segregation

## Phase 1: Fabric Command Tree Permission Hardening
- [x] Task: Add Op Level 2+ requirements to inspectNode, iNode, wandNode, and trace <pos> in ChestLoggerCommands
- [x] Task: Verify claim, unclaim, trust, untrust, trustlist, and trace hand remain accessible to non-op players
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 2: Paper Command Executor & Tab-Completion Permission Hardening
- [x] Task: Update PaperCommandExecutor.onTabComplete to dynamically filter subcommands by permissions
- [x] Task: Update handleTrace in PaperCommandExecutor to allow main-hand trace for regular players while protecting coordinate trace
- [x] Task: Register chestlogger.trace (default: true) in plugin.yml
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 3: Automated Verification & Documentation Synchronization
- [x] Task: Write/update permission unit tests in Fabric and Paper test suites
- [x] Task: Verify full build with ./gradlew test
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)
