# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> The full engineering playbook is in `AGENTS.md`. This file summarizes the most important points for quick orientation.

## Agent Directives

- **Do not launch planning agents for tasks. Write plans directly.**
- **Avoid re-reading files you've already examined in this session.**
- **Prefer acting over gathering more context. If you've read the relevant module, start working.**

## Cloud/Remote Mode

The `gh` CLI is available in cloud/remote mode (verified Feb 2026). Use it normally for creating PRs, viewing issues, and other GitHub operations.

## Commands

```bash
./gradlew run            # Start server (telnet :4000, web :8080)
./gradlew demo           # Start server + auto-launch browser demo
./gradlew ktlintCheck    # Lint (Kotlin official style) — run before every PR
./gradlew test           # Full test suite — run before committing
./gradlew buildWeb       # Build web client (requires bun) — auto-runs with `run`/`demo`
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
./gradlew run -Pconfig.ambonmud.logging.level=DEBUG
./gradlew run -Pconfig.ambonmud.logging.packageLevels.dev.ambon.transport=DEBUG
./gradlew run -Pconfig.ambonmud.server.telnetPort=5000
./gradlew run -Pconfig.ambonmud.persistence.backend=POSTGRES  # connection defaults match docker compose
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

AmbonMUD is a Kotlin MUD server with a tick-based event loop, telnet + WebSocket transports (with GMCP structured data), YAML world loading, class-based character progression with trainer-based ability learning/multi-classing, spell/ability and status-effect systems, shop/economy, NPC behavior trees, dialogue trees, quests, achievements, group play, guilds, crafting/enchanting, player housing, procedural dungeons, pets, factions, auction house, player trading, PvP dueling, bank system, day/night/weather/seasonal events, leaderboards, and a layered persistence stack with selectable YAML or PostgreSQL backends and optional Redis caching/pub-sub.

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
    │  QuestSystem, AchievementSystem, GroupSystem, GuildSystem,
    │  CraftingSystem, FriendsSystem, HousingSystem, PetSystem,
    │  ReputationSystem, AuctionSystem, TradeSystem, DuelSystem,
    │  WeatherSystem, WorldTimeSystem, WorldEventSystem,
    │  LeaderboardSystem, TrainerRegistry, Scheduler,
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
- **Progression:** Score, Spells, Effects, Balance, QuestLog, QuestInfo, QuestAccept, QuestAbandon, AchievementList, TitleSet, TitleClear, SpriteList, SpriteSet, SpriteDefault, Leaderboard, HallOfFame
- **NPCs:** Talk, DialogueChoice, ShopList, Buy, Sell
- **Groups:** GroupCmd (Invite, Accept, Leave, Kick, List)
- **Guilds:** Guild (Create, Disband, Invite, Accept, Leave, Kick, Promote, Demote, Motd, Roster, Info), Gchat
- **Friends:** Friend (List, Add, Remove)
- **Mail:** Mail (List, Read, Delete, Send, Abort)
- **Crafting:** Gather, Craft, Recipes, Specialize, Enchant, Enchantments
- **Economy:** Auction (List, Sell, Buy, Cancel), Bank (Balance, Deposit, Withdraw), Trade (Initiate, Offer, Accept, Cancel)
- **Social:** Duel (Challenge, Accept, Decline), Reputation
- **Training:** Train (List, Learn, Unlock)
- **Pets:** Pet (Status, Dismiss, Name)
- **World:** Time
- **Dungeons:** DungeonEnter, DungeonLeave
- **Housing:** House (Info, Expand, Furnish, Describe, Invite, Kick, Lock, Unlock)
- **Sharding:** Phase (instance switching)
- **Staff:** Goto, Transfer, Spawn, Smite, Kick, Shutdown
- **Utility:** Help, Clear, Colors, Who, AnsiOn, AnsiOff
- **Meta:** Invalid (with usage hint), Unknown, Noop (empty input)

### Persistence Model

`PlayerRecord` (in `persistence/PlayerRecord.kt`) is the persistence DTO. Key fields: `id` (PlayerId), `name`, `roomId`, `level`, `xpTotal`, `hp`/`maxHp`, `mana`/`maxMana`, `race`, `playerClass`, `gold`, `isStaff`, `activeQuests`, `completedQuestIds`, `unlockedAchievementIds`, `achievementProgress`, `activeTitle`, `passwordHash`, `ansiEnabled`, `guildId`, `recallRoom`, `friends`, `craftingSkills`, `discoveredRecipes`, `craftingSpecialization`, `mail`, `gender`, `stats` (JSON map), `bankGold`, `bankItems`, `factionStandings` (JSON map), `learnedAbilityIds`, `unlockedClasses`, `skillPoints`.

`PlayerState` (in `engine/PlayerState.kt`) is the runtime in-memory version, maintained by the engine and periodically flushed back to `PlayerRecord` via the repository chain.

`PlayerRepository` interface: `findByName(name)`, `findById(id)`, `create(request)`, `save(record)`. All lookups are case-insensitive.

`GuildRepository` interface (`persistence/GuildRepository.kt`): guild CRUD with `YamlGuildRepository` and `PostgresGuildRepository` implementations. `GuildsTable.kt` for Exposed schema.

### Wiring / Dependency Injection

`MudServer.kt` is the composition root for STANDALONE/ENGINE modes. `GatewayServer.kt` for GATEWAY mode. No DI framework — all dependencies are manually wired via constructor injection in these files. `Main.kt` dispatches to the appropriate root based on `config.mode`.

## Project Map

### Source Files (~314 Kotlin files in main, ~137 test files)

| Package | Purpose | Key Files |
|---------|---------|-----------|
| `dev.ambon` | Entry point, wiring | `Main.kt` (bootstrap), `MudServer.kt` (21K, composition root), `CoroutineExtensions.kt` |
| `dev.ambon.config` | Configuration | `AppConfig.kt` (84K, full schema + `validated()`), `AppConfigLoader.kt` |
| `dev.ambon.engine` | Core game logic | `GameEngine.kt` (87K, tick loop), `PlayerRegistry.kt`, `PlayerState.kt`, `CombatSystem.kt` (29K), `MobSystem.kt`, `MobRegistry.kt`, `RegenSystem.kt`, `PlayerProgression.kt`, `GmcpEmitter.kt` (77K), `GroupSystem.kt` (13K), `QuestSystem.kt` (12K), `AchievementSystem.kt` (13K), `GuildSystem.kt`, `CraftingSystem.kt`, `FriendsSystem.kt`, `HousingSystem.kt`, `PetSystem.kt`, `ReputationSystem.kt`, `AuctionSystem.kt`, `TradeSystem.kt`, `DuelSystem.kt`, `WeatherSystem.kt`, `WorldTimeSystem.kt`, `WorldEventSystem.kt`, `LeaderboardSystem.kt`, `TrainerRegistry.kt`, `ThreatTable.kt`, `ShopRegistry.kt`, `SpriteRegistry.kt`, `SpriteLoader.kt`, `EngineUtil.kt` |
| `dev.ambon.engine.commands` | Command parsing/routing | `CommandParser.kt` (47K, sealed Command hierarchy), `CommandRouter.kt` (dispatch infrastructure only); handlers in `handlers/` subpackage: `NavigationHandler`, `CommunicationHandler`, `CombatHandler`, `ItemHandler`, `WorldFeaturesHandler`, `ProgressionHandler`, `DialogueQuestHandler`, `ShopHandler`, `GroupHandler`, `GuildHandler`, `CraftingHandler`, `EnchantHandler`, `FriendsHandler`, `MailHandler`, `SpriteHandler`, `TrainerHandler`, `PetHandler`, `AuctionHandler`, `BankHandler`, `TradeHandler`, `DuelHandler`, `ReputationHandler`, `LeaderboardHandler`, `DungeonHandler`, `HousingHandler`, `WorldInfoHandler`, `UiHandler`, `AdminHandler`, `HandlerHelpers` |
| `dev.ambon.engine.abilities` | Ability/spell system | `AbilitySystem.kt` (29K), `AbilityRegistry.kt`, `AbilityRegistryLoader.kt`, `AbilityDefinition.kt` |
| `dev.ambon.engine.status` | Status effects | `StatusEffectSystem.kt` (16K), `StatusEffectRegistry.kt`, `StatusEffectRegistryLoader.kt`, `StatusEffectDefinition.kt`, `ActiveEffect.kt` |
| `dev.ambon.engine.behavior` | Mob behavior trees | `BehaviorTreeSystem.kt`, `BtNode.kt`, `BtResult.kt`, `BtContext.kt`, `BehaviorTemplates.kt`, `MobBehaviorMemory.kt`; nodes/conditions/actions subdirs |
| `dev.ambon.engine.dialogue` | NPC dialogue | `DialogueSystem.kt`, `DialogueTree.kt` |
| `dev.ambon.engine.dungeon` | Procedural dungeons | `DungeonManager.kt`, `DungeonGenerator.kt`, `DungeonRegistry.kt`, `DungeonLayout.kt`, `DungeonInstance.kt` |
| `dev.ambon.domain.dungeon` | Dungeon domain model | `DungeonTemplateDef.kt`, `DungeonDifficulty.kt`, `CraftingQuality.kt` |
| `dev.ambon.engine.items` | Item management | `ItemRegistry.kt` (20K), `ItemMatching.kt` |
| `dev.ambon.domain.sprite` | Sprite domain model | `SpriteDefinition.kt` (SpriteDefinition, SpriteVariant, SpriteCategory, SpriteUnlockCondition) |
| `dev.ambon.engine.scheduler` | Delayed actions | `Scheduler.kt` |
| `dev.ambon.engine.events` | Event types | `InboundEvent.kt`, `OutboundEvent.kt` |
| `dev.ambon.bus` | Event bus abstractions | `InboundBus.kt`, `OutboundBus.kt` (interfaces); `Local*Bus.kt`, `Redis*Bus.kt`, `Grpc*Bus.kt` (impls); `DepthTrackingChannel.kt` |
| `dev.ambon.domain` | Domain model | `PlayerClass.kt`, `Race.kt`; sub-packages: `ids/`, `items/`, `mob/`, `quest/`, `achievement/`, `world/` |
| `dev.ambon.domain.world` | World model | `Room.kt`, `Direction.kt`, `World.kt`, `WorldFactory.kt`, `ShopDefinition.kt`, `MobSpawn.kt`, `ItemSpawn.kt`, `MobDrop.kt` |
| `dev.ambon.domain.world.data` | YAML DTOs | `WorldFile.kt`, `RoomFile.kt`, `MobFile.kt`, `ItemFile.kt`, `ShopFile.kt`, `MobDropFile.kt`, `BehaviorFile.kt`, `DialogueNodeFile.kt`, `QuestFile.kt`, `DungeonFile.kt` |
| `dev.ambon.domain.world.load` | World loading | `WorldLoader.kt` (61K, YAML parsing + validation) |
| `dev.ambon.persistence` | Player + guild persistence | `PlayerRepository.kt` (interface), `PlayerRecord.kt`, `PlayerCreationRequest.kt`; `WriteCoalescingPlayerRepository.kt`, `RedisCachingPlayerRepository.kt`, `YamlPlayerRepository.kt`, `PostgresPlayerRepository.kt`, `PlayersTable.kt`, `DatabaseManager.kt`, `PersistenceWorker.kt`, `StringCache.kt`; `GuildRepository.kt` (interface), `YamlGuildRepository.kt`, `PostgresGuildRepository.kt`, `GuildsTable.kt` |
| `dev.ambon.transport` | Network I/O | `Transport.kt`, `BlockingSocketTransport.kt` (telnet), `KtorWebSocketTransport.kt` (14K, WebSocket), `NetworkSession.kt` (12K), `OutboundRouter.kt` (10K), `AnsiRenderer.kt`, `PlainRenderer.kt`, `TelnetLineDecoder.kt` (6K) |
| `dev.ambon.grpc` | gRPC engine/gateway | `EngineGrpcServer.kt`, `EngineServer.kt` (10K), `EngineServiceImpl.kt`, `GrpcOutboundDispatcher.kt`, `ProtoMapper.kt` (8K), `OutboundEventPlane.kt` |
| `dev.ambon.gateway` | Gateway-mode root | `GatewayServer.kt` (23K), `SessionRouter.kt` |
| `dev.ambon.sharding` | Zone sharding | `ZoneRegistry.kt`, `StaticZoneRegistry.kt`, `RedisZoneRegistry.kt`, `InterEngineBus.kt`, `LocalInterEngineBus.kt`, `RedisInterEngineBus.kt`, `InterEngineMessage.kt`, `HandoffManager.kt` (12K), `PlayerLocationIndex.kt`, `RedisPlayerLocationIndex.kt`, `InstanceSelector.kt`, `LoadBalancedInstanceSelector.kt`, `InstanceScaler.kt`, `ThresholdInstanceScaler.kt`, `ScaleDecisionPublisher.kt`, `ZoneInstance.kt` |
| `dev.ambon.session` | Session IDs | `SessionIdFactory.kt`, `AtomicSessionIdFactory.kt`, `SnowflakeSessionIdFactory.kt`, `GatewayIdLeaseManager.kt` |
| `dev.ambon.redis` | Redis infra | `RedisConnectionManager.kt`, `JsonSupport.kt` |
| `dev.ambon.metrics` | Observability | `GameMetrics.kt` (18K), `MetricsHttpServer.kt` |
| `dev.ambon.admin` | Admin dashboard | `AdminHttpServer.kt` (63K) |
| `dev.ambon.ui.login` | Login screen | `LoginScreen.kt`, `LoginScreenLoader.kt`, `LoginScreenRenderer.kt` |

### Resources

| What | Where |
|------|-------|
| Default config | `src/main/resources/application.yaml` |
| Multi-instance profiles | `src/main/resources/application-{engine1,engine2,gw1,gw2}.yaml` |
| World zones (23 YAML files) | `src/main/resources/world/` — 20 zones: crossroads_path, thornhaven_city, thornwood_forest, farmer_fields, cobblestone_road, highland_trails, old_mines, marsh_of_fog, goblin_warrens, dark_barrows, sea_cliffs, sunken_temple, ruined_fortress, shadowmere_fen, thornhaven_sewers, haunted_manor, barrens_wastes, frost_caverns, celestial_peak, dungeon_of_echoes; plus achievements, player_sprites, sprites |
| Login banner + styles | `src/main/resources/login.txt`, `src/main/resources/login.styles.yaml` |
| Flyway migrations | `src/main/resources/db/migration/` (V1–V26: players table through guilds, crafting, friends, mail, sprites, stats JSON, discovered recipes, faction standings, bank, leaderboards, skill points/multiclass) |
| Proto definitions | `src/main/proto/ambonmud/v1/engine_service.proto`, `events.proto` |
| Web terminal client (static) | `src/main/resources/web-terminal/` (served at `/terminal`) |
| V4 canvas client (React + PixiJS) | `web-v3/` (built to `src/main/resources/web-v3/` by `./gradlew buildWeb`) |
| Demo placeholder sprites | `src/main/resources/world/images/demo/` (default PNGs) |
| World YAML format spec | `docs/WORLD_YAML_SPEC.md` |
| Dungeon template reference | `docs/DUNGEON_TEMPLATE_REFERENCE.md` |
| Runtime player saves | `data/players/` (git-ignored, do not commit) |

### Tests (~137 test files)

| Area | Files | Key Tests |
|------|-------|-----------|
| Engine core | `GameEngineIntegrationTest`, `GameEngineLoginFlowTest` (36K), `GameEngineAnsiBehaviorTest` | Full login/play/quit flows, ANSI |
| Commands | `CommandParserTest` (24K), `CommandRouterTest` (20K), `CommandRouterAdminTest` (20K), `CommandRouterItemsTest` (16K), `CommandRouterShopTest`, `CommandRouterBroadcastTest`, `CommandRouterScoreTest`, `CrossEngineCommandsTest`, `NamesTellGossipTest`, `SocialChannelCommandsTest`, `PhaseCommandTest` | Every command category |
| Combat/mobs | `CombatSystemTest` (32K), `MobRespawnTest`, `MobRegistryTest`, `MobSystemTest`, `ThreatTableTest` | Damage, threat, death, respawn |
| Abilities/status | `AbilitySystemTest` (19K), `StatusEffectSystemTest` (21K) | Cast, cooldown, DOT/HOT/stun/root |
| Behavior trees | `BehaviorTreeSystemTest` (23K), `BehaviorYamlParsingTest` | Mob AI, YAML-driven behaviors |
| Dialogue/quests/achievements | `DialogueSystemTest`, `QuestSystemTest` (12K), `AchievementSystemTest` (23K) | NPC conversations, quest tracking |
| Groups | `GroupSystemTest` (15K) | Party invite/leave/kick, XP sharing |
| Guilds | `GuildSystemTest` | Guild create/disband/invite/promote/demote, MOTD, roster |
| Crafting | `CraftingSystemTest` | Gather, craft, recipe validation, specialization, quality, discovery |
| Dungeons | `DungeonGeneratorTest`, `DungeonManagerTest` | Layout generation, instance lifecycle, scaling, boss detection |
| Friends/Mail | `FriendsSystemTest`, `MailHandlerTest` | Friend list management, mail send/read/delete |
| Persistence | `YamlPlayerRepositoryTest`, `PostgresPlayerRepositoryTest`, `RedisCachingPlayerRepositoryTest`, `WriteCoalescingPlayerRepositoryTest`, `PersistenceWorkerTest` | Atomic writes, H2 Postgres mode, cache layers |
| Bus | `LocalInboundBusTest`, `LocalOutboundBusTest`, `RedisInboundBusTest`, `RedisOutboundBusTest`, `GrpcInboundBusTest`, `GrpcOutboundBusTest` | All bus variants |
| Transport | `OutboundRouterTest` (11K), `OutboundRouterAnsiControlsTest`, `OutboundRouterPromptCoalescingTest`, `AnsiRendererTest`, `PlainRendererTest`, `TelnetLineDecoderTest`, `KtorWebSocketTransportTest` | Backpressure, ANSI, protocol |
| Sharding | `HandoffManagerTest` (18K), `StaticZoneRegistryTest`, `LoadBalancedInstanceSelectorTest`, `ThresholdInstanceScalerTest`, `InterEngineMessageSerializationTest`, `LocalInterEngineBusTest` | Zone handoff, scaling |
| gRPC | `EngineGrpcServerTest`, `EngineServiceImplTest`, `GatewayEngineIntegrationTest` (16K), `GrpcOutboundDispatcherTest`, `ProtoMapperTest` | End-to-end gateway-engine |
| Other | `AppConfigLoaderTest`, `GameMetricsTest`, `MetricsHttpServerTest`, `AdminModuleTest` (10K), `GmcpEmitterTest` (57K), `PlayerProgressionTest`, `SchedulerTest`, `SchedulerDropsTest`, `LoginScreenLoaderTest`, `LoginScreenRendererTest`, `WorldLoaderTest` (33K), `SessionIdFactory` tests | Config, metrics, admin, world loading |

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
| CI workflow | `.github/workflows/ci.yml` (Java 21, `ktlintCheck test integrationTest`) |
| CodeQL analysis | `.github/workflows/codeql.yml` (weekly + on main) |

## Build Configuration

- **Kotlin:** 2.3.10, JVM toolchain 21
- **Gradle:** wrapper with `-Xmx2g`, daemon idle timeout 10 minutes
- **Key dependencies:** Ktor 3.4.2 (WebSocket server), kotlinx-coroutines 1.10.2, Jackson 2.21.1 (YAML), Hoplite 2.9.0 (config), Logback 1.5.32, BCrypt 0.4, Micrometer 1.16.4 (Prometheus), Lettuce 7.5.0.RELEASE (Redis), Exposed 0.58.0 (SQL), HikariCP 7.0.2, PostgreSQL 42.7.10, Flyway 12.2.0, gRPC 1.80.0, Protobuf 4.34.1
- **Test deps:** JUnit Jupiter 6.0.3, kotlinx-coroutines-test, H2 2.4.240 (Postgres compat mode), Ktor test host, gRPC testing/in-process

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
2. Add column to `PlayersTable.kt` and update `readRecord()` / `writeRecord()`.
3. For Postgres: add a new Flyway migration (`V<N>__description.sql` in `src/main/resources/db/migration/`).
4. If the field is runtime state, update `PlayerState` and the `toPlayerState()` / `toPlayerRecord()` extensions in `PlayerState.kt`.
5. Run `PersistenceFieldCoverageTest` — it will catch any mapping omission across YAML, Redis JSON, Postgres, and PlayerState round-trips.

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

**When adding a new GMCP package family** (e.g. `Quest`, `Guild`), you must also register it in the WebSocket auto-opt-in list at `KtorWebSocketTransport.kt` line ~208 (`Core.Supports.Set`). The `GmcpEmitter.emit()` method checks `supportsPackage(sessionId, supportCheck)` before sending — if the package isn't in the client's supported set, the GMCP is silently dropped. Prefix matching applies: registering `"Quest 1"` covers `Quest.List`, `Quest.Update`, `Quest.Available`, etc.

### Ability/spell image
Add `image` field to `AbilityDefinitionConfig` (AppConfig.kt), `AbilityDefinition`, `AbilityRegistryLoader`, and `GmcpEmitter.CharSkillPayload`. Client reads it from `Char.Skills` GMCP into `SkillSummary.image`.

### Guild system changes
Edit `GuildSystem.kt` for logic, `GuildHandler.kt` for commands. Guild persistence: `GuildRepository` interface with `YamlGuildRepository` and `PostgresGuildRepository` impls. `GuildsTable.kt` for Exposed schema. `PlayerRecord.guildId` links player to guild. `PlayerState` has `guildId`, `guildRank`, `guildTag`. Test in `GuildSystemTest`.

### Crafting system changes
Edit `CraftingSystem.kt` for logic, `CraftingHandler.kt` for commands (Gather, Craft, Recipes). `PlayerRecord.craftingSkills` stores per-player skill levels. Recipe definitions live in `application.yaml` under the crafting config section. Test in `CraftingSystemTest`.

### Sprite system changes
`SpriteRegistry` holds all sprite definitions. Tier and staff sprites are auto-generated in `MudServer.kt` via `SpriteLoader.generateTierSprites()` / `generateStaffSprites()`. Achievement sprites are defined in `src/main/resources/world/sprites.yaml` and loaded via `SpriteLoader.loadFromResource()`. Commands in `SpriteHandler.kt` (SpriteList, SpriteSet, SpriteDefault). `PlayerRecord.activeSprite` / `PlayerState.activeSprite` store the player's selection (null = auto). `GmcpEmitter.resolveSprite()` uses the registry; `sendCharSprites()` emits `Char.Sprites` GMCP. Tier names configured in `AppConfig.ImagesConfig.spriteTierNames`. See `docs/ARCANUM_SPRITE_INSTRUCTIONS.md` for image naming conventions. Test in `SpriteRegistryTest`, `SpriteLoaderTest`, `SpriteCommandTest`.

### Trainer system changes
Edit `TrainerRegistry.kt` for trainer loading, `TrainerHandler.kt` for commands (Train List/Learn/Unlock). Trainer definitions live in zone YAML under `trainers:` section — no code change needed to add trainers. `PlayerRecord.learnedAbilityIds`, `unlockedClasses`, and `skillPoints` store progression. Skill point interval configured via `engine.skillPoints.interval`. Multi-class unlock requires `engine.multiclass.minLevel` and `engine.multiclass.goldCost`. `GmcpEmitter` emits `Trainer.List` and `Char.Classes` GMCP packages. Hot-reload via `HotReloadManager`. See `docs/TRAINER_SYSTEM.md`.

### Pet system changes
Edit `PetSystem.kt` for logic, `PetHandler.kt` for commands (Pet Status/Dismiss/Name). Pet templates defined in `application.yaml` under `engine.pets.definitions`. Referenced by abilities using `SUMMON_PET` effect type with `petTemplateKey`. Pets are session-only (no persistence). See `docs/RECENT_YAML_CHANGES.md` for YAML schema.

### Faction/reputation changes
Edit `ReputationSystem.kt` for logic, `ReputationHandler.kt` for display. Faction definitions in `application.yaml` under `engine.factions.definitions`. Mob faction affiliation via `faction:` field in zone YAML mob definitions. `PlayerRecord.factionStandings` persisted as JSON map; Flyway V23. Quest reward integration in `QuestSystem.kt`. See `docs/RECENT_YAML_CHANGES.md` for YAML schema.

### Auction house changes
Edit `AuctionSystem.kt` for logic, `AuctionHandler.kt` for commands. Listings persist to `data/auction_listings.json`. No YAML config section — runtime-only. GMCP package: `Auction.*`. See `docs/RECENT_YAML_CHANGES.md`.

### Enchanting system changes
Edit enchanting logic in the crafting subsystem, `EnchantHandler.kt` for commands. Enchantment definitions in `application.yaml` under `engine.enchanting.definitions`. Enchanting is a crafting skill requiring `enchanting_table` station. Item `enchantments` field persisted on `ItemInstance`. `GMCP Crafting.Result` includes `type: "enchant"`. See `docs/RECENT_YAML_CHANGES.md` for YAML schema.

### Bank system changes
Edit bank logic in the bank handler, `BankHandler.kt` for commands. Rooms with `bank: true` in zone YAML enable bank commands. `PlayerRecord.bankGold` and `bankItems` persisted; Flyway V24. `Char.Bank` GMCP package. See `docs/RECENT_YAML_CHANGES.md` for YAML schema.

### Day/night, weather, seasonal events
Edit `WorldTimeSystem.kt` (clock/period), `WeatherSystem.kt` (transitions), `WorldEventSystem.kt` (date-triggered events). Config in `application.yaml` under `engine.worldTime`, `engine.weather`, `engine.worldEvents.definitions`. GMCP: `World.Time`, `World.Weather`, `World.Events`. `time` command handled in `WorldInfoHandler.kt`. See `docs/RECENT_YAML_CHANGES.md` for YAML schema.

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
- Run `ktlintCheck` before opening a PR or finalizing any change. The pre-commit hook enforces this automatically.
- **Run the full test suite before committing** (`./gradlew ktlintCheck test integrationTest`). Catching failures locally is faster than waiting for CI.
- Add tests for every behavioral change; this codebase treats tests as design constraints.
- If CI tests fail after pushing, fix the failures promptly.

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
- **GitHub CLI (`gh`) is available** in cloud/remote mode (verified Feb 2026). Use it normally for creating PRs, viewing issues, and other GitHub operations.
- **No hardcoded timing in tests:** Cloud environments have variable CPU scheduling latency. Never use short `delay()` calls (e.g. `delay(50)`) to synchronize with async coroutines launched on `Dispatchers.Default`. Instead, use polling loops with `withTimeout` and a generous timeout (e.g. 2 seconds), or use proper coroutine synchronization primitives (channels, `CompletableDeferred`). For negative tests (asserting nothing arrives), use at least `delay(200)`.
- **First build is slow:** The Gradle wrapper downloads the distribution and all dependencies on first run. Subsequent builds use the cached daemon.
- **Test timeout:** Individual tests timeout after 30 seconds (`junit-platform.properties`). Entire suite times out after 5 minutes (Gradle backstop).

## Design System

AmbonMUD's visual identity is **Surreal Gentle Magic** — cozy fantasy with glass-morphism depth, jewel-toned colors, and ambient magical details. Brand personality: *surreal, magical, adventure*.

- **Design context & principles:** [`.impeccable.md`](.impeccable.md) — users, brand personality, aesthetic direction, 5 design principles
- **Full design system:** [`docs/STYLE_GUIDE.md`](docs/STYLE_GUIDE.md) — color tokens, typography, motion, component states, validation checklist
- **Design tokens (source of truth):** `web-v3/src/styles.css` — all CSS custom properties live here
- **Creator tool style:** [`docs/ARCANUM_STYLE_GUIDE.md`](docs/ARCANUM_STYLE_GUIDE.md) — separate aesthetic for the Ambon Arcanum

### Web client style changes
- Edit design tokens in `web-v3/src/styles.css` (single file, ~5K lines)
- Component styles are in the same file, organized by component class name
- Canvas rendering (PixiJS scenes, particle effects) is in `web-v3/src/canvas/`
- Run `./gradlew buildWeb` (or `bun run build` from `web-v3/`) to write assets to `src/main/resources/web-v3/`
- Built assets are gitignored — never commit them. They're built on demand by Gradle, CI, and Docker.
- Validate with `bun run lint` and visual inspection via `./gradlew demo`

### Key design rules
- Never hardcode colors — always use CSS variables
- Dark-first (light mode planned but not yet implemented)
- WCAG AA contrast minimum for all text
- Respect `prefers-reduced-motion` for all animations
- Cozy over cool: rounded corners, soft shadows, gentle gradients

## Known Quirks

- **Compiler warnings in tests:** Several test files produce "No cast needed" warnings (e.g. `InterEngineMessageHandlingTest.kt`, `CrossEngineCommandsTest.kt`). These are harmless and do not affect test results.
- **Largest files:** `GameEngine.kt` (87K, tick loop), `AppConfig.kt` (84K), `GmcpEmitter.kt` (77K), `AdminHttpServer.kt` (63K), and `WorldLoader.kt` (61K) are the largest files. Command handlers are split across `handlers/` subpackage — navigate by handler class name. `CommandRouter.kt` itself is just 109 lines of dispatch infrastructure.
- **Generated sources:** Protobuf/gRPC generates code under `build/generated/`. A child `.editorconfig` suppresses ktlint for these files.
- **Gradle daemon idle timeout:** Set to 10 minutes (`gradle.properties`) to reclaim stale daemons faster than the 3-hour default.
- **Staff access:** Granted by editing `isStaff: true` in the player YAML file (or `is_staff` column in Postgres) — there is no in-game promotion command.
- **Metrics package:** Uses `io.micrometer.prometheusmetrics` (not the deprecated `io.micrometer.prometheus`).
