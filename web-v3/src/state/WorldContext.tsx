import { createContext, useContext, useReducer } from "react";
import type { Dispatch, ReactNode } from "react";
import type {
  DialogueState,
  DungeonInfo,
  MobInfo,
  RoomFeature,
  RoomItem,
  RoomMob,
  RoomPlayer,
  RoomState,
  WorldEvent,
  WorldTime,
  WorldWeather,
  ZoneInstances,
} from "../types";
import { EMPTY_ROOM } from "../constants";

export interface WorldState {
  room: RoomState;
  players: RoomPlayer[];
  mobs: RoomMob[];
  roomItems: RoomItem[];
  mobInfo: MobInfo[];
  roomFeatures: RoomFeature[];
  dialogue: DialogueState | null;
  zoneInstances: ZoneInstances;
  worldTime: WorldTime | null;
  worldWeather: WorldWeather | null;
  worldEvents: WorldEvent[];
  dungeonInfo: DungeonInfo | null;
}

export type WorldAction =
  | { type: "SET_ROOM"; payload: RoomState }
  | { type: "SET_PLAYERS"; payload: RoomPlayer[] }
  | { type: "ADD_PLAYER"; payload: RoomPlayer }
  | { type: "REMOVE_PLAYER"; payload: string }
  | { type: "SET_MOBS"; payload: RoomMob[] }
  | { type: "ADD_MOB"; payload: RoomMob }
  | { type: "UPDATE_MOB"; payload: RoomMob }
  | { type: "REMOVE_MOB"; payload: string }
  | { type: "SET_ROOM_ITEMS"; payload: RoomItem[] }
  | { type: "SET_MOB_INFO"; payload: MobInfo[] }
  | { type: "SET_ROOM_FEATURES"; payload: RoomFeature[] }
  | { type: "SET_DIALOGUE"; payload: DialogueState | null }
  | { type: "SET_ZONE_INSTANCES"; payload: ZoneInstances }
  | { type: "SET_WORLD_TIME"; payload: WorldTime | null }
  | { type: "SET_WORLD_WEATHER"; payload: WorldWeather | null }
  | { type: "SET_WORLD_EVENTS"; payload: WorldEvent[] }
  | { type: "SET_DUNGEON_INFO"; payload: DungeonInfo | null }
  | { type: "RESET" };

const INITIAL_WORLD_STATE: WorldState = {
  room: EMPTY_ROOM,
  players: [],
  mobs: [],
  roomItems: [],
  mobInfo: [],
  roomFeatures: [],
  dialogue: null,
  zoneInstances: { zone: null, currentEngineId: null, instances: [] },
  worldTime: null,
  worldWeather: null,
  worldEvents: [],
  dungeonInfo: null,
};

function worldReducer(state: WorldState, action: WorldAction): WorldState {
  switch (action.type) {
    case "SET_ROOM":
      return { ...state, room: action.payload };
    case "SET_PLAYERS":
      return { ...state, players: action.payload };
    case "ADD_PLAYER":
      return { ...state, players: [...state.players, action.payload] };
    case "REMOVE_PLAYER":
      return { ...state, players: state.players.filter((p) => p.name !== action.payload) };
    case "SET_MOBS":
      return { ...state, mobs: action.payload };
    case "ADD_MOB":
      return { ...state, mobs: [...state.mobs, action.payload] };
    case "UPDATE_MOB":
      return { ...state, mobs: state.mobs.map((m) => (m.id === action.payload.id ? action.payload : m)) };
    case "REMOVE_MOB":
      return { ...state, mobs: state.mobs.filter((m) => m.id !== action.payload) };
    case "SET_ROOM_ITEMS":
      return { ...state, roomItems: action.payload };
    case "SET_MOB_INFO":
      return { ...state, mobInfo: action.payload };
    case "SET_ROOM_FEATURES":
      return { ...state, roomFeatures: action.payload };
    case "SET_DIALOGUE":
      return { ...state, dialogue: action.payload };
    case "SET_ZONE_INSTANCES":
      return { ...state, zoneInstances: action.payload };
    case "SET_WORLD_TIME":
      return { ...state, worldTime: action.payload };
    case "SET_WORLD_WEATHER":
      return { ...state, worldWeather: action.payload };
    case "SET_WORLD_EVENTS":
      return { ...state, worldEvents: action.payload };
    case "SET_DUNGEON_INFO":
      return { ...state, dungeonInfo: action.payload };
    case "RESET":
      return INITIAL_WORLD_STATE;
  }
}

const WorldStateContext = createContext<WorldState>(INITIAL_WORLD_STATE);
const WorldDispatchContext = createContext<Dispatch<WorldAction>>(() => {});

export function WorldProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(worldReducer, INITIAL_WORLD_STATE);
  return (
    <WorldStateContext value={state}>
      <WorldDispatchContext value={dispatch}>
        {children}
      </WorldDispatchContext>
    </WorldStateContext>
  );
}

export function useWorldState(): WorldState {
  return useContext(WorldStateContext);
}

export function useWorldDispatch(): Dispatch<WorldAction> {
  return useContext(WorldDispatchContext);
}
