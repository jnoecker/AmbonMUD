import { Container, Graphics, Sprite, Text, Texture, Assets } from "pixi.js";
import { gameStateRef, canvasCallbacks } from "../GameStateBridge";
import { canvasEvents } from "../CanvasEventBus";
import { CombatAnimator } from "../systems/CombatAnimator";
import { GainPopupSystem } from "../systems/GainPopup";
import { StatusEffectDisplay } from "../systems/StatusEffectDisplay";
import { SpellProjectileSystem } from "../systems/SpellProjectile";

const BASE_SPRITE_SIZE = 160;
const MIN_SPRITE_SIZE = 120;
const MAX_SPRITE_SIZE = 200;
const SMALL_SPRITE = 80;
const HP_BAR_WIDTH = 140;
const HP_BAR_HEIGHT = 10;
const MANA_BAR_HEIGHT = 8;
const LABEL_FONT_SIZE = 15;
const PARTY_LABEL_FONT_SIZE = 13;

const PLAYER_TINT = 0x81a2be;
const PET_TINT = 0xb294bb;
const ENEMY_TINT = 0xf0c674;
const HP_COLOR = 0x8abf8a;
const HP_BG_COLOR = 0x3a3a3a;
const MANA_COLOR = 0x64b5f6;
const LABEL_COLOR = "#d8dcef";
const ENEMY_LABEL_COLOR = "#f0c674";
const PARTY_LABEL_COLOR = "#81a2be";
const PET_LABEL_COLOR = "#b294bb";
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
  private petMembers: Array<{ sprite: Sprite; label: Text; hpBar: Graphics; id: string }> = [];

  private combatAnimator: CombatAnimator;
  private gainPopups: GainPopupSystem;
  private spellProjectiles = new SpellProjectileSystem();
  private statusEffects = new StatusEffectDisplay();
  private enemyStatusEffects = new StatusEffectDisplay();
  private uiGraphics = new Graphics();

  private background: Sprite | null = null;
  private lastRoomImage: string | null | undefined = "\0";
  private bgLoadToken = 0;

  private lastPlayerSpritePath: string | null = null;
  private lastEnemyImage: string | null = null;
  private lastTargetId: string | null = null;
  private lastPartyKey = "";
  private lastPetsKey = "";
  private width = 0;
  private height = 0;

  // Fade in on enter
  private fadeElapsed = 0;
  private fadeInDuration = 300;
  private fadingIn = true;

  // Victory text shown during death animation
  private victoryText: Text | null = null;
  private victoryElapsed = 0;

  // Combat action buttons
  private fleeBtn: Container;
  private smiteBtn: Container;
  private smiteFlashGraphics = new Graphics();
  private smiteFlashAlpha = 0;

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
    this.container.addChild(this.enemyStatusEffects.container);
    this.container.addChild(this.combatAnimator.container);
    this.container.addChild(this.spellProjectiles.graphics);
    this.container.addChild(this.gainPopups.container);

    this.fleeBtn = this.buildActionButton("Flee", 0xd4888a, 0x4a1a1a, () => {
      canvasCallbacks.sendCommand?.("flee");
    });
    this.smiteBtn = this.buildActionButton("Smite", 0xbea873, 0x3a3020, () => {
      const target = gameStateRef.current.combatTarget?.targetName;
      if (target) {
        canvasCallbacks.sendCommand?.(`smite ${target}`);
        this.smiteFlashAlpha = 1;
      }
    });
    this.smiteBtn.visible = false;
    this.container.addChild(this.smiteFlashGraphics);
    this.container.addChild(this.fleeBtn);
    this.container.addChild(this.smiteBtn);
  }

  resize(width: number, height: number) {
    this.width = width;
    this.height = height;
    if (this.background) {
      this.background.width = width;
      this.background.height = height;
    }
  }

  /** Whether the death/victory animation is still playing. */
  get isDeathAnimating(): boolean {
    return this.combatAnimator.isDeathAnimating;
  }

  /** Called when entering battle scene */
  enter() {
    this.fadingIn = true;
    this.fadeElapsed = 0;
    this.container.alpha = 0;
    this.combatAnimator.clear();
    this.spellProjectiles.clear();
    this.gainPopups.clear();
    this.removeVictoryText();
    // Reset tracking so update() re-creates everything
    this.lastRoomImage = "\0";
    this.lastPlayerSpritePath = "\0";
    this.lastEnemyImage = "\0";
    this.lastTargetId = "\0";
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
    const { vitals, character, combatTarget, groupInfo, room, mobs } = state;
    const stillInCombat = state.inCombat;

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

    // Update enemy sprite — fall back to category-based default
    const enemyCategory = combatTarget?.targetCategory ?? "humanoid";
    const enemyImage = combatTarget?.targetImage ?? gameStateRef.current.serverAssets[`default_mob_${enemyCategory}`] ?? null;
    const targetId = combatTarget?.targetId ?? null;
    if (enemyImage !== this.lastEnemyImage) {
      this.lastEnemyImage = enemyImage;
      this.loadEnemySprite(enemyImage);
    }

    // If the combat target changed to a different mob, cancel any in-progress
    // death animation so the dissolve/fade effects don't apply to the new target.
    if (targetId !== this.lastTargetId) {
      this.lastTargetId = targetId;
      if (this.combatAnimator.isDeathAnimating) {
        this.combatAnimator.clearDeathAnimation();
        this.removeVictoryText();
        if (this.enemySprite) {
          this.enemySprite.alpha = 1;
          this.enemySprite.scale.set(1);
          this.enemySprite.tint = this.lastEnemyImage ? 0xffffff : ENEMY_TINT;
        }
        if (!this.fadingIn) {
          this.container.alpha = 1;
        }
      }
    }

    this.enemyLabel.text = combatTarget?.targetName ?? "";

    // Update party
    const partyKey = groupInfo.members.map((m) => `${m.name}:${m.hp}:${m.maxHp}`).join("|");
    if (partyKey !== this.lastPartyKey) {
      this.lastPartyKey = partyKey;
      this.rebuildParty(groupInfo.members.filter((m) => m.name !== character.name));
    }

    // Update pets (mobs with ownerName matching the current character)
    const myPets = mobs.filter((m) => m.ownerName && m.ownerName === character.name);
    const petsKey = myPets.map((p) => `${p.id}:${p.hp}:${p.maxHp}`).join("|");
    if (petsKey !== this.lastPetsKey) {
      this.lastPetsKey = petsKey;
      this.rebuildBattlePets(myPets);
    }

    // Process events from the bus
    const { combat, gains } = canvasEvents.drain();
    const playerPos = this.getPlayerPosition();
    const enemyPos = this.getEnemyPosition();

    for (const event of combat) {
      // Try to spawn a spell projectile — abilities get a visible arcing projectile
      this.spellProjectiles.trySpawn(event, playerPos.x, playerPos.y, enemyPos.x, enemyPos.y);
      this.combatAnimator.processEvent(event, playerPos.x, playerPos.y, enemyPos.x, enemyPos.y);
    }

    for (const event of gains) {
      this.gainPopups.spawn(event, this.width / 2, this.height * 0.15);
    }

    // Update animations
    this.combatAnimator.update(deltaMs);
    this.spellProjectiles.update(deltaMs);
    this.gainPopups.update(deltaMs);

    // Apply death animation effects to enemy sprite
    const deathState = this.combatAnimator.getDeathAnimState();
    if (deathState) {
      if (this.enemySprite) {
        this.enemySprite.alpha = deathState.alpha;
        this.enemySprite.scale.set(deathState.scale);
        // Lerp tint toward white during flash
        const r = Math.round(0xff + (0xff - 0xff) * deathState.tintLerp);
        const g = Math.round(0xff * (1 - deathState.tintLerp * 0.3));
        const b = Math.round(0xff * (1 - deathState.tintLerp * 0.3));
        this.enemySprite.tint = (r << 16) | (g << 8) | b;
      }
      // Hide enemy UI during dissolve/hold
      if (deathState.alpha < 0.3) {
        this.enemyLabel.alpha = 0;
        this.enemyHpBar.alpha = 0;
        this.enemyHpText.alpha = 0;
      }
      // Fade entire scene during hold phase — but only when combat is truly
      // over.  If the player is still fighting other mobs the scene must
      // stay visible so the next target renders correctly.
      if (!this.fadingIn && !stillInCombat) {
        this.container.alpha = deathState.sceneAlpha;
      }
      // Show victory text only when combat is over — not mid-fight
      if (!stillInCombat) {
        this.updateVictoryText(deltaMs, deathState.alpha <= 0);
      }
    } else {
      // Reset enemy sprite if death animation just finished
      if (this.enemySprite) {
        this.enemySprite.alpha = 1;
        this.enemySprite.scale.set(1);
        this.enemySprite.tint = this.lastEnemyImage ? 0xffffff : ENEMY_TINT;
      }
      this.enemyLabel.alpha = 1;
      this.enemyHpBar.alpha = 1;
      this.enemyHpText.alpha = 1;
      // Restore container opacity in case the death animation faded it out
      if (!this.fadingIn) {
        this.container.alpha = 1;
      }
    }

    // Smite button visible only for staff
    this.smiteBtn.visible = character.isStaff;

    // Smite flash animation
    if (this.smiteFlashAlpha > 0) {
      this.smiteFlashAlpha -= deltaMs / 400;
      if (this.smiteFlashAlpha <= 0) {
        this.smiteFlashAlpha = 0;
        this.smiteFlashGraphics.clear();
      } else {
        this.smiteFlashGraphics.clear();
        this.smiteFlashGraphics.rect(0, 0, this.width, this.height);
        this.smiteFlashGraphics.fill({ color: 0xbea873, alpha: this.smiteFlashAlpha * 0.7 });
      }
    }

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

  /**
   * Compute player and enemy sprite sizes based on relative maxHP.
   * - Mob much stronger (2x+ HP): player shrinks, enemy grows
   * - Roughly equal (within ~1.5x): both at base size
   * - Mob much weaker: player grows, enemy shrinks
   */
  private computeSpriteScales(playerMaxHp: number, enemyMaxHp: number | null): { playerSize: number; enemySize: number } {
    if (!enemyMaxHp || enemyMaxHp <= 0 || playerMaxHp <= 0) {
      return { playerSize: BASE_SPRITE_SIZE, enemySize: BASE_SPRITE_SIZE };
    }
    // Ratio > 1 means enemy is stronger, < 1 means weaker
    const ratio = enemyMaxHp / playerMaxHp;
    // Convert to a scale factor: log-based so it doesn't explode
    // clamp the scale difference to ±0.25 of base
    const scaleDelta = Math.max(-0.25, Math.min(0.25, Math.log2(ratio) * 0.15));
    const enemyScale = 1 + scaleDelta;
    const playerScale = 1 - scaleDelta;
    return {
      playerSize: Math.round(Math.max(MIN_SPRITE_SIZE, Math.min(MAX_SPRITE_SIZE, BASE_SPRITE_SIZE * playerScale))),
      enemySize: Math.round(Math.max(MIN_SPRITE_SIZE, Math.min(MAX_SPRITE_SIZE, BASE_SPRITE_SIZE * enemyScale))),
    };
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

    // Compute dynamic sprite sizes based on relative power (maxHP ratio)
    const { playerSize, enemySize } = this.computeSpriteScales(
      vitals.maxHp,
      combatTarget?.targetMaxHp ?? null,
    );

    // Player position (left side) with lunge + shake offsets
    const playerPos = this.getPlayerPosition();
    const playerLunge = this.combatAnimator.getLungeOffset("player");
    const playerShake = this.combatAnimator.getShakeOffset("player");
    const playerDrawX = playerPos.x + playerLunge.x + playerShake.x;
    const playerDrawY = playerPos.y + playerLunge.y + playerShake.y;
    if (this.playerSprite) {
      this.playerSprite.x = playerDrawX;
      this.playerSprite.y = playerDrawY;
      this.playerSprite.width = playerSize;
      this.playerSprite.height = playerSize;
    }

    this.playerLabel.x = playerDrawX;
    this.playerLabel.y = playerDrawY + playerSize / 2 + 6;

    // Player HP bar
    const playerHpPct = percent(vitals.hp, vitals.maxHp);
    this.playerHpBar.clear();
    this.playerHpBar.roundRect(playerPos.x - HP_BAR_WIDTH / 2, playerPos.y + playerSize / 2 + 24, HP_BAR_WIDTH, HP_BAR_HEIGHT, 2);
    this.playerHpBar.fill(HP_BG_COLOR);
    if (playerHpPct > 0) {
      this.playerHpBar.roundRect(playerPos.x - HP_BAR_WIDTH / 2, playerPos.y + playerSize / 2 + 24, HP_BAR_WIDTH * playerHpPct / 100, HP_BAR_HEIGHT, 2);
      this.playerHpBar.fill(HP_COLOR);
    }

    this.playerHpText.text = `${vitals.hp}/${vitals.maxHp}`;
    this.playerHpText.x = playerPos.x;
    this.playerHpText.y = playerPos.y + playerSize / 2 + 34;

    // Player mana bar
    const manaPct = percent(vitals.mana, vitals.maxMana);
    this.playerManaBar.clear();
    this.playerManaBar.roundRect(playerPos.x - HP_BAR_WIDTH / 2, playerPos.y + playerSize / 2 + 46, HP_BAR_WIDTH, MANA_BAR_HEIGHT, 2);
    this.playerManaBar.fill(HP_BG_COLOR);
    if (manaPct > 0) {
      this.playerManaBar.roundRect(playerPos.x - HP_BAR_WIDTH / 2, playerPos.y + playerSize / 2 + 46, HP_BAR_WIDTH * manaPct / 100, MANA_BAR_HEIGHT, 2);
      this.playerManaBar.fill(MANA_COLOR);
    }

    // Status effects above the player
    this.statusEffects.update(gameStateRef.current.effects, playerDrawX, playerDrawY - playerSize / 2 - 28);

    // Enemy position (right side) with lunge + shake offsets
    const enemyPos = this.getEnemyPosition();
    const enemyLunge = this.combatAnimator.getLungeOffset("enemy");
    const enemyShake = this.combatAnimator.getShakeOffset("enemy");
    const enemyDrawX = enemyPos.x + enemyLunge.x + enemyShake.x;
    const enemyDrawY = enemyPos.y + enemyLunge.y + enemyShake.y;
    if (this.enemySprite) {
      this.enemySprite.x = enemyDrawX;
      this.enemySprite.y = enemyDrawY;
      this.enemySprite.width = enemySize;
      this.enemySprite.height = enemySize;
    }

    this.enemyLabel.x = enemyDrawX;
    this.enemyLabel.y = enemyDrawY + enemySize / 2 + 6;

    // Enemy HP bar (only when we have a target)
    this.enemyHpBar.clear();
    if (combatTarget?.targetName) {
      const enemyHp = combatTarget.targetHp ?? 0;
      const enemyMaxHp = combatTarget.targetMaxHp ?? 1;
      const enemyHpPct = percent(enemyHp, enemyMaxHp);
      this.enemyHpBar.roundRect(enemyPos.x - HP_BAR_WIDTH / 2, enemyPos.y + enemySize / 2 + 24, HP_BAR_WIDTH, HP_BAR_HEIGHT, 2);
      this.enemyHpBar.fill(HP_BG_COLOR);
      if (enemyHpPct > 0) {
        this.enemyHpBar.roundRect(enemyPos.x - HP_BAR_WIDTH / 2, enemyPos.y + enemySize / 2 + 24, HP_BAR_WIDTH * enemyHpPct / 100, HP_BAR_HEIGHT, 2);
        this.enemyHpBar.fill(0xd4888a);
      }
      this.enemyHpText.text = `${enemyHp}/${enemyMaxHp}`;
    } else {
      this.enemyHpText.text = "";
    }
    this.enemyHpText.x = enemyPos.x;
    this.enemyHpText.y = enemyPos.y + enemySize / 2 + 34;

    // Enemy status effects above the enemy
    const fullTarget = gameStateRef.current.combatTarget;
    const targetMob = fullTarget?.targetId
      ? gameStateRef.current.mobs.find((m) => m.id === fullTarget.targetId)
      : null;
    this.enemyStatusEffects.update(targetMob?.effects ?? [], enemyDrawX, enemyDrawY - enemySize / 2 - 28);

    // Party members (stacked below player on left)
    const partyStartY = playerPos.y - playerSize / 2 - 20;
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

    // Pets (positioned below player on the right)
    const petStartY = playerPos.y + playerSize / 2 + 20;
    for (let i = 0; i < this.petMembers.length; i++) {
      const pet = this.petMembers[i];
      const petMob = gameStateRef.current.mobs.find((m: { id: string }) => m.id === pet.id);
      const px = playerPos.x + 60;
      const py = petStartY + i * (SMALL_SPRITE + 30);

      pet.sprite.x = px;
      pet.sprite.y = py;
      pet.label.x = px;
      pet.label.y = py + SMALL_SPRITE / 2 + 4;

      pet.hpBar.clear();
      if (petMob) {
        const pHpPct = percent(petMob.hp, petMob.maxHp);
        const barW = 60;
        pet.hpBar.roundRect(px - barW / 2, py + SMALL_SPRITE / 2 + 18, barW, 5, 1);
        pet.hpBar.fill(HP_BG_COLOR);
        if (pHpPct > 0) {
          pet.hpBar.roundRect(px - barW / 2, py + SMALL_SPRITE / 2 + 18, barW * pHpPct / 100, 5, 1);
          pet.hpBar.fill(HP_COLOR);
        }
      }
    }

    // Draw combat effects
    this.combatAnimator.drawGlows(playerDrawX, playerDrawY, enemyDrawX, enemyDrawY);
    this.combatAnimator.drawFlashes(
      playerDrawX, playerDrawY, playerSize, playerSize,
      enemyDrawX, enemyDrawY, enemySize, enemySize,
    );
    this.combatAnimator.drawSlashes();
    this.combatAnimator.drawParticles();

    // Position action buttons — bottom-right
    const btnX = w - 60;
    this.fleeBtn.x = btnX;
    this.fleeBtn.y = h - 24;
    if (this.smiteBtn.visible) {
      this.smiteBtn.x = btnX;
      this.smiteBtn.y = h - 64;
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

  private rebuildBattlePets(pets: Array<{ id: string; name: string; hp: number; maxHp: number; image?: string | null; category?: string }>) {
    for (const { sprite, label, hpBar } of this.petMembers) {
      this.container.removeChild(sprite);
      this.container.removeChild(label);
      this.container.removeChild(hpBar);
      sprite.destroy();
      label.destroy();
      hpBar.destroy();
    }
    this.petMembers = [];

    for (const pet of pets) {
      const sprite = new Sprite(Texture.WHITE);
      sprite.width = SMALL_SPRITE;
      sprite.height = SMALL_SPRITE;
      sprite.anchor.set(0.5);
      sprite.tint = PET_TINT;

      const petImage = pet.image ?? gameStateRef.current.serverAssets[`default_mob_${pet.category ?? "humanoid"}`] ?? null;
      if (petImage) {
        Assets.load(petImage).then((tex: unknown) => {
          if (tex && typeof tex === "object" && "baseTexture" in (tex as Record<string, unknown>)) {
            sprite.texture = tex as Texture;
          } else if (tex instanceof Texture) {
            sprite.texture = tex;
          }
        }).catch(() => {/* ignore */});
      }

      const label = new Text({
        text: `♥ ${pet.name}`,
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: PARTY_LABEL_FONT_SIZE, fill: PET_LABEL_COLOR },
      });
      label.anchor.set(0.5, 0);

      const hpBar = new Graphics();

      this.container.addChild(sprite);
      this.container.addChild(label);
      this.container.addChild(hpBar);
      this.petMembers.push({ sprite, label, hpBar, id: pet.id });
    }
  }

  private async loadBackground(imagePath: string | null) {
    // Token guards against out-of-order async resolution leaving an orphan sprite.
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
    sprite.width = BASE_SPRITE_SIZE;
    sprite.height = BASE_SPRITE_SIZE;
    sprite.anchor.set(0.5);
    sprite.tint = PLAYER_TINT;
    this.container.addChildAt(sprite, 1); // after uiGraphics
    this.playerSprite = sprite;

    if (spritePath) {
      try {
        const texture = await Assets.load(spritePath);
        sprite.texture = texture;
        sprite.width = BASE_SPRITE_SIZE;
        sprite.height = BASE_SPRITE_SIZE;
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
    sprite.width = BASE_SPRITE_SIZE;
    sprite.height = BASE_SPRITE_SIZE;
    sprite.anchor.set(0.5);
    sprite.tint = ENEMY_TINT;
    this.container.addChildAt(sprite, 1);
    this.enemySprite = sprite;

    if (imagePath) {
      try {
        const texture = await Assets.load(imagePath);
        sprite.texture = texture;
        sprite.width = BASE_SPRITE_SIZE;
        sprite.height = BASE_SPRITE_SIZE;
        sprite.tint = 0xffffff;
      } catch {
        // Keep placeholder
      }
    }
  }

  private updateVictoryText(deltaMs: number, enemyGone: boolean) {
    if (!enemyGone) return;
    if (!this.victoryText) {
      this.victoryText = new Text({
        text: "VICTORY",
        style: {
          fontFamily: "JetBrains Mono, Cascadia Mono, monospace",
          fontSize: 28,
          fill: "#f0c674",
          fontWeight: "bold",
          letterSpacing: 6,
        },
      });
      this.victoryText.anchor.set(0.5);
      this.victoryText.x = this.width / 2;
      this.victoryText.y = this.height * 0.4;
      this.victoryText.alpha = 0;
      this.victoryElapsed = 0;
      this.container.addChild(this.victoryText);
    }
    this.victoryElapsed += deltaMs;
    // Fade in over 200ms
    this.victoryText.alpha = Math.min(1, this.victoryElapsed / 200);
    // Gentle float upward
    this.victoryText.y = this.height * 0.4 - this.victoryElapsed * 0.01;
  }

  private removeVictoryText() {
    if (this.victoryText) {
      this.container.removeChild(this.victoryText);
      this.victoryText.destroy();
      this.victoryText = null;
      this.victoryElapsed = 0;
    }
  }

  destroy() {
    this.combatAnimator.clear();
    this.spellProjectiles.clear();
    this.gainPopups.clear();
    this.container.destroy({ children: true });
  }
}
