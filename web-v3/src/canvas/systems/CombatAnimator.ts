import { Container, Graphics, Text } from "pixi.js";
import type { CombatEventData } from "../../types";

const FLOAT_SPEED = 50;
const LIFETIME_MS = 1200;

interface FloatingNumber {
  text: Text;
  elapsed: number;
  dx: number; // horizontal drift
}

interface FlashEffect {
  target: "player" | "enemy";
  elapsed: number;
  duration: number;
  color: number;
}

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
  private flashGraphics = new Graphics();

  constructor() {
    this.container.addChild(this.flashGraphics);
  }

  /**
   * Process a combat event and spawn appropriate visual feedback.
   * playerX/playerY and enemyX/enemyY are the centers of the respective sprites.
   */
  processEvent(event: CombatEventData, playerX: number, playerY: number, enemyX: number, enemyY: number) {
    const type = event.type;

    if (type === "dodge") {
      this.spawnFloat("DODGE", EVENT_COLORS.dodge, event.sourceIsPlayer ? enemyX : playerX, event.sourceIsPlayer ? enemyY : playerY);
      return;
    }

    if (type === "kill") {
      this.spawnFloat("DEFEATED", EVENT_COLORS.kill, enemyX, enemyY - 20);
      this.addFlash("enemy", 0xff6b6b, 400);
      return;
    }

    if (type === "death") {
      this.spawnFloat("DEATH", EVENT_COLORS.death, playerX, playerY - 20);
      this.addFlash("player", 0xef5350, 400);
      return;
    }

    if (event.damage > 0) {
      const color = EVENT_COLORS[type] ?? EVENT_COLORS.meleeHit;
      const x = event.sourceIsPlayer ? enemyX : playerX;
      const y = event.sourceIsPlayer ? enemyY : playerY;
      this.spawnFloat(`-${event.damage}`, color, x, y);
      this.addFlash(event.sourceIsPlayer ? "enemy" : "player", 0xff6b6b, 200);
    }

    if (event.healing > 0) {
      const color = EVENT_COLORS[type] ?? EVENT_COLORS.heal;
      this.spawnFloat(`+${event.healing}`, color, playerX, playerY);
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

  clear() {
    for (const f of this.floats) {
      this.container.removeChild(f.text);
      f.text.destroy();
    }
    this.floats = [];
    this.flashes = [];
    this.flashGraphics.clear();
  }
}
