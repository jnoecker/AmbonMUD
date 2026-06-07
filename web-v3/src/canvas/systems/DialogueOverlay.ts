import { Container, Graphics, Text } from "pixi.js";
import { gameStateRef, canvasCallbacks } from "../GameStateBridge";
import type { DialogueState } from "../../types";

const BOX_PADDING = 16;
const CHOICE_HEIGHT = 28;
const BOX_BG = 0x22293c;
const BOX_BORDER = 0x6f7da1;
const NPC_NAME_COLOR = "#f0c674";
const TEXT_COLOR = "#d8dcef";
const CHOICE_COLOR = "#b9aed8";
const CHOICE_HOVER_COLOR = "#d8dcef";
const ENDING_COLOR = "#6f7da1";

export class DialogueOverlay {
  readonly container = new Container();

  // Full-canvas hit area that dismisses the dialogue when clicked. Only active
  // once the conversation has reached a state with no further choices, so
  // mid-conversation canvas interactions (e.g. choice buttons) are preserved.
  private fullBg = new Graphics();
  private bg = new Graphics();
  private npcNameText: Text;
  private bodyText: Text;
  private choiceTexts: Text[] = [];
  private endingText: Text | null = null;

  private dismissHint: Text | null = null;
  private lastDialogueKey: string | null = null;
  private isDismissable = false;
  private width = 0;
  private height = 0;

  constructor() {
    this.npcNameText = new Text({
      text: "",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 14, fill: NPC_NAME_COLOR, fontWeight: "bold" },
    });

    this.bodyText = new Text({
      text: "",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 12, fill: TEXT_COLOR, wordWrap: true, wordWrapWidth: 300 },
    });

    // Full-canvas dismiss backdrop — only enabled when dismissable.
    this.fullBg.eventMode = "none";
    this.fullBg.visible = false;
    this.fullBg.on("pointerdown", () => {
      if (this.isDismissable) {
        canvasCallbacks.dismissDialogue?.();
      }
    });

    this.bg.eventMode = "static";
    this.bg.cursor = "default";
    this.bg.on("pointerdown", (event) => {
      if (this.isDismissable) {
        canvasCallbacks.dismissDialogue?.();
      }
      // Stop propagation so the full-canvas backdrop underneath doesn't also
      // fire (it would double-dismiss, which is harmless, but explicit is
      // cleaner). Also prevents clicking the box from bubbling to world hits.
      event.stopPropagation();
    });
    // fullBg must render behind the dialogue box and its choices so those
    // still receive pointer events.
    this.container.addChild(this.fullBg);
    this.container.addChild(this.bg);
    this.container.addChild(this.npcNameText);
    this.container.addChild(this.bodyText);
    this.container.visible = false;
  }

  resize(width: number, height: number) {
    this.width = width;
    this.height = height;
    this.redrawFullBg();
  }

  private redrawFullBg() {
    this.fullBg.clear();
    this.fullBg.rect(0, 0, this.width, this.height);
    // Near-transparent fill so it still registers hits without darkening the
    // scene behind the dialogue.
    this.fullBg.fill({ color: 0x000000, alpha: 0.001 });
  }

  update() {
    const state = gameStateRef.current;
    const dialogue = state.dialogue;

    // The monster-manual panel renders dialogue in its own styled window;
    // showing the canvas overlay too would double the dialogue on screen.
    if (!dialogue || state.monsterPanelOpen) {
      this.container.visible = false;
      this.fullBg.visible = false;
      this.fullBg.eventMode = "none";
      this.lastDialogueKey = null;
      return;
    }

    this.container.visible = true;

    const key = `${dialogue.mobName}:${dialogue.text}:${dialogue.choices.map((c) => c.text).join("|")}`;
    if (key === this.lastDialogueKey) return;
    this.lastDialogueKey = key;

    this.rebuild(dialogue);
  }

  private rebuild(dialogue: DialogueState) {
    // Clear old choices
    for (const choice of this.choiceTexts) {
      this.container.removeChild(choice);
      choice.destroy();
    }
    this.choiceTexts = [];

    if (this.endingText) {
      this.container.removeChild(this.endingText);
      this.endingText.destroy();
      this.endingText = null;
    }

    if (this.dismissHint) {
      this.container.removeChild(this.dismissHint);
      this.dismissHint.destroy();
      this.dismissHint = null;
    }

    // Box dimensions
    const boxWidth = Math.min(440, this.width - 40);
    const innerWidth = boxWidth - BOX_PADDING * 2;
    this.bodyText.style.wordWrapWidth = innerWidth;

    this.npcNameText.text = dialogue.mobName;
    this.bodyText.text = dialogue.text;

    // Calculate content height
    let contentHeight = BOX_PADDING;
    contentHeight += this.npcNameText.height + 8 + this.bodyText.height + 12;

    if (dialogue.choices.length > 0) {
      for (const choice of dialogue.choices) {
        const choiceText = new Text({
          text: `${choice.index}. ${choice.text}`,
          style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 12, fill: CHOICE_COLOR },
        });
        choiceText.eventMode = "static";
        choiceText.cursor = "pointer";

        const idx = choice.index;
        choiceText.on("pointerover", () => { choiceText.style.fill = CHOICE_HOVER_COLOR; });
        choiceText.on("pointerout", () => { choiceText.style.fill = CHOICE_COLOR; });
        choiceText.on("pointerdown", (event) => {
          // Stop propagation so the always-on dismiss backdrop doesn't fire and
          // close the dialogue when the user is actually picking a choice.
          event.stopPropagation();
          canvasCallbacks.sendCommand?.(`${idx}`);
        });

        this.choiceTexts.push(choiceText);
        this.container.addChild(choiceText);
        contentHeight += CHOICE_HEIGHT;
      }
    } else {
      this.endingText = new Text({
        text: "The conversation has ended.",
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 11, fill: ENDING_COLOR, fontStyle: "italic" },
      });
      this.container.addChild(this.endingText);
      contentHeight += 20;
    }

    // The overlay is always dismissable: clicking outside the choices ends the
    // conversation rather than forcing the user to click through every node.
    // Choice texts call stopPropagation, so picking a choice doesn't double as
    // a dismiss.
    this.isDismissable = true;
    this.bg.cursor = "pointer";
    this.redrawFullBg();
    this.fullBg.visible = true;
    this.fullBg.eventMode = "static";
    this.fullBg.cursor = "pointer";

    this.dismissHint = new Text({
      text: "Click anywhere to close",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 10, fill: ENDING_COLOR },
    });
    this.dismissHint.anchor.set(0.5, 0);
    this.container.addChild(this.dismissHint);
    contentHeight += 16;

    contentHeight += BOX_PADDING;

    // Position the box at dead center
    const boxX = (this.width - boxWidth) / 2;
    const boxY = Math.max(20, (this.height - contentHeight) / 2);

    this.bg.clear();
    this.bg.roundRect(boxX, boxY, boxWidth, contentHeight, 6);
    this.bg.fill({ color: BOX_BG, alpha: 0.95 });
    this.bg.roundRect(boxX, boxY, boxWidth, contentHeight, 6);
    this.bg.stroke({ color: BOX_BORDER, alpha: 0.6, width: 1 });

    let y = boxY + BOX_PADDING;
    this.npcNameText.x = boxX + BOX_PADDING;
    this.npcNameText.y = y;
    y += this.npcNameText.height + 8;

    this.bodyText.x = boxX + BOX_PADDING;
    this.bodyText.y = y;
    y += this.bodyText.height + 12;

    for (const choiceText of this.choiceTexts) {
      choiceText.x = boxX + BOX_PADDING + 8;
      choiceText.y = y;
      y += CHOICE_HEIGHT;
    }

    if (this.endingText) {
      this.endingText.x = boxX + BOX_PADDING;
      this.endingText.y = y;
      y += 20;
    }

    // Position dismiss hint centered at bottom of box
    if (this.dismissHint) {
      this.dismissHint.x = boxX + boxWidth / 2;
      this.dismissHint.y = y + 2;
    }
  }

  destroy() {
    this.container.destroy({ children: true });
  }
}
