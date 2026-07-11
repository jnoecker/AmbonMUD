import { useMemo, useState } from "react";
import type { WorldArea } from "../types";
import { zoneHue } from "../constants";
import { formatZoneName } from "../utils";

interface WorldMapProps {
  areas: WorldArea[];
  currentZone: string | null;
  serverAssets: Record<string, string>;
}

/** An area whose worldMap placement is fully authored. */
interface PlacedArea extends WorldArea {
  mapX: number;
  mapY: number;
  mapW: number;
  mapH: number;
}

function isPlaced(area: WorldArea): area is PlacedArea {
  return area.mapX != null && area.mapY != null && area.mapW != null && area.mapH != null;
}

function formatLevelRange(area: WorldArea): string {
  if (area.minLevel == null || area.maxLevel == null) return "—";
  if (area.minLevel === area.maxLevel) return String(area.minLevel);
  return `${area.minLevel}–${area.maxLevel}`;
}

/**
 * The World Map atlas tab. The painted `world_map` art is the full-height
 * centrepiece; every zone with an authored `worldMap` placement is seated onto
 * it as a soft jewel-tinted region carrying only a tiny level-range tag (hidden
 * outright on regions too small for it — container queries in styles.css).
 * Hovering or focusing a region floats a parchment tooltip with the full name;
 * clicking pins the zone's ledger in the side panel, which also lists uncharted
 * zones. The side panel is the future hook for viewing that zone's own map.
 */
export function WorldMap({ areas, currentZone, serverAssets }: WorldMapProps) {
  const [selected, setSelected] = useState<string | null>(null);
  const [hovered, setHovered] = useState<string | null>(null);
  const [artFailed, setArtFailed] = useState(false);

  const mapUrl = serverAssets["world_map"] ?? serverAssets["flight_map"] ?? null;
  const showArt = mapUrl != null && !artFailed;

  const placed = useMemo(() => areas.filter(isPlaced), [areas]);
  const uncharted = useMemo(() => areas.filter((a) => !isPlaced(a)), [areas]);

  const selectedArea = selected != null ? areas.find((a) => a.zone === selected) ?? null : null;
  const hoveredArea = hovered != null ? placed.find((a) => a.zone === hovered) ?? null : null;

  if (areas.length === 0) {
    return (
      <div className="atlas-empty">
        <p>No areas reported by the server yet.</p>
      </div>
    );
  }

  // Float the hover tooltip above the region, or below it when the region
  // hugs the map's top edge; clamp horizontally so edge zones stay readable.
  const tipBelow = hoveredArea != null && hoveredArea.mapY < 9;
  const tipLeft = hoveredArea != null
    ? Math.min(88, Math.max(12, hoveredArea.mapX + hoveredArea.mapW / 2))
    : 0;
  const tipTop = hoveredArea != null
    ? (tipBelow ? hoveredArea.mapY + hoveredArea.mapH : hoveredArea.mapY)
    : 0;

  return (
    <div className="world-map-body">
      <div
        className={`world-map-frame ${showArt ? "" : "world-map-frame-fallback"}`}
        role="group"
        aria-label="World map of known realms"
      >
        {showArt && (
          <img
            className="world-map-art"
            src={mapUrl}
            alt=""
            draggable={false}
            onError={() => setArtFailed(true)}
          />
        )}
        <div className="world-map-regions">
          {placed.map((area) => {
            const here = area.zone === currentZone;
            const isSelected = area.zone === selected;
            const range = formatLevelRange(area);
            return (
              <button
                key={area.zone}
                type="button"
                className={[
                  "world-map-zone",
                  here ? "world-map-zone-here" : "",
                  isSelected ? "world-map-zone-selected" : "",
                ].join(" ")}
                style={{
                  left: `${area.mapX}%`,
                  top: `${area.mapY}%`,
                  width: `${area.mapW}%`,
                  height: `${area.mapH}%`,
                  "--wm-hue": zoneHue(area.zone),
                } as React.CSSProperties}
                aria-pressed={isSelected}
                aria-label={`${formatZoneName(area.zone)}, levels ${range}${here ? ", you are here" : ""}`}
                onClick={() => setSelected((prev) => (prev === area.zone ? null : area.zone))}
                onMouseEnter={() => setHovered(area.zone)}
                onMouseLeave={() => setHovered((prev) => (prev === area.zone ? null : prev))}
                onFocus={() => setHovered(area.zone)}
                onBlur={() => setHovered((prev) => (prev === area.zone ? null : prev))}
              >
                <span className="world-map-zone-tag">{range}</span>
                {here && <span className="world-map-here-dot" aria-hidden="true" />}
              </button>
            );
          })}
          {hoveredArea && (
            <div
              className={`world-map-tip ${tipBelow ? "world-map-tip-below" : ""}`}
              style={{ left: `${tipLeft}%`, top: `${tipTop}%` }}
              aria-hidden="true"
            >
              <span className="world-map-tip-name">{formatZoneName(hoveredArea.zone)}</span>
              <span className="world-map-tip-level">{formatLevelRange(hoveredArea)}</span>
            </div>
          )}
        </div>
        {placed.length === 0 && (
          <p className="world-map-unplotted">
            No realms have been charted onto the world map yet.
          </p>
        )}
      </div>

      <aside className="world-map-side">
        {selectedArea ? (
          <div className="world-map-detail" role="status">
            <div className="world-map-detail-title">
              <span className="world-map-detail-name">{formatZoneName(selectedArea.zone)}</span>
              {selectedArea.zone === currentZone && (
                <span className="atlas-here-pill" aria-label="You are here">
                  you are here
                </span>
              )}
            </div>
            <div className="world-map-detail-meta">
              <span>
                Levels <strong>{formatLevelRange(selectedArea)}</strong>
              </span>
              <span className="world-map-detail-id">{selectedArea.zone}</span>
            </div>
            <p className="world-map-detail-hint">A closer survey of this realm is still being charted.</p>
          </div>
        ) : (
          <div className="world-map-detail world-map-detail-empty">
            <p className="world-map-detail-hint">
              Hover a realm to read its name — click to pin its ledger here.
            </p>
            <p className="world-map-detail-hint world-map-legend-line">
              <span className="world-map-here-dot world-map-legend-dot" aria-hidden="true" /> marks where
              you stand.
            </p>
          </div>
        )}

        {uncharted.length > 0 && (
          <div className="world-map-uncharted">
            <span className="world-map-uncharted-label">Uncharted</span>
            <div className="world-map-uncharted-chips">
              {uncharted.map((area) => (
                <span
                  key={area.zone}
                  className={`world-map-chip ${area.zone === currentZone ? "world-map-chip-here" : ""}`}
                >
                  {formatZoneName(area.zone)}
                  <span className="world-map-chip-level">{formatLevelRange(area)}</span>
                </span>
              ))}
            </div>
          </div>
        )}
      </aside>
    </div>
  );
}
