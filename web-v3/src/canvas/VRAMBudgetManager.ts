/**
 * Tracks estimated VRAM usage and triggers quality fallbacks when budget
 * is exceeded. Uses a conservative 1.5x multiplier on raw pixel calculations
 * to account for mipmaps, WebGL overhead, and compressed texture internals.
 *
 * Per D-07: 64MB mobile / 256MB desktop budget, warning at 80%, fallback
 * to 1x resolution + disable parallax when over budget.
 */

const BYTES_PER_PIXEL = 4; // RGBA
const ESTIMATION_MULTIPLIER = 1.5; // Conservative for mipmaps + WebGL overhead
const WARNING_THRESHOLD = 0.8; // 80% budget

export interface VRAMFallbackState {
  /** True when VRAM usage exceeds the budget cap */
  overBudget: boolean;
  /** Recommended max resolution (1 when over budget on mobile, else device DPR) */
  maxResolution: number;
  /** Whether parallax should be disabled to save VRAM */
  disableParallax: boolean;
}

export class VRAMBudgetManager {
  private budgetBytes: number;
  private estimatedUsage = 0;
  private isMobile: boolean;
  private warningLogged = false;

  constructor(isMobile: boolean) {
    this.isMobile = isMobile;
    // 64MB on mobile, 256MB on desktop per D-07
    this.budgetBytes = isMobile ? 64 * 1024 * 1024 : 256 * 1024 * 1024;
  }

  /**
   * Track a texture allocation. Call when a new texture is loaded or created.
   * @param width Texture width in pixels
   * @param height Texture height in pixels
   */
  trackTexture(width: number, height: number): void {
    const bytes = width * height * BYTES_PER_PIXEL * ESTIMATION_MULTIPLIER;
    this.estimatedUsage += bytes;

    if (this.usagePercent() >= WARNING_THRESHOLD * 100 && !this.warningLogged) {
      console.warn(
        `[VRAM] Budget ${Math.round(this.usagePercent())}% used ` +
        `(${this.formatBytes(this.estimatedUsage)} / ${this.formatBytes(this.budgetBytes)})`,
      );
      this.warningLogged = true;
    }

    if (this.isOverBudget()) {
      console.warn(
        `[VRAM] Budget exceeded — quality reduced ` +
        `(${this.formatBytes(this.estimatedUsage)} / ${this.formatBytes(this.budgetBytes)})`,
      );
    }
  }

  /**
   * Release a texture allocation. Call when a texture is unloaded/destroyed.
   * @param width Texture width in pixels
   * @param height Texture height in pixels
   */
  releaseTexture(width: number, height: number): void {
    const bytes = width * height * BYTES_PER_PIXEL * ESTIMATION_MULTIPLIER;
    this.estimatedUsage = Math.max(0, this.estimatedUsage - bytes);

    // Reset warning flag when usage drops below threshold
    if (this.usagePercent() < WARNING_THRESHOLD * 100) {
      this.warningLogged = false;
    }
  }

  /** Reset all tracked usage (e.g., on full scene teardown). */
  reset(): void {
    this.estimatedUsage = 0;
    this.warningLogged = false;
  }

  /** Whether estimated usage exceeds the budget cap. */
  isOverBudget(): boolean {
    return this.estimatedUsage > this.budgetBytes;
  }

  /** Current usage as a percentage of budget (0-100+). */
  usagePercent(): number {
    return (this.estimatedUsage / this.budgetBytes) * 100;
  }

  /** Current estimated usage in bytes. */
  get usage(): number {
    return this.estimatedUsage;
  }

  /** Budget cap in bytes. */
  get budget(): number {
    return this.budgetBytes;
  }

  /** Get the current fallback state for consumers. */
  getFallbackState(): VRAMFallbackState {
    const overBudget = this.isOverBudget();
    return {
      overBudget,
      maxResolution: overBudget && this.isMobile ? 1 : (this.isMobile ? 2 : window.devicePixelRatio || 1),
      disableParallax: overBudget && this.isMobile,
    };
  }

  private formatBytes(bytes: number): string {
    return `${(bytes / (1024 * 1024)).toFixed(1)}MB`;
  }
}

/** Singleton instance — created during PixiCanvas init. */
let _instance: VRAMBudgetManager | null = null;

export function initVRAMBudget(isMobile: boolean): VRAMBudgetManager {
  _instance = new VRAMBudgetManager(isMobile);
  return _instance;
}

export function getVRAMBudget(): VRAMBudgetManager | null {
  return _instance;
}
