# Design Decisions

This project is intentionally built like a production backend service, even though it's "just a MUD."
The goal is to keep the codebase easy to extend (world content, commands, transports, persistence) without premature complexity.

## Table of Contents

1. Event-driven engine (not request/response)
2. Transport as a replaceable adapter (telnet first, WebSocket added)
3. Backpressure handled explicitly
4. ANSI support is semantic (not sprinkled escapes)
5. World content is data (not code)
6. Namespaced IDs for multi-zone worlds
7. Persistence is phased (YAML → Redis → DB)
8. Dependency injection without a framework
9. Tests as design constraints
10. Event bus abstraction (not raw channels)
11. Write-behind persistence worker (async, coalescing)
12. Redis as opt-in infrastructure (not a hard dependency)

---

## 1) Event-driven engine (not request/response)

Decision: The server runs as a long-lived event loop (tick-based) consuming inbound events and emitting outbound events.

Why:
- A MUD is a stateful real-time system, not a stateless HTTP API.
- The event model provides a clean boundary between transport and game logic.
- A tick loop makes it easy to add world ticks (combat, regen, mob AI, scheduled events, zone resets) without re-architecting.

Tradeoff:
- You need to be disciplined about blocking calls. Network and disk I/O must not stall the loop.

---

## 2) Transport as a replaceable adapter (telnet first)

Decision: Start with telnet for maximum simplicity and compatibility, while keeping the core engine unaware of transport specifics. Add WebSockets as a second adapter (for a browser demo client) without rewriting game logic.

Why:
- Telnet gives immediate feedback and makes iteration fast.
- The engine speaks in semantic events (SendText, SendPrompt, Close, ClearScreen) instead of raw socket writes.
- WebSockets (Ktor) provides a low-friction browser client while still bridging only lines in and framed text out.
- This keeps a clean path to future transports (SSH, other clients) without rewriting game logic.

Tradeoff:
- Telnet negotiation/line decoding and WebSocket framing are "edge" concerns, but they stay isolated to transport.

---

## 3) Backpressure handled explicitly

Decision: Outbound writes use bounded queues and disconnect slow clients rather than letting memory grow unbounded.

Why:
- A single slow client should never degrade the server or other players.
- Prompt coalescing prevents spamming prompts when the client is not reading.

Tradeoff:
- Some output may be dropped (prompts are treated as disposable), but correctness and stability win.

---

## 4) ANSI support is semantic (not sprinkled escapes)

Decision: ANSI behavior is represented as semantic events (SetAnsi, ClearScreen, ShowAnsiDemo) and rendered by a per-session renderer.

Why:
- Prevents escape sequences from leaking into domain logic.
- Keeps behavior testable (engine tests verify semantics; transport tests verify formatting).
- Avoids "remember to append \u001B[...] everywhere" drift.

Tradeoff:
- Slightly more plumbing up front, but much less tech debt.

---

## 5) World content is data (not code)

Decision: Rooms/exits/mobs/items live in YAML and are validated on load.

Why:
- Iterating on world design should not require recompiling.
- Validation catches broken exits and invalid directions early.
- This structure is a stepping stone toward more advanced area formats.

Tradeoff:
- Data-loading and validation code is extra work early, but pays off immediately in iteration speed.

---

## 6) Namespaced IDs for multi-zone worlds

Decision: Room, mob, and item IDs are namespaced as zone:id rather than plain ids.

Why:
- Avoids global ID collisions as the world grows.
- Makes multi-zone loading and cross-zone exits possible without hacks.
- Mirrors how large MUDs typically partition content.

Tradeoff:
- Slightly more verbose YAML, but dramatically fewer future constraints.

---

## 7) Persistence is phased (YAML → Redis → DB)

Decision: Use YAML player persistence as the durable layer, with a layered repository stack that adds write-behind coalescing and Redis caching in subsequent phases.

Why:
- YAML persistence is inspectable, easy to debug, and requires no infrastructure (Phase 1).
- Repository abstraction allows clean migration or cache layering without touching game logic.
- Atomic write strategy prevents corruption on crash at every layer.
- Write-behind coalescing (Phase 2) removes persistence from the hot path — every room move was hitting disk.
- Redis L2 cache (Phase 3) allows faster reads across processes and reduces directory scan frequency.

Tradeoff:
- Directory scans for lookup are acceptable at current scale; Redis name index defers the scaling wall.
- Write-behind creates a potential data loss window (configurable, default 5 s); acceptable for a game server.

---

## 8) Dependency injection without a framework

Decision: Compose dependencies in the bootstrap layer (constructor injection), not via globals or singletons.

Why:
- Keeps tests lightweight (swap repo or clock easily).
- Makes ownership boundaries explicit.
- Avoids early framework lock-in.

Tradeoff:
- Slightly more wiring in main, but increased clarity and testability.

---

## 9) Tests as design constraints

Decision: Tests are written early, including regression tests for real bugs encountered during development.

Why:
- Prevents subtle protocol and ANSI regressions that are hard to notice manually.
- Encourages semantic event boundaries (engine tests assert events; transport tests assert bytes/lines).
- Makes refactors safer as the project grows.

Tradeoff:
- Some extra time up front, but faster iteration over time.

---

## 10) Event bus abstraction (not raw channels)

Decision: Extract `InboundBus` and `OutboundBus` interfaces wrapping Kotlin channels, rather than passing `Channel<T>` directly to the engine.

Why:
- Creates a clean substitution point: swap `LocalInboundBus` for `RedisInboundBus` without changing any engine code.
- Mirrors the transport adapter pattern at the bus layer — the engine is now fully decoupled from both transport and bus technology.
- Enables future gRPC gateway split (Phase 4) with `GrpcInboundBus` / `GrpcOutboundBus`.
- The interface is minimal: `send`, `trySend`, `tryReceive`, `close` — only what the engine actually needs.

Tradeoff:
- One extra indirection compared to raw channels, but the payoff in substitutability is immediate and demonstrated (Redis bus works today).

---

## 11) Write-behind persistence worker (async, coalescing)

Decision: Move player saves off the engine tick into a background coroutine with dirty-flag coalescing.

Why:
- Every room move was calling `repo.save()` synchronously, which was blocking I/O on the engine's hot path (even though it used `Dispatchers.IO`).
- Coalescing means 10 rapid room changes produce exactly 1 file write.
- `PlayerRegistry.kt` calls `repo.save()` exactly as before — the coalescing is transparent to game logic.
- Shutdown sequence flushes all dirty records before stopping, so data loss is bounded to the flush interval.

Tradeoff:
- Up to `flushIntervalMs` (default 5 s) of data loss on hard crash. Acceptable for a game server; configurable if requirements tighten.

---

## 12) Redis as opt-in infrastructure (not a hard dependency)

Decision: Redis is enabled via config (`redis.enabled = false` by default). The server runs identically without it at every scale.

Why:
- Forcing Redis in development would add friction and a required service just to start the server.
- The bus and cache layers both degrade gracefully: Redis failure logs a warning and falls back to the local implementation.
- Allows incremental adoption: Redis cache only (no bus), or both, or neither.
- Keeps the test suite fast — integration tests for Redis use Testcontainers and are isolated.

Tradeoff:
- Dual-path code (enabled/disabled) requires explicit null checks on `RedisConnectionManager.commands`. This is intentional and visible, not hidden.
