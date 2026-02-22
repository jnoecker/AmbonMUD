AmbonMUD
========

AmbonMUD is a Kotlin/JVM MUD server with a single-threaded game engine, telnet and WebSocket transports, YAML world content, and a layered persistence stack.

Current Highlights
------------------
- Single-process event loop (`GameEngine`) with bounded per-tick work.
- Dual client support:
  - Telnet on `4000` (default)
  - Web client + WebSocket on `8080` (default)
- Semantic event boundaries:
  - Engine uses `InboundEvent` / `OutboundEvent`
  - Transport renders protocol bytes and ANSI
- Login/account flow with bcrypt password checks and takeover handling.
- Tiered NPC system (weak/standard/elite/boss) with per-mob stat overrides, XP rewards, and loot tables.
- Combat, regen, scheduler, and zone lifespan resets.
- Inventory/equipment and stat modifiers (`damage`, `armor`, `constitution`).
- Layered persistence:
  - `WriteCoalescingPlayerRepository` (optional worker)
  - `RedisCachingPlayerRepository` (optional)
  - `YamlPlayerRepository` (durable store)
- Optional Redis integration:
  - L2 player cache
  - Pub/sub event buses (`RedisInboundBus`, `RedisOutboundBus`)
- Prometheus metrics endpoint (enabled by default) and local Grafana stack via Docker Compose.

Screenshots
-----------
Web client:
![Web client login](src/main/resources/screenshots/Login.png)
![Web client combat](src/main/resources/screenshots/Combat.png)

Grafana dashboards:
![Grafana dashboard view 1](src/main/resources/screenshots/Dashboard1.png)
![Grafana dashboard view 2](src/main/resources/screenshots/Dashboard2.png)
![Grafana dashboard view 3](src/main/resources/screenshots/Dashboard3.png)

Requirements
------------
- JDK 17 (Gradle toolchain target)
- Gradle wrapper (included)

Quick Start
-----------
Start server (Unix):

```bash
./gradlew run
```

Start server (Windows PowerShell):

```powershell
.\gradlew.bat run
```

Run demo mode (auto-launch browser when supported):

```bash
./gradlew demo
```

```powershell
.\gradlew.bat demo
```

Connect:
- Telnet: `telnet localhost 4000`
- Browser: `http://localhost:8080`

Notes:
- Defaults come from `src/main/resources/application.yaml`.
- The web client loads xterm.js from a CDN; telnet works fully offline.

Configuration
-------------
Config schema lives in `src/main/kotlin/dev/ambon/config/AppConfig.kt`.
Defaults live in `src/main/resources/application.yaml`.

Top-level key:

```yaml
ambonMUD:
  ...
```

Runtime overrides use `-Pconfig.<path>=<value>`:

```bash
./gradlew run -Pconfig.ambonMUD.server.telnetPort=5000
./gradlew run -Pconfig.ambonMUD.logging.level=DEBUG
./gradlew run -Pconfig.ambonMUD.engine.mob.maxMovesPerTick=25
./gradlew run -Pconfig.ambonMUD.redis.enabled=true
```

High-use sections:
- `ambonMUD.server` (ports, tick rate, channel capacities)
- `ambonMUD.world.resources` (zone file list)
- `ambonMUD.persistence` (root path + worker)
- `ambonMUD.redis` (cache + bus)
- `ambonMUD.engine.*` (mob/combat/regen/scheduler caps)
- `ambonMUD.observability` (metrics endpoint)
- `ambonMUD.logging` (root and package log levels)

Architecture At A Glance
------------------------

```text
Transports (telnet + WebSocket)
  -> decode input into InboundEvent
  -> render OutboundEvent to session output
        |
        v
InboundBus / OutboundBus (Local* or Redis*)
        |
        v
GameEngine (single-threaded tick loop)
  - login FSM
  - command routing
  - combat, mobs, regen
  - scheduler
  - zone resets
        |
        v
OutboundRouter
  - per-session queue routing
  - ANSI/plain rendering
  - prompt coalescing
  - backpressure disconnects
```

Core boundary rules:
- Engine does not know sockets/web protocols.
- Transport does not own gameplay state.
- ANSI/control output remains semantic in engine (`SetAnsi`, `ClearScreen`, `ShowAnsiDemo`).

Login
-----
On connect, players enter a login state machine.

Validation rules:
- Name: `2..16` chars, alnum/underscore, cannot start with digit.
- Password: non-blank, max `72` chars (bcrypt-safe).

Behavior:
- Existing player: password required.
- New player: create flow with confirmation.
- Same-name takeover: old session is disconnected, session state remaps to new connection.

Command Reference
-----------------
Movement / world:
- `n/s/e/w/u/d` and `north/south/east/west/up/down`
- `look` / `l`
- `look <direction>`
- `exits` / `ex`

Communication:
- `say <msg>` or `'<msg>`
- `emote <msg>`
- `who`
- `tell <player> <msg>` / `t <player> <msg>`
- `gossip <msg>` / `gs <msg>`

Items and gear:
- `inventory` / `inv` / `i`
- `equipment` / `eq`
- `wear <item>` / `equip <item>`
- `remove <head|body|hand>` / `unequip <head|body|hand>`
- `get <item>` / `take <item>` / `pickup <item>` / `pick <item>` / `pick up <item>`
- `drop <item>`

Combat and character:
- `kill <mob>`
- `flee`
- `score` / `sc`

UI / session:
- `ansi on` / `ansi off`
- `colors`
- `clear`
- `help` / `?`
- `quit` / `exit`

Staff commands (`isStaff: true`):
- `goto <zone:room | room | zone:>`
- `transfer <player> <room>`
- `spawn <mob-template>`
- `smite <player|mob>`
  - Player target: removes from combat, sets HP to 1, moves to world start.
  - Mob target: removes mob from room.
- `kick <player>`
- `shutdown`

World Data
----------
World files live in `src/main/resources/world`.
The default world list is configured in `application.yaml` under `ambonMUD.world.resources`.

Format and validation contract:
- `docs/world-zone-yaml-spec.md`
- Loader: `src/main/kotlin/dev/ambon/domain/world/load/WorldLoader.kt`

Key rules:
- IDs are namespaced (`zone:id`) after normalization.
- Cross-zone exits are supported.
- Item placement supports `room` or unplaced; `mob` placement is deprecated and rejected.
- Mob loot uses `mobs.<id>.drops` with `itemId` + `chance`.
- Zone `lifespan` is in minutes; `lifespan > 0` enables runtime resets.

Persistence
-----------
Durable data is YAML under `data/players` by default:
- Player files: `data/players/players/<zero-padded-id>.yaml`
- ID allocator: `data/players/next_player_id.txt`

Repository layering (outermost to innermost):

```text
WriteCoalescingPlayerRepository   (optional, async write-behind)
  -> RedisCachingPlayerRepository (optional, if redis.enabled=true)
    -> YamlPlayerRepository       (durable, atomic file writes)
```

`PlayerRegistry` always uses the `PlayerRepository` abstraction and does not know which wrappers are active.

Granting staff access:
- Set `isStaff: true` in the relevant player file (`data/players/players/<id>.yaml`).

Redis Integration
-----------------
Redis is optional and disabled by default.

Enable cache:

```yaml
ambonMUD:
  redis:
    enabled: true
    uri: redis://localhost:6379
    cacheTtlSeconds: 3600
```

Enable pub/sub buses:

```yaml
ambonMUD:
  redis:
    enabled: true
    bus:
      enabled: true
      inboundChannel: ambon:inbound
      outboundChannel: ambon:outbound
      instanceId: ""
```

Notes:
- Bus mode is currently marked experimental in startup logs.
- Redis failures degrade to best-effort behavior; engine process should keep running.

Observability
-------------
Prometheus metrics are exposed on the web server at:
- Default: `http://localhost:8080/metrics`
- Configurable: `ambonMUD.observability.metricsEndpoint`

Local dashboard stack:

```bash
docker compose up -d
```

Grafana:
- URL: `http://localhost:3000`
- User: `admin`
- Password: `admin`

Developer Workflow
------------------
Run tests:

```bash
./gradlew test
```

Run lint:

```bash
./gradlew ktlintCheck
```

CI parity:

```bash
./gradlew ktlintCheck test
```

Windows equivalents:

```powershell
.\gradlew.bat test
.\gradlew.bat ktlintCheck
.\gradlew.bat ktlintCheck test
```

Scalability Status
------------------
Scalability plan phases:

| Phase | Description | Status |
|------|-------------|--------|
| 1 | Bus abstraction + session ID factory | Done |
| 2 | Async write-behind persistence worker | Done |
| 3 | Redis L2 cache + Redis event bus | Done |
| 4 | Gateway/engine split (gRPC) | Planned |

Related Docs
------------
- `AGENTS.md` - engineering playbook and invariants
- `docs/onboarding.md` - developer onboarding
- `DesignDecisions.md` - architectural rationale
- `docs/scalability-plan-brainstorm.md` - roadmap and implementation status
- `docs/world-zone-yaml-spec.md` - world content format contract