# Architectural Review Summary — April 2026

**Date:** April 3, 2026
**Scope:** Full codebase (~314 source files, ~137 test files)
**Method:** 8 parallel review agents across all architectural domains, followed by 16 fix PRs across 3 waves

## Overview

A comprehensive pre-release architectural review was conducted across 8 domains: engine core, persistence, transport, commands, game systems, configuration, sharding/gRPC, and test coverage. The review identified ~100 findings which were addressed through 16 PRs, all merged to `main`.

## PRs Merged

### Wave 1: Critical Fixes (PRs #863–#870)

| PR | Title | Files Changed | Tests Added |
|----|-------|:---:|:---:|
| #863 | Engine concurrency fixes | 4 | — |
| #864 | Economy atomicity (trade/auction) | 6 | 9 |
| #865 | Player death cleanup (8 systems) | 4 | 2 |
| #866 | Persistence & cache coherence | 6 | 6 |
| #867 | Config validation & metric cardinality | 3 | 9 |
| #868 | Transport & networking reliability | 4 | — |
| #869 | Admin & transport security | 7 | 5 |
| #870 | Command system hardening | 6 | 11 |

### Wave 2: High-Priority Fixes (PRs #871–#874)

| PR | Title | Files Changed | Tests Added |
|----|-------|:---:|:---:|
| #871 | Sharding, gRPC, Redis reliability | 11 | — |
| #873 | Test coverage additions | 9 | 61 |
| #874 | Memory leak prevention & cleanup | 6 | 5 |

### Wave 3: Lower-Priority Polish (PRs #875��#879)

| PR | Title | Files Changed | Tests Added |
|----|-------|:---:|:---:|
| #875 | Persistence polish (indexes, normalization) | 7 | — |
| #876 | Protocol & engine polish | 4 | ��� |
| #877 | CI/DevOps (JaCoCo, CodeQL, test reports) | 6 | — |
| #878 | Game system edge cases | 5 | — |
| #879 | Command & UX polish | 22 | — |

**Totals:** ~120 files changed, ~108 new tests added

## Findings by Category

### 1. Player Death Cleanup (PR #865)

`handlePlayerDeath()` in CombatSystem only cleaned up combat state. Added cleanup calls for:
- GroupSystem (leave group)
- TradeSystem (cancel active trade, return escrowed items)
- DuelSystem (end active duel)
- PetSystem (dismiss all pets)
- StatusEffectSystem (clear all effects)
- AbilitySystem (reset cooldowns)
- DialogueSystem (end conversations)
- DungeonManager (remove from instance)

### 2. Economy Exploit Prevention (PR #864)

Gold and item transfers in trades and auctions were non-atomic — crash between steps could duplicate gold or items.

- Trade completion now validates gold at completion time (not just offer time) and transfers items + gold in a single method
- Auction purchase deducts gold and transfers items atomically
- Negative gold prevention via `coerceAtLeast(0)`
- Added `TradeResult` and `AuctionPurchaseResult` sealed interfaces for explicit success/failure handling

### 3. Engine Concurrency (PR #863)

- GMCP dirty tracking sets wrapped in `try/finally` to guarantee clearing even if flush throws
- Room member map cleanup: empty rooms evicted from `roomMembers` map
- Scheduler logging improved: shows deferred count instead of silently dropping
- Handoff timeout now logs at WARN level for disconnected players

### 4. Security Hardening (PRs #868, #869)

- **JSON injection** in admin error responses fixed (now uses Jackson serialization)
- **Constant-time auth comparison** via `MessageDigest.isEqual()`
- **WebSocket origin validation** checks Origin against Host header
- **GMCP JSON parsing** replaced string splitting with Jackson `readValue`
- **GMCP payload size limit** of 64KB with WARN logging on overflow
- **Close reason sanitization** filters to printable ASCII only
- **Connection limits** on telnet transport (configurable, default 5000)
- **WebSocket frame size validation** (max 64KB)
- **Snowflake ID overflow** now spin-waits instead of returning duplicates
- **Socket leak** on accept failure fixed with try-catch cleanup

### 5. Persistence & Cache (PRs #866, #875)

- **Redis cache invalidation on save** — `storeOnSave()` override added
- **Auction atomic writes** using `atomicWriteText()` temp-file-then-rename pattern
- **HikariCP timeouts** configured (maxLifetime 30min, connection 30s, idle 10min)
- **PostgreSQL exception handling** uses SQL state code `23505` instead of string matching
- **YAML temp file leak** cleanup in `finally` block
- **Write coalescing eviction race** fixed with lock around dirty flag + cache removal
- **Flyway V27** adds index on `auth_token_hash` column
- **Stat key normalization** on load (uppercase for stats, lowercase for factions/crafting)
- **FlushResult** data class reports both success and failure counts
- **Stat value constraints** coerced to >= 1 to prevent division-by-zero
- **Guild ID sanitization** logs warning on collision-risk normalization

### 6. Configuration & Metrics (PRs #867, #877, #878)

- **Metric cardinality explosion** fixed: `DisconnectReason` and `GrpcDropReason` enums normalize arbitrary strings to fixed tag values; tick phases pre-registered
- **Config validation** additions:
  - `startRoom` required (non-null)
  - World time hours must be ordered (dawn < day < dusk < night)
  - Faction cross-references fail on undefined enemies (was warn-only)
  - Sharding requires Redis; instancing requires sharding
  - Equipment slot orders must be unique
  - XP exponent >= 1.0
  - Outbound queue capacity capped at 100,000
  - CORS wildcard `*` triggers startup warning
  - Status effect `maxStacks >= 1` required
- **JaCoCo** code coverage added to build
- **CI test reports** uploaded as artifacts
- **CodeQL** upgraded to `security-and-quality` queries
- **Default DB password** changed to `changeme` placeholder

### 7. Command System (PRs #870, #879)

- **State validation** in item handlers: wear/remove/get/drop/give blocked during combat, dialogue, and trade
- **Prompt suppression bug** fixed: cross-zone move failure no longer leaves player without prompt
- **Input length limit** of 2000 characters in CommandParser
- **Auction price validation**: must be > 0 and <= 1 billion
- **Dialogue cleanup on flee** via `dialogueSystem.endConversation()`
- **Broadcast rate limiting**: gossip/shout/ooc limited to 1 per 2 seconds (staff exempt)
- **Error message standardization**: ~60 error paths changed from `SendText` to `SendError` across 13 handlers
- **Dialogue choice range** extended from 1-9 to 1-99
- **Hot-reload guard** prevents concurrent reloads (returns 409 Conflict)
- **Admin rate limiting**: staff toggle 5s, broadcast 10s, reload 30s cooldowns

### 8. Sharding & gRPC (PR #871)

- **Handoff timeout recovery**: player restored to source room with metric counter
- **Gateway stream orphan cleanup**: snapshot-based filtering prevents race with reconnecting sessions
- **gRPC control plane timeout** increased from 250ms to 2000ms (configurable)
- **Redis failure handling**: `isConnected()` method, WARN logging, `redis_unavailable_total` metric
- **Redis async error handling**: `.exceptionally()` callbacks on fire-and-forget operations
- **Stale zone instance cleanup**: expired lease entries deleted from Redis hash

### 9. Memory Leak Prevention (PR #874)

- **GmcpEmitter `lastZoneBySession`** bounded with LRU cap (10,000 entries)
- **Threat table** periodic stale entry sweep every 60 seconds
- **Grace period `fullDisconnect()`** made idempotent with guard set

### 10. Protocol & Engine Polish (PR #876)

- **ProtoMapper** logs WARN on unknown event types with case name and session ID
- **GrpcOutboundDispatcher** drains pending events on shutdown (2s timeout)
- **allPlayers()** cached once per tick instead of allocating new list 3+ times
- **SessionRouter** failure logging improved with event type and session ID
- **Round-robin modulo** uses `Math.floorMod()` to handle integer overflow

### 11. Game System Edge Cases (PR #878)

- **Status effect stacking**: `maxStacks=0` treated as 1 (defensive clamp)
- **Quest/achievement during death**: callbacks skip when player HP <= 0
- **Dungeon failed creation**: cleanup on exception removes partial rooms/mappings
- **Admin possess validation**: mob despawn during possession triggers full cleanup

### 12. Test Coverage (PR #873)

Added 61 new tests across 6 new test files:

- **MultiSystemIntegrationTest** (6 tests): Death cleanup chains, quest-achievement interaction, mob kill advancing quests
- **ShutdownCleanupTest** (6 tests): Dirty record flush, trade cancellation, duel cleanup on shutdown
- **DuelCommandTest** (10 tests): Full duel lifecycle including timeout and disconnect
- **TradeCommandTest** (10 tests): Full trade lifecycle including escrow and disconnect
- **DungeonCommandTest** (8 tests): Entry, exit, level requirements, difficulty selection
- **HousingCommandTest** (8 tests): Status, purchase, templates, guest management
- **MobSystemTest** expanded from 1 to 8 tests: Spawn, death, respawn, movement, aggro

## Items Not Addressed

The following items were assessed and intentionally not changed:

- **Telnet partial writes** (false positive — Java's `OutputStream.write(byte[])` guarantees full write or throws)
- **Inventory capacity in give command** (no inventory limit system exists in the codebase)
- **baseMana/baseMaxHp persistence** (runtime-computed from level progression, not independent persisted values)
- **Auction in-memory cleanup** (already cleaned up every tick via `expireListings()`)
