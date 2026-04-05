# Architecture

**Analysis Date:** 2026-04-05

## Pattern Overview

**Overall:** Layered event-driven architecture with pluggable transports and persistence backends

**Key Characteristics:**
- Single-threaded game engine on dedicated coroutine dispatcher
- Transport-agnostic event bus (InboundBus/OutboundBus)
- Sealed command hierarchy with registry-based routing
- Three deployment modes (STANDALONE, ENGINE, GATEWAY) with identical game logic
- Pluggable persistence chain: WriteCoalescingPlayerRepository → RedisCachingPlayerRepository → YamlPlayerRepository|PostgresPlayerRepository

## Layers

**Transport Layer (Network I/O):**
- Purpose: Accept raw client input (telnet/WebSocket) and serialize engine outbound events to network frames
- Location: `src/main/kotlin/dev/ambon/transport/`
- Contains: BlockingSocketTransport, KtorWebSocketTransport, NetworkSession, OutboundRouter
- Depends on: InboundBus (to send Connected/Disconnected/LineReceived/GmcpReceived events), OutboundBus (to receive text/prompts/GMCP/control events)
- Used by: Session handlers that accept inbound frames and emit OutboundFrames

**Event Bus Layer (Pub-Sub Abstraction):**
- Purpose: Decouple transports from engine; enable multi-process deployments via Redis or gRPC
- Location: `src/main/kotlin/dev/ambon/bus/`
- Contains: InboundBus interface (Local, Redis, gRPC impls), OutboundBus interface (Local, Redis, gRPC impls)
- Depends on: InboundEvent, OutboundEvent sealed types; Clock (for event timestamps); Jackson/Protobuf for serialization
- Used by: GameEngine (receives/sends events), Transports (sends inbound, receives outbound), Persistence (flushes on shutdown)

**Engine Layer (Game Logic):**
- Purpose: Single-threaded tick-based game loop processing player commands, mob AI, combat, ability effects, world events
- Location: `src/main/kotlin/dev/ambon/engine/`
- Contains: GameEngine (87K lines, tick loop), PlayerRegistry, PlayerState, CombatSystem, AbilitySystem, StatusEffectSystem, MobSystem, BehaviorTreeSystem, CommandRouter, 30+ specialized systems
- Depends on: InboundBus (to poll/receive events), OutboundBus (to send events), World (spatial/content model), Registries (items, mobs, abilities, quests, achievements, stats)
- Used by: Entry point (MudServer/EngineServer/GatewayServer instantiate), PersistenceWorker (calls save/evict)

**Command Layer (Player Input → Game Action):**
- Purpose: Parse raw input text into a sealed Command hierarchy, route to handlers
- Location: `src/main/kotlin/dev/ambon/engine/commands/`
- Contains: CommandParser (47K, text→Command parsing), CommandRouter (dispatch registry), handlers/ subpackage with ~35 handler files (NavigationHandler, CombatHandler, CraftingHandler, etc.)
- Depends on: PlayerRegistry, World, Registries, OutboundBus
- Used by: InputEventHandler in GameEngine event dispatch

**Domain Layer (Data Models):**
- Purpose: Immutable domain types: Player progression, items, mobs, quests, achievements, world structure
- Location: `src/main/kotlin/dev/ambon/domain/`
- Contains: PlayerClass, Race, Stats, Room, Direction, World; sub-packages: ids/, items/, mob/, quest/, achievement/, world/, world/data/, world/load/
- Depends on: Jackson (for YAML deserialize), validations
- Used by: Every layer

**Persistence Layer (Save/Load):**
- Purpose: Translate between runtime state (PlayerState/PlayerRecord) and durable storage (YAML files or PostgreSQL)
- Location: `src/main/kotlin/dev/ambon/persistence/`
- Contains: PlayerRepository interface, PlayerRecord DTO, WriteCoalescingPlayerRepository (batches writes), RedisCachingPlayerRepository (optional layer), YamlPlayerRepository, PostgresPlayerRepository; GuildRepository/HouseRepository variants
- Depends on: Database (Exposed ORM), Jackson (YAML), Lettuce (Redis optional)
- Used by: MudServer wiring, PersistenceWorker (async flush)

**Deployment-Specific Layers:**

*STANDALONE mode (MudServer):*
- Single JVM process: all transports + engine + persistence in-process
- Wiring: `src/main/kotlin/dev/ambon/MudServer.kt` (21K, composition root)
- InboundBus/OutboundBus: LocalInboundBus/LocalOutboundBus (in-process Channel-based)

*ENGINE mode (EngineServer):*
- No transports; exposes GameEngine via gRPC service (proto: `src/main/proto/ambonmud/v1/engine_service.proto`)
- Wiring: `src/main/kotlin/dev/ambon/grpc/EngineServer.kt`
- InboundBus/OutboundBus: GrpcInboundBus/GrpcOutboundBus (receive/send from gRPC streams)

*GATEWAY mode (GatewayServer):*
- Transports + gRPC client connections to remote engines; no local game logic
- Wiring: `src/main/kotlin/dev/ambon/gateway/GatewayServer.kt` (23K)
- InboundBus/OutboundBus: GrpcInboundBus/GrpcOutboundBus (forward to engines)
- SessionRouter: remaps sessions on cross-zone handoffs (engine redirects)

## Data Flow

**Player Command Execution:**

1. Transport reads raw line from socket → creates LineReceived InboundEvent
2. InboundBus receives LineReceived event (buffered in Channel)
3. GameEngine.run() tick processes inbound queue: calls InputEventHandler.handle(LineReceived)
4. InputEventHandler calls CommandParser.parse() → sealed Command instance
5. CommandRouter.handle(sessionId, Command) dispatches to registered handler
6. Handler queries PlayerRegistry/World/Registries, modifies PlayerState, emits OutboundEvents
7. OutboundRouter reads OutboundBus receive channel in background job
8. OutboundRouter renders text via AnsiRenderer/PlainRenderer, writes to OutboundFrame
9. Transport session receives OutboundFrame from queue, writes to socket

**Session Lifecycle:**

1. Transport accepts connection → creates NetworkSession → sends Connected InboundEvent
2. LoginFlowHandler processes Connected, shows login screen (SendText with login.txt content)
3. Player enters name → LineReceived(name) → LoginFlowHandler.promptForPassword()
4. Player enters password → LoginFlowHandler validates BCrypt hash
5. LoginFlowHandler calls PlayerRegistry.login() to load PlayerState from repository
6. After login: GmcpEmitter sends Char.Name, Char.Stats, Char.Skills, Char.Inventory GMCP packages
7. Player sees prompt and can issue commands
8. Player enters "quit" → CommandRouter.handle() calls QuitHandler → sends Close OutboundEvent
9. SessionEventHandler.onPlayerLoggedOut() called → cleans up session, flushes PlayerState to persistence
10. Transport closes socket

**Multi-Process (ENGINE + GATEWAY):**

1. Gateway transport accepts connection, allocates SessionId
2. Gateway sends Connected InboundEvent to GrpcInboundBus
3. GrpcInboundBus serializes to proto, calls engine.SendInboundEvent(proto)
4. Engine receives proto stream, GrpcInboundBus deserializes to InboundEvent
5. Engine processes normally, emits OutboundEvents to GrpcOutboundBus
6. GrpcOutboundBus serializes to proto, sends in outbound stream
7. Gateway receives proto stream, deserializes to OutboundEvent
8. OutboundRouter on gateway handles event, writes to session socket
9. Cross-zone handoff: Engine sends SessionRedirect OutboundEvent
10. Gateway SessionRouter remaps session to different engine, reconnects gRPC client

**State Management:**

- **Runtime state:** PlayerState (in-memory), MobState, DungeonInstance (ephemeral)
- **Persisted state:** PlayerRecord (via PlayerRepository), GuildRecord (via GuildRepository)
- **World state:** WorldStateRegistry (world feature toggles: doors, containers, levers)
- **Periodic flush:** PersistenceWorker calls `playerRepo.save(playerRecord)` every N seconds (coalesced)

## Key Abstractions

**InboundEvent (sealed interface):**
- Purpose: Represent all types of input from clients
- Examples: `Connected(sessionId)`, `LineReceived(sessionId, line)`, `GmcpReceived(sessionId, gmcpPackage, jsonData)`
- Pattern: Direct sum type; engine exhaustively matches on all variants

**OutboundEvent (sealed interface):**
- Purpose: Represent all types of output to clients
- Examples: `SendText(sessionId, text)`, `SendPrompt(sessionId)`, `GmcpData(sessionId, gmcpPackage, jsonData)`, `Close(sessionId, reason)`
- Pattern: Direct sum type; OutboundRouter exhaustively matches on all variants

**Command (sealed interface):**
- Purpose: Represent all player commands after parsing
- Examples: `Move(dir)`, `Say(message)`, `Kill(targetName)`, `Learn(abilityName)`
- Pattern: Direct sum type; CommandRouter routes via registry lookup by `cmd::class`

**PlayerRegistry:**
- Purpose: In-memory map of active SessionId → PlayerState
- Pattern: get(sessionId), getByName(name), add/remove on login/logout
- Lifecycle: Empty on startup, populated as players login, emptied as players logout

**PlayerState:**
- Purpose: Complete runtime player data (HP, mana, inventory, quests, etc.)
- Pattern: Mutable data class; modified by systems during tick; flushed to PlayerRecord periodically
- Lifecycle: Created on login (load from repository), modified in-tick, saved on logout

**PlayerRecord:**
- Purpose: Persistence DTO with all fields that survive logout
- Pattern: Immutable data class; created/updated by repository layer
- Contains: All PlayerState fields except runtime-only (lastActivityEpochMs, possessed MobId, etc.)

**World:**
- Purpose: Immutable spatial model (rooms, NPCs, items, spawns, quests, shops)
- Pattern: Loaded once at startup from YAML, reference is shared read-only
- Contains: Map<RoomId, Room>, mob/item spawns, quest/shop/trainer definitions

**Room:**
- Purpose: Single location with exits, NPCs, features (doors, containers, levers)
- Pattern: Immutable; accessed via World.rooms[roomId]
- Contains: description, exits, mobIds, itemIds, features

## Entry Points

**STANDALONE (MudServer.start):**
- Location: `src/main/kotlin/dev/ambon/MudServer.kt`
- Triggers: `./gradlew run` or `Main.kt` with `config.mode == STANDALONE`
- Responsibilities: Compose all layers (transports, engine, persistence), start/stop all jobs, bind to telnet/web ports

**ENGINE (EngineServer.start):**
- Location: `src/main/kotlin/dev/ambon/grpc/EngineServer.kt`
- Triggers: `./gradlew runEngine1` or `Main.kt` with `config.mode == ENGINE`
- Responsibilities: Start gRPC server (port 9091+), forward inbound/outbound to local GameEngine, handle session redirects

**GATEWAY (GatewayServer.start):**
- Location: `src/main/kotlin/dev/ambon/gateway/GatewayServer.kt`
- Triggers: `./gradlew runGateway1` or `Main.kt` with `config.mode == GATEWAY`
- Responsibilities: Start telnet/web transports, connect to engine(s) via gRPC, proxy InboundEvents and OutboundEvents

**Main.kt:**
- Location: `src/main/kotlin/dev/ambon/Main.kt`
- Loads AppConfig, sets log levels, dispatches to one of the three server classes
- Installs shutdown hook for graceful termination

## Error Handling

**Strategy:** Fail-fast on unrecoverable errors; resilient channels for recoverable backpressure

**Patterns:**
- Login errors: Send SendError OutboundEvent, keep session open for retry (max 3 wrong passwords)
- Command errors: Send SendError (invalid command) or SendInfo (outcome), always send SendPrompt
- Missing player/NPC: Silently skip (e.g., kill non-existent target, look at missing room feature)
- Persistence errors: Log warning, continue (do not drop session)
- Transport backpressure: Session queue fills; OutboundRouter applies backpressure; slow clients get disconnected after timeout
- Channel closed: Transports and engine handle gracefully (no panic)

## Cross-Cutting Concerns

**Logging:** 
- Framework: Logback (ch.qos.logback)
- Levels configurable per-package in `application.yaml`
- Used for startup info, errors, debug traces, metrics (no game messages)

**Validation:** 
- Player names: 2–16 chars, alphanumeric/underscore, no leading digit
- Passwords: non-blank, max 72 chars (BCrypt limit)
- Configured in LoginFlowHandler, validated before PlayerRecord creation

**Authentication:** 
- BCrypt hash on password (4 rounds)
- Auth tokens: SHA-256 hash stored in player record, issued on login, used for fast re-login
- Resume tokens: Session-only, used to reconnect if gateway fails

**GMCP (Mud Client Protocol):**
- Enabled via telnet negotiation in NetworkSession
- Engine emits structured JSON packages (Char.Name, Char.Stats, Char.Skills, Combat.State, etc.)
- Client parses JSON and updates UI (handled by `web-v3/src/gmcp/applyGmcpPackage.ts`)
- Sent via `GmcpData` OutboundEvent; routed through OutboundRouter unchanged

---

*Architecture analysis: 2026-04-05*
