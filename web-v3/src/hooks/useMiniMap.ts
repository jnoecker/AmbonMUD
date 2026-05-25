import { useCallback, useEffect, useRef } from "react";
import { MAP_OFFSETS } from "../constants";
import { canvasCallbacks, gameStateRef } from "../canvas/GameStateBridge";
import type { MapRoom } from "../types";

// Parchment / ink palette — matches the in-scene minimap.
const BG_COLOR = "#241c14"; // dark fallback when the scroll image hasn't loaded
const LINE_COLOR = "rgba(224, 196, 134, 0.9)"; // e0c486 parchment-gold route line
const TICK_COLOR = "rgba(224, 196, 134, 0.8)";
const INK = "#4a3a28"; // dark sepia pen ink
const NODE_FILL = "#cdb487"; // explored room wash (procedural fallback)
const CURRENT_FILL = "#9c3b22"; // sienna "you are here"
const CURRENT_MARK = "#f3e6c8"; // cream center dot
const FOG_INK = "rgba(138, 122, 94, 0.6)"; // faint "unknown" outline
const HOUSING_FILL = "#b98a5a";
const QUEST_MARKER = "#c69a2e"; // amber gold
const PATH_GLOW = "rgba(198, 154, 46, 0.9)";
const PATH_GLOW_OUTER = "rgba(198, 154, 46, 0.3)";
const LABEL_CURRENT = "#f3e6c8";
const LABEL_VISITED = "rgba(238, 224, 196, 0.92)";
const LABEL_OUTLINE = "rgba(28, 20, 10, 0.85)";
const QUEST_PULSE_PERIOD = 2500; // ms for one full cycle
const PATH_SHIMMER_PERIOD = 1800; // ms

// Zoom is expressed as the cell spacing in CSS px. The view pans by tracking the
// grid coordinate sitting at the centre of the scroll area.
const MAX_ZOOM = 120; // most zoomed-in cell size
const DEFAULT_ZOOM = 88; // comfortable default centred on the player
const MAX_NODE = 52; // glyph size caps (px); scaled down to fit the cell
const MAX_CURRENT = 64;

// Server-asset keys (shared with the in-scene minimap).
const A_ROOM = "minimap_room";
const A_ROOM_CURRENT = "minimap_room_current";
const A_ROOM_FOG = "minimap_unexplored";
const A_ROOM_HOUSING = "minimap_room_housing";
const A_QUEST = "minimap_quest";
const terrainKey = (t: string) => `minimap_room_${t}`;

// Inset fractions so map nodes stay within the visible scroll parchment area.
const SCROLL_INSET_LEFT = 0.08;
const SCROLL_INSET_RIGHT = 0.10;
const SCROLL_INSET_TOP = 0.08;
const SCROLL_INSET_BOTTOM = 0.12;
// How much to zoom the background image (1.0 = fill canvas, >1 = zoom in)
const BG_ZOOM = 1.15;

function clamp(v: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, v));
}

interface Trim {
  sx: number;
  sy: number;
  sw: number;
  sh: number;
}

interface GlyphEntry {
  img: HTMLImageElement;
  trim: Trim | null;
  ready: boolean;
}

interface View {
  cell: number;
  panGx: number;
  panGy: number;
}

/** Opaque bounding box of an image, or null if there's no real padding / pixels
 *  can't be read (CORS-tainted offscreen canvas). */
function computeTrim(img: HTMLImageElement): Trim | null {
  const w = img.naturalWidth;
  const h = img.naturalHeight;
  if (w === 0 || h === 0) return null;
  try {
    const c = document.createElement("canvas");
    c.width = w;
    c.height = h;
    const cx = c.getContext("2d", { willReadFrequently: true });
    if (!cx) return null;
    cx.drawImage(img, 0, 0);
    const data = cx.getImageData(0, 0, w, h).data;
    const threshold = 12;
    let minX = w;
    let minY = h;
    let maxX = -1;
    let maxY = -1;
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        if (data[(y * w + x) * 4 + 3] > threshold) {
          if (x < minX) minX = x;
          if (x > maxX) maxX = x;
          if (y < minY) minY = y;
          if (y > maxY) maxY = y;
        }
      }
    }
    if (maxX < minX || maxY < minY) return null;
    const pad = Math.min(minX, minY, w - 1 - maxX, h - 1 - maxY);
    if (pad <= 1) return null;
    return { sx: minX, sy: minY, sw: maxX - minX + 1, sh: maxY - minY + 1 };
  } catch {
    return null;
  }
}

/** Procedural inked-rect room (fallback when no glyph asset is available). */
function drawProceduralRoom(
  ctx: CanvasRenderingContext2D,
  cx: number,
  cy: number,
  half: number,
  isCurrent: boolean,
  isVisited: boolean,
  isHousing: boolean,
) {
  const x0 = cx - half;
  const y0 = cy - half;
  const s = half * 2;
  ctx.lineJoin = "round";
  if (isCurrent) {
    ctx.globalAlpha = 0.9;
    ctx.fillStyle = CURRENT_FILL;
    ctx.fillRect(x0, y0, s, s);
    ctx.globalAlpha = 1;
    ctx.strokeStyle = INK;
    ctx.lineWidth = 2;
    ctx.strokeRect(x0, y0, s, s);
    ctx.fillStyle = CURRENT_MARK;
    ctx.beginPath();
    ctx.arc(cx, cy, Math.max(2, half * 0.3), 0, Math.PI * 2);
    ctx.fill();
  } else if (isVisited) {
    ctx.globalAlpha = 0.9;
    ctx.fillStyle = isHousing ? HOUSING_FILL : NODE_FILL;
    ctx.fillRect(x0, y0, s, s);
    ctx.globalAlpha = 1;
    ctx.strokeStyle = INK;
    ctx.lineWidth = 1.5;
    ctx.strokeRect(x0, y0, s, s);
  } else {
    ctx.strokeStyle = FOG_INK;
    ctx.lineWidth = 1.5;
    ctx.strokeRect(x0, y0, s, s);
  }
}

/** Procedural quest marker (fallback when no minimap_quest asset). */
function drawProceduralQuest(ctx: CanvasRenderingContext2D, cx: number, cy: number, half: number, pulse: number) {
  ctx.save();
  ctx.globalAlpha = pulse;
  ctx.strokeStyle = QUEST_MARKER;
  ctx.lineWidth = 2.5;
  ctx.strokeRect(cx - half - 3, cy - half - 3, (half + 3) * 2, (half + 3) * 2);
  const my = cy - half - 9;
  ctx.fillStyle = QUEST_MARKER;
  ctx.beginPath();
  ctx.moveTo(cx, my - 5);
  ctx.lineTo(cx + 4, my);
  ctx.lineTo(cx, my + 5);
  ctx.lineTo(cx - 4, my);
  ctx.closePath();
  ctx.fill();
  ctx.restore();
}

/** Pure drawing function — renders the map at the supplied zoom/pan view. */
function renderMap(
  canvas: HTMLCanvasElement,
  visited: Map<string, MapRoom>,
  currentId: string | null,
  bgImage: HTMLImageElement | null,
  questTargetRoomIds: Set<string>,
  serverAssets: Record<string, string>,
  view: View,
  drawGlyph: (ctx: CanvasRenderingContext2D, key: string, cx: number, cy: number, size: number, alpha: number) => boolean,
) {
  const ctx = canvas.getContext("2d");
  if (!ctx) return;

  const rect = canvas.getBoundingClientRect();
  const dpr = window.devicePixelRatio || 1;
  const width = Math.max(1, Math.floor(rect.width));
  const height = Math.max(1, Math.floor(rect.height));

  if (canvas.width !== width * dpr || canvas.height !== height * dpr) {
    canvas.width = width * dpr;
    canvas.height = height * dpr;
  }

  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, width, height);

  // Background: custom map scroll image (zoomed in) or dark fallback. Fixed —
  // it does not pan/zoom with the plotted rooms.
  if (bgImage && bgImage.complete) {
    const zw = width * BG_ZOOM;
    const zh = height * BG_ZOOM;
    const zx = (width - zw) / 2;
    const zy = (height - zh) / 2;
    ctx.drawImage(bgImage, zx, zy, zw, zh);
  } else {
    ctx.fillStyle = BG_COLOR;
    ctx.fillRect(0, 0, width, height);
  }

  if (!currentId) return;

  // Scroll parchment bounds — nodes must stay within these
  const scrollLeft = width * SCROLL_INSET_LEFT;
  const scrollRight = width * (1 - SCROLL_INSET_RIGHT);
  const scrollTop = height * SCROLL_INSET_TOP;
  const scrollBottom = height * (1 - SCROLL_INSET_BOTTOM);

  const nodePad = MAX_CURRENT / 2 + 16;
  const availW = (scrollRight - scrollLeft) - nodePad * 2;
  const availH = (scrollBottom - scrollTop) - nodePad * 2;
  const originX = (scrollLeft + scrollRight) / 2;
  const originY = (scrollTop + scrollBottom) / 2;

  const cell = view.cell;
  const nodeSize = Math.min(MAX_NODE, cell * 0.62);
  const currentSize = Math.min(MAX_CURRENT, cell * 0.74);

  function nodeX(n: MapRoom): number { return originX + (n.x - view.panGx) * cell; }
  function nodeY(n: MapRoom): number { return originY + (n.y - view.panGy) * cell; }

  function inScrollBounds(px: number, py: number): boolean {
    return px >= scrollLeft + nodePad && px <= scrollRight - nodePad &&
           py >= scrollTop + nodePad && py <= scrollBottom - nodePad;
  }

  // Clip everything to scroll parchment bounds
  ctx.save();
  ctx.beginPath();
  ctx.rect(scrollLeft, scrollTop, scrollRight - scrollLeft, scrollBottom - scrollTop);
  ctx.clip();

  // Connecting lines — only between two visible rooms; off-map exits get a tick.
  for (const node of visited.values()) {
    const sx = nodeX(node);
    const sy = nodeY(node);
    const sIn = inScrollBounds(sx, sy);
    for (const [dir, targetId] of Object.entries(node.exits)) {
      if (dir === "up" || dir === "down") continue;
      const target = visited.get(targetId);
      const offset = MAP_OFFSETS[dir];
      if (target) {
        const tx = nodeX(target);
        const ty = nodeY(target);
        if (sIn && inScrollBounds(tx, ty)) {
          ctx.strokeStyle = LINE_COLOR;
          ctx.lineWidth = 2.5;
          ctx.beginPath();
          ctx.moveTo(sx, sy);
          ctx.lineTo(tx, ty);
          ctx.stroke();
        } else if (sIn && offset) {
          // Target clipped — short tick toward it.
          ctx.strokeStyle = TICK_COLOR;
          ctx.lineWidth = 2;
          ctx.beginPath();
          ctx.moveTo(sx + offset.dx * nodeSize * 0.5, sy + offset.dy * nodeSize * 0.5);
          ctx.lineTo(sx + offset.dx * (nodeSize * 0.5 + 8), sy + offset.dy * (nodeSize * 0.5 + 8));
          ctx.stroke();
        }
      }
    }
  }

  // Quest path trail — BFS from current room to nearest quest target
  if (currentId && questTargetRoomIds.size > 0 && !questTargetRoomIds.has(currentId)) {
    const pathEdges = bfsQuestPath(currentId, questTargetRoomIds, visited);
    if (pathEdges.length > 0) {
      const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      const shimmer = reducedMotion
        ? 0.7
        : 0.45 + 0.3 * (0.5 + 0.5 * Math.sin(Date.now() / PATH_SHIMMER_PERIOD * Math.PI * 2));

      for (const [fromId, toId] of pathEdges) {
        const fromNode = visited.get(fromId);
        const toNode = visited.get(toId);
        if (!fromNode || !toNode) continue;
        const sx = nodeX(fromNode);
        const sy = nodeY(fromNode);
        const tx = nodeX(toNode);
        const ty = nodeY(toNode);
        if (!inScrollBounds(sx, sy) && !inScrollBounds(tx, ty)) continue;

        ctx.globalAlpha = shimmer * 0.35;
        ctx.strokeStyle = PATH_GLOW_OUTER;
        ctx.lineWidth = 6;
        ctx.beginPath();
        ctx.moveTo(sx, sy);
        ctx.lineTo(tx, ty);
        ctx.stroke();

        ctx.globalAlpha = shimmer;
        ctx.strokeStyle = PATH_GLOW;
        ctx.lineWidth = 2.5;
        ctx.beginPath();
        ctx.moveTo(sx, sy);
        ctx.lineTo(tx, ty);
        ctx.stroke();
      }
      ctx.globalAlpha = 1;
    }
  }

  // Nodes — terrain-aware glyph stamps (with procedural inked-rect fallback)
  for (const [id, node] of visited.entries()) {
    const x = nodeX(node);
    const y = nodeY(node);
    if (!inScrollBounds(x, y)) continue;

    const isCurrent = id === currentId;
    const isVisited = node.title !== "";
    const isHousing = !!node.housing;
    const size = isCurrent ? currentSize : nodeSize;

    let key: string;
    if (isCurrent) key = A_ROOM_CURRENT;
    else if (!isVisited) key = A_ROOM_FOG;
    else if (isHousing) key = A_ROOM_HOUSING;
    else if (node.terrain && serverAssets[terrainKey(node.terrain)]) key = terrainKey(node.terrain);
    else key = A_ROOM;

    const drew = drawGlyph(ctx, key, x, y, size, 1);
    if (!drew) drawProceduralRoom(ctx, x, y, size / 2, isCurrent, isVisited, isHousing);

    // Quest objective marker
    if (!isCurrent && questTargetRoomIds.has(id)) {
      const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      const pulse = reducedMotion ? 0.7 : 0.4 + 0.45 * (0.5 + 0.5 * Math.sin(Date.now() / QUEST_PULSE_PERIOD * Math.PI * 2));
      if (!drawGlyph(ctx, A_QUEST, x, y, size * 1.3, pulse)) {
        drawProceduralQuest(ctx, x, y, size / 2, pulse);
      }
    }

    // Labels
    if (isVisited && node.title) {
      ctx.font = isCurrent
        ? "bold 12px 'JetBrains Mono', 'Cascadia Mono', monospace"
        : "11px 'JetBrains Mono', 'Cascadia Mono', monospace";
      ctx.textAlign = "center";
      ctx.textBaseline = "top";
      const maxLen = isCurrent ? 18 : 14;
      const label = node.title.length > maxLen ? node.title.slice(0, maxLen - 1) + "…" : node.title;
      const ly = y + size / 2 + 4;
      ctx.lineWidth = 3;
      ctx.strokeStyle = LABEL_OUTLINE;
      ctx.strokeText(label, x, ly);
      ctx.fillStyle = isCurrent ? LABEL_CURRENT : LABEL_VISITED;
      ctx.fillText(label, x, ly);
    }
  }

  // Off-screen quest target edge indicators — gold chevrons at the scroll
  // parchment rim pointing toward quest rooms that are outside visible bounds.
  const reducedMotionEdge = typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const edgePulse = reducedMotionEdge ? 0.6 : 0.35 + 0.45 * (0.5 + 0.5 * Math.sin(Date.now() / QUEST_PULSE_PERIOD * Math.PI * 2));
  for (const targetId of questTargetRoomIds) {
    const targetNode = visited.get(targetId);
    if (!targetNode) continue;
    const tx = nodeX(targetNode);
    const ty = nodeY(targetNode);
    if (inScrollBounds(tx, ty)) continue; // already visible
    const ddx = tx - originX;
    const ddy = ty - originY;
    const dist = Math.sqrt(ddx * ddx + ddy * ddy);
    if (dist === 0) continue;
    const nx = ddx / dist;
    const ny = ddy / dist;
    const padIn = 14;
    const ex = Math.max(scrollLeft + padIn, Math.min(scrollRight - padIn, originX + nx * Math.min(dist, availW / 2)));
    const ey = Math.max(scrollTop + padIn, Math.min(scrollBottom - padIn, originY + ny * Math.min(dist, availH / 2)));
    const angle = Math.atan2(ddy, ddx);
    const chevLen = 7;
    const chevSpread = 0.5;
    const tipX = ex + Math.cos(angle) * 3;
    const tipY = ey + Math.sin(angle) * 3;
    ctx.save();
    ctx.globalAlpha = edgePulse;
    ctx.strokeStyle = QUEST_MARKER;
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(tipX, tipY);
    ctx.lineTo(tipX - Math.cos(angle - chevSpread) * chevLen, tipY - Math.sin(angle - chevSpread) * chevLen);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(tipX, tipY);
    ctx.lineTo(tipX - Math.cos(angle + chevSpread) * chevLen, tipY - Math.sin(angle + chevSpread) * chevLen);
    ctx.stroke();
    ctx.fillStyle = QUEST_MARKER;
    ctx.beginPath();
    ctx.arc(ex, ey, 3, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
  }

  // Restore from scroll-bounds clip
  ctx.restore();
}

/** BFS from currentId to the nearest quest target. Returns edge pairs [from, to]. */
function bfsQuestPath(
  currentId: string,
  targets: Set<string>,
  visited: Map<string, MapRoom>,
): Array<[string, string]> {
  const seen = new Set<string>();
  const parent = new Map<string, string>();
  const queue: string[] = [currentId];
  seen.add(currentId);
  let found: string | null = null;

  while (queue.length > 0) {
    const id = queue.shift()!;
    const node = visited.get(id);
    if (!node) continue;
    for (const [dir, neighborId] of Object.entries(node.exits)) {
      if (dir === "up" || dir === "down") continue;
      if (seen.has(neighborId) || !visited.has(neighborId)) continue;
      seen.add(neighborId);
      parent.set(neighborId, id);
      if (targets.has(neighborId)) { found = neighborId; break; }
      queue.push(neighborId);
    }
    if (found) break;
  }

  if (!found) return [];
  const edges: Array<[string, string]> = [];
  let cur = found;
  while (parent.has(cur)) {
    const prev = parent.get(cur)!;
    edges.push([prev, cur]);
    cur = prev;
  }
  edges.reverse();
  return edges;
}

export function useMiniMap() {
  const mapCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const visitedRef = useRef<Map<string, MapRoom>>(new Map());
  const currentRoomIdRef = useRef<string | null>(null);
  const bgImageRef = useRef<HTMLImageElement | null>(null);
  // Glyph textures keyed by asset key (loaded once; global, not per-zone).
  const glyphCacheRef = useRef<Map<string, GlyphEntry>>(new Map());
  const pulseRafRef = useRef<number | null>(null);
  const drawMapRef = useRef<() => void>(() => {});

  // Pan/zoom view state. zoom 0 = "uninitialised" (pick a default on next draw);
  // pan null = "follow the current room" until the user drags/zooms.
  const zoomRef = useRef(0);
  const panRef = useRef<{ gx: number; gy: number } | null>(null);
  const userAdjustedRef = useRef(false);
  const zoomBoundsRef = useRef({ min: 8, max: MAX_ZOOM });
  const dragRef = useRef<{ x: number; y: number; gx: number; gy: number } | null>(null);

  const drawMap = useCallback(() => {
    const canvas = mapCanvasRef.current;
    if (!canvas) return;

    const assets = gameStateRef.current.serverAssets;

    // Custom scroll background — draw-only, so no crossOrigin needed.
    if (bgImageRef.current == null && assets["map_background"]) {
      const img = new Image();
      img.onload = () => drawMapRef.current();
      img.src = assets["map_background"];
      bgImageRef.current = img;
    }
    const bg = bgImageRef.current?.complete ? bgImageRef.current : null;

    // Resolve the zoom/pan view from the canvas size + zone bounds.
    const rect = canvas.getBoundingClientRect();
    const width = Math.max(1, rect.width);
    const height = Math.max(1, rect.height);
    const visited = visitedRef.current;
    const current = currentRoomIdRef.current ? visited.get(currentRoomIdRef.current) : undefined;

    let minX = Infinity;
    let maxX = -Infinity;
    let minY = Infinity;
    let maxY = -Infinity;
    for (const node of visited.values()) {
      if (node.x < minX) minX = node.x;
      if (node.x > maxX) maxX = node.x;
      if (node.y < minY) minY = node.y;
      if (node.y > maxY) maxY = node.y;
    }
    const hasRooms = minX !== Infinity;
    const spanX = hasRooms ? maxX - minX : 0;
    const spanY = hasRooms ? maxY - minY : 0;

    const nodePad = MAX_CURRENT / 2 + 16;
    const availW = width * (1 - SCROLL_INSET_LEFT - SCROLL_INSET_RIGHT) - nodePad * 2;
    const availH = height * (1 - SCROLL_INSET_TOP - SCROLL_INSET_BOTTOM) - nodePad * 2;
    // Whole-zone fit = minimum zoom (you can't zoom out past seeing everything).
    const fitCell = Math.min(MAX_ZOOM, spanX > 0 ? availW / spanX : MAX_ZOOM, spanY > 0 ? availH / spanY : MAX_ZOOM);
    const minZoom = Math.max(10, Math.min(fitCell, DEFAULT_ZOOM));
    const maxZoom = MAX_ZOOM;
    zoomBoundsRef.current = { min: minZoom, max: maxZoom };

    if (zoomRef.current === 0) zoomRef.current = clamp(DEFAULT_ZOOM, minZoom, maxZoom);
    zoomRef.current = clamp(zoomRef.current, minZoom, maxZoom);

    // Pan: follow the current room (or zone centre) until the user takes over.
    const centerGx = current ? current.x : hasRooms ? (minX + maxX) / 2 : 0;
    const centerGy = current ? current.y : hasRooms ? (minY + maxY) / 2 : 0;
    if (!userAdjustedRef.current || !panRef.current) {
      panRef.current = { gx: centerGx, gy: centerGy };
    }
    if (hasRooms) {
      panRef.current.gx = clamp(panRef.current.gx, minX - 1, maxX + 1);
      panRef.current.gy = clamp(panRef.current.gy, minY - 1, maxY + 1);
    }

    // Draw a room/quest glyph (auto-trimmed, aspect-fit). Lazily loads the
    // texture with crossOrigin so its transparent margin can be measured.
    const drawGlyph = (
      ctx: CanvasRenderingContext2D,
      key: string,
      cx: number,
      cy: number,
      size: number,
      alpha: number,
    ): boolean => {
      const url = assets[key];
      if (!url) return false;
      const entry = glyphCacheRef.current.get(key);
      if (!entry) {
        const img = new Image();
        img.crossOrigin = "anonymous";
        const created: GlyphEntry = { img, trim: null, ready: false };
        glyphCacheRef.current.set(key, created);
        img.onload = () => {
          created.trim = computeTrim(img);
          created.ready = true;
          drawMapRef.current();
        };
        img.src = url;
        return false;
      }
      if (!entry.ready) return false;
      const { img, trim } = entry;
      const sx = trim?.sx ?? 0;
      const sy = trim?.sy ?? 0;
      const sw = trim?.sw ?? img.naturalWidth;
      const sh = trim?.sh ?? img.naturalHeight;
      if (sw <= 0 || sh <= 0) return false;
      const scale = size / Math.max(sw, sh);
      const dw = sw * scale;
      const dh = sh * scale;
      ctx.save();
      ctx.globalAlpha = alpha;
      ctx.drawImage(img, sx, sy, sw, sh, cx - dw / 2, cy - dh / 2, dw, dh);
      ctx.restore();
      return true;
    };

    renderMap(
      canvas,
      visited,
      currentRoomIdRef.current,
      bg,
      gameStateRef.current.questTargetRoomIds,
      assets,
      { cell: zoomRef.current, panGx: panRef.current.gx, panGy: panRef.current.gy },
      drawGlyph,
    );
  }, []);

  // Keep a stable handle to the latest drawMap for async (glyph/bg) reloads.
  useEffect(() => {
    drawMapRef.current = drawMap;
  }, [drawMap]);

  // Centre of the scroll area in CSS px, for cursor-anchored zoom math.
  const scrollOrigin = (rect: DOMRect) => ({
    x: (rect.width * SCROLL_INSET_LEFT + rect.width * (1 - SCROLL_INSET_RIGHT)) / 2,
    y: (rect.height * SCROLL_INSET_TOP + rect.height * (1 - SCROLL_INSET_BOTTOM)) / 2,
  });

  const onMapPointerDown = useCallback((e: React.PointerEvent<HTMLCanvasElement>) => {
    e.currentTarget.setPointerCapture?.(e.pointerId);
    dragRef.current = {
      x: e.clientX,
      y: e.clientY,
      gx: panRef.current?.gx ?? 0,
      gy: panRef.current?.gy ?? 0,
    };
  }, []);

  const onMapPointerMove = useCallback((e: React.PointerEvent<HTMLCanvasElement>) => {
    const d = dragRef.current;
    if (!d) return;
    const cell = zoomRef.current || 1;
    panRef.current = {
      gx: d.gx - (e.clientX - d.x) / cell,
      gy: d.gy - (e.clientY - d.y) / cell,
    };
    userAdjustedRef.current = true;
    drawMap();
  }, [drawMap]);

  const onMapPointerUp = useCallback((e: React.PointerEvent<HTMLCanvasElement>) => {
    e.currentTarget.releasePointerCapture?.(e.pointerId);
    dragRef.current = null;
  }, []);

  const onMapWheel = useCallback((e: React.WheelEvent<HTMLCanvasElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const origin = scrollOrigin(rect);
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;
    const cell = zoomRef.current || DEFAULT_ZOOM;
    const { min, max } = zoomBoundsRef.current;
    const factor = e.deltaY < 0 ? 1.12 : 1 / 1.12;
    const newCell = clamp(cell * factor, min, max);
    if (newCell === cell) return;
    // Keep the grid point under the cursor fixed while zooming.
    const p = panRef.current ?? { gx: 0, gy: 0 };
    const gx = p.gx + (mx - origin.x) / cell;
    const gy = p.gy + (my - origin.y) / cell;
    panRef.current = { gx: gx - (mx - origin.x) / newCell, gy: gy - (my - origin.y) / newCell };
    zoomRef.current = newCell;
    userAdjustedRef.current = true;
    drawMap();
  }, [drawMap]);

  const zoomBy = useCallback((factor: number) => {
    const { min, max } = zoomBoundsRef.current;
    zoomRef.current = clamp((zoomRef.current || DEFAULT_ZOOM) * factor, min, max);
    drawMap();
  }, [drawMap]);

  const zoomIn = useCallback(() => zoomBy(1.25), [zoomBy]);
  const zoomOut = useCallback(() => zoomBy(0.8), [zoomBy]);

  /** Re-centre on the player and reset to the default zoom. */
  const recenter = useCallback(() => {
    userAdjustedRef.current = false;
    panRef.current = null;
    zoomRef.current = 0;
    drawMap();
  }, [drawMap]);

  const updateMap = useCallback(
    (roomId: string, exits: Record<string, string>, title: string, image: string | null, mapX: number, mapY: number, housing?: boolean, terrain?: string | null) => {
      currentRoomIdRef.current = roomId;
      const rooms = visitedRef.current;

      if (!rooms.has(roomId)) {
        rooms.set(roomId, { x: mapX, y: mapY, exits, title, image, housing, terrain: terrain ?? undefined });
      } else {
        const node = rooms.get(roomId)!;
        node.exits = exits;
        node.title = title;
        node.image = image;
        node.housing = housing;
        if (terrain) node.terrain = terrain;
        node.x = mapX;
        node.y = mapY;
      }

      // Pre-place unvisited horizontal neighbors (N/S/E/W only).
      for (const [dir, targetId] of Object.entries(exits)) {
        if (dir === "up" || dir === "down") continue;
        if (rooms.has(targetId)) continue;
        const offset = MAP_OFFSETS[dir];
        if (!offset) continue;
        rooms.set(targetId, { x: mapX + offset.dx, y: mapY + offset.dy, exits: {}, title: "", image: null });
      }

      drawMap();
    },
    [drawMap],
  );

  /** Pre-populate the map with all rooms in a zone as fog nodes. */
  const loadZoneMap = useCallback(
    (zone: string, rooms: Array<{ id: string; x: number; y: number; exits: Record<string, string> }>) => {
      const map = visitedRef.current;
      map.clear();
      currentRoomIdRef.current = null;
      // New zone — reset the view so it re-centres at the default zoom.
      userAdjustedRef.current = false;
      panRef.current = null;
      zoomRef.current = 0;

      for (const r of rooms) {
        map.set(r.id, { x: r.x, y: r.y, exits: r.exits, title: "", image: null });
      }
      drawMap();

      // Also push to the PixiJS compact minimap
      canvasCallbacks.loadZoneMap?.(zone, rooms);
    },
    [drawMap],
  );

  const resetMap = useCallback(() => {
    visitedRef.current.clear();
    currentRoomIdRef.current = null;
    userAdjustedRef.current = false;
    panRef.current = null;
    zoomRef.current = 0;
    drawMap();
  }, [drawMap]);

  /** Start a gentle animation loop for quest marker pulse (call when map is visible). */
  const startPulse = useCallback(() => {
    if (pulseRafRef.current != null) return;
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reducedMotion) return;
    const tick = () => {
      drawMap();
      pulseRafRef.current = requestAnimationFrame(tick);
    };
    pulseRafRef.current = requestAnimationFrame(tick);
  }, [drawMap]);

  /** Stop the animation loop (call when map is hidden). */
  const stopPulse = useCallback(() => {
    if (pulseRafRef.current != null) {
      cancelAnimationFrame(pulseRafRef.current);
      pulseRafRef.current = null;
    }
  }, []);

  return {
    mapCanvasRef,
    drawMap,
    updateMap,
    loadZoneMap,
    resetMap,
    startPulse,
    stopPulse,
    onMapPointerDown,
    onMapPointerMove,
    onMapPointerUp,
    onMapWheel,
    zoomIn,
    zoomOut,
    recenter,
  };
}
