import { Container, Graphics, Sprite, Text, Texture, Assets } from "pixi.js";
import { gameStateRef, canvasCallbacks } from "../GameStateBridge";
import { StatusEffectDisplay } from "../systems/StatusEffectDisplay";
import { Minimap } from "../systems/Minimap";
import { EntityPopout } from "../systems/EntityPopout";
import type { MobInfo } from "../../types";

const SHOP_BADGE_SIZE = 72;

const EXIT_ARROW_COLOR = 0xb9aed8;
const EXIT_LABEL_COLOR = "#b9aed8";
const PLAYER_LABEL_COLOR = "#d8dcef";
const OTHER_PLAYER_LABEL_COLOR = "#81a2be";
const MOB_LABEL_COLOR = "#f0c674";
const ITEM_LABEL_COLOR = "#8abeb7";
const PLAYER_LABEL_FONT_SIZE = 15;
const MOB_LABEL_FONT_SIZE = 14;
const ITEM_LABEL_FONT_SIZE = 13;
const ARROW_SIZE = 22;
const SPRITE_SIZE = 128;
const ITEM_SPRITE_SIZE = 64;
const ROLE_ICON_SIZE = 12;
const ROLE_ICON_GAP = 4;
const TRANSITION_DURATION_MS = 300;

interface DirectionLayout {
  arrow: (g: Graphics, cx: number, cy: number) => void;
  labelAnchorX: number;
  labelAnchorY: number;
  offsetX: (w: number) => number;
  offsetY: (h: number) => number;
}

const DIRECTION_LAYOUTS: Record<string, DirectionLayout> = {
  north: {
    arrow: (g, cx, cy) => drawArrow(g, cx, cy - 4, "up"),
    labelAnchorX: 0.5, labelAnchorY: 1,
    offsetX: (w) => w / 2, offsetY: () => 38,
  },
  south: {
    arrow: (g, cx, cy) => drawArrow(g, cx, cy + 4, "down"),
    labelAnchorX: 0.5, labelAnchorY: 0,
    offsetX: (w) => w / 2, offsetY: (h) => h - 38,
  },
  east: {
    arrow: (g, cx, cy) => drawArrow(g, cx + 4, cy, "right"),
    labelAnchorX: 0, labelAnchorY: 0.5,
    offsetX: (w) => w - 50, offsetY: (h) => h / 2,
  },
  west: {
    arrow: (g, cx, cy) => drawArrow(g, cx - 4, cy, "left"),
    labelAnchorX: 1, labelAnchorY: 0.5,
    offsetX: () => 50, offsetY: (h) => h / 2,
  },
  up: {
    arrow: (g, cx, cy) => drawArrow(g, cx, cy - 4, "up"),
    labelAnchorX: 1, labelAnchorY: 1,
    offsetX: (w) => w - 60, offsetY: () => 50,
  },
  down: {
    arrow: (g, cx, cy) => drawArrow(g, cx, cy + 4, "down"),
    labelAnchorX: 1, labelAnchorY: 0,
    offsetX: (w) => w - 60, offsetY: (h) => h - 50,
  },
};

function drawArrow(g: Graphics, cx: number, cy: number, dir: "up" | "down" | "left" | "right") {
  const s = ARROW_SIZE;
  g.fill(EXIT_ARROW_COLOR);
  switch (dir) {
    case "up":
      g.poly([cx, cy - s / 2, cx - s / 2, cy + s / 2, cx + s / 2, cy + s / 2]);
      break;
    case "down":
      g.poly([cx, cy + s / 2, cx - s / 2, cy - s / 2, cx + s / 2, cy - s / 2]);
      break;
    case "left":
      g.poly([cx - s / 2, cy, cx + s / 2, cy - s / 2, cx + s / 2, cy + s / 2]);
      break;
    case "right":
      g.poly([cx + s / 2, cy, cx - s / 2, cy - s / 2, cx - s / 2, cy + s / 2]);
      break;
  }
  g.fill();
}

// Role indicator colors
const ROLE_QUEST_COLOR = 0xf0c674;
const ROLE_SHOP_COLOR = 0x81a2be;
const ROLE_DIALOGUE_COLOR = 0xb9aed8;

function drawRoleIcons(g: Graphics, cx: number, cy: number, info: MobInfo) {
  const icons: number[] = [];
  if (info.questGiver) icons.push(ROLE_QUEST_COLOR);
  if (info.shopKeeper) icons.push(ROLE_SHOP_COLOR);
  if (info.dialogue) icons.push(ROLE_DIALOGUE_COLOR);
  if (icons.length === 0) return;

  const totalWidth = icons.length * ROLE_ICON_SIZE + (icons.length - 1) * ROLE_ICON_GAP;
  let x = cx - totalWidth / 2 + ROLE_ICON_SIZE / 2;
  const y = cy - SPRITE_SIZE / 2 - 14;

  for (const color of icons) {
    g.fill(color);
    g.circle(x, y, ROLE_ICON_SIZE / 2);
    g.fill();
    x += ROLE_ICON_SIZE + ROLE_ICON_GAP;
  }
}

function drawQuestMarker(g: Graphics, cx: number, cy: number) {
  const y = cy - SPRITE_SIZE / 2 - 24;
  g.fill(ROLE_QUEST_COLOR);
  g.roundRect(cx - 2.5, y - 10, 5, 12, 1);
  g.fill();
  g.circle(cx, y + 7, 2.5);
  g.fill();
}

export class WorldScene {
  readonly container = new Container();

  private background: Sprite | null = null;
  private titleText: Text;
  private descText: Text;
  private exitGraphics = new Graphics();
  private exitLabels: Text[] = [];
  private playerSprite: Sprite | null = null;
  private playerLabel: Text;
  private mobSprites: Map<string, { sprite: Sprite; label: Text; hitArea: Graphics }> = new Map();
  private itemSprites: Array<{ sprite: Sprite; label: Text; hitArea: Graphics }> = [];
  private playerSprites: Map<string, { sprite: Sprite; label: Text; hitArea: Graphics }> = new Map();
  private roleGraphics = new Graphics();
  private statusEffects = new StatusEffectDisplay();
  private minimap = new Minimap();
  private entityPopout = new EntityPopout();

  private shopBadge: Container;
  private shopSprite: Sprite | null = null;
  private shopLabel: Text;
  private shopHitArea = new Graphics();
  private shopVisible = false;

  private lastRoomId: string | null = null;
  private lastRoomImage: string | null | undefined = undefined;
  private lastPlayerSpritePath: string | null = null;
  private lastExitsKey = "";
  private lastMobsKey = "";
  private lastItemsKey = "";
  private lastPlayersKey = "";
  private lastMobInfoKey = "";
  private width = 0;
  private height = 0;

  // Room transition state
  private transitioning = false;
  private transitionPhase: "fadeOut" | "fadeIn" = "fadeOut";
  private transitionProgress = 0;
  private transitionElapsed = 0;

  // Exit hit areas for click-to-move
  private exitHitAreas: Array<{ dir: string; area: Graphics }> = [];

  // Click-away to dismiss popout
  private backdropHit = new Graphics();

  constructor() {
    this.titleText = new Text({
      text: "",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 18, fill: "#d8dcef", fontWeight: "bold", dropShadow: { color: 0x000000, alpha: 0.8, blur: 4, distance: 2 } },
    });
    this.titleText.anchor.set(0, 0);

    this.descText = new Text({
      text: "",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 12, fill: "#b0b4c8", wordWrap: true, wordWrapWidth: 400, dropShadow: { color: 0x000000, alpha: 0.7, blur: 3, distance: 1 } },
    });
    this.descText.anchor.set(0, 0);
    this.descText.alpha = 0.85;

    this.playerLabel = new Text({
      text: "",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: PLAYER_LABEL_FONT_SIZE, fill: PLAYER_LABEL_COLOR, dropShadow: { color: 0x000000, alpha: 0.7, blur: 3, distance: 1 } },
    });
    this.playerLabel.anchor.set(0.5, 0);

    // Backdrop for dismissing popout
    this.backdropHit.eventMode = "static";
    this.backdropHit.visible = false;
    this.backdropHit.on("pointerdown", () => {
      this.entityPopout.hide();
      this.backdropHit.visible = false;
    });

    // Shop badge — floating kiosk icon when a shop is available
    this.shopBadge = new Container();
    this.shopBadge.visible = false;
    this.shopBadge.eventMode = "static";
    this.shopBadge.cursor = "pointer";
    this.shopBadge.on("pointerdown", () => {
      canvasCallbacks.openShop?.();
    });
    this.shopBadge.on("pointerover", () => {
      if (this.shopSprite) this.shopSprite.alpha = 1;
    });
    this.shopBadge.on("pointerout", () => {
      if (this.shopSprite) this.shopSprite.alpha = 0.85;
    });
    // Invisible hit area so clicks register even before sprite loads
    const hs = SHOP_BADGE_SIZE;
    this.shopHitArea.rect(-hs / 2, -hs / 2, hs, hs + 20);
    this.shopHitArea.fill({ color: 0x000000, alpha: 0.001 });
    this.shopHitArea.eventMode = "none";
    this.shopBadge.addChild(this.shopHitArea);
    this.shopLabel = new Text({
      text: "Shop",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 11, fill: "#bea873", dropShadow: { color: 0x000000, alpha: 1, blur: 4, distance: 0 } },
    });
    this.shopLabel.anchor.set(0.5, 0);
    this.shopLabel.y = hs / 2 + 2;
    this.shopLabel.eventMode = "none";
    this.shopBadge.addChild(this.shopLabel);
    this.loadShopIcon();

    this.container.addChild(this.exitGraphics);
    this.container.addChild(this.roleGraphics);
    this.container.addChild(this.statusEffects.container);
    this.container.addChild(this.titleText);
    this.container.addChild(this.descText);
    this.container.addChild(this.playerLabel);
    this.container.addChild(this.minimap.container);
    this.container.addChild(this.shopBadge);
    this.container.addChild(this.backdropHit);
    this.container.addChild(this.entityPopout.container);
  }

  resize(width: number, height: number) {
    this.width = width;
    this.height = height;
    this.entityPopout.resize(width, height);

    // Update backdrop size
    this.backdropHit.clear();
    this.backdropHit.rect(0, 0, width, height);
    this.backdropHit.fill({ color: 0x000000, alpha: 0.001 });

    this.layoutAll();
  }

  update(deltaMs: number) {
    const state = gameStateRef.current;
    const { room, character, mobs, roomItems, players, mobInfo } = state;

    // Handle room transition animation
    if (this.transitioning) {
      this.transitionElapsed += deltaMs;
      this.transitionProgress = Math.min(1, this.transitionElapsed / TRANSITION_DURATION_MS);
      if (this.transitionPhase === "fadeOut") {
        this.container.alpha = 1 - this.transitionProgress;
        if (this.transitionProgress >= 1) {
          this.transitionPhase = "fadeIn";
          this.transitionElapsed = 0;
          this.transitionProgress = 0;
        }
      } else {
        this.container.alpha = this.transitionProgress;
        if (this.transitionProgress >= 1) {
          this.transitioning = false;
          this.container.alpha = 1;
        }
      }
    }

    const roomChanged = room.id !== this.lastRoomId;
    if (roomChanged) {
      if (this.lastRoomId !== null) {
        this.transitioning = true;
        this.transitionPhase = "fadeOut";
        this.transitionProgress = 0;
        this.transitionElapsed = 0;
      }
      this.lastRoomId = room.id;
      this.titleText.text = room.title !== "-" ? room.title : "";
      this.descText.text = room.description || "";
      // Dismiss popout on room change
      this.entityPopout.hide();
      this.backdropHit.visible = false;
    }

    // Update minimap
    this.minimap.updateRoom(room.id, room.exits, room.title !== "-" ? room.title : "");

    if (room.image !== this.lastRoomImage) {
      this.lastRoomImage = room.image;
      this.loadBackground(room.image ?? null);
    }

    const exitsKey = Object.keys(room.exits).sort().join(",");
    if (exitsKey !== this.lastExitsKey) {
      this.lastExitsKey = exitsKey;
      this.rebuildExits(room.exits);
    }

    const spritePath = character.sprite;
    if (spritePath !== this.lastPlayerSpritePath) {
      this.lastPlayerSpritePath = spritePath;
      this.loadPlayerSprite(spritePath);
    }

    this.playerLabel.text = character.name !== "-" ? character.name : "";

    const mobsKey = mobs.map((m) => `${m.id}:${m.hp}`).join("|");
    if (mobsKey !== this.lastMobsKey) {
      this.lastMobsKey = mobsKey;
      this.rebuildMobs(mobs);
    }

    const itemsKey = roomItems.map((i) => i.id).join("|");
    if (itemsKey !== this.lastItemsKey) {
      this.lastItemsKey = itemsKey;
      this.rebuildItems(roomItems);
    }

    const playersKey = players.map((p) => p.name).join("|");
    if (playersKey !== this.lastPlayersKey) {
      this.lastPlayersKey = playersKey;
      this.rebuildPlayers(players);
    }

    const mobInfoKey = mobInfo.map((m) => `${m.id}:${m.questGiver}:${m.shopKeeper}:${m.dialogue}`).join("|");
    if (mobInfoKey !== this.lastMobInfoKey) {
      this.lastMobInfoKey = mobInfoKey;
    }

    // Shop badge visibility
    const hasShop = state.shop !== null;
    if (hasShop !== this.shopVisible) {
      this.shopVisible = hasShop;
      this.shopBadge.visible = hasShop;
    }

    this.layoutAll();
  }

  private layoutAll() {
    const w = this.width;
    const h = this.height;
    if (w === 0 || h === 0) return;

    if (this.background) {
      this.background.width = w;
      this.background.height = h;
    }

    // Minimap in top-left
    this.minimap.layout(12, 12);

    // Room title and description to the right of minimap
    const textLeft = 184;
    const textMaxWidth = Math.max(200, w - textLeft - 20);
    this.titleText.x = textLeft;
    this.titleText.y = 16;
    this.descText.x = textLeft;
    this.descText.y = 40;
    this.descText.style.wordWrapWidth = textMaxWidth;

    // Player in lower-left
    const playerX = w * 0.18;
    const playerY = h * 0.70;
    if (this.playerSprite) {
      this.playerSprite.x = playerX;
      this.playerSprite.y = playerY;
    }
    this.playerLabel.x = playerX;
    this.playerLabel.y = playerY + SPRITE_SIZE / 2 + 6;

    // Status effects above the player sprite
    this.statusEffects.update(gameStateRef.current.effects, playerX, playerY - SPRITE_SIZE / 2 - 32);

    // Layout mobs spread across the right portion, evenly spaced
    const mobEntries = [...this.mobSprites.values()];
    if (mobEntries.length > 0) {
      const mobY = h * 0.68;
      const mobAreaLeft = w * 0.38;
      const mobAreaRight = w - 24;
      const mobAreaWidth = mobAreaRight - mobAreaLeft;
      const mobSpacing = mobEntries.length === 1
        ? 0
        : Math.min(SPRITE_SIZE + 24, mobAreaWidth / mobEntries.length);
      const totalMobWidth = (mobEntries.length - 1) * mobSpacing;
      let mobX = mobAreaLeft + (mobAreaWidth - totalMobWidth) / 2;
      for (const { sprite, label, hitArea } of mobEntries) {
        sprite.x = mobX;
        sprite.y = mobY;
        label.x = mobX;
        label.y = mobY + SPRITE_SIZE / 2 + 6;
        hitArea.x = mobX - SPRITE_SIZE / 2;
        hitArea.y = mobY - SPRITE_SIZE / 2;
        mobX += mobSpacing;
      }
    }

    // Layout other players near the player sprite
    const otherPlayerEntries = [...this.playerSprites.values()];
    if (otherPlayerEntries.length > 0) {
      const opY = h * 0.55;
      let startX = playerX + SPRITE_SIZE / 2 + 20;
      for (const { sprite, label, hitArea } of otherPlayerEntries) {
        sprite.x = startX;
        sprite.y = opY;
        label.x = startX;
        label.y = opY + (SPRITE_SIZE * 0.75) / 2 + 6;
        hitArea.x = startX - (SPRITE_SIZE * 0.75) / 2;
        hitArea.y = opY - (SPRITE_SIZE * 0.75) / 2;
        startX += SPRITE_SIZE * 0.75 + 20;
      }
    }

    // Layout item sprites in a horizontal row, centered
    if (this.itemSprites.length > 0) {
      const itemY = h * 0.42;
      const itemSpacing = Math.min(ITEM_SPRITE_SIZE + 16, (w * 0.6) / Math.max(1, this.itemSprites.length));
      const totalItemWidth = (this.itemSprites.length - 1) * itemSpacing;
      let itemX = w / 2 - totalItemWidth / 2;
      for (const { sprite, label, hitArea } of this.itemSprites) {
        sprite.x = itemX;
        sprite.y = itemY;
        label.x = itemX;
        label.y = itemY + ITEM_SPRITE_SIZE / 2 + 2;
        hitArea.x = itemX - ITEM_SPRITE_SIZE / 2;
        hitArea.y = itemY - ITEM_SPRITE_SIZE / 2;
        itemX += itemSpacing;
      }
    }

    // Shop badge position — below minimap on the left
    if (this.shopBadge.visible) {
      this.shopBadge.x = 92;
      this.shopBadge.y = 220;
    }

    // Exits
    this.exitGraphics.clear();
    // Role indicators
    this.roleGraphics.clear();

    const state = gameStateRef.current;
    const exits = state.room.exits;
    let labelIdx = 0;
    for (const dir of Object.keys(exits)) {
      const layout = DIRECTION_LAYOUTS[dir.toLowerCase()];
      if (!layout && labelIdx < this.exitLabels.length) {
        const label = this.exitLabels[labelIdx];
        label.x = w / 2;
        label.y = h - 20 - (Object.keys(exits).length - labelIdx) * 22;
        labelIdx++;
        continue;
      }
      if (!layout) continue;
      const cx = layout.offsetX(w);
      const cy = layout.offsetY(h);
      layout.arrow(this.exitGraphics, cx, cy);
      if (labelIdx < this.exitLabels.length) {
        const label = this.exitLabels[labelIdx];
        label.x = cx;
        label.y = cy + (dir.toLowerCase() === "north" || dir.toLowerCase() === "up" ? -26 : 26);
        labelIdx++;
      }
    }

    // Draw NPC role indicators
    const mobInfo = state.mobInfo;
    if (mobInfo.length > 0) {
      for (const info of mobInfo) {
        const entry = this.mobSprites.get(info.id);
        if (!entry) continue;
        const { sprite } = entry;
        if (info.questGiver) drawQuestMarker(this.roleGraphics, sprite.x, sprite.y);
        drawRoleIcons(this.roleGraphics, sprite.x, sprite.y, info);
      }
    }

    this.layoutExitHitAreas(exits);
  }

  private layoutExitHitAreas(exits: Record<string, string>) {
    for (const { area } of this.exitHitAreas) {
      this.container.removeChild(area);
      area.destroy();
    }
    this.exitHitAreas = [];

    const w = this.width;
    const h = this.height;

    for (const dir of Object.keys(exits)) {
      const layout = DIRECTION_LAYOUTS[dir.toLowerCase()];
      if (!layout) continue;

      const cx = layout.offsetX(w);
      const cy = layout.offsetY(h);
      const area = new Graphics();
      area.rect(cx - 35, cy - 25, 70, 50);
      area.fill({ color: 0x000000, alpha: 0.001 });
      area.eventMode = "static";
      area.cursor = "pointer";

      const direction = dir;
      area.on("pointerdown", () => {
        canvasCallbacks.sendCommand?.(direction);
      });

      this.container.addChild(area);
      this.exitHitAreas.push({ dir, area });
    }
  }

  private rebuildExits(exits: Record<string, string>) {
    for (const label of this.exitLabels) {
      this.container.removeChild(label);
      label.destroy();
    }
    this.exitLabels = [];

    for (const dir of Object.keys(exits)) {
      const label = new Text({
        text: dir,
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 13, fill: EXIT_LABEL_COLOR, dropShadow: { color: 0x000000, alpha: 0.6, blur: 3, distance: 1 } },
      });
      label.anchor.set(0.5);
      label.alpha = 0.8;
      this.exitLabels.push(label);
      this.container.addChild(label);
    }
  }

  private rebuildMobs(mobs: Array<{ id: string; name: string; hp: number; maxHp: number; image?: string | null }>) {
    for (const { sprite, label, hitArea } of this.mobSprites.values()) {
      this.container.removeChild(sprite);
      this.container.removeChild(label);
      this.container.removeChild(hitArea);
      sprite.destroy();
      label.destroy();
      hitArea.destroy();
    }
    this.mobSprites.clear();

    for (const mob of mobs) {
      const sprite = new Sprite(Texture.WHITE);
      sprite.width = SPRITE_SIZE;
      sprite.height = SPRITE_SIZE;
      sprite.anchor.set(0.5);
      sprite.tint = 0xf0c674;

      if (mob.image) {
        this.loadSpriteTexture(sprite, mob.image);
      }

      const label = new Text({
        text: mob.name,
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: MOB_LABEL_FONT_SIZE, fill: MOB_LABEL_COLOR, dropShadow: { color: 0x000000, alpha: 0.6, blur: 3, distance: 1 } },
      });
      label.anchor.set(0.5, 0);

      // Click hit area
      const hitArea = new Graphics();
      hitArea.rect(0, 0, SPRITE_SIZE, SPRITE_SIZE);
      hitArea.fill({ color: 0x000000, alpha: 0.001 });
      hitArea.eventMode = "static";
      hitArea.cursor = "pointer";

      const mobData = mob;
      hitArea.on("pointerdown", () => {
        const info = gameStateRef.current.mobInfo.find((m) => m.id === mobData.id) ?? null;
        this.entityPopout.showMob(mobData.name, mobData.image, mobData.hp, mobData.maxHp, info);
        this.showPopout();
      });

      this.container.addChild(sprite);
      this.container.addChild(label);
      this.container.addChild(hitArea);
      this.mobSprites.set(mob.id, { sprite, label, hitArea });
    }
  }

  private rebuildItems(items: Array<{ id: string; name: string; image?: string | null }>) {
    for (const { sprite, label, hitArea } of this.itemSprites) {
      this.container.removeChild(sprite);
      this.container.removeChild(label);
      this.container.removeChild(hitArea);
      sprite.destroy();
      label.destroy();
      hitArea.destroy();
    }
    this.itemSprites = [];

    for (const item of items) {
      const sprite = new Sprite(Texture.WHITE);
      sprite.width = ITEM_SPRITE_SIZE;
      sprite.height = ITEM_SPRITE_SIZE;
      sprite.anchor.set(0.5);
      sprite.tint = 0x8abeb7;

      if (item.image) {
        this.loadSpriteTexture(sprite, item.image);
      }

      const label = new Text({
        text: item.name,
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: ITEM_LABEL_FONT_SIZE, fill: ITEM_LABEL_COLOR, dropShadow: { color: 0x000000, alpha: 0.5, blur: 3, distance: 1 } },
      });
      label.anchor.set(0.5, 0);

      const hitArea = new Graphics();
      hitArea.rect(0, 0, ITEM_SPRITE_SIZE, ITEM_SPRITE_SIZE);
      hitArea.fill({ color: 0x000000, alpha: 0.001 });
      hitArea.eventMode = "static";
      hitArea.cursor = "pointer";

      const itemData = item;
      hitArea.on("pointerdown", () => {
        this.entityPopout.showItem(itemData.name, itemData.image);
        this.showPopout();
      });

      this.container.addChild(sprite);
      this.container.addChild(label);
      this.container.addChild(hitArea);
      this.itemSprites.push({ sprite, label, hitArea });
    }
  }

  private rebuildPlayers(players: Array<{ name: string; level: number }>) {
    for (const { sprite, label, hitArea } of this.playerSprites.values()) {
      this.container.removeChild(sprite);
      this.container.removeChild(label);
      this.container.removeChild(hitArea);
      sprite.destroy();
      label.destroy();
      hitArea.destroy();
    }
    this.playerSprites.clear();

    const otherSize = SPRITE_SIZE * 0.75;
    for (const player of players) {
      const sprite = new Sprite(Texture.WHITE);
      sprite.width = otherSize;
      sprite.height = otherSize;
      sprite.anchor.set(0.5);
      sprite.tint = 0x81a2be;

      const label = new Text({
        text: player.name,
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 13, fill: OTHER_PLAYER_LABEL_COLOR, dropShadow: { color: 0x000000, alpha: 0.6, blur: 3, distance: 1 } },
      });
      label.anchor.set(0.5, 0);

      const hitArea = new Graphics();
      hitArea.rect(0, 0, otherSize, otherSize);
      hitArea.fill({ color: 0x000000, alpha: 0.001 });
      hitArea.eventMode = "static";
      hitArea.cursor = "pointer";

      const playerData = player;
      hitArea.on("pointerdown", () => {
        this.entityPopout.showPlayer(playerData.name, playerData.level);
        this.showPopout();
      });

      this.container.addChild(sprite);
      this.container.addChild(label);
      this.container.addChild(hitArea);
      this.playerSprites.set(player.name, { sprite, label, hitArea });
    }
  }

  private async loadBackground(imagePath: string | null) {
    if (this.background) {
      this.container.removeChild(this.background);
      this.background.destroy();
      this.background = null;
    }

    if (!imagePath) return;

    try {
      const texture = await Assets.load(imagePath);
      const sprite = new Sprite(texture);
      sprite.width = this.width;
      sprite.height = this.height;
      sprite.alpha = 0.6;
      this.container.addChildAt(sprite, 0);
      this.background = sprite;
    } catch {
      // Image not available
    }
  }

  private async loadPlayerSprite(spritePath: string | null) {
    if (this.playerSprite) {
      this.container.removeChild(this.playerSprite);
      this.playerSprite.destroy();
      this.playerSprite = null;
    }

    if (!spritePath) {
      const sprite = new Sprite(Texture.WHITE);
      sprite.width = SPRITE_SIZE;
      sprite.height = SPRITE_SIZE;
      sprite.anchor.set(0.5);
      sprite.tint = 0x81a2be;
      this.container.addChild(sprite);
      this.playerSprite = sprite;
      return;
    }

    try {
      const texture = await Assets.load(spritePath);
      const sprite = new Sprite(texture);
      sprite.width = SPRITE_SIZE;
      sprite.height = SPRITE_SIZE;
      sprite.anchor.set(0.5);
      this.container.addChild(sprite);
      this.playerSprite = sprite;
    } catch {
      const sprite = new Sprite(Texture.WHITE);
      sprite.width = SPRITE_SIZE;
      sprite.height = SPRITE_SIZE;
      sprite.anchor.set(0.5);
      sprite.tint = 0x81a2be;
      this.container.addChild(sprite);
      this.playerSprite = sprite;
    }
  }

  private async loadSpriteTexture(sprite: Sprite, imagePath: string) {
    try {
      const texture = await Assets.load(imagePath);
      sprite.texture = texture;
      sprite.tint = 0xffffff;
    } catch {
      // Keep placeholder tint
    }
  }

  private async loadShopIcon() {
    try {
      const texture = await Assets.load("/images/global_assets/shop_kiosk.png");
      const sprite = new Sprite(texture);
      sprite.width = SHOP_BADGE_SIZE;
      sprite.height = SHOP_BADGE_SIZE;
      sprite.anchor.set(0.5);
      sprite.alpha = 0.85;
      sprite.eventMode = "none";
      this.shopSprite = sprite;
      this.shopBadge.addChild(sprite);
    } catch {
      // Fallback: no icon shown
    }
  }

  private showPopout() {
    // Re-add backdrop and popout so they render on top of dynamically added sprites
    this.container.addChild(this.backdropHit);
    this.container.addChild(this.entityPopout.container);
    this.backdropHit.visible = true;
  }

  destroy() {
    for (const { area } of this.exitHitAreas) {
      area.destroy();
    }
    this.minimap.destroy();
    this.entityPopout.destroy();
    this.container.destroy({ children: true });
  }
}
