AmbonMUD
========

AmbonMUD is a Kotlin MUD server (runtime banner: "QuickMUD"). It is a small, event-driven backend with telnet and WebSocket transports, data-driven world loading, and YAML-backed player persistence.

Current State
-------------
- Single-process server with a tick-based engine, NPC wandering, and scheduled actions.
- Dual transport support: native telnet and browser WebSocket client (xterm.js).
- Login flow with name + password (bcrypt), per-session state, and basic persistence.
- YAML-defined, multi-zone world with validation on load.
- Items and mobs loaded from world data; items can be in rooms or on mobs; inventory and equipment supported.
- Wearable items support basic `damage` and `armor` stats with slots (head/body/hand).
- Basic combat with `kill <mob>` and `flee`, resolved over ticks.
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

2) Connect with telnet:

```bash
telnet localhost 4000
```

3) Open the browser demo client:

```text
http://localhost:8080
```

The server listens on telnet port 4000 and web port 8080 (see `src/main/kotlin/dev/ambon/Main.kt`).

Login
-----
On connect, you will be prompted for a character name and password.
- Name rules: 2-16 characters, letters/digits/underscore, cannot start with a digit.
- Password rules: 1-72 characters.
- Existing characters require the correct password.

Commands
--------
- `help` or `?`: list available commands.
- `look` or `l`: look around the current room.
- `look <direction>`: peek into a direction (e.g. `look north`).
- `n`, `s`, `e`, `w` (or `north`, `south`, `east`, `west`): move.
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
- Items may define `slot` (`head`, `body`, `hand`) and optional `damage`/`armor` stats.
- Exit directions support `north/south/east/west/up/down` in world files.

Persistence
-----------
Player records are stored as YAML in `data/players/players`. IDs are allocated in `data/players/next_player_id.txt`. On login, the server loads or creates the player record and places the player in their saved room.

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
- Advanced combat, stats, or character progression.
- Admin tools or in-game world editing.
- Multi-process scaling or persistence beyond YAML.

Design Notes
------------
See `DesignDecisions.md` for architectural rationale and future-direction notes.
