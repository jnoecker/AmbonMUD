# Codebase Structure

**Analysis Date:** 2026-04-05

## Directory Layout

```
C:\AmbonMUD/
├── src/main/kotlin/dev/ambon/           # Kotlin application code
│   ├── Main.kt                          # Entry point, dispatch to STANDALONE/ENGINE/GATEWAY
│   ├── MudServer.kt                     # STANDALONE mode: composition root (21K)
│   ├── ServerInfrastructure.kt          # Shared setup: transports, DB, metrics, wiring
│   │
│   ├── admin/                           # Admin HTTP dashboard (63K)
│   │   └── AdminHttpServer.kt
│   │
│   ├── bus/                             # Event bus abstractions & implementations
│   │   ├── InboundBus.kt                # Interface
│   │   ├── OutboundBus.kt               # Interface
│   │   ├── LocalInboundBus.kt           # Channel-based, single-process
│   │   ├── LocalOutboundBus.kt
│   │   ├── RedisInboundBus.kt           # Redis pub-sub, multi-process
│   │   ├── RedisOutboundBus.kt
│   │   ├── GrpcInboundBus.kt            # gRPC streams, multi-process
│   │   ├── GrpcOutboundBus.kt
│   │   └── DepthTrackingChannel.kt
│   │
│   ├── config/                          # Configuration loading & schema
│   │   ├── AppConfig.kt                 # 84K: full schema with defaults
│   │   ├── AppConfigLoader.kt           # YAML→AppConfig using Hoplite
│   │   ├── DeploymentMode.kt            # STANDALONE | ENGINE | GATEWAY enum
│   │   └── [40+ config data classes]
│   │
│   ├── domain/                          # Domain model (immutable types)
│   │   ├── PlayerClass.kt               # Class definitions
│   │   ├── Race.kt                      # Race definitions
│   │   ├── StatMap.kt                   # Data-driven stats
│   │   ├── ids/
│   │   │   ├── SessionId.kt             # Session UUID
│   │   │   ├── PlayerId.kt              # Player unique ID
│   │   │   ├── RoomId.kt                # zone:room ID format
│   │   │   └── MobId.kt
│   │   ├── items/
│   │   │   ├── ItemDefinition.kt        # Weapon, armor, etc.
│   │   │   ├── ItemInstance.kt          # Instance with durability, enchantments
│   │   │   └── [item property types]
│   │   ├── mob/
│   │   │   ├── MobDefinition.kt
│   │   │   ├── MobState.kt              # Runtime mob data
│   │   │   └── [mob-related types]
│   │   ├── quest/
│   │   │   ├── QuestDef.kt              # Quest definition
│   │   │   ├── QuestState.kt            # Player quest progress
│   │   │   └── [quest objective types]
│   │   ├── achievement/
│   │   │   ├── AchievementDef.kt
│   │   │   ├── AchievementState.kt
│   │   │   └── [achievement types]
│   │   ├── crafting/
│   │   │   ├── RecipeDef.kt
│   │   │   ├── GatheringNodeDef.kt
│   │   │   └── [crafting types]
│   │   ├── world/
│   │   │   ├── World.kt                 # Complete spatial/content model
│   │   │   ├── Room.kt                  # Single location
│   │   │   ├── Direction.kt             # north, south, etc.
│   │   │   ├── RoomFeature.kt           # Door, container, lever, sign
│   │   │   ├── MobSpawn.kt              # Spawn definition (not instance)
│   │   │   ├── ItemSpawn.kt
│   │   │   ├── ShopDefinition.kt
│   │   │   ├── TrainerDefinition.kt
│   │   │   ├── WorldFactory.kt          # Factory helper
│   │   │   ├── data/                    # YAML DTO layer
│   │   │   │   ├── WorldFile.kt
│   │   │   │   ├── RoomFile.kt
│   │   │   │   ├── MobFile.kt
│   │   │   │   └── [YAML DTO classes]
│   │   │   └── load/
│   │   │       ├── WorldLoader.kt       # 61K: YAML parsing & validation
│   │   │       └── [zone-specific loaders]
│   │   ├── guild/                       # Guild data types
│   │   ├── housing/                     # Housing/player house types
│   │   ├── dungeon/                     # Dungeon template types
│   │   ├── puzzle/                      # Puzzle definition types
│   │   └── [other domain sub-packages]
│   │
│   ├── engine/                          # Game engine and systems
│   │   ├── GameEngine.kt                # 87K: main tick loop
│   │   ├── PlayerRegistry.kt            # SessionId → PlayerState map
│   │   ├── PlayerState.kt               # 150 lines: runtime player data
│   │   ├── MobRegistry.kt               # MobId → MobState map
│   │   ├── WorldStateRegistry.kt        # World feature state (doors open/closed, etc.)
│   │   ├── PlayerProgression.kt         # Level/XP/progression calcs
│   │   │
│   │   ├── events/
│   │   │   ├── InboundEvent.kt          # Sealed: Connected, Disconnected, LineReceived, GmcpReceived
│   │   │   ├── OutboundEvent.kt         # Sealed: SendText, SendInfo, SendError, SendPrompt, etc.
│   │   │   ├── EngineEventDispatcher.kt # Router for inbound→handler mapping
│   │   │   ├── InputEventHandler.kt     # Handles LineReceived → parse → CommandRouter
│   │   │   ├── LoginEventHandler.kt     # Handles auth flow
│   │   │   ├── SessionEventHandler.kt   # Handles Connected/Disconnected
│   │   │   └── [other event handlers]
│   │   │
│   │   ├── commands/
│   │   │   ├── Command.kt               # Sealed: all player commands (Move, Say, Kill, etc.)
│   │   │   ├── CommandParser.kt         # 47K: text→Command parsing
│   │   │   ├── CommandRouter.kt         # 109 lines: registry-based dispatch
│   │   │   ├── PhaseResult.kt           # For instancing/cross-zone moves
│   │   │   └── handlers/                # 35+ command handler files
│   │   │       ├── NavigationHandler.kt # move, look, exits, recall
│   │   │       ├── CombatHandler.kt     # kill, flee, cast, dispel
│   │   │       ├── ItemHandler.kt       # get, drop, use, wear, remove, inv
│   │   │       ├── CommunicationHandler.kt # say, tell, gossip, emote, etc.
│   │   │       ├── ShopHandler.kt       # buy, sell, shop list
│   │   │       ├── GroupHandler.kt      # group invite, accept, leave, kick, list
│   │   │       ├── GuildHandler.kt      # guild creation, roster, promotion, hall
│   │   │       ├── CraftingHandler.kt   # gather, craft, recipes, specialize
│   │   │       ├── BankHandler.kt       # balance, deposit, withdraw
│   │   │       ├── TradeHandler.kt      # trade request, offer, accept
│   │   │       ├── AuctionHandler.kt    # auction buy, sell, list, cancel
│   │   │       ├── DuelHandler.kt       # duel challenge, accept, decline
│   │   │       ├── MailHandler.kt       # mail list, read, send, delete
│   │   │       ├── FriendsHandler.kt    # friend list, add, remove
│   │   │       ├── TrainerHandler.kt    # train list, learn, unlock, reset
│   │   │       ├── AdminHandler.kt      # goto, spawn, smite, broadcast, etc.
│   │   │       └── [20+ more handlers]
│   │   │
│   │   ├── CombatSystem.kt              # 29K: damage calcs, threat, threat table
│   │   ├── AbilitySystem.kt             # 29K: casting, cooldowns, effects
│   │   ├── StatusEffectSystem.kt        # 16K: DOT, HOT, stun, root, debuffs
│   │   ├── MobSystem.kt                 # Mob spawning, respawn, behavior
│   │   ├── BehaviorTreeSystem.kt        # NPC AI via behavior trees
│   │   ├── DialogueSystem.kt            # NPC dialogue trees
│   │   ├── QuestSystem.kt               # 12K: quest tracking, turn-in, rewards
│   │   ├── AchievementSystem.kt         # 13K: achievement unlock, progress tracking
│   │   ├── GroupSystem.kt               # 13K: party formation, XP sharing
│   │   ├── GuildSystem.kt               # Guild create, members, ranks
│   │   ├── CraftingSystem.kt            # Gathering, crafting, recipes, quality
│   │   ├── EnchantSystem.kt             # Item enchantment
│   │   ├── DungeonManager.kt            # Procedural dungeon gen & lifecycle
│   │   ├── PetSystem.kt                 # Pet summon, dismiss, commands
│   │   ├── HousingSystem.kt             # Player house purchase, decorate, visit
│   │   ├── AuctionSystem.kt             # Auction house listings
│   │   ├── TradeSystem.kt               # Player-to-player trading
│   │   ├── DuelSystem.kt                # PvP duel system
│   │   ├── ReputationSystem.kt          # Faction standing tracking
│   │   ├── PrestigeSystem.kt            # Prestige ranks & perks
│   │   ├── CurrencySystem.kt            # Secondary currency tracking
│   │   ├── WeatherSystem.kt             # Weather transitions
│   │   ├── WorldTimeSystem.kt           # Day/night/seasons
│   │   ├── WorldEventSystem.kt          # Server-wide events (holidays, etc.)
│   │   ├── LeaderboardSystem.kt         # Leaderboard rankings
│   │   ├── LotterySystem.kt             # Lottery draws
│   │   ├── [other systems]
│   │   │
│   │   ├── abilities/
│   │   │   ├── AbilityRegistry.kt       # Id → AbilityDefinition map
│   │   │   ├── AbilityDefinition.kt     # Spell/ability metadata
│   │   │   ├── AbilityRegistryLoader.kt # Load from AppConfig
│   │   │   └── EffectType.kt            # DAMAGE, HEAL, STUN, etc.
│   │   │
│   │   ├── status/
│   │   │   ├── StatusEffectRegistry.kt
│   │   │   ├── StatusEffectDefinition.kt
│   │   │   ├── StatusEffectRegistryLoader.kt
│   │   │   └── ActiveEffect.kt
│   │   │
│   │   ├── items/
│   │   │   ├── ItemRegistry.kt          # 20K: item definitions
│   │   │   └── ItemMatching.kt          # Fuzzy match item by keyword
│   │   │
│   │   ├── behavior/
│   │   │   ├── BehaviorTreeSystem.kt
│   │   │   ├── BtNode.kt                # Behavior tree node type
│   │   │   ├── BtResult.kt              # Success | Failure | Running
│   │   │   ├── BtContext.kt             # Context passed through tree
│   │   │   ├── BehaviorTemplates.kt     # Pre-built tree helpers
│   │   │   ├── nodes/                   # Sequence, Selector, Parallel, etc.
│   │   │   ├── conditions/              # HasTarget, IsHealthy, etc.
│   │   │   └── actions/                 # Chase, Attack, Flee, etc.
│   │   │
│   │   ├── dialogue/
│   │   │   ├── DialogueSystem.kt
│   │   │   └── DialogueTree.kt
│   │   │
│   │   ├── dungeon/
│   │   │   ├── DungeonManager.kt
│   │   │   ├── DungeonGenerator.kt      # Procedural generation
│   │   │   ├── DungeonRegistry.kt       # Instance tracking
│   │   │   ├── DungeonLayout.kt
│   │   │   └── DungeonInstance.kt
│   │   │
│   │   ├── scheduler/
│   │   │   └── Scheduler.kt             # Delayed actions (via clock.advance)
│   │   │
│   │   ├── quest/
│   │   │   └── [quest system internals]
│   │   │
│   │   ├── crafting/
│   │   │   ├── CraftingSystem.kt
│   │   │   ├── CraftingRegistry.kt
│   │   │   ├── GatheringRegistry.kt
│   │   │   └── [crafting internals]
│   │   │
│   │   ├── GmcpEmitter.kt              # 77K: GMCP package emitter
│   │   ├── ShopRegistry.kt             # Vendor shop definitions
│   │   ├── TrainerRegistry.kt          # Trainer definitions
│   │   ├── SpriteRegistry.kt           # Sprite/avatar definitions
│   │   ├── SpriteLoader.kt             # Load sprites from YAML
│   │   ├── EquipmentSlotRegistry.kt    # Slot definitions
│   │   ├── GenderRegistry.kt           # Gender options
│   │   ├── StatRegistry.kt             # Data-driven stat definitions
│   │   ├── StatRegistryLoader.kt
│   │   ├── EngineUtil.kt               # Shared helpers
│   │   └── [other registries/loaders]
│   │
│   ├── gateway/                         # GATEWAY mode specific
│   │   ├── GatewayServer.kt             # 23K: gateway composition root
│   │   └── SessionRouter.kt             # Maps session→engine for multi-engine mode
│   │
│   ├── grpc/                            # gRPC infrastructure
│   │   ├── EngineServer.kt              # 10K: ENGINE mode gRPC server
│   │   ├── EngineServiceImpl.kt          # gRPC service handler
│   │   ├── GrpcOutboundDispatcher.kt    # Event dispatch over gRPC
│   │   ├── GrpcClientAuthInterceptor.kt # Shared-secret auth
│   │   ├── ProtoMapper.kt               # 8K: Protobuf↔Domain mapping
│   │   └── proto/                       # Generated from .proto files
│   │
│   ├── sharding/                        # Multi-engine / zone sharding
│   │   ├── ZoneRegistry.kt              # Zone→instance mapping
│   │   ├── StaticZoneRegistry.kt        # Hardcoded zone list
│   │   ├── RedisZoneRegistry.kt         # Redis-backed registry
│   │   ├── InterEngineBus.kt            # Inter-engine messaging interface
│   │   ├── LocalInterEngineBus.kt       # In-process
│   │   ├── RedisInterEngineBus.kt       # Redis-backed
│   │   ├── InterEngineMessage.kt        # Message types
│   │   ├── HandoffManager.kt            # 12K: cross-zone session handoff
│   │   ├── PlayerLocationIndex.kt       # O(1) tell/who routing
│   │   ├── InstanceSelector.kt          # Load-balanced instance selection
│   │   ├── LoadBalancedInstanceSelector.kt
│   │   ├── InstanceScaler.kt            # Auto-scale instances up/down
│   │   ├── ThresholdInstanceScaler.kt
│   │   ├── ScaleDecisionPublisher.kt
│   │   ├── ZoneInstance.kt              # Single instance metadata
│   │   └── [other sharding types]
│   │
│   ├── transport/                       # Network I/O
│   │   ├── Transport.kt                 # Interface (start, stop)
│   │   ├── BlockingSocketTransport.kt   # Telnet server
│   │   ├── KtorWebSocketTransport.kt    # 14K: WebSocket server
│   │   ├── NetworkSession.kt            # 12K: single session I/O
│   │   ├── OutboundRouter.kt            # 10K: OutboundEvent→Frame dispatch
│   │   ├── OutboundFrame.kt             # Frame types
│   │   ├── AnsiRenderer.kt              # ANSI color codes
│   │   ├── PlainRenderer.kt             # Plain text output
│   │   ├── TelnetLineDecoder.kt         # 6K: telnet protocol + GMCP negotiation
│   │   ├── ScreenReaderFilter.kt        # A11y: strip ANSI for screen readers
│   │   └── [frame/rendering types]
│   │
│   ├── session/                         # Session ID generation
│   │   ├── SessionIdFactory.kt          # Interface
│   │   ├── AtomicSessionIdFactory.kt    # Counter-based
│   │   ├── SnowflakeSessionIdFactory.kt # Snowflake IDs for distributed
│   │   └── GatewayIdLeaseManager.kt     # Redis-backed gateway uniqueness
│   │
│   ├── persistence/                     # Player/Guild/House save/load
│   │   ├── PlayerRepository.kt          # Interface
│   │   ├── PlayerRecord.kt              # Persistence DTO
│   │   ├── PlayerCreationRequest.kt
│   │   ├── WriteCoalescingPlayerRepository.kt # Batch writes
│   │   ├── RedisCachingPlayerRepository.kt   # Optional caching layer
│   │   ├── YamlPlayerRepository.kt           # File-based storage
│   │   ├── PostgresPlayerRepository.kt       # SQL storage
│   │   ├── PlayersTable.kt              # Exposed ORM schema
│   │   ├── DatabaseManager.kt           # DB connection pooling
│   │   ├── PersistenceWorker.kt         # Async flusher
│   │   ├── WorldStatePersistenceWorker.kt # Flushes world feature state
│   │   ├── GuildRepository.kt           # Guild interface
│   │   ├── GuildRecord.kt
│   │   ├── YamlGuildRepository.kt
│   │   ├── PostgresGuildRepository.kt
│   │   ├── GuildsTable.kt
│   │   ├── HouseRepository.kt           # Player house interface
│   │   ├── HouseRecord.kt
│   │   ├── [other persistence types]
│   │   └── StringCache.kt               # String interning for JSON
│   │
│   ├── redis/                           # Redis infrastructure
│   │   ├── RedisConnectionManager.kt    # Connection pooling
│   │   ├── JsonSupport.kt               # Jackson integration
│   │   └── [redis helpers]
│   │
│   ├── metrics/                         # Observability
│   │   ├── GameMetrics.kt               # 18K: Micrometer meters
│   │   ├── MetricsHttpServer.kt         # Prometheus /metrics endpoint
│   │   └── [metric bindings]
│   │
│   ├── ui/
│   │   └── login/
│   │       ├── LoginScreen.kt           # Login screen metadata
│   │       ├── LoginScreenLoader.kt     # Load login.txt + styles
│   │       └── LoginScreenRenderer.kt   # Render ANSI output
│   │
│   └── [other packages: extensions, error types, etc.]
│
├── src/main/resources/                  # Configuration, assets, YAML
│   ├── application.yaml                 # 163K: master config (all systems, abilities, quests, etc.)
│   ├── application-{engine1,engine2,gw1,gw2}.yaml # Multi-instance profiles
│   ├── logback.xml                      # Logging config
│   ├── login.txt                        # Login banner
│   ├── login.styles.yaml                # ANSI styling for login
│   ├── sprites.yaml                     # Sprite definitions (staff/tier sprites)
│   ├── world/
│   │   ├── achievements.yaml            # Achievement definitions
│   │   ├── sprites.yaml                 # Achievement sprite definitions
│   │   ├── *.yaml                       # 24 zone YAML files (rooms, mobs, items, quests, etc.)
│   │   └── images/                      # PNG sprite assets (organized by zone)
│   ├── db/migration/                    # Flyway SQL migrations (V1-V34)
│   └── web-v3/                          # Built web client assets (gitignored, rebuilt on build)
│
├── src/main/proto/                      # Protocol Buffers
│   └── ambonmud/v1/
│       ├── engine_service.proto         # gRPC service definition
│       ├── events.proto                 # Protobuf event definitions
│       └── [other proto files]
│
├── src/test/kotlin/dev/ambon/           # Tests (~137 files)
│   ├── test/                            # Test utilities
│   │   ├── MutableClock.kt              # Deterministic time
│   │   ├── EngineTestHelpers.kt
│   │   └── [test fixtures]
│   ├── engine/
│   │   ├── GameEngineIntegrationTest.kt # Full login/play/quit flows
│   │   ├── GameEngineLoginFlowTest.kt   # 36K: auth and login sequence
│   │   ├── CombatSystemTest.kt          # 32K: damage, threat, death
│   │   ├── AbilitySystemTest.kt         # 19K: casting, cooldowns, effects
│   │   ├── StatusEffectSystemTest.kt    # 21K: DOT, HOT, stun, root
│   │   ├── BehaviorTreeSystemTest.kt    # 23K: mob AI
│   │   ├── [40+ other engine tests]
│   │   └── commands/
│   │       ├── CommandParserTest.kt     # 24K: every command parse case
│   │       ├── CommandRouterTest.kt     # 20K: handler dispatch
│   │       ├── CommandRouterAdminTest.kt # 20K: staff commands
│   │       └── [command category tests]
│   ├── persistence/
│   │   ├── YamlPlayerRepositoryTest.kt
│   │   ├── PostgresPlayerRepositoryTest.kt
│   │   ├── RedisCachingPlayerRepositoryTest.kt
│   │   ├── WriteCoalescingPlayerRepositoryTest.kt
│   │   └── [persistence tests]
│   ├── bus/
│   │   ├── LocalInboundBusTest.kt
│   │   ├── LocalOutboundBusTest.kt
│   │   ├── RedisInboundBusTest.kt
│   │   ├── GrpcInboundBusTest.kt
│   │   └── [bus tests]
│   ├── transport/
│   │   ├── OutboundRouterTest.kt        # 11K: backpressure, coalescing
│   │   ├── OutboundRouterPromptCoalescingTest.kt
│   │   ├── AnsiRendererTest.kt
│   │   └── [transport tests]
│   ├── sharding/
│   │   ├── HandoffManagerTest.kt        # 18K: cross-zone handoff
│   │   ├── StaticZoneRegistryTest.kt
│   │   ├── LoadBalancedInstanceSelectorTest.kt
│   │   └── [sharding tests]
│   └── [other test packages]
│
├── src/test/resources/
│   └── world/
│       ├── test_world.yaml              # Test world
│       ├── ok_*.yaml                    # Valid zone test data
│       └── bad_*.yaml                   # 40+ invalid YAML test cases
│
├── web-v3/                              # React + PixiJS web client (source)
│   ├── src/
│   │   ├── main.tsx                     # App entry point
│   │   ├── app.tsx                      # Root component
│   │   ├── styles.css                   # Design tokens & component styles (5K lines)
│   │   ├── gmcp/
│   │   │   └── applyGmcpPackage.ts      # GMCP handler (Char.*, Combat.*, etc.)
│   │   ├── canvas/
│   │   │   ├── WorldRenderer.ts         # PixiJS scene
│   │   │   ├── CameraSystem.ts
│   │   │   ├── EntityRenderer.ts
│   │   │   └── [rendering modules]
│   │   ├── components/
│   │   │   ├── MainPanel.tsx
│   │   │   ├── Sidebar.tsx
│   │   │   ├── InputBar.tsx
│   │   │   └── [UI components]
│   │   └── [other source files]
│   ├── public/
│   ├── package.json
│   └── bun.lockb
│
├── build.gradle.kts                     # Gradle build config
├── gradle.properties                    # Gradle settings
├── gradlew                              # Unix gradle wrapper
├── gradlew.bat                          # Windows gradle wrapper
├── docker-compose.yml                   # Prometheus, Grafana, Redis, Postgres
├── Dockerfile                           # Container image
├── README.md                            # Project documentation
├── CLAUDE.md                            # This file (project instructions)
├── AGENTS.md                            # Engineering playbook
└── docs/                                # Design & specification docs
    ├── STYLE_GUIDE.md                   # Design system
    ├── WORLD_YAML_SPEC.md               # Zone YAML format
    ├── DUNGEON_TEMPLATE_REFERENCE.md    # Dungeon YAML schema
    └── [other documentation]
```

## Directory Purposes

**src/main/kotlin/dev/ambon:**
- Purpose: All Kotlin application code
- Contains: Engine, transports, persistence, systems, handlers, domain model
- Key files: Main.kt (entry), MudServer.kt (STANDALONE wiring), GameEngine.kt (core loop)

**src/main/kotlin/dev/ambon/engine:**
- Purpose: Game engine and all gameplay systems
- Contains: GameEngine, 30+ systems, command handlers, registries
- Key files: GameEngine.kt (87K), CombatSystem.kt, AbilitySystem.kt, CommandRouter.kt

**src/main/kotlin/dev/ambon/engine/commands/handlers:**
- Purpose: Player command handlers (one per category or shared)
- Contains: NavigationHandler, CombatHandler, CraftingHandler, ~32 total handler files
- Each handler implements parse logic, game logic, and output formatting

**src/main/kotlin/dev/ambon/transport:**
- Purpose: Network I/O and session management
- Contains: Telnet server, WebSocket server, session state, outbound routing
- Key files: BlockingSocketTransport.kt, KtorWebSocketTransport.kt, OutboundRouter.kt

**src/main/kotlin/dev/ambon/persistence:**
- Purpose: Player/Guild/House save and load
- Contains: Repository interfaces, DTOs, implementations (YAML/Postgres), coalescing layer
- Key files: PlayerRepository.kt, PlayerRecord.kt, WriteCoalescingPlayerRepository.kt

**src/main/kotlin/dev/ambon/domain:**
- Purpose: Immutable domain types (no game logic)
- Contains: PlayerClass, Race, Room, World, Items, Quests, Achievements
- Sub-packages: ids/, items/, mob/, quest/, achievement/, world/, world/data/, world/load/

**src/main/kotlin/dev/ambon/domain/world/load:**
- Purpose: YAML parsing and world loading
- Contains: WorldLoader.kt (61K), zone loaders, YAML validation
- Key files: WorldLoader.kt (main YAML parser), individual zone loaders

**src/main/kotlin/dev/ambon/bus:**
- Purpose: Event bus abstraction and implementations
- Contains: InboundBus/OutboundBus interfaces, Local/Redis/gRPC implementations
- Key files: Local*Bus.kt (for STANDALONE), Redis*Bus.kt (for multi-process), Grpc*Bus.kt (for gRPC)

**src/main/kotlin/dev/ambon/gateway:**
- Purpose: GATEWAY mode specific code
- Contains: GatewayServer.kt, SessionRouter.kt, multi-engine session management
- Key files: GatewayServer.kt (23K, composition root for GATEWAY mode)

**src/main/kotlin/dev/ambon/sharding:**
- Purpose: Multi-engine support, zone distribution, instance management
- Contains: Zone registry, instance selector, handoff manager, location index
- Key files: HandoffManager.kt (12K), ZoneRegistry, InstanceSelector

**src/main/resources/:**
- Purpose: Configuration, assets, and world data
- Contains: application.yaml (163K), zone YAML files (24 files), sprites.yaml, database migrations
- Key files: application.yaml (master config), world/*.yaml (zone definitions)

**src/main/resources/world/:**
- Purpose: World definition files in YAML
- Contains: 24 zone files, achievements.yaml, sprites.yaml
- Each zone file: rooms, mobs, items, quests, shops, trainers, dialogue trees

**src/main/proto/:**
- Purpose: Protocol Buffer definitions for gRPC
- Contains: engine_service.proto (gRPC service), events.proto (event serialization)
- Generated code goes to build/generated/

**src/test/kotlin:**
- Purpose: Unit and integration tests (~137 test files)
- Contains: Comprehensive test coverage for all systems
- Key test classes: GameEngineIntegrationTest (36K), CommandParserTest (24K), CombatSystemTest (32K)

**src/test/resources/world/:**
- Purpose: Test fixtures (valid and invalid YAML)
- Contains: test_world.yaml, ok_*.yaml (valid zones), bad_*.yaml (40+ invalid test cases)

**web-v3/:**
- Purpose: React + PixiJS web client source code
- Contains: Component code, canvas rendering, GMCP handler, design system
- Key files: styles.css (5K, design tokens), main.tsx, applyGmcpPackage.ts
- Output: Built assets written to src/main/resources/web-v3/ by `./gradlew buildWeb`

**docs/:**
- Purpose: Design and specification documentation
- Contains: STYLE_GUIDE.md (design system), WORLD_YAML_SPEC.md (zone format), DUNGEON_TEMPLATE_REFERENCE.md

## Key File Locations

**Entry Points:**
- `src/main/kotlin/dev/ambon/Main.kt`: Bootstrap; loads config, dispatches to STANDALONE/ENGINE/GATEWAY
- `src/main/kotlin/dev/ambon/MudServer.kt`: STANDALONE mode composition root (21K)
- `src/main/kotlin/dev/ambon/grpc/EngineServer.kt`: ENGINE mode gRPC server (10K)
- `src/main/kotlin/dev/ambon/gateway/GatewayServer.kt`: GATEWAY mode composition root (23K)

**Configuration:**
- `src/main/resources/application.yaml`: Master config (163K, all systems, abilities, quests, etc.)
- `src/main/kotlin/dev/ambon/config/AppConfig.kt`: Config schema (84K)
- `src/main/kotlin/dev/ambon/config/AppConfigLoader.kt`: YAML→Kotlin loading

**Core Game Logic:**
- `src/main/kotlin/dev/ambon/engine/GameEngine.kt`: Main tick loop (87K)
- `src/main/kotlin/dev/ambon/engine/PlayerRegistry.kt`: Active players map
- `src/main/kotlin/dev/ambon/engine/PlayerState.kt`: Runtime player data
- `src/main/kotlin/dev/ambon/domain/world/World.kt`: Spatial and content model

**Command Parsing & Dispatch:**
- `src/main/kotlin/dev/ambon/engine/commands/CommandParser.kt`: Text→Command (47K)
- `src/main/kotlin/dev/ambon/engine/commands/CommandRouter.kt`: Command→Handler dispatch (109 lines)
- `src/main/kotlin/dev/ambon/engine/commands/handlers/`: 35+ handler files (one per category)

**Combat & Abilities:**
- `src/main/kotlin/dev/ambon/engine/CombatSystem.kt`: Damage, threat, death (29K)
- `src/main/kotlin/dev/ambon/engine/AbilitySystem.kt`: Casting, cooldowns, effects (29K)
- `src/main/kotlin/dev/ambon/engine/status/StatusEffectSystem.kt`: DOT, HOT, stun, root (16K)

**Persistence:**
- `src/main/kotlin/dev/ambon/persistence/PlayerRepository.kt`: Interface
- `src/main/kotlin/dev/ambon/persistence/PlayerRecord.kt`: Persistence DTO
- `src/main/kotlin/dev/ambon/persistence/WriteCoalescingPlayerRepository.kt`: Write batching
- `src/main/kotlin/dev/ambon/persistence/YamlPlayerRepository.kt`: File-based
- `src/main/kotlin/dev/ambon/persistence/PostgresPlayerRepository.kt`: SQL-based

**Transport & Networking:**
- `src/main/kotlin/dev/ambon/transport/BlockingSocketTransport.kt`: Telnet server
- `src/main/kotlin/dev/ambon/transport/KtorWebSocketTransport.kt`: WebSocket server (14K)
- `src/main/kotlin/dev/ambon/transport/OutboundRouter.kt`: Event→Frame dispatch (10K)
- `src/main/kotlin/dev/ambon/transport/TelnetLineDecoder.kt`: Telnet protocol + GMCP (6K)

**GMCP (Mud Client Protocol):**
- `src/main/kotlin/dev/ambon/engine/GmcpEmitter.kt`: Package emitter (77K)
- `web-v3/src/gmcp/applyGmcpPackage.ts`: Client-side GMCP handler

**Event Bus:**
- `src/main/kotlin/dev/ambon/bus/InboundBus.kt`: Interface
- `src/main/kotlin/dev/ambon/bus/OutboundBus.kt`: Interface
- `src/main/kotlin/dev/ambon/bus/LocalInboundBus.kt`: Channel-based (STANDALONE)
- `src/main/kotlin/dev/ambon/bus/RedisInboundBus.kt`: Redis-backed (multi-process)
- `src/main/kotlin/dev/ambon/bus/GrpcInboundBus.kt`: gRPC-backed (multi-process)

**Testing:**
- `src/test/kotlin/dev/ambon/test/MutableClock.kt`: Deterministic time
- `src/test/kotlin/dev/ambon/engine/GameEngineIntegrationTest.kt`: Full login/play flows (36K)
- `src/test/kotlin/dev/ambon/engine/commands/CommandParserTest.kt`: Command parsing (24K)
- `src/test/kotlin/dev/ambon/engine/CombatSystemTest.kt`: Combat mechanics (32K)

**Web Client:**
- `web-v3/src/styles.css`: Design tokens and all component styles (5K lines)
- `web-v3/src/main.tsx`: App entry point
- `web-v3/src/gmcp/applyGmcpPackage.ts`: GMCP data handler

**World & Content:**
- `src/main/resources/world/`: 24 zone YAML files + achievements.yaml + sprites.yaml
- `src/main/kotlin/dev/ambon/domain/world/load/WorldLoader.kt`: 61K YAML parser
- `docs/WORLD_YAML_SPEC.md`: Zone YAML format reference

## Naming Conventions

**Files:**
- Handler files: `{Category}Handler.kt` (e.g., CombatHandler.kt, CraftingHandler.kt)
- System files: `{Feature}System.kt` (e.g., CombatSystem.kt, QuestSystem.kt)
- Repository files: `{Entity}Repository.kt`, `{Entity}RepositoryFactory.kt`
- Registry files: `{Entity}Registry.kt`, `{Entity}RegistryLoader.kt`
- Test files: `{Class}Test.kt` or `{Feature}IntegrationTest.kt`
- Domain DTOs: `{Entity}Def.kt` (definition), `{Entity}Record.kt` (persistence), `{Entity}State.kt` (runtime)
- YAML DTOs: `{Entity}File.kt` (in domain/world/data/)

**Directories:**
- Game systems: lowercase, plural when applicable (engine/, bus/, persistence/)
- Sub-systems: lowercase by feature name (abilities/, status/, crafting/, behavior/)
- Handlers: lowercase, organized by category (handlers/)
- Domain sub-packages: lowercase by entity (ids/, items/, mob/, quest/, world/)
- Tests: mirror source structure (test/kotlin/dev/ambon/{same-path-as-src}/)

## Where to Add New Code

**New Command:**
1. Add variant to `Command` sealed interface in `src/main/kotlin/dev/ambon/engine/commands/CommandParser.kt`
2. Add parse logic to `CommandParser.parse()`
3. Implement handler in appropriate `src/main/kotlin/dev/ambon/engine/commands/handlers/{Category}Handler.kt`
4. Register in `GameEngine` via `commandRouter.on<Command.YourCommand> { ... }`
5. Tests: `src/test/kotlin/dev/ambon/engine/commands/{Category}Test.kt` or add to existing test file

**New Game System:**
1. Create `src/main/kotlin/dev/ambon/engine/{Feature}System.kt`
2. Initialize in `MudServer.kt` or `GameEngine` constructor
3. Inject into GameEngine and call from appropriate tick hook
4. Add handlers in `src/main/kotlin/dev/ambon/engine/commands/handlers/` as needed
5. Tests: `src/test/kotlin/dev/ambon/engine/{Feature}SystemTest.kt`

**New Ability or Status Effect:**
1. Add definition to `src/main/resources/application.yaml` under `engine.abilities.definitions` or `engine.statusEffects.definitions`
2. No code change needed for basic definitions
3. If custom effect mechanics: update `AbilityRegistryLoader.kt` or `StatusEffectRegistryLoader.kt`
4. Tests: Add cases to existing `AbilitySystemTest.kt` or `StatusEffectSystemTest.kt`

**New Zone/World Content:**
1. Create new YAML file in `src/main/resources/world/{zone_name}.yaml`
2. Define rooms, mobs, items, quests, shops, trainers, dialogue trees per `docs/WORLD_YAML_SPEC.md`
3. No code change required
4. World loader picks up file automatically on startup

**New Persistence Field (Player):**
1. Add field to `PlayerState` in `src/main/kotlin/dev/ambon/engine/PlayerState.kt`
2. Add field to `PlayerRecord` in `src/main/kotlin/dev/ambon/persistence/PlayerRecord.kt`
3. Update `PlayersTable.kt` and add Flyway migration in `src/main/resources/db/migration/V{N}__description.sql`
4. Update round-trip conversion in `PlayerState.kt` extensions: `toPlayerState()` and `toPlayerRecord()`
5. Tests: Add to `PersistenceFieldCoverageTest` to verify round-trip across YAML, Redis, Postgres

**Utilities & Helpers:**
- Shared command/system helpers: `src/main/kotlin/dev/ambon/engine/commands/handlers/HandlerHelpers.kt`
- Shared engine utilities: `src/main/kotlin/dev/ambon/engine/EngineUtil.kt`
- Domain helpers: `src/main/kotlin/dev/ambon/domain/{area}/` (e.g., domain/items/ for item helpers)

## Special Directories

**src/main/resources/db/migration/:**
- Purpose: Flyway SQL migrations (auto-applied on startup if using PostgreSQL)
- Generated: No, manually written
- Committed: Yes, version-controlled
- Files: V1__initial_schema.sql through V34__latest_migration.sql

**src/main/resources/world/images/:**
- Purpose: PNG sprite assets for demonstration
- Generated: No (source images provided by design)
- Committed: Yes, but kept minimal
- Usage: Referenced in zone YAML under `image:` fields

**build/generated/:**
- Purpose: Generated Protocol Buffer and gRPC code
- Generated: Yes, by Gradle protobuf plugin
- Committed: No, gitignored
- Editable: No, regenerated on every build

**src/main/resources/web-v3/:**
- Purpose: Built web client assets (React + PixiJS bundle)
- Generated: Yes, by `./gradlew buildWeb` (calls `bun run build` from web-v3/)
- Committed: No, gitignored (rebuilt on demand)
- Source: web-v3/ directory

**data/players/:**
- Purpose: YAML player save files (if persistence.backend=YAML)
- Generated: Yes, by YamlPlayerRepository at runtime
- Committed: No, gitignored (ephemeral player saves)

---

*Structure analysis: 2026-04-05*
