# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> The full engineering playbook is in `AGENTS.md`. This file summarizes the most important points for quick orientation.

## Agent Directives

- **Do not launch planning agents for tasks. Write plans directly.**
- **Avoid re-reading files you've already examined in this session.**
- **Prefer acting over gathering more context. If you've read the relevant module, start working.**

## Cloud/Remote Mode

In cloud/remote mode, GitHub API tools (gh CLI, curl to GitHub API) do not work for creating PRs or interacting with GitHub issues. Instead, just push the code to the branch and notify the user — they will create the PR themselves.

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

Run tests matching a pattern:
```bash
./gradlew test --tests "*CommandRouter*"
```

Override any config value at runtime with `-Pconfig.<key>=<value>`:
```bash
./gradlew run -Pconfig.ambonMUD.logging.level=DEBUG
./gradlew run -Pconfig.ambonMUD.logging.packageLevels.dev.ambon.transport=DEBUG
./gradlew run -Pconfig.ambonMUD.server.telnetPort=5000
./gradlew run -Pconfig.ambonMUD.persistence.backend=POSTGRES  # connection defaults match docker compose
```

Multi-instance local testing (start engines first, then gateways):
```bash
./gradlew runEngine1     # ENGINE mode, gRPC :9091
./gradlew runEngine2     # ENGINE mode, gRPC :9092
./gradlew runGateway1    # GATEWAY mode, telnet :4000, web :8080
./gradlew runGateway2    # GATEWAY mode, telnet :4001, web :8081
```

On Windows use `.\gradlew.bat` instead of `./gradlew`.

## Architecture

AmbonMUD is a Kotlin MUD server with a tick-based event loop, telnet + WebSocket transports (with GMCP structured data), YAML world loading, class-based character progression with spell/ability and status-effect systems, shop/economy, NPC behavior trees, dialogue trees, quests, achievements, group play, and a layered persistence stack with selectable YAML or PostgreSQL backends and optional Redis caching/pub-sub.

### Deployment Modes

Three deployment modes (set via `ambonMUD.mode`):
- **`STANDALONE`** (default): single-process, all components in-process.
- **`ENGINE`**: GameEngine + persistence + gRPC server; gateways connect remotely.
- **`GATEWAY`**: transports + gRPC client; game logic runs on a remote engine.

### Layered Architecture

```
Transports (telnet / WebSocket)
    │  decode raw I/O into InboundEvent, render OutboundEvent
    ▼
InboundBus / OutboundBus  (interface layer; Local* impls in single-process mode)
    │                      (Redis* impls for multi-process pub/sub)
    │                      (Grpc* impls for gateway ↔ engine gRPC streaming)
    ▼
GameEngine  (single-threaded coroutine dispatcher, 100ms tick)
    │  CommandRouter, CombatSystem, AbilitySystem, StatusEffectSystem,
    │  MobSystem, BehaviorTreeSystem, RegenSystem, DialogueSystem,
    │  QuestSystem, AchievementSystem, GroupSystem, Scheduler,
    │  PlayerProgression, GmcpEmitter, Registries
    ▼
OutboundRouter  (per-session queues, backpressure, prompt coalescing)
    │  AnsiRenderer / PlainRenderer
    ▼
Sessions
```

### Critical Contracts

- **Engine boundary:** Engine communicates only via `InboundEvent` / `OutboundEvent` — no transport code in engine, no gameplay code in transport.
- **Single-threaded engine:** `GameEngine` runs on a dedicated single-thread `engineDispatcher`. Never call blocking I/O inside engine systems. Use the injected `Clock` instead of wall-clock calls.
- **RoomId format:** Must be namespaced as `<zone>:<room>`.
- **Player name:** 2–16 chars, alnum/underscore, cannot start with a digit.
- **Password:** non-blank, max 72 chars (BCrypt limit).
- **Persistence chain:** `WriteCoalescingPlayerRepository` → `RedisCachingPlayerRepository` (if enabled) → `YamlPlayerRepository` or `PostgresPlayerRepository` (selected via `ambonMUD.persistence.backend`). YAML uses atomic writes; preserve this in any persistence changes.
- **Event bus:** `InboundBus`/`OutboundBus` are interfaces — never pass raw `Channel` references to engine code. All bus impls (Local, Redis, gRPC) are interchangeable.
- **Outbound routing:** `OutboundRouter` applies backpressure (slow clients may be disconnected). Consecutive prompts coalesce. `Close` sends final text then closes via callback.

### Event Types

**InboundEvent** (sealed interface in `engine/events/InboundEvent.kt`):
- `Connected(sessionId, defaultAnsiEnabled)` — new session
- `Disconnected(sessionId, reason)` — session lost
- `LineReceived(sessionId, line)` — player typed a line
- `GmcpReceived(sessionId, gmcpPackage, jsonData)` — GMCP data from client

**OutboundEvent** (sealed interface in `engine/events/OutboundEvent.kt`):
- `SendText`, `SendInfo`, `SendError` — text to player
- `SendPrompt` — prompt line
- `ShowLoginScreen`, `SetAnsi`, `ClearScreen`, `ShowAnsiDemo` — UI control
- `Close(sessionId, reason)` — disconnect session
- `SessionRedirect` — cross-engine handoff
- `GmcpData(sessionId, gmcpPackage, jsonData)` — GMCP telemetry to client

### Command System

`CommandParser.kt` transforms raw input into a sealed `Command` hierarchy. `CommandRouter.kt` dispatches each variant. Key command categories:
- **Navigation:** Move, Look, LookDir, Exits
- **Communication:** Say, Tell, Whisper, Gossip, Shout, Ooc, Pose, Emote, Gtell
- **Combat:** Kill, Flee, Cast, Dispel
- **Items:** Get, Drop, Use, Give, Wear, Remove, Inventory, Equipment
- **Progression:** Score, Spells, Effects, Balance, QuestLog, QuestInfo, QuestAccept, QuestAbandon, AchievementList, TitleSet, TitleClear
- **NPCs:** Talk, DialogueChoice, ShopList, Buy, Sell
- **Groups:** GroupCmd (Invite, Accept, Leave, Kick, List)
- **Sharding:** Phase (instance switching)
- **Staff:** Goto, Transfer, Spawn, Smite, Kick, Shutdown
- **Utility:** Help, Clear, Colors, Who, AnsiOn, AnsiOff
- **Meta:** Invalid (with usage hint), Unknown, Noop (empty input)

### Persistence Model

`PlayerRecord` (in `persistence/PlayerRecord.kt`) is the persistence DTO. Key fields: `id` (PlayerId), `name`, `roomId`, `level`, `xpTotal`, `hp`/`maxHp`, `mana`/`maxMana`, `strength`/`dexterity`/`constitution`/`intelligence`/`wisdom`/`charisma`, `race`, `playerClass`, `gold`, `isStaff`, `activeQuests`, `completedQuestIds`, `unlockedAchievementIds`, `achievementProgress`, `activeTitle`, `passwordHash`, `ansiEnabled`.

`PlayerState` (in `engine/PlayerState.kt`) is the runtime in-memory version, maintained by the engine and periodically flushed back to `PlayerRecord` via the repository chain.

`PlayerRepository` interface: `findByName(name)`, `findById(id)`, `create(request)`, `save(record)`. All lookups are case-insensitive.

### Wiring / Dependency Injection

`MudServer.kt` is the composition root for STANDALONE/ENGINE modes. `GatewayServer.kt` for GATEWAY mode. No DI framework — all dependencies are manually wired via constructor injection in these files. `Main.kt` dispatches to the appropriate root based on `config.mode`.

## Project Map

### Source Files (~197 Kotlin files in main, ~86 test files)

| Package | Purpose | Key Files |
|---------|---------|-----------|
| `dev.ambon` | Entry point, wiring | `Main.kt` (bootstrap), `MudServer.kt` (25K, composition root), `CoroutineExtensions.kt` |
| `dev.ambon.config` | Configuration | `AppConfig.kt` (33K, full schema + `validated()`), `AppConfigLoader.kt` |
| `dev.ambon.engine` | Core game logic | `GameEngine.kt` (38K, tick loop), `PlayerRegistry.kt`, `PlayerState.kt`, `CombatSystem.kt` (25K), `MobSystem.kt`, `MobRegistry.kt`, `RegenSystem.kt`, `PlayerProgression.kt`, `GmcpEmitter.kt` (15K), `GroupSystem.kt` (12K), `QuestSystem.kt` (11K), `AchievementSystem.kt` (10K), `ThreatTable.kt`, `ShopRegistry.kt`, `EngineUtil.kt` |
| `dev.ambon.engine.commands` | Command parsing/routing | `CommandParser.kt` (17K, sealed Command hierarchy), `CommandRouter.kt` (dispatch infrastructure only); handlers in `handlers/` subpackage: `NavigationHandler`, `CommunicationHandler`, `CombatHandler`, `ItemHandler`, `WorldFeaturesHandler`, `ProgressionHandler`, `DialogueQuestHandler`, `ShopHandler`, `GroupHandler`, `UiHandler`, `AdminHandler`, `HandlerHelpers` |
| `dev.ambon.engine.abilities` | Ability/spell system | `AbilitySystem.kt` (16K), `AbilityRegistry.kt`, `AbilityRegistryLoader.kt`, `AbilityDefinition.kt` |
| `dev.ambon.engine.status` | Status effects | `StatusEffectSystem.kt` (13K), `StatusEffectRegistry.kt`, `StatusEffectRegistryLoader.kt`, `StatusEffectDefinition.kt`, `ActiveEffect.kt` |
| `dev.ambon.engine.behavior` | Mob behavior trees | `BehaviorTreeSystem.kt`, `BtNode.kt`, `BtResult.kt`, `BtContext.kt`, `BehaviorTemplates.kt`, `MobBehaviorMemory.kt`; nodes/conditions/actions subdirs |
| `dev.ambon.engine.dialogue` | NPC dialogue | `DialogueSystem.kt`, `DialogueTree.kt` |
| `dev.ambon.engine.items` | Item management | `ItemRegistry.kt` (17K), `ItemMatching.kt` |
| `dev.ambon.engine.scheduler` | Delayed actions | `Scheduler.kt` |
| `dev.ambon.engine.events` | Event types | `InboundEvent.kt`, `OutboundEvent.kt` |
| `dev.ambon.bus` | Event bus abstractions | `InboundBus.kt`, `OutboundBus.kt` (interfaces); `Local*Bus.kt`, `Redis*Bus.kt`, `Grpc*Bus.kt` (impls); `DepthTrackingChannel.kt` |
| `dev.ambon.domain` | Domain model | `PlayerClass.kt`, `Race.kt`; sub-packages: `ids/`, `items/`, `mob/`, `quest/`, `achievement/`, `world/` |
| `dev.ambon.domain.world` | World model | `Room.kt`, `Direction.kt`, `World.kt`, `WorldFactory.kt`, `ShopDefinition.kt`, `MobSpawn.kt`, `ItemSpawn.kt`, `MobDrop.kt` |
| `dev.ambon.domain.world.data` | YAML DTOs | `WorldFile.kt`, `RoomFile.kt`, `MobFile.kt`, `ItemFile.kt`, `ShopFile.kt`, `MobDropFile.kt`, `BehaviorFile.kt`, `DialogueNodeFile.kt`, `QuestFile.kt` |
| `dev.ambon.domain.world.load` | World loading | `WorldLoader.kt` (30K, YAML parsing + validation) |
| `dev.ambon.persistence` | Player persistence | `PlayerRepository.kt` (interface), `PlayerRecord.kt`, `PlayerDto.kt`, `PlayerCreationRequest.kt`; `WriteCoalescingPlayerRepository.kt`, `RedisCachingPlayerRepository.kt`, `YamlPlayerRepository.kt`, `PostgresPlayerRepository.kt`, `PlayersTable.kt`, `DatabaseManager.kt`, `PersistenceWorker.kt`, `StringCache.kt` |
| `dev.ambon.transport` | Network I/O | `Transport.kt`, `BlockingSocketTransport.kt` (telnet), `KtorWebSocketTransport.kt` (13K, WebSocket), `NetworkSession.kt` (12K), `OutboundRouter.kt` (9K), `AnsiRenderer.kt`, `PlainRenderer.kt`, `TelnetLineDecoder.kt` (6K) |
| `dev.ambon.grpc` | gRPC engine/gateway | `EngineGrpcServer.kt`, `EngineServer.kt` (21K), `EngineServiceImpl.kt`, `GrpcOutboundDispatcher.kt`, `ProtoMapper.kt` (8.6K), `OutboundEventPlane.kt` |
| `dev.ambon.gateway` | Gateway-mode root | `GatewayServer.kt` (23K), `SessionRouter.kt` |
| `dev.ambon.sharding` | Zone sharding | `ZoneRegistry.kt`, `StaticZoneRegistry.kt`, `RedisZoneRegistry.kt`, `InterEngineBus.kt`, `LocalInterEngineBus.kt`, `RedisInterEngineBus.kt`, `InterEngineMessage.kt`, `HandoffManager.kt` (16K), `PlayerLocationIndex.kt`, `RedisPlayerLocationIndex.kt`, `InstanceSelector.kt`, `LoadBalancedInstanceSelector.kt`, `InstanceScaler.kt`, `ThresholdInstanceScaler.kt`, `ScaleDecisionPublisher.kt`, `ZoneInstance.kt` |
| `dev.ambon.session` | Session IDs | `SessionIdFactory.kt`, `AtomicSessionIdFactory.kt`, `SnowflakeSessionIdFactory.kt`, `GatewayIdLeaseManager.kt` |
| `dev.ambon.redis` | Redis infra | `RedisConnectionManager.kt`, `JsonSupport.kt` |
| `dev.ambon.metrics` | Observability | `GameMetrics.kt` (13K), `MetricsHttpServer.kt` |
| `dev.ambon.admin` | Admin dashboard | `AdminHttpServer.kt` (34K) |
| `dev.ambon.ui.login` | Login screen | `LoginScreen.kt`, `LoginScreenLoader.kt`, `LoginScreenRenderer.kt` |

### Resources

| What | Where |
|------|-------|
| Default config | `src/main/resources/application.yaml` |
| Multi-instance profiles | `src/main/resources/application-{engine1,engine2,gw1,gw2}.yaml` |
| World zones (9 YAML files) | `src/main/resources/world/` (ambon_hub, tutorial_glade, demo_ruins, noecker_resume, 4 training zones, achievements) |
| Login banner + styles | `src/main/resources/login.txt`, `src/main/resources/login.styles.yaml` |
| Flyway migrations | `src/main/resources/db/migration/` (V1–V7: players table through achievements) |
| Proto definitions | `src/main/proto/ambonmud/v1/engine_service.proto`, `events.proto` |
| Web demo client (static) | `src/main/resources/web/` |
| World YAML format spec | `docs/WORLD_YAML_SPEC.md` |
| Runtime player saves | `data/players/` (git-ignored, do not commit) |

### Tests (~78 test files)

| Area | Files | Key Tests |
|------|-------|-----------|
| Engine core | `GameEngineIntegrationTest`, `GameEngineLoginFlowTest` (29K), `GameEngineAnsiBehaviorTest` | Full login/play/quit flows, ANSI |
| Commands | `CommandParserTest` (15K), `CommandRouterTest` (19K), `CommandRouterAdminTest` (23K), `CommandRouterItemsTest` (21K), `CommandRouterShopTest`, `CommandRouterBroadcastTest`, `CommandRouterScoreTest`, `CrossEngineCommandsTest`, `NamesTellGossipTest`, `SocialChannelCommandsTest`, `PhaseCommandTest` | Every command category |
| Combat/mobs | `CombatSystemTest` (42K), `MobRespawnTest`, `MobRegistryTest`, `MobSystemTest`, `ThreatTableTest` | Damage, threat, death, respawn |
| Abilities/status | `AbilitySystemTest` (20K), `StatusEffectSystemTest` (23K) | Cast, cooldown, DOT/HOT/stun/root |
| Behavior trees | `BehaviorTreeSystemTest` (18K), `BehaviorYamlParsingTest` | Mob AI, YAML-driven behaviors |
| Dialogue/quests/achievements | `DialogueSystemTest` (14K), `QuestSystemTest` (13K), `AchievementSystemTest` (20K) | NPC conversations, quest tracking |
| Groups | `GroupSystemTest` (15K) | Party invite/leave/kick, XP sharing |
| Persistence | `YamlPlayerRepositoryTest`, `PostgresPlayerRepositoryTest`, `RedisCachingPlayerRepositoryTest`, `WriteCoalescingPlayerRepositoryTest`, `PersistenceWorkerTest` | Atomic writes, H2 Postgres mode, cache layers |
| Bus | `LocalInboundBusTest`, `LocalOutboundBusTest`, `RedisInboundBusTest`, `RedisOutboundBusTest`, `GrpcInboundBusTest`, `GrpcOutboundBusTest` | All bus variants |
| Transport | `OutboundRouterTest` (11K), `OutboundRouterAnsiControlsTest`, `OutboundRouterPromptCoalescingTest`, `AnsiRendererTest`, `PlainRendererTest`, `TelnetLineDecoderTest`, `KtorWebSocketTransportTest` | Backpressure, ANSI, protocol |
| Sharding | `HandoffManagerTest` (18K), `StaticZoneRegistryTest`, `LoadBalancedInstanceSelectorTest`, `ThresholdInstanceScalerTest`, `InterEngineMessageSerializationTest`, `LocalInterEngineBusTest` | Zone handoff, scaling |
| gRPC | `EngineGrpcServerTest`, `EngineServiceImplTest`, `GatewayEngineIntegrationTest` (16K), `GrpcOutboundDispatcherTest`, `ProtoMapperTest` | End-to-end gateway-engine |
| Other | `AppConfigLoaderTest`, `GameMetricsTest`, `MetricsHttpServerTest`, `AdminModuleTest` (15K), `GmcpEmitterTest` (20K), `PlayerProgressionTest`, `SchedulerTest`, `SchedulerDropsTest`, `LoginScreenLoaderTest`, `LoginScreenRendererTest`, `WorldLoaderTest` (26K), `SessionIdFactory` tests | Config, metrics, admin, world loading |

### Test Utilities

| Utility | Location | Purpose |
|---------|----------|---------|
| `MutableClock` | `src/test/kotlin/dev/ambon/test/MutableClock.kt` | Deterministic time via `advance(ms)` and `set(ms)` — use instead of wall-clock |
| `InMemoryPlayerRepository` | `src/test/kotlin/dev/ambon/persistence/InMemoryPlayerRepository.kt` | Fast in-memory `PlayerRepository` with case-insensitive lookup and `clear()` |
| `EngineTestHelpers` | `src/test/kotlin/dev/ambon/test/EngineTestHelpers.kt` | `LocalOutboundBus.drainAll()`, `PlayerRegistry.loginOrFail()` |
| `RedisBusTestFixtures` | `src/test/kotlin/dev/ambon/bus/RedisBusTestFixtures.kt` | `FakePublisher`, `FakeSubscriberSetup` for testing Redis bus without Redis |
| World fixtures | `src/test/resources/world/` | `test_world.yaml`, `ok_*.yaml` (valid), `bad_*.yaml` (40+ invalid YAML for error testing), `mz_*.yaml` / `split_zone_*.yaml` (multi-zone) |

### Infrastructure

| What | Where |
|------|-------|
| Docker Compose | `docker-compose.yml` (Prometheus, Grafana, Redis, Postgres) |
| CI workflow | `.github/workflows/ci.yml` (Java 21, `ktlintCheck test`) |
| CodeQL analysis | `.github/workflows/codeql.yml` (weekly + on main) |

## Build Configuration

- **Kotlin:** 2.3.10, JVM toolchain 21
- **Gradle:** wrapper with `-Xmx2g`, daemon idle timeout 10 minutes
- **Key dependencies:** Ktor 3.4.0 (WebSocket server), kotlinx-coroutines 1.10.2, Jackson 2.21.0 (YAML), Hoplite 2.9.0 (config), Logback 1.5.18, BCrypt 0.4, Micrometer 1.16.3 (Prometheus), Lettuce 7.4.0 (Redis), Exposed 0.58.0 (SQL), HikariCP 6.2.1, PostgreSQL 42.7.5, Flyway 11.3.0, gRPC 1.79.0, Protobuf 3.25.5
- **Test deps:** JUnit Jupiter 6.0.3, kotlinx-coroutines-test, H2 2.3.232 (Postgres compat mode), Ktor test host, gRPC testing/in-process

## Change Playbooks

### New command
1. Add variant to `Command` sealed interface in `CommandParser.kt`.
2. Add parse logic in `CommandParser.parse()` (use `matchPrefix()` for prefix matching, `requiredArg()` if arguments needed).
3. Implement handler in the appropriate `handlers/` file under `CommandRouter.kt` (e.g. `NavigationHandler`, `CombatHandler`, `ItemHandler`, etc.). For a brand-new category, add a new handler file and wire it from `CommandRouter`.
4. Preserve prompt behavior for success/failure paths (`outbound.send(SendPrompt(...))`).
5. Add parser tests in `CommandParserTest` and router tests in `CommandRouterTest` (or a dedicated test file).

### Staff command
Same as above, plus gate with `if (!playerState.isStaff)` check in `AdminHandler.kt`. Test in `CommandRouterAdminTest`.

### Combat/mob/item
Edit `CombatSystem`, `MobSystem`, `MobRegistry`, `ItemRegistry`; preserve `max*PerTick` caps to avoid tick starvation.

### Ability/spell
Add definition in `application.yaml` under `engine.abilities.definitions`. If new effect type needed, update `AbilityRegistryLoader`. Class restriction via `classRestriction` field. Test in `AbilitySystemTest`.

### Status effect
Add definition in `application.yaml` under `engine.statusEffects.definitions`. If new effect mechanic, update `EffectType` enum and tick branches in `StatusEffectSystem`. Keep `CombatSystem` call sites in sync (`getPlayerStatMods`, `hasMobEffect(STUN)`, `absorbPlayerDamage`).

### World content only
Edit YAML in `src/main/resources/world/`; no code change needed. See `docs/WORLD_YAML_SPEC.md` for schema.

### Config
Update `AppConfig.kt` and `application.yaml` together; keep `validated()` strict with `require()` checks.

### Persistence (adding a field to PlayerRecord)
1. Add field with default to `PlayerRecord` data class.
2. Verify YAML round-trip (Jackson handles new defaults for existing files).
3. Verify Redis JSON round-trip (`RedisCachingPlayerRepositoryTest`).
4. For Postgres: add a new Flyway migration (`V<N>__description.sql` in `src/main/resources/db/migration/`), update `PlayersTable.kt` column and `PostgresPlayerRepository.kt` mapping (`toPlayerRecord()`, `insert`, `upsert`).
5. If the field is runtime state, update `PlayerState` and the `PlayerRegistry` sync logic.

### Bus/Redis/gRPC (adding a new event variant)
1. Add variant to `InboundEvent` or `OutboundEvent`.
2. Add type discriminator + data class in `RedisInboundBus`/`RedisOutboundBus`.
3. Add proto message in `src/main/proto/ambonmud/v1/events.proto`.
4. Add mapping in `ProtoMapper.kt`.
5. Test with both `LocalInboundBus` and mock bus.

### Sharding / inter-engine messaging
When adding new `InterEngineMessage` variants, update serialization in `InterEngineMessage.kt` and add tests. Update both `LocalInterEngineBus` and `RedisInterEngineBus`.

### GMCP
Update `GmcpEmitter.kt` and the v3 web client's GMCP handler at `web-v3/src/gmcp/applyGmcpPackage.ts`. Telnet negotiation is in `NetworkSession.kt` (WILL GMCP) and `TelnetLineDecoder.kt`.

## Kotlin Style (ktlint)

This project uses **ktlint 1.5.0** with `kotlin.code.style=official`. Rule overrides live in the root `.editorconfig`. The following rules are **disabled** to reduce formatting friction:

- `multiline-expression-wrapping` — no forced newline after `=` for multiline RHS.
- `string-template-indent` — disabled as a dependency of the above.
- `chain-method-continuation` — short chains can stay on fewer lines.
- `function-signature` — parameters don't have to be one-per-line.

All other standard rules remain enforced. The most important ones to know:

1. **Trailing commas (REQUIRED)** — Every multiline parameter list, argument list, and collection literal must end with a trailing comma:
   ```kotlin
   class Foo(
       val bar: String,
       val baz: Int,      // <- trailing comma
   )

   doSomething(
       first = 1,
       second = 2,        // <- trailing comma
   )
   ```

2. **No wildcard imports** — Always use explicit imports, never `import foo.bar.*`.

3. **Multiline `when` entries** — Arrow and body on the same line when short; braces for multi-statement bodies.

4. **Spacing** — Single space after `if`/`for`/`when`/`while` and around operators/colons. No space before commas or inside parentheses.

5. **Blank lines** — No blank lines at the start or end of a class/function body. No blank lines inside parameter lists.

6. **No max line length enforced** — Keep lines reasonable but don't force-wrap short expressions.

7. **Multiline string templates** — Closing `"""` must be on its own line, with `.trimIndent()` on the same line.

8. **No blank line before first declaration in a class** — The first property/function starts immediately after the opening brace (or after the constructor closing parenthesis).

## Testing Patterns

### General
- Run `ktlintCheck test` before finalizing any change.
- Add tests for every behavioral change; this codebase treats tests as design constraints.
- Prefer focused test runs while iterating (`--tests "ClassName"`), then run full suite before finalizing.

### Deterministic time
Always use `MutableClock` for code that depends on time. Never use `System.currentTimeMillis()` or similar in production code — use the injected `Clock`.

### Async/coroutine tests
```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
fun `test name`() = runTest {
    val engine = GameEngine(inbound, outbound, ...)
    val engineJob = launch { engine.run() }

    inbound.send(InboundEvent.Connected(sid))
    runCurrent()           // let engine process events
    advanceTimeBy(100)     // advance virtual time
    runCurrent()

    val events = outbound.drainAll()  // collect all outbound events
    // assertions...
    engineJob.cancel()
}
```

### Database tests
Postgres tests use H2 in PostgreSQL-compatibility mode (`jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`). No Docker required.

### Test isolation
- `@TempDir` for file-based tests (YAML persistence).
- `@BeforeEach` for database cleanup.
- `InMemoryPlayerRepository.clear()` between tests.

## Cloud / CI Environment

When running in Claude Code cloud sessions (claude.ai/code), be aware of these constraints:

- **JVM Toolchain version:** The `jvmToolchain()` in `build.gradle.kts` must match the JDK installed in the build environment. Cloud sessions provide JDK 21; the toolchain is currently set to `jvmToolchain(21)`. If it ever drifts, update it in `build.gradle.kts` — the Foojay resolver cannot auto-provision through the cloud egress proxy.
- **Egress proxy:** All outbound HTTP/HTTPS traffic goes through a proxy injected via `JAVA_TOOL_OPTIONS`. Gradle dependency resolution works through this proxy. Do not add `mavenLocal()` or assume direct internet access.
- **GitHub CLI (`gh`) is not available:** The `gh` command is not installed in cloud sessions. Use `git` commands directly for all repository operations (push, fetch, branch). Git remotes are wired through a local proxy that handles authentication automatically.
- **No hardcoded timing in tests:** Cloud environments have variable CPU scheduling latency. Never use short `delay()` calls (e.g. `delay(50)`) to synchronize with async coroutines launched on `Dispatchers.Default`. Instead, use polling loops with `withTimeout` and a generous timeout (e.g. 2 seconds), or use proper coroutine synchronization primitives (channels, `CompletableDeferred`). For negative tests (asserting nothing arrives), use at least `delay(200)`.
- **First build is slow:** The Gradle wrapper downloads the distribution and all dependencies on first run. Subsequent builds use the cached daemon.
- **Test timeout:** Individual tests timeout after 30 seconds (`junit-platform.properties`). Entire suite times out after 5 minutes (Gradle backstop).

## Known Quirks

- **Compiler warnings in tests:** Several test files produce "No cast needed" warnings (e.g. `InterEngineMessageHandlingTest.kt`, `CrossEngineCommandsTest.kt`). These are harmless and do not affect test results.
- **Largest files:** `GameEngine.kt` (38K, tick loop) and `WorldLoader.kt` (30K) are the largest remaining files. Command handlers are split across `handlers/` subpackage — navigate by handler class name. `CommandRouter.kt` itself is now just 62 lines of dispatch infrastructure.
- **Generated sources:** Protobuf/gRPC generates code under `build/generated/`. A child `.editorconfig` suppresses ktlint for these files.
- **Gradle daemon idle timeout:** Set to 10 minutes (`gradle.properties`) to reclaim stale daemons faster than the 3-hour default.
- **Staff access:** Granted by editing `isStaff: true` in the player YAML file (or `is_staff` column in Postgres) — there is no in-game promotion command.
- **Metrics package:** Uses `io.micrometer.prometheusmetrics` (not the deprecated `io.micrometer.prometheus`).
