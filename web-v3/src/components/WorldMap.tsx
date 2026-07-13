import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { WorldArea } from "../types";
import { zoneHue } from "../constants";
import { formatZoneName } from "../utils";

interface WorldMapProps {
  areas: WorldArea[];
  currentZone: string | null;
  serverAssets: Record<string, string>;
  /** Open the named zone's chart (Local Map preview via the `map` command). */
  onViewCharts: (zone: string) => void;
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

const MIN_ZOOM = 1;
const MAX_ZOOM = 5;
const WHEEL_STEP = 1.18;
/** Pointer travel (px) before a press counts as a pan, not a click. */
const DRAG_THRESHOLD = 5;
/** Fallback art aspect (w/h) until the painted map reports its real size. */
const FALLBACK_ASPECT = 3 / 4;

/**
 * The World Map atlas tab. The painted `world_map` art is the full-height
 * centrepiece inside a pannable, zoomable viewport (wheel or buttons to zoom,
 * drag to pan). Zoom resizes the layout — not a CSS transform — so each
 * region's container query re-evaluates and level tags surface as zones grow.
 * Hover floats a parchment tooltip with the full name; clicking pins the
 * zone's ledger in the side panel, where "unfurl the charts" opens that
 * zone's own map via the `map` command.
 */
export function WorldMap({ areas, currentZone, serverAssets, onViewCharts }: WorldMapProps) {
  const [selected, setSelected] = useState<string | null>(null);
  const [hovered, setHovered] = useState<string | null>(null);
  const [artFailed, setArtFailed] = useState(false);
  const [artAspect, setArtAspect] = useState<number | null>(null);
  const [zoom, setZoom] = useState(1);
  const [viewportSize, setViewportSize] = useState<{ w: number; h: number }>({ w: 0, h: 0 });

  const viewportRef = useRef<HTMLDivElement | null>(null);
  const zoomRef = useRef(1);
  const fitRef = useRef<{ w: number; h: number }>({ w: 0, h: 0 });
  const pendingScrollRef = useRef<{ left: number; top: number } | null>(null);
  const dragRef = useRef<{
    pointerId: number;
    startX: number;
    startY: number;
    scrollLeft: number;
    scrollTop: number;
    dragged: boolean;
  } | null>(null);

  const mapUrl = serverAssets["world_map"] ?? serverAssets["flight_map"] ?? null;
  const showArt = mapUrl != null && !artFailed;

  const placed = useMemo(() => areas.filter(isPlaced), [areas]);
  const uncharted = useMemo(() => areas.filter((a) => !isPlaced(a)), [areas]);

  const selectedArea = selected != null ? areas.find((a) => a.zone === selected) ?? null : null;
  const hoveredArea = hovered != null ? placed.find((a) => a.zone === hovered) ?? null : null;

  // ── Viewport measurement ──────────────────────────
  useEffect(() => {
    const vp = viewportRef.current;
    if (!vp) return;
    const measure = () => setViewportSize({ w: vp.clientWidth, h: vp.clientHeight });
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(vp);
    return () => ro.disconnect();
  }, []);

  // Contain-fit of the art inside the viewport = the zoom-1 canvas size.
  const aspect = showArt ? artAspect ?? FALLBACK_ASPECT : FALLBACK_ASPECT;
  const fit = useMemo(() => {
    const { w, h } = viewportSize;
    if (w <= 0 || h <= 0) return { w: 0, h: 0 };
    const width = Math.min(w, h * aspect);
    return { w: width, h: width / aspect };
  }, [viewportSize, aspect]);
  useEffect(() => {
    fitRef.current = fit;
  }, [fit]);

  const canvasW = fit.w * zoom;
  const canvasH = fit.h * zoom;

  // ── Zoom (wheel at cursor, buttons at centre) ─────
  const setZoomAt = useCallback((next: number, clientX?: number, clientY?: number) => {
    const vp = viewportRef.current;
    if (!vp) return;
    const prev = zoomRef.current;
    const nz = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, next));
    if (nz === prev) return;
    const rect = vp.getBoundingClientRect();
    const px = clientX != null ? clientX - rect.left : rect.width / 2;
    const py = clientY != null ? clientY - rect.top : rect.height / 2;
    // Content coords of the point under the cursor, accounting for the
    // margin-auto centering while the canvas is smaller than the viewport.
    const f = fitRef.current;
    const offPrevX = Math.max(0, (vp.clientWidth - f.w * prev) / 2);
    const offPrevY = Math.max(0, (vp.clientHeight - f.h * prev) / 2);
    const offNextX = Math.max(0, (vp.clientWidth - f.w * nz) / 2);
    const offNextY = Math.max(0, (vp.clientHeight - f.h * nz) / 2);
    const contentX = vp.scrollLeft + px - offPrevX;
    const contentY = vp.scrollTop + py - offPrevY;
    const ratio = nz / prev;
    zoomRef.current = nz;
    setZoom(nz);
    pendingScrollRef.current = {
      left: contentX * ratio + offNextX - px,
      top: contentY * ratio + offNextY - py,
    };
  }, []);

  useLayoutEffect(() => {
    const vp = viewportRef.current;
    const p = pendingScrollRef.current;
    if (vp && p) {
      vp.scrollLeft = p.left;
      vp.scrollTop = p.top;
      pendingScrollRef.current = null;
    }
  }, [zoom]);

  // Native listener: React's synthetic wheel handler is passive, so it can't
  // preventDefault the page scroll. setZoomAt is stable (reads refs only).
  useEffect(() => {
    const vp = viewportRef.current;
    if (!vp) return;
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const factor = e.deltaY < 0 ? WHEEL_STEP : 1 / WHEEL_STEP;
      setZoomAt(zoomRef.current * factor, e.clientX, e.clientY);
    };
    vp.addEventListener("wheel", onWheel, { passive: false });
    return () => vp.removeEventListener("wheel", onWheel);
  }, [setZoomAt]);

  const resetView = useCallback(() => {
    zoomRef.current = 1;
    setZoom(1);
    pendingScrollRef.current = { left: 0, top: 0 };
  }, []);

  // ── Drag to pan (suppresses the click that ends a drag) ──
  const onPointerDown = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return;
    const vp = viewportRef.current;
    if (!vp) return;
    dragRef.current = {
      pointerId: e.pointerId,
      startX: e.clientX,
      startY: e.clientY,
      scrollLeft: vp.scrollLeft,
      scrollTop: vp.scrollTop,
      dragged: false,
    };
  }, []);

  const onPointerMove = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    const vp = viewportRef.current;
    if (!drag || !vp || e.pointerId !== drag.pointerId) return;
    const dx = e.clientX - drag.startX;
    const dy = e.clientY - drag.startY;
    if (!drag.dragged && Math.hypot(dx, dy) < DRAG_THRESHOLD) return;
    if (!drag.dragged) {
      drag.dragged = true;
      try {
        vp.setPointerCapture(drag.pointerId);
      } catch {
        // Pointer already gone (or synthetic); the drag still tracks moves.
      }
    }
    vp.scrollLeft = drag.scrollLeft - dx;
    vp.scrollTop = drag.scrollTop - dy;
  }, []);

  const onPointerEnd = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    if (!drag || e.pointerId !== drag.pointerId) return;
    if (!drag.dragged) {
      dragRef.current = null;
      return;
    }
    // The click that ends a real drag fires right after pointerup; clear on
    // the next tick so a drag released without one can't eat a later click.
    setTimeout(() => {
      if (dragRef.current === drag) dragRef.current = null;
    }, 0);
  }, []);

  const onClickCapture = useCallback((e: React.MouseEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    dragRef.current = null;
    if (drag?.dragged) {
      e.preventDefault();
      e.stopPropagation();
    }
  }, []);

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
    ? Math.min(92, Math.max(8, hoveredArea.mapX + hoveredArea.mapW / 2))
    : 0;
  const tipTop = hoveredArea != null
    ? (tipBelow ? hoveredArea.mapY + hoveredArea.mapH : hoveredArea.mapY)
    : 0;

  return (
    <div className="world-map-body">
      <div className="world-map-stage">
        <div
          ref={viewportRef}
          className="world-map-viewport"
          role="group"
          aria-label="World map of known realms — scroll or use the buttons to zoom, drag to pan"
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerEnd}
          onPointerCancel={onPointerEnd}
          onClickCapture={onClickCapture}
          style={{ cursor: zoom > 1 ? "grab" : undefined }}
        >
        <div
          className={`world-map-canvas ${showArt ? "" : "world-map-canvas-fallback"}`}
          style={{ width: canvasW || undefined, height: canvasH || undefined }}
        >
          {showArt && (
            <img
              className="world-map-art"
              src={mapUrl}
              alt=""
              draggable={false}
              onLoad={(e) => {
                const img = e.currentTarget;
                if (img.naturalWidth > 0 && img.naturalHeight > 0) {
                  setArtAspect(img.naturalWidth / img.naturalHeight);
                }
              }}
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
                  onDoubleClick={() => onViewCharts(area.zone)}
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
        </div>
        </div>
        {placed.length === 0 && (
          <p className="world-map-unplotted">
            No realms have been charted onto the world map yet.
          </p>
        )}
        <div className="map-zoom-controls world-map-zoom-controls">
          <button
            type="button"
            className="map-zoom-btn"
            onClick={() => setZoomAt(zoomRef.current * 1.4)}
            title="Zoom in"
            aria-label="Zoom in"
          >
            +
          </button>
          <button
            type="button"
            className="map-zoom-btn"
            onClick={() => setZoomAt(zoomRef.current / 1.4)}
            title="Zoom out"
            aria-label="Zoom out"
          >
            −
          </button>
          <button
            type="button"
            className="map-zoom-btn"
            onClick={resetView}
            title="Fit the whole map"
            aria-label="Fit the whole map"
          >
            ⌖
          </button>
        </div>
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
            <button
              type="button"
              className="world-map-view-charts"
              onClick={() => onViewCharts(selectedArea.zone)}
            >
              Unfurl this realm&apos;s charts →
            </button>
          </div>
        ) : (
          <div className="world-map-detail world-map-detail-empty">
            <p className="world-map-detail-hint">
              Hover a realm to read its name — click to pin its ledger here, double-click to open
              its charts.
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
                <button
                  key={area.zone}
                  type="button"
                  className={`world-map-chip ${area.zone === currentZone ? "world-map-chip-here" : ""}`}
                  title="Unfurl this realm's charts"
                  onClick={() => onViewCharts(area.zone)}
                >
                  {formatZoneName(area.zone)}
                  <span className="world-map-chip-level">{formatLevelRange(area)}</span>
                </button>
              ))}
            </div>
          </div>
        )}
      </aside>
    </div>
  );
}
