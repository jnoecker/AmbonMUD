import type { CombatEventData, GainEvent } from "../types";

export interface CanvasEventQueue {
  combatEvents: CombatEventData[];
  gainEvents: GainEvent[];
  push(event: CombatEventData | GainEvent): void;
  drain(): { combat: CombatEventData[]; gains: GainEvent[] };
}

export const canvasEvents: CanvasEventQueue = {
  combatEvents: [],
  gainEvents: [],

  push(event: CombatEventData | GainEvent) {
    if ("type" in event && "damage" in event) {
      this.combatEvents.push(event as CombatEventData);
    } else {
      this.gainEvents.push(event as GainEvent);
    }
  },

  drain() {
    const combat = this.combatEvents.splice(0);
    const gains = this.gainEvents.splice(0);
    return { combat, gains };
  },
};
