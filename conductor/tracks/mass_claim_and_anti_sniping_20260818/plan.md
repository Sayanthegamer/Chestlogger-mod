# Implementation Plan: Player Mass-Claiming, Anti-Sniping Historical Protection & Claim Transfers

## Phase 1: Core Claim Manager Transfer API & Verification
- [x] Task: Implement transferClaim and transferDoubleChest in ClaimManager (chestlogger-common)
- [x] Task: Write unit tests for ClaimManager transfer logic
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 2: Anti-Sniping History Guard & Land Claim Hook
- [x] Task: Implement historical placer & interaction check for unclaimed container validation
- [x] Task: Implement safe LandClaimHook for Paper (GriefPrevention, WorldGuard, Towny, Lands)
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 3: Player Mass-Claiming & Transfer Command Integration
- [x] Task: Update executeClaim and transfer commands in Fabric (ChestLoggerCommands)
- [x] Task: Update handleClaim and transfer commands in Paper (PaperCommandExecutor)
- [x] Task: Update tab completion and documentation (USER_GUIDE.md, COMMANDS.md, MODERATOR_GUIDE.md)
- [x] Task: Verify full build with ./gradlew test
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)
