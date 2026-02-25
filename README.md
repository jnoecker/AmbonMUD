AmbonMUD
========

AmbonMUD is a Kotlin MUD server (runtime banner: "AmbonMUD"). It is a production-quality, event-driven backend with telnet and WebSocket transports, data-driven world loading, class-based character progression, a spell/ability system with status effects (DoT, HoT, STUN, ROOT, SHIELD), tiered NPC combat, a shop/economy system, GMCP structured data, and a layered persistence stack with selectable YAML or PostgreSQL backends and optional Redis caching.

Current State
-------------
- Tick-based engine with NPC wandering, scheduled actions, and individual mob respawn timers.
- Three deployment modes: `STANDALONE` (single-process, default), `ENGINE` (game logic + gRPC server), `GATEWAY` (transports + gRPC client) for horizontal scaling.
- Zone-based engine sharding with zone registry, inter-engine messaging, player handoff protocol, player-location index for O(1) cross-engine `tell` routing, and zone instancing (layering) for hot-zone load distribution.
- Dual transport support: native telnet (with NAWS/TTYPE negotiation) and a browser WebSocket client (xterm.js; served by the server at `/` and `/ws`).
- GMCP (Generic MUD Communication Protocol) support: structured JSON data sent alongside text output, with GMCP-aware panels in the web client (vitals, room info, inventory, skills, room players, communication channels).
- Login flow with name + password (bcrypt), race selection (Human, Elf, Dwarf, Halfling), class selection (Warrior, Mage, Cleric, Rogue), per-session state, and layered persistence (write-behind coalescing + optional Redis L2 cache).
- Six primary attributes (STR, DEX, CON, INT, WIS, CHA) with race-based modifiers and mechanical effects on combat (STR scales melee damage, DEX adds dodge chance, INT scales spell damage, CON scales HP regen, WIS scales mana regen).
- YAML-defined, multi-zone world with validation on load (optional zone `lifespan` resets to respawn mobs/items). Eight zones: tutorial glade, central hub, resume showcase, ancient ruins, and four low-level training zones (marsh, highlands, mines, barrens).
- Consumable items with charges and on-use effects (`healHp`, `grantXp`); `use <item>` command.
- Wearable items with `damage`, `armor`, and `constitution` stats and slots (head/body/hand).
- Tiered NPC system (weak/standard/elite/boss) with per-mob stat overrides, XP rewards, gold drops (`goldMin`/`goldMax`), loot tables, and individual respawn timers.
- Gold currency system: mobs drop gold on death, items have a `basePrice` for shop pricing, players carry a persistent gold balance.
- Shop system: shops defined per-room in zone YAML (`shops` map), `buy`/`sell`/`list` commands, configurable buy/sell price multipliers (`engine.economy.*`).
- Spell/ability system with mana pool, mana regen, cooldowns, class-specific abilities (12 abilities across 4 classes), and auto-learn on level-up.
- Status effect system: DoT, HoT, STAT_BUFF/DEBUFF, STUN, ROOT, SHIELD types; configurable stacking rules; player and mob targets; `effects`/`buffs`/`debuffs` command; `Char.StatusEffects` GMCP package.
- Combat with `kill <mob>`, `flee`, and `cast <spell> [target]`; attribute-based damage scaling, dodge mechanics, stun/root handling, and spell damage that bypasses mob armor.
- HP and mana regeneration over time (HP regen scales with CON + equipment; mana regen scales with WIS).
- Rich social/communication commands: say, emote, tell, gossip, whisper, shout, ooc (out-of-character), pose, and give.
- Staff/admin commands (goto, transfer, spawn, smite, kick, shutdown) gated behind `isStaff` flag.
- Abstracted `InboundBus` / `OutboundBus` interfaces with Local, Redis, and gRPC implementations.
- Optional Redis integration: L2 player cache + pub/sub event bus with HMAC-signed envelopes (disabled by default).
- gRPC bidirectional streaming for gateway-to-engine communication with exponential-backoff reconnect.
- Multi-engine gateway support with session routing for sharded deployments.
- Snowflake-style session IDs for globally unique allocation across gateways, with overflow wait and clock-rollback hardening.
- Prometheus metrics endpoint (served by Ktor in `STANDALONE` / `GATEWAY` mode; standalone HTTP server in `ENGINE` mode).
- Swarm load-testing module (`:swarm`) for bot-driven stress tests.

Screenshots
-----------
Web client:
![Web client login](src/main/resources/screenshots/Login.png)
![Web client combat](src/main/resources/screenshots/Combat.png)

Requirements
------------
- JDK 21 (CI runs on Java 21; the Gradle toolchain targets 21)
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
- `ambonMUD.persistence.backend` (`YAML` or `POSTGRES` — default `YAML`)
- `ambonMUD.persistence.rootDir` (where player YAML data is written, YAML backend only)
- `ambonMUD.persistence.worker` (write-behind flush interval, enable/disable)
- `ambonMUD.database` (jdbcUrl, username, password, pool size — defaults match docker compose)
- `ambonMUD.redis` (enabled, URI, cache TTL, pub/sub bus config, shared-secret for HMAC)
- `ambonMUD.engine.abilities` (spell/ability definitions, class-specific abilities)
- `ambonMUD.sharding` (zone-based engine sharding, instancing, player-location index)
- `ambonMUD.logging` (root log level and per-package overrides)

Login
-----
On connect, you will be prompted for a character name and password. New characters also choose a race and class.
- Name rules: 2-16 characters, letters/digits/underscore, cannot start with a digit.
- Password rules: 1-72 characters.
- Race choices: Human, Elf, Dwarf, Halfling — each with attribute modifiers (STR/DEX/CON/INT/WIS/CHA).
- Class choices: Warrior, Mage, Cleric, Rogue — each with different HP/mana scaling per level and class-specific abilities.
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
- `whisper <player> <msg>` or `wh <player> <msg>`: whisper to a player in the same room.
- `shout <msg>` or `sh <msg>`: shout to all players.
- `ooc <msg>`: out-of-character chat.
- `pose <msg>` or `po <msg>`: pose/emote variant.

**Items**
- `inventory` / `inv` / `i`: show inventory.
- `equipment` / `eq`: show worn items.
- `wear <item>` / `equip <item>`: wear an item from inventory.
- `remove <slot>` / `unequip <slot>`: remove an item from a slot (`head`, `body`, `hand`).
- `get <item>` / `take <item>` / `pickup <item>` / `pick <item>` / `pick up <item>`: take item.
- `drop <item>`: drop item.
- `use <item>`: use a consumable item (potions, etc.).
- `give <item> <player>`: give an item to another player in the room.

**Combat & Abilities**
- `kill <mob>`: engage a mob in combat.
- `flee`: end combat (you stay in the room).
- `cast <spell> [target]` or `c <spell> [target]`: cast a spell (damage spells need a target; self-heals do not).
- `spells` or `abilities`: list your known spells with mana cost, cooldown, and description.
- `effects`, `buffs`, or `debuffs`: list active status effects on your character.

**Character**
- `score` or `sc`: show character sheet (level, HP, mana, XP, gold, attributes, race, class, equipment stats).

**Economy**
- `gold` or `balance`: show your current gold balance.
- `list` or `shop`: list items for sale in the current room (when a shop is present).
- `buy <item>`: purchase an item from the shop in the current room.
- `sell <item>`: sell an item from your inventory to the shop.

**Zone Instancing**
- `phase` or `layer`: list available zone instances.
- `phase <target>`: switch to a specific zone instance (by player name or instance number).

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
World files live in `src/main/resources/world` and are loaded by `dev.ambon.domain.world.load.WorldLoader`. Each YAML file describes a zone; multiple zones are merged into a single world.

Current zones (8 files):

| Zone | File | Description |
|------|------|-------------|
| `tutorial_glade` | `tutorial_glade.yaml` | Starting area for new players |
| `ambon_hub` | `ambon_hub.yaml` | Central hub connecting all zones |
| `noecker_resume` | `noecker_resume.yaml` | Resume showcase zone |
| `demo_ruins` | `demo_ruins.yaml` | Ancient ruins with varied content |
| `low_training_marsh` | `low_training_marsh.yaml` | Low-level training zone (marsh) |
| `low_training_highlands` | `low_training_highlands.yaml` | Low-level training zone (highlands) |
| `low_training_mines` | `low_training_mines.yaml` | Low-level training zone (mines) |
| `low_training_barrens` | `low_training_barrens.yaml` | Low-level training zone (barrens) |

Detailed format/validation rules for generators are documented in `docs/world-zone-yaml-spec.md`.

```yaml
zone: demo
startRoom: trailhead
mobs:
  wolf:
    name: "a wary wolf"
    room: trailhead
    respawnSeconds: 60
items:
  lantern:
    displayName: "a brass lantern"
    description: "A brass lantern with soot-stained glass."
    room: trailhead
  potion:
    displayName: "a healing potion"
    description: "A small vial of red liquid."
    consumable: true
    room: trailhead
    onUse:
      healHp: 10
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
- Items may be placed in a `room` (or left unplaced).
- Items may define `slot` (`head`, `body`, `hand`) and optional `damage`/`armor`/`constitution` stats.
- Items support `basePrice` (integer, default 0) for shop pricing; 0 means not buyable/sellable.
- Consumable items support `onUse` effects (`healHp`, `grantXp`) and optional `charges`.
- Mobs support `respawnSeconds` for individual respawn timers independent of zone resets.
- Mobs support `goldMin`/`goldMax` for gold drops on kill; computed from tier formula if omitted.
- Mobs support a `drops` list for item loot tables (each entry: `itemId` + `chance`).
- Shops are defined with a `shops` map at the zone level; each shop is bound to a room.
- Exit directions support `north/south/east/west/up/down` in world files.
- Optional `lifespan` is in minutes; zones with `lifespan > 0` periodically reset mob/item spawns at runtime.

Persistence
-----------
AmbonMUD supports two player persistence backends, selected via `ambonMUD.persistence.backend`:

| Backend | Description |
|---------|-------------|
| `YAML` (default) | Player records stored as YAML files under `data/players/`. No external infrastructure needed. |
| `POSTGRES` | Player records stored in a PostgreSQL database. Schema managed by Flyway migrations (V1–V4). |

The persistence stack has three layers regardless of backend:

```
WriteCoalescingPlayerRepository  <- dirty-flag write-behind (configurable flush interval)
  |
RedisCachingPlayerRepository     <- L2 cache (if redis.enabled = true)
  |
YamlPlayerRepository             <- durable YAML files, atomic writes
  -- or --
PostgresPlayerRepository         <- Exposed DSL + HikariCP connection pool
```

Player records include: name, race, class, six primary attributes (STR/DEX/CON/INT/WIS/CHA), level, XP, HP/mana, room, staff flag, and timestamps.

### YAML Backend (default)

No additional configuration needed. Player files are written to `data/players/players/` (configurable via `ambonMUD.persistence.rootDir`). IDs are allocated in `data/players/next_player_id.txt`.

### PostgreSQL Backend

Requires a running PostgreSQL instance. The database connection defaults (`localhost:5432/ambonmud`, user `ambon`, password `ambon`) match the docker compose stack, so you only need to flip the backend flag:

```bash
./gradlew run -Pconfig.ambonMUD.persistence.backend=POSTGRES
```

Or in `application.yaml`:

```yaml
ambonMUD:
  persistence:
    backend: POSTGRES
```

To connect to a different Postgres instance, override the `database.*` keys:

```bash
./gradlew run -Pconfig.ambonMUD.persistence.backend=POSTGRES \
              -Pconfig.ambonMUD.database.jdbcUrl=jdbc:postgresql://myhost:5432/mydb \
              -Pconfig.ambonMUD.database.username=myuser \
              -Pconfig.ambonMUD.database.password=mypass
```

Flyway runs migrations automatically on startup. Schema files live in `src/main/resources/db/migration/`.

### Redis Caching (optional, works with either backend)

Redis caching is disabled by default. The connection URI defaults to `redis://localhost:6379` (matching docker compose), so just flip the flag:

```bash
./gradlew run -Pconfig.ambonMUD.redis.enabled=true
```

### Staff Access

To grant staff/admin access to a player:
- **YAML backend:** add `isStaff: true` to their record at `data/players/players/<id>.yaml`
- **Postgres backend:** set `is_staff = true` on their row in the `players` table

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

Docker Compose (Full Infrastructure)
-------------------------------------
The `docker-compose.yml` brings up all optional infrastructure in one command:

| Service | Image | Port | Purpose |
|---------|-------|------|---------|
| Prometheus | `prom/prometheus:v2.51.2` | 9090 | Metrics scraping |
| Grafana | `grafana/grafana:10.4.2` | 3000 | Dashboards |
| Redis | `redis:7-alpine` | 6379 | L2 player cache + pub/sub event bus |
| PostgreSQL | `postgres:16-alpine` | 5432 | Player persistence (alternative to YAML) |

1) Start the stack:

```bash
docker compose up -d
```

2) Run with the PostgreSQL backend (connection defaults already match the compose stack):

```bash
./gradlew run -Pconfig.ambonMUD.persistence.backend=POSTGRES
```

Flyway runs migrations automatically on first startup — no manual schema setup needed.

3) Run with Redis caching enabled:

```bash
./gradlew run -Pconfig.ambonMUD.redis.enabled=true
```

4) Open Grafana:

```text
http://localhost:3000
```

Login:
- Username: `admin`
- Password: `admin`

Data is persisted in a Docker named volume (`pgdata`). To wipe the database and start fresh, run `docker compose down -v`.

Grafana dashboards during load testing:
![Grafana dashboard view 1](src/main/resources/screenshots/Dashboard1.png)
![Grafana dashboard view 2](src/main/resources/screenshots/Dashboard2.png)
![Grafana dashboard view 3](src/main/resources/screenshots/Dashboard3.png)

Scalability Roadmap
-------------------
The codebase follows a phased scalability plan:

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | Abstract `InboundBus`/`OutboundBus` interfaces; extract `SessionIdFactory` | Done |
| 2 | Async persistence worker with write-behind coalescing | Done |
| 3 | Redis L2 player cache + pub/sub event bus (HMAC-signed envelopes) | Done |
| 4 | gRPC gateway split for true horizontal scaling | Done |
| 5 | Zone-based engine sharding (zone registry, inter-engine bus, player handoff, player-location index, zone instancing) | Done |

See `docs/engine-sharding-design.md` for the sharding architecture, `docs/DesignDecisions.md` for rationale, and `docs/scalability-plan-brainstorm.md` for the historical phase-by-phase plan.

Load Testing
------------
The `swarm/` directory contains a Kotlin-based load testing module:

```bash
./gradlew :swarm:run --args="--config example.swarm.yaml"
```

See `swarm/README.md` for configuration and usage.

Design Notes
------------
See `docs/DesignDecisions.md` for architectural rationale and future-direction notes.
