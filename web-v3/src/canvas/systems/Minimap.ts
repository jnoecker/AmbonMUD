import { Assets, Container, Graphics, Sprite, Texture } from "pixi.js";
import { canvasCallbacks, gameStateRef } from "../GameStateBridge";
import { MAP_OFFSETS } from "../../constants";

/** Directions that represent the same horizontal plane. Up/down exits are shown
 *  as indicators on the current room but don't place nodes on the minimap. */
const HORIZONTAL_DIRS = new Set(["north", "south", "east", "west"]);

interface MapNode {
  x: number;
  y: number;
  exits: Record<string, string>;
  title: string;
  image: string | null;
}

const DEFAULT_DIAMETER = 140;
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
  private inner = new Container();
  private visited = new Map<string, MapNode>();
  private currentRoomId: string | null = null;
  private lastKey = "";

  // Current sizing — updated via setDiameter()
  private _diameter = DEFAULT_DIAMETER;
  private _radius = DEFAULT_DIAMETER / 2;
  private _cell = 44;
  private _nodeRadius = 12;
  private _currentRadius = 16;

  // Sprite cache for room thumbnails
  private thumbSprites = new Map<string, Sprite>();
  private thumbMasks = new Map<string, Graphics>();
  private loadingImages = new Set<string>();
  private fogTexture: Texture | null = null;
  private fogAssetLoaded = false;

  // Click navigation
  private clickAreas: Array<{ roomId: string; area: Graphics }> = [];

  constructor() {
    this.rebuildExpandButton();

    // Inner content group that gets masked
    this.inner.addChild(this.mapGraphics);
    this.inner.addChild(this.nodeContainer);

    this.container.addChild(this.bg);
    this.container.addChild(this.clipMask);
    this.container.addChild(this.inner);
    this.container.addChild(this.expandButton);

    this.applyDiameter();
  }

  get diameter(): number {
    return this._diameter;
  }

  /** Resize the minimap. Recalculates proportional node/cell sizes. */
  setDiameter(d: number) {
    if (d === this._diameter) return;
    this._diameter = d;
    this._radius = d / 2;
    // Scale cell/node sizes proportionally
    this._cell = Math.round(d * 0.31);
    this._nodeRadius = Math.round(d * 0.085);
    this._currentRadius = Math.round(d * 0.11);
    this.applyDiameter();
    this.lastKey = ""; // force redraw
  }

  private applyDiameter() {
    const r = this._radius;
    const d = this._diameter;

    // Rebuild clip mask for new size
    this.clipMask.clear();
    this.clipMask.circle(r, r, r - 2);
    this.clipMask.fill(0xffffff);
    this.inner.mask = this.clipMask;

    // Reposition expand button
    this.expandButton.x = r - 11;
    this.expandButton.y = d - 28;
  }

  private rebuildExpandButton() {
    const btn = this.expandButton;
    btn.clear();
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
    btn.eventMode = "static";
    btn.cursor = "pointer";
    btn.on("pointerdown", () => {
      canvasCallbacks.openMap?.();
    });
  }

  updateRoom(roomId: string | null, exits: Record<string, string>, title: string, image: string | null, mapX: number, mapY: number) {
    if (!roomId) return;

    // Reload fog texture once Server.Assets GMCP arrives
    if (!this.fogAssetLoaded && Object.keys(gameStateRef.current.serverAssets).length > 0) {
      this.fogAssetLoaded = true;
      this.loadFogTexture();
    }

    const key = `${roomId}:${mapX},${mapY}:${JSON.stringify(exits)}:${image ?? ""}`;
    if (key === this.lastKey) return;
    this.lastKey = key;

    this.currentRoomId = roomId;

    if (!this.visited.has(roomId)) {
      // Use server-provided coordinates directly
      this.visited.set(roomId, { x: mapX, y: mapY, exits, title, image });
    } else {
      const node = this.visited.get(roomId)!;
      node.exits = exits;
      node.title = title;
      node.image = image;
      node.x = mapX;
      node.y = mapY;
    }

    // Pre-place unvisited horizontal neighbors (N/S/E/W only).
    // Up/down exits are shown as indicators on the current room, not as separate nodes.
    for (const [dir, targetId] of Object.entries(exits)) {
      if (!HORIZONTAL_DIRS.has(dir)) continue;
      if (this.visited.has(targetId)) continue;
      const offset = MAP_OFFSETS[dir];
      if (!offset) continue;
      this.visited.set(targetId, { x: mapX + offset.dx, y: mapY + offset.dy, exits: {}, title: "", image: null });
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
    const R = this._radius;
    const D = this._diameter;
    const CELL = this._cell;
    const NODE_R = this._nodeRadius;
    const CUR_R = this._currentRadius;

    // Clear click areas
    for (const { area } of this.clickAreas) {
      this.container.removeChild(area);
      area.destroy();
    }
    this.clickAreas = [];

    // Draw circular background with decorative border
    this.bg.clear();
    this.bg.circle(R, R, R);
    this.bg.stroke({ color: OUTER_GLOW_COLOR, width: 2, alpha: 0.4 });
    this.bg.circle(R, R, R - 1);
    this.bg.fill({ color: BG_COLOR, alpha: BG_ALPHA });
    this.bg.circle(R, R, R - 3);
    this.bg.stroke({ color: BORDER_COLOR, width: 1, alpha: 0.6 });

    this.mapGraphics.clear();

    // Hide all existing thumbs
    for (const sprite of this.thumbSprites.values()) {
      sprite.visible = false;
    }

    if (!this.currentRoomId) return;
    const current = this.visited.get(this.currentRoomId);
    if (!current) return;

    const cx = R;
    const cy = R;

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
      const radius = isCurrent ? CUR_R : NODE_R;
      const visited = node.title !== "";

      if (isCurrent) {
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
        if (this.fogTexture) {
          this.ensureThumb(`__fog__${id}`, null, nx, ny, radius, 0.7, this.fogTexture);
        }
      }

      if (node.image) {
        this.ensureThumb(id, node.image, nx, ny, radius, isCurrent ? 1 : 0.8, null);
      }

      // Up/down indicators on the current room
      if (isCurrent) {
        const hasUp = "up" in node.exits;
        const hasDown = "down" in node.exits;
        const chev = Math.max(4, Math.round(D * 0.035));
        if (hasUp) {
          this.mapGraphics.moveTo(nx - chev, ny - radius - chev);
          this.mapGraphics.lineTo(nx, ny - radius - chev * 2);
          this.mapGraphics.lineTo(nx + chev, ny - radius - chev);
          this.mapGraphics.stroke({ color: CURRENT_GLOW, width: 2, alpha: 0.7 });
        }
        if (hasDown) {
          this.mapGraphics.moveTo(nx - chev, ny + radius + chev);
          this.mapGraphics.lineTo(nx, ny + radius + chev * 2);
          this.mapGraphics.lineTo(nx + chev, ny + radius + chev);
          this.mapGraphics.stroke({ color: CURRENT_GLOW, width: 2, alpha: 0.7 });
        }
      }
    }

    // Click areas for navigation — separate pass so they're always on top.
    if (current) {
      for (const [dir, targetId] of Object.entries(current.exits)) {
        if (!HORIZONTAL_DIRS.has(dir)) {
          const offset = MAP_OFFSETS[dir];
          if (!offset) continue;
          const indX = cx + offset.dx * (CUR_R + 14);
          const indY = cy + offset.dy * (CUR_R + 14);
          const area = new Graphics();
          area.circle(indX, indY, 12);
          area.fill({ color: 0x000000, alpha: 0.001 });
          area.eventMode = "static";
          area.cursor = "pointer";
          area.on("pointerdown", () => { canvasCallbacks.sendCommand?.(dir); });
          this.container.addChild(area);
          this.clickAreas.push({ roomId: targetId, area });
          continue;
        }

        const targetNode = this.visited.get(targetId);
        if (!targetNode) continue;
        const tnx = cx + (targetNode.x - current.x) * CELL;
        const tny = cy + (targetNode.y - current.y) * CELL;
        if (!this.inBounds(tnx, tny)) continue;

        const area = new Graphics();
        area.circle(tnx, tny, NODE_R + 3);
        area.fill({ color: 0x000000, alpha: 0.001 });
        area.eventMode = "static";
        area.cursor = "pointer";
        area.on("pointerdown", () => { canvasCallbacks.sendCommand?.(dir); });
        this.container.addChild(area);
        this.clickAreas.push({ roomId: targetId, area });
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
      this.fogTexture = await Assets.load(
        gameStateRef.current.serverAssets["minimap_unexplored"] ?? "/images/global_assets/minimap-unexplored.png",
      );
    } catch { /* no fog texture */ }
  }

  private inBounds(x: number, y: number): boolean {
    const dx = x - this._radius;
    const dy = y - this._radius;
    const maxR = this._radius - this._currentRadius - 6;
    return dx * dx + dy * dy <= maxR * maxR;
  }

  destroy() {
    this.clearThumbs();
    this.container.destroy({ children: true });
  }
}
