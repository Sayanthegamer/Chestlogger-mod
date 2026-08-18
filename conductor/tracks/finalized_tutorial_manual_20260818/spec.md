# Specification: Comprehensive Project-Wide Audit & Finalized Video-Grade Tutorial Manual

## 1. Overview
Perform a 100% rigorous code audit across all modules in the ChestLogger repository (`chestlogger-common`, `chestlogger-fabric`, `chestlogger-paper`) to verify every command, GUI interaction, network payload, REST API route, security heuristic, and storage mechanic. Formulate a definitive, video-grade tutorial manual in `docs/MODERATOR_GUIDE.md` with Mermaid diagrams, ASCII UI layouts, and actionable investigation playbooks.

## 2. Functional Requirements
1. **Codebase Truth Audit**:
   - Cross-reference all 16 `ActionType` values, 8 `ActorType` values, transaction log fields, and slot deltas.
   - Verify every command node in Brigadier (`ChestLoggerCommands`) and Bukkit (`PaperCommandExecutor`), including exact argument types, tab completers, and permission nodes (`chestlogger.inspect`, `chestlogger.trust`, `chestlogger.rollback`, `chestlogger.stats`, `chestlogger.purge`, `chestlogger.web`, `chestlogger.admin`).
   - Verify all configuration keys and defaults across `chestlogger_alerts.json`, `chestlogger_web.json`, `chestlogger_inspect.json`, and `chestlogger_general.json`.
2. **Visual GUI Walkthroughs**:
   - Document Fabric client screens: `ChestLogScreen` (transaction table, filters, pagination), `ChestLogProvenanceScreen` (DAG node view, step drawer), and `ChestLogConfigScreen` (switches, inputs).
   - Document Paper 54-slot chest GUIs: `PaperChestHistoryView`, `PaperProvenanceGuiView`, and `PaperChestConfigView`.
3. **Engine Mechanics Breakdown**:
   - Smart Theft Engine: Sliding raid velocity tracker (300s window, threshold 3 containers), owner presence states (`🔴 Offline`, `🟡 Absent`, `🟢 Nearby`), and in-game interactive admin alert cards (`[Teleport]`, `[Inspect]`, `[Trust]`).
   - Item Provenance Engine: 64-bit component fingerprinting for non-fungibles, commodity flow analysis ($\Delta t \le 5\text{m}$), confidence scoring (`EXACT_LINKAGE`, `HIGH_CONFIDENCE`, `PROBABLE`).
   - Rollback Engine: Differential slot compensation math, conflict mitigation, adaptive empty slot fallback, and 2-step confirmation token lifecycle.
4. **REST API & Telemetry Guide**:
   - Document all endpoints: `/api/v1/health`, `/api/v1/stats`, `/api/v1/incidents`, `/api/v1/query`, `/api/v1/provenance`, `/api/v1/export`, `/api/v1/trust`.
   - Provide authentication header specs (`X-ChestLogger-Auth`) and practical `curl` examples.
5. **Operational Moderator Playbooks**:
   - Real-world scenario walkthroughs for investigating chest looting, active base raids, tracing stolen/duplicated items, and performing safe rollbacks.

## 3. Non-Functional Requirements
- 100% technical fidelity with zero made-up syntax or hallucinated commands.
- Crystal-clear formatting using GitHub Flavored Markdown, Mermaid diagrams, and ASCII mockups.

## 4. Acceptance Criteria
- `docs/MODERATOR_GUIDE.md` is updated with the exhaustive manual.
- `README.md` navigation and links point directly to the guide.
- Complete alignment with the actual Java source code.
