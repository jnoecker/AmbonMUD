import type {
  CharacterInfo,
  CombatTarget,
  ContainerContents,
  CraftingNode,
  DialogueState,
  FeaturePopoutFocus,
  GroupInfo,
  ItemEntry,
  MobInfo,
  MonsterEntry,
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
  ZoneMapRoomData,
  BorderStub,
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
  /** True while the monster-manual panel is open — it renders dialogue itself,
   *  so the in-canvas DialogueOverlay must yield to avoid a doubled window. */
  monsterPanelOpen: boolean;
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
  /**
   * True while this player has a music-box song playing. Player-scoped and
   * room-independent — keeps the kiosk badge in the rail as the song follows
   * them out of the room it started in.
   */
  musicBoxPlaying: boolean;
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
  openFlight: (() => void) | null;
  openBoat: (() => void) | null;
  openTrainer: (() => void) | null;
  openCrafting: (() => void) | null;
  openDungeon: (() => void) | null;
  openLottery: (() => void) | null;
  openDice: (() => void) | null;
  openJukebox: (() => void) | null;
  openMusicBox: (() => void) | null;
  openHousing: (() => void) | null;
  openInn: (() => void) | null;
  openMail: (() => void) | null;
  openMap: (() => void) | null;
  openRoom: (() => void) | null;
  openCharacter: (() => void) | null;
  openQuests: (() => void) | null;
  onTargetSelected: ((targetName: string) => void) | null;
  openVideo: ((videoUrl: string) => void) | null;
  dismissDialogue: (() => void) | null;
  openQuestOffers: ((mobKeyword: string) => void) | null;
  loadZoneMap: ((zone: string, rooms: ZoneMapRoomData[], border: BorderStub[]) => void) | null;
  openMobDetail: ((detail: { name: string; description: string; image: string | null }) => void) | null;
  openImagePreview: ((url: string) => void) | null;
  /** Open the monster-manual / bestiary panel for a clicked creature. */
  openMonsterManual: ((entry: MonsterEntry) => void) | null;
  /** Open the player field-manual card for a clicked player in the room. */
  openPlayerCard: ((player: RoomPlayer) => void) | null;
  /** Open the parchment item card for a clicked room item. */
  openItemManual: ((entry: ItemEntry) => void) | null;
  /** Staff: open the admin console (the canvas STAFF button). */
  openAdminPanel: (() => void) | null;
  /** Staff: toggle invisibility (the canvas eye button). */
  toggleInvis: (() => void) | null;
  /** Demo characters: open the Save Your Character (claim) window. */
  openClaim: (() => void) | null;
} = {
  sendCommand: null,
  openAdminPanel: null,
  toggleInvis: null,
  openClaim: null,
  prefillCommand: null,
  openPlayerCard: null,
  openShop: null,
  openAuction: null,
  openPuzzle: null,
  openFeatures: null,
  openBank: null,
  openStylist: null,
  openFlight: null,
  openBoat: null,
  openTrainer: null,
  openCrafting: null,
  openDungeon: null,
  openLottery: null,
  openDice: null,
  openJukebox: null,
  openMusicBox: null,
  openHousing: null,
  openInn: null,
  openMail: null,
  openMap: null,
  openRoom: null,
  openCharacter: null,
  openQuests: null,
  onTargetSelected: null,
  openVideo: null,
  dismissDialogue: null,
  openQuestOffers: null,
  loadZoneMap: null,
  openMobDetail: null,
  openImagePreview: null,
  openMonsterManual: null,
  openItemManual: null,
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
    character: { name: "-", gender: "", race: "", className: "", level: null, sprite: null, isStaff: false, title: null, autolootEnabled: false, autopeekEnabled: false, screenReaderEnabled: false, wimpyThresholdPct: 10, isDemo: false },
    mobInfo: [],
    groupInfo: { leader: null, members: [] },
    dialogue: null,
    monsterPanelOpen: false,
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
    musicBoxPlaying: false,
  },
};
