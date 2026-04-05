# Technology Stack

**Analysis Date:** 2026-04-05

## Languages

**Primary:**
- Kotlin 2.3.10 - Backend game engine, all server-side logic and systems
- TypeScript 5.9.3 - Web client development
- JavaScript - Web client runtime (React 19.2, PixiJS 8.16)

**Secondary:**
- SQL - Database schema migrations (Flyway), PostgreSQL queries via Exposed ORM
- YAML - World content definition, configuration loading, player persistence (optional)
- Protocol Buffers (proto3) - gRPC service definitions for engine-gateway communication

## Runtime

**Environment:**
- Java/JVM 21 (Temurin distribution in CI)
- Kotlin targets JVM (jvmToolchain 21 in `build.gradle.kts`)

**Package Managers:**
- Gradle 8.x (wrapper) - Backend dependency and build management
  - Lockfile: `gradle-wrapper.properties`
  - Key wrapper config in `gradle.properties` (daemon idle timeout 10 minutes)
- Bun 1.x+ - Frontend package manager and task runner
  - Lockfile: `web-v3/bun.lock`
  - Cached in CI workflows

## Frameworks

**Core:**
- Ktor 3.4.2 - WebSocket server, HTTP routing, async coroutine-based networking
  - `ktor-server-core`, `ktor-server-netty`, `ktor-server-websockets`
  - Embedded Netty NIO event loop (with IPv4 preference workaround for Windows)

**Concurrency/Async:**
- kotlinx-coroutines 1.10.2 - Async I/O, tick-based event loop, multi-instance coordination
  - Custom `engineDispatcher` (single-threaded, dedicated to GameEngine tick loop)
  - Separate `hashingContext` for CPU-bound BCrypt password operations

**Game Logic:**
- Custom tick-based event loop in `GameEngine.kt` - 100ms ticks by default
- Event bus pattern with pluggable impls (Local, Redis, gRPC for multi-process modes)

**Web Frontend:**
- React 19.2.0 - UI component framework
- Vite 7.3.1 - Build tool and dev server
- xterm.js 6.0 - Terminal emulator for text-based client overlay
- PixiJS 8.16.0 - Canvas/WebGL 2D rendering for world viewport and effects

**Testing:**
- JUnit Jupiter (JUnit 5) 6.0.3 - Test runner
- kotlinx-coroutines-test - Async test utilities (runTest, advanceTimeBy)
- Ktor test-host - In-process HTTP/WebSocket testing
- H2 Database 2.4.240 (PostgreSQL mode) - Fast in-memory testing, avoids Docker

**Build/Dev:**
- protobuf-gradle-plugin 0.9.6 - Code generation from proto definitions
- google-protobuf (protoc) 4.34.1 - Protocol Buffers compiler
- gradle-shadow 9.4.1 - Fat JAR packaging for deployment
- org.jlleitschuh.gradle.ktlint 14.2.0 - Kotlin linting (official style, custom .editorconfig)
- Flyway 12.2.0 - Database schema versioning and migrations
- Jacoco - Code coverage reporting

## Key Dependencies

**Critical:**
- Jackson 2.21.1 (jackson-module-kotlin, jackson-dataformat-yaml) - YAML parsing for configs and world loading, JSON serialization for persistence
- Hoplite 2.9.0 - YAML-driven configuration management with validation
- Lettuce 7.5.0.RELEASE - Redis client for caching and pub/sub (optional, graceful fallback)
- BCrypt (jbcrypt 0.4) - Password hashing and verification with CPU isolation

**Persistence:**
- Exposed 0.58.0 - Kotlin SQL DSL and ORM (JDBC-based)
  - `exposed-core` (query DSL)
  - `exposed-jdbc` (connection management)
- HikariCP 7.0.2 - JDBC connection pooling
- PostgreSQL driver 42.7.10 - Production database support
- Flyway 12.2.0 - Schema migrations (V1–V34 covering all features)

**Networking/RPC:**
- gRPC 1.80.0 - Bidirectional streaming RPC for engine-gateway communication
  - `grpc-netty-shaded` - gRPC transport via Netty
  - `grpc-protobuf`, `grpc-stub`, `grpc-kotlin-stub` - Code generation
  - Custom `EngineGrpcServer` and `EngineServiceImpl` for service definition

**Observability:**
- Micrometer Core 1.16.4 - Metrics facade
- Micrometer Prometheus Registry 1.16.4 - Prometheus scrape endpoint (`/metrics` on port 9099)
- Logback 1.5.32 - SLF4J implementation, console + ring buffer appenders
- kotlin-logging 8.0.01 - Kotlin facade over SLF4J

## Configuration

**Environment:**
- YAML-driven via Hoplite (`src/main/resources/application.yaml`)
- Profile overlays: `application-{profile}.yaml` (engine1, engine2, gw1, gw2 for multi-instance)
- System property override: `-Pconfig.ambonMUD.X.Y=value` (Gradle property → system property mapper)
- Environment variable support for database credentials and Redis URI

**Build:**
- `build.gradle.kts` - Single Gradle configuration file
- Kotlin DSL with manual dependency version variables
- Web client build target: `./gradlew buildWeb` → outputs to `src/main/resources/web-v3/` (git-ignored)
- Multi-instance test profiles (engines on gRPC :9091/:9092, gateways on telnet :4000/:4001)

**Runtime Config Structure:**
```yaml
ambonMUD:
  mode: STANDALONE|ENGINE|GATEWAY
  server: { telnetPort, webPort, tickMillis: 100 }
  persistence: { backend: YAML|POSTGRES, rootDir, worker }
  database: { jdbcUrl, username, maxPoolSize }
  redis: { enabled, uri, cacheTtlSeconds, bus: { enabled } }
  grpc: { server: { port }, client: { engineHost, enginePort } }
  gateway: { id, engines: [...] }
  logging: { level, packageLevels }
  observability: { metricsEnabled, metricsHttpPort: 9099 }
  admin: { enabled, port, token }
  engine: { abilities, statusEffects, stats, pets, ... }
```

## Platform Requirements

**Development:**
- JDK 21 (any distribution, tested with Temurin)
- Gradle wrapper (self-downloading, requires ~2GB heap)
- Kotlin 2.3.10 (managed by Gradle)
- Bun 1.x+ (for web client build; graceful skip if missing)
- Optional: Docker + Docker Compose for Redis/Postgres/Prometheus/Grafana stack

**Production:**
- Java 21 runtime (cloud-native: container via Dockerfile multi-stage build)
- PostgreSQL 16+ (or any Postgres-compatible database)
- Redis 7+ (optional, for caching and multi-instance pub/sub)
- Prometheus 2.51+ (optional, for metrics scraping)
- Grafana 10.4+ (optional, for visualization)

**Deployment:**
- Docker image: Multi-stage (frontend build → backend JAR + static assets)
- CI/CD: GitHub Actions with ARM64 native runner for ECR push (OIDC credential federation)
- Container orchestration: Not required (single-process STANDALONE mode supported)
- Horizontal scaling: gRPC-based ENGINE/GATEWAY sharding with Redis coordination

---

*Stack analysis: 2026-04-05*
