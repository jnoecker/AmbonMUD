import { Container, Graphics, Sprite, Texture, Assets } from "pixi.js";
import type { AbilityVisualArchetype, CombatEventData, SkillVisual } from "../../types";

/**
 * Per-archetype combat visual renderer. Driven by the skill's `visual.archetype`
 * (sent via Char.Skills) and the ability id on each combat event. Falls back to
 * sensible defaults for unknown archetypes.
 *
 * Archetypes:
 *  - RANGED_PROJECTILE: sprite (or colored orb) arcs caster -> target.
 *  - MELEE_STRIKE: pulses the ability icon at the impact point (caller skips red slash).
 *  - HEAL_AURA: rising green/gold aura on target.
 *  - BUFF_AURA: rising aura + icon flash on self/ally.
 *  - DEBUFF_AURA: sinking dark aura on enemy.
 *  - AREA_BURST: radial wave at target.
 *  - SUMMON_POOF: sparkle puff at caster.
 */

const PROJECTILE_DURATION_MS = 420;
const TRAIL_COUNT = 8;

const ICON_PULSE_DURATION_MS = 420;
const ICON_PULSE_START_SCALE = 0.4;
const ICON_PULSE_END_SCALE = 1.4;
const ICON_PULSE_TARGET_SIZE = 64;

const AURA_DURATION_MS = 700;
const AURA_RADIUS = 56;

const AREA_BURST_DURATION_MS = 520;
const AREA_BURST_RADIUS = 110;

const SUMMON_POOF_DURATION_MS = 650;
const SUMMON_POOF_PARTICLES = 14;

const ARCHETYPE_DEFAULT_COLOR: Record<AbilityVisualArchetype, number> = {
  RANGED_PROJECTILE: 0xc8a8f0,
  MELEE_STRIKE: 0xf0c674,
  HEAL_AURA: 0x90e090,
  BUFF_AURA: 0xf0d68a,
  DEBUFF_AURA: 0x9060c0,
  AREA_BURST: 0xf08060,
  SUMMON_POOF: 0xb294bb,
};

function hexToNumber(hex: string | null | undefined, fallback: number): number {
  if (!hex || typeof hex !== "string") return fallback;
  const trimmed = hex.startsWith("#") ? hex.slice(1) : hex;
  const n = parseInt(trimmed, 16);
  return Number.isFinite(n) ? n : fallback;
}

interface RangedProjectile {
  sx: number; sy: number; ex: number; ey: number;
  elapsed: number;
  duration: number;
  color: number;
  glowColor: number;
  sprite: Sprite | null;
  trail: Array<{ x: number; y: number }>;
  arcHeight: number;
}

interface IconPulse {
  x: number; y: number;
  elapsed: number;
  duration: number;
  sprite: Sprite | null;
  tint: number;
}

interface Aura {
  x: number; y: number;
  elapsed: number;
  duration: number;
  color: number;
  direction: "rise" | "fall";
}

interface AreaBurst {
  x: number; y: number;
  elapsed: number;
  duration: number;
  color: number;
}

interface PoofParticle {
  x: number; y: number;
  vx: number; vy: number;
  elapsed: number;
  lifetime: number;
  color: number;
  size: number;
}

/** Per-event archetype resolution result returned to BattleScene. */
export interface ArchetypeRoute {
  archetype: AbilityVisualArchetype | null;
  /** Whether the new system handles the projectile/impact (BattleScene should skip SpellProjectile/red slash). */
  consumesProjectile: boolean;
  /** Whether CombatAnimator should suppress its red slash for this event. */
  suppressSlash: boolean;
}

export class AbilityVisualSystem {
  readonly container = new Container();
  private graphics = new Graphics();
  private spriteLayer = new Container();
  private projectiles: RangedProjectile[] = [];
  private iconPulses: IconPulse[] = [];
  private auras: Aura[] = [];
  private bursts: AreaBurst[] = [];
  private poofs: PoofParticle[] = [];
  private textureCache = new Map<string, Texture | null>();

  constructor() {
    this.container.addChild(this.graphics);
    this.container.addChild(this.spriteLayer);
  }

  /**
   * Route a combat event through the archetype dispatcher.
   * - Returns archetype + flags so BattleScene knows what other systems to skip.
   * - Mutates internal state to play the relevant animation.
   */
  processEvent(
    event: CombatEventData,
    archetype: AbilityVisualArchetype | null,
    visual: SkillVisual | null,
    imageFallback: string | null,
    playerX: number, playerY: number,
    enemyX: number, enemyY: number,
  ): ArchetypeRoute {
    if (!archetype) {
      return { archetype: null, consumesProjectile: false, suppressSlash: false };
    }

    const color = hexToNumber(visual?.color, ARCHETYPE_DEFAULT_COLOR[archetype]);
    const accentColor = hexToNumber(visual?.accentColor, color);
    const projectileImage = visual?.projectileImage ?? imageFallback ?? null;

    // Caster and target positions depend on who sourced the event.
    const sx = event.sourceIsPlayer ? playerX : enemyX;
    const sy = event.sourceIsPlayer ? playerY : enemyY;

    // For buffs/heals on self, target is the player. For debuffs on enemy, target is enemy.
    // We let event.targetIsPlayer drive this when present (abilityCast); otherwise infer.
    const targetIsPlayer = event.type === "abilityCast"
      ? event.targetIsPlayer
      : !event.sourceIsPlayer;
    const tx = targetIsPlayer ? playerX : enemyX;
    const ty = targetIsPlayer ? playerY : enemyY;

    switch (archetype) {
      case "RANGED_PROJECTILE":
        this.spawnRangedProjectile(sx, sy, tx, ty, color, accentColor, projectileImage);
        return { archetype, consumesProjectile: true, suppressSlash: false };

      case "MELEE_STRIKE":
        // Caller still does lunge/shake via CombatAnimator; we replace the red slash
        // with a pulse of the ability icon at the impact point.
        this.spawnIconPulse(tx, ty, projectileImage, color);
        return { archetype, consumesProjectile: true, suppressSlash: true };

      case "HEAL_AURA":
        this.spawnAura(tx, ty, color, "rise");
        this.spawnIconPulse(tx, ty - 10, projectileImage, color);
        return { archetype, consumesProjectile: true, suppressSlash: false };

      case "BUFF_AURA":
        this.spawnAura(tx, ty, color, "rise");
        this.spawnIconPulse(tx, ty - 10, projectileImage, color);
        return { archetype, consumesProjectile: true, suppressSlash: false };

      case "DEBUFF_AURA":
        this.spawnAura(tx, ty, color, "fall");
        this.spawnIconPulse(tx, ty + 10, projectileImage, color);
        return { archetype, consumesProjectile: true, suppressSlash: false };

      case "AREA_BURST":
        this.spawnAreaBurst(tx, ty, color);
        return { archetype, consumesProjectile: true, suppressSlash: false };

      case "SUMMON_POOF":
        this.spawnSummonPoof(sx, sy, color, accentColor);
        return { archetype, consumesProjectile: true, suppressSlash: false };
    }
    return { archetype: null, consumesProjectile: false, suppressSlash: false };
  }

  private spawnRangedProjectile(
    sx: number, sy: number, ex: number, ey: number,
    color: number, glowColor: number,
    imagePath: string | null,
  ) {
    const proj: RangedProjectile = {
      sx, sy, ex, ey,
      elapsed: 0,
      duration: PROJECTILE_DURATION_MS,
      color,
      glowColor,
      sprite: null,
      trail: [],
      arcHeight: 0.2,
    };
    this.projectiles.push(proj);

    if (imagePath) {
      const cached = this.textureCache.get(imagePath);
      if (cached) {
        proj.sprite = this.attachSprite(cached, color);
      } else if (cached !== null) {
        Assets.load(imagePath).then((tex: unknown) => {
          const texture = tex instanceof Texture ? tex : null;
          this.textureCache.set(imagePath, texture);
          if (texture && this.projectiles.includes(proj)) {
            proj.sprite = this.attachSprite(texture, color);
          }
        }).catch(() => {
          this.textureCache.set(imagePath, null);
        });
      }
    }
  }

  private attachSprite(texture: Texture, tint: number): Sprite {
    const sprite = new Sprite(texture);
    sprite.anchor.set(0.5);
    sprite.width = 32;
    sprite.height = 32;
    sprite.tint = tint;
    this.spriteLayer.addChild(sprite);
    return sprite;
  }

  private spawnIconPulse(x: number, y: number, imagePath: string | null, tint: number) {
    const pulse: IconPulse = { x, y, elapsed: 0, duration: ICON_PULSE_DURATION_MS, sprite: null, tint };
    this.iconPulses.push(pulse);
    if (!imagePath) return;
    const cached = this.textureCache.get(imagePath);
    if (cached) {
      pulse.sprite = this.attachSprite(cached, 0xffffff);
    } else if (cached !== null) {
      Assets.load(imagePath).then((tex: unknown) => {
        const texture = tex instanceof Texture ? tex : null;
        this.textureCache.set(imagePath, texture);
        if (texture && this.iconPulses.includes(pulse)) {
          pulse.sprite = this.attachSprite(texture, 0xffffff);
        }
      }).catch(() => {
        this.textureCache.set(imagePath, null);
      });
    }
  }

  private spawnAura(x: number, y: number, color: number, direction: "rise" | "fall") {
    this.auras.push({ x, y, elapsed: 0, duration: AURA_DURATION_MS, color, direction });
  }

  private spawnAreaBurst(x: number, y: number, color: number) {
    this.bursts.push({ x, y, elapsed: 0, duration: AREA_BURST_DURATION_MS, color });
  }

  private spawnSummonPoof(x: number, y: number, color: number, accent: number) {
    for (let i = 0; i < SUMMON_POOF_PARTICLES; i++) {
      const angle = (Math.PI * 2 * i) / SUMMON_POOF_PARTICLES + (Math.random() - 0.5) * 0.4;
      const speed = 40 + Math.random() * 60;
      this.poofs.push({
        x, y,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed - 30,
        elapsed: 0,
        lifetime: SUMMON_POOF_DURATION_MS * (0.7 + Math.random() * 0.6),
        color: Math.random() > 0.5 ? color : accent,
        size: 2 + Math.random() * 2,
      });
    }
  }

  update(deltaMs: number) {
    // Projectiles
    for (let i = this.projectiles.length - 1; i >= 0; i--) {
      const p = this.projectiles[i];
      p.elapsed += deltaMs;
      const t = Math.min(1, p.elapsed / p.duration);
      const pos = this.arcPosition(p, t);
      p.trail.push({ x: pos.x, y: pos.y });
      if (p.trail.length > TRAIL_COUNT) p.trail.shift();
      if (p.sprite) {
        p.sprite.x = pos.x;
        p.sprite.y = pos.y;
        // Slight wobble + rotation for life
        p.sprite.rotation += (deltaMs / 1000) * 4;
      }
      if (t >= 1) {
        if (p.sprite) {
          this.spriteLayer.removeChild(p.sprite);
          p.sprite.destroy();
        }
        // Brief impact burst at endpoint
        this.spawnAreaBurst(p.ex, p.ey, p.color);
        this.projectiles.splice(i, 1);
      }
    }

    // Icon pulses
    for (let i = this.iconPulses.length - 1; i >= 0; i--) {
      const pulse = this.iconPulses[i];
      pulse.elapsed += deltaMs;
      const t = Math.min(1, pulse.elapsed / pulse.duration);
      if (pulse.sprite) {
        const scale = ICON_PULSE_START_SCALE + (ICON_PULSE_END_SCALE - ICON_PULSE_START_SCALE) * easeOutCubic(t);
        pulse.sprite.x = pulse.x;
        pulse.sprite.y = pulse.y;
        pulse.sprite.width = ICON_PULSE_TARGET_SIZE * scale;
        pulse.sprite.height = ICON_PULSE_TARGET_SIZE * scale;
        pulse.sprite.alpha = 1 - t;
      }
      if (t >= 1) {
        if (pulse.sprite) {
          this.spriteLayer.removeChild(pulse.sprite);
          pulse.sprite.destroy();
        }
        this.iconPulses.splice(i, 1);
      }
    }

    // Auras
    for (let i = this.auras.length - 1; i >= 0; i--) {
      const a = this.auras[i];
      a.elapsed += deltaMs;
      if (a.elapsed >= a.duration) this.auras.splice(i, 1);
    }

    // Area bursts
    for (let i = this.bursts.length - 1; i >= 0; i--) {
      const b = this.bursts[i];
      b.elapsed += deltaMs;
      if (b.elapsed >= b.duration) this.bursts.splice(i, 1);
    }

    // Poof particles
    for (let i = this.poofs.length - 1; i >= 0; i--) {
      const p = this.poofs[i];
      p.elapsed += deltaMs;
      const dt = deltaMs / 1000;
      p.x += p.vx * dt;
      p.y += p.vy * dt;
      p.vy += 120 * dt;
      p.vx *= 0.97;
      if (p.elapsed >= p.lifetime) this.poofs.splice(i, 1);
    }

    this.draw();
  }

  private arcPosition(p: RangedProjectile, t: number): { x: number; y: number } {
    const eased = 1 - (1 - t) * (1 - t);
    const x = p.sx + (p.ex - p.sx) * eased;
    const baseY = p.sy + (p.ey - p.sy) * eased;
    const arc = -p.arcHeight * Math.hypot(p.ex - p.sx, p.ey - p.sy) * 4 * t * (1 - t);
    return { x, y: baseY + arc };
  }

  private draw() {
    const g = this.graphics;
    g.clear();

    // Projectile trails + glows (used even when a sprite is present)
    for (const p of this.projectiles) {
      for (let i = 0; i < p.trail.length; i++) {
        const tp = p.trail[i];
        const trailT = i / Math.max(1, p.trail.length);
        const trailAlpha = trailT * 0.4;
        const size = 3 + trailT * 4;
        g.circle(tp.x, tp.y, size * 1.5);
        g.fill({ color: p.glowColor, alpha: trailAlpha * 0.25 });
        g.circle(tp.x, tp.y, size);
        g.fill({ color: p.color, alpha: trailAlpha });
      }
      // If no sprite, render an orb at the head
      if (!p.sprite) {
        const t = Math.min(1, p.elapsed / p.duration);
        const pos = this.arcPosition(p, t);
        g.circle(pos.x, pos.y, 14);
        g.fill({ color: p.glowColor, alpha: 0.18 });
        g.circle(pos.x, pos.y, 8);
        g.fill({ color: p.glowColor, alpha: 0.4 });
        g.circle(pos.x, pos.y, 5);
        g.fill({ color: p.color, alpha: 0.9 });
        g.circle(pos.x, pos.y, 2);
        g.fill({ color: 0xffffff, alpha: 0.7 });
      }
    }

    // Auras — circular gradient that rises (buff/heal) or sinks (debuff).
    for (const a of this.auras) {
      const t = Math.min(1, a.elapsed / a.duration);
      const eased = easeOutCubic(t);
      const dy = a.direction === "rise" ? -eased * 30 : eased * 22;
      const cy = a.y + dy;
      const alpha = (1 - t) * 0.45;
      // Outer halo
      g.circle(a.x, cy, AURA_RADIUS * (0.6 + eased * 0.6));
      g.fill({ color: a.color, alpha: alpha * 0.4 });
      // Mid ring
      g.circle(a.x, cy, AURA_RADIUS * (0.45 + eased * 0.4));
      g.fill({ color: a.color, alpha: alpha * 0.5 });
      // Inner bright core
      g.circle(a.x, cy, AURA_RADIUS * 0.25);
      g.fill({ color: 0xffffff, alpha: alpha * 0.5 });
      // Direction streaks: vertical lines moving up or down
      const lineCount = 6;
      for (let i = 0; i < lineCount; i++) {
        const ox = (i / lineCount - 0.5) * AURA_RADIUS * 1.4;
        const lineLen = 14 + eased * 18;
        const startY = cy + (a.direction === "rise" ? lineLen : -lineLen);
        g.moveTo(a.x + ox, startY);
        g.lineTo(a.x + ox, cy);
        g.stroke({ color: a.color, alpha: alpha * 0.8, width: 1.5 });
      }
    }

    // Area burst — radial expanding ring at impact site.
    for (const b of this.bursts) {
      const t = Math.min(1, b.elapsed / b.duration);
      const eased = easeOutCubic(t);
      const radius = AREA_BURST_RADIUS * eased;
      const alpha = (1 - t) * 0.7;
      // Filled disc fades fastest
      g.circle(b.x, b.y, radius * 0.5);
      g.fill({ color: b.color, alpha: alpha * 0.35 });
      // Ring outline
      g.circle(b.x, b.y, radius);
      g.stroke({ color: b.color, alpha, width: 3 });
      g.circle(b.x, b.y, radius * 0.7);
      g.stroke({ color: 0xffffff, alpha: alpha * 0.6, width: 1.5 });
    }

    // Summon poof — sparkle particles.
    for (const p of this.poofs) {
      const t = p.elapsed / p.lifetime;
      const alpha = t < 0.2 ? t / 0.2 : 1 - (t - 0.2) / 0.8;
      const size = p.size * (1 - t * 0.5);
      g.circle(p.x, p.y, size * 2.4);
      g.fill({ color: p.color, alpha: alpha * 0.15 });
      g.circle(p.x, p.y, size);
      g.fill({ color: p.color, alpha: alpha * 0.85 });
      g.circle(p.x, p.y, size * 0.4);
      g.fill({ color: 0xffffff, alpha: alpha * 0.7 });
    }
  }

  clear() {
    for (const p of this.projectiles) {
      if (p.sprite) {
        this.spriteLayer.removeChild(p.sprite);
        p.sprite.destroy();
      }
    }
    for (const p of this.iconPulses) {
      if (p.sprite) {
        this.spriteLayer.removeChild(p.sprite);
        p.sprite.destroy();
      }
    }
    this.projectiles.length = 0;
    this.iconPulses.length = 0;
    this.auras.length = 0;
    this.bursts.length = 0;
    this.poofs.length = 0;
    this.graphics.clear();
  }
}

function easeOutCubic(t: number): number {
  return 1 - (1 - t) ** 3;
}
