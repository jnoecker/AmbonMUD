import { Container, Graphics, Sprite, Text, Texture, Assets } from "pixi.js";
import { gameStateRef } from "../GameStateBridge";
import { canvasEvents } from "../CanvasEventBus";
import { CombatAnimator } from "../systems/CombatAnimator";
import { GainPopupSystem } from "../systems/GainPopup";
import { StatusEffectDisplay } from "../systems/StatusEffectDisplay";

const SPRITE_SIZE = 128;
const SMALL_SPRITE = 80;
const HP_BAR_WIDTH = 140;
const HP_BAR_HEIGHT = 10;
const MANA_BAR_HEIGHT = 8;
const LABEL_FONT_SIZE = 15;
const PARTY_LABEL_FONT_SIZE = 13;

const PLAYER_TINT = 0x81a2be;
const ENEMY_TINT = 0xf0c674;
const HP_COLOR = 0x81c784;
const HP_BG_COLOR = 0x3a3a3a;
const MANA_COLOR = 0x64b5f6;
const LABEL_COLOR = "#d8dcef";
const ENEMY_LABEL_COLOR = "#f0c674";
const PARTY_LABEL_COLOR = "#81a2be";
const HP_TEXT_COLOR = "#d8dcef";

function percent(current: number, max: number): number {
  return max > 0 ? Math.max(0, Math.min(100, (current / max) * 100)) : 0;
}

export class BattleScene {
  readonly container = new Container();

  private playerSprite: Sprite | null = null;
  private playerLabel: Text;
  private playerHpBar = new Graphics();
  private playerManaBar = new Graphics();
  private playerHpText: Text;

  private enemySprite: Sprite | null = null;
  private enemyLabel: Text;
  private enemyHpBar = new Graphics();
  private enemyHpText: Text;

  private partyMembers: Array<{ sprite: Sprite; label: Text; hpBar: Graphics }> = [];

  private combatAnimator: CombatAnimator;
  private gainPopups: GainPopupSystem;
  private statusEffects = new StatusEffectDisplay();
  private uiGraphics = new Graphics();

  private background: Sprite | null = null;
  private lastRoomImage: string | null | undefined = "\0";

  private lastPlayerSpritePath: string | null = null;
  private lastEnemyImage: string | null = null;
  private lastPartyKey = "";
  private width = 0;
  private height = 0;

  // Fade in on enter
  private fadeElapsed = 0;
  private fadeInDuration = 300;
  private fadingIn = true;

  constructor() {
    this.playerLabel = new Text({
      text: "",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: LABEL_FONT_SIZE, fill: LABEL_COLOR },
    });
    this.playerLabel.anchor.set(0.5, 0);

    this.playerHpText = new Text({
      text: "",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 12, fill: HP_TEXT_COLOR },
    });
    this.playerHpText.anchor.set(0.5, 0);

    this.enemyLabel = new Text({
      text: "",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: LABEL_FONT_SIZE, fill: ENEMY_LABEL_COLOR, fontWeight: "bold" },
    });
    this.enemyLabel.anchor.set(0.5, 0);

    this.enemyHpText = new Text({
      text: "",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 12, fill: HP_TEXT_COLOR },
    });
    this.enemyHpText.anchor.set(0.5, 0);

    this.combatAnimator = new CombatAnimator();
    this.gainPopups = new GainPopupSystem();

    this.container.addChild(this.uiGraphics);
    this.container.addChild(this.playerHpBar);
    this.container.addChild(this.playerManaBar);
    this.container.addChild(this.playerLabel);
    this.container.addChild(this.playerHpText);
    this.container.addChild(this.enemyHpBar);
    this.container.addChild(this.enemyLabel);
    this.container.addChild(this.enemyHpText);
    this.container.addChild(this.statusEffects.container);
    this.container.addChild(this.combatAnimator.container);
    this.container.addChild(this.gainPopups.container);
  }

  resize(width: number, height: number) {
    this.width = width;
    this.height = height;
    if (this.background) {
      this.background.width = width;
      this.background.height = height;
    }
  }

  /** Called when entering battle scene */
  enter() {
    this.fadingIn = true;
    this.fadeElapsed = 0;
    this.container.alpha = 0;
    this.combatAnimator.clear();
    this.gainPopups.clear();
    // Reset tracking so update() re-creates everything
    this.lastRoomImage = "\0";
    this.lastPlayerSpritePath = "\0";
    this.lastEnemyImage = "\0";
    this.lastPartyKey = "\0";
  }

  update(deltaMs: number) {
    // Fade in
    if (this.fadingIn) {
      this.fadeElapsed += deltaMs;
      this.container.alpha = Math.min(1, this.fadeElapsed / this.fadeInDuration);
      if (this.fadeElapsed >= this.fadeInDuration) {
        this.fadingIn = false;
        this.container.alpha = 1;
      }
    }

    const state = gameStateRef.current;
    const { vitals, character, combatTarget, groupInfo, room } = state;

    // Update room background
    if (room.image !== this.lastRoomImage) {
      this.lastRoomImage = room.image;
      this.loadBackground(room.image ?? null);
    }

    // Update player sprite
    const spritePath = character.sprite;
    if (spritePath !== this.lastPlayerSpritePath) {
      this.lastPlayerSpritePath = spritePath;
      this.loadPlayerSprite(spritePath);
    }

    this.playerLabel.text = character.name !== "-" ? character.name : "";

    // Update enemy sprite
    const enemyImage = combatTarget?.targetImage ?? null;
    if (enemyImage !== this.lastEnemyImage) {
      this.lastEnemyImage = enemyImage;
      this.loadEnemySprite(enemyImage);
    }

    this.enemyLabel.text = combatTarget?.targetName ?? "";

    // Update party
    const partyKey = groupInfo.members.map((m) => `${m.name}:${m.hp}:${m.maxHp}`).join("|");
    if (partyKey !== this.lastPartyKey) {
      this.lastPartyKey = partyKey;
      this.rebuildParty(groupInfo.members.filter((m) => m.name !== character.name));
    }

    // Process events from the bus
    const { combat, gains } = canvasEvents.drain();
    const playerPos = this.getPlayerPosition();
    const enemyPos = this.getEnemyPosition();

    for (const event of combat) {
      this.combatAnimator.processEvent(event, playerPos.x, playerPos.y, enemyPos.x, enemyPos.y);
    }

    for (const event of gains) {
      this.gainPopups.spawn(event, this.width / 2, this.height * 0.15);
    }

    // Update animations
    this.combatAnimator.update(deltaMs);
    this.gainPopups.update(deltaMs);

    // Layout everything
    this.layoutAll(vitals, combatTarget);
  }

  private getPlayerPosition() {
    const w = this.width;
    const h = this.height;
    return { x: w * 0.25, y: h * 0.6 };
  }

  private getEnemyPosition() {
    const w = this.width;
    const h = this.height;
    return { x: w * 0.75, y: h * 0.45 };
  }

  private layoutAll(vitals: { hp: number; maxHp: number; mana: number; maxMana: number }, combatTarget: { targetName: string | null; targetHp: number | null; targetMaxHp: number | null } | null) {
    const w = this.width;
    const h = this.height;
    if (w === 0 || h === 0) return;

    this.uiGraphics.clear();

    // Red-tinted combat overlay on top of room background
    this.uiGraphics.rect(0, 0, w, h);
    this.uiGraphics.fill({ color: 0x2a0a0a, alpha: 0.55 });

    // Ground line
    const groundY = h * 0.75;
    this.uiGraphics.moveTo(0, groundY);
    this.uiGraphics.lineTo(w, groundY);
    this.uiGraphics.stroke({ color: 0x4a4a6a, alpha: 0.4, width: 1 });

    // Player position (left side)
    const playerPos = this.getPlayerPosition();
    if (this.playerSprite) {
      this.playerSprite.x = playerPos.x;
      this.playerSprite.y = playerPos.y;
    }

    this.playerLabel.x = playerPos.x;
    this.playerLabel.y = playerPos.y + SPRITE_SIZE / 2 + 6;

    // Player HP bar
    const playerHpPct = percent(vitals.hp, vitals.maxHp);
    this.playerHpBar.clear();
    this.playerHpBar.roundRect(playerPos.x - HP_BAR_WIDTH / 2, playerPos.y + SPRITE_SIZE / 2 + 24, HP_BAR_WIDTH, HP_BAR_HEIGHT, 2);
    this.playerHpBar.fill(HP_BG_COLOR);
    if (playerHpPct > 0) {
      this.playerHpBar.roundRect(playerPos.x - HP_BAR_WIDTH / 2, playerPos.y + SPRITE_SIZE / 2 + 24, HP_BAR_WIDTH * playerHpPct / 100, HP_BAR_HEIGHT, 2);
      this.playerHpBar.fill(HP_COLOR);
    }

    this.playerHpText.text = `${vitals.hp}/${vitals.maxHp}`;
    this.playerHpText.x = playerPos.x;
    this.playerHpText.y = playerPos.y + SPRITE_SIZE / 2 + 34;

    // Player mana bar
    const manaPct = percent(vitals.mana, vitals.maxMana);
    this.playerManaBar.clear();
    this.playerManaBar.roundRect(playerPos.x - HP_BAR_WIDTH / 2, playerPos.y + SPRITE_SIZE / 2 + 46, HP_BAR_WIDTH, MANA_BAR_HEIGHT, 2);
    this.playerManaBar.fill(HP_BG_COLOR);
    if (manaPct > 0) {
      this.playerManaBar.roundRect(playerPos.x - HP_BAR_WIDTH / 2, playerPos.y + SPRITE_SIZE / 2 + 46, HP_BAR_WIDTH * manaPct / 100, MANA_BAR_HEIGHT, 2);
      this.playerManaBar.fill(MANA_COLOR);
    }

    // Status effects above the player
    this.statusEffects.update(gameStateRef.current.effects, playerPos.x, playerPos.y - SPRITE_SIZE / 2 - 28);

    // Enemy position (right side)
    const enemyPos = this.getEnemyPosition();
    if (this.enemySprite) {
      this.enemySprite.x = enemyPos.x;
      this.enemySprite.y = enemyPos.y;
    }

    this.enemyLabel.x = enemyPos.x;
    this.enemyLabel.y = enemyPos.y + SPRITE_SIZE / 2 + 6;

    // Enemy HP bar (only when we have a target)
    this.enemyHpBar.clear();
    if (combatTarget?.targetName) {
      const enemyHp = combatTarget.targetHp ?? 0;
      const enemyMaxHp = combatTarget.targetMaxHp ?? 1;
      const enemyHpPct = percent(enemyHp, enemyMaxHp);
      this.enemyHpBar.roundRect(enemyPos.x - HP_BAR_WIDTH / 2, enemyPos.y + SPRITE_SIZE / 2 + 24, HP_BAR_WIDTH, HP_BAR_HEIGHT, 2);
      this.enemyHpBar.fill(HP_BG_COLOR);
      if (enemyHpPct > 0) {
        this.enemyHpBar.roundRect(enemyPos.x - HP_BAR_WIDTH / 2, enemyPos.y + SPRITE_SIZE / 2 + 24, HP_BAR_WIDTH * enemyHpPct / 100, HP_BAR_HEIGHT, 2);
        this.enemyHpBar.fill(0xef5350);
      }
      this.enemyHpText.text = `${enemyHp}/${enemyMaxHp}`;
    } else {
      this.enemyHpText.text = "";
    }
    this.enemyHpText.x = enemyPos.x;
    this.enemyHpText.y = enemyPos.y + SPRITE_SIZE / 2 + 34;

    // Party members (stacked below player on left)
    const partyStartY = playerPos.y - SPRITE_SIZE / 2 - 20;
    for (let i = 0; i < this.partyMembers.length; i++) {
      const member = this.partyMembers[i];
      const memberState = gameStateRef.current.groupInfo.members.filter((m) => m.name !== gameStateRef.current.character.name)[i];
      const mx = playerPos.x - 60;
      const my = partyStartY - i * (SMALL_SPRITE + 30);

      member.sprite.x = mx;
      member.sprite.y = my;
      member.label.x = mx;
      member.label.y = my + SMALL_SPRITE / 2 + 4;

      // Party member HP bar
      member.hpBar.clear();
      if (memberState) {
        const mHpPct = percent(memberState.hp, memberState.maxHp);
        const barW = 60;
        member.hpBar.roundRect(mx - barW / 2, my + SMALL_SPRITE / 2 + 18, barW, 5, 1);
        member.hpBar.fill(HP_BG_COLOR);
        if (mHpPct > 0) {
          member.hpBar.roundRect(mx - barW / 2, my + SMALL_SPRITE / 2 + 18, barW * mHpPct / 100, 5, 1);
          member.hpBar.fill(HP_COLOR);
        }
      }
    }

    // Draw combat flash overlays
    this.combatAnimator.drawFlashes(
      playerPos.x, playerPos.y, SPRITE_SIZE, SPRITE_SIZE,
      enemyPos.x, enemyPos.y, SPRITE_SIZE, SPRITE_SIZE,
    );
  }

  private rebuildParty(members: Array<{ name: string; hp: number; maxHp: number }>) {
    for (const { sprite, label, hpBar } of this.partyMembers) {
      this.container.removeChild(sprite);
      this.container.removeChild(label);
      this.container.removeChild(hpBar);
      sprite.destroy();
      label.destroy();
      hpBar.destroy();
    }
    this.partyMembers = [];

    for (const member of members) {
      const sprite = new Sprite(Texture.WHITE);
      sprite.width = SMALL_SPRITE;
      sprite.height = SMALL_SPRITE;
      sprite.anchor.set(0.5);
      sprite.tint = PLAYER_TINT;

      const label = new Text({
        text: member.name,
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: PARTY_LABEL_FONT_SIZE, fill: PARTY_LABEL_COLOR },
      });
      label.anchor.set(0.5, 0);

      const hpBar = new Graphics();

      this.container.addChild(sprite);
      this.container.addChild(label);
      this.container.addChild(hpBar);
      this.partyMembers.push({ sprite, label, hpBar });
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
      sprite.alpha = 0.5;
      this.container.addChildAt(sprite, 0); // behind everything
      this.background = sprite;
    } catch {
      // No background
    }
  }

  private async loadPlayerSprite(spritePath: string | null) {
    if (this.playerSprite) {
      this.container.removeChild(this.playerSprite);
      this.playerSprite.destroy();
      this.playerSprite = null;
    }

    const sprite = new Sprite(Texture.WHITE);
    sprite.width = SPRITE_SIZE;
    sprite.height = SPRITE_SIZE;
    sprite.anchor.set(0.5);
    sprite.tint = PLAYER_TINT;
    this.container.addChildAt(sprite, 1); // after uiGraphics
    this.playerSprite = sprite;

    if (spritePath) {
      try {
        const texture = await Assets.load(spritePath);
        sprite.texture = texture;
        sprite.tint = 0xffffff;
      } catch {
        // Keep placeholder
      }
    }
  }

  private async loadEnemySprite(imagePath: string | null) {
    if (this.enemySprite) {
      this.container.removeChild(this.enemySprite);
      this.enemySprite.destroy();
      this.enemySprite = null;
    }

    const sprite = new Sprite(Texture.WHITE);
    sprite.width = SPRITE_SIZE;
    sprite.height = SPRITE_SIZE;
    sprite.anchor.set(0.5);
    sprite.tint = ENEMY_TINT;
    this.container.addChildAt(sprite, 1);
    this.enemySprite = sprite;

    if (imagePath) {
      try {
        const texture = await Assets.load(imagePath);
        sprite.texture = texture;
        sprite.tint = 0xffffff;
      } catch {
        // Keep placeholder
      }
    }
  }

  destroy() {
    this.combatAnimator.clear();
    this.gainPopups.clear();
    this.container.destroy({ children: true });
  }
}
