# Plan: YAML → PostgreSQL Player Persistence Migration

## Overview

Add a `PostgresPlayerRepository` implementation of the existing `PlayerRepository` interface, wired as a selectable backend alongside the current `YamlPlayerRepository`. Uses Exposed (JetBrains' Kotlin SQL framework) for type-safe queries, Flyway for schema migrations, and HikariCP for connection pooling. Tests use H2 in PostgreSQL-compatibility mode (no Docker required).

---

## Phase 1: Dependencies & Config

### 1a. Add Gradle dependencies (`build.gradle.kts`)

```kotlin
val exposedVersion = "0.58.0"

// Exposed (Kotlin SQL framework)
implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
// Connection pool
implementation("com.zaxxer:HikariCP:6.2.1")
// JDBC driver
implementation("org.postgresql:postgresql:42.7.5")
// Schema migration
implementation("org.flywaydb:flyway-core:11.3.0")
implementation("org.flywaydb:flyway-database-postgresql:11.3.0")

// Test: H2 in Postgres mode
testImplementation("com.h2database:h2:2.3.232")
```

### 1b. Add config types (`AppConfig.kt`)

Add a new `DatabaseConfig` data class and a `PersistenceBackend` enum:

```kotlin
enum class PersistenceBackend { YAML, POSTGRES }

data class DatabaseConfig(
    val jdbcUrl: String = "",
    val username: String = "",
    val password: String = "",
    val maxPoolSize: Int = 5,
    val minimumIdle: Int = 1,
)
```

Add `backend` field to `PersistenceConfig` and `database` field to `AppConfig`:

```kotlin
data class PersistenceConfig(
    val backend: PersistenceBackend = PersistenceBackend.YAML,
    val rootDir: String = "data/players",
    val worker: PersistenceWorkerConfig = PersistenceWorkerConfig(),
)

data class AppConfig(
    ...
    val database: DatabaseConfig = DatabaseConfig(),
    ...
)
```

Add validation in `validated()`:

```kotlin
if (persistence.backend == PersistenceBackend.POSTGRES) {
    require(database.jdbcUrl.isNotBlank()) { "ambonMUD.database.jdbcUrl required when backend=POSTGRES" }
    require(database.maxPoolSize > 0) { "ambonMUD.database.maxPoolSize must be > 0" }
}
```

### 1c. Add default config values (`application.yaml`)

```yaml
persistence:
  backend: YAML           # YAML or POSTGRES
  rootDir: data/players
  worker:
    enabled: true
    flushIntervalMs: 5000

database:
  jdbcUrl: ""
  username: ""
  password: ""
  maxPoolSize: 5
  minimumIdle: 1
```

---

## Phase 2: Flyway Migration & Schema

### 2a. Create migration file (single migration)

Path: `src/main/resources/db/migration/V1__create_players_table.sql`

```sql
CREATE SEQUENCE player_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE players (
    id         BIGINT       PRIMARY KEY DEFAULT nextval('player_id_seq'),
    name       VARCHAR(16)  NOT NULL,
    name_lower VARCHAR(16)  NOT NULL,
    room_id    VARCHAR(128) NOT NULL,
    constitution INT        NOT NULL DEFAULT 0,
    level      INT          NOT NULL DEFAULT 1,
    xp_total   BIGINT       NOT NULL DEFAULT 0,
    created_at_epoch_ms BIGINT NOT NULL,
    last_seen_epoch_ms  BIGINT NOT NULL,
    password_hash VARCHAR(72) NOT NULL DEFAULT '',
    ansi_enabled BOOLEAN     NOT NULL DEFAULT FALSE,
    is_staff   BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX idx_players_name_lower ON players (name_lower);
```

Notes:
- **`DEFAULT nextval('player_id_seq')`** — the DB auto-assigns IDs on insert; no need for a separate SELECT nextval round-trip.
- **`name_lower` column** — materialized lowercase name for portable case-insensitive uniqueness. Expression indexes (`LOWER(name)`) are idiomatic Postgres but unreliable in H2's PostgreSQL-compatibility mode. A plain `UNIQUE INDEX` on a stored column works identically across both.
- The repository is responsible for populating `name_lower = name.trim().lowercase()` on every insert/upsert.
- All column types are H2-compatible in `MODE=PostgreSQL`.

---

## Phase 3: Exposed Table Definition & PostgresPlayerRepository

### 3a. Create `PlayersTable.kt`

Path: `src/main/kotlin/dev/ambon/persistence/PlayersTable.kt`

Exposed DSL table object matching the Flyway-managed schema:

```kotlin
object PlayersTable : Table("players") {
    val id = long("id").autoIncrement("player_id_seq")
    val name = varchar("name", 16)
    val nameLower = varchar("name_lower", 16)
    val roomId = varchar("room_id", 128)
    val constitution = integer("constitution").default(0)
    val level = integer("level").default(1)
    val xpTotal = long("xp_total").default(0L)
    val createdAtEpochMs = long("created_at_epoch_ms")
    val lastSeenEpochMs = long("last_seen_epoch_ms")
    val passwordHash = varchar("password_hash", 72).default("")
    val ansiEnabled = bool("ansi_enabled").default(false)
    val isStaff = bool("is_staff").default(false)

    override val primaryKey = PrimaryKey(id)
}
```

### 3b. Create `PostgresPlayerRepository.kt`

Path: `src/main/kotlin/dev/ambon/persistence/PostgresPlayerRepository.kt`

Implements `PlayerRepository` using Exposed DSL queries:

```kotlin
class PostgresPlayerRepository(
    private val database: Database,
    private val metrics: GameMetrics = GameMetrics.noop(),
) : PlayerRepository {

    override suspend fun findByName(name: String): PlayerRecord? {
        val sample = Timer.start()
        try {
            return newSuspendedTransaction(Dispatchers.IO, database) {
                PlayersTable.selectAll()
                    .where { PlayersTable.nameLower eq name.trim().lowercase() }
                    .firstOrNull()
                    ?.toPlayerRecord()
            }
        } finally {
            sample.stop(metrics.playerRepoLoadTimer)
        }
    }

    override suspend fun findById(id: PlayerId): PlayerRecord? {
        val sample = Timer.start()
        try {
            return newSuspendedTransaction(Dispatchers.IO, database) {
                PlayersTable.selectAll()
                    .where { PlayersTable.id eq id.value }
                    .firstOrNull()
                    ?.toPlayerRecord()
            }
        } finally {
            sample.stop(metrics.playerRepoLoadTimer)
        }
    }

    override suspend fun create(
        name: String,
        startRoomId: RoomId,
        nowEpochMs: Long,
        passwordHash: String,
        ansiEnabled: Boolean,
    ): PlayerRecord {
        val trimmed = name.trim()
        return newSuspendedTransaction(Dispatchers.IO, database) {
            val result = PlayersTable.insert {
                it[PlayersTable.name] = trimmed
                it[nameLower] = trimmed.lowercase()
                it[roomId] = startRoomId.value
                it[createdAtEpochMs] = nowEpochMs
                it[lastSeenEpochMs] = nowEpochMs
                it[PlayersTable.passwordHash] = passwordHash
                it[PlayersTable.ansiEnabled] = ansiEnabled
            }

            PlayerRecord(
                id = PlayerId(result[PlayersTable.id]),
                name = trimmed,
                roomId = startRoomId,
                createdAtEpochMs = nowEpochMs,
                lastSeenEpochMs = nowEpochMs,
                passwordHash = passwordHash,
                ansiEnabled = ansiEnabled,
            )
        }
    }

    override suspend fun save(record: PlayerRecord) {
        val sample = Timer.start()
        try {
            newSuspendedTransaction(Dispatchers.IO, database) {
                // Upsert: insert or update on conflict — matches YAML overwrite semantics
                PlayersTable.upsert(PlayersTable.id) {
                    it[id] = record.id.value
                    it[name] = record.name
                    it[nameLower] = record.name.lowercase()
                    it[roomId] = record.roomId.value
                    it[constitution] = record.constitution
                    it[level] = record.level
                    it[xpTotal] = record.xpTotal
                    it[createdAtEpochMs] = record.createdAtEpochMs
                    it[lastSeenEpochMs] = record.lastSeenEpochMs
                    it[passwordHash] = record.passwordHash
                    it[ansiEnabled] = record.ansiEnabled
                    it[isStaff] = record.isStaff
                }
            }
            metrics.onPlayerSave()
        } catch (e: Throwable) {
            metrics.onPlayerSaveFailure()
            throw e
        } finally {
            sample.stop(metrics.playerRepoSaveTimer)
        }
    }

    private fun ResultRow.toPlayerRecord() = PlayerRecord(
        id = PlayerId(this[PlayersTable.id]),
        name = this[PlayersTable.name],
        roomId = RoomId(this[PlayersTable.roomId]),
        constitution = this[PlayersTable.constitution],
        level = this[PlayersTable.level],
        xpTotal = this[PlayersTable.xpTotal],
        createdAtEpochMs = this[PlayersTable.createdAtEpochMs],
        lastSeenEpochMs = this[PlayersTable.lastSeenEpochMs],
        passwordHash = this[PlayersTable.passwordHash],
        ansiEnabled = this[PlayersTable.ansiEnabled],
        isStaff = this[PlayersTable.isStaff],
    )
}
```

Key design decisions:
- **Exposed DSL** — type-safe column references, compile-time safety on queries, idiomatic Kotlin.
- **`newSuspendedTransaction(Dispatchers.IO, db)`** — Exposed's coroutine-aware transaction wrapper dispatches to IO internally; no outer `withContext` needed, keeping stack traces clean.
- **`upsert` on save** — `INSERT ... ON CONFLICT (id) DO UPDATE` so saves are idempotent (matches YAML behavior where save overwrites the file).
- **Metrics** — reuses existing `playerRepoLoadTimer` / `playerRepoSaveTimer` / `onPlayerSave()` / `onPlayerSaveFailure()`.
- **Auto-generated IDs** — `id` column has `DEFAULT nextval('player_id_seq')`; the insert omits `id` and Exposed reads the generated value back from `result[PlayersTable.id]`, replacing the file-based `next_player_id.txt`.
- **`name_lower` column** — materialized lowercase for portable case-insensitive uniqueness. The unique index on `name_lower` works identically on Postgres and H2, avoiding expression-index dialect issues. The `INSERT` will throw on conflict, which we catch and wrap as `PlayerPersistenceException`.

### 3c. Create `DatabaseManager.kt`

Path: `src/main/kotlin/dev/ambon/persistence/DatabaseManager.kt`

Encapsulates HikariCP pool creation, Flyway migration, and Exposed `Database` connection:

```kotlin
class DatabaseManager(private val config: DatabaseConfig) {
    private val hikariDataSource: HikariDataSource

    val database: Database

    init {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.username
            password = config.password
            maximumPoolSize = config.maxPoolSize
            minimumIdle = config.minimumIdle
        }
        hikariDataSource = HikariDataSource(hikariConfig)
        database = Database.connect(hikariDataSource)
    }

    fun migrate() {
        Flyway.configure()
            .dataSource(hikariDataSource)
            .load()
            .migrate()
    }

    fun close() {
        hikariDataSource.close()
    }
}
```

---

## Phase 4: Wiring in MudServer

### 4a. Update `MudServer.kt`

Replace the fixed `yamlRepo` initialization with backend selection:

```kotlin
private val databaseManager: DatabaseManager? =
    if (config.persistence.backend == PersistenceBackend.POSTGRES) {
        DatabaseManager(config.database).also { it.migrate() }
    } else null

private val baseRepo: PlayerRepository =
    when (config.persistence.backend) {
        PersistenceBackend.YAML -> YamlPlayerRepository(
            rootDir = Paths.get(config.persistence.rootDir),
            metrics = gameMetrics,
        )
        PersistenceBackend.POSTGRES -> PostgresPlayerRepository(
            database = databaseManager!!.database,
            metrics = gameMetrics,
        )
    }

// Redis cache wraps whichever backend is selected
private val redisRepo: RedisCachingPlayerRepository? =
    if (redisManager != null) {
        RedisCachingPlayerRepository(delegate = baseRepo, ...)
    } else null

private val l2Repo: PlayerRepository = redisRepo ?: baseRepo

// Write coalescing wraps whatever L2 is
private val coalescingRepo: WriteCoalescingPlayerRepository? = ...
private val playerRepo = coalescingRepo ?: l2Repo
```

Add `databaseManager?.close()` in `stop()`.

The full decorator chain `WriteCoalescing → RedisCache → Postgres/Yaml` is preserved unchanged.

---

## Phase 5: Tests

### 5a. `PostgresPlayerRepositoryTest.kt`

Path: `src/test/kotlin/dev/ambon/persistence/PostgresPlayerRepositoryTest.kt`

Uses H2 in PostgreSQL-compatibility mode. Mirrors the existing `YamlPlayerRepositoryTest` structure:

```kotlin
class PostgresPlayerRepositoryTest {
    companion object {
        private lateinit var hikari: HikariDataSource
        private lateinit var database: Database

        @BeforeAll @JvmStatic
        fun setup() {
            val config = HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                username = "sa"
                password = ""
            }
            hikari = HikariDataSource(config)
            database = Database.connect(hikari)
            Flyway.configure().dataSource(hikari).load().migrate()
        }

        @AfterAll @JvmStatic
        fun teardown() { hikari.close() }
    }

    @BeforeEach
    fun reset() {
        transaction(database) {
            PlayersTable.deleteAll()
            exec("ALTER SEQUENCE player_id_seq RESTART WITH 1")
        }
    }

    @Test fun `create then findById and findByName`() = runTest { ... }
    @Test fun `save persists changes`() = runTest { ... }
    @Test fun `create enforces unique name case-insensitive`() = runTest { ... }
    @Test fun `findByName returns null for unknown`() = runTest { ... }
    @Test fun `findById returns null for unknown`() = runTest { ... }
}
```

Tests cover:
1. Create + findById + findByName round-trip
2. Save persists all fields (including upsert behavior)
3. Case-insensitive name uniqueness (throws `PlayerPersistenceException`)
4. Null returns for missing players
5. Flyway migrations apply cleanly on H2

### 5b. Existing tests remain unchanged

- `InMemoryPlayerRepository` unit tests — untouched
- `WriteCoalescingPlayerRepositoryTest` — untouched (wraps any `PlayerRepository`)
- `RedisCachingPlayerRepositoryTest` — untouched
- `YamlPlayerRepositoryTest` — untouched

---

## Phase 6: Lint & Verify

1. `./gradlew ktlintCheck` — ensure new code passes linting
2. `./gradlew test` — all existing + new tests pass

---

## Files Changed (Summary)

| File | Change |
|------|--------|
| `build.gradle.kts` | Add Exposed, HikariCP, PostgreSQL driver, Flyway, H2 test deps |
| `src/main/kotlin/dev/ambon/config/AppConfig.kt` | Add `PersistenceBackend` enum, `DatabaseConfig`, validation |
| `src/main/resources/application.yaml` | Add `database` section, `backend` field |
| `src/main/resources/db/migration/V1__create_players_table.sql` | New: schema + sequence (single migration) |
| `src/main/kotlin/dev/ambon/persistence/PlayersTable.kt` | New: Exposed DSL table definition |
| `src/main/kotlin/dev/ambon/persistence/DatabaseManager.kt` | New: HikariCP + Flyway + Exposed wrapper |
| `src/main/kotlin/dev/ambon/persistence/PostgresPlayerRepository.kt` | New: Exposed-based `PlayerRepository` impl |
| `src/main/kotlin/dev/ambon/MudServer.kt` | Backend selection wiring |
| `src/test/kotlin/dev/ambon/persistence/PostgresPlayerRepositoryTest.kt` | New: H2-backed tests |

## Out of Scope (Future Work)

- Data migration CLI tool (YAML → Postgres import)
- Inventory/items persistence
- World state persistence
- Connection pool metrics integration with Micrometer
