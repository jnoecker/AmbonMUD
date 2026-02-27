import { useCallback, useRef } from "react";
import { MAP_OFFSETS } from "../constants";
import type { MapRoom } from "../types";

export function useMiniMap() {
  const mapCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const visitedRef = useRef<Map<string, MapRoom>>(new Map());
  const currentRoomIdRef = useRef<string | null>(null);

  const drawMap = useCallback(() => {
    const canvas = mapCanvasRef.current;
    if (!canvas) return;
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

    const gradient = ctx.createLinearGradient(0, 0, width, height);
    gradient.addColorStop(0, "#fbf8fd");
    gradient.addColorStop(1, "#eff4fb");
    ctx.fillStyle = gradient;
    ctx.fillRect(0, 0, width, height);

    const currentId = currentRoomIdRef.current;
    if (!currentId) return;
    const current = visitedRef.current.get(currentId);
    if (!current) return;

    const cell = 28;
    const radius = 6;
    const centerX = width / 2;
    const centerY = height / 2;

    ctx.strokeStyle = "rgba(107, 107, 123, 0.46)";
    ctx.lineWidth = 1.4;
    for (const node of visitedRef.current.values()) {
      const sx = centerX + (node.x - current.x) * cell;
      const sy = centerY + (node.y - current.y) * cell;
      for (const targetId of Object.values(node.exits)) {
        const target = visitedRef.current.get(targetId);
        if (!target) continue;
        const tx = centerX + (target.x - current.x) * cell;
        const ty = centerY + (target.y - current.y) * cell;
        ctx.beginPath();
        ctx.moveTo(sx, sy);
        ctx.lineTo(tx, ty);
        ctx.stroke();
      }
    }

    for (const [id, node] of visitedRef.current.entries()) {
      const x = centerX + (node.x - current.x) * cell;
      const y = centerY + (node.y - current.y) * cell;
      if (x < -radius || x > width + radius || y < -radius || y > height + radius) continue;

      const currentNode = id === currentId;
      ctx.fillStyle = currentNode ? "#e8c5d8" : "#b8d8e8";
      ctx.beginPath();
      ctx.arc(x, y, currentNode ? radius + 1.8 : radius, 0, Math.PI * 2);
      ctx.fill();

      ctx.strokeStyle = "rgba(107, 107, 123, 0.66)";
      ctx.lineWidth = 1;
      ctx.stroke();

      if (currentNode) {
        ctx.strokeStyle = "rgba(232, 216, 168, 0.95)";
        ctx.lineWidth = 1.8;
        ctx.beginPath();
        ctx.arc(x, y, radius + 5.8, 0, Math.PI * 2);
        ctx.stroke();
      }
    }
  }, []);

  const updateMap = useCallback(
    (roomId: string, exits: Record<string, string>) => {
      currentRoomIdRef.current = roomId;
      const rooms = visitedRef.current;

      if (!rooms.has(roomId)) {
        let placed = false;
        for (const [dir, neighborId] of Object.entries(exits)) {
          const neighbor = rooms.get(neighborId);
          const offset = MAP_OFFSETS[dir];
          if (!neighbor || !offset) continue;
          rooms.set(roomId, { x: neighbor.x - offset.dx, y: neighbor.y - offset.dy, exits });
          placed = true;
          break;
        }

        if (!placed) {
          if (rooms.size === 0) {
            rooms.set(roomId, { x: 0, y: 0, exits });
          } else {
            const previous = Array.from(rooms.values()).at(-1);
            rooms.set(roomId, { x: (previous?.x ?? 0) + 1, y: previous?.y ?? 0, exits });
          }
        }
      } else {
        const node = rooms.get(roomId);
        if (node) node.exits = exits;
      }

      for (const [dir, targetId] of Object.entries(exits)) {
        if (rooms.has(targetId)) continue;
        const source = rooms.get(roomId);
        const offset = MAP_OFFSETS[dir];
        if (!source || !offset) continue;
        rooms.set(targetId, { x: source.x + offset.dx, y: source.y + offset.dy, exits: {} });
      }

      drawMap();
    },
    [drawMap],
  );

  const resetMap = useCallback(() => {
    visitedRef.current.clear();
    currentRoomIdRef.current = null;
    drawMap();
  }, [drawMap]);

  return {
    mapCanvasRef,
    drawMap,
    updateMap,
    resetMap,
  };
}

