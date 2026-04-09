# Environment Themes

Environment themes let Arcanum authors define per-zone visual atmospheres: particle mote colors, sky gradients, weather particle styles, and room transition colors. These are consumed by the web client via the `Zone.Environment` GMCP package.

## How It Works

1. **Author** defines weather types and zone themes in `application.yaml` (or a data-dir overlay).
2. **Server** resolves the active theme (zone override merged over global defaults) when a player enters a zone or first connects.
3. **Server** emits `Zone.Environment` GMCP to the client.
4. **Web client** applies theme colors to ambient motes, sky gradient, room transition, and weather particles — no client code changes needed for new zones.

---

## Weather Type Definitions

Weather types are now fully config-driven. The built-in types are defined under `engine.weather.types` and can be extended or overridden.

### YAML schema

```yaml
engine:
  weather:
    minTransitionMs: 300000   # Min milliseconds between transitions per zone
    maxTransitionMs: 900000   # Max milliseconds between transitions per zone
    types:
      MY_MIST:
        displayName: Mist
        description: A pale mist rolls in from the sea.
        weight: 1.5          # Relative probability (higher = more common)
        particleHint: fog    # Particle effect: rain | storm | snow | fog | wind | (empty = none)
        icon: "🌫"           # Unicode icon shown in the web UI (optional)
```

### Built-in types

| ID      | particle hint | Default weight |
|---------|---------------|---------------|
| `CLEAR` | *(none)*      | 3.0            |
| `RAIN`  | `rain`        | 2.0            |
| `STORM` | `storm`       | 0.5            |
| `FOG`   | `fog`         | 1.0            |
| `SNOW`  | `snow`        | 0.8            |
| `WIND`  | `wind`        | 1.0            |

To add a custom type, add an entry under `engine.weather.types` using any ID string. To remove a built-in type, override `engine.weather.types` as a map that omits the entry.

---

## Zone Environment Themes

Zone themes are defined under `engine.environment`. There is a `defaultTheme` that applies to all zones, plus per-zone overrides in the `zones` map keyed by zone prefix.

### YAML schema

```yaml
engine:
  environment:
    defaultTheme:
      moteColors:               # List of ambient mote color pairs
        - core: "#c8b8e8"       # Core particle color (CSS hex)
          glow: "#a897d2"       # Outer glow color (CSS hex)
      skyGradients:             # Sky gradient per time period
        DAWN:
          top: "#2a1a3a"        # Sky top color
          bottom: "#c88060"     # Horizon color
        DAY:
          top: "#4a6ea0"
          bottom: "#87ceeb"
        DUSK:
          top: "#3a2040"
          bottom: "#c86848"
        NIGHT:
          top: "#0a0c14"
          bottom: "#1a1c2e"
      transitionColors:         # Colors used for room transition motes
        - "#c8b8e8"
        - "#a897d2"
        - "#8caec9"
      weatherParticleOverrides: # Override particle hint per weather type (optional)
        RAIN: fog               # Use fog particles instead of rain in this zone
    zones:
      my_zone:
        moteColors:             # Overrides default moteColors for this zone
          - core: "#a8d8a0"
            glow: "#6aaa5a"
        skyGradients:           # Partial override — only specified periods are replaced
          DAY:
            top: "#4a8a50"
            bottom: "#88cc88"
```

### Merge rules

- `moteColors`: zone list replaces the default completely (if non-empty).
- `skyGradients`: zone map is merged over defaults; only specified periods are replaced.
- `transitionColors`: zone list replaces defaults (if non-empty).
- `weatherParticleOverrides`: zone map is merged over defaults.

### Zone key

The zone key must match the zone prefix in room IDs (the part before the colon, e.g. `tutorial_glade` for rooms like `tutorial_glade:start`).

---

## Particle Hints

The `particleHint` on a weather type (or a per-zone override) maps to a built-in particle renderer in the web client:

| Hint    | Effect                                          |
|---------|-------------------------------------------------|
| `rain`  | Falling rain streaks at a slight angle          |
| `storm` | Heavy rain + occasional lightning flash         |
| `snow`  | Soft drifting snowflakes                        |
| `fog`   | Slow-drifting translucent fog patches           |
| `wind`  | Horizontal streak lines                         |
| *(empty)* | No particles (e.g. `CLEAR`)                  |

To disable particles for a specific weather type in a specific zone, set `weatherParticleOverrides.<TYPE>: ""` (empty string).

---

## GMCP Protocol

The server sends `Zone.Environment` when:
- A player first subscribes to GMCP (`Core.Supports.Set`).
- A player enters a new zone.

### Payload

```json
{
  "zone": "tutorial_glade",
  "moteColors": [
    { "core": "#a8d8a0", "glow": "#6aaa5a" }
  ],
  "skyGradients": {
    "DAWN":  { "top": "#1a2a1a", "bottom": "#a8c070" },
    "DAY":   { "top": "#4a8a50", "bottom": "#88cc88" },
    "DUSK":  { "top": "#2a3020", "bottom": "#a08848" },
    "NIGHT": { "top": "#0a140a", "bottom": "#1a2c1a" }
  },
  "transitionColors": ["#a8d8a0", "#6aaa5a", "#c8e8b0"],
  "weatherParticleOverrides": {}
}
```

The `World.Weather` packet now also includes:

```json
{
  "zone": "tutorial_glade",
  "weather": "RAIN",
  "description": "A steady rain falls.",
  "particleHint": "rain",
  "icon": "☂"
}
```

---

## Arcanum Workflow

Arcanum authors the environment config as part of the server configuration YAML.  The recommended approach:

1. In the Arcanum zone editor, open the **Environment** tab for the zone.
2. Set mote colors, sky gradients, and any weather particle overrides.
3. Export to `application.yaml` (or a data-dir override file if deploying to a hosted instance).
4. The server picks up the config on next restart (or hot-reload if supported).

No client asset uploads are required — all environment data is transmitted at runtime via GMCP.
