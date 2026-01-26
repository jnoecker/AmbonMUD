AmbonMUD
========

AmbonMUD is a Kotlin MUD server (the runtime banner says "QuickMUD") built as a small, event-driven backend. It runs a telnet-compatible TCP server, loads YAML world data at startup, and persists players to disk.

Features
--------
- Tick-based game engine with NPC wandering and scheduled actions.
- Telnet transport with ANSI rendering, prompt handling, and backpressure protection.
- YAML-defined world data with multi-zone room IDs and validation on load.
- Basic player persistence to `data/players` via YAML files.
- Commands for movement, chat, inventory, and simple UI helpers.

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

2) Connect with telnet:

```bash
telnet localhost 4000
```

The server listens on port 4000 (see `src/main/kotlin/dev/ambon/Main.kt`).

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
- `name <newName>`: claim or log in to a character name.
- `tell <player> <msg>` or `t <player> <msg>`: private message.
- `gossip <msg>` or `gs <msg>`: broadcast to everyone.
- `inventory` / `inv` / `i`: show inventory.
- `get <item>` / `take <item>` / `pickup <item>` / `pick <item>` / `pick up <item>`: take item.
- `drop <item>`: drop item.
- `ansi on` / `ansi off`: toggle ANSI colors.
- `colors`: show ANSI demo (when ANSI is on).
- `clear`: clear screen (ANSI) or print a divider.
- `quit` or `exit`: disconnect.

World Data
----------
World files live in `src/main/resources/world` and are loaded by `WorldFactory.demoWorld()`. Each YAML file describes a zone:

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

Persistence
-----------
Player records are stored in `data/players` as YAML files. When you run `name <newName>`, the server either creates a new record or loads the existing one and places the player in their saved room.

Tests
-----
```bash
./gradlew test
```

Design Notes
------------
See `DesignDecisions.md` for architectural rationale and future-direction notes.
