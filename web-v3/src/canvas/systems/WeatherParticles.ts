import { Graphics } from "pixi.js";

/**
 * Renders weather particle effects (rain, snow, fog, storm) on the world scene.
 *
 * The active effect is driven by the World.Weather GMCP packet's weather type,
 * resolved against the Zone.Environment weatherParticleOverrides map, then
 * the WeatherTypeDefinition particleHint from the server.
 *
 * Respects prefers-reduced-motion.
 */

const prefersReducedMotion =
  typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

interface RainDrop {
  x: number;
  y: number;
  speed: number;
  length: number;
  alpha: number;
}

interface SnowFlake {
  x: number;
  y: number;
  speed: number;
  size: number;
  drift: number;
  phase: number;
  alpha: number;
}

interface FogPatch {
  x: number;
  y: number;
  w: number;
  h: number;
  speed: number;
  alpha: number;
  phase: number;
}

const RAIN_COUNT = 120;
const SNOW_COUNT = 60;
const FOG_COUNT = 12;
const STORM_RAIN_COUNT = 200;

export class WeatherParticles {
  readonly graphics = new Graphics();
  private width = 0;
  private height = 0;
  private hint = "";
  private time = 0;

  private rainDrops: RainDrop[] = [];
  private snowFlakes: SnowFlake[] = [];
  private fogPatches: FogPatch[] = [];

  resize(w: number, h: number) {
    this.width = w;
    this.height = h;
    this.reinitParticles();
  }

  /**
   * Set the active particle hint. The hint comes from:
   * 1. Zone.Environment.weatherParticleOverrides[weatherId] if present
   * 2. Otherwise WeatherTypeDefinition.particleHint from World.Weather GMCP
   * 3. Empty string means no particles (e.g. CLEAR)
   */
  setHint(hint: string) {
    if (hint === this.hint) return;
    this.hint = hint;
    this.reinitParticles();
  }

  update(deltaMs: number) {
    if (prefersReducedMotion || this.hint === "" || this.width === 0) {
      this.graphics.clear();
      return;
    }
    this.time += deltaMs / 1000;
    switch (this.hint) {
      case "rain":
        this.updateRain(deltaMs, false);
        break;
      case "storm":
        this.updateRain(deltaMs, true);
        break;
      case "snow":
        this.updateSnow(deltaMs);
        break;
      case "fog":
        this.updateFog(deltaMs);
        break;
      case "wind":
        this.updateWind(deltaMs);
        break;
      default:
        this.graphics.clear();
    }
  }

  private reinitParticles() {
    this.rainDrops = [];
    this.snowFlakes = [];
    this.fogPatches = [];
    const w = this.width;
    const h = this.height;
    if (w === 0 || h === 0) return;

    const count = this.hint === "storm" ? STORM_RAIN_COUNT : RAIN_COUNT;
    if (this.hint === "rain" || this.hint === "storm") {
      for (let i = 0; i < count; i++) {
        this.rainDrops.push(this.makeRainDrop(w, h, true));
      }
    } else if (this.hint === "snow") {
      for (let i = 0; i < SNOW_COUNT; i++) {
        this.snowFlakes.push(this.makeSnowFlake(w, h, true));
      }
    } else if (this.hint === "fog") {
      for (let i = 0; i < FOG_COUNT; i++) {
        this.fogPatches.push(this.makeFogPatch(w, h, true));
      }
    }
  }

  private makeRainDrop(w: number, h: number, scatter = false): RainDrop {
    return {
      x: Math.random() * w,
      y: scatter ? Math.random() * h : -10,
      speed: 280 + Math.random() * 120,
      length: 10 + Math.random() * 10,
      alpha: 0.3 + Math.random() * 0.3,
    };
  }

  private makeSnowFlake(w: number, h: number, scatter = false): SnowFlake {
    return {
      x: Math.random() * w,
      y: scatter ? Math.random() * h : -10,
      speed: 20 + Math.random() * 30,
      size: 1 + Math.random() * 2,
      drift: (Math.random() - 0.5) * 20,
      phase: Math.random() * Math.PI * 2,
      alpha: 0.5 + Math.random() * 0.4,
    };
  }

  private makeFogPatch(w: number, h: number, scatter = false): FogPatch {
    const pw = 150 + Math.random() * 200;
    const ph = 40 + Math.random() * 60;
    return {
      x: scatter ? Math.random() * (w + pw) - pw : -pw,
      y: Math.random() * h * 0.8,
      w: pw,
      h: ph,
      speed: 8 + Math.random() * 12,
      alpha: 0.04 + Math.random() * 0.06,
      phase: Math.random() * Math.PI * 2,
    };
  }

  private updateRain(deltaMs: number, isStorm: boolean) {
    const g = this.graphics;
    g.clear();
    const dt = deltaMs / 1000;
    const w = this.width;
    const h = this.height;
    const angle = isStorm ? 0.18 : 0.06; // radians, slight lean
    const color = isStorm ? 0x9ab0cc : 0xb8d0e8;

    for (const drop of this.rainDrops) {
      drop.x += Math.sin(angle) * drop.speed * dt;
      drop.y += Math.cos(angle) * drop.speed * dt;
      if (drop.y > h + 20) {
        drop.x = Math.random() * w;
        drop.y = -20;
      }
      const ex = drop.x + Math.sin(angle) * drop.length;
      const ey = drop.y + Math.cos(angle) * drop.length;
      g.moveTo(drop.x, drop.y);
      g.lineTo(ex, ey);
      g.stroke({ color, alpha: drop.alpha, width: 1 });
    }

    // Storm: occasional lightning flash
    if (isStorm) {
      const flash = Math.max(0, 0.04 * Math.sin(this.time * 3.7) * Math.sin(this.time * 11.3));
      if (flash > 0.02) {
        g.rect(0, 0, w, h);
        g.fill({ color: 0xd0e8ff, alpha: flash * 0.15 });
      }
    }
  }

  private updateSnow(deltaMs: number) {
    const g = this.graphics;
    g.clear();
    const dt = deltaMs / 1000;
    const w = this.width;
    const h = this.height;

    for (const flake of this.snowFlakes) {
      flake.y += flake.speed * dt;
      flake.x += (flake.drift + Math.sin(this.time * 0.8 + flake.phase) * 12) * dt;
      if (flake.y > h + 10) {
        flake.x = Math.random() * w;
        flake.y = -10;
      }
      if (flake.x > w + 10) flake.x = -10;
      if (flake.x < -10) flake.x = w + 10;

      g.circle(flake.x, flake.y, flake.size);
      g.fill({ color: 0xddeeff, alpha: flake.alpha });
    }
  }

  private updateFog(deltaMs: number) {
    const g = this.graphics;
    g.clear();
    const dt = deltaMs / 1000;
    const w = this.width;

    for (const patch of this.fogPatches) {
      patch.x += patch.speed * dt;
      if (patch.x > w + patch.w) patch.x = -patch.w;

      const pulse = patch.alpha * (0.7 + 0.3 * Math.sin(this.time * 0.4 + patch.phase));
      g.ellipse(patch.x, patch.y, patch.w, patch.h);
      g.fill({ color: 0xc8d8e8, alpha: pulse });
    }
  }

  private updateWind(deltaMs: number) {
    // Wind: subtle horizontal streaks
    const g = this.graphics;
    g.clear();
    const w = this.width;
    const h = this.height;
    const dt = deltaMs / 1000;

    if (this.snowFlakes.length === 0) {
      for (let i = 0; i < 30; i++) {
        this.snowFlakes.push(this.makeSnowFlake(w, h, true));
      }
    }
    for (const flake of this.snowFlakes) {
      flake.x += (80 + flake.drift * 2) * dt;
      flake.y += (Math.sin(this.time * 1.2 + flake.phase) * 20) * dt;
      if (flake.x > w + 20) { flake.x = -20; flake.y = Math.random() * h; }

      const lineLen = 15 + Math.random() * 20;
      g.moveTo(flake.x, flake.y);
      g.lineTo(flake.x - lineLen, flake.y);
      g.stroke({ color: 0xd0d8e8, alpha: flake.alpha * 0.5, width: 1 });
    }
  }

  clear() {
    this.hint = "";
    this.rainDrops = [];
    this.snowFlakes = [];
    this.fogPatches = [];
    this.graphics.clear();
  }
}
