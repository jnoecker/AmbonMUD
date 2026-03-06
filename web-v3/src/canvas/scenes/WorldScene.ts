import { Container, Graphics, Sprite, Text, Texture, Assets } from "pixi.js";
import { gameStateRef, canvasCallbacks } from "../GameStateBridge";
import { StatusEffectDisplay } from "../systems/StatusEffectDisplay";
import { Minimap } from "../systems/Minimap";
import { EntityPopout } from "../systems/EntityPopout";
import type { MobInfo } from "../../types";

const SHOP_BADGE_SIZE = 72;
const QUEST_ICON_SIZE = 28;

const PLAYER_LABEL_COLOR = "#d8dcef";
const OTHER_PLAYER_LABEL_COLOR = "#81a2be";
const MOB_LABEL_COLOR = "#f0c674";
const ITEM_LABEL_COLOR = "#8abeb7";
const PLAYER_LABEL_FONT_SIZE = 15;
const MOB_LABEL_FONT_SIZE = 14;
const ITEM_LABEL_FONT_SIZE = 13;
const COMPASS_SIZE = 120;
const COMPASS_MARGIN = 16;
const STAIR_ICON_SIZE = 56;
const BASE_SPRITE_SIZE = 128;
const BASE_ITEM_SPRITE_SIZE = 64;
const REF_WIDTH = 1200;
const REF_HEIGHT = 800;
const MIN_SPRITE_SIZE = 64;
const MAX_SPRITE_SIZE = 192;
const MIN_ITEM_SIZE = 32;
const MAX_ITEM_SIZE = 96;

const clamp = (v: number, min: number, max: number) => Math.max(min, Math.min(max, v));
const ROLE_ICON_SIZE = 12;
const ROLE_ICON_GAP = 4;
const TRANSITION_DURATION_MS = 300;

// Role indicator colors
const ROLE_SHOP_COLOR = 0x81a2be;
const DIALOGUE_ICON_SIZE = 28;
const AGGRO_ICON_SIZE = 24;

function drawRoleIcons(g: Graphics, cx: number, cy: number, info: MobInfo, spriteSize: number) {
  const icons: number[] = [];
  // quest indicators are handled by sprites now
  if (info.shopKeeper) icons.push(ROLE_SHOP_COLOR);
  // dialogue is handled by sprite indicator, not dot
  if (icons.length === 0) return;

  const totalWidth = icons.length * ROLE_ICON_SIZE + (icons.length - 1) * ROLE_ICON_GAP;
  let x = cx - totalWidth / 2 + ROLE_ICON_SIZE / 2;
  const y = cy - spriteSize / 2 - 14;

  for (const color of icons) {
    g.fill(color);
    g.circle(x, y, ROLE_ICON_SIZE / 2);
    g.fill();
    x += ROLE_ICON_SIZE + ROLE_ICON_GAP;
  }
}

export class WorldScene {
  readonly container = new Container();

  private background: Sprite | null = null;
  private titleText: Text;
  private descText: Text;
  private descBg = new Graphics();
  private playerSprite: Sprite | null = null;
  private playerLabel: Text;
  private mobSprites: Map<string, { sprite: Sprite; label: Text; hitArea: Graphics }> = new Map();
  private itemSprites: Array<{ sprite: Sprite; label: Text; hitArea: Graphics }> = [];
  private playerSprites: Map<string, { sprite: Sprite; label: Text; hitArea: Graphics }> = new Map();
  private roleGraphics = new Graphics();
  private statusEffects = new StatusEffectDisplay();
  private minimap = new Minimap();
  private entityPopout = new EntityPopout();

  private dialogueTexture: Texture | null = null;
  private dialogueIcons: Map<string, Sprite> = new Map();
  private aggroTexture: Texture | null = null;
  private aggroIcons: Map<string, Sprite> = new Map();
  private questAvailableTexture: Texture | null = null;
  private questAvailableIcons: Map<string, Sprite> = new Map();
  private questCompleteTexture: Texture | null = null;
  private questCompleteIcons: Map<string, Sprite> = new Map();

  private roomExpandBtn = new Graphics();
  private currentMobSize = BASE_SPRITE_SIZE;

  // Compass rose navigation
  private compassContainer = new Container();
  private compassHighlightGraphics = new Graphics();
  private compassHitAreas: Array<{ dir: string; area: Graphics }> = [];
  private stairsUpSprite: Sprite | null = null;
  private stairsDownSprite: Sprite | null = null;
  private stairsUpHit: Graphics | null = null;
  private stairsDownHit: Graphics | null = null;
  private lastExitDirs: string[] = [];

  private shopBadge: Container;
  private shopSprite: Sprite | null = null;
  private shopLabel: Text;
  private shopHitArea = new Graphics();
  private shopVisible = false;

  private lastRoomId: string | null = null;
  private lastRoomImage: string | null | undefined = undefined;
  private lastPlayerSpritePath: string | null = null;
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

  // Click-away to dismiss popout
  private backdropHit = new Graphics();

  constructor() {
    this.titleText = new Text({
      text: "",
      style: { fontFamily: "Cormorant Garamond, Georgia, serif", fontSize: 26, fill: "#d8dcef", fontWeight: "700", dropShadow: { color: 0x000000, alpha: 0.8, blur: 6, distance: 3 } },
    });
    this.titleText.anchor.set(0, 0);

    this.descText = new Text({
      text: "",
      style: { fontFamily: "Cormorant Garamond, Georgia, serif", fontSize: 18, fill: "#d0d4e8", fontWeight: "500", wordWrap: true, wordWrapWidth: 400, dropShadow: { color: 0x000000, alpha: 0.9, blur: 6, distance: 2 } },
    });
    this.descText.anchor.set(0, 0);
    this.descText.alpha = 0.95;

    // Room expand button next to title
    const rb = this.roomExpandBtn;
    rb.roundRect(0, 0, 20, 20, 4);
    rb.fill({ color: 0x141828, alpha: 0.85 });
    rb.roundRect(0, 0, 20, 20, 4);
    rb.stroke({ color: 0x3a4060, width: 1 });
    const rc = 0xb9aed8;
    rb.moveTo(4, 7); rb.lineTo(4, 4); rb.lineTo(7, 4);
    rb.stroke({ color: rc, width: 1.5 });
    rb.moveTo(13, 4); rb.lineTo(16, 4); rb.lineTo(16, 7);
    rb.stroke({ color: rc, width: 1.5 });
    rb.moveTo(16, 13); rb.lineTo(16, 16); rb.lineTo(13, 16);
    rb.stroke({ color: rc, width: 1.5 });
    rb.moveTo(7, 16); rb.lineTo(4, 16); rb.lineTo(4, 13);
    rb.stroke({ color: rc, width: 1.5 });
    rb.eventMode = "static";
    rb.cursor = "pointer";
    rb.on("pointerdown", () => {
      canvasCallbacks.openRoom?.();
    });

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
    this.shopHitArea.eventMode = "auto";
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
    this.loadDialogueTexture();
    this.loadAggroTexture();
    this.loadQuestTextures();
    this.buildCompassRose();
    this.loadCompassAssets();

    this.container.addChild(this.roleGraphics);
    this.container.addChild(this.statusEffects.container);
    this.container.addChild(this.titleText);
    this.container.addChild(this.descBg);
    this.container.addChild(this.descText);
    this.container.addChild(this.roomExpandBtn);
    this.container.addChild(this.playerLabel);
    this.container.addChild(this.minimap.container);
    this.container.addChild(this.shopBadge);
    this.container.addChild(this.compassContainer);
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

    const mobInfoKey = mobInfo.map((m) => `${m.id}:${m.questAvailable}:${m.questComplete}:${m.shopKeeper}:${m.dialogue}:${m.aggressive}`).join("|");
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
    this.titleText.y = 14;
    this.descText.x = textLeft + 10;
    this.descText.y = 48 + 8;
    this.descText.style.wordWrapWidth = textMaxWidth - 20;

    // Semi-transparent background pill behind description
    this.descBg.clear();
    if (this.descText.text) {
      const pad = 10;
      this.descBg.roundRect(
        textLeft, 48,
        Math.min(this.descText.width + pad * 2, textMaxWidth),
        this.descText.height + pad * 2,
        8,
      );
      this.descBg.fill({ color: 0x0a0e1a, alpha: 0.55 });
    }

    // Room expand button next to title
    this.roomExpandBtn.x = textLeft + this.titleText.width + 12;
    this.roomExpandBtn.y = this.titleText.y;

    // Dynamic entity sizing
    const scale = Math.min(w / REF_WIDTH, h / REF_HEIGHT);
    const playerSize = clamp(BASE_SPRITE_SIZE * scale, MIN_SPRITE_SIZE, MAX_SPRITE_SIZE);

    const mobEntries = [...this.mobSprites.values()];
    const mobCount = mobEntries.length;
    const mobAreaLeft = w * 0.38;
    const mobAreaRight = w - 24;
    const mobAreaWidth = mobAreaRight - mobAreaLeft;
    const mobBaseSize = BASE_SPRITE_SIZE * scale;
    const mobFitSize = mobCount > 0 ? (mobAreaWidth - 16) / mobCount - 16 : mobBaseSize;
    const mobSize = clamp(Math.min(mobBaseSize, mobFitSize), MIN_SPRITE_SIZE, MAX_SPRITE_SIZE);
    this.currentMobSize = mobSize;

    const itemCount = this.itemSprites.length;
    const itemAreaWidth = w * 0.6;
    const itemBaseSize = BASE_ITEM_SPRITE_SIZE * scale;
    const itemFitSize = itemCount > 0 ? (itemAreaWidth - 8) / itemCount - 8 : itemBaseSize;
    const itemSize = clamp(Math.min(itemBaseSize, itemFitSize), MIN_ITEM_SIZE, MAX_ITEM_SIZE);

    const otherSize = playerSize * 0.75;

    // Player in lower-left
    const playerX = w * 0.18;
    const playerY = h * 0.70;
    if (this.playerSprite) {
      this.playerSprite.x = playerX;
      this.playerSprite.y = playerY;
      this.playerSprite.width = playerSize;
      this.playerSprite.height = playerSize;
    }
    this.playerLabel.x = playerX;
    this.playerLabel.y = playerY + playerSize / 2 + 6;

    // Status effects above the player sprite
    this.statusEffects.update(gameStateRef.current.effects, playerX, playerY - playerSize / 2 - 32);

    // Layout mobs spread across the right portion, evenly spaced
    if (mobCount > 0) {
      const mobY = h * 0.68;
      const mobSpacing = mobCount === 1
        ? 0
        : Math.min(mobSize + 24, mobAreaWidth / mobCount);
      const totalMobWidth = (mobCount - 1) * mobSpacing;
      let mobX = mobAreaLeft + (mobAreaWidth - totalMobWidth) / 2;
      for (const { sprite, label, hitArea } of mobEntries) {
        sprite.x = mobX;
        sprite.y = mobY;
        sprite.width = mobSize;
        sprite.height = mobSize;
        label.x = mobX;
        label.y = mobY + mobSize / 2 + 6;
        hitArea.clear();
        hitArea.rect(0, 0, mobSize, mobSize);
        hitArea.fill({ color: 0x000000, alpha: 0.001 });
        hitArea.x = mobX - mobSize / 2;
        hitArea.y = mobY - mobSize / 2;
        mobX += mobSpacing;
      }
    }

    // Layout other players near the player sprite
    const otherPlayerEntries = [...this.playerSprites.values()];
    if (otherPlayerEntries.length > 0) {
      const opY = h * 0.55;
      let startX = playerX + playerSize / 2 + 20;
      for (const { sprite, label, hitArea } of otherPlayerEntries) {
        sprite.x = startX;
        sprite.y = opY;
        sprite.width = otherSize;
        sprite.height = otherSize;
        label.x = startX;
        label.y = opY + otherSize / 2 + 6;
        hitArea.clear();
        hitArea.rect(0, 0, otherSize, otherSize);
        hitArea.fill({ color: 0x000000, alpha: 0.001 });
        hitArea.x = startX - otherSize / 2;
        hitArea.y = opY - otherSize / 2;
        startX += otherSize + 20;
      }
    }

    // Layout item sprites in a horizontal row, centered
    if (itemCount > 0) {
      const itemY = h * 0.42;
      const itemSpacing = Math.min(itemSize + 16, itemAreaWidth / Math.max(1, itemCount));
      const totalItemWidth = (itemCount - 1) * itemSpacing;
      let itemX = w / 2 - totalItemWidth / 2;
      for (const { sprite, label, hitArea } of this.itemSprites) {
        sprite.x = itemX;
        sprite.y = itemY;
        sprite.width = itemSize;
        sprite.height = itemSize;
        label.x = itemX;
        label.y = itemY + itemSize / 2 + 2;
        hitArea.clear();
        hitArea.rect(0, 0, itemSize, itemSize);
        hitArea.fill({ color: 0x000000, alpha: 0.001 });
        hitArea.x = itemX - itemSize / 2;
        hitArea.y = itemY - itemSize / 2;
        itemX += itemSpacing;
      }
    }

    // Shop badge position — below minimap on the left
    if (this.shopBadge.visible) {
      this.shopBadge.x = 92;
      this.shopBadge.y = 220;
    }

    // Compass rose in bottom-right
    const state = gameStateRef.current;
    const exits = state.room.exits;
    const exitDirs = Object.keys(exits).map((d) => d.toLowerCase());
    this.compassContainer.x = w - COMPASS_SIZE / 2 - COMPASS_MARGIN;
    this.compassContainer.y = h - COMPASS_SIZE / 2 - COMPASS_MARGIN;
    this.updateCompassHighlights(exitDirs);

    // Stairs icons next to compass
    const hasUp = exitDirs.includes("up");
    const hasDown = exitDirs.includes("down");
    if (this.stairsUpSprite) {
      this.stairsUpSprite.visible = hasUp;
      this.stairsUpSprite.x = -COMPASS_SIZE / 2 - STAIR_ICON_SIZE / 2 - 8;
      this.stairsUpSprite.y = -STAIR_ICON_SIZE / 2 - 4;
    }
    if (this.stairsUpHit) {
      this.stairsUpHit.visible = hasUp;
      this.stairsUpHit.x = -COMPASS_SIZE / 2 - STAIR_ICON_SIZE - 8;
      this.stairsUpHit.y = -STAIR_ICON_SIZE / 2 - 4;
    }
    if (this.stairsDownSprite) {
      this.stairsDownSprite.visible = hasDown;
      this.stairsDownSprite.x = -COMPASS_SIZE / 2 - STAIR_ICON_SIZE / 2 - 8;
      this.stairsDownSprite.y = STAIR_ICON_SIZE / 2 + 4;
    }
    if (this.stairsDownHit) {
      this.stairsDownHit.visible = hasDown;
      this.stairsDownHit.x = -COMPASS_SIZE / 2 - STAIR_ICON_SIZE - 8;
      this.stairsDownHit.y = 4;
    }

    // Role indicators
    this.roleGraphics.clear();

    // Draw NPC role indicators
    const mobInfo = state.mobInfo;
    const activeDialogueMobs = new Set<string>();
    const activeAggroMobs = new Set<string>();
    const activeQuestAvail = new Set<string>();
    const activeQuestComplete = new Set<string>();
    if (mobInfo.length > 0) {
      for (const info of mobInfo) {
        const entry = this.mobSprites.get(info.id);
        if (!entry) continue;
        const { sprite } = entry;
        drawRoleIcons(this.roleGraphics, sprite.x, sprite.y, info, this.currentMobSize);
        if (info.dialogue) {
          activeDialogueMobs.add(info.id);
          this.ensureDialogueIcon(info.id, sprite.x, sprite.y);
        }
        if (info.aggressive) {
          activeAggroMobs.add(info.id);
          this.ensureAggroIcon(info.id, sprite.x, sprite.y);
        }
        if (info.questComplete) {
          activeQuestComplete.add(info.id);
          this.ensureQuestIcon(info.id, sprite.x, sprite.y, "complete");
        } else if (info.questAvailable) {
          activeQuestAvail.add(info.id);
          this.ensureQuestIcon(info.id, sprite.x, sprite.y, "available");
        }
      }
    }
    // Remove stale indicator icons
    this.pruneIcons(this.dialogueIcons, activeDialogueMobs);
    this.pruneIcons(this.aggroIcons, activeAggroMobs);
    this.pruneIcons(this.questAvailableIcons, activeQuestAvail);
    this.pruneIcons(this.questCompleteIcons, activeQuestComplete);
  }

  private buildCompassRose() {
    const s = COMPASS_SIZE;
    const r = s / 2;

    // Highlight layer drawn behind the sprite
    this.compassContainer.addChild(this.compassHighlightGraphics);

    // Clickable hit area wedges for each cardinal direction
    const directions: Array<{ dir: string; points: number[] }> = [
      { dir: "north", points: [0, -r, -r * 0.4, -r * 0.15, r * 0.4, -r * 0.15] },
      { dir: "south", points: [0, r, -r * 0.4, r * 0.15, r * 0.4, r * 0.15] },
      { dir: "east", points: [r, 0, r * 0.15, -r * 0.4, r * 0.15, r * 0.4] },
      { dir: "west", points: [-r, 0, -r * 0.15, -r * 0.4, -r * 0.15, r * 0.4] },
    ];

    for (const { dir, points } of directions) {
      const area = new Graphics();
      area.poly(points);
      area.fill({ color: 0x000000, alpha: 0.001 });
      area.eventMode = "static";
      area.cursor = "pointer";
      area.on("pointerdown", () => {
        canvasCallbacks.sendCommand?.(dir);
      });
      this.compassContainer.addChild(area);
      this.compassHitAreas.push({ dir, area });
    }
  }

  private async loadCompassAssets() {
    try {
      const texture = await Assets.load("/images/global_assets/compass_rose.png");
      const sprite = new Sprite(texture);
      sprite.width = COMPASS_SIZE;
      sprite.height = COMPASS_SIZE;
      sprite.anchor.set(0.5);
      sprite.eventMode = "none";
      // Insert after highlight graphics but before hit areas
      this.compassContainer.addChildAt(sprite, 1);
    } catch { /* fallback: no compass image */ }

    try {
      const tex = await Assets.load("/images/global_assets/stairs_up.png");
      const sprite = new Sprite(tex);
      sprite.width = STAIR_ICON_SIZE;
      sprite.height = STAIR_ICON_SIZE;
      sprite.anchor.set(0.5);
      sprite.eventMode = "none";
      sprite.visible = false;
      this.stairsUpSprite = sprite;
      this.compassContainer.addChild(sprite);

      const hit = new Graphics();
      hit.rect(0, 0, STAIR_ICON_SIZE, STAIR_ICON_SIZE);
      hit.fill({ color: 0x000000, alpha: 0.001 });
      hit.eventMode = "static";
      hit.cursor = "pointer";
      hit.visible = false;
      hit.on("pointerdown", () => { canvasCallbacks.sendCommand?.("up"); });
      this.stairsUpHit = hit;
      this.compassContainer.addChild(hit);
    } catch { /* no stairs up icon */ }

    try {
      const tex = await Assets.load("/images/global_assets/stairs_down.png");
      const sprite = new Sprite(tex);
      sprite.width = STAIR_ICON_SIZE;
      sprite.height = STAIR_ICON_SIZE;
      sprite.anchor.set(0.5);
      sprite.eventMode = "none";
      sprite.visible = false;
      this.stairsDownSprite = sprite;
      this.compassContainer.addChild(sprite);

      const hit = new Graphics();
      hit.rect(0, 0, STAIR_ICON_SIZE, STAIR_ICON_SIZE);
      hit.fill({ color: 0x000000, alpha: 0.001 });
      hit.eventMode = "static";
      hit.cursor = "pointer";
      hit.visible = false;
      hit.on("pointerdown", () => { canvasCallbacks.sendCommand?.("down"); });
      this.stairsDownHit = hit;
      this.compassContainer.addChild(hit);
    } catch { /* no stairs down icon */ }
  }

  private updateCompassHighlights(exitDirs: string[]) {
    const key = exitDirs.sort().join(",");
    const lastKey = this.lastExitDirs.sort().join(",");
    if (key === lastKey) return;
    this.lastExitDirs = [...exitDirs];

    const g = this.compassHighlightGraphics;
    g.clear();

    const r = COMPASS_SIZE / 2;
    const cardinals = ["north", "south", "east", "west"];
    const angles: Record<string, number> = { north: -Math.PI / 2, east: 0, south: Math.PI / 2, west: Math.PI };

    for (const dir of cardinals) {
      const available = exitDirs.includes(dir);
      const angle = angles[dir];
      // Draw a subtle glow wedge for available directions
      if (available) {
        g.moveTo(0, 0);
        g.arc(0, 0, r * 0.85, angle - Math.PI / 6, angle + Math.PI / 6);
        g.lineTo(0, 0);
        g.fill({ color: 0xb9aed8, alpha: 0.25 });
      }
    }

    // Update hit area cursors
    for (const { dir, area } of this.compassHitAreas) {
      const available = exitDirs.includes(dir);
      area.cursor = available ? "pointer" : "default";
      area.alpha = available ? 1 : 0.3;
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
    for (const icon of this.dialogueIcons.values()) {
      this.container.removeChild(icon);
      icon.destroy();
    }
    this.dialogueIcons.clear();
    for (const icon of this.aggroIcons.values()) {
      this.container.removeChild(icon);
      icon.destroy();
    }
    this.aggroIcons.clear();
    for (const icon of this.questAvailableIcons.values()) {
      this.container.removeChild(icon);
      icon.destroy();
    }
    this.questAvailableIcons.clear();
    for (const icon of this.questCompleteIcons.values()) {
      this.container.removeChild(icon);
      icon.destroy();
    }
    this.questCompleteIcons.clear();
    this.mobSprites.clear();

    for (const mob of mobs) {
      const sprite = new Sprite(Texture.WHITE);
      sprite.width = BASE_SPRITE_SIZE;
      sprite.height = BASE_SPRITE_SIZE;
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
      hitArea.rect(0, 0, BASE_SPRITE_SIZE, BASE_SPRITE_SIZE);
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
      sprite.width = BASE_ITEM_SPRITE_SIZE;
      sprite.height = BASE_ITEM_SPRITE_SIZE;
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
      hitArea.rect(0, 0, BASE_ITEM_SPRITE_SIZE, BASE_ITEM_SPRITE_SIZE);
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

    const otherSize = BASE_SPRITE_SIZE * 0.75;
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
      sprite.width = BASE_SPRITE_SIZE;
      sprite.height = BASE_SPRITE_SIZE;
      sprite.anchor.set(0.5);
      sprite.tint = 0x81a2be;
      this.container.addChild(sprite);
      this.playerSprite = sprite;
      return;
    }

    try {
      const texture = await Assets.load(spritePath);
      const sprite = new Sprite(texture);
      sprite.width = BASE_SPRITE_SIZE;
      sprite.height = BASE_SPRITE_SIZE;
      sprite.anchor.set(0.5);
      this.container.addChild(sprite);
      this.playerSprite = sprite;
    } catch {
      const sprite = new Sprite(Texture.WHITE);
      sprite.width = BASE_SPRITE_SIZE;
      sprite.height = BASE_SPRITE_SIZE;
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

  private async loadDialogueTexture() {
    try {
      this.dialogueTexture = await Assets.load("/images/global_assets/dialog_indicator.png");
    } catch {
      // Fallback: no dialogue sprites
    }
  }

  private async loadAggroTexture() {
    try {
      this.aggroTexture = await Assets.load("/images/global_assets/aggro_indicator.png");
    } catch {
      // Fallback: no aggro sprites
    }
  }

  private ensureDialogueIcon(mobId: string, cx: number, cy: number) {
    if (!this.dialogueTexture) return;
    let icon = this.dialogueIcons.get(mobId);
    if (!icon) {
      icon = new Sprite(this.dialogueTexture);
      icon.width = DIALOGUE_ICON_SIZE;
      icon.height = DIALOGUE_ICON_SIZE;
      icon.anchor.set(0.5);
      icon.eventMode = "none";
      this.dialogueIcons.set(mobId, icon);
      this.container.addChild(icon);
    }
    icon.x = cx - 20;
    icon.y = cy - this.currentMobSize / 2 - 8;
  }

  private ensureAggroIcon(mobId: string, cx: number, cy: number) {
    if (!this.aggroTexture) return;
    let icon = this.aggroIcons.get(mobId);
    if (!icon) {
      icon = new Sprite(this.aggroTexture);
      icon.width = AGGRO_ICON_SIZE;
      icon.height = AGGRO_ICON_SIZE;
      icon.anchor.set(0.5);
      icon.eventMode = "none";
      this.aggroIcons.set(mobId, icon);
      this.container.addChild(icon);
    }
    icon.x = cx + this.currentMobSize / 2 - 4;
    icon.y = cy - this.currentMobSize / 2 - 8;
  }

  private async loadQuestTextures() {
    try {
      this.questAvailableTexture = await Assets.load("/images/global_assets/quest_available_indicator.png");
    } catch { /* no sprite */ }
    try {
      this.questCompleteTexture = await Assets.load("/images/global_assets/quest_complete_indicator.png");
    } catch { /* no sprite */ }
  }

  private ensureQuestIcon(mobId: string, cx: number, cy: number, type: "available" | "complete") {
    const map = type === "complete" ? this.questCompleteIcons : this.questAvailableIcons;
    const otherMap = type === "complete" ? this.questAvailableIcons : this.questCompleteIcons;
    const texture = type === "complete" ? this.questCompleteTexture : this.questAvailableTexture;
    // Remove conflicting icon (available vs complete are mutually exclusive)
    const other = otherMap.get(mobId);
    if (other) {
      this.container.removeChild(other);
      other.destroy();
      otherMap.delete(mobId);
    }
    if (!texture) return;
    let icon = map.get(mobId);
    if (!icon) {
      icon = new Sprite(texture);
      icon.width = QUEST_ICON_SIZE;
      icon.height = QUEST_ICON_SIZE;
      icon.anchor.set(0.5);
      icon.eventMode = "none";
      map.set(mobId, icon);
      this.container.addChild(icon);
    }
    icon.x = cx;
    icon.y = cy - this.currentMobSize / 2 - 20;
  }

  private pruneIcons(map: Map<string, Sprite>, active: Set<string>) {
    for (const [id, icon] of map) {
      if (!active.has(id)) {
        this.container.removeChild(icon);
        icon.destroy();
        map.delete(id);
      }
    }
  }

  private showPopout() {
    // Re-add backdrop and popout so they render on top of dynamically added sprites
    this.container.addChild(this.backdropHit);
    this.container.addChild(this.entityPopout.container);
    this.backdropHit.visible = true;
  }

  destroy() {
    this.compassContainer.destroy({ children: true });
    this.minimap.destroy();
    this.entityPopout.destroy();
    this.container.destroy({ children: true });
  }
}
