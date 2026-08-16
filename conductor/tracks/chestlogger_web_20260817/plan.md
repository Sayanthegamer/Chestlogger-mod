# Implementation Plan: ChestLogger Embedded Web Admin Dashboard

## Phase 1: Web Server Foundation & Configuration (TDD)
- [ ] Task: Write unit tests for `WebConfig` and `HttpAuthHandler` (token authentication, CORS, rate limiting).
- [ ] Task: Implement `WebConfig` loaded from `config/chestlogger_web.json` (port, enabled, secretToken, allowedOrigins).
- [ ] Task: Implement `EmbeddedHttpServer` wrapping `com.sun.net.httpserver.HttpServer` with background executor pool.
- [ ] Task: Wire server start and stop hooks to `ServerLifecycleEvents`.
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 2: REST API Endpoints & Export Streamers (TDD)
- [ ] Task: Write integration tests for `/api/v1/stats`, `/api/v1/query`, and `/api/v1/export` endpoints.
- [ ] Task: Implement `StatsHttpHandler` returning JSON telemetry from `TransactionEventQueue` and `PersistentIndexManager`.
- [ ] Task: Implement `QueryHttpHandler` integrating with `QueryEngine` to serve paginated JSON records.
- [ ] Task: Implement `ExportHttpHandler` streaming filtered results in CSV and JSON formats.
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 3: Embedded Web Dashboard Single-Page Application
- [ ] Task: Create static HTML/CSS/JS web assets in `src/main/resources/assets/chestlogger/web/`.
- [ ] Task: Implement `StaticAssetHttpHandler` serving embedded resources with proper MIME types and caching headers.
- [ ] Task: Implement responsive dark-theme dashboard with telemetry gauges, coordinate/player search, log table, and export triggers.
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 4: Security Hardening, Edge Cases & End-to-End Regression
- [ ] Task: Write security test suite (path traversal prevention on static assets, brute-force auth rate limiting, invalid query params).
- [ ] Task: Write concurrency and stress tests simulating simultaneous browser queries and game ticks.
- [ ] Task: Run full regression test suite (All phases).
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)
