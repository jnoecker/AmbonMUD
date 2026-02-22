AmbonMUD
========

AmbonMUD is a Kotlin MUD server (runtime banner: "AmbonMUD"). It is a production-quality, event-driven backend with telnet and WebSocket transports, data-driven world loading, tiered NPC combat, and a layered persistence stack with optional Redis caching.

Current State
-------------
- Tick-based engine with NPC wandering and scheduled actions.
- Three deployment modes: `STANDALONE` (single-process, default), `ENGINE` (game logic + gRPC server), `GATEWAY` (transports + gRPC client) for horizontal scaling.
- Dual transport support: native telnet and a browser WebSocket client (xterm.js; served by the server at `/` and `/ws`).
- Login flow with name + password (bcrypt), per-session state, and layered persistence (write-behind coalescing + optional Redis L2 cache).
- YAML-defined, multi-zone world with validation on load (optional zone `lifespan` resets to respawn mobs/items).
- Items and mobs loaded from world data; items can be in rooms or on mobs; inventory and equipment supported.
- Wearable items support basic `damage`, `armor`, and `constitution` stats with slots (head/body/hand).
- Tiered NPC system (weak/standard/elite/boss) with per-mob stat overrides, XP rewards, and loot tables.
- Combat with `kill <mob>` and `flee`, resolved over ticks (kills grant XP; players can level up).
- HP regeneration over time (regen interval scales with constitution + equipment).
- Chat and social commands (say, emote, tell, gossip), plus basic UI helpers (ANSI, clear, colors).
- Staff/admin commands (goto, transfer, spawn, smite, kick, shutdown) gated behind `isStaff` flag.
- Abstracted `InboundBus` / `OutboundBus` interfaces with Local, Redis, and gRPC implementations.
- Optional Redis integration: L2 player cache + pub/sub event bus (disabled by default).
- gRPC bidirectional streaming for gateway-to-engine communication with exponential-backoff reconnect.
- Snowflake-style session IDs for globally unique allocation across gateways, with overflow wait and clock-rollback hardening.
- Prometheus metrics endpoint (served by Ktor in `STANDALONE` / `GATEWAY` mode; standalone HTTP server in `ENGINE` mode).

Screenshots
-----------
Web client:
![Web client login](src/main/resources/screenshots/Login.png)
![Web client combat](src/main/resources/screenshots/Combat.png)

Requirements
------------
- JDK 17
- Gradle wrapper (included)

Run
---
1) Start the server:

```bash
./gradlew run
```

On Windows:

```powershell
.\gradlew.bat run
```

Or launch demo mode (auto-opens browser when supported):

```bash
./gradlew demo
```

On Windows:

```powershell
.\gradlew.bat demo
```

`demo` enables browser auto-launch by setting:

```text
config.override.ambonMUD.demo.autoLaunchBrowser=true
```

2) Connect with telnet:

```bash
telnet localhost 4000
```

3) Open the browser demo client:

```text
http://localhost:8080
```

By default the server listens on telnet port 4000 and web port 8080. These values come from `src/main/resources/application.yaml`.

Note: The web client loads xterm.js from a CDN. If you're offline, prefer telnet.

Configuration
-------------
Runtime config is loaded via Hoplite from `src/main/resources/application.yaml`.

Top-level key:

```yaml
ambonMUD:
  ...
```

Any config value can be overridden at runtime with a `-P` project property using the pattern `-Pconfig.<key>=<value>`:

```bash
./gradlew run -Pconfig.ambonMUD.server.telnetPort=5000
./gradlew run -Pconfig.ambonMUD.logging.level=DEBUG
./gradlew run -Pconfig.ambonMUD.logging.packageLevels.dev.ambon.transport=DEBUG
```

This works identically on Windows PowerShell with no quoting issues.

Most day-to-day tuning lives under:
- `ambonMUD.server` (ports, tick rates, channel capacities)
- `ambonMUD.world.resources` (which zone YAML resources to load)
- `ambonMUD.persistence.rootDir` (where player YAML data is written)
- `ambonMUD.persistence.worker` (write-behind flush interval, enable/disable)
- `ambonMUD.redis` (enabled, URI, cache TTL, pub/sub bus config)
- `ambonMUD.logging` (root log level and per-package overrides)

Login
-----
On connect, you will be prompted for a character name and password.
- Name rules: 2-16 characters, letters/digits/underscore, cannot start with a digit.
- Password rules: 1-72 characters.
- Existing characters require the correct password.
- Login banner text is loaded from `src/main/resources/login.txt`.
- Optional login banner styles are loaded from `src/main/resources/login.styles.yaml`.
- If the styles file is missing or invalid, the banner is shown without ANSI styling.

Commands
--------
**Movement / Look**
- `n`, `s`, `e`, `w`, `u`, `d` (or `north`, `south`, `east`, `west`, `up`, `down`): move.
- `look` or `l`: look around the current room.
- `look <direction>`: peek into a direction (e.g. `look north`).
- `exits` or `ex`: list exits in the current room.

**Communication**
- `say <msg>` or `'<msg>`: speak to the room.
- `emote <msg>`: perform an emote visible to the room.
- `who`: list online players.
- `tell <player> <msg>` or `t <player> <msg>`: private message.
- `gossip <msg>` or `gs <msg>`: broadcast to everyone.

**Items**
- `inventory` / `inv` / `i`: show inventory.
- `equipment` / `eq`: show worn items.
- `wear <item>` / `equip <item>`: wear an item from inventory.
- `remove <slot>` / `unequip <slot>`: remove an item from a slot (`head`, `body`, `hand`).
- `get <item>` / `take <item>` / `pickup <item>` / `pick <item>` / `pick up <item>`: take item.
- `drop <item>`: drop item.

**Combat**
- `kill <mob>`: engage a mob in combat.
- `flee`: end combat (you stay in the room).

**Character**
- `score` or `sc`: show character sheet (level, HP, XP, constitution, equipment stats).

**UI / Settings**
- `ansi on` / `ansi off`: toggle ANSI colors.
- `colors`: show ANSI demo (when ANSI is on).
- `clear`: clear screen (ANSI) or print a divider.
- `help` or `?`: list available commands.
- `quit` or `exit`: disconnect.

**Staff / Admin** *(requires `isStaff: true` on the player record)*
- `goto <room>`: teleport to a room (`zone:room`, bare `room` for current zone, `zone:` for zone start).
- `transfer <player> <room>`: move a player to a room.
- `spawn <mob-template>`: spawn a mob by template ID.
- `smite <player|mob>`: instantly kill a player or mob.
- `kick <player>`: disconnect a player.
- `shutdown`: gracefully shut down the server.

World Data
----------
World files live in `src/main/resources/world` and are loaded by `dev.ambon.domain.world.WorldFactory`. Each YAML file describes a zone; multiple zones are merged into a single world.

Detailed format/validation rules for generators are documented in `docs/world-zone-yaml-spec.md`.

```yaml
zone: demo
startRoom: trailhead
mobs:
  wolf:
    name: "a wary wolf"
    room: trailhead
items:
  lantern:
    displayName: "a brass lantern"
    description: "A brass lantern with soot-stained glass."
    room: trailhead
rooms:
  trailhead:
    title: "Forest Trailhead"
    description: "A narrow trail slips beneath ancient boughs."
    exits:
      north: mossy_path
```

Notes:
- Room IDs and exit targets can be local (`trailhead`) or fully qualified (`zone:trailhead`).
- `mobs` and `items` are optional; `rooms` and `startRoom` are required.
- Items may be placed in a `room` or on a `mob` (not both).
- Items may define `slot` (`head`, `body`, `hand`) and optional `damage`/`armor`/`constitution` stats.
- Exit directions support `north/south/east/west/up/down` in world files.
- Optional `lifespan` is in minutes; zones with `lifespan > 0` periodically reset mob/item spawns at runtime.

Persistence
-----------
Player records are stored as YAML under `data/players/players/` (configurable via `ambonMUD.persistence.rootDir`). IDs are allocated in `data/players/next_player_id.txt`. On login, the server loads or creates the player record and places the player in their saved room.

The persistence stack has three layers:

```
WriteCoalescingPlayerRepository  ← dirty-flag write-behind (configurable flush interval)
  ↓
RedisCachingPlayerRepository     ← L2 cache (if redis.enabled = true)
  ↓
YamlPlayerRepository             ← durable YAML files, atomic writes
```

Redis caching is disabled by default. Enable it with:

```yaml
ambonMUD:
  redis:
    enabled: true
    uri: "redis://localhost:6379"
    cacheTtlSeconds: 3600
```

To grant staff/admin access to a player, manually add `isStaff: true` to their YAML record at `data/players/players/<name>.yaml`.

Tests
-----
```bash
./gradlew test
```

Formatting / Lint
-----------------
```bash
./gradlew ktlintCheck
```

Observability (Docker + Grafana)
--------------------------------
This repo includes a Prometheus + Grafana stack for local metrics dashboards.

1) Start the stack:

```bash
docker compose up -d
```

2) Open Grafana:

```text
http://localhost:3000
```

Login:
- Username: `admin`
- Password: `admin`

Grafana dashboards during load testing:
![Grafana dashboard view 1](src/main/resources/screenshots/Dashboard1.png)
![Grafana dashboard view 2](src/main/resources/screenshots/Dashboard2.png)
![Grafana dashboard view 3](src/main/resources/screenshots/Dashboard3.png)

Scalability Roadmap
-------------------
The codebase follows a four-phase scalability plan. Phases 1–4 are implemented:

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | Abstract `InboundBus`/`OutboundBus` interfaces; extract `SessionIdFactory` | ✅ Done |
| 2 | Async persistence worker with write-behind coalescing | ✅ Done |
| 3 | Redis L2 player cache + pub/sub event bus | ✅ Done |
| 4 | gRPC gateway split for true horizontal scaling | ✅ Done |

See `docs/scalability-plan-brainstorm.md` for detailed design and `DesignDecisions.md` for rationale.

Design Notes
------------
See `DesignDecisions.md` for architectural rationale and future-direction notes.
