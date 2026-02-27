# AmbonMUD - Roadmap

This roadmap tracks active and future work only.

## Current focus (in progress)

### 1. Web client v3 hardening

Status: In progress

Primary goals:
- Add high-value GMCP surfaces to UI (`Group.Info`, `Char.Achievements`, `Char.StatusVars`)
- Improve local frontend workflow and documented backend proxy/topology
- Add frontend CI checks (`lint`, typecheck/build)
- Add focused frontend tests for GMCP package handling
- Reduce initial bundle size through chunking/lazy loading

### 2. Observability hardening for distributed deployments

Status: In progress

Primary goals:
- Queue depth/capacity metrics across bus and session buffers
- Better error taxonomy metrics (auth, handoff, reconnect, Redis fallback)
- Versioned alert/dashboard improvements
- Correlation across gateway and engine flows

## Planned next

### 3. Crafting and gathering systems

Status: Planned

Scope:
- Resource nodes/materials
- Recipe execution and progression hooks
- Economy integration for buy/sell loops

### 4. Persistent world-state expansion

Status: Planned

Scope:
- Extended persistent interactables and server events
- More world-state hooks for content systems

### 5. Social systems

Status: Planned

Scope:
- Guilds/friends/mail lifecycle
- Offline interaction and retention features

## Later backlog

### 6. Player housing

Status: Backlog

### 7. OLC/world-builder tooling

Status: Backlog

### 8. Procedural dungeon content

Status: Backlog

## Prioritization guidance

1. Complete web-v3 reliability and CI guardrails first.
2. Land observability upgrades needed for multi-engine confidence.
3. Move to gameplay breadth (crafting, persistent state, social systems).
4. Tackle larger content tooling and procedural systems after telemetry and workflow maturity.

## Contribution entry points

High-leverage areas for contributors:
- Web-v3 GMCP feature implementations and tests
- Metrics and dashboard improvements
- YAML world content additions
- Ability/status-effect balancing and tests

See [DEVELOPER_GUIDE.md](./DEVELOPER_GUIDE.md) for setup and workflow.
