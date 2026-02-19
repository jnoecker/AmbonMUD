# AGENTS.md

## Purpose
This repository is a Kotlin MUD server ("QuickMUD") with a single-process event loop, a telnet transport adapter, YAML world loading, and YAML-backed player persistence.

Use this document as the default engineering playbook when making code or content changes.

## Environment
- JDK: 17 toolchain in Gradle (`build.gradle.kts`), CI currently runs on Java 21 (`.github/workflows/ci.yml`).
- Build tool: Gradle wrapper (`gradlew`, `gradlew.bat`).
- Kotlin style: `kotlin.code.style=official` (`gradle.properties`).

## Core Commands
- Run server (Windows): `.\gradlew.bat run`
- Run server (Unix): `./gradlew run`
- Lint: `.\gradlew.bat ktlintCheck`
- Tests: `.\gradlew.bat test`
- CI parity (Unix/CI): `./gradlew ktlintCheck test`
- CI parity (Windows): `.\gradlew.bat ktlintCheck test`

The server listens on port `4000` (`src/main/kotlin/dev/ambon/Main.kt`).

## Project Map
- Bootstrap/runtime wiring: `src/main/kotlin/dev/ambon/Main.kt`, `src/main/kotlin/dev/ambon/MudServer.kt`
- Engine and gameplay: `src/main/kotlin/dev/ambon/engine`
- Transport and protocol: `src/main/kotlin/dev/ambon/transport`
- World loading and validation: `src/main/kotlin/dev/ambon/domain/world/load/WorldLoader.kt`
- World content: `src/main/resources/world`
- Persistence abstractions/impl: `src/main/kotlin/dev/ambon/persistence`
- Tests: `src/test/kotlin`, fixtures in `src/test/resources/world`
- Runtime player data (git-ignored): `data/players`

## Architecture Contracts (Do Not Break)
1. Engine vs transport boundary
- Engine communicates using semantic events only (`InboundEvent`, `OutboundEvent`).
- ANSI/control behavior remains semantic in engine (`SetAnsi`, `ClearScreen`, `ShowAnsiDemo`), with raw escape rendering in transport renderers only.

2. Engine loop model
- `GameEngine` is single-threaded via its dispatcher and tick loop.
- Keep blocking I/O out of engine systems/router.
- Use injected `Clock` for time-based logic; do not introduce direct wall-clock calls.

3. Session output semantics
- `OutboundRouter` applies backpressure (slow clients may be disconnected).
- Prompt coalescing is intentional: consecutive prompts collapse.
- `Close` sends final text then closes via callback.

4. World and ID invariants
- `RoomId` must be namespaced: `<zone>:<room>`.
- Multi-zone world loading and cross-zone exits are supported.
- Loader validates exits, start room, mob/item placement, stats, and slot/direction values.
- Item placement is exclusive: room or mob (or unplaced), never both.

5. Player and persistence invariants
- Name validation: length 2..16, alnum/underscore only, cannot start with digit.
- Password validation: non-blank, max 72 chars (BCrypt-safe).
- Case-insensitive online-name uniqueness is enforced.
- Player room/last-seen persistence must stay intact.
- Keep atomic-write behavior for YAML persistence files.

## Change Playbooks
### Commands
1. Update parse behavior in `src/main/kotlin/dev/ambon/engine/commands/CommandParser.kt`.
2. Add/adjust command variant in `src/main/kotlin/dev/ambon/engine/commands/CommandParser.kt` (`Command` sealed interface).
3. Implement behavior in `src/main/kotlin/dev/ambon/engine/commands/CommandRouter.kt`.
4. Preserve prompt behavior for success/failure paths.
5. Add/adjust parser tests and router/integration tests under `src/test/kotlin/dev/ambon/engine`.

### Combat, mobs, items
- Modify `CombatSystem`, `MobSystem`, `MobRegistry`, `ItemRegistry` carefully to preserve membership/index consistency.
- Maintain bounded per-tick processing caps (`max*PerTick`) to avoid starvation.
- Add/adjust tests for damage, equipment modifiers, death/drop flow, and move/broadcast behavior.

### World content and loader
- Content-only changes: edit YAML in `src/main/resources/world`.
- Loader/schema behavior changes: update `WorldLoader.kt` and add fixture coverage in `src/test/resources/world`.
- Keep both positive and negative validation tests in `src/test/kotlin/dev/ambon/world/load/WorldLoaderTest.kt`.

### Persistence
- Keep `PlayerRepository` as the abstraction boundary.
- Preserve case-insensitive lookup and unique-name behavior.
- Use `YamlPlayerRepositoryTest` and `@TempDir` for regression coverage.

## Testing Expectations
- Minimum verification for any meaningful change: `ktlintCheck` and `test`.
- Prefer focused test runs while iterating, then run full suite before finalizing.
- Add tests for every behavioral change; this codebase treats tests as design constraints.

## Practical Notes
- Keep protocol/network concerns in `transport`; keep gameplay/state transitions in `engine`.
- Reuse deterministic test helpers (`MutableClock`, in-memory repository) where possible.
- Do not commit runtime player save artifacts from `data/players`.
- `src/main/kotlin/dev/ambon/engine/scheduler/Schedular.kt` defines `Scheduler`; filename is currently mismatched, so avoid accidental rename unless doing an explicit refactor.
