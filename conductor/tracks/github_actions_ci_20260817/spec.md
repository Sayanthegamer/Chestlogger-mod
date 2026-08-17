# Track Specification: GitHub Actions CI Workflow & Build Verification

## 1. Overview
Establish an automated continuous integration (CI) pipeline using GitHub Actions (`.github/workflows/ci.yml`). The workflow automatically builds the Fabric mod, executes all 255+ automated unit, integration, and security tests, verifies zero compilation warnings or code style deviations, and archives the release mod JARs (`chestlogger-*.jar`) as downloadable workflow artifacts.

---

## 2. Functional Requirements
1. **Triggers**:
   - `push` events on `master` and `main` branches.
   - `pull_request` events targeting `master` and `main` branches.
   - `workflow_dispatch` for manual workflow triggering.
2. **Environment & Runtime**:
   - Runner OS: `ubuntu-latest`.
   - Java Version: JDK 25 via `actions/setup-java@v4` with Eclipse Temurin / Zulu distribution.
   - Gradle Setup: `gradle/actions/setup-gradle@v4` with automatic dependency caching.
3. **Execution Pipeline**:
   - Make Gradle wrapper executable (`chmod +x ./gradlew`).
   - Run complete verification: `./gradlew check --stacktrace`.
   - Assemble production binaries: `./gradlew build`.
   - Upload mod artifacts (`build/libs/chestlogger-*.jar`) via `actions/upload-artifact@v4` with 30-day retention.
4. **Local Workflow Linter & Validation**:
   - Verify YAML schema correctness, action version compatibility, and step ordering.

---

## 3. Acceptance Criteria
1. `.github/workflows/ci.yml` is created and adheres to GitHub Actions syntax standards.
2. The workflow properly targets JDK 25 and modern Fabric Loom 1.17 / Gradle 9.5.1 requirements.
3. Workflow artifact packaging includes both binary JAR and sources JAR.
4. All automated project tests pass cleanly during the workflow run.
