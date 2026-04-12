# CLAUDE.md

> Full engineering playbook: `AGENTS.md`. This file covers what you need to avoid mistakes.

## Agent Directives

- **Do not launch planning agents for tasks. Write plans directly.**
- **Avoid re-reading files you've already examined in this session.**
- **Prefer acting over gathering more context. If you've read the relevant module, start working.**

## Commands

```bash
./gradlew run            # Start server (telnet :4000, web :8080)
./gradlew demo           # Start server + auto-launch browser demo
./gradlew ktlintCheck    # Lint — run before every PR
./gradlew test           # Full test suite — run before committing
./gradlew buildWeb       # Build web client (requires bun) — auto-runs with run/demo
```

```bash
./gradlew test --tests "dev.ambon.engine.commands.CommandParserTest"  # single class
./gradlew test --tests "*CommandRouter*"                              # pattern
```

Override config: `-Pconfig.<key>=<value>` (e.g. `-Pconfig.ambonmud.persistence.backend=POSTGRES`).

Multi-instance: `runEngine1`/`runEngine2` (gRPC :9091/:9092), `runGateway1`/`runGateway2` (telnet :4000/:4001).

On Windows use `.\gradlew.bat`.

## Architecture

Kotlin MUD server: tick-based engine, telnet + WebSocket transports (GMCP), YAML world loading, class-based progression with trainers/multi-classing, abilities/status effects, shops, behavior trees, dialogue, quests, achievements, groups, guilds, crafting/enchanting, housing, dungeons, pets, factions, auction house, trading, PvP dueling, bank, day/night/weather/events, leaderboards, prestige, currencies, lottery, daily/global quests, puzzles, stylist.

Bundled world content is the single **Auringold Academy** tutorial zone plus `achievements.yaml` and `sprites.yaml` under `src/main/resources/world/`. The production demo instance fetches the full Auringold world (20+ zones) from Cloudflare R2 at boot — see `docs/DEPLOYMENT.md` § "Remote world & config overlay".

### Deployment Modes (set via `ambonMUD.mode`)

- **STANDALONE** (default): single-process. **ENGINE**: game logic + gRPC server. **GATEWAY**: transports + gRPC client.

### Layered Architecture

```
Transports (telnet / WebSocket) → InboundBus/OutboundBus → GameEngine (100ms tick) → OutboundRouter → Sessions
```

Bus implementations: `Local*` (single-process), `Redis*` (multi-process), `Grpc*` (gateway↔engine).

### Critical Contracts

- **Engine boundary:** Engine communicates only via `InboundEvent`/`OutboundEvent` — no transport code in engine, no gameplay in transport.
- **Single-threaded engine:** Runs on dedicated `engineDispatcher`. Never call blocking I/O inside engine. Use injected `Clock`, not wall-clock.
- **RoomId format:** `<zone>:<room>`.
- **Player name:** 2–16 chars, alnum/underscore, no leading digit. **Password:** non-blank, max 72 (BCrypt).
- **Persistence chain:** `WriteCoalescingPlayerRepository` → `RedisCachingPlayerRepository` (optional) → `YamlPlayerRepository` or `PostgresPlayerRepository`. YAML uses atomic writes.
- **Event bus:** `InboundBus`/`OutboundBus` are interfaces — never pass raw `Channel` to engine code.
- **Outbound routing:** `OutboundRouter` applies backpressure. Consecutive prompts coalesce. `Close` sends final text then closes via callback.

### Key Source Locations

| Area | Key files |
|------|-----------|
| Entry/wiring | `Main.kt`, `MudServer.kt` (composition root), `GatewayServer.kt` |
| Config | `AppConfig.kt` (schema + `validated()`), `application.yaml` |
| Engine | `GameEngine.kt` (tick loop), `PlayerState.kt`, `PlayerRegistry.kt` |
| Commands | `CommandParser.kt` (141 variants, sealed hierarchy), `CommandRouter.kt` (dispatch), `handlers/` subpackage (37 handler files) |
| Events | `InboundEvent.kt`, `OutboundEvent.kt` |
| Persistence | `PlayerRecord.kt` (DTO), `PlayerRepository.kt` (interface), `PlayersTable.kt`, `GuildRepository.kt` |
| Transport | `KtorWebSocketTransport.kt`, `NetworkSession.kt`, `OutboundRouter.kt`, `TelnetLineDecoder.kt` |
| GMCP | `GmcpEmitter.kt` (server→client), `web-v3/src/gmcp/applyGmcpPackage.ts` (client) |
| World | `WorldLoader.kt`, zone YAMLs in `src/main/resources/world/` |
| Web client | `web-v3/` (React + PixiJS), built to `src/main/resources/web-v3/` |
| Migrations | `src/main/resources/db/migration/` (V1–V34) |
| Proto | `src/main/proto/ambonmud/v1/` |

### Test Utilities

- `MutableClock` — deterministic time via `advance(ms)`/`set(ms)`
- `InMemoryPlayerRepository` — fast in-memory with `clear()`
- `EngineTestHelpers` — `drainAll()`, `loginOrFail()`
- World fixtures in `src/test/resources/world/` (`ok_*.yaml` valid, `bad_*.yaml` invalid)

## Change Playbooks

### New command
1. Add variant to `Command` sealed interface in `CommandParser.kt`.
2. Add parse logic in `CommandParser.parse()` (`matchPrefix()`, `requiredArg()`).
3. Add handler in appropriate `handlers/` file. New category → new handler file + wire from `CommandRouter`.
4. Send `SendPrompt` on success/failure paths.
5. Tests in `CommandParserTest` + `CommandRouterTest` (or dedicated file). Staff commands: gate with `isStaff` in `AdminHandler.kt`, test in `CommandRouterAdminTest`.

### Persistence (adding a field)
1. Add field with default to `PlayerRecord`.
2. Add column to `PlayersTable.kt`, update `readRecord()`/`writeRecord()`.
3. Flyway migration in `db/migration/`.
4. Update `PlayerState` + `toPlayerState()`/`toPlayerRecord()` if runtime state.
5. `PersistenceFieldCoverageTest` catches mapping omissions.

### Bus/gRPC (new event variant)
1. Add to `InboundEvent` or `OutboundEvent`.
2. Type discriminator in `Redis*Bus`.
3. Proto message in `events.proto`.
4. Mapping in `ProtoMapper.kt`.

### GMCP
Update `GmcpEmitter.kt` + `web-v3/src/gmcp/applyGmcpPackage.ts`. **New GMCP package family:** register in WebSocket auto-opt-in at `KtorWebSocketTransport.kt` (~line 208, `Core.Supports.Set`). Without this, GMCP is silently dropped. Prefix matching: `"Quest 1"` covers `Quest.List`, `Quest.Update`, etc.

### General system pattern
Most systems follow: `*System.kt` (logic) + `*Handler.kt` (commands) + config in `application.yaml` + test in `*SystemTest`. World features use zone YAML flags (e.g. `bank: true`, `tavern: true`, `pvpEnabled: true`).

### Config
Update `AppConfig.kt` and `application.yaml` together; keep `validated()` strict.

### World content only
Edit YAML in `src/main/resources/world/`. See `docs/WORLD_YAML_SPEC.md`.

## Kotlin Style (ktlint)

ktlint 1.5.0, `kotlin.code.style=official`. Overrides in `.editorconfig`.

**Disabled rules:** `multiline-expression-wrapping`, `string-template-indent`, `chain-method-continuation`, `function-signature`.

**Key enforced rules:**
1. **Trailing commas required** on all multiline parameter/argument lists and collection literals.
2. **No wildcard imports** — always explicit.
3. No blank lines at start/end of class/function bodies or inside parameter lists.
4. Closing `"""` on its own line with `.trimIndent()`.

## Testing Patterns

- Run `./gradlew ktlintCheck test integrationTest` before committing.
- **Deterministic time:** Always use `MutableClock`. Never `System.currentTimeMillis()` in production code.
- **Coroutine tests:** `runTest { }` with `runCurrent()` / `advanceTimeBy()`. Collect via `outbound.drainAll()`.
- **DB tests:** H2 in PostgreSQL mode (`MODE=PostgreSQL`). No Docker needed.
- **Isolation:** `@TempDir` for file tests, `@BeforeEach` for DB cleanup, `InMemoryPlayerRepository.clear()`.

## Cloud / CI Environment

- JVM toolchain 21 must match cloud JDK. Foojay can't provision through egress proxy.
- `gh` CLI available in cloud mode.
- **No hardcoded timing:** Use polling with `withTimeout` (2s+), not `delay(50)`. Negative tests: `delay(200)` minimum.
- Test timeout: 30s per test, 5min suite.

## Design System

**Surreal Gentle Magic** — cozy fantasy, glass-morphism, jewel tones. See `.impeccable.md` (principles), `docs/STYLE_GUIDE.md` (tokens/components), `web-v3/src/styles.css` (source of truth).

- Never hardcode colors — use CSS variables. Dark-first. WCAG AA minimum.
- `web-v3/src/canvas/` for PixiJS rendering. Built assets are gitignored.
- Validate: `bun run lint` + `./gradlew demo`.

## Known Quirks

- **Largest files:** `GmcpEmitter.kt` (~3168 lines), `GameEngine.kt` (~2610 lines), `AppConfig.kt` (~2445 lines), `AdminHttpServer.kt`, `WorldLoader.kt`. `CommandRouter.kt` is thin dispatch (~100 lines); all gameplay lives in the 37 files under `handlers/`.
- **Generated sources:** Protobuf under `build/generated/`, ktlint-suppressed via child `.editorconfig`.
- **Staff access:** Set `isStaff: true` in player YAML or `is_staff` in Postgres — no in-game command.
- **Metrics:** Uses `io.micrometer.prometheusmetrics` (not deprecated `io.micrometer.prometheus`).
