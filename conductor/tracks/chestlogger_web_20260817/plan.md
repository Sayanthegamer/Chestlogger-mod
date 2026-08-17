# Implementation Plan: ChestLogger Embedded Web Admin Dashboard

## Phase 1: Web Server Foundation & Configuration (TDD) [checkpoint: 0d932bf]
- [x] Task: Write unit tests for `WebConfig` and `HttpAuthHandler` (token authentication, CORS, rate limiting). (0d932bf)
- [x] Task: Implement `WebConfig` loaded from `config/chestlogger_web.json` (port, enabled, secretToken, allowedOrigins). (0d932bf)
- [x] Task: Implement `EmbeddedHttpServer` wrapping `com.sun.net.httpserver.HttpServer` with background executor pool. (0d932bf)
- [x] Task: Wire server start and stop hooks to `ServerLifecycleEvents`. (0d932bf)
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) (0d932bf)

## Phase 2: REST API Endpoints & Export Streamers (TDD) [checkpoint: 36952b8]
- [x] Task: Write integration tests for `/api/v1/stats`, `/api/v1/query`, and `/api/v1/export` endpoints. (36952b8)
- [x] Task: Implement `StatsHttpHandler` returning JSON telemetry from `TransactionEventQueue` and `PersistentIndexManager`. (36952b8)
- [x] Task: Implement `QueryHttpHandler` integrating with `QueryEngine` to serve paginated JSON records. (36952b8)
- [x] Task: Implement `ExportHttpHandler` streaming filtered results in CSV and JSON formats. (36952b8)
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) (36952b8)

## Phase 3: Embedded Web Dashboard Single-Page Application [checkpoint: 5cb96a8]
- [x] Task: Create static HTML/CSS/JS web assets in `src/main/resources/assets/chestlogger/web/`. (5cb96a8)
- [x] Task: Implement `StaticAssetHttpHandler` serving embedded resources with proper MIME types and caching headers. (5cb96a8)
- [x] Task: Implement responsive dark-theme dashboard with telemetry gauges, coordinate/player search, log table, and export triggers. (5cb96a8)
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) (5cb96a8)

## Phase 4: Security Hardening, Edge Cases & End-to-End Regression
- [~] Task: Write security test suite (path traversal prevention on static assets, brute-force auth rate limiting, invalid query params).
- [ ] Task: Write concurrency and stress tests simulating simultaneous browser queries and game ticks.
- [ ] Task: Run full regression test suite (All phases).
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)
