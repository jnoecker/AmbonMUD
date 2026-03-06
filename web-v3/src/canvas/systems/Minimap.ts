import { Assets, Container, Graphics, Sprite, Texture } from "pixi.js";
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
  image: string | null;
}

const MAP_DIAMETER = 280;
const MAP_RADIUS = MAP_DIAMETER / 2;
const CELL = 56;
const NODE_RADIUS = 16;
const CURRENT_RADIUS = 20;
const BG_COLOR = 0x141828;
const BG_ALPHA = 0.88;
const BORDER_COLOR = 0x3a4060;
const OUTER_GLOW_COLOR = 0x4a5880;
const NODE_COLOR = 0x4a6080;
const CURRENT_COLOR = 0xb9aed8;
const CURRENT_GLOW = 0xe8d8a8;
const LINE_COLOR = 0x4a5070;
const FOG_COLOR = 0x2a3050;

export class Minimap {
  readonly container = new Container();

  private bg = new Graphics();
  private clipMask = new Graphics();
  private mapGraphics = new Graphics();
  private nodeContainer = new Container();
  private expandButton = new Graphics();
  private visited = new Map<string, MapNode>();
  private currentRoomId: string | null = null;
  private lastKey = "";

  // Sprite cache for room thumbnails
  private thumbSprites = new Map<string, Sprite>();
  private thumbMasks = new Map<string, Graphics>();
  private loadingImages = new Set<string>();
  private fogTexture: Texture | null = null;

  // Click navigation
  private clickAreas: Array<{ roomId: string; area: Graphics }> = [];

  constructor() {
    // Circular clip mask — everything inside the map is clipped to this
    this.clipMask.circle(MAP_RADIUS, MAP_RADIUS, MAP_RADIUS - 2);
    this.clipMask.fill(0xffffff);

    // Expand button at bottom-center of the circle
    const btn = this.expandButton;
    btn.roundRect(0, 0, 22, 22, 4);
    btn.fill({ color: BG_COLOR, alpha: 0.95 });
    btn.roundRect(0, 0, 22, 22, 4);
    btn.stroke({ color: BORDER_COLOR, width: 1 });
    const ic = CURRENT_COLOR;
    btn.moveTo(5, 8); btn.lineTo(5, 5); btn.lineTo(8, 5);
    btn.stroke({ color: ic, width: 1.5 });
    btn.moveTo(14, 5); btn.lineTo(17, 5); btn.lineTo(17, 8);
    btn.stroke({ color: ic, width: 1.5 });
    btn.moveTo(17, 14); btn.lineTo(17, 17); btn.lineTo(14, 17);
    btn.stroke({ color: ic, width: 1.5 });
    btn.moveTo(8, 17); btn.lineTo(5, 17); btn.lineTo(5, 14);
    btn.stroke({ color: ic, width: 1.5 });
    btn.x = MAP_RADIUS - 11;
    btn.y = MAP_DIAMETER - 30;
    btn.eventMode = "static";
    btn.cursor = "pointer";
    btn.on("pointerdown", () => {
      canvasCallbacks.openMap?.();
    });

    this.loadFogTexture();

    // Inner content group that gets masked
    const inner = new Container();
    inner.addChild(this.mapGraphics);
    inner.addChild(this.nodeContainer);
    inner.mask = this.clipMask;

    this.container.addChild(this.bg);
    this.container.addChild(this.clipMask);
    this.container.addChild(inner);
    this.container.addChild(this.expandButton);
  }

  get diameter(): number {
    return MAP_DIAMETER;
  }

  updateRoom(roomId: string | null, exits: Record<string, string>, title: string, image: string | null) {
    if (!roomId) return;

    const key = `${roomId}:${JSON.stringify(exits)}:${image ?? ""}`;
    if (key === this.lastKey) return;
    this.lastKey = key;

    this.currentRoomId = roomId;

    if (!this.visited.has(roomId)) {
      let placed = false;
      for (const [dir, neighborId] of Object.entries(exits)) {
        const neighbor = this.visited.get(neighborId);
        const offset = MAP_OFFSETS[dir];
        if (!neighbor || !offset) continue;
        this.visited.set(roomId, { x: neighbor.x - offset.dx, y: neighbor.y - offset.dy, exits, title, image });
        placed = true;
        break;
      }
      if (!placed) {
        if (this.visited.size === 0) {
          this.visited.set(roomId, { x: 0, y: 0, exits, title, image });
        } else {
          const previous = Array.from(this.visited.values()).at(-1);
          this.visited.set(roomId, { x: (previous?.x ?? 0) + 1, y: previous?.y ?? 0, exits, title, image });
        }
      }
    } else {
      const node = this.visited.get(roomId)!;
      node.exits = exits;
      node.title = title;
      node.image = image;
    }

    // Place unknown neighbors
    for (const [dir, targetId] of Object.entries(exits)) {
      if (this.visited.has(targetId)) continue;
      const source = this.visited.get(roomId);
      const offset = MAP_OFFSETS[dir];
      if (!source || !offset) continue;
      this.visited.set(targetId, { x: source.x + offset.dx, y: source.y + offset.dy, exits: {}, title: "", image: null });
    }

    this.redraw();
  }

  reset() {
    this.visited.clear();
    this.currentRoomId = null;
    this.lastKey = "";
    this.clearThumbs();
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

    // Draw circular background with decorative border
    this.bg.clear();
    // Outer glow ring
    this.bg.circle(MAP_RADIUS, MAP_RADIUS, MAP_RADIUS);
    this.bg.stroke({ color: OUTER_GLOW_COLOR, width: 2, alpha: 0.4 });
    // Main fill
    this.bg.circle(MAP_RADIUS, MAP_RADIUS, MAP_RADIUS - 1);
    this.bg.fill({ color: BG_COLOR, alpha: BG_ALPHA });
    // Inner accent ring
    this.bg.circle(MAP_RADIUS, MAP_RADIUS, MAP_RADIUS - 3);
    this.bg.stroke({ color: BORDER_COLOR, width: 1, alpha: 0.6 });

    this.mapGraphics.clear();

    // Hide all existing thumbs
    for (const sprite of this.thumbSprites.values()) {
      sprite.visible = false;
    }

    if (!this.currentRoomId) return;
    const current = this.visited.get(this.currentRoomId);
    if (!current) return;

    const cx = MAP_RADIUS;
    const cy = MAP_RADIUS;

    // Draw connecting lines
    for (const node of this.visited.values()) {
      const sx = cx + (node.x - current.x) * CELL;
      const sy = cy + (node.y - current.y) * CELL;

      for (const targetId of Object.values(node.exits)) {
        const target = this.visited.get(targetId);
        if (!target) continue;
        const tx = cx + (target.x - current.x) * CELL;
        const ty = cy + (target.y - current.y) * CELL;

        if (this.inBounds(sx, sy) || this.inBounds(tx, ty)) {
          this.mapGraphics.moveTo(sx, sy);
          this.mapGraphics.lineTo(tx, ty);
          this.mapGraphics.stroke({ color: LINE_COLOR, width: 2, alpha: 0.5 });
        }
      }
    }

    // Draw nodes
    for (const [id, node] of this.visited.entries()) {
      const nx = cx + (node.x - current.x) * CELL;
      const ny = cy + (node.y - current.y) * CELL;

      if (!this.inBounds(nx, ny)) continue;

      const isCurrent = id === this.currentRoomId;
      const radius = isCurrent ? CURRENT_RADIUS : NODE_RADIUS;
      const visited = node.title !== "";

      if (isCurrent) {
        // Glow ring
        this.mapGraphics.circle(nx, ny, radius + 4);
        this.mapGraphics.stroke({ color: CURRENT_GLOW, width: 2, alpha: 0.5 });
      }

      if (visited) {
        this.mapGraphics.circle(nx, ny, radius);
        this.mapGraphics.fill({ color: isCurrent ? CURRENT_COLOR : NODE_COLOR });
        this.mapGraphics.circle(nx, ny, radius);
        this.mapGraphics.stroke({ color: isCurrent ? CURRENT_GLOW : 0x5a6a90, width: 1, alpha: isCurrent ? 0.8 : 0.5 });
      } else {
        this.mapGraphics.circle(nx, ny, radius);
        this.mapGraphics.fill({ color: FOG_COLOR, alpha: 0.5 });
        this.mapGraphics.circle(nx, ny, radius);
        this.mapGraphics.stroke({ color: 0x3a4060, width: 1, alpha: 0.35 });
        // Show fog-of-war texture for unexplored rooms
        if (this.fogTexture) {
          this.ensureThumb(`__fog__${id}`, null, nx, ny, radius, 0.7, this.fogTexture);
        }
      }

      if (node.image) {
        this.ensureThumb(id, node.image, nx, ny, radius, isCurrent ? 1 : 0.8, null);
      }

      // Clickable navigation for adjacent rooms
      if (!isCurrent && this.isAdjacentToCurrent(id)) {
        const area = new Graphics();
        area.circle(nx, ny, radius + 3);
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

  private ensureThumb(roomId: string, imagePath: string | null, nx: number, ny: number, radius: number, alpha: number, preloaded: Texture | null) {
    const existing = this.thumbSprites.get(roomId);
    if (existing) {
      existing.x = nx;
      existing.y = ny;
      existing.width = radius * 2;
      existing.height = radius * 2;
      existing.alpha = alpha;
      existing.visible = true;

      const mask = this.thumbMasks.get(roomId);
      if (mask) {
        mask.clear();
        mask.circle(nx, ny, radius);
        mask.fill(0xffffff);
      }
      return;
    }

    if (preloaded) {
      this.createThumbSprite(roomId, preloaded, nx, ny, radius, alpha);
      return;
    }

    if (!imagePath || this.loadingImages.has(roomId)) return;
    this.loadingImages.add(roomId);

    Assets.load(imagePath).then((texture: Texture) => {
      this.loadingImages.delete(roomId);
      this.createThumbSprite(roomId, texture, nx, ny, radius, alpha);
    }).catch(() => {
      this.loadingImages.delete(roomId);
    });
  }

  private createThumbSprite(roomId: string, texture: Texture, nx: number, ny: number, radius: number, alpha: number) {
    const sprite = new Sprite(texture);
    sprite.anchor.set(0.5);
    sprite.width = radius * 2;
    sprite.height = radius * 2;
    sprite.x = nx;
    sprite.y = ny;
    sprite.alpha = alpha;
    sprite.eventMode = "none";

    const mask = new Graphics();
    mask.circle(nx, ny, radius);
    mask.fill(0xffffff);
    sprite.mask = mask;

    this.nodeContainer.addChild(mask);
    this.nodeContainer.addChild(sprite);
    this.thumbSprites.set(roomId, sprite);
    this.thumbMasks.set(roomId, mask);
  }

  private clearThumbs() {
    for (const sprite of this.thumbSprites.values()) {
      sprite.destroy();
    }
    for (const mask of this.thumbMasks.values()) {
      mask.destroy();
    }
    this.thumbSprites.clear();
    this.thumbMasks.clear();
    this.loadingImages.clear();
  }

  private async loadFogTexture() {
    try {
      this.fogTexture = await Assets.load("/images/global_assets/minimap-unexplored.png");
    } catch { /* no fog texture */ }
  }

  private inBounds(x: number, y: number): boolean {
    // Circle-based bounds check — only show nodes within the map circle
    const dx = x - MAP_RADIUS;
    const dy = y - MAP_RADIUS;
    const maxR = MAP_RADIUS - CURRENT_RADIUS - 6;
    return dx * dx + dy * dy <= maxR * maxR;
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
    this.clearThumbs();
    this.container.destroy({ children: true });
  }
}
