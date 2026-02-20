# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> The full engineering playbook is in `AGENTS.md`. This file summarizes the most important points for quick orientation.

## Commands

```bash
./gradlew run            # Start server (telnet :4000, web :8080)
./gradlew demo           # Start server + auto-launch browser demo
./gradlew test           # Run full test suite
./gradlew ktlintCheck    # Lint (Kotlin official style)
./gradlew ktlintCheck test  # CI parity check
```

Run a single test class:
```bash
./gradlew test --tests "dev.ambon.engine.commands.CommandParserTest"
```

On Windows use `.\gradlew.bat` instead of `./gradlew`.

## Architecture

AmbonMUD is a single-process, tick-based MUD server (Kotlin/JVM). The core design separates gameplay from transport:

```
Transports (telnet / WebSocket)
    │  decode raw I/O into InboundEvent, render OutboundEvent
    ▼
GameEngine  (single-threaded coroutine dispatcher, 100ms tick)
    │  CommandRouter, CombatSystem, MobSystem, RegenSystem,
    │  Scheduler, PlayerProgression, Registries
    ▼
OutboundRouter  (per-session queues, backpressure, prompt coalescing)
    │  AnsiRenderer / PlainRenderer
    ▼
Sessions
```

**Critical contracts:**
- Engine communicates only via `InboundEvent` / `OutboundEvent` — no transport code in engine, no gameplay code in transport.
- `GameEngine` is single-threaded. Never call blocking I/O inside engine systems. Use the injected `Clock` instead of wall-clock calls.
- `RoomId` must be namespaced: `<zone>:<room>`.
- Player name: 2–16 chars, alnum/underscore, cannot start with a digit.
- Password: non-blank, max 72 chars (BCrypt limit).
- YAML player files use atomic writes — preserve this in any persistence changes.

## Key Locations

| What | Where |
|------|-------|
| Entry point / wiring | `src/main/kotlin/dev/ambon/Main.kt`, `MudServer.kt` |
| Config schema + defaults | `src/main/kotlin/dev/ambon/config/AppConfig.kt`, `src/main/resources/application.yaml` |
| Game engine + subsystems | `src/main/kotlin/dev/ambon/engine/` |
| Command parsing + routing | `engine/commands/CommandParser.kt`, `CommandRouter.kt` |
| Transport adapters | `src/main/kotlin/dev/ambon/transport/` |
| World YAML content | `src/main/resources/world/` |
| World loader + validation | `src/main/kotlin/dev/ambon/domain/world/load/WorldLoader.kt` |
| World YAML format spec | `docs/world-zone-yaml-spec.md` |
| Persistence | `src/main/kotlin/dev/ambon/persistence/` |
| Tests | `src/test/kotlin/` (fixtures: `src/test/resources/world/`) |
| Web demo client (static) | `src/main/resources/web/` |
| Runtime player saves | `data/players/` (git-ignored, do not commit) |

## Change Playbooks (summary)

- **New command:** parse in `CommandParser.kt` → implement in `CommandRouter.kt` → add tests.
- **Combat/mob/item:** edit `CombatSystem`, `MobSystem`, `MobRegistry`, `ItemRegistry`; preserve `max*PerTick` caps.
- **World content only:** edit YAML in `src/main/resources/world/`; no code change needed.
- **Config:** update `AppConfig.kt` and `application.yaml` together; keep `validated()` strict.
- **Persistence:** work through the `PlayerRepository` interface; maintain case-insensitive lookup and atomic writes.

## Testing

Use `MutableClock` and `InMemoryPlayerRepository` for deterministic unit tests. Run `ktlintCheck test` before finalizing any change.

## Known Quirks

None currently.
