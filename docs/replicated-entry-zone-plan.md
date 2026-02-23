# AmbonMUD Replicated Entry Zone Plan

## Why this plan

A single tutorial or entry zone can become a hot shard during large new-player spikes. The current sharding model assumes one owner per zone, which keeps behavior simple but concentrates login and early movement load.

This plan adds an explicit replicated-zone mode for entry areas only, so multiple engines can host equivalent copies while preserving existing single-owner behavior for normal zones.

## Scope

In scope:
- Replicated routing for selected zones (for example `tutorial`).
- Engine selection for handoffs and login placement into replicated zones.
- Backward-compatible behavior for all existing single-owner zones.

Out of scope:
- Generic multi-owner behavior for all zones.
- Shared in-zone simulation across replicas (players in replica A do not automatically see replica B).
- Dynamic zone rebalancing of arbitrary live zones.

## Design constraints

These constraints remain hard requirements:
- Exactly one engine owns a given session at a time.
- Engine tick loop remains single-threaded per process.
- Existing world IDs stay namespaced as `<zone>:<room>`.
- Single-owner zone behavior must remain unchanged.

## Proposed model

### Zone modes

Introduce explicit zone modes:
- `SINGLE_OWNER` (default): existing behavior, exactly one owner.
- `REPLICATED_ENTRY`: multiple engines may claim this zone; routing selects one engine per inbound handoff/login.

This avoids accidental split-brain from overlapping `claimZones` calls in Redis.

### Session affinity

For `REPLICATED_ENTRY` zones:
- Select a target engine when entering the zone.
- Keep that session sticky to the selected engine until the player leaves the replicated zone.
- Persist the player's room as normal; if room is in a replicated zone on reconnect, reselect from active replicas.

### Visibility semantics

A replicated zone is treated as independent copies:
- Room-local chat/emotes/combat are replica-local.
- This is acceptable for tutorial/entry content.
- Global channels (`gossip`, `tell`, `who`) keep existing cross-engine behavior.

## Routing policy

### Selector

Use power-of-two choices with health/load weighting:
1. Build candidate engine set that currently claims the replicated zone.
2. Randomly sample two healthy candidates.
3. Pick the candidate with lower score.

Score example:
- `score = sessionsInReplicatedZone + 2 * inTransitHandoffs + queuePressurePenalty`

If only one healthy candidate exists, use it.
If no healthy candidate exists, fail as current handoff does.

### Why this selector

- Better balancing than pure random.
- Lower coordination cost than global least-load.
- Stable under churn and partial telemetry loss.

## Registry and metadata changes

### ZoneRegistry API extension

Add read path for replicated routing while preserving current methods:
- `fun modeOf(zone: String): ZoneMode`
- `fun candidatesOf(zone: String): List<EngineAddress>`

Keep:
- `ownerOf(zone)` for `SINGLE_OWNER` zones.
- `claimZones(...)` with mode-aware semantics.

### Redis representation

Add keys:
- `zone:mode:<zone>` -> `SINGLE_OWNER|REPLICATED_ENTRY`
- `zone:replicas:<zone>` -> set/list of engine addresses with TTL
- `engine:load:<engineId>` -> load snapshot JSON with TTL

Keep existing:
- `zone:owner:<zone>` for single-owner lookup

## Config changes

Add configuration for replicated zones and selector tuning:

```yaml
ambonMUD:
  sharding:
    replicatedZones: [tutorial]
    selection:
      strategy: POWER_OF_TWO
      loadTtlSeconds: 15
      healthFailureThreshold: 3
```

Validation rules:
- `replicatedZones` entries must be non-blank.
- A zone cannot be both statically single-assigned and replicated.
- `strategy` must default to `POWER_OF_TWO`.

## Engine and gateway behavior changes

### Engine

- On startup, claim local zones as either single-owner or replicated based on config.
- Publish periodic load snapshots (lightweight counters only).
- In handoff flow:
  - for `SINGLE_OWNER`, keep current `ownerOf` path.
  - for `REPLICATED_ENTRY`, call selector on `candidatesOf`.

### Gateway

No protocol rewrite required for first iteration.
Gateway still follows `SessionRedirect` from engine.
Optional future optimization: gateway-side preselection for initial login routing.

## Rollout plan

### Phase 1: Contracts and config
- Add `ZoneMode`, config schema, and strict validation.
- Extend `ZoneRegistry` interfaces and static implementation.
- Keep behavior unchanged unless a zone is configured as replicated.

### Phase 2: Redis registry support
- Implement replicated claim/candidate semantics in `RedisZoneRegistry`.
- Add TTL heartbeat for replica membership.
- Preserve current single-owner keys for backward compatibility.

### Phase 3: Load telemetry and selector
- Add lightweight per-engine load snapshot publication.
- Implement selector utility with deterministic unit tests.
- Add fallback behavior for stale/missing load.

### Phase 4: Handoff integration
- Update `HandoffManager` selection path for replicated zones.
- Preserve existing timeout/rollback behavior.
- Add integration tests for multi-engine replicated-zone routing.

### Phase 5: Observability and hardening
- Metrics: candidate count, selector decisions, fallback counts, handoff failure reasons.
- Add chaos tests for engine loss during replicated-zone entry.

## Testing plan

Unit:
- Selector picks lower-load candidate from sampled pair.
- Selector falls back correctly on stale telemetry.
- Config validation rejects illegal overlaps.

Integration:
- Two engines host `tutorial`; new sessions distribute over time.
- Handoff into replicated zone uses candidate selector, not last-writer owner.
- Engine drop removes replica after TTL and routing continues to remaining replica.

Regression:
- Existing single-owner sharding tests remain unchanged.
- Cross-zone handoff and login flows still pass for non-replicated zones.

## Risks and mitigations

Risk: accidental use on non-entry zones causing fragmented social experience.
Mitigation: explicit `REPLICATED_ENTRY` mode plus documentation and config validation.

Risk: stale load snapshots bias selection.
Mitigation: TTL-based freshness check and random fallback.

Risk: hidden split-brain semantics with current Redis owner keys.
Mitigation: mode-aware keys and API; no implicit multi-owner from overlapping claims.

## Acceptance criteria

- Replicated zones are opt-in and isolated to configured zone list.
- Single-owner zone behavior remains bit-for-bit compatible.
- In load tests, entry-zone session distribution is materially more balanced than random-only baseline.
- No increase in handoff timeout error rate for non-replicated zones.
