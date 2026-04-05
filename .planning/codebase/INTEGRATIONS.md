# External Integrations

**Analysis Date:** 2026-04-05

## APIs & External Services

**None detected.** AmbonMUD is a self-contained game server with no external API calls (Stripe, Auth0, Discord, etc.). All game logic, user management, and persistence are handled internally.

## Data Storage

**Databases:**

**PostgreSQL 16+ (Production)**
- Primary relational database for player profiles, guilds, housing, and game state
- Connection: `ambonMUD.database.jdbcUrl` (default: `jdbc:postgresql://localhost:5432/ambonmud`)
- Credentials: `ambonMUD.database.{username, password}` in config or environment
- Client: Exposed 0.58.0 (Kotlin SQL DSL + JDBC)
- Connection pool: HikariCP 7.0.2 (configurable `maxPoolSize` and `minimumIdle`)
- Schema: 34 Flyway migrations (`src/main/resources/db/migration/V1__*.sql` → V34__*)
  - Key tables: `players`, `guilds`, `guild_halls`, `houses`, `leaderboards`
  - JSON columns for denormalized data: `stats`, `daily_quest_data`, `currencies`, `faction_standings`

**YAML (Development/Fallback)**
- File-based persistence when `ambonMUD.persistence.backend: YAML`
- Root directory: `ambonMUD.persistence.rootDir` (default: `data/players/`)
- One file per player: `data/players/{player-id}.yaml`
- Atomic writes via temp file + rename (preserves consistency on crash)
- Not git-tracked (`.gitignore`); runtime-only

**H2 Database (Testing)**
- In-process PostgreSQL-compatible mode for test isolation
- JDBC URL: `jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`
- Flyway auto-migrations during test setup
- No Docker required; speeds up CI/CD

**File Storage:**
- No S3 or cloud object storage
- World assets: `src/main/resources/world/` (YAML zone definitions, sprite/image references)
- Player saves: Local filesystem (YAML or PostgreSQL)
- Web client builds: `src/main/resources/web-v3/` (git-ignored, built on-demand)
- Demo sprites: `src/main/resources/world/images/demo/` (PNG assets)
- Lottery state: `data/lottery_state.json` (ephemeral, session-scoped)
- Auction listings: `data/auction_listings.json` (ephemeral, server-scoped)

**Caching:**

**Redis 7+ (Optional Multi-Instance Mode)**
- Connection: `ambonMUD.redis.uri` (default: `redis://localhost:6379`)
- Client: Lettuce 7.5.0.RELEASE (async, non-blocking)
- TTL: `ambonMUD.redis.cacheTtlSeconds` (default: 3600)
- Use cases:
  - Player cache layer: `RedisCachingPlayerRepository` (optional wrapper in persistence chain)
  - Event pub/sub: `RedisInboundBus`/`RedisOutboundBus` for multi-instance coordination
  - Zone registry: `RedisZoneRegistry` for cross-engine zone handoff
- Graceful degradation: If Redis unavailable, local in-memory fallbacks activate
- Bus config: `ambonMUD.redis.bus.{enabled, inboundChannel, outboundChannel, sharedSecret}`

## Authentication & Identity

**Auth Provider:** Custom (no OAuth/SAML/LDAP)

**Implementation:**
- Username/password authentication via telnet/WebSocket login flow
- Password hashing: BCrypt (jbcrypt 0.4, cost factor: default gensalt)
  - Isolated CPU context via `hashingContext` (separate from I/O dispatcher)
  - Verification timeout: config-driven (default: no explicit timeout; uses Kotlin blocking)
- Login state machine: 9 states in `LoginFlowHandler.kt` (AwaitingName → AwaitingPasswordAuth → authenticated)
- Session ID: Snowflake-based (64-bit distributed ID) via `SnowflakeSessionIdFactory`
- Constraints:
  - Player name: 2–16 alphanumeric + underscore, cannot start with digit
  - Password: non-blank, max 72 chars (BCrypt limit)
  - Max concurrent logins: `ambonMUD.login.maxConcurrentLogins` (default: 150)
  - Max wrong password retries: `ambonMUD.login.maxWrongPasswordRetries` (default: 3)
  - Auth thread pool: `ambonMUD.login.authThreads` (default: 8, CPU-bound BCrypt)

**Staff Access:**
- No in-game promotion command
- Granted by manually editing `is_staff: true` in player YAML or Postgres `players.is_staff` column
- Staff commands gated with `playerState.isStaff` checks in `AdminHandler.kt`

**Player Persistence:**
- `PlayerRepository` interface with two impls:
  - `YamlPlayerRepository` - File-based (dev/fallback)
  - `PostgresPlayerRepository` - SQL via Exposed ORM
- Wrappable with optional layers:
  - `WriteCoalescingPlayerRepository` - Batches writes every 5s (default)
  - `RedisCachingPlayerRepository` - Adds Redis TTL cache
- Field `lastSeenEpochMs` updated on login; used for idle timeout

## Monitoring & Observability

**Error Tracking:**
- Not detected. No Sentry/Rollbar/DataDog integration
- Errors logged via Logback → console + ring buffer (in-memory)

**Logs:**

**Logback Configuration:** `src/main/resources/logback.xml`
- Appenders:
  - `CONSOLE` - Human-readable format to stdout (HH:mm:ss.SSS, level, thread, logger, message)
  - `RING` - In-memory ring buffer (2000 entries) for admin dashboard inspection
- Root level: `INFO` (configurable via `ambonMUD.logging.level`)
- Package-specific levels: `ambonMUD.logging.packageLevels` (e.g., transport/engine can be DEBUG)

**Metrics:**

**Prometheus + Grafana (Optional)**
- Metrics collection: Micrometer Core 1.16.4 (instrumented timers, counters, gauges)
- Registry: Micrometer Prometheus Registry (scrape endpoint: `http://localhost:9099/metrics`)
- Config:
  - `ambonMUD.observability.metricsEnabled: true` (default)
  - `ambonMUD.observability.metricsHttpPort: 9099` (default)
- Prometheus scrape: `infra/prometheus.yml` (targets game server metrics endpoint)
- Grafana dashboards: Pre-provisioned in `infra/grafana/provisioning/`
- Metrics tracked:
  - Tick loop timing (GameEngine frame time)
  - Event bus throughput (inbound/outbound event counts)
  - Combat/ability execution latency
  - Custom counters per system (see `GameMetrics.kt`)

**Admin Dashboard:** `dev.ambon.admin.AdminHttpServer` (optional)
- Embedded HTTP server on port `ambonMUD.admin.port` (default: 9091, disabled by default)
- Token-based access: `ambonMUD.admin.token` (bearer token in Authorization header)
- Endpoints:
  - `/admin/` - Server status, player list, metrics summary
  - `/admin/logs` - Ring buffer log viewer
  - `/admin/reload` - Hot-reload world config (YAML zones, abilities, status effects)
  - `/admin/players` - Player search and inspection
- UI: 63K HTML/JS served from `AdminHttpServer.kt`

## CI/CD & Deployment

**Hosting:**

**Development:**
- Local JVM process (Gradle run)
  - Telnet: localhost:4000
  - WebSocket: localhost:8080
  - Admin dashboard: localhost:9091 (if enabled)

**Production (Cloud):**
- Docker container (multi-stage: Node 20 frontend build → JVM 21 runtime)
- ECR (Amazon Elastic Container Registry) - OIDC federated push
- No Kubernetes/ECS orchestration required (runs as single container)
- Multi-instance mode: Deploy multiple ENGINE and GATEWAY containers with Redis coordination

**CI Pipeline:** GitHub Actions (`.github/workflows/ci.yml`)

**Test Job:**
- Trigger: Push to any branch, pull_request
- Environment: Ubuntu 22.04
- Steps:
  1. Checkout (with git-lfs for large assets)
  2. Setup JDK 21 (Temurin)
  3. Setup Bun (latest, cache bun.lock)
  4. Install web deps: `bun install --frozen-lockfile`
  5. Gradle: `ktlintCheck test integrationTest`
  6. Artifacts: Upload test results + Jacoco coverage reports
- Timeout: 5 minutes (backstop per Gradle config)

**Frontend Job:**
- Trigger: Same as test job
- Steps: Bun lint + build in `web-v3/`
- Output: Verified but not uploaded (built again in Docker stage)

**Docker Job:**
- Trigger: Push to main (only)
- Environment: Ubuntu 24.04 ARM64 (native, not QEMU)
- Auth: AWS OIDC credential federation (role: `AWS_ECR_PUSH_ROLE_ARN`)
- Steps:
  1. Log in to ECR
  2. Build multi-stage Docker image (frontend build → JVM app + assets)
  3. Push with Git SHA tag + `latest` tag
- Image location: `{ECR_REGISTRY}/{ECR_REPO}/ambonmud/app:${GIT_SHA}`

## Environment Configuration

**Required Environment Variables (for production):**
- `DATABASE_URL` or `POSTGRES_PASSWORD` - Database credentials (or bake into config)
- `REDIS_URI` - Redis connection string (if multi-instance mode)
- `AWS_ECR_PUSH_ROLE_ARN` - For ECR push auth (CI only)
- `AWS_REGION` - AWS region for ECR (CI only)

**Configurable via `application.yaml` or `-Pconfig.X=Y`:**
- `ambonMUD.database.{jdbcUrl, username, password, maxPoolSize}`
- `ambonMUD.redis.{enabled, uri, cacheTtlSeconds}`
- `ambonMUD.server.{telnetPort, webPort, tickMillis}`
- `ambonMUD.logging.level` - Global log level (INFO, DEBUG, etc.)
- All 50+ config fields in `AppConfig.kt` can be overridden

**Secrets Location:**

**Never committed:**
- `.env` files (git-ignored)
- `application-secrets.yaml` (if created locally)
- Docker Compose uses environment substitution for passwords

**Injected at runtime:**
- Database credentials: Environment variables or Hoplite validation
- Redis password (if auth required): Part of `redis.uri`
- Admin API token: `ambonMUD.admin.token` (set at deploy time)

## Webhooks & Callbacks

**Incoming:** None detected. No webhook listeners for external systems.

**Outgoing:** None detected. No outbound webhooks to Discord, Slack, etc.

## World Asset Distribution

**Static Assets (Player-Facing):**
- Web client: Built artifacts served from `src/main/resources/web-v3/`
- Zone YAML definitions: `src/main/resources/world/*.yaml` (24 zone files loaded at startup)
- Sprite images: Referenced from config `ambonMUD.images.baseUrl` (default: `https://auringold.ambon.dev/`)
  - Downloaded at runtime by web client via GMCP + image URLs
  - No built-in image server; external CDN used
  - Demo fallback sprites: `src/main/resources/world/images/demo/` (PNG, bundled)

**Content Hotload:**
- `/admin/reload` endpoint triggers `HotReloadManager`
- Reloads world zones, abilities, status effects, behaviors, dialogues
- No persistence of hotload changes (config source of truth)

---

*Integration audit: 2026-04-05*
