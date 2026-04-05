import { createContext, useContext, useReducer } from "react";
import type { Dispatch, ReactNode } from "react";
import type {
  CombatLogMessage,
  CombatTarget,
  DuelChallenge,
  DuelState,
  PetState,
  SkillSummary,
  StatusEffect,
} from "../types";

const MAX_COMBAT_LOG = 20;

export interface CombatState {
  combatTarget: CombatTarget | null;
  effects: StatusEffect[];
  skills: SkillSummary[];
  combatLogMessages: CombatLogMessage[];
  duelState: DuelState | null;
  duelChallenge: DuelChallenge | null;
  petState: PetState | null;
}

export type CombatAction =
  | { type: "SET_COMBAT_TARGET"; payload: CombatTarget | null }
  | { type: "UPDATE_COMBAT_TARGET_HP"; payload: { targetId: string; hp: number; maxHp: number } }
  | { type: "CLEAR_COMBAT_TARGET_IF"; payload: string }
  | { type: "SET_EFFECTS"; payload: StatusEffect[] }
  | { type: "SET_SKILLS"; payload: SkillSummary[] }
  | { type: "UPDATE_SKILL_COOLDOWN"; payload: { abilityId: string; cooldownMs: number; receivedAt: number } }
  | { type: "SET_COMBAT_LOG_MESSAGES"; payload: CombatLogMessage[] }
  | { type: "PUSH_COMBAT_LOG_MESSAGE"; payload: CombatLogMessage }
  | { type: "SET_DUEL_STATE"; payload: DuelState | null }
  | { type: "SET_DUEL_CHALLENGE"; payload: DuelChallenge | null }
  | { type: "SET_PET_STATE"; payload: PetState | null }
  | { type: "RESET" };

const INITIAL_COMBAT_STATE: CombatState = {
  combatTarget: null,
  effects: [],
  skills: [],
  combatLogMessages: [],
  duelState: null,
  duelChallenge: null,
  petState: null,
};

function combatReducer(state: CombatState, action: CombatAction): CombatState {
  switch (action.type) {
    case "SET_COMBAT_TARGET":
      return { ...state, combatTarget: action.payload };
    case "UPDATE_COMBAT_TARGET_HP":
      return state.combatTarget && state.combatTarget.targetId === action.payload.targetId
        ? { ...state, combatTarget: { ...state.combatTarget, targetHp: action.payload.hp, targetMaxHp: action.payload.maxHp } }
        : state;
    case "CLEAR_COMBAT_TARGET_IF":
      return state.combatTarget && state.combatTarget.targetId === action.payload
        ? { ...state, combatTarget: null }
        : state;
    case "SET_EFFECTS":
      return { ...state, effects: action.payload };
    case "SET_SKILLS":
      return { ...state, skills: action.payload };
    case "UPDATE_SKILL_COOLDOWN": {
      const { abilityId, cooldownMs, receivedAt } = action.payload;
      return { ...state, skills: state.skills.map((s) => s.id === abilityId ? { ...s, cooldownRemainingMs: cooldownMs, receivedAt } : s) };
    }
    case "SET_COMBAT_LOG_MESSAGES":
      return { ...state, combatLogMessages: action.payload };
    case "PUSH_COMBAT_LOG_MESSAGE": {
      const next = [...state.combatLogMessages, action.payload];
      return { ...state, combatLogMessages: next.length > MAX_COMBAT_LOG ? next.slice(-MAX_COMBAT_LOG) : next };
    }
    case "SET_DUEL_STATE":
      return { ...state, duelState: action.payload };
    case "SET_DUEL_CHALLENGE":
      return { ...state, duelChallenge: action.payload };
    case "SET_PET_STATE":
      return { ...state, petState: action.payload };
    case "RESET":
      return INITIAL_COMBAT_STATE;
  }
}

const CombatStateContext = createContext<CombatState>(INITIAL_COMBAT_STATE);
const CombatDispatchContext = createContext<Dispatch<CombatAction>>(() => {});

export function CombatProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(combatReducer, INITIAL_COMBAT_STATE);
  return (
    <CombatStateContext value={state}>
      <CombatDispatchContext value={dispatch}>
        {children}
      </CombatDispatchContext>
    </CombatStateContext>
  );
}

export function useCombatState(): CombatState {
  return useContext(CombatStateContext);
}

export function useCombatDispatch(): Dispatch<CombatAction> {
  return useContext(CombatDispatchContext);
}
