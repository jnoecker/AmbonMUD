import { createContext, useContext, useReducer } from "react";
import type { Dispatch, ReactNode } from "react";
import type {
  ContainerContents,
  FactionStanding,
  FriendNotification,
  HousingInfo,
  LayoutMode,
  LeaderboardData,
  LookTargetInfo,
  MailEntry,
  MailMessage,
  PopoutPanel,
  StaffMobZone,
  StaffWorldZone,
  TrainerData,
} from "../types";

export interface UIState {
  activePopout: PopoutPanel;
  showAdminPanel: boolean;
  showCommandPalette: boolean;
  terminalVisible: boolean;
  terminalOpaque: boolean;
  layoutMode: LayoutMode;
  composerValue: string;
  videoUrl: string | null;
  videoClosing: boolean;
  toast: string | null;
  lookTarget: LookTargetInfo | null;
  broadcast: { sender: string; message: string } | null;
  staffInvisible: boolean;
  possessing: string | null;
  containerContents: ContainerContents | null;
  staffWorldInfo: StaffWorldZone[];
  staffMobTemplates: StaffMobZone[];
  mailInbox: MailEntry[] | null;
  mailMessage: MailMessage | null;
  friendNotifications: FriendNotification[];
  trainer: TrainerData | null;
  housing: HousingInfo | null;
  leaderboard: Record<string, LeaderboardData>;
  factions: FactionStanding[];
}

export type UIAction =
  | { type: "SET_ACTIVE_POPOUT"; payload: PopoutPanel }
  | { type: "CLOSE_POPOUT_IF"; payload: PopoutPanel }
  | { type: "SET_SHOW_ADMIN_PANEL"; payload: boolean }
  | { type: "SET_SHOW_COMMAND_PALETTE"; payload: boolean }
  | { type: "SET_TERMINAL_VISIBLE"; payload: boolean }
  | { type: "SET_TERMINAL_OPAQUE"; payload: boolean }
  | { type: "SET_LAYOUT_MODE"; payload: LayoutMode }
  | { type: "SET_COMPOSER_VALUE"; payload: string }
  | { type: "SET_VIDEO_URL"; payload: string | null }
  | { type: "SET_VIDEO_CLOSING"; payload: boolean }
  | { type: "SET_TOAST"; payload: string | null }
  | { type: "SET_LOOK_TARGET"; payload: LookTargetInfo | null }
  | { type: "SET_BROADCAST"; payload: { sender: string; message: string } | null }
  | { type: "PUSH_BROADCAST"; payload: { sender: string; message: string } }
  | { type: "SET_STAFF_INVISIBLE"; payload: boolean }
  | { type: "SET_POSSESSING"; payload: string | null }
  | { type: "SET_CONTAINER_CONTENTS"; payload: ContainerContents | null }
  | { type: "SET_STAFF_WORLD_INFO"; payload: StaffWorldZone[] }
  | { type: "SET_STAFF_MOB_TEMPLATES"; payload: StaffMobZone[] }
  | { type: "SET_MAIL_INBOX"; payload: MailEntry[] | null }
  | { type: "SET_MAIL_MESSAGE"; payload: MailMessage | null }
  | { type: "SET_FRIEND_NOTIFICATIONS"; payload: FriendNotification[] }
  | { type: "PUSH_FRIEND_NOTIFICATION"; payload: FriendNotification }
  | { type: "SET_TRAINER"; payload: TrainerData | null }
  | { type: "SET_HOUSING"; payload: HousingInfo | null }
  | { type: "SET_LEADERBOARD"; payload: Record<string, LeaderboardData> }
  | { type: "MERGE_LEADERBOARD"; payload: { category: string; data: LeaderboardData } }
  | { type: "SET_FACTIONS"; payload: FactionStanding[] }
  | { type: "RESET" };

function readInitialLayoutMode(): LayoutMode {
  try {
    return (localStorage.getItem("ambonmud_layout_mode") as LayoutMode) ?? "auto";
  } catch {
    return "auto";
  }
}

const INITIAL_UI_STATE: UIState = {
  activePopout: null,
  showAdminPanel: false,
  showCommandPalette: false,
  terminalVisible: false,
  terminalOpaque: false,
  layoutMode: readInitialLayoutMode(),
  composerValue: "",
  videoUrl: null,
  videoClosing: false,
  toast: null,
  lookTarget: null,
  broadcast: null,
  staffInvisible: false,
  possessing: null,
  containerContents: null,
  staffWorldInfo: [],
  staffMobTemplates: [],
  mailInbox: null,
  mailMessage: null,
  friendNotifications: [],
  trainer: null,
  housing: null,
  leaderboard: {},
  factions: [],
};

function uiReducer(state: UIState, action: UIAction): UIState {
  switch (action.type) {
    case "SET_ACTIVE_POPOUT":
      return { ...state, activePopout: action.payload };
    case "CLOSE_POPOUT_IF":
      return state.activePopout === action.payload ? { ...state, activePopout: null } : state;
    case "SET_SHOW_ADMIN_PANEL":
      return { ...state, showAdminPanel: action.payload };
    case "SET_SHOW_COMMAND_PALETTE":
      return { ...state, showCommandPalette: action.payload };
    case "SET_TERMINAL_VISIBLE":
      return { ...state, terminalVisible: action.payload };
    case "SET_TERMINAL_OPAQUE":
      return { ...state, terminalOpaque: action.payload };
    case "SET_LAYOUT_MODE":
      return { ...state, layoutMode: action.payload };
    case "SET_COMPOSER_VALUE":
      return { ...state, composerValue: action.payload };
    case "SET_VIDEO_URL":
      return { ...state, videoUrl: action.payload };
    case "SET_VIDEO_CLOSING":
      return { ...state, videoClosing: action.payload };
    case "SET_TOAST":
      return { ...state, toast: action.payload };
    case "SET_LOOK_TARGET":
      return { ...state, lookTarget: action.payload };
    case "SET_BROADCAST":
      return { ...state, broadcast: action.payload };
    case "PUSH_BROADCAST":
      return { ...state, broadcast: action.payload };
    case "SET_STAFF_INVISIBLE":
      return { ...state, staffInvisible: action.payload };
    case "SET_POSSESSING":
      return { ...state, possessing: action.payload };
    case "SET_CONTAINER_CONTENTS":
      return { ...state, containerContents: action.payload };
    case "SET_STAFF_WORLD_INFO":
      return { ...state, staffWorldInfo: action.payload };
    case "SET_STAFF_MOB_TEMPLATES":
      return { ...state, staffMobTemplates: action.payload };
    case "SET_MAIL_INBOX":
      return { ...state, mailInbox: action.payload };
    case "SET_MAIL_MESSAGE":
      return { ...state, mailMessage: action.payload };
    case "SET_FRIEND_NOTIFICATIONS":
      return { ...state, friendNotifications: action.payload };
    case "PUSH_FRIEND_NOTIFICATION":
      return { ...state, friendNotifications: [...state.friendNotifications, action.payload] };
    case "SET_TRAINER":
      return { ...state, trainer: action.payload };
    case "SET_HOUSING":
      return { ...state, housing: action.payload };
    case "SET_LEADERBOARD":
      return { ...state, leaderboard: action.payload };
    case "MERGE_LEADERBOARD":
      return { ...state, leaderboard: { ...state.leaderboard, [action.payload.category]: action.payload.data } };
    case "SET_FACTIONS":
      return { ...state, factions: action.payload };
    case "RESET":
      return { ...INITIAL_UI_STATE, layoutMode: readInitialLayoutMode() };
  }
}

const UIStateContext = createContext<UIState>(INITIAL_UI_STATE);
const UIDispatchContext = createContext<Dispatch<UIAction>>(() => {});

export function UIProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(uiReducer, INITIAL_UI_STATE);
  return (
    <UIStateContext value={state}>
      <UIDispatchContext value={dispatch}>
        {children}
      </UIDispatchContext>
    </UIStateContext>
  );
}

export function useUIState(): UIState {
  return useContext(UIStateContext);
}

export function useUIDispatch(): Dispatch<UIAction> {
  return useContext(UIDispatchContext);
}
