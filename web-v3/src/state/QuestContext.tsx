import { createContext, useContext, useReducer } from "react";
import type { Dispatch, ReactNode } from "react";
import type {
  AchievementData,
  AutoQuest,
  DailyQuestBoard,
  DailyQuestEntry,
  GlobalQuest,
  QuestAvailable,
  QuestEntry,
  QuestNotification,
} from "../types";

export interface QuestState {
  quests: QuestEntry[];
  questsAvailable: QuestAvailable[];
  achievements: AchievementData;
  dailyQuests: DailyQuestBoard | null;
  weeklyQuests: DailyQuestEntry[];
  autoQuest: AutoQuest | null;
  globalQuest: GlobalQuest | null;
  questNotifications: QuestNotification[];
}

export type QuestAction =
  | { type: "SET_QUESTS"; payload: QuestEntry[] }
  | { type: "SET_QUESTS_AND_FILTER_AVAILABLE"; payload: QuestEntry[] }
  | { type: "UPDATE_QUEST_OBJECTIVE"; payload: { questId: string; objectiveIndex: number; current: number; required: number } }
  | { type: "REMOVE_QUEST"; payload: string }
  | { type: "SET_QUESTS_AVAILABLE"; payload: QuestAvailable[] }
  | { type: "SET_ACHIEVEMENTS"; payload: AchievementData }
  | { type: "SET_DAILY_QUESTS"; payload: DailyQuestBoard | null }
  | { type: "SET_WEEKLY_QUESTS"; payload: DailyQuestEntry[] }
  | { type: "SET_AUTO_QUEST"; payload: AutoQuest | null }
  | { type: "SET_GLOBAL_QUEST"; payload: GlobalQuest | null }
  | { type: "SET_QUEST_NOTIFICATIONS"; payload: QuestNotification[] }
  | { type: "PUSH_QUEST_NOTIFICATION"; payload: QuestNotification }
  | { type: "DISMISS_QUEST_NOTIFICATION"; payload: string }
  | { type: "RESET" };

const INITIAL_QUEST_STATE: QuestState = {
  quests: [],
  questsAvailable: [],
  achievements: { completed: [], inProgress: [] },
  dailyQuests: null,
  weeklyQuests: [],
  autoQuest: null,
  globalQuest: null,
  questNotifications: [],
};

function questReducer(state: QuestState, action: QuestAction): QuestState {
  switch (action.type) {
    case "SET_QUESTS":
      return { ...state, quests: action.payload };
    case "SET_QUESTS_AND_FILTER_AVAILABLE": {
      const activeIds = new Set(action.payload.map((q) => q.id));
      const filtered = state.questsAvailable.filter((q) => !activeIds.has(q.id));
      return { ...state, quests: action.payload, questsAvailable: filtered };
    }
    case "UPDATE_QUEST_OBJECTIVE": {
      const { questId, objectiveIndex, current, required } = action.payload;
      return {
        ...state,
        quests: state.quests.map((q) => {
          if (q.id !== questId) return q;
          const objectives = q.objectives.map((o, i) => i === objectiveIndex ? { ...o, current, required } : o);
          return { ...q, objectives };
        }),
      };
    }
    case "REMOVE_QUEST":
      return { ...state, quests: state.quests.filter((q) => q.id !== action.payload) };
    case "SET_QUESTS_AVAILABLE":
      return { ...state, questsAvailable: action.payload };
    case "SET_ACHIEVEMENTS":
      return { ...state, achievements: action.payload };
    case "SET_DAILY_QUESTS":
      return { ...state, dailyQuests: action.payload };
    case "SET_WEEKLY_QUESTS":
      return { ...state, weeklyQuests: action.payload };
    case "SET_AUTO_QUEST":
      return { ...state, autoQuest: action.payload };
    case "SET_GLOBAL_QUEST":
      return { ...state, globalQuest: action.payload };
    case "SET_QUEST_NOTIFICATIONS":
      return { ...state, questNotifications: action.payload };
    case "PUSH_QUEST_NOTIFICATION":
      return { ...state, questNotifications: [...state.questNotifications, action.payload] };
    case "DISMISS_QUEST_NOTIFICATION":
      return { ...state, questNotifications: state.questNotifications.filter((n) => n.id !== action.payload) };
    case "RESET":
      return INITIAL_QUEST_STATE;
  }
}

const QuestStateContext = createContext<QuestState>(INITIAL_QUEST_STATE);
const QuestDispatchContext = createContext<Dispatch<QuestAction>>(() => {});

export function QuestProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(questReducer, INITIAL_QUEST_STATE);
  return (
    <QuestStateContext value={state}>
      <QuestDispatchContext value={dispatch}>
        {children}
      </QuestDispatchContext>
    </QuestStateContext>
  );
}

export function useQuestState(): QuestState {
  return useContext(QuestStateContext);
}

export function useQuestDispatch(): Dispatch<QuestAction> {
  return useContext(QuestDispatchContext);
}
