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

  private bg = new Graphics();
  private npcNameText: Text;
  private bodyText: Text;
  private choiceTexts: Text[] = [];
  private endingText: Text | null = null;

  private lastDialogueKey: string | null = null;
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

    this.container.addChild(this.bg);
    this.container.addChild(this.npcNameText);
    this.container.addChild(this.bodyText);
    this.container.visible = false;
  }

  resize(width: number, height: number) {
    this.width = width;
    this.height = height;
  }

  update() {
    const state = gameStateRef.current;
    const dialogue = state.dialogue;

    if (!dialogue) {
      this.container.visible = false;
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

    // Box dimensions
    const boxWidth = Math.min(400, this.width - 40);
    this.bodyText.style.wordWrapWidth = boxWidth - BOX_PADDING * 2;

    this.npcNameText.text = dialogue.mobName;
    this.bodyText.text = dialogue.text;

    // Calculate content height
    let contentHeight = BOX_PADDING + this.npcNameText.height + 8 + this.bodyText.height + 12;

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
        choiceText.on("pointerdown", () => {
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

    contentHeight += BOX_PADDING;

    // Position the box at bottom-center
    const boxX = (this.width - boxWidth) / 2;
    const boxY = this.height - contentHeight - 20;

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
    }
  }

  destroy() {
    this.container.destroy({ children: true });
  }
}
