# AmbonMUD - Developer Guide

This guide is the canonical onboarding path for engineers joining AmbonMUD.

## 1. Prerequisites

Required:
- JDK 21
- Git

Optional (only for specific workflows):
- Docker + Docker Compose (PostgreSQL, Redis, Prometheus, Grafana)
- Bun (for local work on `web-v3`)

Project/runtime versions in use:
- Kotlin 2.3.10
- Gradle wrapper 9.3.1
- Ktor 3.4.0
- JUnit 6.0.3

## 2. Clone and initial build

```bash
git clone https://github.com/jnoecker/AmbonMUD.git
cd AmbonMUD
./gradlew build
```

Windows:

```powershell
git clone https://github.com/jnoecker/AmbonMUD.git
cd AmbonMUD
.\gradlew.bat build
```

If build succeeds, your environment is ready.

## 3. Run the server (standalone)

```bash
./gradlew run
```

Windows:

```powershell
.\gradlew.bat run
```

Default endpoints:
- Telnet: `localhost:4000`
- Web client: `http://localhost:8080`
- WebSocket gameplay endpoint: `/ws`

Demo mode:

```bash
./gradlew demo
```

## 4. Verify setup

Minimum verification:

```bash
./gradlew ktlintCheck test
```

Expected signals:
- Gradle exits successfully
- Server starts and logs telnet/web bind ports
- You can connect via telnet or browser

## 5. Configuration and environment

Main config file:
- `src/main/resources/application.yaml`

Profile overlays (used by multi-instance tasks):
- `application-engine1.yaml`
- `application-engine2.yaml`
- `application-gw1.yaml`
- `application-gw2.yaml`

Runtime overrides are passed as Gradle project properties:

```bash
./gradlew run -Pconfig.ambonMUD.server.telnetPort=5000
./gradlew run -Pconfig.ambonMUD.logging.level=DEBUG
./gradlew run -Pconfig.ambonMUD.persistence.backend=POSTGRES
./gradlew run -Pconfig.ambonMUD.redis.enabled=true
```

Notes:
- Default persistence backend is `YAML`
- Postgres settings default to local compose stack (`ambon/ambon`)
- Redis bus HMAC secret is required only when Redis bus is enabled

## 6. Optional infrastructure (Docker)

Bring up supporting services:

```bash
docker compose up -d
```

Services:
- PostgreSQL: `localhost:5432`
- Redis: `localhost:6379`
- Prometheus: `localhost:9090`
- Grafana: `localhost:3000`

Run with Postgres backend:

```bash
./gradlew run -Pconfig.ambonMUD.persistence.backend=POSTGRES
```

Run with Redis enabled:

```bash
./gradlew run -Pconfig.ambonMUD.redis.enabled=true
```

## 7. Build, test, lint commands

Core commands:

```bash
./gradlew run
./gradlew demo
./gradlew ktlintCheck
./gradlew test
./gradlew ktlintCheck test
```

Focused tests:

```bash
./gradlew test --tests "dev.ambon.engine.commands.CommandParserTest"
./gradlew test --tests "*CommandRouter*"
```

Swarm load testing:

```bash
./gradlew :swarm:run --args="--config example.swarm.yaml"
```

## 8. Development workflow

1. Create a branch.
2. Implement changes with tests.
3. Run focused tests while iterating.
4. Run `ktlintCheck test` before opening/merging.
5. Keep engine/transport boundaries intact (see architecture contracts).

Recommended quality gate before PR:

```bash
./gradlew ktlintCheck test
```

## 9. Architecture overview

Top-level runtime model:

1. Transports decode input into `InboundEvent`.
2. Engine processes events in single-threaded tick loop.
3. Engine emits `OutboundEvent`.
4. Outbound router renders output per session.

Key modules:
- `dev.ambon.engine`: game systems, command routing, registry/state
- `dev.ambon.transport`: telnet/ws adaptation and protocol handling
- `dev.ambon.persistence`: repository stack and persistence workers
- `dev.ambon.bus`: local/redis/grpc event-bus implementations
- `dev.ambon.gateway`, `dev.ambon.grpc`, `dev.ambon.sharding`: split-mode and sharding logic
- `dev.ambon.config`: typed config + strict validation

Entry points:
- `Main.kt`: mode dispatch (`STANDALONE`, `ENGINE`, `GATEWAY`)
- `MudServer.kt`: standalone/engine composition root
- `GatewayServer.kt`: gateway composition root

For full design rationale and scaling contracts, see [ARCHITECTURE.md](./ARCHITECTURE.md).

## 10. Common engineering tasks

### Add a command

1. Add command variant in `engine/commands/CommandParser.kt`.
2. Parse input to that variant in `parse`.
3. Implement behavior in `engine/commands/CommandRouter.kt`.
4. Preserve prompt behavior for success/failure paths.
5. Add parser and router tests.

### Add an ability or status effect

1. Update definitions in `application.yaml`.
2. Ensure validator constraints are met (`AppConfig.validated()`).
3. Add/adjust tests in `AbilitySystemTest` / `StatusEffectSystemTest`.

### Add or update world content

1. Edit YAML in `src/main/resources/world`.
2. Keep IDs namespaced by zone (`<zone>:<id>` once normalized).
3. Validate with world loader tests.
4. Follow [WORLD_YAML_SPEC.md](./WORLD_YAML_SPEC.md).

### Add a persistence field

1. Add defaulted field to `PlayerRecord`.
2. Verify YAML compatibility.
3. Verify Redis JSON round-trip.
4. Add Flyway migration and repository mappings for Postgres.

### Multi-instance local topology test

Start in separate terminals:

```bash
./gradlew runEngine1
./gradlew runEngine2
./gradlew runGateway1
./gradlew runGateway2
```

## 11. Troubleshooting

### JDK mismatch

Symptom: Gradle/toolchain errors.

Fix:
- Confirm `java -version` is JDK 21.
- Re-run build with wrapper.

### Port already in use

Symptom: server fails to bind 4000/8080.

Fix:
- Override ports with `-Pconfig.ambonMUD.server.telnetPort` / `webPort`.
- Stop conflicting process.

### PostgreSQL or Redis connection failures

Symptom: startup failures when optional backends enabled.

Fix:
- Ensure `docker compose up -d` is running.
- Confirm host/port/credentials in config.
- Check service logs with `docker compose logs <service>`.

### Admin dashboard auth errors

Symptom: dashboard unauthorized or startup validation failure.

Fix:
- Ensure `ambonMUD.admin.enabled=true` implies non-blank `ambonMUD.admin.token`.

### Redis bus startup validation failure

Symptom: config validation fails when Redis bus enabled.

Fix:
- Set non-blank `ambonMUD.redis.bus.sharedSecret`.

## 12. Cloud/remote development notes

For remote Claude Code environments:
- `gh` CLI may be unavailable; use `git` directly.
- Avoid tight timing assumptions in tests; use deterministic/polling synchronization.
- First Gradle build can be slow due dependency download.
- Keep JVM toolchain aligned with JDK 21.

## 13. Related docs

- Architecture and scaling: [ARCHITECTURE.md](./ARCHITECTURE.md)
- World YAML contract: [WORLD_YAML_SPEC.md](./WORLD_YAML_SPEC.md)
- Web client v3: [WEB_V3.md](./WEB_V3.md)
- Project roadmap: [ROADMAP.md](./ROADMAP.md)
- Root overview: [../README.md](../README.md)
