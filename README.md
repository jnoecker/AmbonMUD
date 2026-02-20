AmbonMUD
========

AmbonMUD is a Kotlin MUD server (runtime banner: "AmbonMUD"). It is a small, event-driven backend with telnet and WebSocket transports, data-driven world loading, and YAML-backed player persistence.

Current State
-------------
- Single-process server with a tick-based engine, NPC wandering, and scheduled actions.
- Dual transport support: native telnet and a browser WebSocket client (xterm.js; served by the server at `/` and `/ws`).
- Login flow with name + password (bcrypt), per-session state, and basic persistence.
- YAML-defined, multi-zone world with validation on load (optional zone `lifespan` resets to respawn mobs/items).
- Items and mobs loaded from world data; items can be in rooms or on mobs; inventory and equipment supported.
- Wearable items support basic `damage`, `armor`, and `constitution` stats with slots (head/body/hand).
- Basic combat with `kill <mob>` and `flee`, resolved over ticks (kills grant XP; players can level up).
- HP regeneration over time (regen interval scales with constitution + equipment).
- Chat and social commands (say, emote, tell, gossip), plus basic UI helpers (ANSI, clear, colors).

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
- `help` or `?`: list available commands.
- `look` or `l`: look around the current room.
- `look <direction>`: peek into a direction (e.g. `look north`).
- `n`, `s`, `e`, `w`, `u`, `d` (or `north`, `south`, `east`, `west`, `up`, `down`): move.
- `exits` or `ex`: list exits in the current room.
- `say <msg>` or `'<msg>`: speak to the room.
- `emote <msg>`: perform an emote visible to the room.
- `who`: list online players.
- `tell <player> <msg>` or `t <player> <msg>`: private message.
- `gossip <msg>` or `gs <msg>`: broadcast to everyone.
- `inventory` / `inv` / `i`: show inventory.
- `equipment` / `eq`: show worn items.
- `wear <item>` / `equip <item>`: wear an item from inventory.
- `remove <slot>` / `unequip <slot>`: remove an item from a slot (`head`, `body`, `hand`).
- `get <item>` / `take <item>` / `pickup <item>` / `pick <item>` / `pick up <item>`: take item.
- `drop <item>`: drop item.
- `kill <mob>`: engage a mob in combat.
- `flee`: end combat (you stay in the room).
- `ansi on` / `ansi off`: toggle ANSI colors.
- `colors`: show ANSI demo (when ANSI is on).
- `clear`: clear screen (ANSI) or print a divider.
- `quit` or `exit`: disconnect.

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
Player records are stored as YAML under `data/players/players` (configurable via `ambonMUD.persistence.rootDir`). IDs are allocated in `data/players/next_player_id.txt`. On login, the server loads or creates the player record and places the player in their saved room.

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

Out of Scope (Yet)
------------------
- Rich character sheet/stats UI (for example `score`) beyond the current combat + leveling messages.
- Advanced combat tuning, per-mob loot/XP tables, or deeper stat systems.
- Admin tools or in-game world editing.
- Multi-process scaling or persistence beyond YAML.

Design Notes
------------
See `DesignDecisions.md` for architectural rationale and future-direction notes.
