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

## Embedded Web Administration & REST API
- **HTTP Server**: Embedded `com.sun.net.httpserver.HttpServer` (Zero external dependencies, daemon thread pool)
- **Frontend**: Vanilla HTML5, CSS3, ES6 JavaScript Single-Page Application (SPA) with inline SVG iconography, dark-carbon observability design system, expandable transaction inspector, and live auto-tail engine embedded directly in mod resources (zero npm build, zero CDN dependencies)
- **Security**: Token-based authentication (`X-ChestLogger-Auth`, Bearer token, query param), IP-based rate limiting (HTTP 429), timing-safe token validation, strict path traversal blocking
- **Export Engine**: RFC 4180 streaming CSV and structured JSON attachment exporters

## Testing & Quality Assurance
- **Unit & Property Tests**: JUnit 5 (Jupiter), AssertJ, fuzz/property generators
- **Integration Testing**: Fabric GameTest / Fabric Server Test runner
- **Benchmarking**: Custom microbenchmarks for throughput (100 to 1M events)
