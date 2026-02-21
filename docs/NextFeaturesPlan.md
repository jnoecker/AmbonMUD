# AmbonMUD - Proposed Next Features and Improvements

## Purpose
This document captures potential near-term and mid-term plans for AmbonMUD improvements. It is not a commitment, but a menu of options with rationale, impact, and rough scope.

## Guiding Constraints
- Preserve engine/transport boundaries and semantic outbound events.
- Keep the engine single-threaded with injected `Clock` usage.
- Avoid blocking I/O inside the engine tick loop.
- Preserve YAML persistence invariants and atomic writes.
- Add tests for behavioral changes.

## Plan A: Player Progression and UX
Goal: Make progression feel more tangible without major engine refactors.

Proposed features
- `score` or `stats` command with derived values (level, XP, max HP, regen interval, damage/armor totals).
- `help <command>` details for onboarding.
- Death flow polish: explicit death summary + safe respawn messaging (no mechanics change).
- Combat feedback improvements (damage roll breakdown, armor mitigation summary).

Impact
- Improves retention and clarity with minimal system changes.

Scope
- New command(s) in `CommandParser` and `CommandRouter`.
- Small additions to UI event generation for richer text.
- Tests for command parsing and output formatting.

Risks
- Over-sharing internal formulas could require balancing later.

## Plan B: Content Authoring and World Quality
Goal: Make it easier to build and validate worlds while reducing runtime surprises.

Proposed features
- World loader warnings for unused items/mobs and unreachable rooms.
- Room `tags` (e.g., `no_combat`, `safe`) with validation and runtime checks.
- Zone-specific ambient messages on a timer (engine-scheduled, no per-room timers).
- Optional per-zone startup broadcast summarizing changes (visible to admins only).

Impact
- Faster content iteration, better world consistency.

Scope
- Extend world schema and loader validation.
- Update tests in `WorldLoaderTest` with positive/negative fixtures.

Risks
- Schema changes require updating `docs/world-zone-yaml-spec.md` and examples.

## Plan C: Social and Community Features
Goal: Improve social stickiness with lightweight systems.

Proposed features
- `reply` command to respond to last tell.
- `whois <player>` for level/last-seen summary.
- Basic chat channels with mute/ignore list persisted per player.

Impact
- Stronger social loops with moderate persistence updates.

Scope
- New `OutboundEvent`s for channel formatting.
- `PlayerRepository` updates to store per-player ignore/mute lists.
- Tests for persistence and command behavior.

Risks
- Must preserve case-insensitive name rules and avoid blocking I/O in engine.

## Plan D: Items, Loot, and Economy
Goal: Add a lightweight reward loop without full shop systems.

Proposed features
- Basic loot tables for mobs (percent chance, min/max quantity).
- `drop` on mob death with room broadcast.
- `value` field for items to enable future vendor systems.

Impact
- Makes combat and exploration feel rewarding.

Scope
- Add optional loot config to world YAML.
- Adjust combat/mob death flow and item placement rules.
- Expand validation to ensure items exist and loot rules are sane.

Risks
- Increased world complexity; requires good tests for drop behavior.

## Plan E: Transport and Client Polish
Goal: Improve usability for telnet and web client without touching engine logic.

Proposed features
- Web client reconnect UX (auto-reconnect with state-safe prompts).
- Telnet line editing and history (if available in current stack).
- Negotiated ANSI detection for telnet to reduce manual toggles.

Impact
- Smoother first-run experience.

Scope
- Transport-side updates only; no engine changes.
- Integration tests for WebSocket and telnet transports.

Risks
- Telnet negotiation can vary by client; ensure fallbacks are safe.

## Plan F: Ops and Observability
Goal: Make it easier to run and debug locally.

Proposed features
- Structured log messages for session lifecycle, combat, and world load.
- Admin-only metrics summary command (e.g., active sessions, tick lag).
- Load-test scenario scripts that simulate sessions and movement.

Impact
- Faster diagnosis of performance and gameplay regressions.

Scope
- Logging and metrics wiring in server/engine boundaries.
- Simple CLI or test harness for automated sessions.

Risks
- Avoid adding blocking operations to the tick loop.

## Recommendations for Next Step
If you want a low-risk, high-value iteration, Plan A is the best first slice. It is largely additive and exercises the command/test pipeline without introducing new persistence or loader schema.

If you want to enable richer content design, Plan B is the next best option, but it requires schema changes and careful validation updates.

## Open Questions
- Do you want to prioritize player-facing UX (Plan A) or content tooling (Plan B)?
- Are schema changes acceptable in the next milestone?
- Should we prioritize telnet improvements or web client polish first?
