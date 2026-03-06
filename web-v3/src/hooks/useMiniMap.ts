import { useCallback, useRef } from "react";
import { MAP_OFFSETS } from "../constants";
import type { MapRoom } from "../types";

const BG_COLOR = "#141828";
const NODE_FILL = "#4a6080";
const CURRENT_FILL = "#b9aed8";
const CURRENT_GLOW = "rgba(232, 216, 168, 0.5)";
const CURRENT_GLOW_STROKE = "rgba(232, 216, 168, 0.8)";
const LINE_STROKE = "rgba(40, 35, 28, 0.6)";
const FOG_FILL = "rgba(42, 48, 80, 0.5)";
const FOG_STROKE = "rgba(58, 64, 96, 0.35)";
const NODE_STROKE = "rgba(90, 106, 144, 0.5)";
const CELL = 48;
const NODE_RADIUS = 18;
const CURRENT_RADIUS = 24;

/** Pure drawing function — no hooks, no closures over React state */
function renderMap(
  canvas: HTMLCanvasElement,
  visited: Map<string, MapRoom>,
  currentId: string | null,
  imageCache: Map<string, HTMLImageElement>,
  loadingImages: Set<string>,
  fogImage: HTMLImageElement | null,
  bgImage: HTMLImageElement | null,
  scheduleRedraw: () => void,
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

  // Background: map scroll image or dark fallback
  if (bgImage && bgImage.complete) {
    ctx.drawImage(bgImage, 0, 0, width, height);
    // Slight dark overlay so nodes remain readable
    ctx.fillStyle = "rgba(10, 12, 22, 0.25)";
    ctx.fillRect(0, 0, width, height);
  } else {
    ctx.fillStyle = BG_COLOR;
    ctx.fillRect(0, 0, width, height);
  }

  if (!currentId) return;
  const current = visited.get(currentId);
  if (!current) return;

  const centerX = width / 2;
  const centerY = height / 2;

  // Connecting lines
  ctx.strokeStyle = LINE_STROKE;
  ctx.lineWidth = 2;
  for (const node of visited.values()) {
    const sx = centerX + (node.x - current.x) * CELL;
    const sy = centerY + (node.y - current.y) * CELL;
    for (const targetId of Object.values(node.exits)) {
      const target = visited.get(targetId);
      if (!target) continue;
      const tx = centerX + (target.x - current.x) * CELL;
      const ty = centerY + (target.y - current.y) * CELL;
      if ((sx >= -20 && sx <= width + 20 && sy >= -20 && sy <= height + 20) ||
          (tx >= -20 && tx <= width + 20 && ty >= -20 && ty <= height + 20)) {
        ctx.beginPath();
        ctx.moveTo(sx, sy);
        ctx.lineTo(tx, ty);
        ctx.stroke();
      }
    }
  }

  // Nodes
  for (const [id, node] of visited.entries()) {
    const x = centerX + (node.x - current.x) * CELL;
    const y = centerY + (node.y - current.y) * CELL;
    if (x < -30 || x > width + 30 || y < -30 || y > height + 30) continue;

    const isCurrent = id === currentId;
    const radius = isCurrent ? CURRENT_RADIUS : NODE_RADIUS;
    const isVisited = node.title !== "";

    if (isCurrent) {
      ctx.strokeStyle = CURRENT_GLOW;
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(x, y, radius + 4, 0, Math.PI * 2);
      ctx.stroke();
    }

    ctx.beginPath();
    ctx.arc(x, y, radius, 0, Math.PI * 2);
    ctx.fillStyle = isVisited ? (isCurrent ? CURRENT_FILL : NODE_FILL) : FOG_FILL;
    ctx.fill();

    ctx.beginPath();
    ctx.arc(x, y, radius, 0, Math.PI * 2);
    if (isVisited) {
      ctx.strokeStyle = isCurrent ? CURRENT_GLOW_STROKE : NODE_STROKE;
      ctx.lineWidth = isCurrent ? 1.5 : 1;
    } else {
      ctx.strokeStyle = FOG_STROKE;
      ctx.lineWidth = 1;
    }
    ctx.stroke();

    // Fog-of-war thumbnail for unexplored rooms
    if (!isVisited && fogImage && fogImage.complete) {
      ctx.save();
      ctx.beginPath();
      ctx.arc(x, y, radius, 0, Math.PI * 2);
      ctx.clip();
      ctx.globalAlpha = 0.7;
      ctx.drawImage(fogImage, x - radius, y - radius, radius * 2, radius * 2);
      ctx.globalAlpha = 1;
      ctx.restore();
    }

    // Room image thumbnail
    if (node.image) {
      const cached = imageCache.get(id);
      if (cached && cached.complete) {
        ctx.save();
        ctx.beginPath();
        ctx.arc(x, y, radius, 0, Math.PI * 2);
        ctx.clip();
        ctx.globalAlpha = isCurrent ? 1 : 0.8;
        ctx.drawImage(cached, x - radius, y - radius, radius * 2, radius * 2);
        ctx.globalAlpha = 1;
        ctx.restore();
      } else if (!loadingImages.has(id)) {
        loadingImages.add(id);
        const img = new Image();
        img.onload = () => {
          loadingImages.delete(id);
          imageCache.set(id, img);
          scheduleRedraw();
        };
        img.onerror = () => {
          loadingImages.delete(id);
        };
        img.src = node.image;
      }
    }

    // Labels
    if (isVisited && node.title) {
      if (isCurrent) {
        ctx.fillStyle = "rgba(232, 216, 168, 0.9)";
        ctx.font = "bold 11px 'JetBrains Mono', 'Cascadia Mono', monospace";
      } else {
        ctx.fillStyle = "rgba(216, 220, 239, 0.7)";
        ctx.font = "10px 'JetBrains Mono', 'Cascadia Mono', monospace";
      }
      ctx.textAlign = "center";
      ctx.textBaseline = "top";
      const maxLen = isCurrent ? 18 : 14;
      const label = node.title.length > maxLen ? node.title.slice(0, maxLen - 1) + "\u2026" : node.title;
      ctx.fillText(label, x, y + radius + (isCurrent ? 5 : 3));
    }
  }
}

export function useMiniMap() {
  const mapCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const visitedRef = useRef<Map<string, MapRoom>>(new Map());
  const currentRoomIdRef = useRef<string | null>(null);
  const imageCache = useRef<Map<string, HTMLImageElement>>(new Map());
  const loadingImages = useRef<Set<string>>(new Set());
  const fogImageRef = useRef<HTMLImageElement | null>(null);
  const bgImageRef = useRef<HTMLImageElement | null>(null);

  // Pre-load the fog-of-war icon and map background
  if (fogImageRef.current == null) {
    const img = new Image();
    img.src = "/images/global_assets/minimap-unexplored.png";
    fogImageRef.current = img;
  }
  if (bgImageRef.current == null) {
    const img = new Image();
    img.src = "/images/global_assets/map_background.png";
    bgImageRef.current = img;
  }

  const drawMap = useCallback(() => {
    const canvas = mapCanvasRef.current;
    if (!canvas) return;
    const fog = fogImageRef.current?.complete ? fogImageRef.current : null;
    const bg = bgImageRef.current?.complete ? bgImageRef.current : null;
    renderMap(
      canvas,
      visitedRef.current,
      currentRoomIdRef.current,
      imageCache.current,
      loadingImages.current,
      fog,
      bg,
      () => {
        const c = mapCanvasRef.current;
        if (c) {
          const f = fogImageRef.current?.complete ? fogImageRef.current : null;
          const b = bgImageRef.current?.complete ? bgImageRef.current : null;
          renderMap(c, visitedRef.current, currentRoomIdRef.current, imageCache.current, loadingImages.current, f, b, () => {});
        }
      },
    );
  }, []);

  const updateMap = useCallback(
    (roomId: string, exits: Record<string, string>, title: string, image: string | null) => {
      currentRoomIdRef.current = roomId;
      const rooms = visitedRef.current;

      if (!rooms.has(roomId)) {
        let placed = false;
        for (const [dir, neighborId] of Object.entries(exits)) {
          const neighbor = rooms.get(neighborId);
          const offset = MAP_OFFSETS[dir];
          if (!neighbor || !offset) continue;
          rooms.set(roomId, { x: neighbor.x - offset.dx, y: neighbor.y - offset.dy, exits, title, image });
          placed = true;
          break;
        }

        if (!placed) {
          if (rooms.size === 0) {
            rooms.set(roomId, { x: 0, y: 0, exits, title, image });
          } else {
            const previous = Array.from(rooms.values()).at(-1);
            rooms.set(roomId, { x: (previous?.x ?? 0) + 1, y: previous?.y ?? 0, exits, title, image });
          }
        }
      } else {
        const node = rooms.get(roomId);
        if (node) {
          node.exits = exits;
          node.title = title;
          node.image = image;
        }
      }

      for (const [dir, targetId] of Object.entries(exits)) {
        if (rooms.has(targetId)) continue;
        const source = rooms.get(roomId);
        const offset = MAP_OFFSETS[dir];
        if (!source || !offset) continue;
        rooms.set(targetId, { x: source.x + offset.dx, y: source.y + offset.dy, exits: {}, title: "", image: null });
      }

      drawMap();
    },
    [drawMap],
  );

  const resetMap = useCallback(() => {
    visitedRef.current.clear();
    currentRoomIdRef.current = null;
    imageCache.current.clear();
    loadingImages.current.clear();
    drawMap();
  }, [drawMap]);

  return {
    mapCanvasRef,
    drawMap,
    updateMap,
    resetMap,
  };
}
