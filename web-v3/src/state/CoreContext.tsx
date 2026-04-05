import { createContext, useContext, useReducer } from "react";
import type { Dispatch, ReactNode } from "react";
import type {
  CharacterInfo,
  CharStats,
  CommandEntry,
  EmotePreset,
  LoginErrorState,
  LoginPromptState,
  PrestigeInfo,
  SpriteList,
  StatusVarLabels,
  Vitals,
} from "../types";
import { DEFAULT_STATUS_VAR_LABELS, EMPTY_CHAR, EMPTY_VITALS } from "../constants";

export interface CoreState {
  vitals: Vitals;
  statusVarLabels: StatusVarLabels;
  character: CharacterInfo;
  charStats: CharStats | null;
  spriteList: SpriteList;
  unlockedClasses: string[];
  loginPrompt: LoginPromptState | null;
  loginError: LoginErrorState | null;
  reconnecting: boolean;
  savedCharacters: string[];
  serverAssets: Record<string, string>;
  serverCommands: CommandEntry[];
  emotePresets: EmotePreset[];
  prestigeInfo: PrestigeInfo | null;
}

export type CoreAction =
  | { type: "SET_VITALS"; payload: Vitals }
  | { type: "SET_STATUS_VAR_LABELS"; payload: StatusVarLabels }
  | { type: "SET_CHARACTER"; payload: CharacterInfo }
  | { type: "SET_CHAR_STATS"; payload: CharStats | null }
  | { type: "SET_SPRITE_LIST"; payload: SpriteList }
  | { type: "SET_UNLOCKED_CLASSES"; payload: string[] }
  | { type: "SET_LOGIN_PROMPT"; payload: LoginPromptState | null }
  | { type: "SET_LOGIN_ERROR"; payload: LoginErrorState | null }
  | { type: "SET_RECONNECTING"; payload: boolean }
  | { type: "SET_SAVED_CHARACTERS"; payload: string[] }
  | { type: "SET_SERVER_ASSETS"; payload: Record<string, string> }
  | { type: "SET_SERVER_COMMANDS"; payload: CommandEntry[] }
  | { type: "SET_EMOTE_PRESETS"; payload: EmotePreset[] }
  | { type: "SET_PRESTIGE_INFO"; payload: PrestigeInfo | null }
  | { type: "RESET" };

const INITIAL_CORE_STATE: CoreState = {
  vitals: EMPTY_VITALS,
  statusVarLabels: DEFAULT_STATUS_VAR_LABELS,
  character: EMPTY_CHAR,
  charStats: null,
  spriteList: { active: null, sprites: [] },
  unlockedClasses: [],
  loginPrompt: null,
  loginError: null,
  reconnecting: false,
  savedCharacters: [],
  serverAssets: {},
  serverCommands: [],
  emotePresets: [],
  prestigeInfo: null,
};

function coreReducer(state: CoreState, action: CoreAction): CoreState {
  switch (action.type) {
    case "SET_VITALS":
      return { ...state, vitals: action.payload };
    case "SET_STATUS_VAR_LABELS":
      return { ...state, statusVarLabels: action.payload };
    case "SET_CHARACTER":
      return { ...state, character: action.payload };
    case "SET_CHAR_STATS":
      return { ...state, charStats: action.payload };
    case "SET_SPRITE_LIST":
      return { ...state, spriteList: action.payload };
    case "SET_UNLOCKED_CLASSES":
      return { ...state, unlockedClasses: action.payload };
    case "SET_LOGIN_PROMPT":
      return { ...state, loginPrompt: action.payload };
    case "SET_LOGIN_ERROR":
      return { ...state, loginError: action.payload };
    case "SET_RECONNECTING":
      return { ...state, reconnecting: action.payload };
    case "SET_SAVED_CHARACTERS":
      return { ...state, savedCharacters: action.payload };
    case "SET_SERVER_ASSETS":
      return { ...state, serverAssets: action.payload };
    case "SET_SERVER_COMMANDS":
      return { ...state, serverCommands: action.payload };
    case "SET_EMOTE_PRESETS":
      return { ...state, emotePresets: action.payload };
    case "SET_PRESTIGE_INFO":
      return { ...state, prestigeInfo: action.payload };
    case "RESET":
      return INITIAL_CORE_STATE;
  }
}

const CoreStateContext = createContext<CoreState>(INITIAL_CORE_STATE);
const CoreDispatchContext = createContext<Dispatch<CoreAction>>(() => {});

export function CoreProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(coreReducer, INITIAL_CORE_STATE);
  return (
    <CoreStateContext value={state}>
      <CoreDispatchContext value={dispatch}>
        {children}
      </CoreDispatchContext>
    </CoreStateContext>
  );
}

export function useCoreState(): CoreState {
  return useContext(CoreStateContext);
}

export function useCoreDispatch(): Dispatch<CoreAction> {
  return useContext(CoreDispatchContext);
}
