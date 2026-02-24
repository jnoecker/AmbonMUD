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
13. Gateway reconnect with bounded backoff (not unbounded retry)
14. GMCP as a structured data channel (not parsed ANSI)
15. Config-driven abilities (not hardcoded spells)
16. Zone-based sharding (zone as the shard unit)
17. Zone instancing for hot-zone load distribution
18. HMAC-signed Redis bus envelopes

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

---

## 13) Gateway reconnect with bounded backoff (not unbounded retry)

Decision: When a gateway's gRPC stream to the engine fails, the gateway performs bounded exponential-backoff reconnect with configurable limits — not an unbounded retry loop.

Why:
- Network partitions and engine restarts are expected in a split deployment; silent session loss is a poor user experience.
- Exponential backoff with jitter prevents thundering herd when multiple gateways reconnect simultaneously.
- A hard attempt budget (`maxAttempts`, default 10) prevents a gateway from retrying forever against a permanently dead engine, which would waste resources and mask failures.
- During reconnect, the inbound channel is closed so new connection attempts fail fast — no silent event queuing into a stale stream.
- After successful reconnect the gateway accepts new sessions immediately; old sessions are cleanly disconnected with an informational message.

Tradeoff:
- All sessions on a gateway are lost on stream failure (each reconnect starts fresh). Session migration would require engine-side session state serialization, which is significantly more complex and deferred for now.
- The `streamVerifyMs` health-check window adds latency to each reconnect attempt, but prevents false-positive "reconnected" states from immediately-dying streams.

---

## 14) GMCP as a structured data channel (not parsed ANSI)

Decision: Send structured JSON data (vitals, room info, inventory, skills) via GMCP subnegotiation alongside plain-text output, rather than requiring clients to parse ANSI text.

Why:
- Rich clients (web, graphical) need machine-readable data for sidebar panels — parsing ANSI text is fragile and version-dependent.
- GMCP is a well-established MUD protocol (telnet option 201) supported by most modern MUD clients (Mudlet, Nexus, etc.).
- The engine's `GmcpEmitter` emits data alongside existing `OutboundEvent`s — no changes to the engine/transport boundary.
- WebSocket clients auto-opt into all GMCP packages; telnet clients negotiate via standard `WILL`/`DO`.
- 13 packages cover character, room, inventory, skills, and communication channels.

Tradeoff:
- GMCP adds telnet subnegotiation complexity (IAC/SB/SE framing), but this is isolated to `TelnetLineDecoder` and `NetworkSession`.
- Maintaining JSON payloads in sync with game state requires emitting GMCP events at every state change point, but the `GmcpEmitter` centralizes this.

---

## 15) Config-driven abilities (not hardcoded spells)

Decision: Define spell/ability definitions in `application.yaml` configuration rather than hardcoding them in Kotlin source.

Why:
- Adding, tuning, or rebalancing abilities should not require recompilation — the same philosophy as world content being data.
- Config validation at startup (`AppConfig.validated()`) catches misconfigured abilities early.
- Class restrictions, mana costs, cooldowns, and effect values are all tunable per-deployment.
- `AbilityRegistryLoader` transforms config into an `AbilityRegistry` that the engine consumes.

Tradeoff:
- Config-driven means no compile-time type safety for ability definitions — misconfigured `targetType` or `effect.type` could be caught only at startup.
- Complex spell effects (multi-hit, area-of-effect) may eventually outgrow flat config and require a scripting layer.

---

## 16) Zone-based sharding (zone as the shard unit)

Decision: Partition the game world across multiple engine processes by zone, using asynchronous inter-engine messaging for cross-zone operations.

Why:
- Zones are natural boundaries: rooms are namespaced (`zone:room`), mobs/items belong to zones, zone resets are independent.
- Most game operations (combat, movement, communication) are zone-local, minimizing cross-shard traffic.
- A player handoff protocol handles cross-zone movement with serialized state transfer and ACK-based rollback on failure.
- Single-engine deployment (`STANDALONE`) remains valid — sharding is opt-in configuration.

Tradeoff:
- Cross-zone operations (`tell`, `gossip`, `who`, `goto`, `transfer`) require inter-engine messaging, adding latency.
- Player handoff creates a brief transit window (~100ms) where the session is in limbo.
- Redis becomes a hard dependency for sharded deployments (ZoneRegistry, InterEngineBus, PlayerLocationIndex).

---

## 17) Zone instancing for hot-zone load distribution

Decision: Allow popular zones to run multiple instances on the same or different engines, with players assigned via load-balanced instance selection and able to switch instances with the `phase` command.

Why:
- A single zone (e.g., the starting hub) can become a bottleneck if all players concentrate there.
- Instancing adds horizontal capacity within a zone without splitting it into artificial sub-zones.
- Auto-scaling based on capacity thresholds (`ThresholdInstanceScaler`) handles load spikes without manual intervention.
- Players on different instances can still use cross-instance communication (`gossip`, `tell`, `who`).

Tradeoff:
- Players on different instances of the same zone cannot see each other in rooms, which can be confusing.
- Instance state (mob spawns, items) is per-instance, increasing total memory footprint.

---

## 18) HMAC-signed Redis bus envelopes

Decision: Sign all Redis pub/sub bus messages with HMAC-SHA256 using a shared secret, and drop messages with invalid signatures.

Why:
- In a multi-process deployment sharing a Redis instance, unsigned messages could allow event injection from unauthorized processes.
- HMAC is computationally cheap and provides message integrity without encryption overhead.
- The shared secret is validated as non-blank at startup when the Redis bus is enabled.

Tradeoff:
- All processes must share the same secret, which is an operational requirement (but standard for shared infrastructure).
- HMAC adds a small per-message overhead (~microseconds), negligible compared to Redis pub/sub latency.
