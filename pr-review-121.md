# PR #121 Review — Add configurable Kotlin swarm load-testing module

## Build & Test Verification

`ktlintCheck` and all tests pass (both `:swarm` and root project). One compiler warning:
- `SwarmRunner.kt:257` — `'lateinit' is unnecessary: definitely initialized in constructors.`

## Blockers

### 1. Shared `Random` instance across concurrent bot coroutines (data race)
**File:** `SwarmRunner.kt:56-65`

When `deterministic` is `false`, all bots share the same `Random.Default`-aliased
`random` instance, passed into each `BotWorker` and used concurrently from
`Dispatchers.IO` coroutines. `kotlin.random.Random` is not thread-safe — concurrent
calls can produce corrupted/repeated sequences or throw.

**Fix:** Create a per-bot `Random` instance in all cases.

### 2. `TelnetBotConnection` reader thread leaks on every connection cycle
**File:** `SwarmRunner.kt:231-268`

The reader `Thread` started in `TelnetBotConnection.init` is never interrupted or
joined on `close()`. Over a run with login churn, this creates hundreds of orphaned
threads.

**Fix:** Store the thread reference and call `thread.interrupt()` in `close()`.

### 3. Missing `WebSocket.request(1)` — WS bots stall after initial messages
**File:** `SwarmRunner.kt:299-338`

`WebSocket.Listener.onText` never calls `webSocket.request(1)`. Per the JDK
`java.net.http.WebSocket.Listener` contract, the runtime stops delivering messages
until the listener requests more. WS bots will stall and `pollLine` will timeout.

**Fix:** Add `webSocket.request(1)` at the end of `onText`.

## Non-Blocking Issues

### 4. Unbounded latency list growth in `SwarmMetrics`
`connectLatenciesMs` / `loginLatenciesMs` are plain `mutableListOf<Long>()`.
With high bot counts or long runs, these grow without bound.

### 5. Name truncation can cause bot collisions
`generateName` produces `"${prefix}_${idx.padStart(4,'0')}"` then `.take(16)`.
A 12+ char prefix loses the bot index after truncation; a 16-char prefix makes
all bots share the same name. Cap prefix at ~10 chars.

### 6. Login state machine false-positive
`login()` returns `true` when `stage >= 4` on a `pollLine` timeout. Stage 4 means
"password sent" but not "server accepted" — inflates login success metrics.

### 7. Unnecessary `lateinit` compiler warning
`SwarmRunner.kt:257` — `lateinit var output` should be `val output`.

## Summary

| # | Issue | Severity |
|---|-------|----------|
| 1 | Shared `Random` across coroutines (data race) | **Blocker** |
| 2 | Reader thread leak in `TelnetBotConnection` | **Blocker** |
| 3 | Missing `WebSocket.request(1)` — WS bots stall | **Blocker** |
| 4 | Unbounded latency list growth | Non-blocking |
| 5 | Name truncation can cause bot collisions | Non-blocking |
| 6 | Login state machine false-positive | Non-blocking |
| 7 | Unnecessary `lateinit` warning | Non-blocking |

**Recommendation:** Fix the three blockers before merging.
