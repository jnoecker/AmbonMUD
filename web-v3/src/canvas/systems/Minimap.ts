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
const QUEST_COLOR = 0xbea873;
const QUEST_GLOW_ALPHA = 0.6;

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
  private currentZone: string | null = null;
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

  // Up/down floor buttons (drawn outside the circle)
  private upButton = new Graphics();
  private downButton = new Graphics();

  constructor() {
    this.rebuildExpandButton();
    this.buildFloorButtons();

    // Inner content group that gets masked
    this.inner.addChild(this.mapGraphics);
    this.inner.addChild(this.nodeContainer);

    this.container.addChild(this.bg);
    this.container.addChild(this.clipMask);
    this.container.addChild(this.inner);
    this.container.addChild(this.expandButton);
    this.container.addChild(this.upButton);
    this.container.addChild(this.downButton);

    this.applyDiameter();

    // Register for zone map data from the React layer
    canvasCallbacks.loadZoneMap = (zone, rooms) => this.loadZoneMap(zone, rooms);
  }

  get diameter(): number {
    return this._diameter;
  }

  /** Total height including the expand button below the circle. */
  get totalHeight(): number {
    return this._diameter + 26;
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

    // Expand button centered below the circle
    this.expandButton.x = r - 11;
    this.expandButton.y = d + 4;

    // Up/down buttons — diagonal offset outside the circle, to the left
    const btnR = 14;
    this.upButton.x = r - r * 0.85 - btnR - 4;
    this.upButton.y = r - r * 0.5 - btnR;
    this.downButton.x = r - r * 0.85 - btnR - 4;
    this.downButton.y = r + r * 0.5 - btnR;
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

  private buildFloorButtons() {
    const btnR = 14;
    for (const [btn, dir, arrowUp] of [
      [this.upButton, "up", true],
      [this.downButton, "down", false],
    ] as const) {
      btn.clear();
      btn.circle(btnR, btnR, btnR);
      btn.fill({ color: BG_COLOR, alpha: 0.9 });
      btn.circle(btnR, btnR, btnR);
      btn.stroke({ color: BORDER_COLOR, width: 1 });
      const cy = btnR;
      const cx = btnR;
      if (arrowUp) {
        btn.moveTo(cx, cy - 5);
        btn.lineTo(cx - 5, cy + 3);
        btn.moveTo(cx, cy - 5);
        btn.lineTo(cx + 5, cy + 3);
        btn.stroke({ color: CURRENT_GLOW, width: 2 });
      } else {
        btn.moveTo(cx, cy + 5);
        btn.lineTo(cx - 5, cy - 3);
        btn.moveTo(cx, cy + 5);
        btn.lineTo(cx + 5, cy - 3);
        btn.stroke({ color: CURRENT_GLOW, width: 2 });
      }
      btn.eventMode = "static";
      btn.cursor = "pointer";
      btn.visible = false;
      btn.on("pointerdown", () => { canvasCallbacks.sendCommand?.(dir); });
    }
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

    // Reset visited map when entering a new zone — each zone has its own
    // coordinate system, so cross-zone data would render at wrong positions.
    const zone = roomId.split(":")[0];
    if (this.currentZone && zone !== this.currentZone) {
      this.visited.clear();
      this.clearThumbs();
    }
    this.currentZone = zone;
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

  /** Pre-populate all rooms in a zone as fog nodes. */
  loadZoneMap(zone: string, rooms: Array<{ id: string; x: number; y: number; exits: Record<string, string> }>) {
    this.visited.clear();
    this.clearThumbs();
    this.currentZone = zone;
    this.currentRoomId = null;
    this.lastKey = "";
    for (const r of rooms) {
      this.visited.set(r.id, { x: r.x, y: r.y, exits: r.exits, title: "", image: null });
    }
    this.redraw();
  }

  reset() {
    this.visited.clear();
    this.currentRoomId = null;
    this.currentZone = null;
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
    const CELL = this._cell;
    const NODE_R = this._nodeRadius;
    const CUR_R = this._currentRadius;
    const questTargets = gameStateRef.current.questTargetRoomIds;

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

    // Build a local position map using exit directions so neighbors always
    // appear at cardinal offsets regardless of BFS-computed absolute coords.
    // This prevents collision-displaced rooms from showing at diagonal positions.
    const localPos = new Map<string, { lx: number; ly: number }>();
    localPos.set(this.currentRoomId!, { lx: 0, ly: 0 });

    // First hop: immediate neighbors at cardinal offsets
    for (const [dir, targetId] of Object.entries(current.exits)) {
      if (!HORIZONTAL_DIRS.has(dir)) continue;
      const off = MAP_OFFSETS[dir];
      if (!off) continue;
      localPos.set(targetId, { lx: off.dx, ly: off.dy });
    }
    // Second hop: neighbors of neighbors
    for (const [dir, neighborId] of Object.entries(current.exits)) {
      if (!HORIZONTAL_DIRS.has(dir)) continue;
      const neighbor = this.visited.get(neighborId);
      if (!neighbor) continue;
      const nPos = localPos.get(neighborId)!;
      for (const [ndir, nextId] of Object.entries(neighbor.exits)) {
        if (!HORIZONTAL_DIRS.has(ndir)) continue;
        if (localPos.has(nextId)) continue;
        const nOff = MAP_OFFSETS[ndir];
        if (!nOff) continue;
        localPos.set(nextId, { lx: nPos.lx + nOff.dx, ly: nPos.ly + nOff.dy });
      }
    }

    // Helper to get pixel position for a room
    const posOf = (id: string): { px: number; py: number } | null => {
      const lp = localPos.get(id);
      if (!lp) return null;
      return { px: cx + lp.lx * CELL, py: cy + lp.ly * CELL };
    };

    // Draw connecting lines
    for (const [id] of localPos) {
      const node = this.visited.get(id);
      if (!node) continue;
      const sp = posOf(id);
      if (!sp) continue;

      for (const [dir, targetId] of Object.entries(node.exits)) {
        if (!HORIZONTAL_DIRS.has(dir)) continue;
        const tp = posOf(targetId);
        if (!tp) continue;

        if (this.inBounds(sp.px, sp.py) || this.inBounds(tp.px, tp.py)) {
          this.mapGraphics.moveTo(sp.px, sp.py);
          this.mapGraphics.lineTo(tp.px, tp.py);
          this.mapGraphics.stroke({ color: LINE_COLOR, width: 2, alpha: 0.65 });
        }
      }
    }

    // Draw nodes — only rooms in the local neighborhood
    for (const [id] of localPos) {
      const node = this.visited.get(id);
      if (!node) continue;
      const p = posOf(id);
      if (!p) continue;
      const nx = p.px;
      const ny = p.py;

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
        this.mapGraphics.fill({ color: FOG_COLOR, alpha: 0.75 });
        this.mapGraphics.circle(nx, ny, radius);
        this.mapGraphics.stroke({ color: 0x5a6a90, width: 1.5, alpha: 0.6 });
        if (this.fogTexture) {
          this.ensureThumb(`__fog__${id}`, null, nx, ny, radius, 0.7, this.fogTexture);
        }
      }

      if (node.image) {
        this.ensureThumb(id, node.image, nx, ny, radius, isCurrent ? 1 : 0.8, null);
      }

      // Quest objective marker — gold ring + diamond
      if (!isCurrent && questTargets.has(id)) {
        this.mapGraphics.circle(nx, ny, radius + 4);
        this.mapGraphics.stroke({ color: QUEST_COLOR, width: 2.5, alpha: QUEST_GLOW_ALPHA });
        // Small diamond above the node
        const dy = ny - radius - 7;
        this.mapGraphics.moveTo(nx, dy - 5);
        this.mapGraphics.lineTo(nx + 4, dy);
        this.mapGraphics.lineTo(nx, dy + 5);
        this.mapGraphics.lineTo(nx - 4, dy);
        this.mapGraphics.closePath();
        this.mapGraphics.fill({ color: QUEST_COLOR, alpha: 0.85 });
      }
    }

    // Off-screen quest target edge indicators — subtle gold glow at the rim
    // pointing toward quest rooms that are beyond the 2-hop local neighborhood.
    if (questTargets.size > 0) {
      for (const targetRoomId of questTargets) {
        if (localPos.has(targetRoomId)) continue; // already visible on the map
        const targetNode = this.visited.get(targetRoomId);
        if (!targetNode) continue;
        // Compute direction from current room to the target in zone coordinates
        const ddx = targetNode.x - current.x;
        const ddy = targetNode.y - current.y;
        const dist = Math.sqrt(ddx * ddx + ddy * ddy);
        if (dist === 0) continue;
        // Normalize and place on the circle rim (inset slightly so the glow is visible)
        const rimR = R - 8;
        const ex = cx + (ddx / dist) * rimR;
        const ey = cy + (ddy / dist) * rimR;
        // Small gold chevron pointing outward
        const angle = Math.atan2(ddy, ddx);
        const chevLen = 6;
        const chevSpread = 0.5;
        const tipX = ex + Math.cos(angle) * 3;
        const tipY = ey + Math.sin(angle) * 3;
        this.mapGraphics.moveTo(tipX, tipY);
        this.mapGraphics.lineTo(tipX - Math.cos(angle - chevSpread) * chevLen, tipY - Math.sin(angle - chevSpread) * chevLen);
        this.mapGraphics.moveTo(tipX, tipY);
        this.mapGraphics.lineTo(tipX - Math.cos(angle + chevSpread) * chevLen, tipY - Math.sin(angle + chevSpread) * chevLen);
        this.mapGraphics.stroke({ color: QUEST_COLOR, width: 2, alpha: 0.7 });
        // Small glow dot
        this.mapGraphics.circle(ex, ey, 3);
        this.mapGraphics.fill({ color: QUEST_COLOR, alpha: 0.5 });
      }
    }

    // Show/hide floor buttons based on current room exits
    this.upButton.visible = current ? "up" in current.exits : false;
    this.downButton.visible = current ? "down" in current.exits : false;

    // Click areas for cardinal navigation — separate pass so they're on top.
    if (current) {
      for (const [dir, targetId] of Object.entries(current.exits)) {
        // Up/down handled by the floor buttons outside the circle
        if (!HORIZONTAL_DIRS.has(dir)) continue;

        const tp = posOf(targetId);
        if (!tp) continue;
        const tnx = tp.px;
        const tny = tp.py;
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
