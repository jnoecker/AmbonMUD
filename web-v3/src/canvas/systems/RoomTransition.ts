import { Graphics } from "pixi.js";

/**
 * Magical particle dissolve transition for room changes.
 *
 * Instead of a plain opacity fade, the scene dissolves into luminous
 * motes that swirl briefly, then the new scene fades in underneath.
 *
 * Respects prefers-reduced-motion by falling back to a simple fade.
 */

const DISSOLVE_DURATION_MS = 500;
const REFORM_DURATION_MS = 400;
const TOTAL_DURATION_MS = DISSOLVE_DURATION_MS + REFORM_DURATION_MS;
const PARTICLE_COUNT = 80;

const MOTE_COLORS = [0xc8b8e8, 0xa897d2, 0x8caec9, 0xbea873, 0xd8def1];

interface TransitionMote {
  /** Start position (scattered across canvas) */
  sx: number;
  sy: number;
  /** Drift velocity */
  vx: number;
  vy: number;
  /** Wander phase */
  phase: number;
  size: number;
  color: number;
}

const prefersReducedMotion =
  typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

export class RoomTransition {
  readonly graphics = new Graphics();
  private motes: TransitionMote[] = [];
  private elapsed = 0;
  private active = false;
  private width = 0;
  private height = 0;

  /** Callback: set to 1 = fully opaque scene, 0 = fully hidden scene. */
  private _sceneAlpha = 1;

  /** Whether the transition particles are showing (scene should be dimmed). */
  get isActive(): boolean { return this.active; }

  /** The opacity the main scene container should use during transition. */
  get sceneAlpha(): number { return this._sceneAlpha; }

  resize(w: number, h: number) {
    this.width = w;
    this.height = h;
  }

  /** Start a room transition. Call this when room ID changes. */
  start() {
    if (prefersReducedMotion) {
      // Simple fade fallback: we'll handle this via sceneAlpha only
      this.active = true;
      this.elapsed = 0;
      this.motes.length = 0;
      return;
    }

    this.active = true;
    this.elapsed = 0;
    this.motes.length = 0;

    const w = this.width;
    const h = this.height;
    if (w === 0 || h === 0) return;

    // Spawn motes scattered across the canvas
    for (let i = 0; i < PARTICLE_COUNT; i++) {
      const x = Math.random() * w;
      const y = Math.random() * h;
      // Drift toward center then back outward
      const angle = Math.atan2(h / 2 - y, w / 2 - x) + (Math.random() - 0.5) * 1.2;
      const speed = 20 + Math.random() * 40;

      this.motes.push({
        sx: x,
        sy: y,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed,
        phase: Math.random() * Math.PI * 2,
        size: 2 + Math.random() * 3,
        color: MOTE_COLORS[Math.floor(Math.random() * MOTE_COLORS.length)],
      });
    }
  }

  /**
   * Update the transition. Returns true while still active.
   * The caller should apply `this.sceneAlpha` to the scene container.
   */
  update(deltaMs: number): boolean {
    if (!this.active) return false;

    this.elapsed += deltaMs;

    if (prefersReducedMotion) {
      // Simple fade: out then in
      if (this.elapsed < DISSOLVE_DURATION_MS) {
        this._sceneAlpha = 1 - this.elapsed / DISSOLVE_DURATION_MS;
      } else {
        this._sceneAlpha = (this.elapsed - DISSOLVE_DURATION_MS) / REFORM_DURATION_MS;
      }
      if (this.elapsed >= TOTAL_DURATION_MS) {
        this.active = false;
        this._sceneAlpha = 1;
      }
      return this.active;
    }

    // Dissolve phase: scene fades out, motes appear and swirl
    if (this.elapsed < DISSOLVE_DURATION_MS) {
      const dt = this.elapsed / DISSOLVE_DURATION_MS;
      // Smooth ease-in-out
      this._sceneAlpha = 1 - dt * dt;
    }
    // Reform phase: scene fades back in, motes dissolve
    else {
      const rt = (this.elapsed - DISSOLVE_DURATION_MS) / REFORM_DURATION_MS;
      // Smooth ease-out
      const eased = 1 - (1 - rt) * (1 - rt);
      this._sceneAlpha = eased;
    }

    // Update mote positions
    const dt = deltaMs / 1000;
    for (const m of this.motes) {
      m.sx += m.vx * dt;
      m.sy += m.vy * dt;
      // Reverse drift midway through
      if (this.elapsed > DISSOLVE_DURATION_MS * 0.8) {
        m.vx *= 0.97;
        m.vy *= 0.97;
      }
    }

    this.draw();

    if (this.elapsed >= TOTAL_DURATION_MS) {
      this.active = false;
      this._sceneAlpha = 1;
      this.motes.length = 0;
      this.graphics.clear();
    }

    return this.active;
  }

  private draw() {
    const g = this.graphics;
    g.clear();

    const dissolveT = Math.min(1, this.elapsed / DISSOLVE_DURATION_MS);
    const reformT = this.elapsed > DISSOLVE_DURATION_MS
      ? Math.min(1, (this.elapsed - DISSOLVE_DURATION_MS) / REFORM_DURATION_MS)
      : 0;

    // Mote visibility: fade in during dissolve, fade out during reform
    let moteAlpha: number;
    if (dissolveT < 1) {
      // Fade in quickly
      moteAlpha = Math.min(1, dissolveT * 2);
    } else {
      // Fade out
      moteAlpha = 1 - reformT;
    }

    for (const m of this.motes) {
      const breathe = 0.8 + 0.2 * Math.sin(this.elapsed * 0.004 + m.phase);
      const size = m.size * breathe;
      const alpha = moteAlpha * (0.5 + 0.5 * Math.sin(this.elapsed * 0.003 + m.phase * 2));

      // Outer glow
      g.circle(m.sx, m.sy, size * 2.5);
      g.fill({ color: m.color, alpha: alpha * 0.1 });

      // Core
      g.circle(m.sx, m.sy, size);
      g.fill({ color: m.color, alpha: alpha * 0.6 });

      // Hot center
      g.circle(m.sx, m.sy, size * 0.3);
      g.fill({ color: 0xffffff, alpha: alpha * 0.35 });
    }
  }

  clear() {
    this.active = false;
    this._sceneAlpha = 1;
    this.motes.length = 0;
    this.graphics.clear();
    this.elapsed = 0;
  }
}
