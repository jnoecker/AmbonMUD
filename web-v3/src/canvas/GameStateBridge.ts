import type {
  CharacterInfo,
  CombatTarget,
  ContainerContents,
  CraftingNode,
  DialogueState,
  FeaturePopoutFocus,
  GroupInfo,
  MobInfo,
  PuzzleState,
  QuestAvailable,
  QuestEntry,
  RoomFeature,
  RoomMob,
  RoomPlayer,
  RoomItem,
  RoomState,
  ShopState,
  SkillSummary,
  StatusEffect,
  StylistState,
  Vitals,
  WorldTime,
  WorldWeather,
  ZoneEnvironment,
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
  quests: QuestEntry[];
  questsAvailable: QuestAvailable[];
  shop: ShopState | null;
  stylistState: StylistState | null;
  puzzle: PuzzleState | null;
  craftingNodes: CraftingNode[];
  roomFeatures: RoomFeature[];
  containerContents: ContainerContents | null;
  questTargetRoomIds: Set<string>;
  serverAssets: Record<string, string>;
  worldTime: WorldTime | null;
  worldWeather: WorldWeather | null;
  zoneEnvironment: ZoneEnvironment | null;
  /** Player + pet skills indexed by id for fast per-ability visual lookup. */
  skillsById: Map<string, SkillSummary>;
}

export interface PendingCast {
  skillId: string;
  skillName: string;
  cooldownMs: number;
  targetType: string;
}

export const canvasCallbacks: {
  sendCommand: ((command: string) => void) | null;
  prefillCommand: ((text: string) => void) | null;
  openShop: (() => void) | null;
  openAuction: (() => void) | null;
  openPuzzle: (() => void) | null;
  openFeatures: ((preferredType?: FeaturePopoutFocus) => void) | null;
  openBank: (() => void) | null;
  openStylist: (() => void) | null;
  openTrainer: (() => void) | null;
  openCrafting: (() => void) | null;
  openDungeon: (() => void) | null;
  openLottery: (() => void) | null;
  openHousing: (() => void) | null;
  openInn: (() => void) | null;
  openMap: (() => void) | null;
  openRoom: (() => void) | null;
  openQuests: (() => void) | null;
  onTargetSelected: ((targetName: string) => void) | null;
  openVideo: ((videoUrl: string) => void) | null;
  dismissDialogue: (() => void) | null;
  openQuestOffers: ((mobKeyword: string) => void) | null;
  loadZoneMap: ((zone: string, rooms: Array<{ id: string; x: number; y: number; exits: Record<string, string> }>) => void) | null;
  openMobDetail: ((detail: { name: string; description: string; image: string | null }) => void) | null;
  openImagePreview: ((url: string) => void) | null;
} = {
  sendCommand: null,
  prefillCommand: null,
  openShop: null,
  openAuction: null,
  openPuzzle: null,
  openFeatures: null,
  openBank: null,
  openStylist: null,
  openTrainer: null,
  openCrafting: null,
  openDungeon: null,
  openLottery: null,
  openHousing: null,
  openInn: null,
  openMap: null,
  openRoom: null,
  openQuests: null,
  onTargetSelected: null,
  openVideo: null,
  dismissDialogue: null,
  openQuestOffers: null,
  loadZoneMap: null,
  openMobDetail: null,
  openImagePreview: null,
};

export const pendingCastRef: { current: PendingCast | null } = { current: null };

export const gameStateRef: { current: GameStateSnapshot } = {
  current: {
    room: { id: null, title: "-", description: "", exits: {}, mapX: 0, mapY: 0 },
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
    character: { name: "-", gender: "", race: "", className: "", level: null, sprite: null, isStaff: false, autolootEnabled: false, wimpyThresholdPct: 10, isDemo: false },
    mobInfo: [],
    groupInfo: { leader: null, members: [] },
    dialogue: null,
    quests: [],
    questsAvailable: [],
    shop: null,
    stylistState: null,
    puzzle: null,
    craftingNodes: [],
    roomFeatures: [],
    containerContents: null,
    questTargetRoomIds: new Set(),
    serverAssets: {},
    worldTime: null,
    worldWeather: null,
    zoneEnvironment: null,
    skillsById: new Map(),
  },
};
