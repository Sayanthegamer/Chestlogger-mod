# Project Workflow

## Guiding Principles

1.  **The Plan is the Source of Truth:** All work must be tracked in `plan.md`
2.  **The Tech Stack is Deliberate:** Changes to the tech stack must be
    documented in `tech-stack.md` *before* implementation
3.  **Test-Driven Development:** Write unit tests before implementing
    functionality
4.  **High Code Coverage:** Aim for >80% code coverage for all modules
5.  **User Experience First:** Every decision should prioritize user experience
6.  **Non-Interactive & CI-Aware:** Prefer non-interactive commands. Use
    `CI=true` for watch-mode tools (tests, linters) to ensure single execution.

## Task Workflow

All tasks follow a strict lifecycle:

### Standard Task Workflow

1.  **Select Task:** Choose the next available task from `plan.md` in sequential
    order

2.  **Mark In Progress:** Before beginning work, edit `plan.md` and change the
    task from `[ ]` to `[~]`

3.  **Write Failing Tests (Red Phase):**

    -   Create a new test file for the feature or bug fix.
    -   Write one or more unit tests that clearly define the expected behavior
        and acceptance criteria for the task.
    -   **CRITICAL:** Run the tests and confirm that they fail as expected. This
        is the "Red" phase of TDD. Do not proceed until you have failing tests.

4.  **Implement to Pass Tests (Green Phase):**

    -   Write the minimum amount of application code necessary to make the
        failing tests pass.
    -   Run the test suite again and confirm that all tests now pass. This is
        the "Green" phase.

5.  **Refactor (Optional but Recommended):**

    -   With the safety of passing tests, refactor the implementation code and
        the test code to improve clarity, remove duplication, and enhance
        performance without changing the external behavior.
    -   Rerun tests to ensure they still pass after refactoring.

6.  **Verify Coverage:** Run coverage reports using the project's chosen tools.
    For example, in a Java project: `./gradlew test jacocoTestReport`. Target: >80% coverage for new code.

7.  **Document Deviations:** If implementation differs from tech stack:

    -   **STOP** implementation
    -   Update `tech-stack.md` with new design
    -   Add dated note explaining the change
    -   Resume implementation

8.  **Commit Code Changes:**

    -   Stage all code changes related to the task.
    -   Propose a clear, concise commit message e.g, `feat(tracking): Detect chest pickup transactions`.
    -   Perform the commit.

9.  **Attach Task Summary with Git Notes:**

    -   **Step 9.1: Get Commit Hash:** Obtain the hash of the *just-completed
        commit* (`git log -1 --format="%H"`).
    -   **Step 9.2: Draft Note Content:** Create a detailed summary for the
        completed task. This should include the task name, a summary of changes,
        a list of all created/modified files, and the core "why" for the change.
    -   **Step 9.3: Attach Note:** Use the `git notes` command to attach the
        summary to the commit: `git notes add -m "<note content>" <commit_hash>`.

10. **Get and Record Task Commit SHA:**

    -   **Step 10.1: Update Plan:** Read `plan.md`, find the line for the
        completed task, update its status from `[~]` to `[x]`, and append the
        first 7 characters of the *just-completed commit's* commit hash.
    -   **Step 10.2: Write Plan:** Write the updated content back to `plan.md`.

11. **Commit Plan Update:**

    -   **Action:** Stage the modified `plan.md` file.
    -   **Action:** Commit this change with a descriptive message (e.g.,
        `conductor(plan): Mark task 'Initialize Fabric Loom 26.2 build' as complete`).

### Task Correction & Plan Amendment Workflows

When an implemented task or phase requires corrections, amendments, or additions, follow these standard workflows to maintain plan integrity and avoid untracked code drift:

1.  **In-Flight Refinements:** If minor gaps are found while a task is actively
    in-progress (`[~]`), make the adjustments directly in the active
    implementation stream and ensure passing tests before committing.
2.  **Code Review Corrections (`conductor-review`):** If issues are identified
    during or after a code review, instruct the agent to review your changes
    (e.g., *"run a review"* or triggering the action manually in compatible
    clients). The review agent will automatically append a `Review Fixes` phase
    to `plan.md` so that correction tasks are formally tracked and
    checkpointed.
3.  **Logical State Reversions (`conductor-revert`):** If a task implementation
    is fundamentally flawed or needs to be redone, instruct the agent to revert
    the changes (e.g., *"revert the last task"* or triggering the action
    manually in compatible clients). This safely rolls back associated git
    commits and resets the task state in `plan.md` back to pending `[ ]` to
    allow a clean restart.

### Phase Completion Verification and Checkpointing Protocol

**Trigger:** This protocol is executed immediately after a task is completed
that also concludes a phase in `plan.md`.

1.  **Announce Protocol Start:** Inform the user that the phase is complete and
    the verification and checkpointing protocol has begun.

2.  **Ensure Test Coverage for Phase Changes:**

    -   **Step 2.1: Determine Phase Scope:** To identify the files changed in
        this phase, you must first find the starting point. Read `plan.md` to
        find the Git commit SHA of the *previous* phase's checkpoint. If no
        previous checkpoint exists, the scope is all changes since the first
        commit.
    -   **Step 2.2: List Changed Files:** Execute `git diff --name-only
        <previous_checkpoint_sha> HEAD` to get a precise list of all files
        modified during this phase.
    -   **Step 2.3: Verify and Create Tests:** For each file in the list:
        -   **CRITICAL:** First, check its extension. Exclude non-code files
            (e.g., `.json`, `.md`, `.yaml`).
        -   For each remaining code file, verify a corresponding test file
            exists.
        -   If a test file is missing, you **must** create one. Before writing
            the test, **first, analyze other test files in the repository to
            determine the correct naming convention and testing style.** The new
            tests **must** validate the functionality described in this phase's
            tasks (`plan.md`).

3.  **Execute Automated Tests with Proactive Debugging:**

    -   Before execution, you **must** announce the exact shell command you will
        use to run the tests.
    -   **Example Announcement:** "I will now run the automated test suite to
        verify the phase. **Command:** `./gradlew test`"
    -   Execute the announced command.
    -   If tests fail, you **must** inform the user and begin debugging. You may
        attempt to propose a fix a **maximum of two times**. If the tests still
        fail after your second proposed fix, you **must stop**, report the
        persistent failure, and ask the user for guidance.

4.  **Propose a Detailed, Actionable Manual Verification Plan:**

    -   **CRITICAL:** To generate the plan, first analyze `product.md`,
        `product-guidelines.md`, and `plan.md` to determine the user-facing
        goals of the completed phase.
    -   You **must** generate a step-by-step plan that walks the user through
        the verification process, including any necessary commands and specific,
        expected outcomes.

5.  **Await Explicit User Feedback:**

    -   After presenting the detailed plan, ask the user for confirmation:
        "**Does this meet your expectations? Please confirm with yes or provide
        feedback on what needs to be changed.**"
    -   **PAUSE** and await the user's response. Do not proceed without an
        explicit yes or confirmation.

6.  **Identify Target Commit for Report:**

    -   Do NOT create a new empty commit for checkpointing.
    -   Identify the hash of the last functional commit made during this phase. This will be the target for the verification report.

7.  **Attach Auditable Verification Report using Git Notes:**

    -   **Step 7.1: Draft Note Content:** Create a detailed verification report
        including the automated test command, the manual verification steps, and
        the user's confirmation.
    -   **Step 7.2: Attach Note:** Use the `git notes` command to attach the full report to the target commit identified in step 6.

8.  **Get and Record Phase Checkpoint SHA:**

    -   **Step 8.1: Get Commit Hash:** Obtain the hash of the *just-created
        checkpoint commit* (`git log -1 --format="%H"`).
    -   **Step 8.2: Update Plan:** Read `plan.md`, find the heading for the
        completed phase, and append the first 7 characters of the commit hash in
        the format `[checkpoint: <sha>]`.
    -   **Step 8.3: Write Plan:** Write the updated content back to `plan.md`.

9.  **Commit Plan Update:**

    -   **Action:** Stage the modified `plan.md` file.
    -   **Action:** Commit this change with a descriptive message following the
        format `conductor(plan): Mark phase '<PHASE NAME>' as complete`.

10. **Announce Completion:** Inform the user that the phase is complete and the
    checkpoint has been created, with the detailed verification report attached
    as a git note.

### Quality Gates

Before marking any task complete, verify:

-   [ ] All tests pass
-   [ ] Code coverage meets requirements (>80%)
-   [ ] Code follows project's code style guidelines (as defined in
    `code_styleguides/`)
-   [ ] All public functions/methods are documented (e.g., Javadoc)
-   [ ] Type safety is enforced
-   [ ] No compilation or static analysis warnings
-   [ ] Documentation updated if needed
-   [ ] No security vulnerabilities or concurrency races introduced

## Development Commands

### Setup

```bash
# Verify Gradle wrapper & environment
./gradlew --version
```

### Daily Development

```bash
# Compile and run unit tests
./gradlew test

# Build mod jar
./gradlew build
```

### Before Committing

```bash
# Full clean build and test verification
./gradlew check
```

## Testing Requirements

### Unit Testing

-   Every module must have corresponding tests.
-   Mock Minecraft world/block entities where needed to test isolated algorithms (binary serializer, compression, index queries, rollback math).
-   Test both success and failure cases (e.g. corruption, partial blocks, queue overflows).

### Integration Testing

-   Test end-to-end container looting and automation logging in a simulated Fabric server environment.
-   Verify rollback idempotency and inventory state consistency.

## Commit Guidelines

### Message Format

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

### Types

-   `feat`: New feature
-   `fix`: Bug fix
-   `docs`: Documentation only
-   `style`: Formatting, missing semicolons, etc.
-   `refactor`: Code change that neither fixes a bug nor adds a feature
-   `test`: Adding missing tests
-   `chore`: Maintenance tasks

### Examples

```bash
git commit -m "feat(tracking): Add double chest inventory tracking"
git commit -m "fix(storage): Prevent partial block flush corruption"
git commit -m "test(rollback): Add compensation unit test"
```

## Definition of Done

A task is complete when:

1.  All code implemented to specification
2.  Unit tests written and passing
3.  Code coverage meets project requirements
4.  Documentation complete (if applicable)
5.  Code passes all compiler checks
6.  Implementation notes added to `plan.md`
7.  Changes committed with proper message
8.  Git note with task summary attached to the commit
