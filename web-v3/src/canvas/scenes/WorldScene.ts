import { Container, Graphics, Sprite, Text, Texture, Assets } from "pixi.js";
import { gameStateRef } from "../GameStateBridge";

const EXIT_ARROW_COLOR = 0xb9aed8;
const EXIT_LABEL_COLOR = "#b9aed8";
const TITLE_COLOR = "#d8dcef";
const PLAYER_LABEL_COLOR = "#d8dcef";
const MOB_LABEL_COLOR = "#f0c674";
const ITEM_LABEL_COLOR = "#8abeb7";
const PLAYER_LABEL_FONT_SIZE = 13;
const MOB_LABEL_FONT_SIZE = 12;
const ARROW_SIZE = 18;
const SPRITE_SIZE = 64;

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

export class WorldScene {
  readonly container = new Container();

  private background: Sprite | null = null;
  private titleText: Text;
  private descText: Text;
  private exitGraphics = new Graphics();
  private exitLabels: Text[] = [];
  private playerSprite: Sprite | null = null;
  private playerLabel: Text;
  private mobSprites: Map<string, { sprite: Sprite; label: Text }> = new Map();
  private itemLabels: Text[] = [];

  private lastRoomId: string | null = null;
  private lastRoomImage: string | null | undefined = undefined;
  private lastPlayerSpritePath: string | null = null;
  private lastExitsKey = "";
  private lastMobsKey = "";
  private lastItemsKey = "";
  private width = 0;
  private height = 0;

  constructor() {
    this.titleText = new Text({
      text: "",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 18, fill: TITLE_COLOR, fontWeight: "bold" },
    });
    this.titleText.anchor.set(0.5, 0);

    this.descText = new Text({
      text: "",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 12, fill: "#9ea3bf", wordWrap: true, wordWrapWidth: 400 },
    });
    this.descText.anchor.set(0.5, 0);
    this.descText.alpha = 0.8;

    this.playerLabel = new Text({
      text: "",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: PLAYER_LABEL_FONT_SIZE, fill: PLAYER_LABEL_COLOR },
    });
    this.playerLabel.anchor.set(0.5, 0);

    this.container.addChild(this.exitGraphics);
    this.container.addChild(this.titleText);
    this.container.addChild(this.descText);
    this.container.addChild(this.playerLabel);
  }

  resize(width: number, height: number) {
    this.width = width;
    this.height = height;
    this.descText.style.wordWrapWidth = Math.min(400, width - 40);
    this.layoutAll();
  }

  update() {
    const state = gameStateRef.current;
    const { room, character, mobs, roomItems } = state;

    const roomChanged = room.id !== this.lastRoomId;
    if (roomChanged) {
      this.lastRoomId = room.id;
      this.titleText.text = room.title !== "-" ? room.title : "";
      this.descText.text = room.description || "";
    }

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

    this.titleText.x = w / 2;
    this.titleText.y = 16;

    this.descText.x = w / 2;
    this.descText.y = 44;

    // Player at center-bottom
    const playerY = h * 0.65;
    if (this.playerSprite) {
      this.playerSprite.x = w / 2;
      this.playerSprite.y = playerY;
    }
    this.playerLabel.x = w / 2;
    this.playerLabel.y = playerY + SPRITE_SIZE / 2 + 4;

    // Layout mobs in a row above the player
    const mobEntries = [...this.mobSprites.values()];
    if (mobEntries.length > 0) {
      const mobY = h * 0.38;
      const totalWidth = mobEntries.length * (SPRITE_SIZE + 20) - 20;
      let startX = (w - totalWidth) / 2 + SPRITE_SIZE / 2;
      for (const { sprite, label } of mobEntries) {
        sprite.x = startX;
        sprite.y = mobY;
        label.x = startX;
        label.y = mobY + SPRITE_SIZE / 2 + 4;
        startX += SPRITE_SIZE + 20;
      }
    }

    // Layout item labels below player
    if (this.itemLabels.length > 0) {
      let itemY = playerY + SPRITE_SIZE / 2 + 24;
      for (const label of this.itemLabels) {
        label.x = w / 2;
        label.y = itemY;
        itemY += 18;
      }
    }

    // Exits
    this.exitGraphics.clear();
    const exits = gameStateRef.current.room.exits;
    let labelIdx = 0;
    for (const dir of Object.keys(exits)) {
      const layout = DIRECTION_LAYOUTS[dir.toLowerCase()];
      if (!layout && labelIdx < this.exitLabels.length) {
        // Unknown direction: place below items
        const label = this.exitLabels[labelIdx];
        label.x = w / 2;
        label.y = h - 20 - (Object.keys(exits).length - labelIdx) * 18;
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
        label.y = cy + (dir.toLowerCase() === "north" || dir.toLowerCase() === "up" ? -22 : 22);
        labelIdx++;
      }
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
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 11, fill: EXIT_LABEL_COLOR },
      });
      label.anchor.set(0.5);
      label.alpha = 0.7;
      this.exitLabels.push(label);
      this.container.addChild(label);
    }
  }

  private rebuildMobs(mobs: Array<{ id: string; name: string; image?: string | null }>) {
    for (const { sprite, label } of this.mobSprites.values()) {
      this.container.removeChild(sprite);
      this.container.removeChild(label);
      sprite.destroy();
      label.destroy();
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
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: MOB_LABEL_FONT_SIZE, fill: MOB_LABEL_COLOR },
      });
      label.anchor.set(0.5, 0);

      this.container.addChild(sprite);
      this.container.addChild(label);
      this.mobSprites.set(mob.id, { sprite, label });
    }
  }

  private rebuildItems(items: Array<{ id: string; name: string }>) {
    for (const label of this.itemLabels) {
      this.container.removeChild(label);
      label.destroy();
    }
    this.itemLabels = [];

    for (const item of items) {
      const label = new Text({
        text: `[${item.name}]`,
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 11, fill: ITEM_LABEL_COLOR },
      });
      label.anchor.set(0.5, 0);
      label.alpha = 0.8;
      this.itemLabels.push(label);
      this.container.addChild(label);
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
      sprite.alpha = 0.35;
      this.container.addChildAt(sprite, 0);
      this.background = sprite;
    } catch {
      // Image not available; continue without background
    }
  }

  private async loadPlayerSprite(spritePath: string | null) {
    if (this.playerSprite) {
      this.container.removeChild(this.playerSprite);
      this.playerSprite.destroy();
      this.playerSprite = null;
    }

    if (!spritePath) {
      // Placeholder colored rectangle
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
      // Fallback to placeholder
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

  destroy() {
    this.container.destroy({ children: true });
  }
}
