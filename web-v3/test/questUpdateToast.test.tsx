import { describe, expect, test } from "bun:test";
import { renderToStaticMarkup } from "react-dom/server";
import { QuestCompleteToast } from "../src/components/QuestCompleteToast";
import { applyGmcpPackage } from "../src/gmcp/applyGmcpPackage";
import type { QuestEntry, QuestNotification } from "../src/types";

function questCtx(initial: QuestEntry[]) {
  let quests = initial;
  const notifications: QuestNotification[] = [];
  const ctx = {
    setQuests: (next: QuestEntry[] | ((prev: QuestEntry[]) => QuestEntry[])) => {
      quests = typeof next === "function" ? next(quests) : next;
    },
    pushQuestNotification: (n: QuestNotification) => { notifications.push(n); },
  } as unknown as Parameters<typeof applyGmcpPackage>[2];
  return { ctx, get quests() { return quests; }, notifications };
}

const teaQuest: QuestEntry = {
  id: "town:tea",
  name: "A Cup of Tea",
  description: "Fetch three cups.",
  objectives: [{ description: "Collect 3 cups of tea", current: 1, required: 3 }],
  readyToTurnIn: false,
  giverMobId: "town:innkeeper",
};

describe("Quest.Update toasts", () => {
  test("an objective tick pushes an 'update' notification with the progress", () => {
    const s = questCtx([teaQuest]);
    applyGmcpPackage(
      "Quest.Update",
      {
        questId: "town:tea",
        objectiveIndex: 0,
        current: 2,
        required: 3,
        readyToTurnIn: false,
        questName: "A Cup of Tea",
        objectiveDescription: "Collect 3 cups of tea",
      },
      s.ctx,
    );
    expect(s.quests[0].objectives[0].current).toBe(2);
    expect(s.notifications).toHaveLength(1);
    expect(s.notifications[0].event).toBe("update");
    expect(s.notifications[0].objective).toEqual({ description: "Collect 3 cups of tea", current: 2, required: 3 });
  });

  test("the final tick of a turn-in quest pushes a 'ready' notification", () => {
    const s = questCtx([teaQuest]);
    applyGmcpPackage(
      "Quest.Update",
      {
        questId: "town:tea",
        objectiveIndex: 0,
        current: 3,
        required: 3,
        readyToTurnIn: true,
        questName: "A Cup of Tea",
        objectiveDescription: "Collect 3 cups of tea",
      },
      s.ctx,
    );
    expect(s.quests[0].readyToTurnIn).toBe(true);
    expect(s.notifications[0].event).toBe("ready");
  });

  test("older servers that omit the names still update state without a toast", () => {
    const s = questCtx([teaQuest]);
    applyGmcpPackage(
      "Quest.Update",
      { questId: "town:tea", objectiveIndex: 0, current: 2, required: 3, readyToTurnIn: false },
      s.ctx,
    );
    expect(s.quests[0].objectives[0].current).toBe(2);
    expect(s.notifications).toHaveLength(0);
  });

  test("the toast renders progress and ready-to-turn-in cues", () => {
    const update: QuestNotification = {
      id: "1",
      questId: "town:tea",
      questName: "A Cup of Tea",
      event: "update",
      receivedAt: 0,
      objective: { description: "Collect 3 cups of tea", current: 2, required: 3 },
    };
    const html = renderToStaticMarkup(<QuestCompleteToast notifications={[update]} onDismiss={() => {}} />);
    expect(html).toContain("Quest Progress");
    expect(html).toContain("2/3");

    const ready: QuestNotification = { ...update, id: "2", event: "ready", objective: { ...update.objective!, current: 3 } };
    const readyHtml = renderToStaticMarkup(<QuestCompleteToast notifications={[ready]} onDismiss={() => {}} />);
    expect(readyHtml).toContain("Ready to Turn In");
    expect(readyHtml).toContain("Return to the quest-giver");
  });
});
