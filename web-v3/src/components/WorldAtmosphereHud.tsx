import type { WorldEvent, WorldTime, WorldWeather } from "../types";

interface Props {
  worldTime: WorldTime | null;
  worldWeather: WorldWeather | null;
  worldEvents: WorldEvent[];
}

function periodIcon(period: string): string {
  switch (period) {
    case "DAWN": return "\u263C";
    case "DAY": return "\u2600";
    case "DUSK": return "\u263D";
    case "NIGHT": return "\u263E";
    default: return "\u2600";
  }
}

function periodLabel(period: string): string {
  switch (period) {
    case "DAWN": return "Dawn";
    case "DAY": return "Day";
    case "DUSK": return "Dusk";
    case "NIGHT": return "Night";
    default: return period;
  }
}

function weatherIcon(weather: string): string {
  switch (weather) {
    case "RAIN": return "\u2602";
    case "STORM": return "\u26A1";
    case "FOG": return "\u2601";
    case "SNOW": return "\u2744";
    case "WIND": return "\u2634";
    default: return "";
  }
}

function weatherLabel(weather: string): string {
  switch (weather) {
    case "CLEAR": return "Clear";
    case "RAIN": return "Rain";
    case "STORM": return "Storm";
    case "FOG": return "Fog";
    case "SNOW": return "Snow";
    case "WIND": return "Wind";
    default: return weather;
  }
}

export function WorldAtmosphereHud({ worldTime, worldWeather, worldEvents }: Props) {
  const hasTime = worldTime !== null;
  const hasWeather = worldWeather !== null && worldWeather.weather !== "CLEAR";
  const hasEvents = worldEvents.length > 0;

  if (!hasTime && !hasWeather && !hasEvents) return null;

  return (
    <div className="atmosphere-hud" aria-label="World atmosphere">
      {hasTime && (
        <span className="atmosphere-hud-chip" title={`${periodLabel(worldTime.period)} — ${String(worldTime.hour).padStart(2, "0")}:${String(worldTime.minute).padStart(2, "0")}`}>
          <span className="atmosphere-hud-icon" aria-hidden="true">{periodIcon(worldTime.period)}</span>
          <span className="atmosphere-hud-label">{String(worldTime.hour).padStart(2, "0")}:{String(worldTime.minute).padStart(2, "0")}</span>
        </span>
      )}
      {hasWeather && (
        <span className="atmosphere-hud-chip" title={worldWeather.description}>
          <span className="atmosphere-hud-icon" aria-hidden="true">{worldWeather.icon || weatherIcon(worldWeather.weather)}</span>
          <span className="atmosphere-hud-label">{weatherLabel(worldWeather.weather)}</span>
        </span>
      )}
      {worldEvents.map((evt) => (
        <span key={evt.id} className="atmosphere-hud-chip atmosphere-hud-chip-event" title={evt.description}>
          <span className="atmosphere-hud-icon" aria-hidden="true">&#x2726;</span>
          <span className="atmosphere-hud-label">{evt.name}</span>
        </span>
      ))}
    </div>
  );
}
