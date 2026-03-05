import { Container, Text } from "pixi.js";
import type { GainEvent } from "../../types";

const FLOAT_SPEED = 40; // pixels per second
const LIFETIME_MS = 1500;

interface ActivePopup {
  text: Text;
  elapsed: number;
}

const GAIN_COLORS: Record<string, string> = {
  xp: "#b9aed8",
  gold: "#f0c674",
  level: "#81d4fa",
};

export class GainPopupSystem {
  readonly container = new Container();
  private popups: ActivePopup[] = [];

  spawn(event: GainEvent, x: number, y: number) {
    const color = GAIN_COLORS[event.type] ?? "#d8dcef";
    let display: string;

    if (event.type === "level") {
      display = "Level Up!";
    } else {
      const label = event.type === "xp" ? "XP" : event.type === "gold" ? "Gold" : event.type;
      display = `+${event.amount} ${label}`;
    }

    const text = new Text({
      text: display,
      style: {
        fontFamily: "JetBrains Mono, Cascadia Mono, monospace",
        fontSize: event.type === "level" ? 16 : 13,
        fill: color,
        fontWeight: event.type === "level" ? "bold" : "normal",
      },
    });
    text.anchor.set(0.5);
    text.x = x;
    text.y = y;

    this.container.addChild(text);
    this.popups.push({ text, elapsed: 0 });
  }

  update(deltaMs: number) {
    for (let i = this.popups.length - 1; i >= 0; i--) {
      const popup = this.popups[i];
      popup.elapsed += deltaMs;
      popup.text.y -= (FLOAT_SPEED * deltaMs) / 1000;
      popup.text.alpha = Math.max(0, 1 - popup.elapsed / LIFETIME_MS);

      if (popup.elapsed >= LIFETIME_MS) {
        this.container.removeChild(popup.text);
        popup.text.destroy();
        this.popups.splice(i, 1);
      }
    }
  }

  clear() {
    for (const popup of this.popups) {
      this.container.removeChild(popup.text);
      popup.text.destroy();
    }
    this.popups = [];
  }
}
