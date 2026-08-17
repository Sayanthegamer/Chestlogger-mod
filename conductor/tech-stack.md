# Technology Stack: ChestLogger

## Core Platform & Build
- **Target Game Version**: Minecraft 26.2
- **Language & Runtime**: Java 25
- **Build System**: Gradle 9.5.1
- **Gradle Plugin**: Fabric Loom 1.17
- **Mod Loader**: Fabric Loader 0.19.3
- **Mod Ecosystem**: Fabric API (0.157.0+26.2 or newest compatible)
- **Mapping Scheme**: Mojang Unobfuscated Official Mappings (no Yarn/Intermediary layer)

## Persistence, Serialization & Compression
- **Storage Engine**: Custom Append-Only Binary Segmented Log Engine with Index Checkpointing
- **Compression**:
  - `LZ4` (lz4-java) - Low CPU overhead default for sequential streaming (exact version verified during bootstrap)
  - `Zstandard` (zstd-jni) - Optional high-density archival profile (exact version verified during bootstrap)
- **Encoding**: Variable-length VarInt/VarLong integers, bitmasks, string/item dictionary table
- **I/O Pipelines**: Bounded MPSC event queues, dedicated background worker thread, profile-tuned batch flushers (`balanced`, `hdd`, `ssd`)

## Item Provenance & Chain-of-Custody Engine
- **Graph Resolver**: Pure-Java cycle-safe DAG traversal engine (`ItemProvenanceResolver`) in `chestlogger-common`
- **Component Fingerprinting**: 64-bit component hashing (`MetadataFingerprint`) for exact non-fungible gear matching
- **Heuristic Temporal Traversal**: Lookback/lookahead window correlation for commodity flow with explicit confidence levels (`EXACT_LINKAGE`, `HIGH_CONFIDENCE`, `PROBABLE`)
- **Visual Presentation**: Dual-presentation via in-game 54-slot GUI (`PaperProvenanceGuiView` / Fabric Screen) and Web UI Canvas/SVG interactive node-link visualizer with live step inspection drawer

## Smart Theft & Security Architecture
- **Heuristic Evaluator**: Thread-safe zero-allocation security evaluator (`SmartTheftEvaluator`) with sub-20ms p99 latency under 16-thread contention
- **Velocity Tracker**: Sliding-window multi-container access tracker (`RaidVelocityTracker`) pruning expired entries over 300s configurable windows
- **Trust Graphs**: Directional player trust management (`TrustManager`) backed by atomic JSON storage (`trust_data.json`)
- **Telemetry Buffers**: Fixed-capacity ring buffer (`IncidentRingBuffer`, default 200) for real-time web telemetry and administrative incident streaming
- **Multi-Channel Alert Dispatch**: Asynchronous Discord Webhooks with dynamic presence tags (`🔴 Offline`, `🟡 Absent`, `🟢 Nearby`), in-game Action-Bar HUD alerts, and interactive clickable chat cards (`[Teleport]`, `[Inspect]`)

## Embedded Web Administration & REST API
- **HTTP Server**: Embedded `com.sun.net.httpserver.HttpServer` (Zero external dependencies, daemon thread pool)
- **Frontend**: Vanilla HTML5, CSS3, ES6 JavaScript Single-Page Application (SPA) with inline SVG iconography, dark-carbon observability design system, expandable transaction inspector, interactive item journey graph visualizer, and live auto-tail engine embedded directly in mod resources (zero npm build, zero CDN dependencies)
- **Security**: Token-based authentication (`X-ChestLogger-Auth`, Bearer token, query param), IP-based rate limiting (HTTP 429), timing-safe token validation, strict path traversal blocking
- **Export Engine**: RFC 4180 streaming CSV and structured JSON attachment exporters

## Testing & Quality Assurance
- **Unit & Property Tests**: JUnit 5 (Jupiter), AssertJ, fuzz/property generators
- **Integration Testing**: Fabric GameTest / Fabric Server Test runner
- **Benchmarking**: Custom microbenchmarks for throughput (100 to 1M events)

## Continuous Integration & DevOps
- **CI Platform**: GitHub Actions (`.github/workflows/ci.yml`)
- **Automated Pipeline**: JDK 25 (Temurin), Gradle caching, automated test verification (`./gradlew check`), release binary compilation (`./gradlew build`), and artifact archiving

