import { Assets, Container, Graphics, Sprite, Texture } from "pixi.js";
import { canvasCallbacks, gameStateRef } from "../GameStateBridge";
import { MAP_OFFSETS } from "../../constants";

/** Directions that represent the same horizontal plane. Up/down exits are shown
 *  as buttons beside the map but don't place nodes on the parchment. */
const HORIZONTAL_DIRS = new Set(["north", "south", "east", "west"]);

interface MapNode {
  x: number;
  y: number;
  exits: Record<string, string>;
  title: string;
  image: string | null;
  housing?: boolean;
}

// Server-asset keys (all optional — each falls back to the procedural look).
// Background follows the existing `*_bg` convention (compass_bg, room_panel_bg).
const A_BG = "minimap_bg"; // the torn parchment "scrap"
const A_ROOM = "minimap_room"; // explored room stamp
const A_ROOM_CURRENT = "minimap_room_current"; // "you are here"
const A_ROOM_FOG = "minimap_unexplored"; // unexplored room (pre-existing key)
const A_ROOM_HOUSING = "minimap_room_housing"; // player housing
const A_QUEST = "minimap_quest"; // quest objective marker (overlay)
const ASSET_KEYS = [A_BG, A_ROOM, A_ROOM_CURRENT, A_ROOM_FOG, A_ROOM_HOUSING, A_QUEST];

const DEFAULT_WIDTH = 140;
const HEIGHT_RATIO = 0.8; // parchment is a landscape rectangle

// Parchment / ink palette — the procedural fallback look.
const PAPER = 0xe6d8b8;
const PAPER_SHADE = 0xd8c49a;
const PAPER_STAIN = 0xcdb487;
const SHADOW = 0x000000;
const INK = 0x4a3a28; // dark sepia pen ink
const INK_SOFT = 0x6e5a40; // lighter ink for secondary strokes / connectors
const LINE_COLOR = 0x6e5a40;
const NODE_FILL = 0xcdb487; // explored room wash
const CURRENT_FILL = 0x9c3b22; // sienna "you are here"
const CURRENT_MARK = 0xf3e6c8; // cream center dot
const FOG_INK = 0x8a7a5e; // faint "unknown" outline
const QUEST_COLOR = 0xc69a2e; // amber gold
const HOUSING_FILL = 0xb98a5a;
const QUEST_PULSE_PERIOD = 2500; // ms
const PATH_COLOR = 0xc69a2e;
const PATH_SHIMMER_PERIOD = 1800; // ms

export class Minimap {
  readonly container = new Container();

  private bg = new Graphics();
  private border = new Graphics();
  private clipMask = new Graphics();
  private mapGraphics = new Graphics();
  private expandButton = new Graphics();
  private nodeLayer = new Container(); // textured room glyphs (when assets present)
  private markerLayer = new Container(); // textured quest markers
  private inner = new Container();
  private visited = new Map<string, MapNode>();
  private currentRoomId: string | null = null;
  private currentZone: string | null = null;
  private lastKey = "";

  // Current sizing — updated via setDiameter()
  private _width = DEFAULT_WIDTH;
  private _height = Math.round(DEFAULT_WIDTH * HEIGHT_RATIO);
  private _cell = 28;
  private _nodeHalf = 7;
  private _currentHalf = 9;

  // Cached torn-edge polygons (flat x,y pairs), rebuilt on resize.
  private tornPath: number[] = [];
  private tornInner: number[] = [];
  private tornShadow: number[] = [];

  // Server-asset textures + the sprites that use them.
  private texCache = new Map<string, Texture>();
  private assetsLoaded = false;
  private paperSprite: Sprite | null = null;
  private roomSprites = new Map<string, Sprite>();
  private questSprites = new Map<string, Sprite>();

  // Click navigation
  private clickAreas: Array<{ roomId: string; area: Graphics }> = [];
  private pulseAccum = 0;

  // Up/down floor buttons (drawn beside the parchment)
  private upButton = new Graphics();
  private downButton = new Graphics();

  constructor() {
    this.buildFloorButtons();

    // Inner content group, clipped to the torn parchment edge: the paper texture
    // sits at the back, then connector lines / procedural glyphs, then textured
    // room glyphs, then quest markers.
    this.inner.addChild(this.mapGraphics);
    this.inner.addChild(this.nodeLayer);
    this.inner.addChild(this.markerLayer);

    this.container.addChild(this.bg);
    this.container.addChild(this.inner);
    this.container.addChild(this.border);
    this.container.addChild(this.clipMask);
    this.container.addChild(this.expandButton);
    this.container.addChild(this.upButton);
    this.container.addChild(this.downButton);

    this.applyDiameter();

    // Register for zone map data from the React layer
    canvasCallbacks.loadZoneMap = (zone, rooms) => this.loadZoneMap(zone, rooms);
  }

  /** Width of the parchment — kept as `diameter` for layout-call compatibility. */
  get diameter(): number {
    return this._width;
  }

  /** Total height including the expand button below the parchment. */
  get totalHeight(): number {
    return this._height + 26;
  }

  /** Resize the map. `d` is the parchment width; height is derived. */
  setDiameter(d: number) {
    if (d === this._width) return;
    this._width = d;
    this._height = Math.round(d * HEIGHT_RATIO);
    const compact = d <= 160;
    // Cell spacing stays comfortably larger than the node glyph so the inked
    // connector lines between rooms remain visible (the signal for an exit).
    this._cell = Math.round(d * (compact ? 0.2 : 0.16));
    this._nodeHalf = Math.round(d * (compact ? 0.05 : 0.045));
    this._currentHalf = Math.round(d * (compact ? 0.06 : 0.055));
    this.applyDiameter();
    this.lastKey = ""; // force redraw
  }

  /** How many exit hops out from the current room to place on the map. */
  private readonly MAX_HOPS = 4;

  /** Deterministic [-amp, amp] jitter from an integer seed (stable per redraw). */
  private jitter(seed: number, amp: number): number {
    const s = Math.sin(seed * 12.9898) * 43758.5453;
    return (s - Math.floor(s) - 0.5) * 2 * amp;
  }

  /** Stable integer seed derived from a room id, so glyph wobble never animates. */
  private hashSeed(id: string): number {
    let h = 0;
    for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) | 0;
    return Math.abs(h) % 100000;
  }

  /** Build a jittered "torn paper" perimeter polygon as flat x,y pairs. */
  private buildTornPath(inset: number, amp: number): number[] {
    const pts: number[] = [];
    const step = 11;
    let seed = inset * 7 + 1; // vary the tear per inset so edges don't overlap
    const w = this._width;
    const h = this._height;
    const a = inset;
    const r = w - inset;
    const t = inset;
    const b = h - inset;
    const edge = (x0: number, y0: number, x1: number, y1: number) => {
      const dx = x1 - x0;
      const dy = y1 - y0;
      const len = Math.hypot(dx, dy) || 1;
      const n = Math.max(1, Math.round(len / step));
      const nx = -dy / len; // perpendicular for the wobble
      const ny = dx / len;
      for (let i = 0; i < n; i++) {
        const f = i / n;
        const j = this.jitter(seed++, amp);
        pts.push(x0 + dx * f + nx * j, y0 + dy * f + ny * j);
      }
    };
    edge(a, t, r, t);
    edge(r, t, r, b);
    edge(r, b, a, b);
    edge(a, b, a, t);
    return pts;
  }

  private applyDiameter() {
    this.tornShadow = this.buildTornPath(0, 4);
    this.tornPath = this.buildTornPath(3, 4);
    this.tornInner = this.buildTornPath(7, 3);

    // Clip inner content to the torn parchment edge.
    this.clipMask.clear();
    this.clipMask.poly(this.tornPath);
    this.clipMask.fill(0xffffff);
    this.inner.mask = this.clipMask;

    if (this.paperSprite) {
      this.paperSprite.width = this._width;
      this.paperSprite.height = this._height;
    }

    this.drawParchment();
    this.rebuildExpandButton();

    // Expand button centered below the parchment.
    this.expandButton.x = this._width / 2 - 11;
    this.expandButton.y = this._height + 4;

    // Up/down buttons stacked just to the left of the parchment, vertically centered.
    const btnR = 14;
    const bx = -btnR * 2 - 8;
    this.upButton.x = bx;
    this.upButton.y = this._height / 2 - btnR * 2 - 3;
    this.downButton.x = bx;
    this.downButton.y = this._height / 2 + 3;
  }

  /** Static parchment backdrop — drawn once per size, not per frame. */
  private drawParchment() {
    const g = this.bg;
    g.clear();

    // Drop shadow under the torn sheet.
    g.poly(this.tornShadow);
    g.fill({ color: SHADOW, alpha: 0.28 });

    // Procedural paper body — skipped when a `minimap_bg` texture covers it. The
    // texture sprite is itself clipped to the torn polygon, so the parchment
    // takes the same scrap shape whether art is supplied or not.
    if (!this.paperSprite) {
      g.poly(this.tornPath);
      g.fill({ color: PAPER, alpha: 0.97 });

      const stains = 4;
      for (let i = 0; i < stains; i++) {
        const sx = this._width * (0.2 + 0.6 * (this.jitter(i * 3 + 1, 0.5) + 0.5));
        const sy = this._height * (0.2 + 0.6 * (this.jitter(i * 3 + 2, 0.5) + 0.5));
        const rad = this._width * 0.08 * (1 + this.jitter(i * 3 + 3, 0.4));
        g.ellipse(sx, sy, rad, rad * 0.7);
        g.fill({ color: i % 2 ? PAPER_STAIN : PAPER_SHADE, alpha: 0.18 });
      }
    }

    // Torn ink edge — drawn on its own layer above the paper (soft inner line
    // under a darker outer line, a pen-nib feel) so it frames any texture.
    const e = this.border;
    e.clear();
    e.poly(this.tornInner);
    e.stroke({ color: INK_SOFT, width: 1, alpha: 0.3 });
    e.poly(this.tornPath);
    e.stroke({ color: INK, width: 1.5, alpha: 0.5 });
  }

  private rebuildExpandButton() {
    const btn = this.expandButton;
    btn.clear();
    btn.roundRect(0, 0, 22, 22, 4);
    btn.fill({ color: PAPER_SHADE, alpha: 0.97 });
    btn.roundRect(0, 0, 22, 22, 4);
    btn.stroke({ color: INK, width: 1, alpha: 0.6 });
    btn.moveTo(5, 8); btn.lineTo(5, 5); btn.lineTo(8, 5);
    btn.stroke({ color: INK, width: 1.5 });
    btn.moveTo(14, 5); btn.lineTo(17, 5); btn.lineTo(17, 8);
    btn.stroke({ color: INK, width: 1.5 });
    btn.moveTo(17, 14); btn.lineTo(17, 17); btn.lineTo(14, 17);
    btn.stroke({ color: INK, width: 1.5 });
    btn.moveTo(8, 17); btn.lineTo(5, 17); btn.lineTo(5, 14);
    btn.stroke({ color: INK, width: 1.5 });
    btn.eventMode = "static";
    btn.cursor = "pointer";
    btn.removeAllListeners();
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
      btn.fill({ color: PAPER_SHADE, alpha: 0.95 });
      btn.circle(btnR, btnR, btnR);
      btn.stroke({ color: INK, width: 1, alpha: 0.6 });
      const cy = btnR;
      const cx = btnR;
      if (arrowUp) {
        btn.moveTo(cx, cy - 5);
        btn.lineTo(cx - 5, cy + 3);
        btn.moveTo(cx, cy - 5);
        btn.lineTo(cx + 5, cy + 3);
      } else {
        btn.moveTo(cx, cy + 5);
        btn.lineTo(cx - 5, cy - 3);
        btn.moveTo(cx, cy + 5);
        btn.lineTo(cx + 5, cy - 3);
      }
      btn.stroke({ color: INK, width: 2 });
      btn.eventMode = "static";
      btn.cursor = "pointer";
      btn.visible = false;
      btn.on("pointerdown", () => { canvasCallbacks.sendCommand?.(dir); });
    }
  }

  updateRoom(roomId: string | null, exits: Record<string, string>, title: string, image: string | null, mapX: number, mapY: number) {
    if (!roomId) return;

    // Load the optional minimap textures once Server.Assets GMCP arrives.
    if (!this.assetsLoaded && Object.keys(gameStateRef.current.serverAssets).length > 0) {
      this.assetsLoaded = true;
      this.loadAssets();
    }

    const key = `${roomId}:${mapX},${mapY}:${JSON.stringify(exits)}`;
    if (key === this.lastKey) return;
    this.lastKey = key;

    // Reset visited map when entering a new zone — each zone has its own
    // coordinate system, so cross-zone data would render at wrong positions.
    const zone = roomId.split(":")[0];
    if (this.currentZone && zone !== this.currentZone) {
      this.visited.clear();
      this.clearNodeSprites();
    }
    this.currentZone = zone;
    this.currentRoomId = roomId;

    if (!this.visited.has(roomId)) {
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
    this.clearNodeSprites();
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
    this.clearNodeSprites();
    this.redraw();
  }

  layout(x: number, y: number) {
    this.container.x = x;
    this.container.y = y;
  }

  private redraw() {
    const CELL = this._cell;
    const questTargets = gameStateRef.current.questTargetRoomIds;

    // Clear click areas
    for (const { area } of this.clickAreas) {
      this.container.removeChild(area);
      area.destroy();
    }
    this.clickAreas = [];

    this.mapGraphics.clear();
    // Hide all textured glyphs; the ones in view are re-shown below.
    for (const s of this.roomSprites.values()) s.visible = false;
    for (const s of this.questSprites.values()) s.visible = false;

    if (!this.currentRoomId) return;
    const current = this.visited.get(this.currentRoomId);
    if (!current) return;

    const cx = this._width / 2;
    const cy = this._height / 2;

    // Local position map keyed on exit directions so neighbors always appear at
    // cardinal offsets regardless of BFS-computed absolute coords.
    const localPos = new Map<string, { lx: number; ly: number }>();
    localPos.set(this.currentRoomId, { lx: 0, ly: 0 });

    let frontier: string[] = [this.currentRoomId];
    for (let hop = 0; hop < this.MAX_HOPS && frontier.length > 0; hop++) {
      const next: string[] = [];
      for (const id of frontier) {
        const node = this.visited.get(id);
        if (!node) continue;
        const base = localPos.get(id)!;
        for (const [dir, targetId] of Object.entries(node.exits)) {
          if (!HORIZONTAL_DIRS.has(dir)) continue;
          if (localPos.has(targetId)) continue;
          const off = MAP_OFFSETS[dir];
          if (!off) continue;
          localPos.set(targetId, { lx: base.lx + off.dx, ly: base.ly + off.dy });
          next.push(targetId);
        }
      }
      frontier = next;
    }

    const posOf = (id: string): { px: number; py: number } | null => {
      const lp = localPos.get(id);
      if (!lp) return null;
      return { px: cx + lp.lx * CELL, py: cy + lp.ly * CELL };
    };

    const pathEdges = this.computeQuestPath(questTargets);

    // Inked connector lines between rooms.
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
          this.mapGraphics.stroke({ color: LINE_COLOR, width: 2, alpha: 0.7 });
        }
      }
    }

    // Quest path trail — amber glow over the shortest-path edges.
    if (pathEdges.length > 0) {
      const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      const shimmer = reducedMotion
        ? 0.7
        : 0.45 + 0.3 * (0.5 + 0.5 * Math.sin(Date.now() / PATH_SHIMMER_PERIOD * Math.PI * 2));
      for (const [fromId, toId] of pathEdges) {
        const sp = posOf(fromId);
        const tp = posOf(toId);
        if (!sp || !tp) continue;
        if (!this.inBounds(sp.px, sp.py) && !this.inBounds(tp.px, tp.py)) continue;
        this.mapGraphics.moveTo(sp.px, sp.py);
        this.mapGraphics.lineTo(tp.px, tp.py);
        this.mapGraphics.stroke({ color: PATH_COLOR, width: 5, alpha: shimmer * 0.3 });
        this.mapGraphics.moveTo(sp.px, sp.py);
        this.mapGraphics.lineTo(tp.px, tp.py);
        this.mapGraphics.stroke({ color: PATH_COLOR, width: 2, alpha: shimmer });
      }
    }

    // Room glyphs — textured stamp when an asset exists, else inked vectors.
    for (const [id] of localPos) {
      const node = this.visited.get(id);
      if (!node) continue;
      const p = posOf(id);
      if (!p) continue;
      const nx = p.px;
      const ny = p.py;
      if (!this.inBounds(nx, ny)) continue;

      const isCurrent = id === this.currentRoomId;
      const half = isCurrent ? this._currentHalf : this._nodeHalf;
      const visited = node.title !== "";
      const isHousing = node.housing === true;
      const seed = this.hashSeed(id);

      // Direction ticks for exits that leave the visible map.
      for (const dir of Object.keys(node.exits)) {
        if (!HORIZONTAL_DIRS.has(dir)) continue;
        const tp = posOf(node.exits[dir]);
        if (tp && this.inBounds(tp.px, tp.py)) continue; // drawn as a connector
        const off = MAP_OFFSETS[dir];
        if (!off) continue;
        this.mapGraphics.moveTo(nx + off.dx * half, ny + off.dy * half);
        this.mapGraphics.lineTo(nx + off.dx * (half + 5), ny + off.dy * (half + 5));
        this.mapGraphics.stroke({ color: INK_SOFT, width: 1.5, alpha: 0.55 });
      }

      const assetKey = isCurrent
        ? A_ROOM_CURRENT
        : !visited
            ? A_ROOM_FOG
            : isHousing
                ? A_ROOM_HOUSING
                : A_ROOM;
      const tex = this.texCache.get(assetKey);

      if (tex) {
        this.placeRoomSprite(id, tex, nx, ny, half);
      } else {
        this.drawRoomGlyph(nx, ny, half, seed, isCurrent, visited, isHousing);
      }

      // Quest objective marker.
      if (!isCurrent && questTargets.has(id)) {
        const pulse = 0.4 + 0.45 * (0.5 + 0.5 * Math.sin(Date.now() / QUEST_PULSE_PERIOD * Math.PI * 2));
        const qTex = this.texCache.get(A_QUEST);
        if (qTex) {
          this.placeQuestSprite(id, qTex, nx, ny, half, pulse);
        } else {
          this.inkRect(nx, ny, half + 3, seed + 7, { stroke: QUEST_COLOR, width: 2, alpha: pulse });
          const ds = this._width <= 180 ? 3 : 5;
          const dOff = this._width <= 180 ? 4 : 7;
          const dy = ny - half - dOff;
          this.mapGraphics.moveTo(nx, dy - ds);
          this.mapGraphics.lineTo(nx + ds - 1, dy);
          this.mapGraphics.lineTo(nx, dy + ds);
          this.mapGraphics.lineTo(nx - ds + 1, dy);
          this.mapGraphics.closePath();
          this.mapGraphics.fill({ color: QUEST_COLOR, alpha: pulse });
        }
      }
    }

    // Off-map quest indicators — amber chevrons on the parchment edge pointing
    // toward quest rooms beyond the visible neighborhood.
    const edgePulse = 0.35 + 0.45 * (0.5 + 0.5 * Math.sin(Date.now() / QUEST_PULSE_PERIOD * Math.PI * 2));
    if (questTargets.size > 0) {
      const halfW = this._width / 2 - 8;
      const halfH = this._height / 2 - 8;
      for (const targetRoomId of questTargets) {
        if (localPos.has(targetRoomId)) continue;
        const targetNode = this.visited.get(targetRoomId);
        if (!targetNode) continue;
        const ddx = targetNode.x - current.x;
        const ddy = targetNode.y - current.y;
        if (ddx === 0 && ddy === 0) continue;
        const scale = 1 / Math.max(Math.abs(ddx) / halfW, Math.abs(ddy) / halfH);
        const ex = cx + ddx * scale;
        const ey = cy + ddy * scale;
        const angle = Math.atan2(ddy, ddx);
        const chevLen = 6;
        const chevSpread = 0.5;
        const tipX = ex + Math.cos(angle) * 3;
        const tipY = ey + Math.sin(angle) * 3;
        this.mapGraphics.moveTo(tipX, tipY);
        this.mapGraphics.lineTo(tipX - Math.cos(angle - chevSpread) * chevLen, tipY - Math.sin(angle - chevSpread) * chevLen);
        this.mapGraphics.moveTo(tipX, tipY);
        this.mapGraphics.lineTo(tipX - Math.cos(angle + chevSpread) * chevLen, tipY - Math.sin(angle + chevSpread) * chevLen);
        this.mapGraphics.stroke({ color: QUEST_COLOR, width: 2, alpha: edgePulse });
        this.mapGraphics.circle(ex, ey, 3);
        this.mapGraphics.fill({ color: QUEST_COLOR, alpha: edgePulse * 0.7 });
      }
    }

    // Floor buttons reflect the current room's vertical exits.
    this.upButton.visible = "up" in current.exits;
    this.downButton.visible = "down" in current.exits;

    // Click areas for cardinal navigation — separate pass so they sit on top.
    for (const [dir, targetId] of Object.entries(current.exits)) {
      if (!HORIZONTAL_DIRS.has(dir)) continue;
      const tp = posOf(targetId);
      if (!tp) continue;
      if (!this.inBounds(tp.px, tp.py)) continue;
      const area = new Graphics();
      area.rect(tp.px - this._nodeHalf - 4, tp.py - this._nodeHalf - 4, (this._nodeHalf + 4) * 2, (this._nodeHalf + 4) * 2);
      area.fill({ color: 0x000000, alpha: 0.001 });
      area.eventMode = "static";
      area.cursor = "pointer";
      area.on("pointerdown", () => { canvasCallbacks.sendCommand?.(dir); });
      this.container.addChild(area);
      this.clickAreas.push({ roomId: targetId, area });
    }
  }

  /** Show/position a textured room glyph (reused across redraws). */
  private placeRoomSprite(id: string, tex: Texture, nx: number, ny: number, half: number) {
    let spr = this.roomSprites.get(id);
    if (!spr) {
      spr = new Sprite(tex);
      spr.anchor.set(0.5);
      spr.eventMode = "none";
      this.nodeLayer.addChild(spr);
      this.roomSprites.set(id, spr);
    } else if (spr.texture !== tex) {
      spr.texture = tex;
    }
    const size = half * 2.4; // a touch of bleed past the node box reads well
    spr.width = size;
    spr.height = size;
    spr.x = nx;
    spr.y = ny;
    spr.visible = true;
  }

  /** Show/position a textured quest marker overlay (reused across redraws). */
  private placeQuestSprite(id: string, tex: Texture, nx: number, ny: number, half: number, alpha: number) {
    let spr = this.questSprites.get(id);
    if (!spr) {
      spr = new Sprite(tex);
      spr.anchor.set(0.5);
      spr.eventMode = "none";
      this.markerLayer.addChild(spr);
      this.questSprites.set(id, spr);
    } else if (spr.texture !== tex) {
      spr.texture = tex;
    }
    const size = (half + 6) * 2;
    spr.width = size;
    spr.height = size;
    spr.x = nx;
    spr.y = ny;
    spr.alpha = alpha;
    spr.visible = true;
  }

  /** Procedural inked room glyph (fallback when no room asset is present). */
  private drawRoomGlyph(nx: number, ny: number, half: number, seed: number, isCurrent: boolean, visited: boolean, isHousing: boolean) {
    if (isCurrent) {
      this.inkRect(nx, ny, half, seed, { fill: CURRENT_FILL, fillAlpha: 0.9, stroke: INK, width: 1.6, doubleStroke: true });
      this.mapGraphics.circle(nx, ny, Math.max(2, half * 0.3));
      this.mapGraphics.fill({ color: CURRENT_MARK, alpha: 0.95 });
    } else if (visited) {
      this.inkRect(nx, ny, half, seed, {
        fill: isHousing ? HOUSING_FILL : NODE_FILL,
        fillAlpha: 0.9,
        stroke: INK,
        width: 1.2,
        alpha: 0.85,
        doubleStroke: true,
      });
      if (isHousing) {
        this.mapGraphics.moveTo(nx - half * 0.6, ny - half);
        this.mapGraphics.lineTo(nx, ny - half * 1.5);
        this.mapGraphics.lineTo(nx + half * 0.6, ny - half);
        this.mapGraphics.stroke({ color: INK, width: 1.2, alpha: 0.8 });
      }
    } else {
      this.inkRect(nx, ny, half, seed, { stroke: FOG_INK, width: 1.2, alpha: 0.55 });
      this.mapGraphics.moveTo(nx - half * 0.6, ny + half * 0.6);
      this.mapGraphics.lineTo(nx + half * 0.6, ny - half * 0.6);
      this.mapGraphics.stroke({ color: FOG_INK, width: 1, alpha: 0.4 });
    }
  }

  /** Draw a hand-drawn (jittered) inked rectangle glyph for a room. */
  private inkRect(
    cx: number,
    cy: number,
    half: number,
    seed: number,
    opts: { fill?: number; fillAlpha?: number; stroke: number; width: number; alpha?: number; doubleStroke?: boolean },
  ) {
    const g = this.mapGraphics;
    const amp = half * 0.16;
    const j = (s: number) => this.jitter(seed + s, amp);
    const poly = [
      cx - half + j(0), cy - half + j(1),
      cx + half + j(2), cy - half + j(3),
      cx + half + j(4), cy + half + j(5),
      cx - half + j(6), cy + half + j(7),
    ];
    if (opts.fill !== undefined) {
      g.poly(poly);
      g.fill({ color: opts.fill, alpha: opts.fillAlpha ?? 1 });
    }
    if (opts.doubleStroke) {
      g.poly(poly.map((v) => v + 0.8));
      g.stroke({ color: opts.stroke, width: opts.width + 0.6, alpha: (opts.alpha ?? 1) * 0.4 });
    }
    g.poly(poly);
    g.stroke({ color: opts.stroke, width: opts.width, alpha: opts.alpha ?? 1 });
  }

  /**
   * BFS from the current room to the nearest quest target.
   * Returns [fromId, toId] edge pairs for the shortest path, or [] if none.
   */
  private computeQuestPath(questTargets: Set<string>): Array<[string, string]> {
    if (questTargets.size === 0 || !this.currentRoomId) return [];

    const start = this.currentRoomId;
    if (questTargets.has(start)) return [];

    const visited = new Set<string>();
    const parent = new Map<string, string>();
    const queue: string[] = [start];
    visited.add(start);

    let target: string | null = null;

    while (queue.length > 0) {
      const current = queue.shift()!;
      const node = this.visited.get(current);
      if (!node) continue;
      for (const [dir, neighborId] of Object.entries(node.exits)) {
        if (!HORIZONTAL_DIRS.has(dir)) continue;
        if (visited.has(neighborId)) continue;
        if (!this.visited.has(neighborId)) continue;
        visited.add(neighborId);
        parent.set(neighborId, current);
        if (questTargets.has(neighborId)) {
          target = neighborId;
          break;
        }
        queue.push(neighborId);
      }
      if (target) break;
    }

    if (!target) return [];

    const edges: Array<[string, string]> = [];
    let current = target;
    while (parent.has(current)) {
      const prev = parent.get(current)!;
      edges.push([prev, current]);
      current = prev;
    }
    edges.reverse();
    return edges;
  }

  /** Load all optional minimap textures from Server.Assets, then redraw. */
  private async loadAssets() {
    const sa = gameStateRef.current.serverAssets;
    await Promise.all(
      ASSET_KEYS.map(async (k) => {
        const url = sa[k];
        if (!url) return;
        try {
          this.texCache.set(k, await Assets.load(url));
        } catch { /* keep the procedural fallback for this key */ }
      }),
    );

    const bgTex = this.texCache.get(A_BG);
    if (bgTex && !this.paperSprite) {
      const s = new Sprite(bgTex);
      s.eventMode = "none";
      s.width = this._width;
      s.height = this._height;
      this.paperSprite = s;
      this.inner.addChildAt(s, 0); // behind lines/glyphs, clipped to the torn edge
    }

    this.drawParchment(); // drop the procedural fill now that the texture covers it
    this.lastKey = "";
    this.redraw();
  }

  private clearNodeSprites() {
    for (const s of this.roomSprites.values()) s.destroy();
    for (const s of this.questSprites.values()) s.destroy();
    this.roomSprites.clear();
    this.questSprites.clear();
  }

  private inBounds(x: number, y: number): boolean {
    const pad = this._width <= 160 ? 6 : 10;
    const halfW = this._width / 2 - this._currentHalf - pad;
    const halfH = this._height / 2 - this._currentHalf - pad;
    return Math.abs(x - this._width / 2) <= halfW && Math.abs(y - this._height / 2) <= halfH;
  }

  /** Called from the PixiJS ticker to animate the quest pulse (~15fps). */
  tick(deltaMs: number) {
    if (gameStateRef.current.questTargetRoomIds.size === 0 || !this.currentRoomId) return;
    this.pulseAccum += deltaMs;
    if (this.pulseAccum < 67) return;
    this.pulseAccum = 0;
    this.redraw();
  }

  destroy() {
    this.clearNodeSprites();
    this.container.destroy({ children: true });
  }
}
