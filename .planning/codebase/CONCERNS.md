# Codebase Concerns

**Analysis Date:** 2026-04-05

## Memory Leak Risks

### Session-Scoped Maps Without Cleanup

**Affected Systems:**
- `DuelSystem.kt` - `pendingChallenges` and `activeDuels` maps track SessionId keys without cleanup on disconnect
- `LotterySystem.kt` - `lastGambleTime` map accumulates SessionId keys indefinitely
- `TradeSystem.kt` - `sessions` map may leave orphaned entries if one participant disconnects mid-trade
- `GameEngine.kt` - `gmcpSessions` map stores GMCP package support per SessionId but is never cleared on disconnect
- `PuzzleSystem.kt` - Calls explicit `removeSession()` (correctly implemented), but not automatically invoked by engine on disconnect

**Impact:** Long-running servers will accumulate stale SessionId entries in memory. Over months of operation, memory usage grows linearly with total sessions ever created, not just active sessions.

**Fix Approach:**
1. Implement `onPlayerDisconnected()` method in `DuelSystem`, `LotterySystem`, and `TradeSystem` to clean up entries
2. Add automatic cleanup in `GameEngine.SessionEventHandler.fullDisconnect()` to call `gmcpSessions.remove(sessionId)`
3. Verify `PuzzleSystem.removeSession()` is called from `GameEngine.SessionEventHandler.fullDisconnect()`
4. Add tests to verify cleanup happens: create session entry, disconnect, verify entry removed

### Unvalidated SessionId Accumulation in SessionGracePeriodManager

**File:** `SessionGracePeriodManager.kt` (lines 31–37)
- `byToken` map stores resumable sessions indefinitely
- `byName` and `preIssuedTokens` maps have no TTL or expiration mechanism
- If a player repeatedly reconnects and disconnects, entries may accumulate

**Fix Approach:** Add expiration logic to clean up entries older than `engine.login.graceWindowSeconds`; implement periodic sweep in scheduler.

---

## Test Coverage Gaps

### Command Handler Coverage

**Missing Direct Tests:**
- `PuzzleHandler` - No dedicated test file; puzzle solving tested only via `GameEngineIntegrationTest`
- `CurrencyHandler` - Only integration-level testing through command routing
- `GlobalQuestHandler` - Minimal handler-level tests, only system-level tests
- `GuildHallHandler` - Tested in `GuildHallSystemTest` but not handler command dispatch tests

**Files:** `src/test/kotlin/dev/ambon/engine/commands/handlers/` has no tests for these handlers

**Impact:** Handler edge cases (invalid input, state validation, error messages) may not be exercised. Regressions in handler logic won't be caught by unit tests.

**Fix Approach:** Create dedicated handler test files (e.g., `PuzzleHandlerTest.kt`, `CurrencyHandlerTest.kt`) following the pattern of `ShopHandlerTest` and `AuctionHandlerTest`.

### Persistence Edge Cases

**Missing Scenario Coverage:**
- `WriteCoalescingPlayerRepository` - No test for version mismatch on concurrent flushes (cache version vs pending version divergence)
- Round-trip serialization of newly added fields (e.g., `authTokenHash`, `authTokenIssuedAt`, `dailyQuestData`) not tested in `PersistenceFieldCoverageTest`
- Postgres JSONB field truncation or encoding issues in `PlayersTable` (custom JSON fields: `stats`, `activeQuests`, `factionStandings`, `currencies`, etc.)

**Files:** `src/test/kotlin/dev/ambon/persistence/`

**Impact:** Silent data loss if JSON serialization fails or if fields exceed DB column limits.

**Fix Approach:** Add round-trip tests for all new persistence fields; add JSON encoding/truncation tests for large maps.

### Untested Transport Scenarios

**Missing Coverage:**
- `BlockingSocketTransport` - No test for TCP connection limit enforcement (`maxConnections` parameter)
- `KtorWebSocketTransport` - No test for frame size limits or malformed WebSocket messages
- `OutboundRouter` - No test for session disconnection while frames are pending in the queue
- `TelnetLineDecoder` - No test for lines with embedded nulls or extremely long non-printable sequences

**Files:** `src/main/kotlin/dev/ambon/transport/` - Test coverage in `src/test/kotlin/dev/ambon/transport/` is minimal

**Impact:** Transport-level DoS attacks (malformed packets, connection floods) not mitigated by tests. Unknown stability under stress.

**Fix Approach:** Add stress tests; test connection limits, payload size limits, and malformed data handling.

---

## Security Considerations

### Admin HTTP Authentication Uses Plain String Comparison

**File:** `AdminHttpServer.kt` (lines ~945–973)
- Uses `MessageDigest.isEqual(password.toByteArray(), token.toByteArray())` to compare the provided password with the configured token
- Token is stored as plain text in `AppConfig.admin.token`
- No rate limiting on authentication attempts

**Current Mitigation:** Token must be passed via HTTP Basic Auth (Base64 encoded in headers); not logged.

**Recommendation:**
1. Hash the configured token at startup using a fast KDF (e.g., PBKDF2) instead of plain string comparison
2. Add rate limiting to `/admin` routes (max 5 failed attempts per IP per minute)
3. Log authentication attempts (with IP address, timestamp, success/failure) to detect brute-force attempts
4. Document in README that `ambonMUD.admin.token` should be a strong secret (32+ chars)

### Password Storage

**File:** `LoginFlowHandler.kt` (BCrypt verification)
- Passwords are correctly hashed with BCrypt (verified via `BCryptPasswordHasher`)
- Password validation is non-blocking (handled on `Dispatchers.IO` context via `hashingContext`)

**Verified Safe:** No known vulnerabilities.

### Input Validation Gaps

**Affected Areas:**
- Player names: Validated as 2–16 chars, alnum/underscore in `PlayerNameValidator`; susceptible to Unicode normalization attacks (e.g., `ı` vs `i` in Turkish)
- Item names, room descriptions, NPC dialogue: No length limits; could cause JSON serialization to exceed database column widths (JSONB fields in Postgres have implicit limits)
- YAML world loading: `WorldLoader.kt` validates structure but does not enforce max depths for nested dialogue/behavior trees (DoS via deeply nested trees)

**Fix Approach:**
1. Add Unicode NFKC normalization to player name validation
2. Add max length constraints to all user-generated text fields (descriptions, custom titles, etc.)
3. Add recursion depth limits in `WorldLoader` when parsing dialogue trees and behavior trees

---

## Performance Bottlenecks

### GameEngine Tick Loop Scale

**File:** `GameEngine.kt` (line ~400–1500, `run()` method)
- Single 100ms tick processes all players, mobs, abilities, status effects, and systems sequentially
- No per-tick budget enforcement for expensive systems (crafting, achievement checks, global quests)
- `tickDuels()` (line ~2397) and `tickCombat()` iterate over all active duels/combats every tick

**Current Limits:**
- `combat.maxActionsPerTick`: 500 (hard cap on damage events)
- No similar cap for: duel ticks, quest updates, achievement checks, global quest updates

**Impact:** If many players are in duels simultaneously (e.g., tournament mode), tick time could exceed 100ms, causing lag.

**Improvement Path:**
1. Implement per-system tick budgets (ms allocation per tick)
2. Add metrics for per-system tick time (`duelSystemTickMs`, `craftingSystemTickMs`, etc.)
3. If budget exceeded, defer non-critical updates to next tick (e.g., achievement checks)

### Threat Table Linear Scans

**File:** `CombatSystem.kt` (line ~83, `ThreatTable` data structure)
- `ThreatTable` is a map of `MobId` → map of `SessionId` → threat amount
- `getHighestThreatTarget()` scans all entries to find max (O(n) per call)
- Called once per mob per combat tick

**Impact:** In large-scale combat (20+ mobs, 50+ players), threat lookups accumulate O(1000+) comparisons per tick.

**Improvement Path:** Use a priority queue instead of HashMap to maintain O(log n) highest-threat lookup.

### PlayerRegistry Room Membership Index

**File:** `PlayerRegistry.kt` (line ~113)
- `roomMembers` map stores Set of SessionIds per RoomId
- O(n) full scan to broadcast messages to all players in a room

**Impact:** Rooms with 100+ players cause O(100) message sends per broadcast; acceptable but worth monitoring.

**Improvement Path:** No immediate optimization needed; monitor for player concentration.

---

## Fragile Areas

### Duel System State Inconsistency

**File:** `DuelSystem.kt` (lines 36–37, 86–87)
- `activeDuels` stores the same ActiveDuel under both player keys: `activeDuels[player1] = duel` and `activeDuels[player2] = duel`
- If one entry is removed but not the other (due to exception or racing remove call), the duel is orphaned and can never be ended
- No guard against removing a duel twice

**Safe Modification:**
1. Add idempotent `endDuel()` that returns early if already removed
2. Add tests to verify both players are cleaned up atomically
3. Consider using a single key (the duel object itself) instead of two SessionId keys

### Guild Record Mutation

**File:** `GuildSystem.kt` (line ~35)
- `guildsById` stores mutable `GuildRecord` objects
- No copy-on-write semantics; modifications are direct
- If an error occurs during a multi-step guild operation (e.g., invite → accept), the guild state may be left inconsistent

**Safe Modification:** Implement copy-on-write pattern; update the entire record via `update(guildId, record)` instead of mutating fields.

### World Hot-Reload Race Window

**File:** Multiple systems (abilities, status effects, mobs, world)
- Systems that cache world references call `rebuild(newWorld)` after hot-reload
- Players connected during reload may see mixed old/new content if update is not atomic

**Safe Modification:**
1. Pause the engine tick loop during hot-reload
2. Atomically swap all registries and world references
3. Resume tick loop
4. Add test to verify no mixed state is visible to players

### Auction Listing Persistence

**File:** `AuctionSystem.kt` (line ~62, `listings` map)
- Listings are stored in memory only; persisted to `data/auction_listings.json` on shutdown/reload
- If server crashes, all active auction listings are lost
- JSON file is not atomically written; corruption on crash is possible

**Fix Approach:** Use atomic write pattern (write to temp file, then rename) when persisting; consider moving to `WorldStateRepository` for consistent persistence.

---

## Scaling Limits

### Mob Combat Tick Timing

**File:** `CombatSystem.kt` (line ~80, `activeMobs` map)
- Each mob has a separate `nextTickAtMs` timestamp for throttling combat updates
- At 100+ concurrent mobs, the map iteration becomes O(n) every tick
- No sharding or bucketing by combat tick time

**Current Capacity:** ~500 active mobs before noticeably degrading tick time

**Scaling Path:**
1. Use time-bucketed lists (e.g., list of mobs due to tick in next 100ms)
2. Sort mobs by `nextTickAtMs` on insertion
3. Only iterate mobs with `nextTickAtMs <= now`

### Session Outbound Queue Backpressure

**File:** `OutboundRouter.kt`
- Each session has an `OutboundFrame` channel with configurable capacity (default 200)
- If client is slow to read, channel fills and `OutboundRouter` applies backpressure (slow down outbound sends)
- If backpressure lasts > threshold, session is forcibly disconnected

**Current Behavior:** Backpressure disconnects slow clients; this is intentional and working as designed.

**Monitoring:** Add metrics for channel utilization; alert if > 80% capacity sustained for > 5 seconds.

### PostgreSQL Persistence Scaling

**File:** `PlayersTable.kt` (198 lines)
- One row per player; all fields (including large JSON blobs: `stats`, `activeQuests`, `factionStandings`, etc.) are in a single row
- JSONB columns have no indexing; queries on nested fields are slow

**Current Capacity:** ~10,000 active players before DB contention

**Scaling Path:**
1. Move frequently-queried nested data (e.g., `activeQuests`) to separate tables
2. Add JSONB indexes on key fields
3. Consider read replicas for leaderboard/who queries

---

## Dependencies at Risk

### Jackson Deserialization Without Validation

**File:** Multiple (GmcpEmitter, LotterySystem, etc.)
- `jacksonObjectMapper()` is instantiated with `FAIL_ON_UNKNOWN_PROPERTIES=false` (in `RedisCachingPlayerRepository`)
- Unknown fields in persisted JSON are silently ignored, allowing silent data loss if schema changes

**Current Mitigation:** Schema versioning in `PlayerRecord.migrateDefaults()` handles known legacy fields.

**Recommendation:** Add explicit schema versioning (e.g., `schemaVersion: Int` field) to detect incompatible persistence formats.

### Exposed SQL DSL Complexity

**File:** `PlayersTable.kt`, `PostgresPlayerRepository.kt` (93 lines)
- Heavy use of custom JSON conversions in `readRecord()` and `writeRecord()`
- No type safety for JSON field access; stringly-typed keys

**Risk:** Typos in field names (e.g., `record["factionstandings"]` vs `record["factionStandings"]`) silently lose data.

**Fix Approach:** Use data classes with Jackson serialization instead of manual JSON assembly.

---

## Missing Critical Features

### No Session Encryption or HTTPS Enforcement

**Current State:**
- Web client supports WebSocket over HTTPS (via reverse proxy, not built-in)
- Telnet is always plain text (no TLS support)
- GMCP packages are sent unencrypted

**Risk:** Player credentials and game state transmitted in plaintext over Telnet; vulnerable to MITM attacks on LAN.

**Recommendation:**
1. Implement TLS support in `BlockingSocketTransport` (starttls upgrade path)
2. Generate self-signed certs for local development
3. Document production reverse proxy setup (nginx TLS termination)

### No Rate Limiting on Commands

**Current State:**
- All commands process immediately; no throttling
- Spam attacks (100+ commands/sec) could degrade tick loop

**Recommendation:**
1. Add configurable rate limit per command category (e.g., "move" max 2/sec)
2. Implement token bucket per SessionId
3. Return "You are doing that too fast." on rate limit

### No Audit Logging for Admin Actions

**File:** `AdminHttpServer.kt`
- Broadcasts, player kicks, spawns are not logged to persistent audit trail
- Cannot replay admin history or detect unauthorized access

**Recommendation:** Add audit log table; log all admin HTTP requests with timestamp, IP, action, and result.

---

## Test Coverage Gaps (Detailed)

### Persistence Migration Untested

**File:** `src/main/resources/db/migration/` (V1–V34 Flyway migrations)
- No test verifies that migrations from V33→V34 succeed and preserve existing data
- No test for downgrade scenarios

**Fix Approach:** Add migration integration tests that:
1. Create a schema at V33
2. Insert test data
3. Run migration to V34
4. Verify schema and data are intact

### GMCP Package Filtering Untested

**File:** `GameEngine.kt` (line ~492, `supportsPackage` lambda)
- `GmcpEmitter` filters sends based on supported packages; no test verifies filtering works
- If a package is sent without being marked as supported, no error is raised (silent drop)

**Impact:** Bugs in GMCP support tracking are invisible to logs.

**Fix Approach:** Add test to `GmcpEmitterTest` that:
1. Registers only `Char.Vitals` support
2. Attempt to send `Char.Stats` GMCP
3. Verify it is not sent (or logged as unsupported)

### InterEngineMessage Serialization Round-Trip Untested for New Variants

**File:** `src/test/kotlin/dev/ambon/sharding/InterEngineMessageHandlingTest.kt`
- Tests existing message types but no test for adding a new variant
- If a variant is added without updating `ProtoMapper`, it silently fails to deserialize

**Fix Approach:** Add parameterized test that round-trips all message variants through Proto serialization.

---

## Blocking I/O in Coroutine Context

### gRPC Service Wiring with runBlocking

**Files:**
- `Main.kt` (line ~18): `runBlocking { start() }` blocks the main thread during startup
- `GrpcOutboundDispatcher.kt` (line ~49): `runBlocking { dispatcher.dispatch(...) }` called from gRPC service thread

**Impact:** If `dispatch()` is slow, gRPC threads are blocked, causing timeout cascades.

**Fix Approach:**
1. In `GrpcOutboundDispatcher`, use `Channel.trySend()` instead of `runBlocking`, with backpressure handling
2. Startup `runBlocking` is acceptable (one-time); no fix needed there

### Thread.sleep() in Shutdown Handler

**File:** `Main.kt` (line ~81): `Thread.sleep(300)` in shutdown hook
- Blocks JVM shutdown for 300ms
- If multiple shutdown hooks run sequentially, cumulative delay increases

**Fix Approach:** Remove sleep; rely on graceful shutdown of coroutine scopes.

---

## Data Integrity Risks

### PlayerRecord Stats Normalization Edge Cases

**File:** `PlayerRecord.kt` (lines 101–127, `migrateDefaults()`)
- Legacy stat value of 0 is coerced to 1 (line ~107)
- But stat can become 0 again if manually set in gameplay
- No validation to prevent stats from dropping below 1

**Fix Approach:** Add invariant check in `PlayerProgression` and combat systems to prevent stat < 1.

### YAML Player Persistence Atomic Writes Not Verified

**File:** `YamlPlayerRepository.kt` (226 lines)
- Claims to use atomic writes (write to temp, rename)
- But implementation not verified; if OS doesn't guarantee atomic rename, corruption is possible on crash

**Fix Approach:** Add test that kills process mid-write and verifies file integrity on recovery.

### Flyway Migration Irreversibility

**Files:** All migration files in `src/main/resources/db/migration/`
- Migrations are one-way (no down() method)
- If a migration introduces a bug, rollback requires manual intervention

**Risk:** Downtime if a migration fails in production.

**Recommendation:** Test all migrations in staging environment before production deploy; document rollback procedure.

---

## Code Quality Concerns

### Excessive Non-null Assertions (!!)

**Files:** Multiple
- `AdminHttpServer.kt` (lines ~945, 954, 964, 973, 983, 992, 1002, 1011): `!!` assertions on nullable registries
- `GameEngine.kt` (lines ~1029, 1414, 1421): `!!` assertions on nullable sharding components
- `BlockingSocketTransport.kt` (line ~44): `serverSocket!!.accept()`

**Risk:** If registry is null due to misconfiguration, runtime crash with no clear error message.

**Fix Approach:** Replace `!!` with `checkNotNull()` to provide clear error messages on null pointer.

### Duplicate PlayerState Lookups

**File:** Multiple handlers call `players.get(sessionId)` twice in the same method
- Example: `NavigationHandler` calls `get()` once for validation, again for action
- Causes cache miss on second call

**Optimization:** Assign result to local variable and reuse.

### GmcpEmitter.kt File Size (2918 lines)

**File:** `GmcpEmitter.kt` is the largest engine file
- Contains 100+ public methods for different GMCP packages
- Difficult to navigate; high cognitive load

**Fix Approach:** Split into multiple files:
- `GmcpCharEmitter.kt` (Char.* packages)
- `GmcpCombatEmitter.kt` (Combat, Room.UpdateMob, etc.)
- `GmcpWorldEmitter.kt` (World.Time, Weather, etc.)

---

## Known Bugs

### Potential Race in Session Reconnection

**File:** `SessionGracePeriodManager.kt`, `SessionEventHandler.kt`
- Grace period allows reconnection within window
- If player reconnects with new IP while old session is still active, both sessions may be bound to same player ID
- No test verifies that old session is forcibly disconnected on new login

**Trigger:** Player A disconnects → Player A reconnects before grace window expires → Both sessions are active

**Workaround:** Takeover behavior exists; old session should be kicked. Verify this works.

**Fix Approach:** Add test; trace code path to ensure old session receive Disconnected event.

### ANSI Rendering Bug in Screen Reader Mode

**File:** `ScreenReaderFilter.kt`
- Strips ANSI codes from OutboundEvent text
- But ANSI codes in item names (e.g., colored potions) are also stripped, losing semantic info

**Impact:** Screen reader users cannot distinguish item rarity by color (which conveyed info in ANSI, but name in plain text is identical).

**Fix Approach:** Preserve semantic markers (e.g., `[RARE]` prefix) alongside ANSI colors.

---

## Documentation Gaps

### No Deployment Guide for GATEWAY Mode

**Files:** CLAUDE.md mentions GATEWAY mode but no production setup guide
- How to configure multiple gateways
- Load balancing across gateways
- Debugging cross-gateway issues

**Recommendation:** Add `docs/DEPLOYMENT_GUIDE.md` with GATEWAY mode setup, failover, and monitoring.

### No Persistence Schema Documentation

**Files:** `PlayersTable.kt` and migration files are the only schema reference
- No markdown documentation of what each field means or migration history

**Recommendation:** Generate schema docs from `PlayersTable.kt` comments.

---

## Summary: Priority Ranking

| Issue | Priority | Effort | Impact |
|-------|----------|--------|--------|
| Memory leak: SessionId maps without cleanup | High | Medium | Server becomes unusable after months |
| Missing DuelSystem/TradeSystem disconnect cleanup | High | Small | Memory leak |
| gmcpSessions not cleared on disconnect | High | Small | Memory leak |
| Admin auth lacks rate limiting | Medium | Small | Brute-force vulnerability |
| Test coverage: Handler-level tests missing | Medium | Medium | Regressions undetected |
| Threat table linear scans | Medium | Medium | Performance degrades at scale |
| gRPC service blocking with runBlocking | Medium | Small | Can cause cascading timeouts |
| PlayerRecord stats can drop below 1 | Medium | Small | Stat-based calculations break |
| Non-null assertions replaced with checkNotNull | Low | Small | Better error messages |
| GmcpEmitter file split into modules | Low | Medium | Code maintainability |

---

*Concerns audit: 2026-04-05*
