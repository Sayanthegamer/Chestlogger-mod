# Specification: Comprehensive Repository Maintenance, Version Sync & Release Preparation (v2.6.0)

## Overview
This track delivers heavy repository maintenance for ChestLogger. It consolidates and completes in-progress rollback persistence work, synchronizes versions across all build configurations, manifests, and documentation to v2.6.0 (incorporating live container rollback persistence, safe mass-claiming, anti-sniping historical defense, strict permission segregation, and GUI polish), executes full test suite verification across Fabric and Paper modules, validates GitHub Actions CI compatibility, and prepares a clean, push-ready repository state.

## Functional Requirements
1. **Rollback Persistence Track Finalization**:
   - Complete Phases 4 & 5 of `rollback_persistence_fix_20260819` (component restoration warning/handling, audit verification, and end-to-end integration tests).
   - Ensure all Fabric and Paper rollback tests pass.
   - Conclude and mark `rollback_persistence_fix_20260819` as completed in `conductor/tracks.md`.
2. **Version Synchronization to v2.6.0**:
   - Update `gradle.properties`: `mod_version=2.6.0`.
   - Update Web UI manifests: `assets/chestlogger/web/manifest.json` in `chestlogger-common` and `chestlogger-fabric`.
   - Audit `plugin.yml` and `fabric.mod.json` token expansions (`${version}`).
   - Update documentation references in `README.md`, `docs/COMMANDS.md`, `docs/USER_GUIDE.md`, `docs/MODERATOR_GUIDE.md`, and Conductor definition documents (`product.md`, `tech-stack.md`).
3. **Multi-Platform Build & Test Verification**:
   - Execute `./gradlew check` to verify 100% passing test suite across `chestlogger-common`, `chestlogger-fabric`, and `chestlogger-paper`.
   - Execute `./gradlew build` to verify clean jar packaging for `chestlogger-fabric-2.6.0.jar` and `chestlogger-paper-2.6.0.jar`.
4. **CI & Workflow Validation**:
   - Validate `.github/workflows/ci.yml` pipeline against Java 25 and Gradle 9.5.1 dependencies.
5. **Git Hygiene & Release Readiness**:
   - Audit uncommitted files, verify `.gitignore` integrity (e.g. no build artifacts or temp logs tracked).
   - Ensure git tree is clean, properly staged, and ready for upstream synchronization.

## Non-Functional Requirements
- **Zero Degradation**: No regressions in existing unit tests, benchmarks, or thread safety invariants.
- **Documentation Parity**: Complete alignment between code version and user-facing documentation.
- **Deterministic Builds**: Build artifacts must compile cleanly without warnings or deprecated Loom configurations.

## Acceptance Criteria
- [ ] In-progress rollback persistence tests and implementation committed with 100% pass rate.
- [ ] Version `2.6.0` consistently reflected in `gradle.properties`, web manifest, and documentation.
- [ ] `./gradlew check` passes with zero test failures across all modules.
- [ ] `./gradlew build` successfully creates `chestlogger-fabric-2.6.0.jar` and `chestlogger-paper-2.6.0.jar`.
- [ ] `conductor/tracks.md` updated with completed rollback fix track and newly registered maintenance track.
- [ ] Git working directory clean with all changes committed following conventional commits.

## Out of Scope
- Introducing new gameplay features or major protocol changes (focus is strictly on maintenance, stabilization, version bump, and release preparation).
