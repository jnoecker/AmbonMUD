# AmbonMUD — Architecture & Design Decisions

This document captures the architectural principles and design decisions that make AmbonMUD a production-grade MUD server. The philosophy is to avoid premature complexity while building extensible foundations for world content, commands, transports, and persistence.

---

## Core Architecture

### Data Flow Diagram

```
┌─────────────────────────────┐
│ Clients (telnet/browser)    │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ Transports (BlockingSocketTransport,   │
│            KtorWebSocketTransport)      │
│ • Decode raw I/O → InboundEvent        │
│ • Render OutboundEvent → text/bytes    │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────┐
│ InboundBus / OutboundBus   │
│ (interface layer)           │
│ • Local (in-process)        │
│ • Redis (pub/sub)           │
│ • gRPC (gateway split)      │
└──────────────┬──────────────┘
               │
               ▼
┌──────────────────────────────────────────┐
│ GameEngine (single-threaded, 100ms tick)│
│ • CommandRouter (command dispatch)       │
│ • CombatSystem (fight resolution)        │
│ • MobSystem (NPC wandering)              │
│ • RegenSystem (HP/mana ticks)            │
│ • Scheduler (delayed callbacks)          │
│ • StatusEffectSystem (DoT/HoT/buffs)    │
│ • PlayerRegistry (session ↔ player)     │
│ • AbilitySystem (spell casting)          │
│ • ShopRegistry (economy)                 │
│ • QuestSystem (quest tracking)           │
│ • AchievementSystem (achievements)       │
│ • GroupSystem (party management)         │
│ • GmcpEmitter (structured data)          │
└──────────────┬───────────────────────────┘
               │
               ▼
┌──────────────────────────────┐
│ OutboundRouter               │
│ • Per-session queues         │
│ • Backpressure (disconnect   │
│   slow clients)              │
│ • Prompt coalescing          │
└──────┬──────────────┬────────┘
       │              │
       ▼              ▼
┌─────────────┐  ┌──────────────┐
│ AnsiRenderer│  │ PlainRenderer│
│ (colors on) │  │ (colors off) │
└─────────────┘  └──────────────┘
```

### Deployment Modes

**STANDALONE** (default, single-process):
- All app components run in one JVM process
- `LocalInboundBus` / `LocalOutboundBus` (wrapped channels)
- YAML persistence by default (zero external dependencies)
- PostgreSQL + Redis available via Docker Compose

**ENGINE** (multi-process, game logic only):
- Runs `GameEngine` + persistence + gRPC server
- `LocalInboundBus` / `LocalOutboundBus` internally
- Gateways connect remotely via gRPC
- Requires gRPC infrastructure but no Redis

**GATEWAY** (multi-process, transports only):
- Runs telnet/WebSocket transports only
- Connects to remote ENGINE via gRPC
- Multiple gateways can share one engine
- Session IDs are globally unique (Snowflake-based)

**Container / Production (AWS ECS Fargate):**
- Single Docker image; mode set via `AMBONMUD_MODE` env var
- `standalone` topology: one Fargate service, `STANDALONE` mode
- `split` topology: separate Engine + Gateway Fargate services connected over Cloud Map DNS
- Config injected via `AMBONMUD_*` env vars (Hoplite env var source)
- `docker-entrypoint.sh` auto-derives unique `engineId` and `advertiseHost` per task
- See `infra/` (CDK project) and [DEPLOYMENT.md](./DEPLOYMENT.md) for full details

---
