import { Container, Graphics, Sprite, Text, Texture, Assets } from "pixi.js";
import { gameStateRef, canvasCallbacks, pendingCastRef } from "../GameStateBridge";
import { StatusEffectDisplay } from "../systems/StatusEffectDisplay";
import { Minimap } from "../systems/Minimap";
import { EntityPopout } from "../systems/EntityPopout";
import { AmbientMotes } from "../systems/AmbientMotes";
import { RoomTransition } from "../systems/RoomTransition";
import { SkyRenderer } from "../systems/SkyRenderer";
import { WeatherParticles } from "../systems/WeatherParticles";
import type { MobInfo } from "../../types";
import { ROOM_SURFACE_WIDGETS } from "../../featureMetadata";

/** Resolves a global asset key to its server-provided URL, with a hardcoded fallback. */
function assetUrl(key: string, fallbackFilename: string): string {
  return gameStateRef.current.serverAssets[key] ?? `/images/global_assets/${fallbackFilename}`;
}

const SHOP_BADGE_SIZE = 96;
// Status indicator icons (quest/dialogue/aggro) size responsively off the mob
// sprite, clamped to this range so they stay prominent without overwhelming.
const STATUS_ICON_RATIO = 0.42;
const STATUS_ICON_MIN = 36;
const STATUS_ICON_MAX = 88;
const LABEL_BG_COLOR = 0x0a0c14;
const LABEL_BG_ALPHA = 0.7;
const LABEL_PAD_X = 10;
const LABEL_PAD_Y = 4;
const LABEL_RADIUS = 6;

/** Draw a dark rounded-rect pill behind a centered text label. Optionally add a glow halo. */
function drawLabelPill(bg: Graphics, label: Text, glowColor?: number) {
  const lw = label.width + LABEL_PAD_X;
  const lh = label.height + LABEL_PAD_Y;
  bg.clear();
  if (glowColor != null) {
    // Faint outer glow halo
    bg.roundRect(label.x - lw / 2 - 3, label.y - LABEL_PAD_Y / 2 - 3, lw + 6, lh + 6, LABEL_RADIUS + 3);
    bg.fill({ color: glowColor, alpha: 0.15 });
  }
  bg.roundRect(label.x - lw / 2, label.y - LABEL_PAD_Y / 2, lw, lh, LABEL_RADIUS);
  bg.fill({ color: LABEL_BG_COLOR, alpha: LABEL_BG_ALPHA });
}

function featureCountLabel(count: number, singular: string, plural: string): string {
  if (count <= 1) return singular;
  return `${count} ${plural}`;
}

const PLAYER_LABEL_COLOR = "#d8dcef";
const OTHER_PLAYER_LABEL_COLOR = "#81a2be";
const PET_LABEL_COLOR = "#b294bb";
const MOB_LABEL_COLOR = "#f0c674";
const ITEM_LABEL_COLOR = "#8abeb7";
const PLAYER_LABEL_FONT_SIZE = 15;
const MOB_LABEL_FONT_SIZE = 14;
const ITEM_LABEL_FONT_SIZE = 13;
const MINIMAP_DESKTOP = 240;
const MINIMAP_MOBILE = 208;
const MINIMAP_MARGIN = 14;
// Player and combat mobs share BASE_SPRITE_SIZE; non-combat NPCs (prop / quest
// giver / dialogue) render notably larger so they read as characters, not props.
const BASE_SPRITE_SIZE = 168;
const PLAYER_SPRITE_SIZE = 196;
const NONCOMBAT_SPRITE_SIZE = 236;
const BASE_ITEM_SPRITE_SIZE = 116;
const REF_WIDTH = 1200;
const REF_HEIGHT = 800;
const MIN_SPRITE_SIZE = 72;
const MAX_SPRITE_SIZE = 320;
const MIN_ITEM_SIZE = 40;
const MAX_ITEM_SIZE = 140;

const clamp = (v: number, min: number, max: number) => Math.max(min, Math.min(max, v));
const ROLE_ICON_SIZE = 12;
const ROLE_ICON_GAP = 4;
// Role indicator colors
const ROLE_SHOP_COLOR = 0x81a2be;

/** Responsive size for a status indicator icon given the host mob's sprite size. */
function statusIconSize(mobSize: number): number {
  return clamp(mobSize * STATUS_ICON_RATIO, STATUS_ICON_MIN, STATUS_ICON_MAX);
}

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
  private playerSprite: Sprite | null = null;
  private playerLabel: Text;
  private playerLabelBg = new Graphics();
  private mobSprites: Map<string, { sprite: Sprite; label: Text; labelBg: Graphics; hitArea: Graphics; name: string; count: number; ids: string[] }> = new Map();
  private petSprites: Map<string, { sprite: Sprite; label: Text; labelBg: Graphics; hitArea: Graphics }> = new Map();
  private itemSprites: Array<{ sprite: Sprite; label: Text; labelBg: Graphics; hitArea: Graphics }> = [];
  private playerSprites: Map<string, { sprite: Sprite; label: Text; labelBg: Graphics; hitArea: Graphics }> = new Map();
  private roleGraphics = new Graphics();
  private statusEffects = new StatusEffectDisplay();
  private minimap = new Minimap();
  private entityPopout = new EntityPopout();
  private ambientMotes = new AmbientMotes();
  private roomTransition = new RoomTransition();
  private skyRenderer = new SkyRenderer();
  private weatherParticles = new WeatherParticles();
  private lastZoneEnvZone: string | null = null;
  private lastWeatherHint = "";

  private dialogueTexture: Texture | null = null;
  private dialogueIcons: Map<string, Sprite> = new Map();
  private aggroTexture: Texture | null = null;
  private aggroIcons: Map<string, Sprite> = new Map();
  private questAvailableTexture: Texture | null = null;
  private questAvailableIcons: Map<string, Sprite> = new Map();
  private questCompleteTexture: Texture | null = null;
  private questCompleteIcons: Map<string, Sprite> = new Map();

  private shopBadge: Container;
  private shopSprite: Sprite | null = null;
  private shopLabel: Text;
  private shopLabelBg = new Graphics();
  private shopHitArea = new Graphics();
  private shopVisible = false;

  private auctionBadge: Container;
  private auctionSprite: Sprite | null = null;
  private auctionLabel: Text;
  private auctionLabelBg = new Graphics();
  private auctionHitArea = new Graphics();
  private auctionVisible = false;

  private stylistBadge: Container;
  private stylistSprite: Sprite | null = null;
  private stylistLabel: Text;
  private stylistLabelBg = new Graphics();
  private stylistHitArea = new Graphics();
  private stylistVisible = false;

  private targetingText: Text | null = null;
  private targetingBg = new Graphics();
  private targetingAnimTime = 0;
  private targetingActive = false;

  // Recall button (visible when logged in and not in combat)
  private recallBtn: Container;
  private lastLoggedIn = false;

  // Depart button (visible at the death sanctum when there's a place to return to)
  private departBtn: Container;
  private lastCanDepart = false;

  private videoBtn: Sprite | null = null;
  private videoAnimTime = 0;
  private lastRoomVideo: string | null | undefined = undefined;

  private lastRoomId: string | null = null;
  private lastRoomImage: string | null | undefined = undefined;
  private bgLoadToken = 0;
  private lastPlayerSpritePath: string | null = null;
  private nodeSprites: Array<{ sprite: Sprite; label: Text; labelBg: Graphics; hitArea: Graphics }> = [];
  private stationBadge: Container;
  private stationSprite: Sprite | null = null;
  private stationLabel: Text;
  private stationLabelBg = new Graphics();
  private stationHitArea = new Graphics();
  private stationVisible = false;

  private trainerBadge: Container;
  private trainerSprite: Sprite | null = null;
  private trainerLabel: Text;
  private trainerLabelBg = new Graphics();
  private trainerHitArea = new Graphics();
  private trainerVisible = false;

  private bankBadge: Container | null = null;
  private bankSprite: Sprite | null = null;
  private bankLabel: Text | null = null;
  private bankLabelBg = new Graphics();
  private bankHitArea = new Graphics();
  private bankVisible = false;

  private lotteryBadge: Container | null = null;
  private lotterySprite: Sprite | null = null;
  private lotteryLabel: Text | null = null;
  private lotteryLabelBg = new Graphics();
  private lotteryHitArea = new Graphics();
  private lotteryVisible = false;

  private dungeonBadge: Container | null = null;
  private dungeonSprite: Sprite | null = null;
  private dungeonLabel: Text | null = null;
  private dungeonLabelBg = new Graphics();
  private dungeonHitArea = new Graphics();
  private dungeonVisible = false;

  private housingBadge: Container | null = null;
  private housingSprite: Sprite | null = null;
  private housingLabel: Text | null = null;
  private housingLabelBg = new Graphics();
  private housingHitArea = new Graphics();
  private housingVisible = false;

  private innBadge: Container | null = null;
  private innSprite: Sprite | null = null;
  private innLabel: Text | null = null;
  private innLabelBg = new Graphics();
  private innHitArea = new Graphics();
  private innVisible = false;

  private duelBadge: Container | null = null;
  private duelSprite: Sprite | null = null;
  private duelLabel: Text | null = null;
  private duelLabelBg = new Graphics();
  private duelHitArea = new Graphics();
  private duelVisible = false;

  private puzzleBadge: Container | null = null;
  private puzzleSprite: Sprite | null = null;
  private puzzleLabel: Text | null = null;
  private puzzleLabelBg = new Graphics();
  private puzzleHitArea = new Graphics();
  private puzzleVisible = false;
  private doorBadge: Container | null = null;
  private doorSprite: Sprite | null = null;
  private doorLabel: Text | null = null;
  private doorLabelBg = new Graphics();
  private doorHitArea = new Graphics();
  private doorVisible = false;
  private doorCount = 0;

  private containerBadge: Container | null = null;
  private containerSprite: Sprite | null = null;
  private containerLabel: Text | null = null;
  private containerLabelBg = new Graphics();
  private containerHitArea = new Graphics();
  private containerVisible = false;
  private containerCount = 0;

  private leverBadge: Container | null = null;
  private leverSprite: Sprite | null = null;
  private leverLabel: Text | null = null;
  private leverLabelBg = new Graphics();
  private leverHitArea = new Graphics();
  private leverVisible = false;
  private leverCount = 0;

  private lastMobsKey = "";
  private lastPetsKey = "";
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
    this.playerLabel = new Text({
      text: "",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: PLAYER_LABEL_FONT_SIZE, fill: PLAYER_LABEL_COLOR, dropShadow: { color: 0x000000, alpha: 0.4, blur: 2, distance: 1 } },
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
    this.shopLabelBg.eventMode = "none";
    this.shopBadge.addChild(this.shopLabelBg);
    this.shopBadge.addChild(this.shopLabel);
    // Asset-dependent sprites (shop, dialogue, aggro, quest) are loaded
    // lazily in update() once Server.Assets GMCP arrives, to avoid 404s
    // from fallback URLs when assets live on a CDN.

    this.auctionBadge = new Container();
    this.auctionBadge.visible = false;
    this.auctionBadge.eventMode = "static";
    this.auctionBadge.cursor = "pointer";
    this.auctionBadge.on("pointerdown", () => {
      canvasCallbacks.openAuction?.();
    });
    this.auctionBadge.on("pointerover", () => {
      if (this.auctionSprite) this.auctionSprite.alpha = 1;
    });
    this.auctionBadge.on("pointerout", () => {
      if (this.auctionSprite) this.auctionSprite.alpha = 0.85;
    });
    this.auctionHitArea.rect(-hs / 2, -hs / 2, hs, hs + 20);
    this.auctionHitArea.fill({ color: 0x000000, alpha: 0.001 });
    this.auctionHitArea.eventMode = "auto";
    this.auctionBadge.addChild(this.auctionHitArea);
    this.auctionLabel = new Text({
      text: ROOM_SURFACE_WIDGETS.auction.label,
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 11, fill: "#d8c18b", dropShadow: { color: 0x000000, alpha: 1, blur: 4, distance: 0 } },
    });
    this.auctionLabel.anchor.set(0.5, 0);
    this.auctionLabel.y = hs / 2 + 2;
    this.auctionLabel.eventMode = "none";
    this.auctionLabelBg.eventMode = "none";
    this.auctionBadge.addChild(this.auctionLabelBg);
    this.auctionBadge.addChild(this.auctionLabel);

    // Stylist badge — floating kiosk icon when a stylist is available
    this.stylistBadge = new Container();
    this.stylistBadge.visible = false;
    this.stylistBadge.eventMode = "static";
    this.stylistBadge.cursor = "pointer";
    this.stylistBadge.on("pointerdown", () => {
      canvasCallbacks.openStylist?.();
    });
    this.stylistBadge.on("pointerover", () => {
      if (this.stylistSprite) this.stylistSprite.alpha = 1;
    });
    this.stylistBadge.on("pointerout", () => {
      if (this.stylistSprite) this.stylistSprite.alpha = 0.85;
    });
    this.stylistHitArea.rect(-hs / 2, -hs / 2, hs, hs + 20);
    this.stylistHitArea.fill({ color: 0x000000, alpha: 0.001 });
    this.stylistHitArea.eventMode = "auto";
    this.stylistBadge.addChild(this.stylistHitArea);
    this.stylistLabel = new Text({
      text: "Stylist",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 11, fill: "#c8a0d8", dropShadow: { color: 0x000000, alpha: 1, blur: 4, distance: 0 } },
    });
    this.stylistLabel.anchor.set(0.5, 0);
    this.stylistLabel.y = hs / 2 + 2;
    this.stylistLabel.eventMode = "none";
    this.stylistLabelBg.eventMode = "none";
    this.stylistBadge.addChild(this.stylistLabelBg);
    this.stylistBadge.addChild(this.stylistLabel);

    // Station badge — floating icon when a crafting station is present
    this.stationBadge = new Container();
    this.stationBadge.visible = false;
    this.stationBadge.eventMode = "static";
    this.stationBadge.cursor = "pointer";
    this.stationBadge.on("pointerdown", () => {
      canvasCallbacks.openCrafting?.();
    });
    this.stationBadge.on("pointerover", () => {
      if (this.stationSprite) this.stationSprite.alpha = 1;
    });
    this.stationBadge.on("pointerout", () => {
      if (this.stationSprite) this.stationSprite.alpha = 0.85;
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
    this.stationLabelBg.eventMode = "none";
    this.stationBadge.addChild(this.stationLabelBg);
    this.stationBadge.addChild(this.stationLabel);

    // Trainer badge — floating icon when a trainer is present
    this.trainerBadge = new Container();
    this.trainerBadge.visible = false;
    this.trainerBadge.eventMode = "static";
    this.trainerBadge.cursor = "pointer";
    this.trainerBadge.on("pointerdown", () => {
      canvasCallbacks.openTrainer?.();
    });
    this.trainerBadge.on("pointerover", () => {
      if (this.trainerSprite) this.trainerSprite.alpha = 1;
    });
    this.trainerBadge.on("pointerout", () => {
      if (this.trainerSprite) this.trainerSprite.alpha = 0.85;
    });
    this.trainerHitArea.rect(-hs / 2, -hs / 2, hs, hs + 20);
    this.trainerHitArea.fill({ color: 0x000000, alpha: 0.001 });
    this.trainerHitArea.eventMode = "auto";
    this.trainerBadge.addChild(this.trainerHitArea);
    this.trainerLabel = new Text({
      text: "Trainer",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 11, fill: "#b9aed8", dropShadow: { color: 0x000000, alpha: 1, blur: 4, distance: 0 } },
    });
    this.trainerLabel.anchor.set(0.5, 0);
    this.trainerLabel.y = hs / 2 + 2;
    this.trainerLabel.eventMode = "none";
    this.trainerLabelBg.eventMode = "none";
    this.trainerBadge.addChild(this.trainerLabelBg);
    this.trainerBadge.addChild(this.trainerLabel);

    // Bank badge — floating icon when a bank is present
    this.bankBadge = new Container();
    this.bankBadge.visible = false;
    this.bankBadge.eventMode = "static";
    this.bankBadge.cursor = "pointer";
    this.bankBadge.on("pointerdown", () => {
      canvasCallbacks.openBank?.();
    });
    this.bankBadge.on("pointerover", () => {
      if (this.bankSprite) this.bankSprite.alpha = 1;
    });
    this.bankBadge.on("pointerout", () => {
      if (this.bankSprite) this.bankSprite.alpha = 0.85;
    });
    this.bankHitArea.rect(-hs / 2, -hs / 2, hs, hs + 20);
    this.bankHitArea.fill({ color: 0x000000, alpha: 0.001 });
    this.bankHitArea.eventMode = "auto";
    this.bankBadge.addChild(this.bankHitArea);
    this.bankLabel = new Text({
      text: "Bank",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 11, fill: "#c9a84c", dropShadow: { color: 0x000000, alpha: 1, blur: 4, distance: 0 } },
    });
    this.bankLabel.anchor.set(0.5, 0);
    this.bankLabel.y = hs / 2 + 2;
    this.bankLabel.eventMode = "none";
    this.bankLabelBg.eventMode = "none";
    this.bankBadge.addChild(this.bankLabelBg);
    this.bankBadge.addChild(this.bankLabel);

    this.lotteryBadge = new Container();
    this.lotteryBadge.visible = false;
    this.lotteryBadge.eventMode = "static";
    this.lotteryBadge.cursor = "pointer";
    this.lotteryBadge.on("pointerdown", () => {
      canvasCallbacks.openLottery?.();
    });
    this.lotteryBadge.on("pointerover", () => {
      if (this.lotterySprite) this.lotterySprite.alpha = 1;
    });
    this.lotteryBadge.on("pointerout", () => {
      if (this.lotterySprite) this.lotterySprite.alpha = 0.85;
    });
    this.lotteryHitArea.rect(-hs / 2, -hs / 2, hs, hs + 20);
    this.lotteryHitArea.fill({ color: 0x000000, alpha: 0.001 });
    this.lotteryHitArea.eventMode = "auto";
    this.lotteryBadge.addChild(this.lotteryHitArea);
    this.lotteryLabel = new Text({
      text: ROOM_SURFACE_WIDGETS.lottery.label,
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 11, fill: "#d7dda0", dropShadow: { color: 0x000000, alpha: 1, blur: 4, distance: 0 } },
    });
    this.lotteryLabel.anchor.set(0.5, 0);
    this.lotteryLabel.y = hs / 2 + 2;
    this.lotteryLabel.eventMode = "none";
    this.lotteryLabelBg.eventMode = "none";
    this.lotteryBadge.addChild(this.lotteryLabelBg);
    this.lotteryBadge.addChild(this.lotteryLabel);

    this.dungeonBadge = new Container();
    this.dungeonBadge.visible = false;
    this.dungeonBadge.eventMode = "static";
    this.dungeonBadge.cursor = "pointer";
    this.dungeonBadge.on("pointerdown", () => {
      canvasCallbacks.openDungeon?.();
    });
    this.dungeonBadge.on("pointerover", () => {
      if (this.dungeonSprite) this.dungeonSprite.alpha = 1;
    });
    this.dungeonBadge.on("pointerout", () => {
      if (this.dungeonSprite) this.dungeonSprite.alpha = 0.85;
    });
    this.dungeonHitArea.rect(-hs / 2, -hs / 2, hs, hs + 20);
    this.dungeonHitArea.fill({ color: 0x000000, alpha: 0.001 });
    this.dungeonHitArea.eventMode = "auto";
    this.dungeonBadge.addChild(this.dungeonHitArea);
    this.dungeonLabel = new Text({
      text: ROOM_SURFACE_WIDGETS.dungeon.label,
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 11, fill: "#a7b8ff", dropShadow: { color: 0x000000, alpha: 1, blur: 4, distance: 0 } },
    });
    this.dungeonLabel.anchor.set(0.5, 0);
    this.dungeonLabel.y = hs / 2 + 2;
    this.dungeonLabel.eventMode = "none";
    this.dungeonLabelBg.eventMode = "none";
    this.dungeonBadge.addChild(this.dungeonLabelBg);
    this.dungeonBadge.addChild(this.dungeonLabel);

    // Inn badge — floating icon when the current room is an inn
    this.innBadge = new Container();
    this.innBadge.visible = false;
    this.innBadge.eventMode = "static";
    this.innBadge.cursor = "pointer";
    this.innBadge.on("pointerdown", () => {
      canvasCallbacks.openInn?.();
    });
    this.innBadge.on("pointerover", () => {
      if (this.innSprite) this.innSprite.alpha = 1;
    });
    this.innBadge.on("pointerout", () => {
      if (this.innSprite) this.innSprite.alpha = 0.85;
    });
    this.innHitArea.rect(-hs / 2, -hs / 2, hs, hs + 20);
    this.innHitArea.fill({ color: 0x000000, alpha: 0.001 });
    this.innHitArea.eventMode = "auto";
    this.innBadge.addChild(this.innHitArea);
    this.innLabel = new Text({
      text: "Inn",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 11, fill: "#e3c98a", dropShadow: { color: 0x000000, alpha: 1, blur: 4, distance: 0 } },
    });
    this.innLabel.anchor.set(0.5, 0);
    this.innLabel.y = hs / 2 + 2;
    this.innLabel.eventMode = "none";
    this.innLabelBg.eventMode = "none";
    this.innBadge.addChild(this.innLabelBg);
    this.innBadge.addChild(this.innLabel);

    // Housing broker badge — floating kiosk icon when a housing broker is present
    this.housingBadge = new Container();
    this.housingBadge.visible = false;
    this.housingBadge.eventMode = "static";
    this.housingBadge.cursor = "pointer";
    this.housingBadge.on("pointerdown", () => {
      canvasCallbacks.openHousing?.();
    });
    this.housingBadge.on("pointerover", () => {
      if (this.housingSprite) this.housingSprite.alpha = 1;
    });
    this.housingBadge.on("pointerout", () => {
      if (this.housingSprite) this.housingSprite.alpha = 0.85;
    });
    this.housingHitArea.rect(-hs / 2, -hs / 2, hs, hs + 20);
    this.housingHitArea.fill({ color: 0x000000, alpha: 0.001 });
    this.housingHitArea.eventMode = "auto";
    this.housingBadge.addChild(this.housingHitArea);
    this.housingLabel = new Text({
      text: "Housing",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 11, fill: "#c8b8d8", dropShadow: { color: 0x000000, alpha: 1, blur: 4, distance: 0 } },
    });
    this.housingLabel.anchor.set(0.5, 0);
    this.housingLabel.y = hs / 2 + 2;
    this.housingLabel.eventMode = "none";
    this.housingLabelBg.eventMode = "none";
    this.housingBadge.addChild(this.housingLabelBg);
    this.housingBadge.addChild(this.housingLabel);

    this.duelBadge = new Container();
    this.duelBadge.visible = false;
    this.duelBadge.eventMode = "static";
    this.duelBadge.cursor = "pointer";
    this.duelBadge.on("pointerdown", () => {
      // Duel badge removed — dueling via player context menu
    });
    this.duelBadge.on("pointerover", () => {
      if (this.duelSprite) this.duelSprite.alpha = 1;
    });
    this.duelBadge.on("pointerout", () => {
      if (this.duelSprite) this.duelSprite.alpha = 0.85;
    });
    this.duelHitArea.rect(-hs / 2, -hs / 2, hs, hs + 20);
    this.duelHitArea.fill({ color: 0x000000, alpha: 0.001 });
    this.duelHitArea.eventMode = "auto";
    this.duelBadge.addChild(this.duelHitArea);
    this.duelLabel = new Text({
      text: ROOM_SURFACE_WIDGETS.duel.label,
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 11, fill: "#e6a3a3", dropShadow: { color: 0x000000, alpha: 1, blur: 4, distance: 0 } },
    });
    this.duelLabel.anchor.set(0.5, 0);
    this.duelLabel.y = hs / 2 + 2;
    this.duelLabel.eventMode = "none";
    this.duelLabelBg.eventMode = "none";
    this.duelBadge.addChild(this.duelLabelBg);
    this.duelBadge.addChild(this.duelLabel);

    // Puzzle badge — floating icon when a puzzle is present in the room
    this.puzzleBadge = new Container();
    this.puzzleBadge.visible = false;
    this.puzzleBadge.eventMode = "static";
    this.puzzleBadge.cursor = "pointer";
    this.puzzleBadge.on("pointerdown", () => {
      canvasCallbacks.openPuzzle?.();
    });
    this.puzzleBadge.on("pointerover", () => {
      if (this.puzzleSprite) this.puzzleSprite.alpha = 1;
    });
    this.puzzleBadge.on("pointerout", () => {
      if (this.puzzleSprite) this.puzzleSprite.alpha = 0.85;
    });
    this.puzzleHitArea.rect(-hs / 2, -hs / 2, hs, hs + 20);
    this.puzzleHitArea.fill({ color: 0x000000, alpha: 0.001 });
    this.puzzleHitArea.eventMode = "auto";
    this.puzzleBadge.addChild(this.puzzleHitArea);
    this.puzzleLabel = new Text({
      text: "Puzzle",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 11, fill: "#c4a8e8", dropShadow: { color: 0x000000, alpha: 1, blur: 4, distance: 0 } },
    });
    this.puzzleLabel.anchor.set(0.5, 0);
    this.puzzleLabel.y = hs / 2 + 2;
    this.puzzleLabel.eventMode = "none";
    this.puzzleLabelBg.eventMode = "none";
    this.puzzleBadge.addChild(this.puzzleLabelBg);
    this.puzzleBadge.addChild(this.puzzleLabel);

    // Door badge — quick access to door controls in the feature panel
    this.doorBadge = new Container();
    this.doorBadge.visible = false;
    this.doorBadge.eventMode = "static";
    this.doorBadge.cursor = "pointer";
    this.doorBadge.on("pointerdown", () => {
      canvasCallbacks.openFeatures?.("door");
    });
    this.doorBadge.on("pointerover", () => {
      if (this.doorSprite) this.doorSprite.alpha = 1;
    });
    this.doorBadge.on("pointerout", () => {
      if (this.doorSprite) this.doorSprite.alpha = 0.85;
    });
    this.doorHitArea.rect(-hs / 2, -hs / 2, hs, hs + 20);
    this.doorHitArea.fill({ color: 0x000000, alpha: 0.001 });
    this.doorHitArea.eventMode = "auto";
    this.doorBadge.addChild(this.doorHitArea);
    this.doorLabel = new Text({
      text: "Door",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 11, fill: "#9ec3e2", dropShadow: { color: 0x000000, alpha: 1, blur: 4, distance: 0 } },
    });
    this.doorLabel.anchor.set(0.5, 0);
    this.doorLabel.y = hs / 2 + 2;
    this.doorLabel.eventMode = "none";
    this.doorLabelBg.eventMode = "none";
    this.doorBadge.addChild(this.doorLabelBg);
    this.doorBadge.addChild(this.doorLabel);

    // Container badge — chest/container quick access
    this.containerBadge = new Container();
    this.containerBadge.visible = false;
    this.containerBadge.eventMode = "static";
    this.containerBadge.cursor = "pointer";
    this.containerBadge.on("pointerdown", () => {
      canvasCallbacks.openFeatures?.("container");
    });
    this.containerBadge.on("pointerover", () => {
      if (this.containerSprite) this.containerSprite.alpha = 1;
    });
    this.containerBadge.on("pointerout", () => {
      if (this.containerSprite) this.containerSprite.alpha = 0.85;
    });
    this.containerHitArea.rect(-hs / 2, -hs / 2, hs, hs + 20);
    this.containerHitArea.fill({ color: 0x000000, alpha: 0.001 });
    this.containerHitArea.eventMode = "auto";
    this.containerBadge.addChild(this.containerHitArea);
    this.containerLabel = new Text({
      text: "Container",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 11, fill: "#d3b26e", dropShadow: { color: 0x000000, alpha: 1, blur: 4, distance: 0 } },
    });
    this.containerLabel.anchor.set(0.5, 0);
    this.containerLabel.y = hs / 2 + 2;
    this.containerLabel.eventMode = "none";
    this.containerLabelBg.eventMode = "none";
    this.containerBadge.addChild(this.containerLabelBg);
    this.containerBadge.addChild(this.containerLabel);

    // Lever badge — room mechanism quick access
    this.leverBadge = new Container();
    this.leverBadge.visible = false;
    this.leverBadge.eventMode = "static";
    this.leverBadge.cursor = "pointer";
    this.leverBadge.on("pointerdown", () => {
      canvasCallbacks.openFeatures?.("lever");
    });
    this.leverBadge.on("pointerover", () => {
      if (this.leverSprite) this.leverSprite.alpha = 1;
    });
    this.leverBadge.on("pointerout", () => {
      if (this.leverSprite) this.leverSprite.alpha = 0.85;
    });
    this.leverHitArea.rect(-hs / 2, -hs / 2, hs, hs + 20);
    this.leverHitArea.fill({ color: 0x000000, alpha: 0.001 });
    this.leverHitArea.eventMode = "auto";
    this.leverBadge.addChild(this.leverHitArea);
    this.leverLabel = new Text({
      text: "Lever",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 11, fill: "#c596d2", dropShadow: { color: 0x000000, alpha: 1, blur: 4, distance: 0 } },
    });
    this.leverLabel.anchor.set(0.5, 0);
    this.leverLabel.y = hs / 2 + 2;
    this.leverLabel.eventMode = "none";
    this.leverLabelBg.eventMode = "none";
    this.leverBadge.addChild(this.leverLabelBg);
    this.leverBadge.addChild(this.leverLabel);

    // Recall button
    this.recallBtn = this.buildActionButton("Recall", 0xb9aed8, 0x2a2845, () => {
      canvasCallbacks.sendCommand?.("recall");
    });
    this.recallBtn.visible = false;

    // Depart button — only shown at the death sanctum when there is somewhere to return to
    this.departBtn = this.buildActionButton("Depart", 0xd8b9ae, 0x452a2a, () => {
      canvasCallbacks.sendCommand?.("depart");
    });
    this.departBtn.visible = false;

    this.container.addChildAt(this.skyRenderer.graphics, 0);
    this.container.addChild(this.ambientMotes.graphics);
    this.container.addChild(this.roleGraphics);
    this.container.addChild(this.statusEffects.container);
    this.container.addChild(this.playerLabelBg);
    this.container.addChild(this.playerLabel);
    this.container.addChild(this.minimap.container);
    this.container.addChild(this.shopBadge);
    this.container.addChild(this.auctionBadge);
    this.container.addChild(this.stylistBadge);
    this.container.addChild(this.stationBadge);
    this.container.addChild(this.trainerBadge);
    this.container.addChild(this.bankBadge!);
    this.container.addChild(this.lotteryBadge!);
    this.container.addChild(this.dungeonBadge!);
    this.container.addChild(this.housingBadge!);
    this.container.addChild(this.innBadge!);
    this.container.addChild(this.duelBadge!);
    this.container.addChild(this.puzzleBadge!);
    this.container.addChild(this.doorBadge!);
    this.container.addChild(this.containerBadge!);
    this.container.addChild(this.leverBadge!);
    this.container.addChild(this.recallBtn);
    this.container.addChild(this.departBtn);
    this.container.addChild(this.backdropHit);
    this.container.addChild(this.entityPopout.container);
    // Weather lives in the overlay so it stays visible during room-transition
    // fades (container.alpha → 0) and reliably renders above room art/sky.
    this.overlayContainer.addChild(this.weatherParticles.graphics);
    this.overlayContainer.addChild(this.roomTransition.graphics);
  }

  resize(width: number, height: number) {
    this.width = width;
    this.height = height;
    this.entityPopout.resize(width, height);
    this.ambientMotes.resize(width, height);
    this.roomTransition.resize(width, height);
    this.skyRenderer.resize(width, height);
    this.weatherParticles.resize(width, height);

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
      this.loadAuctionIcon();
      this.loadStylistIcon();
      this.loadStationIcon();
      this.loadTrainerIcon();
      this.loadBankIcon();
      this.loadLotteryIcon();
      this.loadDungeonIcon();
      this.loadHousingBrokerIcon();
      this.loadInnIcon();
      this.loadDuelIcon();
      this.loadPuzzleIcon();
      this.loadDoorIcon();
      this.loadContainerIcon();
      this.loadLeverIcon();
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

    // Apply zone environment theme when it changes (from Zone.Environment GMCP)
    const zoneEnv = state.zoneEnvironment;
    if (zoneEnv !== null && zoneEnv.zone !== this.lastZoneEnvZone) {
      this.lastZoneEnvZone = zoneEnv.zone;
      this.ambientMotes.setTheme(zoneEnv.moteColors);
      this.roomTransition.setTransitionColors(zoneEnv.transitionColors);
      this.skyRenderer.setTheme(zoneEnv.skyGradients);
    }

    // Update sky period from World.Time GMCP
    if (state.worldTime !== null) {
      this.skyRenderer.setPeriod(state.worldTime.period);
    }

    // Resolve weather particle hint: zone override > server particleHint
    if (state.worldWeather !== null) {
      const weatherId = state.worldWeather.weather;
      const hint = (zoneEnv?.weatherParticleOverrides[weatherId]) ?? state.worldWeather.particleHint ?? "";
      if (hint !== this.lastWeatherHint) {
        this.lastWeatherHint = hint;
        this.weatherParticles.setHint(hint);
      }
    }

    const roomChanged = room.id !== this.lastRoomId;
    if (roomChanged) {
      if (this.lastRoomId !== null) {
        this.roomTransition.start();
      }
      this.lastRoomId = room.id;
      // Dismiss popout on room change
      this.entityPopout.hide();
      this.backdropHit.visible = false;
    }

    // Update sky gradient and weather particles
    this.skyRenderer.update(deltaMs);
    this.weatherParticles.update(deltaMs);

    // Update ambient motes (zone-themed floating particles)
    this.ambientMotes.update(deltaMs);

    // Update minimap
    this.minimap.updateRoom(room.id, room.exits, room.title !== "-" ? room.title : "", room.image ?? null, room.mapX, room.mapY);
    this.minimap.tick(deltaMs);

    // Resolve room background: custom image, or terrain-based default from server assets
    const terrain = room.terrain ?? "outside";
    const effectiveImage = room.image ?? state.serverAssets[`default_bg_${terrain}`] ?? null;
    if (effectiveImage !== this.lastRoomImage) {
      this.lastRoomImage = effectiveImage;
      this.loadBackground(effectiveImage);
    }

    // Suppress weather particles for sheltered terrains
    const sheltered = terrain === "inside" || terrain === "underground" || terrain === "underwater";
    if (sheltered) {
      this.weatherParticles.setHint("");
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

    const pets = mobs.filter((m) => m.ownerName);
    const nonPetMobs = mobs.filter((m) => !m.ownerName);

    const mobsKey = nonPetMobs.map((m) => `${m.id}:${m.hp}`).join("|");
    if (mobsKey !== this.lastMobsKey) {
      this.lastMobsKey = mobsKey;
      this.rebuildMobs(nonPetMobs);
    }

    const petsKey = pets.map((p) => `${p.id}:${p.hp}`).join("|");
    if (petsKey !== this.lastPetsKey) {
      this.lastPetsKey = petsKey;
      this.rebuildPets(pets);
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

    const mobInfoKey = mobInfo.map((m) => `${m.id}:${m.questAvailable}:${m.questComplete}:${m.shopKeeper}:${m.dialogue}:${m.aggressive}:${m.combatant}`).join("|");
    if (mobInfoKey !== this.lastMobInfoKey) {
      this.lastMobInfoKey = mobInfoKey;
    }

    // Shop badge visibility
    const hasShop = state.shop !== null;
    if (hasShop !== this.shopVisible) {
      this.shopVisible = hasShop;
      this.shopBadge.visible = hasShop;
    }

    const hasAuction = !!state.room.auction;
    if (hasAuction !== this.auctionVisible) {
      this.auctionVisible = hasAuction;
      this.auctionBadge.visible = hasAuction;
    }

    const hasStylist = state.stylistState !== null;
    if (hasStylist !== this.stylistVisible) {
      this.stylistVisible = hasStylist;
      this.stylistBadge.visible = hasStylist;
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

    // Trainer badge visibility
    const hasTrainer = !!state.room.trainer;
    if (hasTrainer !== this.trainerVisible) {
      this.trainerVisible = hasTrainer;
      this.trainerBadge.visible = hasTrainer;
      if (hasTrainer) {
        this.trainerLabel.text = state.room.trainer!;
      }
    }

    // Bank badge visibility
    const hasBank = !!state.room.bank;
    if (hasBank !== this.bankVisible) {
      this.bankVisible = hasBank;
      if (this.bankBadge) this.bankBadge.visible = hasBank;
    }

    const hasLottery = !!state.room.tavern;
    if (hasLottery !== this.lotteryVisible) {
      this.lotteryVisible = hasLottery;
      if (this.lotteryBadge) this.lotteryBadge.visible = hasLottery;
    }

    const hasDungeon = !!state.room.dungeon;
    if (hasDungeon !== this.dungeonVisible) {
      this.dungeonVisible = hasDungeon;
      if (this.dungeonBadge) this.dungeonBadge.visible = hasDungeon;
    }

    const hasHousingBroker = !!state.room.housingBroker;
    if (hasHousingBroker !== this.housingVisible) {
      this.housingVisible = hasHousingBroker;
      if (this.housingBadge) this.housingBadge.visible = hasHousingBroker;
    }

    const hasInn = !!state.room.inn;
    if (hasInn !== this.innVisible) {
      this.innVisible = hasInn;
      if (this.innBadge) this.innBadge.visible = hasInn;
    }

    // Duel badge removed — dueling is accessed via player context menu
    if (this.duelVisible) {
      this.duelVisible = false;
      if (this.duelBadge) this.duelBadge.visible = false;
    }

    // Puzzle badge visibility — driven by Puzzle.List GMCP (state.puzzle non-null)
    const hasPuzzle = state.puzzle !== null;
    if (hasPuzzle !== this.puzzleVisible) {
      this.puzzleVisible = hasPuzzle;
      if (this.puzzleBadge) this.puzzleBadge.visible = hasPuzzle;
    }

    const doorCount = state.roomFeatures.filter((feature) => feature.type === "door").length;
    const hasDoors = doorCount > 0;
    if (hasDoors !== this.doorVisible || doorCount !== this.doorCount) {
      this.doorVisible = hasDoors;
      this.doorCount = doorCount;
      if (this.doorBadge) this.doorBadge.visible = hasDoors;
      if (this.doorLabel) this.doorLabel.text = featureCountLabel(doorCount, "Door", "Doors");
    }

    const containerCount = state.roomFeatures.filter((feature) => feature.type === "container").length;
    const hasContainers = containerCount > 0;
    if (hasContainers !== this.containerVisible || containerCount !== this.containerCount) {
      this.containerVisible = hasContainers;
      this.containerCount = containerCount;
      if (this.containerBadge) this.containerBadge.visible = hasContainers;
      if (this.containerLabel) this.containerLabel.text = featureCountLabel(containerCount, "Container", "Containers");
    }

    const leverCount = state.roomFeatures.filter((feature) => feature.type === "lever").length;
    const hasLevers = leverCount > 0;
    if (hasLevers !== this.leverVisible || leverCount !== this.leverCount) {
      this.leverVisible = hasLevers;
      this.leverCount = leverCount;
      if (this.leverBadge) this.leverBadge.visible = hasLevers;
      if (this.leverLabel) this.leverLabel.text = featureCountLabel(leverCount, "Lever", "Levers");
    }

    // Recall button visibility — show when logged in and not in combat
    const loggedIn = state.character.name !== "-";
    const showRecall = loggedIn && !state.vitals.inCombat;
    if (showRecall !== this.lastLoggedIn) {
      this.lastLoggedIn = showRecall;
      this.recallBtn.visible = showRecall;
    }

    // Depart button visibility — only at sanctum with a recorded death zone
    const canDepart = !!state.room.canDepart;
    if (canDepart !== this.lastCanDepart) {
      this.lastCanDepart = canDepart;
      this.departBtn.visible = canDepart;
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

    // Minimap in the top-right corner. Room title/description now live in a DOM
    // panel below the canvas, so the scene itself carries no text overlay.
    const mapDiam = w < 500 ? MINIMAP_MOBILE : MINIMAP_DESKTOP;
    this.minimap.setDiameter(mapDiam);
    this.minimap.layout(w - mapDiam - MINIMAP_MARGIN, MINIMAP_MARGIN);

    // In strip mode (text layout), hide entity sprites — they're just placeholders
    // and clutter the compact room header.
    const stripMode = h < 200;
    this.roleGraphics.visible = !stripMode;
    this.statusEffects.container.visible = !stripMode;
    if (this.playerSprite) this.playerSprite.visible = !stripMode;
    this.playerLabel.visible = !stripMode;
    this.playerLabelBg.visible = !stripMode;
    for (const { sprite, label, labelBg, hitArea } of this.mobSprites.values()) {
      sprite.visible = !stripMode;
      label.visible = !stripMode;
      labelBg.visible = !stripMode;
      hitArea.visible = !stripMode;
    }
    for (const { sprite, label, labelBg, hitArea } of this.playerSprites.values()) {
      sprite.visible = !stripMode;
      label.visible = !stripMode;
      labelBg.visible = !stripMode;
      hitArea.visible = !stripMode;
    }
    for (const { sprite, label, labelBg, hitArea } of this.itemSprites) {
      sprite.visible = !stripMode;
      label.visible = !stripMode;
      labelBg.visible = !stripMode;
      hitArea.visible = !stripMode;
    }
    this.shopBadge.visible = this.shopVisible && !stripMode;
    this.auctionBadge.visible = this.auctionVisible && !stripMode;
    this.stationBadge.visible = this.stationVisible && !stripMode;
    this.trainerBadge.visible = this.trainerVisible && !stripMode;
    if (this.bankBadge) this.bankBadge.visible = this.bankVisible && !stripMode;
    if (this.lotteryBadge) this.lotteryBadge.visible = this.lotteryVisible && !stripMode;
    if (this.dungeonBadge) this.dungeonBadge.visible = this.dungeonVisible && !stripMode;
    if (this.housingBadge) this.housingBadge.visible = this.housingVisible && !stripMode;
    if (this.innBadge) this.innBadge.visible = this.innVisible && !stripMode;
    if (this.duelBadge) this.duelBadge.visible = this.duelVisible && !stripMode;
    if (this.puzzleBadge) this.puzzleBadge.visible = this.puzzleVisible && !stripMode;
    if (this.doorBadge) this.doorBadge.visible = this.doorVisible && !stripMode;
    if (this.containerBadge) this.containerBadge.visible = this.containerVisible && !stripMode;
    if (this.leverBadge) this.leverBadge.visible = this.leverVisible && !stripMode;
    this.recallBtn.visible = this.recallBtn.visible && !stripMode;
    this.departBtn.visible = this.departBtn.visible && !stripMode;

    // Dynamic entity sizing
    const scale = Math.min(w / REF_WIDTH, h / REF_HEIGHT);
    const playerSize = clamp(PLAYER_SPRITE_SIZE * scale, MIN_SPRITE_SIZE, MAX_SPRITE_SIZE);

    // Order combat-eligible mobs first (left); quest-givers, dialog NPCs, and
    // props sit on the right. Missing mobInfo defaults to combat (true), which
    // matches the server default for legacy mobs.
    const mobInfoByRepId = new Map(gameStateRef.current.mobInfo.map((m) => [m.id, m]));
    const mobEntries = [...this.mobSprites.values()].sort((a, b) => {
      const ai = mobInfoByRepId.get(a.ids[0]);
      const bi = mobInfoByRepId.get(b.ids[0]);
      const aCombat = ai ? ai.combatant : true;
      const bCombat = bi ? bi.combatant : true;
      if (aCombat !== bCombat) return aCombat ? -1 : 1;
      return a.name.localeCompare(b.name);
    });
    const mobCount = mobEntries.length;
    const mobAreaLeft = w * 0.38;
    const mobAreaRight = w - 24;
    const mobAreaWidth = mobAreaRight - mobAreaLeft;
    // Per-mob base size: combatants use BASE_SPRITE_SIZE, non-combat NPCs
    // (props / quest givers / dialogue) render larger so they read as characters.
    const mobFitSize = mobCount > 0 ? (mobAreaWidth - 16) / mobCount - 16 : BASE_SPRITE_SIZE * scale;
    const mobSizeFor = (entry: (typeof mobEntries)[number]): number => {
      const info = mobInfoByRepId.get(entry.ids[0]);
      const isCombat = info ? info.combatant : true;
      const base = (isCombat ? BASE_SPRITE_SIZE : NONCOMBAT_SPRITE_SIZE) * scale;
      return clamp(Math.min(base, mobFitSize), MIN_SPRITE_SIZE, MAX_SPRITE_SIZE);
    };
    const maxMobSize = mobCount > 0 ? Math.max(...mobEntries.map(mobSizeFor)) : BASE_SPRITE_SIZE * scale;

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
    drawLabelPill(this.playerLabelBg, this.playerLabel);

    // Status effects above the player sprite
    this.statusEffects.update(gameStateRef.current.effects, playerX, playerY - playerSize / 2 - 32);

    // Layout mobs spread across the right portion, evenly spaced
    if (mobCount > 0) {
      const mobY = h * 0.68;
      const mobSpacing = mobCount === 1
        ? 0
        : Math.min(maxMobSize + 24, mobAreaWidth / mobCount);
      const totalMobWidth = (mobCount - 1) * mobSpacing;
      let mobX = mobAreaLeft + (mobAreaWidth - totalMobWidth) / 2;
      // Vertical stagger so adjacent mob labels don't collide. Cycles through
      // up to 3 rows for 3+ mobs so neighbours-by-2 also don't share a row.
      const mobStaggerStep = mobCount > 1 ? Math.min(32, maxMobSize * 0.26) : 0;
      const mobStaggerRows = Math.min(mobCount, 3);
      let mobIdx = 0;
      for (const entry of mobEntries) {
        const { sprite, label, labelBg, hitArea, name, count, ids } = entry;
        const thisMobSize = mobSizeFor(entry);
        const mobYOffset = (mobIdx % mobStaggerRows) * mobStaggerStep;
        const thisMobY = mobY + mobYOffset;
        sprite.x = mobX;
        sprite.y = thisMobY;
        sprite.width = thisMobSize;
        sprite.height = thisMobSize;
        label.x = mobX;
        label.y = thisMobY + thisMobSize / 2 + 6;

        // Color label text by mob role + prepend role icon. Append "(N)" when
        // duplicates are stacked into a single sprite.
        const info = mobInfoByRepId.get(ids[0]);
        const suffix = count > 1 ? ` (${count})` : "";
        if (info?.aggressive) {
          label.style.fill = "#d4888a";
          label.text = "\u2620 " + name + suffix;
        } else if (info?.shopKeeper) {
          label.style.fill = "#8caec9";
          label.text = "\uD83D\uDCB0 " + name + suffix;
        } else if (info?.questGiver) {
          label.style.fill = "#bea873";
          label.text = "\u2B50 " + name + suffix;
        } else {
          label.style.fill = MOB_LABEL_COLOR;
          label.text = name + suffix;
        }
        drawLabelPill(labelBg, label, info?.questGiver ? 0xbea873 : undefined);

        hitArea.clear();
        hitArea.rect(0, 0, thisMobSize, thisMobSize);
        hitArea.fill({ color: 0x000000, alpha: 0.001 });
        hitArea.x = mobX - thisMobSize / 2;
        hitArea.y = thisMobY - thisMobSize / 2;
        mobX += mobSpacing;
        mobIdx += 1;
      }
    }

    // Layout other players near the player sprite
    const otherPlayerEntries = [...this.playerSprites.values()];
    if (otherPlayerEntries.length > 0) {
      const opY = h * 0.55;
      let startX = playerX + playerSize / 2 + 20;
      for (const { sprite, label, labelBg, hitArea } of otherPlayerEntries) {
        sprite.x = startX;
        sprite.y = opY;
        sprite.width = otherSize;
        sprite.height = otherSize;
        label.x = startX;
        label.y = opY + otherSize / 2 + 6;
        drawLabelPill(labelBg, label);
        hitArea.clear();
        hitArea.rect(0, 0, otherSize, otherSize);
        hitArea.fill({ color: 0x000000, alpha: 0.001 });
        hitArea.x = startX - otherSize / 2;
        hitArea.y = opY - otherSize / 2;
        startX += otherSize + 20;
      }
    }

    // Layout pet sprites near the player (below and to the right)
    const petEntries = [...this.petSprites.values()];
    if (petEntries.length > 0) {
      const petY = playerY + playerSize * 0.1;
      const petSize = otherSize;
      let petStartX = playerX - petSize / 2 - 10;
      for (const { sprite, label, labelBg, hitArea } of petEntries) {
        sprite.x = petStartX;
        sprite.y = petY;
        sprite.width = petSize;
        sprite.height = petSize;
        label.x = petStartX;
        label.y = petY + petSize / 2 + 6;
        drawLabelPill(labelBg, label);
        hitArea.clear();
        hitArea.rect(0, 0, petSize, petSize);
        hitArea.fill({ color: 0x000000, alpha: 0.001 });
        hitArea.x = petStartX - petSize / 2;
        hitArea.y = petY - petSize / 2;
        petStartX -= petSize + 16;
      }
    }

    // Layout item sprites in a horizontal row, centered
    if (itemCount > 0) {
      const itemY = h * 0.40;
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
        drawLabelPill(labelBg, label);
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
      const nodeY = h * 0.55;
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
        drawLabelPill(labelBg, label);

        hitArea.clear();
        hitArea.rect(0, 0, itemSize, itemSize);
        hitArea.fill({ color: 0x000000, alpha: 0.001 });
        hitArea.x = nodeX - itemSize / 2;
        hitArea.y = nodeY - itemSize / 2;
        nodeX += nodeSpacing;
      }
    }

    // Room-feature badges — right side, stacked vertically below the minimap.
    const badgeX = w - 70;
    const badgeStartY = h * 0.35;
    // Count visible badges to compute adaptive spacing
    const visibleBadgeCount = [
      this.shopBadge.visible, this.auctionBadge.visible, this.stylistBadge.visible,
      this.stationBadge.visible, this.trainerBadge.visible,
      this.bankBadge?.visible,
      this.lotteryBadge?.visible, this.dungeonBadge?.visible,
      this.housingBadge?.visible,
      this.innBadge?.visible,
      this.duelBadge?.visible, this.puzzleBadge?.visible,
      this.doorBadge?.visible, this.containerBadge?.visible,
      this.leverBadge?.visible,
    ].filter(Boolean).length;
    const availableHeight = h * 0.58; // from 35% to ~93% of viewport
    // Minimum spacing must clear the full badge footprint (icon + label pill + gap)
    // so labels don't run into the next badge's icon.
    const minBadgeSpacing = SHOP_BADGE_SIZE + 30;
    const maxSpacing = Math.max(h * 0.15, minBadgeSpacing);
    const badgeSpacing = visibleBadgeCount > 1
      ? Math.max(minBadgeSpacing, Math.min(maxSpacing, availableHeight / visibleBadgeCount))
      : maxSpacing;
    let badgeSlot = 0;

    if (this.shopBadge.visible) {
      this.shopBadge.x = badgeX;
      this.shopBadge.y = badgeStartY + badgeSlot * badgeSpacing;
      drawLabelPill(this.shopLabelBg, this.shopLabel);
      badgeSlot++;
    }

    if (this.auctionBadge.visible) {
      this.auctionBadge.x = badgeX;
      this.auctionBadge.y = badgeStartY + badgeSlot * badgeSpacing;
      drawLabelPill(this.auctionLabelBg, this.auctionLabel);
      badgeSlot++;
    }

    if (this.stylistBadge.visible) {
      this.stylistBadge.x = badgeX;
      this.stylistBadge.y = badgeStartY + badgeSlot * badgeSpacing;
      drawLabelPill(this.stylistLabelBg, this.stylistLabel);
      badgeSlot++;
    }

    if (this.stationBadge.visible) {
      this.stationBadge.x = badgeX;
      this.stationBadge.y = badgeStartY + badgeSlot * badgeSpacing;
      drawLabelPill(this.stationLabelBg, this.stationLabel);
      badgeSlot++;
    }

    if (this.trainerBadge.visible) {
      this.trainerBadge.x = badgeX;
      this.trainerBadge.y = badgeStartY + badgeSlot * badgeSpacing;
      drawLabelPill(this.trainerLabelBg, this.trainerLabel);
      badgeSlot++;
    }

    if (this.bankBadge?.visible) {
      this.bankBadge.x = badgeX;
      this.bankBadge.y = badgeStartY + badgeSlot * badgeSpacing;
      drawLabelPill(this.bankLabelBg, this.bankLabel!);
      badgeSlot++;
    }

    if (this.lotteryBadge?.visible) {
      this.lotteryBadge.x = badgeX;
      this.lotteryBadge.y = badgeStartY + badgeSlot * badgeSpacing;
      drawLabelPill(this.lotteryLabelBg, this.lotteryLabel!);
      badgeSlot++;
    }

    if (this.dungeonBadge?.visible) {
      this.dungeonBadge.x = badgeX;
      this.dungeonBadge.y = badgeStartY + badgeSlot * badgeSpacing;
      drawLabelPill(this.dungeonLabelBg, this.dungeonLabel!);
      badgeSlot++;
    }

    if (this.housingBadge?.visible) {
      this.housingBadge.x = badgeX;
      this.housingBadge.y = badgeStartY + badgeSlot * badgeSpacing;
      drawLabelPill(this.housingLabelBg, this.housingLabel!);
      badgeSlot++;
    }

    if (this.innBadge?.visible) {
      this.innBadge.x = badgeX;
      this.innBadge.y = badgeStartY + badgeSlot * badgeSpacing;
      drawLabelPill(this.innLabelBg, this.innLabel!);
      badgeSlot++;
    }

    if (this.duelBadge?.visible) {
      this.duelBadge.x = badgeX;
      this.duelBadge.y = badgeStartY + badgeSlot * badgeSpacing;
      drawLabelPill(this.duelLabelBg, this.duelLabel!);
      badgeSlot++;
    }

    if (this.puzzleBadge?.visible) {
      this.puzzleBadge.x = badgeX;
      this.puzzleBadge.y = badgeStartY + badgeSlot * badgeSpacing;
      drawLabelPill(this.puzzleLabelBg, this.puzzleLabel!);
      badgeSlot++;
    }

    if (this.doorBadge?.visible) {
      this.doorBadge.x = badgeX;
      this.doorBadge.y = badgeStartY + badgeSlot * badgeSpacing;
      drawLabelPill(this.doorLabelBg, this.doorLabel!);
      badgeSlot++;
    }

    if (this.containerBadge?.visible) {
      this.containerBadge.x = badgeX;
      this.containerBadge.y = badgeStartY + badgeSlot * badgeSpacing;
      drawLabelPill(this.containerLabelBg, this.containerLabel!);
      badgeSlot++;
    }

    if (this.leverBadge?.visible) {
      this.leverBadge.x = badgeX;
      this.leverBadge.y = badgeStartY + badgeSlot * badgeSpacing;
      drawLabelPill(this.leverLabelBg, this.leverLabel!);
      badgeSlot++;
    }

    // Recall button — bottom-left
    if (this.recallBtn.visible) {
      this.recallBtn.x = 16 + 40;
      this.recallBtn.y = h - 24;
    }

    // Depart button — sits beside Recall at the bottom-left
    if (this.departBtn.visible) {
      // Offset to the right of Recall (button width 80 + 8px gap), or take Recall's slot if hidden
      this.departBtn.x = this.recallBtn.visible ? 16 + 40 + 88 : 16 + 40;
      this.departBtn.y = h - 24;
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
      // mobSprites is keyed by mob name (groups of duplicates) — look up info
      // via the representative id, then key icons off that id too.
      for (const entry of this.mobSprites.values()) {
        const repId = entry.ids[0];
        const info = mobInfo.find((m) => m.id === repId);
        if (!info) continue;
        const { sprite } = entry;
        const mobSize = sprite.height;
        drawRoleIcons(this.roleGraphics, sprite.x, sprite.y, info, mobSize);
        if (info.dialogue) {
          activeDialogueMobs.add(repId);
          this.ensureDialogueIcon(repId, sprite.x, sprite.y, mobSize);
        }
        if (info.aggressive) {
          activeAggroMobs.add(repId);
          this.ensureAggroIcon(repId, sprite.x, sprite.y, mobSize);
        }
        if (info.questComplete) {
          activeQuestComplete.add(repId);
          this.ensureQuestIcon(repId, sprite.x, sprite.y, "complete", mobSize);
        } else if (info.questAvailable) {
          activeQuestAvail.add(repId);
          this.ensureQuestIcon(repId, sprite.x, sprite.y, "available", mobSize);
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

  private rebuildMobs(mobs: Array<{ id: string; templateKey: string; name: string; description?: string; hp: number; maxHp: number; image?: string | null; video?: string | null; category?: string }>) {
    for (const { sprite, label, labelBg, hitArea } of this.mobSprites.values()) {
      this.container.removeChild(sprite);
      this.container.removeChild(labelBg);
      this.container.removeChild(label);
      this.container.removeChild(hitArea);
      sprite.destroy();
      labelBg.destroy();
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

    // Group mobs by templateKey — every spawn of the same template renders
    // as a single sprite with a "(N)" count suffix. Two mobs that share a
    // display name but come from different templates have distinct keys and
    // remain separate sprites. Mobs without a templateKey (legacy/dynamic
    // spawns) fall back to their instance id so each stays on its own.
    const groups = new Map<string, { representative: typeof mobs[number]; count: number; ids: string[] }>();
    for (const mob of mobs) {
      const groupKey = mob.templateKey || mob.id;
      const existing = groups.get(groupKey);
      if (existing) {
        existing.count += 1;
        existing.ids.push(mob.id);
      } else {
        groups.set(groupKey, { representative: mob, count: 1, ids: [mob.id] });
      }
    }

    for (const [groupKey, { representative: mob, count, ids }] of groups.entries()) {
      const sprite = new Sprite(Texture.WHITE);
      sprite.width = BASE_SPRITE_SIZE;
      sprite.height = BASE_SPRITE_SIZE;
      sprite.anchor.set(0.5);
      sprite.tint = 0xf0c674;

      const mobImage = mob.image ?? gameStateRef.current.serverAssets[`default_mob_${mob.category ?? "humanoid"}`] ?? null;
      if (mobImage) {
        this.loadSpriteTexture(sprite, mobImage);
      }

      const label = new Text({
        text: mob.name,
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: MOB_LABEL_FONT_SIZE, fill: MOB_LABEL_COLOR, dropShadow: { color: 0x000000, alpha: 0.4, blur: 2, distance: 1 } },
      });
      label.anchor.set(0.5, 0);

      const labelBg = new Graphics();
      labelBg.eventMode = "none";

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
      this.container.addChild(labelBg);
      this.container.addChild(label);
      this.container.addChild(hitArea);
      this.mobSprites.set(groupKey, { sprite, label, labelBg, hitArea, name: mob.name, count, ids });
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

      const itemImage = item.image ?? gameStateRef.current.serverAssets["default_item_generic"] ?? null;
      if (itemImage) {
        this.loadSpriteTexture(sprite, itemImage);
      }

      const label = new Text({
        text: item.name,
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: ITEM_LABEL_FONT_SIZE, fill: ITEM_LABEL_COLOR, dropShadow: { color: 0x000000, alpha: 0.4, blur: 2, distance: 1 } },
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

      const nodeImage = node.image ?? gameStateRef.current.serverAssets["default_item_generic"] ?? null;
      if (nodeImage) {
        this.loadSpriteTexture(sprite, nodeImage);
      }

      const label = new Text({
        text: node.name,
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: ITEM_LABEL_FONT_SIZE, fill: "#8da97b", dropShadow: { color: 0x000000, alpha: 0.4, blur: 2, distance: 1 } },
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

  private rebuildPlayers(players: Array<{ name: string; level: number; sprite?: string | null }>) {
    for (const { sprite, label, labelBg, hitArea } of this.playerSprites.values()) {
      this.container.removeChild(sprite);
      this.container.removeChild(labelBg);
      this.container.removeChild(label);
      this.container.removeChild(hitArea);
      sprite.destroy();
      labelBg.destroy();
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

      if (player.sprite) {
        this.loadSpriteTexture(sprite, player.sprite);
      }

      const label = new Text({
        text: player.name,
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 13, fill: OTHER_PLAYER_LABEL_COLOR, dropShadow: { color: 0x000000, alpha: 0.4, blur: 2, distance: 1 } },
      });
      label.anchor.set(0.5, 0);

      const labelBg = new Graphics();
      labelBg.eventMode = "none";

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
      this.container.addChild(labelBg);
      this.container.addChild(label);
      this.container.addChild(hitArea);
      this.playerSprites.set(player.name, { sprite, label, labelBg, hitArea });
    }
  }

  private rebuildPets(pets: Array<{ id: string; name: string; image?: string | null; category?: string; hp: number; maxHp: number }>) {
    for (const { sprite, label, labelBg, hitArea } of this.petSprites.values()) {
      this.container.removeChild(sprite);
      this.container.removeChild(labelBg);
      this.container.removeChild(label);
      this.container.removeChild(hitArea);
      sprite.destroy();
      labelBg.destroy();
      label.destroy();
      hitArea.destroy();
    }
    this.petSprites.clear();

    const petSize = BASE_SPRITE_SIZE * 0.75;
    for (const pet of pets) {
      const sprite = new Sprite(Texture.WHITE);
      sprite.width = petSize;
      sprite.height = petSize;
      sprite.anchor.set(0.5);
      sprite.tint = 0xb294bb;

      const petImage = pet.image ?? gameStateRef.current.serverAssets[`default_mob_${pet.category ?? "humanoid"}`] ?? null;
      if (petImage) {
        this.loadSpriteTexture(sprite, petImage);
      }

      const label = new Text({
        text: `♥ ${pet.name}`,
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 13, fill: PET_LABEL_COLOR, dropShadow: { color: 0x000000, alpha: 0.4, blur: 2, distance: 1 } },
      });
      label.anchor.set(0.5, 0);

      const labelBg = new Graphics();
      labelBg.eventMode = "none";

      const hitArea = new Graphics();
      hitArea.rect(0, 0, petSize, petSize);
      hitArea.fill({ color: 0x000000, alpha: 0.001 });
      hitArea.eventMode = "static";
      hitArea.cursor = "pointer";

      const petData = pet;
      hitArea.on("pointerdown", () => {
        this.entityPopout.showMob(petData.name, undefined, petData.image, undefined, petData.hp, petData.maxHp, null, false);
        this.showPopout();
      });

      this.container.addChild(sprite);
      this.container.addChild(labelBg);
      this.container.addChild(label);
      this.container.addChild(hitArea);
      this.petSprites.set(pet.id, { sprite, label, labelBg, hitArea });
    }
  }

  private async loadBackground(imagePath: string | null) {
    // Token guards against out-of-order async resolution: a slow earlier load
    // must not overwrite a newer one and leave an orphan sprite in the container.
    const token = ++this.bgLoadToken;

    if (this.background) {
      this.container.removeChild(this.background);
      this.background.destroy();
      this.background = null;
    }

    if (!imagePath) return;

    try {
      const texture = await Assets.load(imagePath);
      if (token !== this.bgLoadToken) return;
      const sprite = new Sprite(texture);
      sprite.width = this.width;
      sprite.height = this.height;
      sprite.alpha = 0.6;
      // Keep sky at the back, then room art, then atmospheric effects and UI.
      this.container.addChildAt(sprite, 1);
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

  /** Clicking your own avatar opens the Character panel. */
  private bindPlayerInteractivity(sprite: Sprite) {
    sprite.eventMode = "static";
    sprite.cursor = "pointer";
    sprite.on("pointerdown", () => canvasCallbacks.openCharacter?.());
  }

  private async loadPlayerSprite(spritePath: string | null) {
    if (this.playerSprite) {
      this.container.removeChild(this.playerSprite);
      this.playerSprite.destroy();
      this.playerSprite = null;
    }

    const resolvedPath = spritePath ?? gameStateRef.current.serverAssets["default_player"] ?? null;
    if (!resolvedPath) {
      const sprite = new Sprite(Texture.WHITE);
      sprite.width = BASE_SPRITE_SIZE;
      sprite.height = BASE_SPRITE_SIZE;
      sprite.anchor.set(0.5);
      sprite.tint = 0x81a2be;
      this.container.addChild(sprite);
      this.playerSprite = sprite;
      this.bindPlayerInteractivity(sprite);
      return;
    }

    try {
      const texture = await Assets.load(resolvedPath);
      const sprite = new Sprite(texture);
      sprite.width = BASE_SPRITE_SIZE;
      sprite.height = BASE_SPRITE_SIZE;
      sprite.anchor.set(0.5);
      this.container.addChild(sprite);
      this.playerSprite = sprite;
      this.bindPlayerInteractivity(sprite);
    } catch {
      const sprite = new Sprite(Texture.WHITE);
      sprite.width = BASE_SPRITE_SIZE;
      sprite.height = BASE_SPRITE_SIZE;
      sprite.anchor.set(0.5);
      sprite.tint = 0x81a2be;
      this.container.addChild(sprite);
      this.playerSprite = sprite;
      this.bindPlayerInteractivity(sprite);
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

  private async loadStylistIcon() {
    try {
      const texture = await Assets.load(assetUrl("stylist_mirror", "stylist_mirror.png"));
      const sprite = new Sprite(texture);
      sprite.width = SHOP_BADGE_SIZE;
      sprite.height = SHOP_BADGE_SIZE;
      sprite.anchor.set(0.5);
      sprite.alpha = 0.85;
      sprite.eventMode = "none";
      this.stylistSprite = sprite;
      this.stylistBadge.addChild(sprite);
    } catch {
      // Fallback: text-only label still works
    }
  }

  private async loadAuctionIcon() {
    try {
      const texture = await Assets.load(assetUrl(ROOM_SURFACE_WIDGETS.auction.assetKey, ROOM_SURFACE_WIDGETS.auction.fallbackFilename));
      const sprite = new Sprite(texture);
      sprite.width = SHOP_BADGE_SIZE;
      sprite.height = SHOP_BADGE_SIZE;
      sprite.anchor.set(0.5);
      sprite.alpha = 0.85;
      sprite.eventMode = "none";
      this.auctionSprite = sprite;
      this.auctionBadge.addChild(sprite);
    } catch {
      // Fallback: text-only label still works
    }
  }

  private async loadStationIcon() {
    try {
      const texture = await Assets.load(assetUrl("crafting_station", "crafting_station.png"));
      const sprite = new Sprite(texture);
      sprite.width = SHOP_BADGE_SIZE;
      sprite.height = SHOP_BADGE_SIZE;
      sprite.anchor.set(0.5);
      sprite.alpha = 0.85;
      sprite.eventMode = "none";
      this.stationSprite = sprite;
      this.stationBadge.addChild(sprite);
    } catch {
      // Fallback: text-only label still works
    }
  }

  private async loadTrainerIcon() {
    try {
      const texture = await Assets.load(assetUrl("trainer_icon", "trainer_icon.png"));
      const sprite = new Sprite(texture);
      sprite.width = SHOP_BADGE_SIZE;
      sprite.height = SHOP_BADGE_SIZE;
      sprite.anchor.set(0.5);
      sprite.alpha = 0.85;
      sprite.eventMode = "none";
      this.trainerSprite = sprite;
      this.trainerBadge.addChild(sprite);
    } catch {
      // Fallback: text-only label still works
    }
  }

  private async loadBankIcon() {
    try {
      const texture = await Assets.load(assetUrl("bank_vault", "bank_vault.png"));
      const sprite = new Sprite(texture);
      sprite.width = SHOP_BADGE_SIZE;
      sprite.height = SHOP_BADGE_SIZE;
      sprite.anchor.set(0.5);
      sprite.alpha = 0.85;
      sprite.eventMode = "none";
      this.bankSprite = sprite;
      this.bankBadge?.addChild(sprite);
    } catch {
      // Fallback: text-only label still works
    }
  }

  private async loadLotteryIcon() {
    try {
      const texture = await Assets.load(assetUrl(ROOM_SURFACE_WIDGETS.lottery.assetKey, ROOM_SURFACE_WIDGETS.lottery.fallbackFilename));
      const sprite = new Sprite(texture);
      sprite.width = SHOP_BADGE_SIZE;
      sprite.height = SHOP_BADGE_SIZE;
      sprite.anchor.set(0.5);
      sprite.alpha = 0.85;
      sprite.eventMode = "none";
      this.lotterySprite = sprite;
      this.lotteryBadge?.addChild(sprite);
    } catch {
      // Fallback: text-only label still works
    }
  }

  private async loadDungeonIcon() {
    try {
      const texture = await Assets.load(assetUrl(ROOM_SURFACE_WIDGETS.dungeon.assetKey, ROOM_SURFACE_WIDGETS.dungeon.fallbackFilename));
      const sprite = new Sprite(texture);
      sprite.width = SHOP_BADGE_SIZE;
      sprite.height = SHOP_BADGE_SIZE;
      sprite.anchor.set(0.5);
      sprite.alpha = 0.85;
      sprite.eventMode = "none";
      this.dungeonSprite = sprite;
      this.dungeonBadge?.addChild(sprite);
    } catch {
      // Fallback: text-only label still works
    }
  }

  private async loadHousingBrokerIcon() {
    try {
      const texture = await Assets.load(assetUrl("housing_broker", "housing_broker.png"));
      const sprite = new Sprite(texture);
      sprite.width = SHOP_BADGE_SIZE;
      sprite.height = SHOP_BADGE_SIZE;
      sprite.anchor.set(0.5);
      sprite.alpha = 0.85;
      sprite.eventMode = "none";
      this.housingSprite = sprite;
      this.housingBadge?.addChild(sprite);
    } catch {
      // Fallback: text-only label still works
    }
  }

  private async loadInnIcon() {
    try {
      const texture = await Assets.load(assetUrl("inn_widget", "inn_widget.png"));
      const sprite = new Sprite(texture);
      sprite.width = SHOP_BADGE_SIZE;
      sprite.height = SHOP_BADGE_SIZE;
      sprite.anchor.set(0.5);
      sprite.alpha = 0.85;
      sprite.eventMode = "none";
      this.innSprite = sprite;
      this.innBadge?.addChild(sprite);
    } catch {
      // Fallback: text-only label still works
    }
  }

  private async loadDuelIcon() {
    try {
      const texture = await Assets.load(assetUrl(ROOM_SURFACE_WIDGETS.duel.assetKey, ROOM_SURFACE_WIDGETS.duel.fallbackFilename));
      const sprite = new Sprite(texture);
      sprite.width = SHOP_BADGE_SIZE;
      sprite.height = SHOP_BADGE_SIZE;
      sprite.anchor.set(0.5);
      sprite.alpha = 0.85;
      sprite.eventMode = "none";
      this.duelSprite = sprite;
      this.duelBadge?.addChild(sprite);
    } catch {
      // Fallback: text-only label still works
    }
  }

  private async loadPuzzleIcon() {
    try {
      const texture = await Assets.load(assetUrl("puzzle_kiosk", "puzzle_kiosk.png"));
      const sprite = new Sprite(texture);
      sprite.width = SHOP_BADGE_SIZE;
      sprite.height = SHOP_BADGE_SIZE;
      sprite.anchor.set(0.5);
      sprite.alpha = 0.85;
      sprite.eventMode = "none";
      this.puzzleSprite = sprite;
      this.puzzleBadge?.addChild(sprite);
    } catch {
      // Fallback: text-only label still works
    }
  }

  private async loadDoorIcon() {
    try {
      const texture = await Assets.load(assetUrl("feature_door", "feature_door.png"));
      const sprite = new Sprite(texture);
      sprite.width = SHOP_BADGE_SIZE;
      sprite.height = SHOP_BADGE_SIZE;
      sprite.anchor.set(0.5);
      sprite.alpha = 0.85;
      sprite.eventMode = "none";
      this.doorSprite = sprite;
      this.doorBadge?.addChild(sprite);
    } catch {
      // Fallback: text-only label still works
    }
  }

  private async loadContainerIcon() {
    try {
      const texture = await Assets.load(assetUrl("feature_container", "feature_container.png"));
      const sprite = new Sprite(texture);
      sprite.width = SHOP_BADGE_SIZE;
      sprite.height = SHOP_BADGE_SIZE;
      sprite.anchor.set(0.5);
      sprite.alpha = 0.85;
      sprite.eventMode = "none";
      this.containerSprite = sprite;
      this.containerBadge?.addChild(sprite);
    } catch {
      // Fallback: text-only label still works
    }
  }

  private async loadLeverIcon() {
    try {
      const texture = await Assets.load(assetUrl("feature_lever", "feature_lever.png"));
      const sprite = new Sprite(texture);
      sprite.width = SHOP_BADGE_SIZE;
      sprite.height = SHOP_BADGE_SIZE;
      sprite.anchor.set(0.5);
      sprite.alpha = 0.85;
      sprite.eventMode = "none";
      this.leverSprite = sprite;
      this.leverBadge?.addChild(sprite);
    } catch {
      // Fallback: text-only label still works
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

  private ensureDialogueIcon(mobId: string, cx: number, cy: number, mobSize: number) {
    if (!this.dialogueTexture) return;
    let icon = this.dialogueIcons.get(mobId);
    if (!icon) {
      icon = new Sprite(this.dialogueTexture);
      icon.anchor.set(0.5);
      icon.eventMode = "none";
      this.dialogueIcons.set(mobId, icon);
      this.container.addChild(icon);
    }
    const size = statusIconSize(mobSize);
    icon.width = size;
    icon.height = size;
    icon.x = cx - mobSize * 0.28;
    icon.y = cy - mobSize / 2 - size / 2;
  }

  private ensureAggroIcon(mobId: string, cx: number, cy: number, mobSize: number) {
    if (!this.aggroTexture) return;
    let icon = this.aggroIcons.get(mobId);
    if (!icon) {
      icon = new Sprite(this.aggroTexture);
      icon.anchor.set(0.5);
      icon.eventMode = "none";
      this.aggroIcons.set(mobId, icon);
      this.container.addChild(icon);
    }
    const size = statusIconSize(mobSize) * 0.85;
    icon.width = size;
    icon.height = size;
    icon.x = cx + mobSize * 0.28;
    icon.y = cy - mobSize / 2 - size / 2;
  }

  private async loadQuestTextures() {
    try {
      this.questAvailableTexture = await Assets.load(assetUrl("quest_available_indicator", "quest_available_indicator.png"));
    } catch { /* no sprite */ }
    try {
      this.questCompleteTexture = await Assets.load(assetUrl("quest_complete_indicator", "quest_complete_indicator.png"));
    } catch { /* no sprite */ }
  }

  private ensureQuestIcon(mobId: string, cx: number, cy: number, type: "available" | "complete", mobSize: number) {
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
      icon.anchor.set(0.5);
      icon.eventMode = "none";
      map.set(mobId, icon);
      this.container.addChild(icon);
    }
    const size = statusIconSize(mobSize);
    icon.width = size;
    icon.height = size;
    icon.x = cx;
    icon.y = cy - mobSize / 2 - size / 2 - 4;
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
