import type { ArcanumSketchEvent, CombatEventData, GainEvent } from "../types";

export interface CanvasEventQueue {
  combatEvents: CombatEventData[];
  gainEvents: GainEvent[];
  sketchEvents: ArcanumSketchEvent[];
  push(event: CombatEventData | GainEvent): void;
  pushSketch(event: ArcanumSketchEvent): void;
  drain(): { combat: CombatEventData[]; gains: GainEvent[] };
  drainSketches(): ArcanumSketchEvent[];
}

export const canvasEvents: CanvasEventQueue = {
  combatEvents: [],
  gainEvents: [],
  sketchEvents: [],

  push(event: CombatEventData | GainEvent) {
    if ("type" in event && "damage" in event) {
      this.combatEvents.push(event as CombatEventData);
    } else {
      this.gainEvents.push(event as GainEvent);
    }
  },

  pushSketch(event: ArcanumSketchEvent) {
    this.sketchEvents.push(event);
  },

  drain() {
    const combat = this.combatEvents.splice(0);
    const gains = this.gainEvents.splice(0);
    return { combat, gains };
  },

  drainSketches() {
    return this.sketchEvents.splice(0);
  },
};
