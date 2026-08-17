# Track Specification: ChestLogger Professional Log Observability UI

## 1. Overview
Overhaul the embedded ChestLogger Web Admin Dashboard to replace generic UI templates ("AI slop") with a high-density, professional log observability interface inspired by modern log platforms (BetterStack, Datadog, Grafana Loki). The new dashboard maximizes vertical screen space for log telemetry, introduces expandable transaction inspectors, live auto-tail streaming, quick-filter chips, and crisp SVG iconography while remaining 100% zero-dependency vanilla web assets.

---

## 2. Architectural & Technical Constraints
1. **Zero External Dependencies**:
   - Must use pure Vanilla HTML5, CSS3, and ES6 JavaScript.
   - Zero npm build steps and zero CDN imports (must function on airgapped or offline servers).
   - Embedded directly within `src/main/resources/assets/chestlogger/web/`.
2. **High-Density Observability UX**:
   - Maximize screen real estate (>80%) dedicated to live logs and query results.
   - Clean, professional dark slate / carbon design system without gratuitous emojis or bloated decorative cards.
3. **Backend Compatibility**:
   - Fully compatible with existing REST API endpoints (`/api/v1/health`, `/api/v1/stats`, `/api/v1/query`, `/api/v1/export`).
   - Client-side token persistence in `localStorage` and request headers (`X-ChestLogger-Auth`).

---

## 3. Functional Requirements

### 3.1 Observability Header & Compact Telemetry Bar
- **Top Brand & Navigation**:
   - Modern brand heading with clean SVG logo, server edition tag, and connection status pill with live ping indicator.
   - Top action controls: Auth Settings modal trigger and Refresh button.
- **Slimline Telemetry & Health Bar**:
   - High-density status bar replacing the oversized 4-card grid:
     - Ring Buffer Saturation meter (active depth / capacity ratio bar + numeric indicator).
     - Event Throughput counters (RAM captured, Disk drained, Dropped count).
     - Storage Metrics (Indexed pointers, Server Uptime).
   - Live Auto-Tail Polling toggle with interval options (`Paused`, `1s`, `5s`, `10s`) and an animated pulse indicator when active.

### 3.2 Streamlined Query Toolbar & Quick-Filter Chips
- **Compact Query Inputs**:
   - Position coordinates `(X, Y, Z)` inputs with clear number step controls.
   - Dimension selector dropdown (`All`, `Overworld`, `The Nether`, `The End`).
   - Actor search (Player name / UUID) and Item identifier search.
   - Timeframe filter (`All Time`, `15m`, `1h`, `24h`, `7d`, `30d`) and Page Size selector (`25`, `50`, `100`, `250`).
- **Quick-Filter Chips**:
   - Instant one-click filter pills for frequent workflows:
     - Dimension tags: `Overworld`, `Nether`, `End`.
     - Action tags: `TAKE Only`, `PUT Only`.
     - Time shortcuts: `Last 15m`, `Last 1h`, `Last 24h`.
- **Export Actions**:
   - Integrated one-click RFC 4180 CSV and formatted JSON download buttons.

### 3.3 High-Density Log Stream & Expandable Row Inspector
- **Log Stream Table**:
   - Monospaced tabular presentation with high visual hierarchy:
     - Timestamp (Local / UTC formatted with relative tooltip).
     - Position badge `[X, Y, Z]` (Click to copy coordinates or filter by location).
     - Dimension badge with distinct color coding.
     - Actor name pill.
     - Action tag (`TAKE` in red-orange / `PUT` in emerald green / `CLEAR` in blue).
     - Slot index badge (`#0` ... `#53`).
     - Item identifier with colored delta pill (`-64`, `+12`).
- **Expandable Transaction Inspector Drawer**:
   - Clicking any log entry reveals an in-depth slide-down / modal drawer containing:
     - Transaction Metadata: Monotonic Sequence ID, Transaction UUID, Source Container Type.
     - Pre- and Post-state inventory diff details.
     - Quick Action Helpers:
       - **Copy `/chestlog rollback` Command**: Pre-fills command string for copying directly to in-game chat or console.
       - **Filter by Actor**: Instantly sets player filter and re-queries.
       - **Filter by Position**: Instantly sets X, Y, Z filters and re-queries.
       - **Copy Raw JSON**: Copies the complete event JSON object to clipboard.

### 3.4 Ergonomics & Polish
- **Keyboard Shortcuts**:
   - `/` to focus the search query input.
   - `r` to manually refresh logs and telemetry.
   - `Escape` to close inspector drawers and modal dialogs.
- **Copy Feedback**:
   - Toast notification system confirming successful clipboard copies and API feedback.

---

## 4. Acceptance Criteria
1. Web UI loads with professional dark slate styling, monospaced tabular data, and zero system emojis.
2. Compact telemetry bar displays real-time queue depth, total enqueued/drained, and connection state.
3. Log table allows clicking rows to expand the Transaction Inspector drawer with raw JSON and rollback command generation.
4. Live Auto-Tail polling automatically updates logs and telemetry at the configured interval when active.
5. Quick-filter chips and standard form filters properly query `/api/v1/query` and display results.
6. Export buttons stream valid CSV and JSON downloads matching current filters.
7. All automated unit and integration tests pass without regression.
