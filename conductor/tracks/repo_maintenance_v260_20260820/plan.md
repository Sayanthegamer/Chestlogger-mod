# Implementation Plan: Heavy Repository Maintenance, Version Sync & Release Preparation (v2.6.0)

## Phase 1: Rollback Persistence Track Finalization & Verification [checkpoint: 7afea44]

- [x] Task: Complete Phase 4 Component/Metadata Restoration in `FabricRollbackExecutor` and `PaperRollbackExecutor` `7afea44`
  - [x] Implement component warning logging and metadata hash delta verification tests `7afea44`
- [x] Task: Complete Phase 5 Audit Integrity & Final Integration Tests for Rollback Persistence `7afea44`
  - [x] Verify `ROLLBACK_COMPENSATION` ordering and partial rollback fallback tests in `FabricRollbackExecutorTest` `7afea44`
  - [x] Mark `rollback_persistence_fix_20260819` as completed in its `plan.md` and update `conductor/tracks.md` `7afea44`
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) `7afea44`


## Phase 2: Version Synchronization & Manifest Updates (v2.6.0) [checkpoint: f8cff40]

- [x] Task: Update build configuration version to 2.6.0 `f8cff40`
  - [x] Update `gradle.properties` (`mod_version=2.6.0`) `f8cff40`
- [x] Task: Update embedded web dashboard manifests `f8cff40`
  - [x] Update `chestlogger-common/src/main/resources/assets/chestlogger/web/manifest.json` (`"version": "2.6.0"`) `f8cff40`
  - [x] Update `chestlogger-fabric/src/main/resources/assets/chestlogger/web/manifest.json` (`"version": "2.6.0"`) `f8cff40`
- [x] Task: Audit `plugin.yml` and `fabric.mod.json` token interpolation `f8cff40`
  - [x] Verify `expand "version": project.version` in `chestlogger-fabric/build.gradle` and `chestlogger-paper/build.gradle` `f8cff40`
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) `f8cff40`

## Phase 3: Documentation Alignment & Specification Reference Audit [checkpoint: c235fd0]

- [x] Task: Update README.md and documentation references to v2.6.0 `c235fd0`
  - [x] Update `README.md` version badges, artifact jar names (`chestlogger-fabric-2.6.0.jar`, `chestlogger-paper-2.6.0.jar`), and feature highlights `c235fd0`
  - [x] Update `docs/COMMANDS.md`, `docs/USER_GUIDE.md`, and `docs/MODERATOR_GUIDE.md` references where applicable `c235fd0`
- [x] Task: Update Conductor product and tech-stack references `c235fd0`
  - [x] Verify `conductor/product.md` and `conductor/tech-stack.md` match 2.6.0 capabilities `c235fd0`
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) `c235fd0`

## Phase 4: Multi-Platform Test & Build Verification

- [ ] Task: Run full test suite across all subprojects
  - [ ] Run `./gradlew check` to ensure 100% test pass rate across `chestlogger-common`, `chestlogger-fabric`, and `chestlogger-paper`
- [ ] Task: Run full release build and artifact packaging
  - [ ] Run `./gradlew build` and verify output jar files in `build/libs`
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 5: Git Hygiene, CI Verification & Upstream Sync Preparation

- [ ] Task: Verify GitHub Actions CI workflow integrity
  - [ ] Validate `.github/workflows/ci.yml` steps for build, test, and release artifact generation
- [ ] Task: Git tree audit and status validation
  - [ ] Check `git status` to ensure zero untracked debris or dirty artifacts
  - [ ] Review commit log (`git log`) and prepare final push checklist
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)
