import { Container, Graphics, Text } from "pixi.js";
import type { StatusEffect } from "../../types";

const ICON_SIZE = 20;
const ICON_GAP = 4;
const BUFF_BORDER = 0x64b5f6;
const DEBUFF_BORDER = 0xef5350;
const ICON_BG = 0x2a3149;

const DEBUFF_TYPES = new Set(["dot", "stun", "root", "slow", "weaken", "blind", "poison", "curse"]);

export class StatusEffectDisplay {
  readonly container = new Container();

  private lastKey = "";

  update(effects: StatusEffect[], centerX: number, y: number) {
    const key = effects.map((e) => `${e.name}:${e.stacks}:${e.type}`).join("|") + `@${centerX},${y}`;
    if (key === this.lastKey) return;
    this.lastKey = key;
    this.rebuild(effects, centerX, y);
  }

  private rebuild(effects: StatusEffect[], centerX: number, y: number) {
    this.container.removeChildren();

    if (effects.length === 0) return;

    const totalWidth = effects.length * (ICON_SIZE + ICON_GAP) - ICON_GAP;
    let x = centerX - totalWidth / 2;

    for (const effect of effects) {
      const isDebuff = DEBUFF_TYPES.has(effect.type.toLowerCase());
      const borderColor = isDebuff ? DEBUFF_BORDER : BUFF_BORDER;

      const bg = new Graphics();
      bg.roundRect(x, y, ICON_SIZE, ICON_SIZE, 3);
      bg.fill({ color: ICON_BG, alpha: 0.9 });
      bg.roundRect(x, y, ICON_SIZE, ICON_SIZE, 3);
      bg.stroke({ color: borderColor, width: 1.5 });
      this.container.addChild(bg);

      // First letter of effect name as icon
      const initial = effect.name.charAt(0).toUpperCase();
      const label = new Text({
        text: initial,
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 10, fill: isDebuff ? "#ef5350" : "#64b5f6", fontWeight: "bold" },
      });
      label.anchor.set(0.5);
      label.x = x + ICON_SIZE / 2;
      label.y = y + ICON_SIZE / 2;
      this.container.addChild(label);

      if (effect.stacks > 1) {
        const stackText = new Text({
          text: `${effect.stacks}`,
          style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 8, fill: "#d8dcef" },
        });
        stackText.anchor.set(1, 1);
        stackText.x = x + ICON_SIZE - 1;
        stackText.y = y + ICON_SIZE - 1;
        this.container.addChild(stackText);
      }

      x += ICON_SIZE + ICON_GAP;
    }
  }

  destroy() {
    this.container.destroy({ children: true });
  }
}
