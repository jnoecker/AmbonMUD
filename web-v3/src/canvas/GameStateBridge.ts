import type {
  CharacterInfo,
  CombatTarget,
  DialogueState,
  GroupInfo,
  RoomMob,
  RoomPlayer,
  RoomItem,
  RoomState,
  ShopState,
  StatusEffect,
  Vitals,
  MobInfo,
} from "../types";

export interface GameStateSnapshot {
  room: RoomState;
  vitals: Vitals;
  mobs: RoomMob[];
  players: RoomPlayer[];
  roomItems: RoomItem[];
  combatTarget: CombatTarget | null;
  inCombat: boolean;
  effects: StatusEffect[];
  character: CharacterInfo;
  mobInfo: MobInfo[];
  groupInfo: GroupInfo;
  dialogue: DialogueState | null;
  shop: ShopState | null;
}

export const canvasCallbacks: {
  sendCommand: ((command: string) => void) | null;
  openShop: (() => void) | null;
  openMap: (() => void) | null;
  openRoom: (() => void) | null;
} = {
  sendCommand: null,
  openShop: null,
  openMap: null,
  openRoom: null,
};

export const gameStateRef: { current: GameStateSnapshot } = {
  current: {
    room: { id: null, title: "-", description: "", exits: {} },
    vitals: {
      hp: 0, maxHp: 0, mana: 0, maxMana: 0,
      level: null, xp: 0, xpIntoLevel: 0, xpToNextLevel: null,
      gold: 0, inCombat: false,
    },
    mobs: [],
    players: [],
    roomItems: [],
    combatTarget: null,
    inCombat: false,
    effects: [],
    character: { name: "-", gender: "", race: "", className: "", level: null, sprite: null, isStaff: false },
    mobInfo: [],
    groupInfo: { leader: null, members: [] },
    dialogue: null,
    shop: null,
  },
};
