import { Container, Graphics, Text } from "pixi.js";
import { gameStateRef, canvasCallbacks } from "../GameStateBridge";
import type { DialogueState, QuestAvailable } from "../../types";

const BOX_PADDING = 16;
const CHOICE_HEIGHT = 28;
const BOX_BG = 0x22293c;
const BOX_BORDER = 0x6f7da1;
const NPC_NAME_COLOR = "#f0c674";
const TEXT_COLOR = "#d8dcef";
const CHOICE_COLOR = "#b9aed8";
const CHOICE_HOVER_COLOR = "#d8dcef";
const ENDING_COLOR = "#6f7da1";

// Quest card colors
const QUEST_CARD_BG = 0x1a2235;
const QUEST_CARD_BORDER = 0x5a8a6a;
const QUEST_TITLE_COLOR = "#8abeb7";
const QUEST_DESC_COLOR = "#a0a8c0";
const QUEST_OBJ_COLOR = "#9098b0";
const QUEST_REWARD_COLOR = "#f0c674";
const QUEST_ACCEPT_BG = 0x2a5a3a;
const QUEST_ACCEPT_HOVER_BG = 0x3a7a4a;
const QUEST_ACCEPT_TEXT_COLOR = "#d8dcef";

const QUEST_CARD_PADDING = 10;
const QUEST_CARD_GAP = 10;
const QUEST_SEPARATOR_GAP = 12;

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
  private questElements: Container[] = [];

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
    const hasQuestOffers = state.questsAvailable.length > 0;

    if (!dialogue && !hasQuestOffers) {
      this.container.visible = false;
      this.fullBg.visible = false;
      this.fullBg.eventMode = "none";
      this.lastDialogueKey = null;
      return;
    }

    this.container.visible = true;

    const questIds = state.questsAvailable.map((q) => q.id).join(",");
    const dialogueKey = dialogue
      ? `${dialogue.mobName}:${dialogue.text}:${dialogue.choices.map((c) => c.text).join("|")}`
      : "no-dialogue";
    const key = `${dialogueKey}:q=${questIds}`;
    if (key === this.lastDialogueKey) return;
    this.lastDialogueKey = key;

    this.rebuild(dialogue, state.questsAvailable);
  }

  private rebuild(dialogue: DialogueState | null, questsAvailable: QuestAvailable[]) {
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

    // Clear old quest elements
    for (const el of this.questElements) {
      this.container.removeChild(el);
      el.destroy({ children: true });
    }
    this.questElements = [];

    // Box dimensions
    const boxWidth = Math.min(440, this.width - 40);
    const innerWidth = boxWidth - BOX_PADDING * 2;
    this.bodyText.style.wordWrapWidth = innerWidth;

    this.npcNameText.text = dialogue?.mobName ?? "";
    this.bodyText.text = dialogue?.text ?? "";

    // Calculate content height
    let contentHeight = BOX_PADDING;
    if (dialogue) {
      contentHeight += this.npcNameText.height + 8 + this.bodyText.height + 12;
    }

    if (dialogue && dialogue.choices.length > 0) {
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
    } else if (dialogue) {
      // Show ending text only when dialogue existed but had no choices
      this.endingText = new Text({
        text: questsAvailable.length > 0 ? "" : "The conversation has ended.",
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 11, fill: ENDING_COLOR, fontStyle: "italic" },
      });
      this.container.addChild(this.endingText);
      if (!questsAvailable.length) contentHeight += 20;
    }

    // Build quest offer cards
    const questCardMeasurements: { container: Container; height: number }[] = [];
    if (questsAvailable.length > 0) {
      contentHeight += QUEST_SEPARATOR_GAP;

      for (const quest of questsAvailable) {
        const { container: questCard, height: cardHeight } = this.buildQuestCard(quest, innerWidth);
        questCardMeasurements.push({ container: questCard, height: cardHeight });
        this.questElements.push(questCard);
        this.container.addChild(questCard);
        contentHeight += cardHeight + QUEST_CARD_GAP;
      }
      // Remove the trailing gap
      contentHeight -= QUEST_CARD_GAP;
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
    if (dialogue) {
      this.npcNameText.x = boxX + BOX_PADDING;
      this.npcNameText.y = y;
      y += this.npcNameText.height + 8;

      this.bodyText.x = boxX + BOX_PADDING;
      this.bodyText.y = y;
      y += this.bodyText.height + 12;
    }

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

    // Position quest cards
    if (questCardMeasurements.length > 0) {
      y += QUEST_SEPARATOR_GAP;
      for (const { container: questCard } of questCardMeasurements) {
        questCard.x = boxX + BOX_PADDING;
        questCard.y = y;
        y += questCard.height + QUEST_CARD_GAP;
      }
    }

    // Position dismiss hint centered at bottom of box
    if (this.dismissHint) {
      this.dismissHint.x = boxX + boxWidth / 2;
      this.dismissHint.y = y + 2;
    }
  }

  private buildQuestCard(quest: QuestAvailable, maxWidth: number): { container: Container; height: number } {
    const card = new Container();
    card.eventMode = "static";
    const cardInnerWidth = maxWidth;

    const titleText = new Text({
      text: `\u2726 ${quest.name}`,
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 12, fill: QUEST_TITLE_COLOR, fontWeight: "bold" },
    });
    titleText.x = QUEST_CARD_PADDING;
    titleText.y = QUEST_CARD_PADDING;

    const descText = new Text({
      text: quest.description,
      style: {
        fontFamily: "JetBrains Mono, Cascadia Mono, monospace",
        fontSize: 10,
        fill: QUEST_DESC_COLOR,
        wordWrap: true,
        wordWrapWidth: cardInnerWidth - QUEST_CARD_PADDING * 2,
      },
    });
    descText.x = QUEST_CARD_PADDING;
    descText.y = titleText.y + titleText.height + 4;

    let yPos = descText.y + descText.height + 6;

    // Objectives
    const objTexts: Text[] = [];
    for (const obj of quest.objectives) {
      const objText = new Text({
        text: `  \u25CB ${obj.description} (0/${obj.count})`,
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 10, fill: QUEST_OBJ_COLOR },
      });
      objText.x = QUEST_CARD_PADDING;
      objText.y = yPos;
      objTexts.push(objText);
      yPos += objText.height + 2;
    }

    // Rewards line
    const rewardParts: string[] = [];
    if (quest.rewards.xp > 0) rewardParts.push(`${quest.rewards.xp} XP`);
    if (quest.rewards.gold > 0) rewardParts.push(`${quest.rewards.gold} Gold`);

    let rewardText: Text | null = null;
    if (rewardParts.length > 0) {
      yPos += 2;
      rewardText = new Text({
        text: `Rewards: ${rewardParts.join(", ")}`,
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 10, fill: QUEST_REWARD_COLOR },
      });
      rewardText.x = QUEST_CARD_PADDING;
      rewardText.y = yPos;
      yPos += rewardText.height + 6;
    } else {
      yPos += 4;
    }

    // Accept button
    const btnHeight = 24;
    const btnWidth = Math.min(120, cardInnerWidth - QUEST_CARD_PADDING * 2);
    const btnX = QUEST_CARD_PADDING;
    const btnY = yPos;

    const btnContainer = new Container();
    btnContainer.eventMode = "static";
    btnContainer.cursor = "pointer";

    const btnBg = new Graphics();
    btnBg.roundRect(0, 0, btnWidth, btnHeight, 4);
    btnBg.fill({ color: QUEST_ACCEPT_BG });
    btnBg.roundRect(0, 0, btnWidth, btnHeight, 4);
    btnBg.stroke({ color: QUEST_CARD_BORDER, alpha: 0.5, width: 1 });

    const btnText = new Text({
      text: "Accept Quest",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 11, fill: QUEST_ACCEPT_TEXT_COLOR, fontWeight: "bold" },
    });
    btnText.x = (btnWidth - btnText.width) / 2;
    btnText.y = (btnHeight - btnText.height) / 2;

    btnContainer.addChild(btnBg);
    btnContainer.addChild(btnText);
    btnContainer.x = btnX;
    btnContainer.y = btnY;

    btnContainer.on("pointerover", () => {
      btnBg.clear();
      btnBg.roundRect(0, 0, btnWidth, btnHeight, 4);
      btnBg.fill({ color: QUEST_ACCEPT_HOVER_BG });
      btnBg.roundRect(0, 0, btnWidth, btnHeight, 4);
      btnBg.stroke({ color: QUEST_CARD_BORDER, alpha: 0.8, width: 1 });
    });
    btnContainer.on("pointerout", () => {
      btnBg.clear();
      btnBg.roundRect(0, 0, btnWidth, btnHeight, 4);
      btnBg.fill({ color: QUEST_ACCEPT_BG });
      btnBg.roundRect(0, 0, btnWidth, btnHeight, 4);
      btnBg.stroke({ color: QUEST_CARD_BORDER, alpha: 0.5, width: 1 });
    });
    btnContainer.on("pointerdown", () => {
      canvasCallbacks.sendCommand?.(`accept ${quest.name}`);
    });

    yPos += btnHeight + QUEST_CARD_PADDING;
    const cardHeight = yPos;

    // Card background
    const cardBg = new Graphics();
    cardBg.roundRect(0, 0, cardInnerWidth, cardHeight, 4);
    cardBg.fill({ color: QUEST_CARD_BG, alpha: 0.9 });
    cardBg.roundRect(0, 0, cardInnerWidth, cardHeight, 4);
    cardBg.stroke({ color: QUEST_CARD_BORDER, alpha: 0.4, width: 1 });

    card.addChild(cardBg);
    card.addChild(titleText);
    card.addChild(descText);
    for (const objText of objTexts) card.addChild(objText);
    if (rewardText) card.addChild(rewardText);
    card.addChild(btnContainer);

    return { container: card, height: cardHeight };
  }

  destroy() {
    this.container.destroy({ children: true });
  }
}
