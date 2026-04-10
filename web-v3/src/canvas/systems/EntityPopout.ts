import { Container, Graphics, Text, Sprite, Texture, Assets, Rectangle } from "pixi.js";
import { canvasCallbacks } from "../GameStateBridge";

const POPOUT_WIDTH = 240;
const POPOUT_PADDING = 12;
const SPRITE_PREVIEW_SIZE = 80;
const BUTTON_HEIGHT = 44;
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
  private buttons: Container[] = [];
  private closeBtn: Graphics;
  private visible = false;
  private width = 0;
  private height = 0;

  constructor() {
    this.container.visible = false;
    this.container.eventMode = "static";

    this.titleText = new Text({
      text: "",
      style: {
        fontFamily: "JetBrains Mono, Cascadia Mono, monospace",
        fontSize: 14,
        fill: TITLE_COLOR,
        fontWeight: "bold",
        wordWrap: true,
        wordWrapWidth: POPOUT_WIDTH - POPOUT_PADDING * 2,
      },
    });
    this.titleText.anchor.set(0.5, 0);

    this.subtitleText = new Text({
      text: "",
      style: {
        fontFamily: "JetBrains Mono, Cascadia Mono, monospace",
        fontSize: 11,
        fill: "#8890b0",
        wordWrap: true,
        wordWrapWidth: POPOUT_WIDTH - POPOUT_PADDING * 2,
      },
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

  showMob(name: string, description: string | null | undefined, image: string | null | undefined, video: string | null | undefined, hp: number, maxHp: number, info: { questGiver?: boolean; questAvailable?: boolean; questComplete?: boolean; shopKeeper?: boolean; dialogue?: boolean; aggressive?: boolean } | null, isStaff: boolean = false) {
    const actions: PopoutAction[] = [];
    const isNpc = info?.shopKeeper || info?.questGiver || info?.dialogue;
    const isAggressive = info?.aggressive === true;

    // Primary interaction first based on mob role
    if (info?.questComplete) {
      actions.push({ label: "\u2605 Turn In Quest", command: `talk ${name}`, color: 0xf0c674 });
    } else if (info?.questAvailable) {
      actions.push({ label: "\u2605 Accept Quest", command: `talk ${name}`, color: 0x5a8a6a });
    } else if (info?.questGiver) {
      // Quest giver with no active quest — still show talk with quest styling
      actions.push({ label: "\u2605 Quests", command: `talk ${name}`, color: 0x8a9a6a });
    }
    if (info?.shopKeeper) actions.push({ label: "Browse Shop", command: `__shop__:list`, color: 0x81a2be });
    if (info?.dialogue && !info?.questComplete && !info?.questAvailable && !info?.questGiver) {
      actions.push({ label: "Talk", command: `talk ${name}`, color: 0xb9aed8 });
    }

    // Attack — prominent for aggressive/combat mobs, available for all
    if (isAggressive || !isNpc) {
      actions.push({ label: "Attack", command: `kill ${name}`, color: 0xd4888a });
    }

    // Look is always available
    actions.push({ label: "Look", command: `look ${name}`, color: 0x64b5f6 });

    // Attack as secondary option for friendly NPCs (still reachable but deprioritized)
    if (isNpc && !isAggressive) {
      actions.push({ label: "Attack", command: `kill ${name}`, color: 0x6a5050 });
    }

    if (video) actions.push({ label: "\u25B6 Cinematic", command: `__video__:${video}`, color: 0xce93d8 });

    // Staff-only actions
    if (isStaff) {
      actions.push({ label: "\u2728 Possess", command: `possess ${name}`, color: 0xb294bb });
    }

    // Build role tag and subtitle with context from MobInfo
    const roleTag = isAggressive ? " \u2620" : info?.shopKeeper ? " \uD83D\uDCB0" : info?.questGiver ? " \u2605" : "";
    const roleParts: string[] = [];
    if (info?.shopKeeper) roleParts.push("Shopkeeper");
    if (info?.questGiver) roleParts.push("Quest Giver");
    if (info?.aggressive) roleParts.push("Hostile");
    const roleLabel = roleParts.join(" \u00B7 ");
    const subtitle = description || roleLabel || (maxHp > 0 ? `HP: ${hp}/${maxHp}` : "");
    const tint = isAggressive ? 0xd4888a : info?.shopKeeper ? 0x81a2be : info?.questGiver ? 0xf0c674 : 0xf0c674;
    this.show(name + roleTag, subtitle, image ?? null, tint, actions);
  }

  showPlayer(name: string, level: number) {
    const actions: PopoutAction[] = [
      { label: "Look", command: `look ${name}`, color: 0x64b5f6 },
      { label: "\u2694 Duel", command: `duel ${name}`, color: 0xd4888a },
      { label: "Tell", command: `__prefill__:tell ${name} `, color: 0xb5bd68 },
      { label: "Whisper", command: `__prefill__:whisper ${name} `, color: 0xa8a8a8 },
      { label: "Give", command: `__prefill__:give `, color: 0xf0c674 },
      { label: "Group Invite", command: `group invite ${name}`, color: 0x81a2be },
      { label: "Friend Add", command: `friend add ${name}`, color: 0xb294bb },
    ];
    this.show(name, `Level ${level}`, null, 0x81a2be, actions);
  }

  showItem(name: string, description: string | null | undefined, image: string | null | undefined, video: string | null | undefined) {
    const actions: PopoutAction[] = [
      { label: "Get", command: `get ${name}`, color: 0x8abeb7 },
      { label: "Look", command: `look ${name}`, color: 0x64b5f6 },
    ];
    if (video) actions.push({ label: "▶ Cinematic", command: `__video__:${video}`, color: 0xce93d8 });
    this.show(name, description || "Item", image ?? null, 0x8abeb7, actions);
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
    for (const btn of this.buttons) {
      this.container.removeChild(btn);
      btn.destroy({ children: true });
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

    // Calculate total height (title and subtitle may wrap to multiple lines)
    const contentTop = POPOUT_PADDING;
    const spriteBottom = contentTop + SPRITE_PREVIEW_SIZE;
    const titleBottom = spriteBottom + 8 + this.titleText.height;
    const subtitleHeight = subtitle ? this.subtitleText.height : 0;
    const subtitleBottom = subtitle ? titleBottom + subtitleHeight + 4 : titleBottom;
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

    // Create action buttons — each button is a Container with a fixed hitArea.
    // Graphics + Text live inside it so pointer events are handled on the
    // Container and never lost to child overlap or Graphics-bounds shifts.
    const btnW = POPOUT_WIDTH - POPOUT_PADDING * 2;
    let btnY = popY + buttonsTop;
    for (const action of actions) {
      const btnContainer = new Container();
      btnContainer.x = popX + POPOUT_PADDING;
      btnContainer.y = btnY;
      btnContainer.eventMode = "static";
      btnContainer.cursor = "pointer";
      btnContainer.hitArea = new Rectangle(0, 0, btnW, BUTTON_HEIGHT);

      const btnBg = new Graphics();
      const drawNormal = () => {
        btnBg.clear();
        btnBg.roundRect(0, 0, btnW, BUTTON_HEIGHT, BUTTON_RADIUS);
        btnBg.fill(BUTTON_BG);
        btnBg.roundRect(0, 0, btnW, BUTTON_HEIGHT, BUTTON_RADIUS);
        btnBg.stroke({ color: action.color, width: 1, alpha: 0.5 });
      };
      const drawHover = () => {
        btnBg.clear();
        btnBg.roundRect(0, 0, btnW, BUTTON_HEIGHT, BUTTON_RADIUS);
        btnBg.fill(BUTTON_HOVER_BG);
        btnBg.roundRect(0, 0, btnW, BUTTON_HEIGHT, BUTTON_RADIUS);
        btnBg.stroke({ color: action.color, width: 1.5, alpha: 0.8 });
      };
      drawNormal();

      const btnLabel = new Text({
        text: action.label,
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 12, fill: BUTTON_TEXT_COLOR },
      });
      btnLabel.anchor.set(0.5);
      btnLabel.x = btnW / 2;
      btnLabel.y = BUTTON_HEIGHT / 2;
      btnLabel.eventMode = "none";

      btnContainer.addChild(btnBg);
      btnContainer.addChild(btnLabel);

      const cmd = action.command;
      btnContainer.on("pointerdown", () => {
        if (cmd.startsWith("__video__:")) {
          canvasCallbacks.openVideo?.(cmd.slice("__video__:".length));
        } else if (cmd.startsWith("__prefill__:")) {
          canvasCallbacks.prefillCommand?.(cmd.slice("__prefill__:".length));
        } else if (cmd.startsWith("__shop__:")) {
          canvasCallbacks.sendCommand?.(cmd.slice("__shop__:".length));
          canvasCallbacks.openShop?.();
        } else {
          canvasCallbacks.sendCommand?.(cmd);
        }
        this.hide();
      });
      btnContainer.on("pointerover", drawHover);
      btnContainer.on("pointerout", drawNormal);

      this.container.addChild(btnContainer);
      this.buttons.push(btnContainer);

      btnY += BUTTON_HEIGHT + BUTTON_GAP;
    }

    // Close button (X in top-right)
    this.closeBtn.clear();
    const closeX = popX + POPOUT_WIDTH - 24;
    const closeY = popY + 8;
    this.closeBtn.circle(closeX, closeY, 16);
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
