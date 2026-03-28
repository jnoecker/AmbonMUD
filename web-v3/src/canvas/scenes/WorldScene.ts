import { Container, Graphics, Sprite, Text, Texture, Assets } from "pixi.js";
import { gameStateRef, canvasCallbacks, pendingCastRef } from "../GameStateBridge";
import { StatusEffectDisplay } from "../systems/StatusEffectDisplay";
import { Minimap } from "../systems/Minimap";
import { EntityPopout } from "../systems/EntityPopout";
import { AmbientMotes } from "../systems/AmbientMotes";
import { RoomTransition } from "../systems/RoomTransition";
import type { MobInfo } from "../../types";

/** Resolves a global asset key to its server-provided URL, with a hardcoded fallback. */
function assetUrl(key: string, fallbackFilename: string): string {
  return gameStateRef.current.serverAssets[key] ?? `/images/global_assets/${fallbackFilename}`;
}

const SHOP_BADGE_SIZE = 96;
const QUEST_ICON_SIZE = 28;

const PLAYER_LABEL_COLOR = "#d8dcef";
const OTHER_PLAYER_LABEL_COLOR = "#81a2be";
const MOB_LABEL_COLOR = "#f0c674";
const ITEM_LABEL_COLOR = "#8abeb7";
const PLAYER_LABEL_FONT_SIZE = 15;
const MOB_LABEL_FONT_SIZE = 14;
const ITEM_LABEL_FONT_SIZE = 13;
const MINIMAP_DESKTOP = 140;
const MINIMAP_MOBILE = 100;
const MINIMAP_MARGIN = 16;
const BASE_SPRITE_SIZE = 128;
const BASE_ITEM_SPRITE_SIZE = 96;
const REF_WIDTH = 1200;
const REF_HEIGHT = 800;
const MIN_SPRITE_SIZE = 64;
const MAX_SPRITE_SIZE = 192;
const MIN_ITEM_SIZE = 32;
const MAX_ITEM_SIZE = 96;

const clamp = (v: number, min: number, max: number) => Math.max(min, Math.min(max, v));
const ROLE_ICON_SIZE = 12;
const ROLE_ICON_GAP = 4;
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
  /** Overlay container for effects that must stay visible while container.alpha is modified (e.g. room transitions). */
  readonly overlayContainer = new Container();

  private background: Sprite | null = null;
  private titleText: Text;
  private descText: Text;
  private descBg = new Graphics();
  private playerSprite: Sprite | null = null;
  private playerLabel: Text;
  private mobSprites: Map<string, { sprite: Sprite; label: Text; hitArea: Graphics }> = new Map();
  private itemSprites: Array<{ sprite: Sprite; label: Text; labelBg: Graphics; hitArea: Graphics }> = [];
  private playerSprites: Map<string, { sprite: Sprite; label: Text; hitArea: Graphics }> = new Map();
  private roleGraphics = new Graphics();
  private statusEffects = new StatusEffectDisplay();
  private minimap = new Minimap();
  private entityPopout = new EntityPopout();
  private ambientMotes = new AmbientMotes();
  private roomTransition = new RoomTransition();

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

  private shopBadge: Container;
  private shopSprite: Sprite | null = null;
  private shopLabel: Text;
  private shopHitArea = new Graphics();
  private shopVisible = false;

  private targetingText: Text | null = null;
  private targetingBg = new Graphics();
  private targetingAnimTime = 0;
  private targetingActive = false;

  // Recall button (visible when logged in and not in combat)
  private recallBtn: Container;
  private lastLoggedIn = false;

  private videoBtn: Sprite | null = null;
  private videoAnimTime = 0;
  private lastRoomVideo: string | null | undefined = undefined;

  private lastRoomId: string | null = null;
  private lastRoomImage: string | null | undefined = undefined;
  private lastPlayerSpritePath: string | null = null;
  private nodeSprites: Array<{ sprite: Sprite; label: Text; labelBg: Graphics; hitArea: Graphics }> = [];
  private stationBadge: Container;
  private stationLabel: Text;
  private stationHitArea = new Graphics();
  private stationVisible = false;

  private lastMobsKey = "";
  private lastItemsKey = "";
  private lastNodesKey = "";
  private lastPlayersKey = "";
  private lastMobInfoKey = "";
  private assetsLoaded = false;
  private width = 0;
  private height = 0;

  // Room transition handled by RoomTransition system

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
    // Asset-dependent sprites (shop, dialogue, aggro, quest) are loaded
    // lazily in update() once Server.Assets GMCP arrives, to avoid 404s
    // from fallback URLs when assets live on a CDN.

    // Station badge — floating anvil icon when a crafting station is present
    this.stationBadge = new Container();
    this.stationBadge.visible = false;
    this.stationBadge.eventMode = "static";
    this.stationBadge.cursor = "pointer";
    this.stationBadge.on("pointerdown", () => {
      canvasCallbacks.sendCommand?.("recipes");
    });
    this.stationHitArea.rect(-hs / 2, -hs / 2, hs, hs + 20);
    this.stationHitArea.fill({ color: 0x000000, alpha: 0.001 });
    this.stationHitArea.eventMode = "auto";
    this.stationBadge.addChild(this.stationHitArea);
    this.stationLabel = new Text({
      text: "Station",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 11, fill: "#8da97b", dropShadow: { color: 0x000000, alpha: 1, blur: 4, distance: 0 } },
    });
    this.stationLabel.anchor.set(0.5, 0);
    this.stationLabel.y = hs / 2 + 2;
    this.stationLabel.eventMode = "none";
    this.stationBadge.addChild(this.stationLabel);

    // Recall button
    this.recallBtn = this.buildActionButton("Recall", 0xb9aed8, 0x2a2845, () => {
      canvasCallbacks.sendCommand?.("recall");
    });
    this.recallBtn.visible = false;

    this.container.addChild(this.ambientMotes.graphics);
    this.container.addChild(this.roleGraphics);
    this.container.addChild(this.statusEffects.container);
    this.container.addChild(this.titleText);
    this.container.addChild(this.descBg);
    this.container.addChild(this.descText);
    this.container.addChild(this.roomExpandBtn);
    this.container.addChild(this.playerLabel);
    this.container.addChild(this.minimap.container);
    this.container.addChild(this.shopBadge);
    this.container.addChild(this.stationBadge);
    this.container.addChild(this.recallBtn);
    this.container.addChild(this.backdropHit);
    this.container.addChild(this.entityPopout.container);
    // Transition graphics live in the overlay so they stay visible while
    // container.alpha fades to 0 during the dissolve phase.
    this.overlayContainer.addChild(this.roomTransition.graphics);
  }

  resize(width: number, height: number) {
    this.width = width;
    this.height = height;
    this.entityPopout.resize(width, height);
    this.ambientMotes.resize(width, height);
    this.roomTransition.resize(width, height);

    // Update backdrop size
    this.backdropHit.clear();
    this.backdropHit.rect(0, 0, width, height);
    this.backdropHit.fill({ color: 0x000000, alpha: 0.001 });

    this.layoutAll();
  }

  update(deltaMs: number) {
    const state = gameStateRef.current;
    const { room, character, mobs, roomItems, players, mobInfo } = state;

    // Reload asset-dependent sprites once Server.Assets GMCP arrives
    if (!this.assetsLoaded && Object.keys(state.serverAssets).length > 0) {
      this.assetsLoaded = true;
      this.loadShopIcon();
      this.loadDialogueTexture();
      this.loadAggroTexture();
      this.loadQuestTextures();
    }

    // Animate video indicator: glow pulse + breathing scale
    if (this.videoBtn) {
      this.videoAnimTime += deltaMs / 1000;
      const t = this.videoAnimTime;
      const pulse = 0.7 + 0.3 * Math.sin(t * 2.0);
      this.videoBtn.alpha = pulse;
      const breathe = 1.0 + 0.08 * Math.sin(t * 1.6);
      this.videoBtn.scale.set(breathe);
    }

    // Handle room transition animation (magical particle dissolve)
    if (this.roomTransition.isActive) {
      this.roomTransition.update(deltaMs);
      this.container.alpha = this.roomTransition.sceneAlpha;
    } else {
      this.container.alpha = 1;
    }

    const roomChanged = room.id !== this.lastRoomId;
    if (roomChanged) {
      if (this.lastRoomId !== null) {
        this.roomTransition.start();
      }
      this.lastRoomId = room.id;
      this.titleText.text = room.title !== "-" ? room.title : "";
      this.descText.text = room.description || "";
      if (room.id) this.ambientMotes.setRoom(room.id);
      // Dismiss popout on room change
      this.entityPopout.hide();
      this.backdropHit.visible = false;
    }

    // Update ambient motes (zone-themed floating particles)
    this.ambientMotes.update(deltaMs);

    // Update minimap
    this.minimap.updateRoom(room.id, room.exits, room.title !== "-" ? room.title : "", room.image ?? null, room.mapX, room.mapY);

    if (room.image !== this.lastRoomImage) {
      this.lastRoomImage = room.image;
      this.loadBackground(room.image ?? null);
    }

    if (room.video !== this.lastRoomVideo) {
      this.lastRoomVideo = room.video;
      this.updateVideoButton(room.video ?? null);
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

    const nodes = state.craftingNodes;
    const nodesKey = nodes.map((n) => n.id).join("|");
    if (nodesKey !== this.lastNodesKey) {
      this.lastNodesKey = nodesKey;
      this.rebuildNodes(nodes);
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

    // Station badge visibility
    const hasStation = !!state.room.station;
    if (hasStation !== this.stationVisible) {
      this.stationVisible = hasStation;
      this.stationBadge.visible = hasStation;
      if (hasStation) {
        const stationName = state.room.station!.split("_").map((w: string) => w.charAt(0).toUpperCase() + w.slice(1)).join(" ");
        this.stationLabel.text = stationName;
      }
    }

    // Recall button visibility — show when logged in and not in combat
    const loggedIn = state.character.name !== "-";
    const showRecall = loggedIn && !state.vitals.inCombat;
    if (showRecall !== this.lastLoggedIn) {
      this.lastLoggedIn = showRecall;
      this.recallBtn.visible = showRecall;
    }

    this.layoutAll();
    this.updateTargetingOverlay(deltaMs);
  }

  private layoutAll() {
    const w = this.width;
    const h = this.height;
    if (w === 0 || h === 0) return;

    if (this.background) {
      this.background.width = w;
      this.background.height = h;
    }

    // Scale text sizes for small canvases (mobile)
    const textScale = Math.max(0.6, Math.min(1.0, w / 700));
    this.titleText.style.fontSize = Math.round(26 * textScale);
    this.descText.style.fontSize = Math.round(18 * textScale);

    // Minimap in bottom-right — smaller on mobile
    const mapDiam = w < 500 ? MINIMAP_MOBILE : MINIMAP_DESKTOP;
    this.minimap.setDiameter(mapDiam);
    this.minimap.layout(w - mapDiam - MINIMAP_MARGIN, h - this.minimap.totalHeight - MINIMAP_MARGIN);

    // Room title and description in top-left (full width now that minimap moved)
    const textLeft = 16;
    const textMaxWidth = Math.max(200, w - textLeft - 40);
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
      for (const { sprite, label, labelBg, hitArea } of this.itemSprites) {
        sprite.x = itemX;
        sprite.y = itemY;
        sprite.width = itemSize;
        sprite.height = itemSize;
        label.x = itemX;
        label.y = itemY + itemSize / 2 + 4;

        // Dark pill behind label for readability on busy backgrounds
        const lw = label.width + 10;
        const lh = label.height + 4;
        labelBg.clear();
        labelBg.roundRect(itemX - lw / 2, label.y - 2, lw, lh, 6);
        labelBg.fill({ color: 0x0a0c14, alpha: 0.7 });

        hitArea.clear();
        hitArea.rect(0, 0, itemSize, itemSize);
        hitArea.fill({ color: 0x000000, alpha: 0.001 });
        hitArea.x = itemX - itemSize / 2;
        hitArea.y = itemY - itemSize / 2;
        itemX += itemSpacing;
      }
    }

    // Layout gathering node sprites — below items
    const nodeCount = this.nodeSprites.length;
    if (nodeCount > 0) {
      const nodeY = h * 0.50;
      const nodeSpacing = Math.min(itemSize + 16, itemAreaWidth / Math.max(1, nodeCount));
      const totalNodeWidth = (nodeCount - 1) * nodeSpacing;
      let nodeX = w / 2 - totalNodeWidth / 2;
      for (const { sprite, label, labelBg, hitArea } of this.nodeSprites) {
        sprite.x = nodeX;
        sprite.y = nodeY;
        sprite.width = itemSize;
        sprite.height = itemSize;
        label.x = nodeX;
        label.y = nodeY + itemSize / 2 + 4;

        const lw = label.width + 10;
        const lh = label.height + 4;
        labelBg.clear();
        labelBg.roundRect(nodeX - lw / 2, label.y - 2, lw, lh, 6);
        labelBg.fill({ color: 0x0a0c14, alpha: 0.7 });

        hitArea.clear();
        hitArea.rect(0, 0, itemSize, itemSize);
        hitArea.fill({ color: 0x000000, alpha: 0.001 });
        hitArea.x = nodeX - itemSize / 2;
        hitArea.y = nodeY - itemSize / 2;
        nodeX += nodeSpacing;
      }
    }

    // Shop badge position — right side, below description area
    if (this.shopBadge.visible) {
      this.shopBadge.x = w - 70;
      this.shopBadge.y = h * 0.35;
    }

    // Station badge position — right side, below shop badge
    if (this.stationBadge.visible) {
      this.stationBadge.x = w - 70;
      this.stationBadge.y = this.shopBadge.visible ? h * 0.48 : h * 0.35;
    }

    // Recall button — bottom-left
    if (this.recallBtn.visible) {
      this.recallBtn.x = 16 + 40;
      this.recallBtn.y = h - 24;
    }

    // Video button: bottom-center
    if (this.videoBtn) {
      this.videoBtn.x = w / 2;
      this.videoBtn.y = h - 80;
    }

    // Role indicators
    this.roleGraphics.clear();

    // Draw NPC role indicators
    const state = gameStateRef.current;
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

  private updateTargetingOverlay(deltaMs: number) {
    const pending = pendingCastRef.current;
    if (pending) {
      this.targetingAnimTime += deltaMs / 1000;
      const pulse = 0.6 + 0.4 * Math.sin(this.targetingAnimTime * 4.0);

      if (!this.targetingText) {
        this.targetingText = new Text({
          text: "",
          style: {
            fontFamily: "Lora, Georgia, serif",
            fontSize: 14,
            fill: "#f0c0d0",
            dropShadow: { color: 0x000000, alpha: 0.8, blur: 4, distance: 1 },
          },
        });
        this.targetingText.anchor.set(0.5, 0);
        this.container.addChild(this.targetingBg);
        this.container.addChild(this.targetingText);
      }
      const msg = `Select target for ${pending.skillName}`;
      if (this.targetingText.text !== msg) this.targetingText.text = msg;
      const tw = this.targetingText.width;
      const tx = this.width / 2;
      const ty = 10;
      this.targetingText.x = tx;
      this.targetingText.y = ty + 6;
      this.targetingBg.clear();
      this.targetingBg.roundRect(tx - tw / 2 - 12, ty, tw + 24, 28, 6);
      this.targetingBg.fill({ color: 0x2a1a30, alpha: 0.85 });
      this.targetingBg.roundRect(tx - tw / 2 - 12, ty, tw + 24, 28, 6);
      this.targetingBg.stroke({ color: 0xd46a8a, width: 1, alpha: 0.6 });
      this.targetingBg.visible = true;
      this.targetingText.visible = true;

      // Pulse targetable entities
      const isEnemy = pending.targetType === "ENEMY";
      const isAlly = pending.targetType === "ALLY";
      if (!this.targetingActive) {
        // Set cursor once on targeting start
        for (const { hitArea } of this.mobSprites.values()) {
          if (isEnemy) hitArea.cursor = "crosshair";
        }
        for (const { hitArea } of this.playerSprites.values()) {
          if (isAlly) hitArea.cursor = "crosshair";
        }
        this.targetingActive = true;
      }
      // Animate alpha every frame
      for (const { sprite } of this.mobSprites.values()) {
        if (isEnemy) sprite.alpha = pulse;
      }
      for (const { sprite } of this.playerSprites.values()) {
        if (isAlly) sprite.alpha = pulse;
      }
    } else {
      if (this.targetingText) {
        this.targetingText.visible = false;
        this.targetingBg.visible = false;
      }
      // Restore entity visuals when targeting ends
      if (this.targetingActive) {
        this.targetingActive = false;
        this.targetingAnimTime = 0;
        for (const { sprite, hitArea } of this.mobSprites.values()) {
          sprite.alpha = 1;
          hitArea.cursor = "pointer";
        }
        for (const { sprite, hitArea } of this.playerSprites.values()) {
          sprite.alpha = 1;
          hitArea.cursor = "pointer";
        }
      }
    }
  }

  private rebuildMobs(mobs: Array<{ id: string; name: string; description?: string; hp: number; maxHp: number; image?: string | null; video?: string | null }>) {
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
        if (pendingCastRef.current) {
          canvasCallbacks.onTargetSelected?.(mobData.name);
          return;
        }
        const info = gameStateRef.current.mobInfo.find((m) => m.id === mobData.id) ?? null;
        const isStaff = gameStateRef.current.character.isStaff;
        this.entityPopout.showMob(mobData.name, mobData.description, mobData.image, mobData.video, mobData.hp, mobData.maxHp, info, isStaff);
        this.showPopout();
      });

      this.container.addChild(sprite);
      this.container.addChild(label);
      this.container.addChild(hitArea);
      this.mobSprites.set(mob.id, { sprite, label, hitArea });
    }
  }

  private rebuildItems(items: Array<{ id: string; name: string; description?: string; image?: string | null; video?: string | null }>) {
    for (const { sprite, label, labelBg, hitArea } of this.itemSprites) {
      this.container.removeChild(sprite);
      this.container.removeChild(labelBg);
      this.container.removeChild(label);
      this.container.removeChild(hitArea);
      sprite.destroy();
      labelBg.destroy();
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
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: ITEM_LABEL_FONT_SIZE, fill: ITEM_LABEL_COLOR, dropShadow: { color: 0x000000, alpha: 0.8, blur: 4, distance: 0 } },
      });
      label.anchor.set(0.5, 0);

      // Dark pill behind label for readability on busy backgrounds
      const labelBg = new Graphics();
      labelBg.eventMode = "none";

      const hitArea = new Graphics();
      hitArea.rect(0, 0, BASE_ITEM_SPRITE_SIZE, BASE_ITEM_SPRITE_SIZE);
      hitArea.fill({ color: 0x000000, alpha: 0.001 });
      hitArea.eventMode = "static";
      hitArea.cursor = "pointer";

      const itemData = item;
      hitArea.on("pointerdown", () => {
        this.entityPopout.showItem(itemData.name, itemData.description, itemData.image, itemData.video);
        this.showPopout();
      });

      this.container.addChild(sprite);
      this.container.addChild(labelBg);
      this.container.addChild(label);
      this.container.addChild(hitArea);
      this.itemSprites.push({ sprite, label, labelBg, hitArea });
    }
  }

  private rebuildNodes(nodes: Array<{ id: string; name: string; skill: string; skillRequired: number; image?: string | null }>) {
    for (const { sprite, label, labelBg, hitArea } of this.nodeSprites) {
      this.container.removeChild(sprite);
      this.container.removeChild(labelBg);
      this.container.removeChild(label);
      this.container.removeChild(hitArea);
      sprite.destroy();
      labelBg.destroy();
      label.destroy();
      hitArea.destroy();
    }
    this.nodeSprites = [];

    for (const node of nodes) {
      const sprite = new Sprite(Texture.WHITE);
      sprite.width = BASE_ITEM_SPRITE_SIZE;
      sprite.height = BASE_ITEM_SPRITE_SIZE;
      sprite.anchor.set(0.5);
      sprite.tint = 0x8da97b; // moss green

      if (node.image) {
        this.loadSpriteTexture(sprite, node.image);
      }

      const label = new Text({
        text: node.name,
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: ITEM_LABEL_FONT_SIZE, fill: "#8da97b", dropShadow: { color: 0x000000, alpha: 0.8, blur: 4, distance: 0 } },
      });
      label.anchor.set(0.5, 0);

      const labelBg = new Graphics();
      labelBg.eventMode = "none";

      const hitArea = new Graphics();
      hitArea.rect(0, 0, BASE_ITEM_SPRITE_SIZE, BASE_ITEM_SPRITE_SIZE);
      hitArea.fill({ color: 0x000000, alpha: 0.001 });
      hitArea.eventMode = "static";
      hitArea.cursor = "pointer";

      const nodeData = node;
      hitArea.on("pointerdown", () => {
        canvasCallbacks.sendCommand?.(`gather ${nodeData.name}`);
      });

      this.container.addChild(sprite);
      this.container.addChild(labelBg);
      this.container.addChild(label);
      this.container.addChild(hitArea);
      this.nodeSprites.push({ sprite, label, labelBg, hitArea });
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
        if (pendingCastRef.current) {
          canvasCallbacks.onTargetSelected?.(playerData.name);
          return;
        }
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

  private updateVideoButton(videoUrl: string | null) {
    if (this.videoBtn) {
      this.container.removeChild(this.videoBtn);
      this.videoBtn.destroy({ children: true });
      this.videoBtn = null;
    }

    if (!videoUrl) return;

    const SIZE = 72;
    const sprite = new Sprite(Texture.WHITE);
    sprite.width = SIZE;
    sprite.height = SIZE;
    sprite.anchor.set(0.5);
    sprite.tint = 0xce93d8;
    sprite.eventMode = "static";
    sprite.cursor = "pointer";

    Assets.load(assetUrl("video_available_indicator", "video_available_indicator.png")).then((tex) => {
      sprite.texture = tex;
      sprite.tint = 0xffffff;
    }).catch(() => { /* keep placeholder */ });

    const url = videoUrl;
    sprite.on("pointerdown", () => {
      canvasCallbacks.openVideo?.(url);
    });

    // Position: bottom-center, above action bar
    sprite.x = this.width / 2;
    sprite.y = this.height - 80;

    this.container.addChild(sprite);
    this.videoBtn = sprite;
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
      const texture = await Assets.load(assetUrl("shop_kiosk", "shop_kiosk.png"));
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
      this.dialogueTexture = await Assets.load(assetUrl("dialog_indicator", "dialog_indicator.png"));
    } catch {
      // Fallback: no dialogue sprites
    }
  }

  private async loadAggroTexture() {
    try {
      this.aggroTexture = await Assets.load(assetUrl("aggro_indicator", "aggro_indicator.png"));
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
      this.questAvailableTexture = await Assets.load(assetUrl("quest_available_indicator", "quest_available_indicator.png"));
    } catch { /* no sprite */ }
    try {
      this.questCompleteTexture = await Assets.load(assetUrl("quest_complete_indicator", "quest_complete_indicator.png"));
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

  private buildActionButton(label: string, color: number, bgColor: number, onClick: () => void): Container {
    const btn = new Container();
    btn.eventMode = "static";
    btn.cursor = "pointer";

    const btnW = 80;
    const btnH = 32;
    const bg = new Graphics();
    bg.roundRect(-btnW / 2, -btnH / 2, btnW, btnH, 6);
    bg.fill({ color: bgColor, alpha: 0.9 });
    bg.roundRect(-btnW / 2, -btnH / 2, btnW, btnH, 6);
    bg.stroke({ color, alpha: 0.6, width: 1.5 });

    const text = new Text({
      text: label,
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 12, fill: `#${color.toString(16).padStart(6, "0")}`, fontWeight: "bold" },
    });
    text.anchor.set(0.5, 0.5);
    text.eventMode = "none";

    btn.addChild(bg);
    btn.addChild(text);

    btn.on("pointerover", () => {
      bg.clear();
      bg.roundRect(-btnW / 2, -btnH / 2, btnW, btnH, 6);
      bg.fill({ color: bgColor, alpha: 1 });
      bg.roundRect(-btnW / 2, -btnH / 2, btnW, btnH, 6);
      bg.stroke({ color, alpha: 0.9, width: 2 });
    });
    btn.on("pointerout", () => {
      bg.clear();
      bg.roundRect(-btnW / 2, -btnH / 2, btnW, btnH, 6);
      bg.fill({ color: bgColor, alpha: 0.9 });
      bg.roundRect(-btnW / 2, -btnH / 2, btnW, btnH, 6);
      bg.stroke({ color, alpha: 0.6, width: 1.5 });
    });
    btn.on("pointerdown", onClick);

    return btn;
  }

  private showPopout() {
    // Re-add backdrop and popout so they render on top of dynamically added sprites
    this.container.addChild(this.backdropHit);
    this.container.addChild(this.entityPopout.container);
    this.backdropHit.visible = true;
  }

  destroy() {
    this.minimap.destroy();
    this.entityPopout.destroy();
    this.container.destroy({ children: true });
    this.overlayContainer.destroy({ children: true });
  }
}
