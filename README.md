# AmbonMUD (Kotlin)

A hobby re-implementation of a late-90s ROM 2.4 / QuickMUD-style server in Kotlin.
Current focus: a clean, testable core architecture (engine loop + transport boundary) that can evolve into
full ROM area compatibility and additional clients (e.g. WebSockets) later.

## Features (so far)
- Telnet server (TCP) with line-based input
- Transport ↔ engine decoupling via events
- Single-threaded game engine loop (avoids shared-state concurrency bugs)
- Telnet negotiation bytes (IAC) stripped to prevent mojibake
- Outbound backpressure protection: disconnect slow clients (with prompt coalescing)
- Unit + integration tests

## Quick Start

### Run
```bash
./gradlew run
```

### Connect:

```bash
telnet localhost 4000
```
### Test
```bash
./gradlew test
```

# Architecture
## High level

- Transport layer reads/writes sockets and converts bytes/lines to events

- Engine owns all mutable game state and runs in a single coroutine context

- Communication is via channels:

- InboundEvent (connected / line received / disconnected)

- OutboundEvent (send text / close)


### Why single-threaded engine?

All world state mutations happen on one dispatcher/thread. This eliminates race conditions without locks.

### Backpressure policy

Each session has a bounded outbound queue. If a client can't keep up:

- Prompts are coalesced/dropped under pressure

- Non-prompt output overflow triggers disconnect to protect the server

# Roadmap

- ANSI color support (transport-side rendering; engine remains plain text)

- Command router (look, movement, etc.)

- ROM area file parsing + world bootstrap

- WebSocket transport (same event contract as telnet)

- Parity harness vs original ROM behavior