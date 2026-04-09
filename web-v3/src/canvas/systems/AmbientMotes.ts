import { Graphics } from "pixi.js";
import type { MoteColor } from "../../types";

/**
 * Perpetual floating magical motes that drift through the world scene.
 * Zone-themed: colors come from server-provided Zone.Environment GMCP,
 * with hardcoded fallbacks for zones that lack configuration.
 *
 * Respects prefers-reduced-motion by not spawning new motes.
 */

const MAX_MOTES = 60;
const SPAWN_RATE = 6; // motes per second

interface Mote {
  x: number;
  y: number;
  /** Base horizontal drift velocity */
  driftX: number;
  /** Base vertical drift velocity (negative = upward) */
  driftY: number;
  /** Sinusoidal wander phase offset */
  phase: number;
  /** Wander frequency multiplier */
  freq: number;
  /** Wander amplitude in pixels */
  amp: number;
  /** Current age in seconds */
  age: number;
  /** Total lifetime in seconds */
  lifetime: number;
  /** Base size in pixels */
  size: number;
  /** Core color */
  color: number;
  /** Outer glow color */
  glowColor: number;
  /** Brightness multiplier (0.5–1.0) for variety */
  brightness: number;
}

/** Parse a CSS hex color string to a numeric value. */
function parseHex(hex: string): number {
  return parseInt(hex.replace("#", ""), 16) || 0;
}

/** Default theme for unknown zones — soft lavender (matches the brand). */
const DEFAULT_THEME = [
  { core: 0xc8b8e8, glow: 0xa897d2 },
  { core: 0xd8c8f8, glow: 0xb8a8e0 },
  { core: 0xb8a8d8, glow: 0x9888c0 },
];

/** Convert server-provided MoteColor[] to the numeric format used for rendering. */
function convertTheme(colors: MoteColor[]): { core: number; glow: number }[] {
  if (colors.length === 0) return DEFAULT_THEME;
  return colors.map((c) => ({ core: parseHex(c.core), glow: parseHex(c.glow) }));
}

const prefersReducedMotion =
  typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

export class AmbientMotes {
  readonly graphics = new Graphics();
  private motes: Mote[] = [];
  private width = 0;
  private height = 0;
  private currentTheme = DEFAULT_THEME;
  private spawnAccum = 0;

  resize(w: number, h: number) {
    this.width = w;
    this.height = h;
  }

  /** Update the color theme from server-provided Zone.Environment data. */
  setTheme(moteColors: MoteColor[]) {
    this.currentTheme = convertTheme(moteColors);
  }

  /** No-op: room-based theming is replaced by setTheme() from Zone.Environment GMCP. */
  setRoom() {
    // Theme is now set by setTheme() from Zone.Environment GMCP.
    // This method is kept for backward compatibility but is a no-op
    // when a server theme has been applied.
  }

  update(deltaMs: number) {
    if (prefersReducedMotion) return;
    const dt = deltaMs / 1000;
    const w = this.width;
    const h = this.height;
    if (w === 0 || h === 0) return;

    // Spawn new motes
    this.spawnAccum += SPAWN_RATE * dt;
    while (this.spawnAccum >= 1 && this.motes.length < MAX_MOTES) {
      this.spawnAccum -= 1;
      this.spawnMote(w, h);
    }
    if (this.spawnAccum >= 1) this.spawnAccum = 0;

    // Update existing motes
    for (let i = this.motes.length - 1; i >= 0; i--) {
      const m = this.motes[i];
      m.age += dt;
      if (m.age >= m.lifetime) {
        this.motes.splice(i, 1);
        continue;
      }
      // Wandering drift
      const wander = Math.sin(m.age * m.freq + m.phase) * m.amp;
      m.x += (m.driftX + wander * 0.3) * dt;
      m.y += (m.driftY + Math.cos(m.age * m.freq * 0.7 + m.phase) * m.amp * 0.2) * dt;
    }

    // Draw
    this.draw();
  }

  private spawnMote(w: number, h: number) {
    const theme = this.currentTheme;
    const palette = theme[Math.floor(Math.random() * theme.length)];
    const edge = Math.random();
    let x: number;
    let y: number;
    if (edge < 0.5) {
      // Spawn from sides
      x = Math.random() < 0.5 ? -10 : w + 10;
      y = Math.random() * h;
    } else {
      // Spawn from bottom or scattered
      x = Math.random() * w;
      y = h + 10 + Math.random() * 20;
    }
    this.motes.push({
      x,
      y,
      driftX: (Math.random() - 0.5) * 12,
      driftY: -(4 + Math.random() * 10), // gentle upward float
      phase: Math.random() * Math.PI * 2,
      freq: 0.8 + Math.random() * 1.2,
      amp: 8 + Math.random() * 16,
      age: 0,
      lifetime: 6 + Math.random() * 8,
      size: 1.5 + Math.random() * 2.5,
      color: palette.core,
      glowColor: palette.glow,
      brightness: 0.5 + Math.random() * 0.5,
    });
  }

  private draw() {
    const g = this.graphics;
    g.clear();

    for (const m of this.motes) {
      const t = m.age / m.lifetime;
      // Fade in first 15%, hold, fade out last 25%
      let alpha: number;
      if (t < 0.15) alpha = t / 0.15;
      else if (t > 0.75) alpha = (1 - t) / 0.25;
      else alpha = 1;
      alpha *= m.brightness;

      // Gentle breathing pulse
      const breathe = 0.85 + 0.15 * Math.sin(m.age * 2.2 + m.phase);
      const size = m.size * breathe;

      // Outer glow
      g.circle(m.x, m.y, size * 3);
      g.fill({ color: m.glowColor, alpha: alpha * 0.08 });

      // Mid glow
      g.circle(m.x, m.y, size * 1.6);
      g.fill({ color: m.glowColor, alpha: alpha * 0.2 });

      // Core
      g.circle(m.x, m.y, size);
      g.fill({ color: m.color, alpha: alpha * 0.65 });

      // Hot center
      g.circle(m.x, m.y, size * 0.35);
      g.fill({ color: 0xffffff, alpha: alpha * 0.4 });
    }
  }

  clear() {
    this.motes.length = 0;
    this.spawnAccum = 0;
    this.graphics.clear();
  }
}
