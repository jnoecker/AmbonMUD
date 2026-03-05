import { Container, Graphics, Text, Sprite, Texture, Assets } from "pixi.js";
import { canvasCallbacks } from "../GameStateBridge";

const POPOUT_WIDTH = 200;
const POPOUT_PADDING = 12;
const SPRITE_PREVIEW_SIZE = 80;
const BUTTON_HEIGHT = 28;
const BUTTON_GAP = 6;
const BUTTON_RADIUS = 6;
const BG_COLOR = 0x1e2340;
const BG_ALPHA = 0.95;
const BORDER_COLOR = 0x4a5280;
const TITLE_COLOR = "#d8dcef";
const BUTTON_BG = 0x2a3460;
const BUTTON_HOVER_BG = 0x3a4570;
const BUTTON_TEXT_COLOR = "#d8dcef";

interface PopoutAction {
  label: string;
  command: string;
  color: number;
}

export class EntityPopout {
  readonly container = new Container();

  private bg = new Graphics();
  private previewSprite: Sprite | null = null;
  private titleText: Text;
  private subtitleText: Text;
  private buttons: Array<{ bg: Graphics; label: Text }> = [];
  private closeBtn: Graphics;
  private visible = false;
  private width = 0;
  private height = 0;

  constructor() {
    this.container.visible = false;
    this.container.eventMode = "static";

    this.titleText = new Text({
      text: "",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 14, fill: TITLE_COLOR, fontWeight: "bold" },
    });
    this.titleText.anchor.set(0.5, 0);

    this.subtitleText = new Text({
      text: "",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 11, fill: "#8890b0" },
    });
    this.subtitleText.anchor.set(0.5, 0);

    this.closeBtn = new Graphics();

    this.container.addChild(this.bg);
    this.container.addChild(this.titleText);
    this.container.addChild(this.subtitleText);
    this.container.addChild(this.closeBtn);
  }

  resize(width: number, height: number) {
    this.width = width;
    this.height = height;
  }

  showMob(name: string, image: string | null | undefined, hp: number, maxHp: number, info: { questGiver?: boolean; shopKeeper?: boolean; dialogue?: boolean } | null) {
    const actions: PopoutAction[] = [
      { label: "Look", command: `look ${name}`, color: 0x64b5f6 },
      { label: "Attack", command: `kill ${name}`, color: 0xef5350 },
    ];
    if (info?.dialogue) actions.push({ label: "Talk", command: `talk ${name}`, color: 0xb9aed8 });
    if (info?.shopKeeper) actions.push({ label: "Shop", command: `list`, color: 0x81a2be });

    const subtitle = maxHp > 0 ? `HP: ${hp}/${maxHp}` : "";
    this.show(name, subtitle, image ?? null, 0xf0c674, actions);
  }

  showPlayer(name: string, level: number) {
    const actions: PopoutAction[] = [
      { label: "Look", command: `look ${name}`, color: 0x64b5f6 },
      { label: "Group Invite", command: `group invite ${name}`, color: 0x81a2be },
    ];
    this.show(name, `Level ${level}`, null, 0x81a2be, actions);
  }

  showItem(name: string, image: string | null | undefined) {
    const actions: PopoutAction[] = [
      { label: "Get", command: `get ${name}`, color: 0x8abeb7 },
      { label: "Look", command: `look ${name}`, color: 0x64b5f6 },
    ];
    this.show(name, "Item", image ?? null, 0x8abeb7, actions);
  }

  hide() {
    this.visible = false;
    this.container.visible = false;
  }

  get isVisible() {
    return this.visible;
  }

  private show(title: string, subtitle: string, image: string | null, tint: number, actions: PopoutAction[]) {
    // Clear old buttons
    for (const { bg, label } of this.buttons) {
      this.container.removeChild(bg);
      this.container.removeChild(label);
      bg.destroy();
      label.destroy();
    }
    this.buttons = [];

    if (this.previewSprite) {
      this.container.removeChild(this.previewSprite);
      this.previewSprite.destroy();
      this.previewSprite = null;
    }

    // Create preview sprite
    const preview = new Sprite(Texture.WHITE);
    preview.width = SPRITE_PREVIEW_SIZE;
    preview.height = SPRITE_PREVIEW_SIZE;
    preview.anchor.set(0.5, 0);
    preview.tint = tint;
    this.container.addChild(preview);
    this.previewSprite = preview;

    if (image) {
      Assets.load(image).then((tex) => {
        preview.texture = tex;
        preview.tint = 0xffffff;
      }).catch(() => { /* keep placeholder */ });
    }

    this.titleText.text = title;
    this.subtitleText.text = subtitle;

    // Calculate total height
    const contentTop = POPOUT_PADDING;
    const spriteBottom = contentTop + SPRITE_PREVIEW_SIZE;
    const titleBottom = spriteBottom + 8 + 18;
    const subtitleBottom = subtitle ? titleBottom + 16 : titleBottom;
    const buttonsTop = subtitleBottom + 8;
    const totalButtonHeight = actions.length * (BUTTON_HEIGHT + BUTTON_GAP) - BUTTON_GAP;
    const popoutHeight = buttonsTop + totalButtonHeight + POPOUT_PADDING;

    // Position centered in canvas
    const popX = (this.width - POPOUT_WIDTH) / 2;
    const popY = Math.max(20, (this.height - popoutHeight) / 2);

    // Draw background
    this.bg.clear();
    this.bg.roundRect(popX, popY, POPOUT_WIDTH, popoutHeight, 10);
    this.bg.fill({ color: BG_COLOR, alpha: BG_ALPHA });
    this.bg.roundRect(popX, popY, POPOUT_WIDTH, popoutHeight, 10);
    this.bg.stroke({ color: BORDER_COLOR, width: 1.5 });

    const cx = popX + POPOUT_WIDTH / 2;

    // Position preview sprite
    preview.x = cx;
    preview.y = popY + contentTop;

    // Position title
    this.titleText.x = cx;
    this.titleText.y = popY + spriteBottom + 8;

    // Position subtitle
    this.subtitleText.x = cx;
    this.subtitleText.y = popY + titleBottom;

    // Create action buttons
    let btnY = popY + buttonsTop;
    for (const action of actions) {
      const btnBg = new Graphics();
      btnBg.roundRect(popX + POPOUT_PADDING, btnY, POPOUT_WIDTH - POPOUT_PADDING * 2, BUTTON_HEIGHT, BUTTON_RADIUS);
      btnBg.fill(BUTTON_BG);
      btnBg.roundRect(popX + POPOUT_PADDING, btnY, POPOUT_WIDTH - POPOUT_PADDING * 2, BUTTON_HEIGHT, BUTTON_RADIUS);
      btnBg.stroke({ color: action.color, width: 1, alpha: 0.5 });
      btnBg.eventMode = "static";
      btnBg.cursor = "pointer";

      const cmd = action.command;
      btnBg.on("pointerdown", () => {
        canvasCallbacks.sendCommand?.(cmd);
        this.hide();
      });
      btnBg.on("pointerover", () => {
        btnBg.clear();
        btnBg.roundRect(popX + POPOUT_PADDING, btnY, POPOUT_WIDTH - POPOUT_PADDING * 2, BUTTON_HEIGHT, BUTTON_RADIUS);
        btnBg.fill(BUTTON_HOVER_BG);
        btnBg.roundRect(popX + POPOUT_PADDING, btnY, POPOUT_WIDTH - POPOUT_PADDING * 2, BUTTON_HEIGHT, BUTTON_RADIUS);
        btnBg.stroke({ color: action.color, width: 1.5, alpha: 0.8 });
      });
      btnBg.on("pointerout", () => {
        btnBg.clear();
        btnBg.roundRect(popX + POPOUT_PADDING, btnY, POPOUT_WIDTH - POPOUT_PADDING * 2, BUTTON_HEIGHT, BUTTON_RADIUS);
        btnBg.fill(BUTTON_BG);
        btnBg.roundRect(popX + POPOUT_PADDING, btnY, POPOUT_WIDTH - POPOUT_PADDING * 2, BUTTON_HEIGHT, BUTTON_RADIUS);
        btnBg.stroke({ color: action.color, width: 1, alpha: 0.5 });
      });

      const btnLabel = new Text({
        text: action.label,
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 12, fill: BUTTON_TEXT_COLOR },
      });
      btnLabel.anchor.set(0.5);
      btnLabel.x = cx;
      btnLabel.y = btnY + BUTTON_HEIGHT / 2;

      this.container.addChild(btnBg);
      this.container.addChild(btnLabel);
      this.buttons.push({ bg: btnBg, label: btnLabel });

      btnY += BUTTON_HEIGHT + BUTTON_GAP;
    }

    // Close button (X in top-right)
    this.closeBtn.clear();
    const closeX = popX + POPOUT_WIDTH - 24;
    const closeY = popY + 8;
    this.closeBtn.circle(closeX, closeY, 10);
    this.closeBtn.fill({ color: 0x3a3a5a, alpha: 0.8 });
    // Draw X
    this.closeBtn.moveTo(closeX - 4, closeY - 4);
    this.closeBtn.lineTo(closeX + 4, closeY + 4);
    this.closeBtn.stroke({ color: 0xd8dcef, width: 1.5 });
    this.closeBtn.moveTo(closeX + 4, closeY - 4);
    this.closeBtn.lineTo(closeX - 4, closeY + 4);
    this.closeBtn.stroke({ color: 0xd8dcef, width: 1.5 });
    this.closeBtn.eventMode = "static";
    this.closeBtn.cursor = "pointer";
    this.closeBtn.removeAllListeners();
    this.closeBtn.on("pointerdown", () => this.hide());

    this.visible = true;
    this.container.visible = true;
  }

  destroy() {
    this.container.destroy({ children: true });
  }
}
