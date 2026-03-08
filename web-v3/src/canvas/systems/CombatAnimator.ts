import { Container, Graphics, Text } from "pixi.js";
import type { CombatEventData } from "../../types";

const FLOAT_SPEED = 50;
const LIFETIME_MS = 1200;

// Lunge animation
const LUNGE_DURATION_MS = 280;
const LUNGE_RETURN_MS = 200;
const LUNGE_DISTANCE = 0.35; // fraction of distance between attacker and target

// Slash effect
const SLASH_DURATION_MS = 300;
const SLASH_SIZE = 48;

// Shake effect
const SHAKE_DURATION_MS = 250;
const SHAKE_INTENSITY = 6;

// Cast glow
const GLOW_DURATION_MS = 400;
const GLOW_RADIUS = 60;

interface FloatingNumber {
  text: Text;
  elapsed: number;
  dx: number;
}

interface FlashEffect {
  target: "player" | "enemy";
  elapsed: number;
  duration: number;
  color: number;
}

interface LungeEffect {
  who: "player" | "enemy";
  elapsed: number;
  phase: "lunge" | "return";
  dx: number;
  dy: number;
}

interface SlashEffect {
  x: number;
  y: number;
  elapsed: number;
  angle: number;
}

interface ShakeEffect {
  who: "player" | "enemy";
  elapsed: number;
}

interface GlowEffect {
  who: "player" | "enemy";
  elapsed: number;
  color: number;
}

/** Multi-phase death animation for defeated enemies. */
interface DeathAnimation {
  elapsed: number;
  /** Total duration in ms. */
  duration: number;
  /** Phase boundaries as fractions of duration. */
  phases: {
    /** 0–flashEnd: intense white flash + heavy shake */
    flashEnd: number;
    /** flashEnd–dissolveEnd: sprite shrinks, fades, and desaturates */
    dissolveEnd: number;
    /** dissolveEnd–1.0: "VICTORY" text holds, scene fades out */
  };
}

const DEATH_ANIM_DURATION_MS = 1400;
const DEATH_FLASH_FRAC = 0.2; // first 20% = white flash
const DEATH_DISSOLVE_FRAC = 0.7; // 20–70% = dissolve/shrink

const EVENT_COLORS: Record<string, string> = {
  meleeHit: "#ff6b6b",
  abilityHit: "#b9aed8",
  heal: "#81c784",
  hotTick: "#a5d6a7",
  dotTick: "#ce93d8",
  dodge: "#ffd54f",
  shieldAbsorb: "#4fc3f7",
  kill: "#f0c674",
  death: "#ef5350",
};

export class CombatAnimator {
  readonly container = new Container();
  private floats: FloatingNumber[] = [];
  private flashes: FlashEffect[] = [];
  private lunges: LungeEffect[] = [];
  private slashes: SlashEffect[] = [];
  private shakes: ShakeEffect[] = [];
  private glows: GlowEffect[] = [];
  private deathAnim: DeathAnimation | null = null;
  private flashGraphics = new Graphics();
  private slashGraphics = new Graphics();
  private glowGraphics = new Graphics();

  constructor() {
    this.container.addChild(this.glowGraphics);
    this.container.addChild(this.flashGraphics);
    this.container.addChild(this.slashGraphics);
  }

  processEvent(event: CombatEventData, playerX: number, playerY: number, enemyX: number, enemyY: number) {
    const type = event.type;

    if (type === "dodge") {
      this.spawnFloat("DODGE", EVENT_COLORS.dodge, event.sourceIsPlayer ? enemyX : playerX, event.sourceIsPlayer ? enemyY : playerY);
      return;
    }

    if (type === "kill") {
      // Start multi-phase death animation instead of a brief flash
      this.deathAnim = {
        elapsed: 0,
        duration: DEATH_ANIM_DURATION_MS,
        phases: { flashEnd: DEATH_FLASH_FRAC, dissolveEnd: DEATH_DISSOLVE_FRAC },
      };
      // Intense white flash at start
      this.addFlash("enemy", 0xffffff, DEATH_ANIM_DURATION_MS * DEATH_FLASH_FRAC);
      // Heavy shake during flash phase
      this.shakes = this.shakes.filter((s) => s.who !== "enemy");
      this.shakes.push({ who: "enemy", elapsed: 0 });
      // Spawn "DEFEATED" text
      this.spawnFloat("DEFEATED", EVENT_COLORS.kill, enemyX, enemyY - 30);
      // Multiple slashes at the enemy for dramatic finish
      this.addSlash(enemyX - 8, enemyY - 8);
      this.addSlash(enemyX + 8, enemyY + 8);
      // "VICTORY" text spawns slightly later — handled by BattleScene
      return;
    }

    if (type === "death") {
      this.spawnFloat("DEATH", EVENT_COLORS.death, playerX, playerY - 20);
      this.addFlash("player", 0xef5350, 400);
      this.addShake("player");
      return;
    }

    if (event.damage > 0) {
      const color = EVENT_COLORS[type] ?? EVENT_COLORS.meleeHit;
      const targetX = event.sourceIsPlayer ? enemyX : playerX;
      const targetY = event.sourceIsPlayer ? enemyY : playerY;
      this.spawnFloat(`-${event.damage}`, color, targetX, targetY);

      // Attacker lunges toward target
      const attacker: "player" | "enemy" = event.sourceIsPlayer ? "player" : "enemy";
      const defender: "player" | "enemy" = event.sourceIsPlayer ? "enemy" : "player";
      const dx = targetX - (event.sourceIsPlayer ? playerX : enemyX);
      const dy = targetY - (event.sourceIsPlayer ? playerY : enemyY);
      this.addLunge(attacker, dx * LUNGE_DISTANCE, dy * LUNGE_DISTANCE);

      // Slash at the impact point
      this.addSlash(targetX, targetY);

      // Defender shakes on hit
      this.addShake(defender);

      // Flash on hit
      this.addFlash(defender, 0xff6b6b, 200);

      // Ability cast glow on caster
      if (type === "abilityHit") {
        this.addGlow(attacker, 0xb9aed8);
      }
    }

    if (event.healing > 0) {
      const color = EVENT_COLORS[type] ?? EVENT_COLORS.heal;
      this.spawnFloat(`+${event.healing}`, color, playerX, playerY);
      this.addGlow("player", 0x81c784);
    }

    if (event.absorbed > 0) {
      this.spawnFloat(`(${event.absorbed})`, EVENT_COLORS.shieldAbsorb, event.sourceIsPlayer ? enemyX : playerX, (event.sourceIsPlayer ? enemyY : playerY) + 16);
    }
  }

  private spawnFloat(display: string, color: string, x: number, y: number) {
    const text = new Text({
      text: display,
      style: {
        fontFamily: "JetBrains Mono, Cascadia Mono, monospace",
        fontSize: 14,
        fill: color,
        fontWeight: "bold",
      },
    });
    text.anchor.set(0.5);
    text.x = x + (Math.random() - 0.5) * 30;
    text.y = y;

    this.container.addChild(text);
    this.floats.push({ text, elapsed: 0, dx: (Math.random() - 0.5) * 20 });
  }

  private addFlash(target: "player" | "enemy", color: number, duration: number) {
    this.flashes.push({ target, elapsed: 0, duration, color });
  }

  private addLunge(who: "player" | "enemy", dx: number, dy: number) {
    // Only one lunge per character at a time
    this.lunges = this.lunges.filter((l) => l.who !== who);
    this.lunges.push({ who, elapsed: 0, phase: "lunge", dx, dy });
  }

  private addSlash(x: number, y: number) {
    const angle = -Math.PI / 4 + (Math.random() - 0.5) * 0.4;
    this.slashes.push({ x, y, elapsed: 0, angle });
  }

  private addShake(who: "player" | "enemy") {
    // Only one shake per character at a time
    this.shakes = this.shakes.filter((s) => s.who !== who);
    this.shakes.push({ who, elapsed: 0 });
  }

  private addGlow(who: "player" | "enemy", color: number) {
    this.glows.push({ who, elapsed: 0, color });
  }

  update(deltaMs: number) {
    // Update floating numbers
    for (let i = this.floats.length - 1; i >= 0; i--) {
      const f = this.floats[i];
      f.elapsed += deltaMs;
      f.text.y -= (FLOAT_SPEED * deltaMs) / 1000;
      f.text.x += (f.dx * deltaMs) / 1000;
      f.text.alpha = Math.max(0, 1 - f.elapsed / LIFETIME_MS);

      if (f.elapsed >= LIFETIME_MS) {
        this.container.removeChild(f.text);
        f.text.destroy();
        this.floats.splice(i, 1);
      }
    }

    // Update flashes
    for (let i = this.flashes.length - 1; i >= 0; i--) {
      const flash = this.flashes[i];
      flash.elapsed += deltaMs;
      if (flash.elapsed >= flash.duration) {
        this.flashes.splice(i, 1);
      }
    }

    // Update lunges
    for (let i = this.lunges.length - 1; i >= 0; i--) {
      const lunge = this.lunges[i];
      lunge.elapsed += deltaMs;
      if (lunge.phase === "lunge" && lunge.elapsed >= LUNGE_DURATION_MS) {
        lunge.phase = "return";
        lunge.elapsed = 0;
      }
      if (lunge.phase === "return" && lunge.elapsed >= LUNGE_RETURN_MS) {
        this.lunges.splice(i, 1);
      }
    }

    // Update slashes
    for (let i = this.slashes.length - 1; i >= 0; i--) {
      this.slashes[i].elapsed += deltaMs;
      if (this.slashes[i].elapsed >= SLASH_DURATION_MS) {
        this.slashes.splice(i, 1);
      }
    }

    // Update shakes
    for (let i = this.shakes.length - 1; i >= 0; i--) {
      this.shakes[i].elapsed += deltaMs;
      if (this.shakes[i].elapsed >= SHAKE_DURATION_MS) {
        this.shakes.splice(i, 1);
      }
    }

    // Update glows
    for (let i = this.glows.length - 1; i >= 0; i--) {
      this.glows[i].elapsed += deltaMs;
      if (this.glows[i].elapsed >= GLOW_DURATION_MS) {
        this.glows.splice(i, 1);
      }
    }

    // Update death animation
    if (this.deathAnim) {
      this.deathAnim.elapsed += deltaMs;
      // Extend shake duration to match flash phase
      const flashEndMs = this.deathAnim.duration * this.deathAnim.phases.flashEnd;
      const enemyShake = this.shakes.find((s) => s.who === "enemy");
      if (enemyShake && this.deathAnim.elapsed < flashEndMs) {
        // Keep shake alive during flash phase with extra intensity
        enemyShake.elapsed = Math.min(enemyShake.elapsed, SHAKE_DURATION_MS * 0.5);
      }
      if (this.deathAnim.elapsed >= this.deathAnim.duration) {
        this.deathAnim = null;
      }
    }
  }

  /** Get the offset for a sprite due to lunge animation */
  getLungeOffset(who: "player" | "enemy"): { x: number; y: number } {
    const lunge = this.lunges.find((l) => l.who === who);
    if (!lunge) return { x: 0, y: 0 };

    let t: number;
    if (lunge.phase === "lunge") {
      // Ease-out quad for punchy snap forward
      const raw = Math.min(1, lunge.elapsed / LUNGE_DURATION_MS);
      t = 1 - (1 - raw) * (1 - raw);
    } else {
      // Ease-in-out for smooth return
      const raw = Math.min(1, lunge.elapsed / LUNGE_RETURN_MS);
      t = 1 - raw * raw;
    }

    return { x: lunge.dx * t, y: lunge.dy * t };
  }

  /** Get the shake offset for a sprite */
  getShakeOffset(who: "player" | "enemy"): { x: number; y: number } {
    const shake = this.shakes.find((s) => s.who === who);
    if (!shake) return { x: 0, y: 0 };

    const progress = shake.elapsed / SHAKE_DURATION_MS;
    const decay = 1 - progress;
    const frequency = 30; // Hz
    const x = Math.sin(shake.elapsed * frequency * 0.06) * SHAKE_INTENSITY * decay;
    const y = Math.cos(shake.elapsed * frequency * 0.04) * SHAKE_INTENSITY * decay * 0.5;
    return { x, y };
  }

  /** Draw flash overlays at the given sprite positions */
  drawFlashes(playerX: number, playerY: number, playerW: number, playerH: number, enemyX: number, enemyY: number, enemyW: number, enemyH: number) {
    this.flashGraphics.clear();
    for (const flash of this.flashes) {
      const progress = flash.elapsed / flash.duration;
      const alpha = 0.4 * (1 - progress);
      if (flash.target === "player") {
        this.flashGraphics.rect(playerX - playerW / 2, playerY - playerH / 2, playerW, playerH);
      } else {
        this.flashGraphics.rect(enemyX - enemyW / 2, enemyY - enemyH / 2, enemyW, enemyH);
      }
      this.flashGraphics.fill({ color: flash.color, alpha });
    }
  }

  /** Draw slash effects */
  drawSlashes() {
    this.slashGraphics.clear();
    for (const slash of this.slashes) {
      const progress = slash.elapsed / SLASH_DURATION_MS;

      // Phase 1 (0–0.4): slash draws in. Phase 2 (0.4–1.0): slash fades out.
      const drawPhase = Math.min(1, progress / 0.4);
      const fadePhase = progress > 0.4 ? (progress - 0.4) / 0.6 : 0;
      const alpha = 0.9 * (1 - fadePhase);

      const halfLen = SLASH_SIZE * drawPhase;
      const cos = Math.cos(slash.angle);
      const sin = Math.sin(slash.angle);

      const x1 = slash.x - cos * halfLen;
      const y1 = slash.y - sin * halfLen;
      const x2 = slash.x + cos * halfLen;
      const y2 = slash.y + sin * halfLen;

      // Main red slash
      this.slashGraphics.moveTo(x1, y1);
      this.slashGraphics.lineTo(x2, y2);
      this.slashGraphics.stroke({ color: 0xff4444, alpha, width: 3 });

      // Bright core
      this.slashGraphics.moveTo(x1, y1);
      this.slashGraphics.lineTo(x2, y2);
      this.slashGraphics.stroke({ color: 0xffaaaa, alpha: alpha * 0.7, width: 1.5 });

      // Second cross-slash (offset slightly)
      if (progress > 0.08) {
        const angle2 = slash.angle + Math.PI / 2.5;
        const cos2 = Math.cos(angle2);
        const sin2 = Math.sin(angle2);
        const phase2 = Math.min(1, (progress - 0.08) / 0.35);
        const halfLen2 = SLASH_SIZE * 0.7 * phase2;
        const fade2 = progress > 0.45 ? (progress - 0.45) / 0.55 : 0;
        const alpha2 = 0.7 * (1 - fade2);

        this.slashGraphics.moveTo(slash.x - cos2 * halfLen2, slash.y - sin2 * halfLen2);
        this.slashGraphics.lineTo(slash.x + cos2 * halfLen2, slash.y + sin2 * halfLen2);
        this.slashGraphics.stroke({ color: 0xff4444, alpha: alpha2, width: 2.5 });
      }
    }
  }

  /** Draw cast/heal glows behind sprites */
  drawGlows(playerX: number, playerY: number, enemyX: number, enemyY: number) {
    this.glowGraphics.clear();
    for (const glow of this.glows) {
      const progress = glow.elapsed / GLOW_DURATION_MS;
      // Glow expands and fades
      const scale = 0.6 + progress * 0.5;
      const alpha = 0.35 * (1 - progress);
      const cx = glow.who === "player" ? playerX : enemyX;
      const cy = glow.who === "player" ? playerY : enemyY;
      const r = GLOW_RADIUS * scale;

      this.glowGraphics.circle(cx, cy, r);
      this.glowGraphics.fill({ color: glow.color, alpha });

      // Inner bright core
      this.glowGraphics.circle(cx, cy, r * 0.5);
      this.glowGraphics.fill({ color: 0xffffff, alpha: alpha * 0.4 });
    }
  }

  /** Whether a death animation is currently playing. */
  get isDeathAnimating(): boolean {
    return this.deathAnim !== null;
  }

  /**
   * Returns death animation visual parameters for BattleScene to apply
   * to the enemy sprite (scale, alpha, tint shift).
   */
  getDeathAnimState(): { scale: number; alpha: number; tintLerp: number; sceneAlpha: number } | null {
    if (!this.deathAnim) return null;
    const t = this.deathAnim.elapsed / this.deathAnim.duration;
    const { flashEnd, dissolveEnd } = this.deathAnim.phases;

    if (t <= flashEnd) {
      // Flash phase: sprite goes white, full size
      const flashT = t / flashEnd;
      return { scale: 1, alpha: 1, tintLerp: flashT, sceneAlpha: 1 };
    } else if (t <= dissolveEnd) {
      // Dissolve phase: sprite shrinks and fades
      const dissolveT = (t - flashEnd) / (dissolveEnd - flashEnd);
      const eased = dissolveT * dissolveT; // ease-in for accelerating shrink
      return {
        scale: 1 - eased * 0.6, // shrink to 40%
        alpha: 1 - eased,
        tintLerp: 1 - dissolveT * 0.5, // fade white tint back partially
        sceneAlpha: 1,
      };
    } else {
      // Hold phase: enemy gone, scene fades out
      const holdT = (t - dissolveEnd) / (1 - dissolveEnd);
      return {
        scale: 0.4,
        alpha: 0,
        tintLerp: 0,
        sceneAlpha: 1 - holdT, // fade entire scene
      };
    }
  }

  clear() {
    for (const f of this.floats) {
      this.container.removeChild(f.text);
      f.text.destroy();
    }
    this.floats = [];
    this.flashes = [];
    this.lunges = [];
    this.slashes = [];
    this.shakes = [];
    this.glows = [];
    this.deathAnim = null;
    this.flashGraphics.clear();
    this.slashGraphics.clear();
    this.glowGraphics.clear();
  }
}
