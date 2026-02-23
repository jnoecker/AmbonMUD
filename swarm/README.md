# Swarm Load Tester

Kotlin-based load testing utility for AmbonMUD that runs many simple bots with configurable behavior.

## Features (v1)
- Runs **inside this repo** as a dedicated Gradle module (`:swarm`)
- CLI supports:
  - config validation mode (`--validate`)
  - timed one-shot run
- Single-machine bot swarm
- Protocol mix with configurable percentages:
  - Telnet
  - WebSocket (`/ws`)
- Login model:
  - new usernames generated per run (`namespacePrefix_####`)
  - credentials retained while running so a bot can disconnect/relogin
- Behavior model:
  - weighted random actions: idle, login churn, movement, chat, auto-combat
  - deterministic mode with seed for reproducible runs
- Load model:
  - linear ramp-up over configurable seconds
- Reporting:
  - periodic progress logs
  - console summary with default KPIs (connect/login success, command count, disconnects, p50/p95/p99 latency)

## Run
```bash
./gradlew :swarm:run --args="--config example.swarm.yaml"
```

## Validate config only
```bash
./gradlew :swarm:run --args="--config example.swarm.yaml --validate"
```

## Config schema
See `swarm/example.swarm.yaml` for full fields and defaults implemented in `SwarmConfig.kt`.

## Notes
- Intended for local/dev environments.
- Transport handling is best-effort by design in v1.
- A root alias task exists:
  - `./gradlew swarmRun` (uses default `:swarm:run` behavior)
