import { describe, expect, test } from "bun:test";
import { applyGmcpPackage } from "../src/gmcp/applyGmcpPackage";
import type { RecallState } from "../src/types";

function recallCtx() {
  let recall: RecallState | null = null;
  const toasts: string[] = [];
  const ctx = {
    setRecallState: (next: RecallState | null | ((prev: RecallState | null) => RecallState | null)) => {
      recall = typeof next === "function" ? next(recall) : next;
    },
    setToast: (t: string | null) => { if (t) toasts.push(t); },
    pushUiFeedback: () => {},
  } as unknown as Parameters<typeof applyGmcpPackage>[2];
  return { ctx, get recall() { return recall; }, toasts };
}

describe("Char.Recall cooldown", () => {
  test("a remaining cooldown becomes a client-clock deadline", () => {
    const s = recallCtx();
    const before = Date.now();
    applyGmcpPackage("Char.Recall", { roomId: "town:inn", roomTitle: "The Inn", cooldownRemainingMs: 45_000 }, s.ctx);
    expect(s.recall?.roomTitle).toBe("The Inn");
    expect(s.recall?.cooldownUntilMs).toBeGreaterThanOrEqual(before + 45_000);
  });

  test("a ready recall has no deadline", () => {
    const s = recallCtx();
    applyGmcpPackage("Char.Recall", { roomId: "town:inn", roomTitle: "The Inn", cooldownRemainingMs: 0 }, s.ctx);
    expect(s.recall?.cooldownUntilMs).toBeNull();
  });

  test("a refused early recall is toasted", () => {
    const s = recallCtx();
    applyGmcpPackage("UI.Feedback", { type: "error", message: "You need to rest... (42 seconds remaining)", code: "RECALL_COOLDOWN", scope: "recall" }, s.ctx);
    expect(s.toasts).toEqual(["You need to rest... (42 seconds remaining)"]);
  });
});
