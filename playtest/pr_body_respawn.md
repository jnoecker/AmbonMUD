Follow-up to the zone-reset discussion on #1222: ground items and feature states previously had **no** respawn path of their own — once looted or toggled, they stayed that way until the zone-lifespan reset. Mobs (`respawnSeconds`) and gathering nodes already had individual timers; this brings items and features to parity, letting authors choose fine-grained timers where the coarse zone reset isn't the right tool.

## Schema (all optional, loader-validated `> 0`)

```yaml
items:
  coin:
    room: hall
    respawnSeconds: 30        # re-placed 30 s after it goes missing (requires room)

rooms:
  hall:
    exits:
      north:
        to: vault
        door:
          initialState: closed
          respawnSeconds: 20  # re-closes/re-locks 20 s after being opened
    features:
      snap_lever:
        type: LEVER
        respawnSeconds: 10    # snaps back to initialState
      gem_chest:
        type: CONTAINER
        respawnSeconds: 15    # reverts state AND refills its authored items
```

- Container refill uses the same wholesale-replace semantics as the `resetWithZone` zone reset (player-stored items are replaced — documented).
- `SIGN` features are stateless; the loader rejects `respawnSeconds` on them, as well as on unplaced items.

## Engine

New `TimedRespawnHandler`, ticked from the engine loop next to `ZoneResetHandler`:

- **Observation-based timers**: a timer arms the first tick a tracked spawn/feature is seen out of its initial condition, and disarms whenever it returns by any means (player puts the item back, closes the door, zone reset fires). No hooks needed in every pickup/interaction path.
- Containers count as "out of initial condition" when their **state or contents** differ — a looted-but-closed chest still refills.
- On revert, players in the room get a flavor line ("a sprung lever snaps back into place.", "a gleaming coin appears.") and a `Room.Features` GMCP refresh so the web canvas updates live.
- Hot reloads re-scan tracked spawns/features via the existing `onZoneScheduleRefresh` hook.
- State is in-memory; restarts re-arm timers (same as mob respawn scheduling).

## Tests

- `TimedRespawnHandlerTest` (new `ok_timed_respawn` fixture, `MutableClock`): item respawn at the boundary, no-timer item stays gone, timer disarms when the item is returned, lever/door/container reverts, contents-only looting arms the container timer, untouched features never revert.
- `WorldLoaderTest`: positive parse of all three field placements + three new `bad_*` fixtures (zero value, unplaced item, sign).
- Docs: `WORLD_YAML_SPEC.md` gains a "Timed respawn" section.

`ktlintCheck`, full `test`, and `integrationTest` all pass.
