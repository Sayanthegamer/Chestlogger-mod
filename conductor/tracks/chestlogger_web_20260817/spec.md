# Track Specification: ChestLogger Embedded Web Admin Dashboard

## 1. Overview
This track introduces a lightweight, zero-dependency embedded web server (`com.sun.net.httpserver.HttpServer`) to ChestLogger. It allows server administrators and moderators to monitor server telemetry, perform spatial and player-based log searches, inspect transactions, and export audit files directly from a modern web browser without needing to join the Minecraft game server.

---

## 2. Architectural & Technical Constraints
1. **Zero External Dependencies**:
   - Utilize Java standard library `com.sun.net.httpserver.HttpServer`.
   - Embed lightweight HTML/CSS/JS assets inside the mod JAR (`assets/chestlogger/web/`).
2. **Zero Main-Thread Blocking**:
   - The HTTP server runs on a dedicated background executor thread pool.
   - Queries directly leverage `QueryEngine` with bounded memory pagination.
3. **Security & Access Control**:
   - **Disabled by Default**: Web server is strictly disabled by default (`enabled: false`) in config until an admin explicitly turns it on.
   - **Localhost Binding by Default**: Binds to `127.0.0.1` by default (configurable to `0.0.0.0` or custom interface only if deliberately configured).
   - Config-defined authentication token / PIN header (`X-ChestLogger-Auth` or query parameter).
   - Rate limiting and bound payload sizes to prevent Denial of Service.
   - Configurable port (default: `8080`) and host (default: `127.0.0.1`).

---

## 3. Functional Requirements

### 3.1 HTTP REST API Endpoints
- `GET /api/v1/health` & `GET /api/v1/stats`:
  - Returns JSON object with queue depth, enqueued count, dropped count, indexed pointers, and active storage segment metrics.
- `GET /api/v1/query`:
  - Query parameters: `x`, `y`, `z`, `dim`, `player`, `item`, `since`, `page`, `limit`.
  - Returns paginated JSON records with total records, total pages, and structured `DisplayRecord` objects.
- `GET /api/v1/export`:
  - Formats: `json` or `csv`.
  - Streams filtered logs as a downloadable file with proper `Content-Disposition`.

### 3.2 Single-Page Web Dashboard UI
- **Responsive Dark Theme UI**:
  - Pure vanilla HTML5 + CSS + lightweight Vanilla JS (zero npm build steps required).
- **Live Search & Filter Controls**:
  - Coordinate inputs `(X, Y, Z)`, Dimension selector, Player Name filter, and Item Identifier search.
- **Interactive Log Table**:
  - Columns: Timestamp, Actor, Action, Slot, Item Identifier, and Colored Delta (+Green / -Red).
  - Client-side pagination and one-click JSON/CSV export buttons.
- **Telemetry Health Bar**:
  - Live ring-buffer gauge and segment stats updated periodically.

---

## 4. Acceptance Criteria
1. Web server starts and stops cleanly with server lifecycle (`ServerStartingCallback` / `ServerStoppingCallback`).
2. Unauthorized requests without the valid auth token receive HTTP 401 Unauthorized.
3. `/api/v1/query` returns accurate paginated JSON from the existing `QueryEngine`.
4. `/api/v1/export` downloads valid JSON and CSV files matching query filters.
5. All automated unit, integration, and security tests pass 100%.
