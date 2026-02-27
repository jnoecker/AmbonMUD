# AmbonMUD

AmbonMUD is a Kotlin MUD server with a tick-based game engine, telnet and WebSocket transports, YAML-defined zones, class-based progression, combat/abilities, and optional distributed deployment modes for horizontal scale.

## What it includes

- Kotlin/JVM server with a single-threaded 100ms engine tick loop
- Telnet transport plus browser client over WebSocket (`/ws`)
- YAML world loading with multi-zone support and validation
- Classes, races, attributes, abilities, status effects, quests, achievements, groups, economy/shops
- Persistence via YAML (default) or PostgreSQL, with optional Redis cache/pub-sub layers
- Deployment modes: `STANDALONE`, `ENGINE`, `GATEWAY`
- Zone-based sharding and zone instancing support
- Swarm load-testing module (`:swarm`)

## Tech stack

- Kotlin `2.3.10`, JVM toolchain `21`
- Gradle wrapper `9.3.1`
- Ktor `3.4.0`, Coroutines `1.10.2`
- Hoplite config, Jackson YAML/JSON
- Exposed + HikariCP + Flyway + PostgreSQL
- Redis (Lettuce)
- gRPC + Protobuf
- Micrometer + Prometheus
- JUnit 6 + ktlint

## Quick start

Prerequisites:
- JDK 21
- Git

Run the server:

```bash
./gradlew run
```

On Windows:

```powershell
.\gradlew.bat run
```

Default endpoints:
- Telnet: `localhost:4000`
- Web client: `http://localhost:8080`

Run demo mode (auto-opens browser):

```bash
./gradlew demo
```

## Build, test, lint

```bash
./gradlew ktlintCheck
./gradlew test
./gradlew ktlintCheck test
```

## Deployment modes

- `STANDALONE` (default): all components in one process
- `ENGINE`: game engine + persistence + gRPC server
- `GATEWAY`: telnet/web transports + gRPC client to engine

Convenience tasks for local multi-instance testing:

```bash
./gradlew runEngine1
./gradlew runEngine2
./gradlew runGateway1
./gradlew runGateway2
```

## Project layout

- `src/main/kotlin/dev/ambon/engine`: gameplay systems and tick loop
- `src/main/kotlin/dev/ambon/transport`: telnet/websocket adapters and rendering
- `src/main/kotlin/dev/ambon/persistence`: YAML/Postgres + cache/coalescing repositories
- `src/main/kotlin/dev/ambon/bus`, `grpc`, `gateway`, `sharding`: distributed/runtime topology
- `src/main/resources/world`: world zone YAML content
- `web-v3`: standalone frontend source; build output goes to `src/main/resources/web-v3`
- `swarm`: load-testing subproject

## Onboarding and docs

- Developer onboarding: [docs/DEVELOPER_GUIDE.md](docs/DEVELOPER_GUIDE.md)
- Architecture and scaling: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- World YAML schema: [docs/WORLD_YAML_SPEC.md](docs/WORLD_YAML_SPEC.md)
- Web client v3 architecture: [docs/WEB_V3.md](docs/WEB_V3.md)
- Current roadmap: [docs/ROADMAP.md](docs/ROADMAP.md)
- Swarm usage: [swarm/README.md](swarm/README.md)

## Contributing

See [docs/DEVELOPER_GUIDE.md](docs/DEVELOPER_GUIDE.md) for setup, workflow, and validation expectations.
