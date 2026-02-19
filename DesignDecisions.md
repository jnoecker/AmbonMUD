# Design Decisions

This project is intentionally built like a production backend service, even though it's "just a MUD."
The goal is to keep the codebase easy to extend (world content, commands, transports, persistence) without premature complexity.

## Table of Contents

1. Event-driven engine (not request/response)
2. Transport as a replaceable adapter (telnet first)
3. Backpressure handled explicitly
4. ANSI support is semantic (not sprinkled escapes)
5. World content is data (not code)
6. Namespaced IDs for multi-zone worlds
7. Persistence is phased (YAML first, DB later)
8. Dependency injection without a framework
9. Tests as design constraints

---

## 1) Event-driven engine (not request/response)

Decision: The server runs as a long-lived event loop (tick-based) consuming inbound events and emitting outbound events.

Why:
- A MUD is a stateful real-time system, not a stateless HTTP API.
- The event model provides a clean boundary between transport and game logic.
- A tick loop makes it easy to add world ticks (regen, mob AI, scheduled events) without re-architecting.

Tradeoff:
- You need to be disciplined about blocking calls. Network and disk I/O must not stall the loop.

---

## 2) Transport as a replaceable adapter (telnet first)

Decision: Start with telnet for maximum simplicity and compatibility, while keeping the core engine unaware of telnet specifics.

Why:
- Telnet gives immediate feedback and makes iteration fast.
- The engine speaks in semantic events (SendText, SendPrompt, Close, ClearScreen) instead of raw socket writes.
- This keeps a clean path to future transports (WebSockets, SSH) without rewriting game logic.

Tradeoff:
- Telnet negotiation and line decoding are "old-world" concerns, but they stay isolated to transport.

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

## 7) Persistence is phased (YAML first, DB later)

Decision: Use YAML player persistence in Phase 1, with a repository interface to preserve swapability.

Why:
- YAML persistence is inspectable, easy to debug, and requires no infrastructure.
- Repository abstraction allows clean migration to SQLite/Postgres later.
- Atomic write strategy prevents corruption on crash.

Tradeoff:
- Directory scans for lookup are acceptable for MVP, but will not scale forever (intentionally deferred).

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
