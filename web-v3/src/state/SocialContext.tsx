import { createContext, useContext, useReducer } from "react";
import type { Dispatch, ReactNode } from "react";
import type {
  ChatChannel,
  ChatMessage,
  FriendEntry,
  GroupInfo,
  GuildHallInfo,
  GuildInfo,
  GuildMemberEntry,
  LotteryInfo,
  PendingGroupInvite,
  PendingGuildInvite,
  WhoPlayer,
} from "../types";
import { MAX_CHAT_MESSAGES_PER_CHANNEL } from "../constants";

function createEmptyChatByChannel(): Record<ChatChannel, ChatMessage[]> {
  return {
    say: [],
    tell: [],
    gossip: [],
    shout: [],
    ooc: [],
    gtell: [],
    gchat: [],
  };
}

export interface SocialState {
  chatByChannel: Record<ChatChannel, ChatMessage[]>;
  activeChatChannel: ChatChannel;
  groupInfo: GroupInfo;
  pendingGroupInvite: PendingGroupInvite | null;
  guildInfo: GuildInfo;
  pendingGuildInvite: PendingGuildInvite | null;
  guildMembers: GuildMemberEntry[];
  guildHall: GuildHallInfo | null;
  friends: FriendEntry[];
  whoPlayers: WhoPlayer[];
  lotteryInfo: LotteryInfo | null;
}

export type SocialAction =
  | { type: "SET_CHAT_BY_CHANNEL"; payload: Record<ChatChannel, ChatMessage[]> }
  | { type: "SET_ACTIVE_CHAT_CHANNEL"; payload: ChatChannel }
  | { type: "PUSH_CHAT_MESSAGE"; payload: ChatMessage }
  | { type: "SET_GROUP_INFO"; payload: GroupInfo }
  | { type: "SET_PENDING_GROUP_INVITE"; payload: PendingGroupInvite | null }
  | { type: "SET_GUILD_INFO"; payload: GuildInfo }
  | { type: "SET_PENDING_GUILD_INVITE"; payload: PendingGuildInvite | null }
  | { type: "SET_GUILD_MEMBERS"; payload: GuildMemberEntry[] }
  | { type: "SET_GUILD_HALL"; payload: GuildHallInfo | null }
  | { type: "SET_FRIENDS"; payload: FriendEntry[] }
  | { type: "SET_WHO_PLAYERS"; payload: WhoPlayer[] }
  | { type: "SET_LOTTERY_INFO"; payload: LotteryInfo | null }
  | { type: "RESET" };

const INITIAL_SOCIAL_STATE: SocialState = {
  chatByChannel: createEmptyChatByChannel(),
  activeChatChannel: "say",
  groupInfo: { leader: null, members: [] },
  pendingGroupInvite: null,
  guildInfo: { name: null, tag: null, rank: null, motd: null, memberCount: 0, maxSize: 50 },
  pendingGuildInvite: null,
  guildMembers: [],
  guildHall: null,
  friends: [],
  whoPlayers: [],
  lotteryInfo: null,
};

function socialReducer(state: SocialState, action: SocialAction): SocialState {
  switch (action.type) {
    case "SET_CHAT_BY_CHANNEL":
      return { ...state, chatByChannel: action.payload };
    case "SET_ACTIVE_CHAT_CHANNEL":
      return { ...state, activeChatChannel: action.payload };
    case "PUSH_CHAT_MESSAGE": {
      const msg = action.payload;
      const channel = msg.channel;
      const existing = state.chatByChannel[channel] ?? [];
      const next = [...existing, msg];
      const trimmed = next.length > MAX_CHAT_MESSAGES_PER_CHANNEL ? next.slice(-MAX_CHAT_MESSAGES_PER_CHANNEL) : next;
      return { ...state, chatByChannel: { ...state.chatByChannel, [channel]: trimmed } };
    }
    case "SET_GROUP_INFO":
      return { ...state, groupInfo: action.payload };
    case "SET_PENDING_GROUP_INVITE":
      return { ...state, pendingGroupInvite: action.payload };
    case "SET_GUILD_INFO":
      return { ...state, guildInfo: action.payload };
    case "SET_PENDING_GUILD_INVITE":
      return { ...state, pendingGuildInvite: action.payload };
    case "SET_GUILD_MEMBERS":
      return { ...state, guildMembers: action.payload };
    case "SET_GUILD_HALL":
      return { ...state, guildHall: action.payload };
    case "SET_FRIENDS":
      return { ...state, friends: action.payload };
    case "SET_WHO_PLAYERS":
      return { ...state, whoPlayers: action.payload };
    case "SET_LOTTERY_INFO":
      return { ...state, lotteryInfo: action.payload };
    case "RESET":
      return { ...INITIAL_SOCIAL_STATE, chatByChannel: createEmptyChatByChannel() };
  }
}

const SocialStateContext = createContext<SocialState>(INITIAL_SOCIAL_STATE);
const SocialDispatchContext = createContext<Dispatch<SocialAction>>(() => {});

export function SocialProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(socialReducer, INITIAL_SOCIAL_STATE);
  return (
    <SocialStateContext value={state}>
      <SocialDispatchContext value={dispatch}>
        {children}
      </SocialDispatchContext>
    </SocialStateContext>
  );
}

export function useSocialState(): SocialState {
  return useContext(SocialStateContext);
}

export function useSocialDispatch(): Dispatch<SocialAction> {
  return useContext(SocialDispatchContext);
}
