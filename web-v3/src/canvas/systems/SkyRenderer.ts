import { Graphics } from "pixi.js";

/**
 * Renders a time-of-day sky gradient behind the world scene.
 *
 * Colors come from Zone.Environment GMCP (per-zone sky gradients keyed
 * by time period: DAWN, DAY, DUSK, NIGHT).  Falls back to dark defaults
 * when no theme data is available.
 *
 * The gradient smoothly cross-fades when the period changes.
 */

interface SkyColors {
  top: number;
  bottom: number;
}

const DEFAULT_SKIES: Record<string, SkyColors> = {
  DAWN:  { top: 0x2a1a3a, bottom: 0xc88060 },
  DAY:   { top: 0x4a6ea0, bottom: 0x87ceeb },
  DUSK:  { top: 0x3a2040, bottom: 0xc86848 },
  NIGHT: { top: 0x0a0c14, bottom: 0x1a1c2e },
};

function parseHex(hex: string): number {
  return parseInt(hex.replace("#", ""), 16) || 0;
}

function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t;
}

function lerpColor(a: number, b: number, t: number): number {
  const ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
  const br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
  const r = Math.round(lerp(ar, br, t));
  const g = Math.round(lerp(ag, bg, t));
  const bl = Math.round(lerp(ab, bb, t));
  return (r << 16) | (g << 8) | bl;
}

const FADE_DURATION_S = 8; // cross-fade over 8 seconds on period change

export class SkyRenderer {
  readonly graphics = new Graphics();
  private width = 0;
  private height = 0;

  private themeSkies: Record<string, SkyColors> = { ...DEFAULT_SKIES };
  private currentPeriod = "NIGHT";
  private previousColors: SkyColors = DEFAULT_SKIES.NIGHT;
  private targetColors: SkyColors = DEFAULT_SKIES.NIGHT;
  private fadeProgress = 1; // 1 = fully at target, no fade

  resize(w: number, h: number) {
    this.width = w;
    this.height = h;
  }

  /** Update sky gradients from Zone.Environment GMCP. */
  setTheme(skyGradients: Record<string, { top: string; bottom: string }>) {
    for (const [period, grad] of Object.entries(skyGradients)) {
      this.themeSkies[period] = { top: parseHex(grad.top), bottom: parseHex(grad.bottom) };
    }
    // Refresh target to use new theme colors for the current period
    const next = this.themeSkies[this.currentPeriod] ?? DEFAULT_SKIES.NIGHT;
    this.previousColors = this.currentDisplayColors();
    this.targetColors = next;
    this.fadeProgress = 0;
  }

  /** Called when World.Time GMCP arrives with a new period. */
  setPeriod(period: string) {
    if (period === this.currentPeriod) return;
    this.currentPeriod = period;
    this.previousColors = this.currentDisplayColors();
    this.targetColors = this.themeSkies[period] ?? DEFAULT_SKIES[period] ?? DEFAULT_SKIES.NIGHT;
    this.fadeProgress = 0;
  }

  update(deltaMs: number) {
    if (this.width === 0 || this.height === 0) return;
    if (this.fadeProgress < 1) {
      this.fadeProgress = Math.min(1, this.fadeProgress + deltaMs / 1000 / FADE_DURATION_S);
    }
    this.draw();
  }

  private currentDisplayColors(): SkyColors {
    const t = this.fadeProgress;
    return {
      top: lerpColor(this.previousColors.top, this.targetColors.top, t),
      bottom: lerpColor(this.previousColors.bottom, this.targetColors.bottom, t),
    };
  }

  private draw() {
    const g = this.graphics;
    const colors = this.currentDisplayColors();
    const w = this.width;
    const h = this.height;

    g.clear();
    // Vertical gradient: fill with top color then overlay bottom using alpha gradient
    g.rect(0, 0, w, h);
    g.fill({ color: colors.top });

    // Simulate gradient with multiple horizontal bands
    const steps = 16;
    for (let i = 0; i < steps; i++) {
      const t = (i + 0.5) / steps;
      const bandColor = lerpColor(colors.top, colors.bottom, t);
      const bandY = (h * i) / steps;
      const bandH = h / steps + 1;
      g.rect(0, bandY, w, bandH);
      g.fill({ color: bandColor, alpha: 1 });
    }
  }

  clear() {
    this.graphics.clear();
  }
}
