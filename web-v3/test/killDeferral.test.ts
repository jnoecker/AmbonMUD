import { describe, expect, test } from "bun:test";
import {
  createKillDeferralState,
  decideKillTiming,
  registerDamagingHit,
  resetKillDeferral,
  tickKillDeferral,
} from "../src/canvas/systems/killDeferral";

const POSTROLL = 280;

interface Payload {
  id: string;
}

describe("killDeferral (GH #1034)", () => {
  test("kill with no preceding hit starts immediately", () => {
    const state = createKillDeferralState<Payload>();
    expect(decideKillTiming(state, POSTROLL).kind).toBe("start");
  });

  test("kill arriving same-frame as a hit defers for the full post-roll", () => {
    const state = createKillDeferralState<Payload>();
    registerDamagingHit(state);

    const decision = decideKillTiming(state, POSTROLL);
    expect(decision.kind).toBe("defer");
    if (decision.kind === "defer") {
      expect(decision.remainingMs).toBe(POSTROLL);
    }
  });

  test("kill arriving mid-post-roll defers only the remaining time", () => {
    const state = createKillDeferralState<Payload>();
    registerDamagingHit(state);
    tickKillDeferral(state, 100, POSTROLL); // 100ms has elapsed since the hit

    const decision = decideKillTiming(state, POSTROLL);
    expect(decision.kind).toBe("defer");
    if (decision.kind === "defer") {
      expect(decision.remainingMs).toBe(POSTROLL - 100);
    }
  });

  test("kill arriving after post-roll has already elapsed starts immediately", () => {
    const state = createKillDeferralState<Payload>();
    registerDamagingHit(state);
    for (let t = 0; t < POSTROLL + 50; t += 16) {
      tickKillDeferral(state, 16, POSTROLL);
    }

    expect(decideKillTiming(state, POSTROLL).kind).toBe("start");
  });

  test("tickKillDeferral releases a pending kill once its timer expires", () => {
    const state = createKillDeferralState<Payload>();
    state.pending = { remainingMs: POSTROLL, payload: { id: "crane" } };

    // First partial tick — still waiting.
    expect(tickKillDeferral(state, 100, POSTROLL)).toBeNull();
    expect(state.pending).not.toBeNull();

    // Next tick completes the post-roll — payload released, queue cleared.
    const ready = tickKillDeferral(state, POSTROLL, POSTROLL);
    expect(ready).not.toBeNull();
    expect(ready?.id).toBe("crane");
    expect(state.pending).toBeNull();
  });

  test("last-hit timer stops growing unboundedly after 2× post-roll", () => {
    const state = createKillDeferralState<Payload>();
    registerDamagingHit(state);
    for (let i = 0; i < 100; i++) {
      tickKillDeferral(state, 100, POSTROLL);
    }
    // Value must have been clamped back to POSITIVE_INFINITY.
    expect(state.msSinceLastDamagingHit).toBe(Number.POSITIVE_INFINITY);
  });

  test("resetKillDeferral clears both fields", () => {
    const state = createKillDeferralState<Payload>();
    registerDamagingHit(state);
    state.pending = { remainingMs: 50, payload: { id: "x" } };

    resetKillDeferral(state);

    expect(state.msSinceLastDamagingHit).toBe(Number.POSITIVE_INFINITY);
    expect(state.pending).toBeNull();
  });
});
