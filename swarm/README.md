# Swarm Load Tester

Kotlin-based load-testing utility for AmbonMUD. It runs configurable bot swarms against telnet and WebSocket endpoints.

## Run

```bash
./gradlew :swarm:run --args="--config example.swarm.yaml"
```

Validate config only:

```bash
./gradlew :swarm:run --args="--config example.swarm.yaml --validate"
```

Root alias task:

```bash
./gradlew swarmRun
```

## What it covers

- Single-machine swarm execution
- Protocol mix by percentage (telnet, websocket)
- Timed runs and validation mode
- Weighted action behaviors (movement/chat/combat/churn)
- Deterministic seeded mode for reproducibility
- Summary KPIs (connect/login success, command count, disconnects, p50/p95/p99 latency)

## Config reference

- Example config: `swarm/example.swarm.yaml`
- Schema implementation: `swarm/src/main/kotlin/dev/ambon/swarm/config/SwarmConfig.kt`

## Notes

- Designed for local/dev load characterization.
- WebSocket target path is `/ws`.
- See root docs for architecture and deployment context: [../README.md](../README.md)
