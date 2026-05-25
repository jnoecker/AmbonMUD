import { Graphics } from "pixi.js";
import type { CombatEventData } from "../../types";

/**
 * Spell projectile system — visible arcing projectiles that travel from
 * caster to target with trailing particles.
 *
 * Driven by CombatAnimator combat events. Only ability-type events spawn
 * projectiles; melee attacks use the existing lunge/slash system.
 */

const PROJECTILE_DURATION_MS = 700;
const TRAIL_COUNT = 12;
const IMPACT_PARTICLE_COUNT = 14;
const IMPACT_LIFETIME_MS = 850;

/** Visual profiles keyed by combat event type. */
interface ProjectileStyle {
  /** Core projectile color */
  color: number;
  /** Trail color (slightly different from core) */
  trailColor: number;
  /** Glow color */
  glowColor: number;
  /** Projectile size in pixels */
  size: number;
  /** Arc height as fraction of distance (0 = straight line, 0.3 = high arc) */
  arcHeight: number;
}

const STYLES: Record<string, ProjectileStyle> = {
  abilityHit: {
    color: 0xc8a8f0,
    trailColor: 0xa888d0,
    glowColor: 0x9070c0,
    size: 9,
    arcHeight: 0.24,
  },
  heal: {
    color: 0x90e090,
    trailColor: 0x60c060,
    glowColor: 0x40a040,
    size: 7,
    arcHeight: 0.28,
  },
  hotTick: {
    color: 0xa8e8a8,
    trailColor: 0x78c878,
    glowColor: 0x58a858,
    size: 5,
    arcHeight: 0.18,
  },
  dotTick: {
    color: 0xd088d0,
    trailColor: 0xb060b0,
    glowColor: 0x904090,
    size: 6,
    arcHeight: 0.12,
  },
  shieldAbsorb: {
    color: 0x70c8f0,
    trailColor: 0x50a8d0,
    glowColor: 0x3088b0,
    size: 7,
    arcHeight: 0.18,
  },
};

interface Projectile {
  /** Start position */
  sx: number;
  sy: number;
  /** End position */
  ex: number;
  ey: number;
  elapsed: number;
  duration: number;
  style: ProjectileStyle;
  /** Trail history positions */
  trail: Array<{ x: number; y: number }>;
}

interface ImpactParticle {
  x: number;
  y: number;
  vx: number;
  vy: number;
  color: number;
  elapsed: number;
  lifetime: number;
  size: number;
}

export class SpellProjectileSystem {
  readonly graphics = new Graphics();
  private projectiles: Projectile[] = [];
  private impacts: ImpactParticle[] = [];

  /**
   * Spawn a projectile for a combat event.
   * Returns true if a projectile was spawned (caller can skip other effects).
   */
  trySpawn(event: CombatEventData, playerX: number, playerY: number, enemyX: number, enemyY: number): boolean {
    const style = STYLES[event.type];
    if (!style) return false;

    let sx: number;
    let sy: number;
    let ex: number;
    let ey: number;

    if (event.type === "heal" || event.type === "hotTick" || event.type === "shieldAbsorb") {
      // Heals/shields: effect rises from below the player
      sx = playerX;
      sy = playerY + 40;
      ex = playerX;
      ey = playerY - 20;
    } else if (event.sourceIsPlayer) {
      sx = playerX;
      sy = playerY;
      ex = enemyX;
      ey = enemyY;
    } else {
      sx = enemyX;
      sy = enemyY;
      ex = playerX;
      ey = playerY;
    }

    this.projectiles.push({
      sx,
      sy,
      ex,
      ey,
      elapsed: 0,
      duration: PROJECTILE_DURATION_MS,
      style,
      trail: [],
    });

    return true;
  }

  update(deltaMs: number) {
    // Update projectiles
    for (let i = this.projectiles.length - 1; i >= 0; i--) {
      const p = this.projectiles[i];
      p.elapsed += deltaMs;
      const t = Math.min(1, p.elapsed / p.duration);

      // Compute current position along arc
      const pos = this.getArcPosition(p, t);

      // Store in trail (keep last N positions)
      p.trail.push({ x: pos.x, y: pos.y });
      if (p.trail.length > TRAIL_COUNT) p.trail.shift();

      if (t >= 1) {
        // Spawn impact burst
        this.spawnImpact(p.ex, p.ey, p.style);
        this.projectiles.splice(i, 1);
      }
    }

    // Update impact particles
    for (let i = this.impacts.length - 1; i >= 0; i--) {
      const ip = this.impacts[i];
      ip.elapsed += deltaMs;
      const dt = deltaMs / 1000;
      ip.x += ip.vx * dt;
      ip.y += ip.vy * dt;
      ip.vx *= 0.96;
      ip.vy *= 0.96;
      if (ip.elapsed >= ip.lifetime) {
        this.impacts.splice(i, 1);
      }
    }

    this.draw();
  }

  private getArcPosition(p: Projectile, t: number): { x: number; y: number } {
    // Ease-out for snappy arrival
    const eased = 1 - (1 - t) * (1 - t);
    const x = p.sx + (p.ex - p.sx) * eased;
    const baseY = p.sy + (p.ey - p.sy) * eased;
    // Parabolic arc: peaks at t=0.5
    const arcOffset = -p.style.arcHeight * Math.hypot(p.ex - p.sx, p.ey - p.sy) * 4 * t * (1 - t);
    return { x, y: baseY + arcOffset };
  }

  private spawnImpact(x: number, y: number, style: ProjectileStyle) {
    for (let i = 0; i < IMPACT_PARTICLE_COUNT; i++) {
      const angle = (Math.PI * 2 * i) / IMPACT_PARTICLE_COUNT + (Math.random() - 0.5) * 0.6;
      const speed = 30 + Math.random() * 60;
      this.impacts.push({
        x: x + (Math.random() - 0.5) * 8,
        y: y + (Math.random() - 0.5) * 8,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed,
        color: Math.random() > 0.3 ? style.color : style.trailColor,
        elapsed: 0,
        lifetime: IMPACT_LIFETIME_MS * (0.6 + Math.random() * 0.8),
        size: 1.5 + Math.random() * 2,
      });
    }
  }

  private draw() {
    const g = this.graphics;
    g.clear();

    // Draw projectiles
    for (const p of this.projectiles) {
      const t = p.elapsed / p.duration;
      const pos = this.getArcPosition(p, Math.min(1, t));
      const style = p.style;

      // Trail
      for (let i = 0; i < p.trail.length; i++) {
        const tp = p.trail[i];
        const trailT = i / p.trail.length;
        const trailAlpha = trailT * 0.4;
        const trailSize = style.size * 0.3 * (0.3 + trailT * 0.7);

        g.circle(tp.x, tp.y, trailSize * 1.5);
        g.fill({ color: style.glowColor, alpha: trailAlpha * 0.3 });

        g.circle(tp.x, tp.y, trailSize);
        g.fill({ color: style.trailColor, alpha: trailAlpha });
      }

      // Outer glow
      g.circle(pos.x, pos.y, style.size * 3);
      g.fill({ color: style.glowColor, alpha: 0.15 });

      // Mid glow
      g.circle(pos.x, pos.y, style.size * 1.8);
      g.fill({ color: style.glowColor, alpha: 0.3 });

      // Core
      g.circle(pos.x, pos.y, style.size);
      g.fill({ color: style.color, alpha: 0.85 });

      // Hot center
      g.circle(pos.x, pos.y, style.size * 0.4);
      g.fill({ color: 0xffffff, alpha: 0.6 });
    }

    // Draw impact particles
    for (const ip of this.impacts) {
      const life = ip.elapsed / ip.lifetime;
      const alpha = life < 0.15 ? life / 0.15 : 1 - (life - 0.15) / 0.85;
      const size = ip.size * (1 - life * 0.5);

      g.circle(ip.x, ip.y, size * 2);
      g.fill({ color: ip.color, alpha: alpha * 0.12 });

      g.circle(ip.x, ip.y, size);
      g.fill({ color: ip.color, alpha: alpha * 0.7 });

      g.circle(ip.x, ip.y, size * 0.35);
      g.fill({ color: 0xffffff, alpha: alpha * 0.4 });
    }
  }

  clear() {
    this.projectiles.length = 0;
    this.impacts.length = 0;
    this.graphics.clear();
  }
}
