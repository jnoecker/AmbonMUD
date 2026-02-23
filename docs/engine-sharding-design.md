# AmbonMUD — Zone-Based Engine Sharding Design

## Problem Statement

The current architecture has a **single engine process** as the authoritative game state holder. Phase 4 scaled the *edge* (multiple gateways), but the engine remains:

1. **A single point of failure** — if the engine process dies, every player disconnects and all in-flight state (combat, mob AI timers, regen) is lost.
2. **A throughput ceiling** — the engine is single-threaded by design (coroutine dispatcher with one thread). As player count and world complexity grow, the 100ms tick budget becomes the bottleneck.
3. **A scaling wall** — adding more zones, mobs, or players increases per-tick work linearly. There's no way to throw more hardware at the problem.

Zone-based sharding addresses all three by partitioning the world across multiple engine processes, each owning one or more zones.

---

## Design Principles

1. **Zone is the shard unit.** A zone is already a natural boundary: rooms are namespaced (`zone:room`), mobs and items belong to zones, zone resets are independent. Sharding along zone lines minimizes cross-shard operations.

2. **Cross-zone operations are the exception, not the rule.** Most game ticks are zone-local: combat, mob wandering, room-local communication (say/emote), item manipulation, regen. Only a handful of operations cross zones: movement through cross-zone exits, `tell`, `gossip`, `who`, `goto`, `transfer`.

3. **Incremental adoption.** A single engine running all zones must remain a valid deployment. Sharding is opt-in configuration, not a rewrite.

4. **No distributed transactions.** Cross-zone operations use asynchronous message passing with eventual consistency. A player moving between zones is a brief handoff, not a two-phase commit.

5. **Engines are stateless between restarts.** All durable state lives in the persistence layer (YAML/Postgres). An engine shard can restart, reload its zones, and resume.

---

## Target Architecture

```
Clients ──→ Gateway A ──→┐                    ┌──→ Gateway B ←── Clients
                         │                    │
                    ┌────┴────────────────────┴────┐
                    │       Zone Router / Mesh      │
                    │  (route sessionId → engine)   │
                    └──┬──────────┬──────────┬──────┘
                       │          │          │
                  Engine-1    Engine-2    Engine-3
                 [ambon_hub]  [demo_ruins] [noecker_resume]
                    │          │          │
                    └──── Shared Persistence ────┘
                          (Postgres / YAML)
                          + Redis (routing, pub/sub)
```

**Key changes from Phase 4:**
- Multiple engine processes, each owning a subset of zones
- A **zone routing layer** that maps sessions to their owning engine
- **Inter-engine messaging** for cross-zone commands
- Gateways connect to multiple engines (or to a routing proxy)

---

## Concepts

### Zone Assignment

Each engine declares which zones it owns at startup via configuration:

```yaml
ambonMUD:
  mode: ENGINE
  sharding:
    zones: ["demo_ruins", "noecker_resume"]   # this engine owns these zones
    # empty list = own ALL zones (single-engine backward compat)
```

An engine **only loads the zones it owns** from the world YAML. It holds `World`, `MobRegistry`, `ItemRegistry`, `MobSystem`, `CombatSystem`, `RegenSystem`, and `Scheduler` state exclusively for its assigned zones.

A zone belongs to exactly one engine at any time. This is enforced by a **zone lease** in Redis (or a static config table in simpler deployments).

### Session Ownership

A session is owned by whichever engine owns the zone the player is currently in. When a player moves cross-zone, session ownership transfers.

**Invariant:** At any moment, exactly one engine holds the `PlayerState` for a given session. There is no split-brain.

### Player Location as the Routing Key

The **zone of the player's current room** determines which engine processes their commands. This is the fundamental routing decision:

```
sessionId → playerRoomId → zone → engine
```

---

## Component Design

### 1. Zone Registry (New)

A shared registry mapping zone names to engine addresses. Backed by Redis for dynamic deployments, or static config for simple setups.

```kotlin
interface ZoneRegistry {
    /** Which engine owns this zone right now? */
    fun ownerOf(zone: String): EngineAddress?

    /** Register this engine as owner of these zones (with lease/TTL). */
    fun claimZones(engineId: String, zones: List<String>, ttlSeconds: Int)

    /** Heartbeat to keep leases alive. */
    fun renewLease(engineId: String)

    /** All engines and their zones. */
    fun allAssignments(): Map<String, List<String>>
}
```

**Implementations:**
- `StaticZoneRegistry` — reads from config, no Redis needed. For development and simple deployments where zone assignments don't change.
- `RedisZoneRegistry` — uses Redis keys with TTL. Engine heartbeats renew the lease. If an engine dies, its zones become unowned after TTL expiry, enabling failover.

**Redis key scheme:**
```
zone:owner:<zone_name>  →  { engineId, grpcHost, grpcPort }   TTL=30s
engine:zones:<engineId> →  SET of zone names                   TTL=30s
```

### 2. Session Router (New)

The routing layer that directs inbound events from gateways to the correct engine.

#### Smart Gateways 

Gateways maintain a local cache of `zone → engine` mappings (populated from ZoneRegistry). When a gateway receives an inbound event, it looks up the player's current zone and forwards to the correct engine.

```
Gateway
  │
  ├── gRPC stream to Engine-1 (for sessions in Engine-1's zones)
  ├── gRPC stream to Engine-2 (for sessions in Engine-2's zones)
  └── gRPC stream to Engine-3 (for sessions in Engine-3's zones)
```

**Pros:** No additional proxy hop. Gateways already have per-session state.
**Cons:** Gateways need zone-awareness. Must handle stream-per-engine lifecycle.

The gateway tracks `sessionId → engineStream` and updates this mapping when a player crosses zones (the old engine sends a redirect signal).


### 3. Inter-Engine Bus (New)

Engines need to communicate for cross-zone operations. This extends the existing bus abstraction.

```kotlin
interface InterEngineBus {
    /** Send a message to the engine that owns the given zone. */
    suspend fun sendToZone(zone: String, message: InterEngineMessage)

    /** Broadcast to ALL engines (for gossip, who, shutdown). */
    suspend fun broadcast(message: InterEngineMessage)

    /** Receive messages targeted at this engine. */
    fun incoming(): ReceiveChannel<InterEngineMessage>
}
```

**Implementations:**
- `RedisInterEngineBus` — uses Redis pub/sub. Each engine subscribes to `engine:<engineId>` (targeted) and `engine:broadcast` (global). Messages are JSON-serialized.
- `GrpcInterEngineBus` — direct gRPC streams between engines. More efficient, but requires engines to discover and connect to each other. Better for high-throughput cross-zone traffic.
- `LocalInterEngineBus` — in-process for single-engine mode. All "cross-zone" messages just loop back.

**Message types:**

```kotlin
sealed interface InterEngineMessage {
    /** Player is moving from one engine's zone to another. */
    data class PlayerHandoff(
        val sessionId: SessionId,
        val playerRecord: PlayerRecord,   // serialized player state
        val targetRoomId: RoomId,
        val playerState: SerializedPlayerState,  // HP, combat, inventory, etc.
        val gatewayId: Int,               // so the target engine can route outbound
    ) : InterEngineMessage

    /** Cross-zone private message. */
    data class TellMessage(
        val fromName: String,
        val toName: String,
        val text: String,
    ) : InterEngineMessage

    /** Broadcast to all players (gossip, server shutdown). */
    data class GlobalBroadcast(
        val type: BroadcastType,  // GOSSIP, SHUTDOWN, ANNOUNCEMENT
        val senderName: String,
        val text: String,
    ) : InterEngineMessage

    /** Request: "who is online?" — each engine replies with its player list. */
    data class WhoRequest(
        val requestId: String,
        val replyToEngineId: String,
    ) : InterEngineMessage

    /** Response to WhoRequest. */
    data class WhoResponse(
        val requestId: String,
        val players: List<PlayerSummary>,
    ) : InterEngineMessage

    /** Redirect gateway outbound for a session to a new engine. */
    data class SessionRedirect(
        val sessionId: SessionId,
        val newEngineId: String,
        val newEngineHost: String,
        val newEnginePort: Int,
    ) : InterEngineMessage

    /** Staff: transfer player to a zone on another engine. */
    data class TransferRequest(
        val staffSessionId: SessionId,
        val targetPlayerName: String,
        val targetRoomId: RoomId,
    ) : InterEngineMessage
}
```

### 4. Player Handoff Protocol (Cross-Zone Movement)

When a player walks through a cross-zone exit whose destination is on a different engine:

```
                Engine-A (source)              Engine-B (target)
                ──────────────────             ──────────────────
 1. Player types "north"
 2. Resolve exit → "zone_b:room_x"
 3. Lookup ZoneRegistry: zone_b → Engine-B
 4. Serialize player state (snapshot)
 5. Remove player from local registries
 6. Broadcast "player leaves" to room
 7. Send PlayerHandoff → Engine-B
 8. Send SessionRedirect → Gateway
    (gateway switches outbound stream)
                                        9.  Receive PlayerHandoff
                                        10. Deserialize player state
                                        11. Add player to local registries
                                        12. Place in target room
                                        13. Broadcast "player arrives" to room
                                        14. Send "look" output to session
                                        15. Ack handoff (optional)
```

**During handoff (steps 5-12), the session is in transit.** The gateway buffers any player input until the new engine confirms ownership. This window should be <100ms in practice.

**Failure handling:**
- If Engine-B doesn't ack within a timeout (e.g. 2 seconds), Engine-A restores the player to their original room and sends an error: "The way north shimmers but does not yield."
- If Engine-A crashes mid-handoff, Engine-B may receive a handoff for a session it can't route outbound to. It persists the player state (safe) and waits for the gateway to reconnect/redirect.

**Serialized player state includes:**
```kotlin
data class SerializedPlayerState(
    val record: PlayerRecord,        // persisted fields
    val hp: Int,
    val maxHp: Int,
    val baseMaxHp: Int,
    val constitution: Int,
    val level: Int,
    val xpTotal: Long,
    val ansiEnabled: Boolean,
    val isStaff: Boolean,
    val inventoryItemIds: List<String>,
    val equippedItemIds: Map<EquipSlot, String>,
    // Note: combat state is NOT transferred — combat ends on zone change
)
```

**Combat on zone change:** Combat ends when a player crosses zones. This is already the behavior for `flee`; crossing zones is a stronger form of disengagement. The mob resets to its spawn state on the next zone tick.

### 5. Partitioned Engine State

Each engine shard holds state only for its zones:

| Component | Current (Global) | Sharded |
|-----------|-------------------|---------|
| `World` | All rooms, all zones | Only rooms in owned zones + *stubs* for cross-zone exit targets |
| `PlayerRegistry` | All online players | Only players currently in this engine's zones |
| `MobRegistry` | All mobs | Only mobs in owned zones |
| `ItemRegistry` | All items | Items in owned zones' rooms + items in inventories of players on this engine |
| `CombatSystem` | All fights | Only fights on this engine (always player + mob in same zone) |
| `MobSystem` | All mob wander state | Only mobs in owned zones |
| `RegenSystem` | All player regen | Only players on this engine |
| `Scheduler` | All scheduled actions | Only zone-local scheduled actions |

**Room stubs:** For cross-zone exits, the source engine only needs to know the exit exists and which zone it targets. It doesn't need the full target room definition. The `WorldLoader` already validates all exits at load time; in sharded mode, cross-zone exit targets would be validated against the ZoneRegistry instead.

### 6. Global Commands Across Shards

#### `gossip`
- Engine broadcasts `GlobalBroadcast(GOSSIP, ...)` via InterEngineBus
- Every engine delivers to its local players
- Latency: one Redis pub/sub hop (~1-5ms)

#### `tell <player> <message>`
- Engine checks local PlayerRegistry first
- If not found locally, broadcasts `TellMessage` to all engines (or uses a global player-location index in Redis: `player:location:<name>` → `engineId`)
- Target engine delivers to the session
- If player is offline/not found: "Player not found" error

**Player location index (Redis):**
```
player:online:<lowercase_name>  →  { engineId, sessionId }   TTL=heartbeat
```

Updated on login, logout, and zone transfer. Enables O(1) `tell` routing without broadcast.

#### `who`
- Engine sends `WhoRequest` broadcast
- Collects `WhoResponse` from all engines (with timeout)
- Merges and displays

**Alternative:** Maintain a Redis set `online:players` that each engine updates. `who` reads from Redis directly. Simpler, lower latency, slightly less consistent.

#### `goto <zone:room>` (staff)
If the target room is on another engine, this becomes a self-initiated cross-zone handoff — same as walking through a cross-zone exit.

#### `transfer <player> <room>` (staff)
- If target player is local: normal transfer
- If remote: send `TransferRequest` to the player's engine. That engine initiates a handoff.

#### `kick <player>` (staff)
- If local: normal kick
- If remote: send a `KickRequest` via InterEngineBus

#### `shutdown`
- Broadcast `GlobalBroadcast(SHUTDOWN, ...)` to all engines
- Each engine runs its local shutdown sequence

### 7. Gateway Changes

In the current architecture, a gateway holds one gRPC stream to one engine. With sharding:

- A gateway holds **one stream per engine** (or per engine it has active sessions on)
- The gateway maintains a `sessionId → engineStream` mapping
- On `SessionRedirect`, the gateway remaps the session to the new engine's stream
- If the gateway has no sessions on a given engine, it can lazily close that stream

**GatewayServer changes:**
```kotlin
class GatewayServer {
    // Current: single engine stream
    // private lateinit var engineStream: BidiStream

    // Sharded: stream per engine
    private val engineStreams = ConcurrentHashMap<String, EngineConnection>()
    private val sessionToEngine = ConcurrentHashMap<SessionId, String>()

    // On SessionRedirect from current engine:
    fun handleRedirect(sessionId: SessionId, newEngineId: String) {
        val newStream = engineStreams.getOrPut(newEngineId) {
            connectToEngine(newEngineId)
        }
        sessionToEngine[sessionId] = newEngineId
    }
}
```

**Outbound routing:** Each engine stream has its own receiver coroutine. Outbound events arrive from whichever engine owns the session and are routed to the session's transport normally.

**Inbound routing:** When the gateway receives a `LineReceived` from a session, it looks up `sessionToEngine[sessionId]` and sends to the correct engine's stream.

**Login flow:** New connections must be routed to an engine for login processing. 
- **Any engine:** The gateway picks any engine (round-robin or random). That engine runs the login flow. If the player's saved room is in another engine's zone, it initiates a handoff immediately after login.

**Recommendation:** "Any engine" approach for simplicity. The handoff protocol already handles the redirect. Login is infrequent enough that the extra hop doesn't matter.

### 8. Configuration

```yaml
ambonMUD:
  mode: ENGINE  # or STANDALONE (unchanged) or GATEWAY (unchanged)

  sharding:
    enabled: false                    # false = single-engine (backward compat)
    zones: []                         # empty = all zones (single-engine)
    # zones: ["demo_ruins"]           # this engine owns only demo_ruins

    registry:
      type: REDIS                     # REDIS or STATIC
      # Static assignments (type: STATIC):
      # assignments:
      #   engine-1: ["ambon_hub", "demo_ruins"]
      #   engine-2: ["noecker_resume"]

    interEngine:
      type: REDIS                     # REDIS or GRPC
      # GRPC requires engines to discover each other via ZoneRegistry

    handoff:
      timeoutMs: 2000                 # max wait for handoff ack
      bufferInputDuringHandoff: true  # gateway buffers input during transit

    playerIndex:
      enabled: true                   # Redis index for O(1) tell routing
      heartbeatMs: 10000              # refresh TTL on player location keys

  gateway:
    engines: []                       # empty = discover from ZoneRegistry
    # engines:                        # static engine list (no discovery)
    #   - host: engine-1.local
    #     port: 9090
    #   - host: engine-2.local
    #     port: 9091
```

### 9. Persistence Implications

Persistence is already shared (YAML directory or Postgres database). No changes to the persistence layer itself, but:

- **Write coalescing** remains per-engine. Each engine coalesces writes for its local players.
- **Handoff triggers a flush.** Before transferring a player to another engine, the source engine flushes that player's record to persistence. The target engine reads the fresh record.
- **Race window:** If two engines somehow both write the same player record (shouldn't happen with correct handoff), Postgres upsert or YAML atomic write ensures the last write wins. The handoff protocol's "exactly one owner" invariant prevents this in practice.
- **Redis cache** (Phase 3) is shared. Both engines can read/write the same player cache key. The handoff flush ensures consistency.

---

## Failure Scenarios

### Engine Crash

1. **ZoneRegistry lease expires** (30s TTL, no heartbeat from dead engine).
2. **Gateways detect stream failure** (existing exponential-backoff reconnect logic).
3. **Gateway disconnects all sessions** that were on the dead engine (existing `handleStreamFailure()` behavior).
4. **Recovery options:**
   - **Manual restart:** Restart the engine, it reclaims its zones, players reconnect.
   - **Automatic failover:** A standby engine detects the expired lease and claims the orphaned zones. Players must re-login (session state is lost, but player records are persisted).

Automatic failover is a future enhancement. For the initial implementation, manual restart with player reconnect is sufficient — it's already better than the current situation where the *entire* server goes down. Add an issue to track this for later.

### Network Partition (Engine-to-Engine)

If Engine-A can't reach Engine-B via InterEngineBus:
- Cross-zone movement fails gracefully ("The way shimmers but does not yield.")
- `tell` to players on the unreachable engine fails ("Player not found.")
- `gossip` is delivered to reachable engines only
- `who` shows partial results (with a warning: "Some servers are unreachable.")

Each engine's zone-local gameplay continues uninterrupted. The partition only affects cross-zone operations.

### Gateway-to-Engine Connection Loss

Same as current Phase 4 behavior: gateway disconnects affected sessions, attempts exponential-backoff reconnect. With sharding, only sessions on the lost engine are affected.

### Handoff Failure

If the target engine is unreachable during a cross-zone move:
- Source engine restores the player to their original room
- Player receives an error message
- No state is lost

---

## Phased Implementation

### Phase 5a: Zone-Partitioned World Loading

**Goal:** Engines load only their assigned zones. Cross-zone exits become stubs.

**Changes:**
- `AppConfig.kt`: Add `ShardingConfig`
- `WorldLoader.kt`: Accept a zone filter. Load only matching zones. For cross-zone exits, create `ExitStub` entries instead of validating target rooms.
- `GameEngine.kt`: No change — it operates on whatever `World` it receives.

**Verification:** Single engine with `zones: []` (all zones) behaves identically to today. Engine with `zones: ["demo_ruins"]` loads only that zone. Cross-zone exits resolve to stubs.

### Phase 5b: Zone Registry & Inter-Engine Bus

**Goal:** Engines register their zones and can exchange messages.

**Changes:**
- New `ZoneRegistry` interface + `StaticZoneRegistry` + `RedisZoneRegistry`
- New `InterEngineBus` interface + `LocalInterEngineBus` + `RedisInterEngineBus`
- Engine startup: register zones with ZoneRegistry, start InterEngineBus listener
- Engine tick: renew lease (heartbeat)

**Verification:** Two engines in separate processes, each owning different zones, both register in Redis. Each can send/receive messages.

### Phase 5c: Player Handoff Protocol

**Goal:** Players can move between zones owned by different engines.

**Changes:**
- `CommandRouter.kt`: On cross-zone move, check ZoneRegistry. If target zone is remote, initiate handoff instead of local move.
- New `HandoffManager` — serializes player state, sends `PlayerHandoff`, handles timeout/rollback.
- `GameEngine.kt`: Handle incoming `PlayerHandoff` — deserialize, add to local registries, send room look.
- `GatewayServer.kt`: Handle `SessionRedirect` — remap session to new engine stream.

**Verification:** Player walks from `ambon_hub` (Engine-1) to `demo_ruins` (Engine-2). Session seamlessly continues on Engine-2. Walking back works. Handoff timeout (kill Engine-2) gracefully fails.

### Phase 5d: Global Commands

**Goal:** `gossip`, `tell`, `who`, and staff commands work across engines.

**Changes:**
- `CommandRouter.kt`: `gossip` → `InterEngineBus.broadcast(GlobalBroadcast)`. `tell` → look up player location index, send `TellMessage`. `who` → gather from all engines.
- New Redis player location index (updated on login/logout/handoff)
- Staff commands (`goto`, `transfer`, `kick`) route via InterEngineBus when target is remote.

**Verification:** Two players on different engines can gossip, tell, see each other in `who`. Staff can `goto` and `transfer` across engines.

### Phase 5e: Gateway Multi-Engine Support

**Goal:** Gateways connect to multiple engines and route sessions correctly.

**Changes:**
- `GatewayServer.kt`: Multiple engine connections. Session-to-engine routing map. Handle `SessionRedirect`.
- Login flow: connect to any engine, post-login handoff if needed.

**Verification:** Single gateway connects to two engines. Players log in, are routed to correct engine based on saved room. Cross-zone movement updates gateway routing. Gateway handles one engine going down (only affected sessions disconnect).

---

## What This Does NOT Cover (Future Work)

- **Dynamic zone rebalancing** — moving a zone from one engine to another at runtime without player disruption. Requires draining the zone (move all players out), transferring ownership, and reloading.
- **Zone splitting** — splitting a large zone across multiple engines. Would require sub-zone sharding and is unlikely to be needed (zones should be sized appropriately).
- **Automatic failover** — a standby engine claiming orphaned zones. Requires consensus (or Redis-based leader election) and state replay from persistence.
- **Cross-zone mob movement** — mobs currently can't wander across zone boundaries (exits are room-level, not zone-level). If a mob's exit leads to another engine's zone, it should stop at the boundary. This is already the implicit behavior since mobs don't cross zones in practice.
- **Cross-zone combat** — a player on Engine-A fighting a mob on Engine-B. This shouldn't happen: if the player is in a zone, the mobs in that zone are on the same engine. Combat is always zone-local.

---

## Deployment Examples

### Development (unchanged)
```bash
./gradlew run   # STANDALONE mode, all zones, one process
```

### Two-Engine Setup
```bash
# Terminal 1: Engine for hub + ruins
./gradlew run -Pconfig.ambonMUD.mode=ENGINE \
  -Pconfig.ambonMUD.sharding.enabled=true \
  -Pconfig.ambonMUD.sharding.zones=ambon_hub,demo_ruins \
  -Pconfig.ambonMUD.grpc.server.port=9090

# Terminal 2: Engine for resume zone
./gradlew run -Pconfig.ambonMUD.mode=ENGINE \
  -Pconfig.ambonMUD.sharding.enabled=true \
  -Pconfig.ambonMUD.sharding.zones=noecker_resume \
  -Pconfig.ambonMUD.grpc.server.port=9091

# Terminal 3: Gateway
./gradlew run -Pconfig.ambonMUD.mode=GATEWAY \
  -Pconfig.ambonMUD.gateway.engines.0.host=localhost \
  -Pconfig.ambonMUD.gateway.engines.0.port=9090 \
  -Pconfig.ambonMUD.gateway.engines.1.host=localhost \
  -Pconfig.ambonMUD.gateway.engines.1.port=9091
```

### Docker Compose (Production-like)
```yaml
services:
  redis:
    image: redis:7

  postgres:
    image: postgres:16

  engine-hub:
    build: .
    command: ["--mode=ENGINE", "--sharding.zones=ambon_hub,demo_ruins"]
    environment:
      AMBON_GRPC_PORT: 9090
      AMBON_REDIS_HOST: redis
      AMBON_PERSISTENCE_BACKEND: POSTGRES

  engine-resume:
    build: .
    command: ["--mode=ENGINE", "--sharding.zones=noecker_resume"]
    environment:
      AMBON_GRPC_PORT: 9090
      AMBON_REDIS_HOST: redis
      AMBON_PERSISTENCE_BACKEND: POSTGRES

  gateway:
    build: .
    command: ["--mode=GATEWAY"]
    ports:
      - "4000:4000"
      - "8080:8080"
    environment:
      AMBON_REDIS_HOST: redis
```

---

## Open Questions

1. **Item ownership during handoff.** When a player transfers engines, their inventory items transfer too. But items are currently tracked by `ItemRegistry` with room/player placement. The target engine needs to instantiate these items in its local `ItemRegistry`. Should we serialize full `ItemInstance` data in the handoff, or rely on item templates and just transfer IDs? Take whichever approach seems most reasonable.

3. **Start room assignment.** The `world.startRoom` is currently global. If new characters start in `ambon_hub`, then the engine owning `ambon_hub` must handle all new character creation. Is this acceptable, or should start room be configurable per engine?  All characters start in a generated room that prints some kind of welcome message and links to the world.startRoom?  "Thanks for joining us at AmbonMUD, proceed north to begin".  Each engine owns that room (lets us handle large influx of new users) and hands off to ambon_hub engine.
   - Follow-up proposal: `docs/replicated-entry-zone-plan.md`

4. **Redis as hard dependency.** The current architecture keeps Redis optional. Zone-based sharding effectively requires Redis (for ZoneRegistry, InterEngineBus, player location index). Should we accept Redis as a hard dependency for sharded mode, or design a Redis-free alternative (e.g., static config + direct gRPC)? Let's keep redis as a hard dependency if that's the easiest path at this point.

5. **Gossip ordering.** With multiple engines, `gossip` messages may arrive at different players in different orders (each engine processes the broadcast independently). Is this acceptable for a MUD, or do we need a total ordering guarantee (e.g., Redis Streams with a sequence number)? Let's keep ordering best effort (order doesn't matter)

6. **Metrics aggregation.** Each engine exposes its own `/metrics`. Should there be a unified metrics view, or is per-engine scraping (standard Prometheus federation) sufficient? Standard is good.

---

## Appendix: Current Cross-Zone Touchpoints

An audit of every place in the engine that accesses state potentially spanning multiple zones. These are the code paths that need sharding awareness:

| Code Path | File:Line | Cross-Zone? | Sharding Impact |
|-----------|-----------|-------------|-----------------|
| `Move` command | `CommandRouter.kt` | Yes (cross-zone exits) | Handoff protocol |
| `Look` at exit direction | `CommandRouter.kt` | No (looks at local exit target name only) | None |
| `Goto` staff command | `CommandRouter.kt` | Yes (arbitrary room) | Handoff protocol |
| `Transfer` staff command | `CommandRouter.kt` | Yes (arbitrary room + player) | Inter-engine transfer request |
| `Tell` command | `CommandRouter.kt` | Yes (any online player) | Player location index lookup |
| `Gossip` command | `CommandRouter.kt` | Yes (all players) | Inter-engine broadcast |
| `Who` command | `CommandRouter.kt` | Yes (all players) | Inter-engine who request/response |
| `Kick` staff command | `CommandRouter.kt` | Yes (any online player) | Inter-engine kick request |
| `Shutdown` staff command | `CommandRouter.kt` | Yes (all engines) | Inter-engine broadcast |
| `Spawn` staff command | `CommandRouter.kt` | No (spawns in current room) | None (always zone-local) |
| `Smite` staff command | `CommandRouter.kt` | No (current room only) | None |
| `Kill` command | `CommandRouter.kt` | No (current room only) | None |
| `Say`/`Emote` | `CommandRouter.kt` | No (current room only) | None |
| Mob wandering | `MobSystem.kt` | No (mobs stay in zone) | None |
| Combat rounds | `CombatSystem.kt` | No (same room required) | None |
| HP regen | `RegenSystem.kt` | No (per-player, zone-local) | None |
| Zone reset | `GameEngine.kt` | No (per-zone) | None |
| Login flow | `GameEngine.kt` | Possibly (saved room may be on another engine) | Post-login handoff |
| Persistence flush | `PersistenceWorker.kt` | No (shared backend) | None |
