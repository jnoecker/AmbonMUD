import { Container, Graphics } from "pixi.js";
import { canvasCallbacks } from "../GameStateBridge";

const MAP_OFFSETS: Record<string, { dx: number; dy: number }> = {
  north: { dx: 0, dy: -1 },
  south: { dx: 0, dy: 1 },
  east: { dx: 1, dy: 0 },
  west: { dx: -1, dy: 0 },
  up: { dx: 1, dy: -1 },
  down: { dx: -1, dy: 1 },
};

interface MapNode {
  x: number;
  y: number;
  exits: Record<string, string>;
  title: string;
}

const MAP_SIZE = 160;
const CELL = 28;
const NODE_RADIUS = 8;
const CURRENT_RADIUS = 10;
const BG_COLOR = 0x141828;
const BG_ALPHA = 0.85;
const BORDER_COLOR = 0x3a4060;
const NODE_COLOR = 0x4a6080;
const CURRENT_COLOR = 0xb9aed8;
const CURRENT_GLOW = 0xe8d8a8;
const LINE_COLOR = 0x4a5070;
export class Minimap {
  readonly container = new Container();

  private bg = new Graphics();
  private mapGraphics = new Graphics();
  private expandButton = new Graphics();
  private visited = new Map<string, MapNode>();
  private currentRoomId: string | null = null;
  private lastKey = "";

  // Click navigation
  private clickAreas: Array<{ roomId: string; area: Graphics }> = [];

  constructor() {
    // Expand button in bottom-right corner of minimap
    const btn = this.expandButton;
    btn.roundRect(0, 0, 20, 20, 4);
    btn.fill({ color: BG_COLOR, alpha: 0.95 });
    btn.roundRect(0, 0, 20, 20, 4);
    btn.stroke({ color: BORDER_COLOR, width: 1 });
    // Draw expand icon (four outward corner lines)
    const ic = CURRENT_COLOR;
    btn.moveTo(4, 7); btn.lineTo(4, 4); btn.lineTo(7, 4);
    btn.stroke({ color: ic, width: 1.5 });
    btn.moveTo(13, 4); btn.lineTo(16, 4); btn.lineTo(16, 7);
    btn.stroke({ color: ic, width: 1.5 });
    btn.moveTo(16, 13); btn.lineTo(16, 16); btn.lineTo(13, 16);
    btn.stroke({ color: ic, width: 1.5 });
    btn.moveTo(7, 16); btn.lineTo(4, 16); btn.lineTo(4, 13);
    btn.stroke({ color: ic, width: 1.5 });
    btn.x = MAP_SIZE - 24;
    btn.y = MAP_SIZE - 24;
    btn.eventMode = "static";
    btn.cursor = "pointer";
    btn.on("pointerdown", () => {
      canvasCallbacks.openMap?.();
    });

    this.container.addChild(this.bg);
    this.container.addChild(this.mapGraphics);
    this.container.addChild(this.expandButton);
  }

  updateRoom(roomId: string | null, exits: Record<string, string>, title: string) {
    if (!roomId) return;

    const key = `${roomId}:${JSON.stringify(exits)}`;
    if (key === this.lastKey) return;
    this.lastKey = key;

    this.currentRoomId = roomId;

    if (!this.visited.has(roomId)) {
      let placed = false;
      for (const [dir, neighborId] of Object.entries(exits)) {
        const neighbor = this.visited.get(neighborId);
        const offset = MAP_OFFSETS[dir];
        if (!neighbor || !offset) continue;
        this.visited.set(roomId, { x: neighbor.x - offset.dx, y: neighbor.y - offset.dy, exits, title });
        placed = true;
        break;
      }
      if (!placed) {
        if (this.visited.size === 0) {
          this.visited.set(roomId, { x: 0, y: 0, exits, title });
        } else {
          const previous = Array.from(this.visited.values()).at(-1);
          this.visited.set(roomId, { x: (previous?.x ?? 0) + 1, y: previous?.y ?? 0, exits, title });
        }
      }
    } else {
      const node = this.visited.get(roomId)!;
      node.exits = exits;
      node.title = title;
    }

    // Place unknown neighbors
    for (const [dir, targetId] of Object.entries(exits)) {
      if (this.visited.has(targetId)) continue;
      const source = this.visited.get(roomId);
      const offset = MAP_OFFSETS[dir];
      if (!source || !offset) continue;
      this.visited.set(targetId, { x: source.x + offset.dx, y: source.y + offset.dy, exits: {}, title: "" });
    }

    this.redraw();
  }

  reset() {
    this.visited.clear();
    this.currentRoomId = null;
    this.lastKey = "";
    this.redraw();
  }

  layout(x: number, y: number) {
    this.container.x = x;
    this.container.y = y;
  }

  private redraw() {
    // Clear click areas
    for (const { area } of this.clickAreas) {
      this.container.removeChild(area);
      area.destroy();
    }
    this.clickAreas = [];

    this.bg.clear();
    this.bg.roundRect(0, 0, MAP_SIZE, MAP_SIZE, 8);
    this.bg.fill({ color: BG_COLOR, alpha: BG_ALPHA });
    this.bg.roundRect(0, 0, MAP_SIZE, MAP_SIZE, 8);
    this.bg.stroke({ color: BORDER_COLOR, width: 1 });

    this.mapGraphics.clear();

    if (!this.currentRoomId) return;
    const current = this.visited.get(this.currentRoomId);
    if (!current) return;

    const cx = MAP_SIZE / 2;
    const cy = MAP_SIZE / 2;

    // Draw connecting lines
    for (const node of this.visited.values()) {
      const sx = cx + (node.x - current.x) * CELL;
      const sy = cy + (node.y - current.y) * CELL;

      for (const targetId of Object.values(node.exits)) {
        const target = this.visited.get(targetId);
        if (!target) continue;
        const tx = cx + (target.x - current.x) * CELL;
        const ty = cy + (target.y - current.y) * CELL;

        // Only draw if at least one end is visible
        if (this.inBounds(sx, sy) || this.inBounds(tx, ty)) {
          this.mapGraphics.moveTo(sx, sy);
          this.mapGraphics.lineTo(tx, ty);
          this.mapGraphics.stroke({ color: LINE_COLOR, width: 1.5, alpha: 0.6 });
        }
      }
    }

    // Draw nodes
    for (const [id, node] of this.visited.entries()) {
      const nx = cx + (node.x - current.x) * CELL;
      const ny = cy + (node.y - current.y) * CELL;

      if (!this.inBounds(nx, ny)) continue;

      const isCurrent = id === this.currentRoomId;

      if (isCurrent) {
        // Glow ring
        this.mapGraphics.circle(nx, ny, CURRENT_RADIUS + 3);
        this.mapGraphics.stroke({ color: CURRENT_GLOW, width: 1.5, alpha: 0.6 });
        // Current node
        this.mapGraphics.circle(nx, ny, CURRENT_RADIUS);
        this.mapGraphics.fill(CURRENT_COLOR);
      } else {
        this.mapGraphics.circle(nx, ny, NODE_RADIUS);
        this.mapGraphics.fill({ color: NODE_COLOR, alpha: node.title ? 1 : 0.4 });
        this.mapGraphics.circle(nx, ny, NODE_RADIUS);
        this.mapGraphics.stroke({ color: 0x5a6a90, width: 1, alpha: 0.5 });

        // Clickable navigation for adjacent rooms
        if (this.isAdjacentToCurrent(id)) {
          const area = new Graphics();
          area.circle(nx, ny, NODE_RADIUS + 2);
          area.fill({ color: 0x000000, alpha: 0.001 });
          area.eventMode = "static";
          area.cursor = "pointer";

          const dir = this.getDirectionTo(id);
          if (dir) {
            area.on("pointerdown", () => {
              canvasCallbacks.sendCommand?.(dir);
            });
          }

          this.container.addChild(area);
          this.clickAreas.push({ roomId: id, area });
        }
      }
    }

  }

  private inBounds(x: number, y: number): boolean {
    const margin = NODE_RADIUS + 2;
    return x >= margin && x <= MAP_SIZE - margin && y >= margin && y <= MAP_SIZE - margin;
  }

  private isAdjacentToCurrent(targetId: string): boolean {
    if (!this.currentRoomId) return false;
    const current = this.visited.get(this.currentRoomId);
    if (!current) return false;
    return Object.values(current.exits).includes(targetId);
  }

  private getDirectionTo(targetId: string): string | null {
    if (!this.currentRoomId) return null;
    const current = this.visited.get(this.currentRoomId);
    if (!current) return null;
    for (const [dir, id] of Object.entries(current.exits)) {
      if (id === targetId) return dir;
    }
    return null;
  }

  destroy() {
    this.container.destroy({ children: true });
  }
}
