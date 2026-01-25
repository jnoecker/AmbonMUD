# AmbonMUD

AmbonMUD is a modern Kotlin-based MUD (Multi-User Dungeon) server, inspired by classic ROM/QuickMUD systems of the late 90s, but built with contemporary architecture, testing discipline, and extensibility in mind.

This project is both:
- a playable text-based multiplayer game server, and
- an exploration of clean, testable backend design for long-lived, stateful systems.

## ✨ Features (v0.4)

### Core Gameplay
- **Telnet-based client support** (with a clean path to WebSockets / SSH later)
- **Multi-user sessions** with per-player state and concurrency
- **Room-based world** with exits and movement
  - Support for `north`, `south`, `east`, `west`, `up`, `down`
  - Directional looking: `look <direction>`
  - Quick exits listing: `exits`

### Communication & Social
- **Public Chat:** `say <message>` (or `' <message>`) to talk to everyone in your room
- **Emotes:** `emote <action>` for roleplaying
- **Global Chat:** `gossip <message>` (or `gs`) to broadcast to everyone online
- **Private Messaging:** `tell <player> <message>` (or `t`) for private conversations
- **Player List:** `who` to see who is currently online

### Player System
- **Session-based players** with default names upon connection
- **Identity:** `name <newName>` command to claim an identity
  - Case-insensitive unique names
  - Name validation rules (length, characters, etc.)
- **In-memory online player registry** with persistence hooks

### Persistence
- **YAML-backed persistence** for players
- **Automatically saves:** player name, current room, ANSI settings, and timestamps
- **State Recovery:** Logging back in via `name <existingName>` restores your saved character state and location
- **No accounts/passwords yet** (intentional MVP choice for easy exploration)

### World System
- **World data loaded from YAML** (no recompiling required)
- **Multi-zone support:** Namespaced room IDs (`zone:room`)
- **Validation on load:**
  - Start room must exist
  - Exits must point to valid rooms (across zones too!)
  - Direction aliases and multi-zone links supported

### Terminal UX
- **ANSI color support** (toggleable per session with `ansi on/off`)
- **Screen management:** `clear` screen support
- **Color demo:** `colors` to test your terminal's compatibility
- **Prompt coalescing** to prevent spam during high-traffic events
- **Graceful fallback** for non-ANSI clients

## Architecture Highlights

- **Explicit game loop** with tick timing and non-blocking I/O
- **Coroutine-based event handling** via Kotlin Channels
- **Clean Separation of Concerns:**
  - `transport`: Telnet protocol, ANSI rendering, line decoding
  - `engine`: Event loop, command routing, session lifecycle
  - `domain`: World model, room logic, player state
  - `persistence`: Pluggable YAML-based repository
- **Constructor-based DI:** No heavy frameworks; everything is injected for maximum testability.

## 🧪 Testing Philosophy

This project is heavily tested for a game server:
- **Unit tests** for command parsing, naming rules, movement logic, and world validation.
- **Integration tests** for engine → outbound events and ANSI behavior.
- **Regression prevention:** Real bugs encountered during development are codified into tests.

## 🚀 Running the Server

### Build
```bash
./gradlew build
```

### Run
```bash
./gradlew run
```

### Connect
Connect via any telnet client:
```bash
telnet localhost 4000
```

## 🗺️ World Data Format (Example)
```yaml
zone: demo
startRoom: demo:foyer

rooms:
  demo:foyer:
    title: The Foyer
    description: A small, quiet foyer lit by torchlight.
    exits:
      north: demo:hall
      up: demo:attic

  hall: 
    title: A Quiet Hallway
    description: The hall stretches into darkness.
    exits:
      south: demo:foyer
```

- Rooms are identified by their zone and ID in the format `zone:room`.
- References to rooms within the current zone do not require a zone prefix.