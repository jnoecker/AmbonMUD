/**
 * Pure timing logic for deferring a kill animation when a damaging hit was
 * just processed in the same frame.  Split out from CombatAnimator so it
 * has no PixiJS dependency and can be unit-tested directly.  See GH #1034:
 * one-shotting a 1-HP enemy must still render an attack round.
 */

export interface KillDeferralState<T> {
  /**
   * Time (ms) since the last damaging hit was registered.  Starts at
   * POSITIVE_INFINITY so the first kill of a new scene is not deferred
   * unnecessarily.
   */
  msSinceLastDamagingHit: number;
  /**
   * Kill payload waiting for the post-roll delay to elapse, or null.
   */
  pending: { remainingMs: number; payload: T } | null;
}

/** Initial, empty deferral state. */
export function createKillDeferralState<T>(): KillDeferralState<T> {
  return {
    msSinceLastDamagingHit: Number.POSITIVE_INFINITY,
    pending: null,
  };
}

/** Register a damaging hit at "now". */
export function registerDamagingHit(state: KillDeferralState<unknown>): void {
  state.msSinceLastDamagingHit = 0;
}

/**
 * Decide what to do with an incoming kill event.  Returns either "start
 * immediately" (no recent hit) or "defer" with a remaining delay.
 *
 * @param state   current deferral state (not mutated)
 * @param postRollMs minimum ms a preceding hit must play before the kill
 *                   animation may start
 */
export function decideKillTiming(
  state: Readonly<KillDeferralState<unknown>>,
  postRollMs: number,
): { kind: "start" } | { kind: "defer"; remainingMs: number } {
  if (state.msSinceLastDamagingHit < postRollMs) {
    return {
      kind: "defer",
      remainingMs: postRollMs - state.msSinceLastDamagingHit,
    };
  }
  return { kind: "start" };
}

/**
 * Tick the deferral state forward by deltaMs.  Returns a payload iff a
 * pending kill has finished its post-roll and should now start animating.
 */
export function tickKillDeferral<T>(
  state: KillDeferralState<T>,
  deltaMs: number,
  postRollMs: number,
): T | null {
  if (state.msSinceLastDamagingHit !== Number.POSITIVE_INFINITY) {
    state.msSinceLastDamagingHit += deltaMs;
    if (state.msSinceLastDamagingHit > postRollMs * 2) {
      state.msSinceLastDamagingHit = Number.POSITIVE_INFINITY;
    }
  }

  if (state.pending) {
    state.pending.remainingMs -= deltaMs;
    if (state.pending.remainingMs <= 0) {
      const payload = state.pending.payload;
      state.pending = null;
      return payload;
    }
  }
  return null;
}

/** Reset to initial state. */
export function resetKillDeferral(state: KillDeferralState<unknown>): void {
  state.msSinceLastDamagingHit = Number.POSITIVE_INFINITY;
  state.pending = null;
}
