import { useCallback, useRef, useState } from "react";
import type { SetStateAction } from "react";
import { applyGmcpPackage } from "../gmcp/applyGmcpPackage";
import { canvasCallbacks } from "../canvas/GameStateBridge";
import { canvasEvents } from "../canvas/CanvasEventBus";
import {
  DEFAULT_SERVER_FEATURES,
  DEFAULT_STATUS_VAR_LABELS,
  EMPTY_CHAR,
  EMPTY_ROOM,
  EMPTY_VITALS,
} from "../constants";
import type {
  AchievementData,
  ActiveSketch,
  ArcanumJournal,
  ArcanumSketchEvent,
  ArcanumStatus,
  AuctionListing,
  AutoQuest,
  BankState,
  CharStats,
  ChatChannel,
  ChatMessage,
  CharacterInfo,
  CombatEventData,
  CombatLogMessage,
  CombatTarget,
  CombatVictoryNotification,
  CommandEntry,
  ContainerContents,
  CraftingNode,
  CraftingRecipe,
  CraftingResult,
  CraftingSkill,
  CurrencyBalance,
  DailyQuestBoard,
  DailyQuestEntry,
  DialogueState,
  DuelChallenge,
  DuelState,
  DungeonCatalogEntry,
  DungeonInfo,
  EmotePreset,
  EquipmentSlotDef,
  FactionActivity,
  FactionStanding,
  FleeNotification,
  FriendEntry,
  FriendNotification,
  GainEvent,
  GlobalQuest,
  GroupInfo,
  GuildHallInfo,
  GuildInfo,
  GuildMemberEntry,
  HousingInfo,
  HousingTemplate,
  ItemSummary,
  LeaderboardData,
  LevelUpNotification,
  LoginErrorState,
  LoginPromptState,
  ConsiderResult,
  LookTargetInfo,
  LotteryInfo,
  DiceGambleResult,
  JukeboxState,
  MusicBoxState,
  MailEntry,
  MailMessage,
  MailNotification,
  MobInfo,
  PendingGroupInvite,
  PendingGuildInvite,
  PetState,
  PopoutPanel,
  PrestigeInfo,
  QuestAvailable,
  QuestEntry,
  QuestNotification,
  RacialProcNotification,
  RoomFeature,
  RoomItem,
  RoomMob,
  RoomPlayer,
  RoomState,
  PuzzleState,
  PuzzleResult,
  ServerFeatures,
  ShopState,
  SkillSummary,
  SpriteList,
  StaffMobZone,
  StaffWorldZone,
  RecallState,
  StatusEffect,
  StatusVarLabels,
  StylistState,
  FlightState,
  TravelStatus,
  BoatState,
  TradeState,
  TrainerData,
  UiFeedbackEntry,
  UiFeedback,
  Vitals,
  WhoPlayer,
  WorldArea,
  WorldEvent,
  WorldTime,
  WorldWeather,
  ZoneCinematicState,
  ZoneEnvironment,
  ZoneInstances,
  CurrencyActivity,
  BorderStub,
} from "../types";

function createEmptyChatByChannel(): Record<ChatChannel, ChatMessage[]> {
  return { say: [], tell: [], gossip: [], shout: [], ooc: [], gtell: [], gchat: [] };
}

let combatLogIdCounter = 0;

function combatEventToLogMessage(event: CombatEventData): CombatLogMessage | null {
  const now = Date.now();
  const id = ++combatLogIdCounter;
  // Server-rendered text (custom config templates, stat-formula suffixes) takes precedence
  // over the hardcoded fallback templates below.
  const styleForType: Record<string, CombatLogMessage["style"]> = {
    meleeHit: "damage",
    petHit: "damage",
    petHurt: "damage",
    abilityHit: "damage",
    heal: "heal",
    hotTick: "heal",
    dotTick: "damage",
    coldDot: "damage",
    dodge: "dodge",
    shieldAbsorb: "info",
    kill: "kill",
    death: "error",
  };
  if (event.text) {
    const style = styleForType[event.type] ?? "info";
    return { id, text: event.text, style, receivedAt: now };
  }
  switch (event.type) {
    case "meleeHit":
      return event.sourceIsPlayer
        ? { id, text: `You hit ${event.targetName} for ${event.damage} damage.`, style: "damage", receivedAt: now }
        : { id, text: `${event.targetName} hits you for ${event.damage} damage!`, style: "damage", receivedAt: now };
    case "petHit":
      return { id, text: `${event.petName ?? "Your pet"} hits ${event.targetName} for ${event.damage} damage.`, style: "damage", receivedAt: now };
    case "petHurt":
      return { id, text: `${event.attackerName} hits ${event.petName ?? "your pet"} for ${event.damage} damage!`, style: "damage", receivedAt: now };
    case "abilityHit":
      return event.sourceIsPlayer
        ? { id, text: `Your ${event.abilityName} hits ${event.targetName} for ${event.damage} damage.`, style: "damage", receivedAt: now }
        : { id, text: `${event.targetName}'s ${event.abilityName} hits you for ${event.damage}!`, style: "damage", receivedAt: now };
    case "heal":
      return { id, text: `${event.abilityName} heals for ${event.healing} HP.`, style: "heal", receivedAt: now };
    case "hotTick":
      return { id, text: `${event.abilityName} restores ${event.healing} HP.`, style: "heal", receivedAt: now };
    case "dotTick":
    case "coldDot":
      return { id, text: `${event.abilityName} deals ${event.damage} damage.`, style: "damage", receivedAt: now };
    case "dodge":
      return event.sourceIsPlayer
        ? { id, text: `${event.targetName} dodges your attack!`, style: "dodge", receivedAt: now }
        : { id, text: "You dodge the attack!", style: "dodge", receivedAt: now };
    case "shieldAbsorb":
      return { id, text: `Shield absorbs ${event.absorbed} damage. (${event.shieldRemaining} remaining)`, style: "info", receivedAt: now };
    case "kill":
      return { id, text: `${event.targetName} has been slain!${event.xpGained > 0 ? ` +${event.xpGained} XP` : ""}${event.goldGained > 0 ? ` +${event.goldGained} gold` : ""}`, style: "kill", receivedAt: now };
    case "death":
      return { id, text: "You have been slain!", style: "error", receivedAt: now };
    default:
      return null;
  }
}

const MAX_COMBAT_LOG = 200;
const MAX_QUEST_NOTIFICATIONS = 5;
const MAX_FRIEND_NOTIFICATIONS = 5;
const MAX_UI_FEEDBACK_FEED = 20;
const MAX_SYSTEM_ACTIVITY = 12;
let systemActivityIdCounter = 0;

export interface MiniMapBridge {
  updateMap: (
    roomId: string,
    exits: Record<string, string>,
    title: string,
    image: string | null,
    mapX: number,
    mapY: number,
    mapZ: number,
    housing?: boolean,
    terrain?: string | null,
  ) => void;
  loadZoneMap: (zone: string, rooms: Array<{ id: string; x: number; y: number; z: number; exits: Record<string, string> }>, border: BorderStub[]) => void;
  resetMap: () => void;
}

export interface AuthRefs {
  resumeTokenRef: React.MutableRefObject<string | null>;
  pendingAuthCharRef: React.MutableRefObject<string | null>;
  sendGmcpRef: React.MutableRefObject<(pkg: string, payload: unknown) => void>;
}

export function useGameState(authRefs: AuthRefs, miniMap: MiniMapBridge) {
  const { resumeTokenRef, pendingAuthCharRef, sendGmcpRef } = authRefs;
  const { updateMap, loadZoneMap, resetMap } = miniMap;
  // ── Core identity ─────────────────────────────────
  const [vitals, setVitals] = useState<Vitals>(EMPTY_VITALS);
  const [statusVarLabels, setStatusVarLabels] = useState<StatusVarLabels>(DEFAULT_STATUS_VAR_LABELS);
  const [character, setCharacter] = useState<CharacterInfo>(EMPTY_CHAR);
  const [room, setRoom] = useState<RoomState>(EMPTY_ROOM);

  // ── Room contents ─────────────────────────────────
  const [players, setPlayers] = useState<RoomPlayer[]>([]);
  const [mobs, setMobs] = useState<RoomMob[]>([]);
  const [roomItems, setRoomItems] = useState<RoomItem[]>([]);
  const [roomFeatures, setRoomFeatures] = useState<RoomFeature[]>([]);
  const [containerContents, setContainerContents] = useState<ContainerContents | null>(null);
  const [mobInfo, setMobInfo] = useState<MobInfo[]>([]);
  // Last room id seen in Room.Info, tracked in a ref so the GMCP handler can
  // detect a room change synchronously — while applying the packet, not later
  // inside a React updater — and clear room-scoped state in packet order.
  const lastRoomIdRef = useRef<string | null>(null);

  // ── Combat ────────────────────────────────────────
  const [effects, setEffects] = useState<StatusEffect[]>([]);
  const [combatTarget, setCombatTarget] = useState<CombatTarget | null>(null);
  const [combatLogMessages, setCombatLogMessages] = useState<CombatLogMessage[]>([]);
  const combatEventsRef = useRef<CombatEventData[]>([]);
  const gainEventsRef = useRef<GainEvent[]>([]);
  const [levelUpNotification, setLevelUpNotification] = useState<LevelUpNotification | null>(null);
  const levelUpIdRef = useRef(0);
  const [combatVictoryNotifications, setCombatVictoryNotifications] = useState<CombatVictoryNotification[]>([]);
  const combatVictoryIdRef = useRef(0);
  const [fleeNotifications, setFleeNotifications] = useState<FleeNotification[]>([]);
  const fleeIdRef = useRef(0);
  const [racialProcNotifications, setRacialProcNotifications] = useState<RacialProcNotification[]>([]);
  const racialProcIdRef = useRef(0);
  const [duelState, setDuelState] = useState<DuelState | null>(null);
  const [duelChallenge, setDuelChallenge] = useState<DuelChallenge | null>(null);

  // ── Skills & quickbar ─────────────────────────────
  const [skills, setSkills] = useState<SkillSummary[]>([]);
  const [petSkills, setPetSkills] = useState<SkillSummary[]>([]);

  // ── Inventory & equipment ─────────────────────────
  const [inventory, setInventory] = useState<ItemSummary[]>([]);
  const [equipment, setEquipment] = useState<Record<string, ItemSummary>>({});
  const [equipmentSlotDefs, setEquipmentSlotDefs] = useState<EquipmentSlotDef[]>([]);

  // ── Character progression ─────────────────────────
  const [achievements, setAchievements] = useState<AchievementData>({ completed: [], inProgress: [] });
  const [charStats, setCharStats] = useState<CharStats | null>(null);
  const [prestigeInfo, setPrestigeInfo] = useState<PrestigeInfo | null>(null);

  // ── Social ────────────────────────────────────────
  const [groupInfo, setGroupInfo] = useState<GroupInfo>({ leader: null, members: [] });
  const [pendingGroupInvite, setPendingGroupInvite] = useState<PendingGroupInvite | null>(null);
  const [guildInfo, setGuildInfo] = useState<GuildInfo>({ name: null, tag: null, rank: null, motd: null, memberCount: 0, maxSize: 50 });
  const [pendingGuildInvite, setPendingGuildInvite] = useState<PendingGuildInvite | null>(null);
  const [guildMembers, setGuildMembers] = useState<GuildMemberEntry[]>([]);
  const [guildHall, setGuildHall] = useState<GuildHallInfo | null>(null);
  const [friends, setFriends] = useState<FriendEntry[]>([]);
  const [friendNotifications, setFriendNotifications] = useState<FriendNotification[]>([]);
  const [chatByChannel, setChatByChannel] = useState<Record<ChatChannel, ChatMessage[]>>(createEmptyChatByChannel);
  const [activeChatChannel, setActiveChatChannel] = useState<ChatChannel>("say");
  // The "tell" target, lifted to app state so the Who/Friends "Tell" buttons can
  // prefill it when they open the standalone chat board.
  const [tellTarget, setTellTarget] = useState("");
  const [whoPlayers, setWhoPlayers] = useState<WhoPlayer[]>([]);
  const [tradeState, setTradeState] = useState<TradeState | null>(null);

  // ── Quests ────────────────────────────────────────
  const [quests, setQuests] = useState<QuestEntry[]>([]);
  const [questsAvailable, setQuestsAvailable] = useState<QuestAvailable[]>([]);
  const [questNotifications, setQuestNotifications] = useState<QuestNotification[]>([]);
  const [dailyQuests, setDailyQuests] = useState<DailyQuestBoard | null>(null);
  const [weeklyQuests, setWeeklyQuests] = useState<DailyQuestEntry[]>([]);
  const [autoQuest, setAutoQuest] = useState<AutoQuest | null>(null);
  const [globalQuest, setGlobalQuest] = useState<GlobalQuest | null>(null);

  // ── Economy ───────────────────────────────────────
  const [shop, setShop] = useState<ShopState | null>(null);
  const [puzzle, setPuzzle] = useState<PuzzleState | null>(null);
  const [puzzleResult, setPuzzleResult] = useState<PuzzleResult | null>(null);
  const [auctionListings, setAuctionListings] = useState<AuctionListing[]>([]);
  const [currencies, setCurrencies] = useState<CurrencyBalance[]>([]);
  const [bankState, setBankState] = useState<BankState | null>(null);
  const [stylistState, setStylistState] = useState<StylistState | null>(null);
  const [flightState, setFlightState] = useState<FlightState | null>(null);
  const [travelStatus, setTravelStatus] = useState<TravelStatus | null>(null);
  const [boatState, setBoatState] = useState<BoatState | null>(null);
  const [recallState, setRecallState] = useState<RecallState | null>(null);
  const [lotteryInfo, setLotteryInfo] = useState<LotteryInfo | null>(null);
  const [diceResult, setDiceResult] = useState<DiceGambleResult | null>(null);
  const [jukebox, setJukebox] = useState<JukeboxState | null>(null);
  const [musicBox, setMusicBox] = useState<MusicBoxState | null>(null);
  const [currencyActivity, setCurrencyActivity] = useState<CurrencyActivity[]>([]);

  // ── Crafting ──────────────────────────────────────
  const [craftingSkills, setCraftingSkills] = useState<CraftingSkill[]>([]);
  const [craftingRecipes, setCraftingRecipes] = useState<CraftingRecipe[]>([]);
  const [craftingNodes, setCraftingNodes] = useState<CraftingNode[]>([]);
  const [gatherCooldownUntilMs, setGatherCooldownUntilMs] = useState<number>(0);

  // ── World ─────────────────────────────────────────
  const [dialogue, setDialogue] = useState<DialogueState | null>(null);
  const [zoneInstances, setZoneInstances] = useState<ZoneInstances>({ zone: null, currentEngineId: null, instances: [] });
  const [worldTime, setWorldTime] = useState<WorldTime | null>(null);
  const [worldWeather, setWorldWeather] = useState<WorldWeather | null>(null);
  const [worldEvents, setWorldEvents] = useState<WorldEvent[]>([]);
  const [zoneEnvironment, setZoneEnvironment] = useState<ZoneEnvironment | null>(null);
  const [zoneCinematic, setZoneCinematic] = useState<ZoneCinematicState | null>(null);
  const [factions, setFactions] = useState<FactionStanding[]>([]);
  const [dungeonInfo, setDungeonInfo] = useState<DungeonInfo | null>(null);
  const [dungeonCatalog, setDungeonCatalog] = useState<DungeonCatalogEntry[]>([]);
  const [factionActivity, setFactionActivity] = useState<FactionActivity[]>([]);

  // ── Housing & pets ────────────────────────────────
  const [housing, setHousing] = useState<HousingInfo | null>(null);
  const [housingTemplates, setHousingTemplates] = useState<HousingTemplate[]>([]);
  const [petState, setPetState] = useState<PetState | null>(null);

  // ── Mail ──────────────────────────────────────────
  const [mailInbox, setMailInbox] = useState<MailEntry[] | null>(null);
  const [mailMessage, setMailMessage] = useState<MailMessage | null>(null);

  // ── Leaderboard ───────────────────────────────────
  const [leaderboard, setLeaderboard] = useState<Record<string, LeaderboardData>>({});

  // ── Arcanum (Akathavae explorer path) ─────────────
  const [arcanumStatus, setArcanumStatus] = useState<ArcanumStatus | null>(null);
  const [arcanumJournal, setArcanumJournal] = useState<ArcanumJournal | null>(null);
  const [activeSketch, setActiveSketch] = useState<ActiveSketch | null>(null);
  const activeSketchRef = useRef<ActiveSketch | null>(null);

  // Sketch lifecycle: `start` raises activeSketch (the SceneManager switches to
  // the sketch scene off it); any terminal phase clears it. The raw event also
  // rides the canvas queue so the scene can animate the outcome — but only when
  // a sketch is actually in flight: an instant-mode server (all sketch timing
  // zeroed) sends terminal events with no start, and queueing those would leak
  // stale results into the next real sketch.
  const pushArcanumSketch = useCallback((event: ArcanumSketchEvent) => {
    if (event.phase === "start") {
      const sketch: ActiveSketch = {
        mobId: event.mobId,
        mobName: event.mobName,
        subjectKey: event.subjectKey,
        observe: event.observe,
        durationMs: event.durationMs ?? 0,
        startedAtMs: Date.now(),
      };
      activeSketchRef.current = sketch;
      setActiveSketch(sketch);
      canvasEvents.pushSketch(event);
    } else {
      if (activeSketchRef.current) canvasEvents.pushSketch(event);
      activeSketchRef.current = null;
      setActiveSketch(null);
    }
  }, []);

  // ── Trainer ───────────────────────────────────────
  const [trainer, setTrainer] = useState<TrainerData | null>(null);
  const [unlockedClasses, setUnlockedClasses] = useState<string[]>([]);
  void unlockedClasses;

  // ── Login / connection ────────────────────────────
  const [loginPrompt, setLoginPrompt] = useState<LoginPromptState | null>(null);
  const [loginError, setLoginError] = useState<LoginErrorState | null>(null);
  const [reconnecting, setReconnecting] = useState(false);
  const [savedCharacters, setSavedCharacters] = useState<string[]>([]);

  // ── Staff / meta ──────────────────────────────────
  const [serverAssets, setServerAssets] = useState<Record<string, string>>({});
  const [serverCommands, setServerCommands] = useState<CommandEntry[]>([]);
  const [worldAreas, setWorldAreas] = useState<WorldArea[]>([]);
  const [emotePresets, setEmotePresets] = useState<EmotePreset[]>([]);
  const [serverFeatures, setServerFeatures] = useState<ServerFeatures>(DEFAULT_SERVER_FEATURES);
  const [staffWorldInfo, setStaffWorldInfo] = useState<StaffWorldZone[]>([]);
  const [staffMobTemplates, setStaffMobTemplates] = useState<StaffMobZone[]>([]);
  const [lookTarget, setLookTarget] = useState<LookTargetInfo | null>(null);
  const [considerResult, setConsiderResult] = useState<ConsiderResult | null>(null);
  const [spriteList, setSpriteList] = useState<SpriteList>({ active: null, sprites: [] });

  // ── UI chrome ─────────────────────────────────────
  const [activePopout, setActivePopout] = useState<PopoutPanel>(null);
  const [broadcast, setBroadcast] = useState<{ sender: string; message: string } | null>(null);
  const [possessing, setPossessing] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [uiFeedbackFeed, setUiFeedbackFeed] = useState<UiFeedbackEntry[]>([]);
  const currenciesRef = useRef<CurrencyBalance[]>([]);
  const factionsRef = useRef<FactionStanding[]>([]);

  // ── Push helpers ──────────────────────────────────
  const pushCombatLogMessage = useCallback((msg: CombatLogMessage) => {
    setCombatLogMessages((prev) => {
      const next = [...prev, msg];
      return next.length > MAX_COMBAT_LOG ? next.slice(-MAX_COMBAT_LOG) : next;
    });
  }, []);

  const clearCombatLog = useCallback(() => {
    setCombatLogMessages([]);
  }, []);

  const pushCombatEvent = useCallback((event: CombatEventData) => {
    combatEventsRef.current = [...combatEventsRef.current.slice(-99), event];
    canvasEvents.push(event);
    const logMsg = combatEventToLogMessage(event);
    if (logMsg) pushCombatLogMessage(logMsg);
    if (event.type === "kill" && event.targetName) {
      combatVictoryIdRef.current += 1;
      const notification: CombatVictoryNotification = {
        id: `combat-victory-${combatVictoryIdRef.current}`,
        targetName: event.targetName,
        xpGained: event.xpGained,
        goldGained: event.goldGained,
        lootedItems: event.lootedItems,
        receivedAt: Date.now(),
      };
      // Replace (rather than append) so back-to-back kills don't queue stale
      // toasts behind the active one — otherwise the active toast's dismissal
      // resurfaces a prior kill several seconds late. Also clear any active
      // flee / racial-save toast: they share a fixed corner slot, and the
      // freshest outcome supersedes the stale one.
      setCombatVictoryNotifications([notification]);
      setFleeNotifications([]);
      setRacialProcNotifications([]);
    }
    // A racial passive that cheated death (lethal-blow save). The server tags these
    // abilityCast events `racial:save:*`; low-health procs (`racial:*`) only hit the log.
    if (event.type === "abilityCast" && event.abilityId?.startsWith("racial:save:") && event.text) {
      racialProcIdRef.current += 1;
      const notification: RacialProcNotification = {
        id: `racial-proc-${racialProcIdRef.current}`,
        abilityName: event.abilityName ?? "Racial power",
        text: event.text,
        receivedAt: Date.now(),
      };
      // Freshest outcome wins the shared corner slot, mirroring kill/flee.
      setRacialProcNotifications([notification]);
      setCombatVictoryNotifications([]);
      setFleeNotifications([]);
    }
    if (event.type === "flee" && event.targetName) {
      fleeIdRef.current += 1;
      const notification: FleeNotification = {
        id: `flee-${fleeIdRef.current}`,
        targetName: event.targetName,
        forced: event.forced,
        receivedAt: Date.now(),
      };
      // Mirror the kill path: share the corner slot, freshest outcome wins.
      // A kill toast from the previous fight can still be on-screen (4.5s
      // lifetime) when the player flees the next pull — without this the two
      // cards stack at the same `top:` and overlap.
      setFleeNotifications([notification]);
      setCombatVictoryNotifications([]);
      setRacialProcNotifications([]);
    }
  }, [pushCombatLogMessage]);

  const pushUiFeedback = useCallback((feedback: UiFeedback) => {
    const entry: UiFeedbackEntry = {
      ...feedback,
      id: `ui-feedback-${Date.now()}-${++systemActivityIdCounter}`,
      receivedAt: Date.now(),
    };
    setUiFeedbackFeed((prev) => {
      const next = [...prev, entry];
      return next.length > MAX_UI_FEEDBACK_FEED ? next.slice(-MAX_UI_FEEDBACK_FEED) : next;
    });
    const style = feedback.type === "error" ? "error" : feedback.type === "success" ? "heal" : "info";
    pushCombatLogMessage({
      id: ++combatLogIdCounter,
      text: feedback.message,
      style,
      receivedAt: Date.now(),
    });
    if (feedback.type === "error") {
      setToast(feedback.message);
    }
  }, [pushCombatLogMessage, setToast]);

  const pushCurrencyActivity = useCallback((activity: Omit<CurrencyActivity, "id" | "receivedAt">) => {
    const entry: CurrencyActivity = {
      ...activity,
      id: `currency-activity-${Date.now()}-${++systemActivityIdCounter}`,
      receivedAt: Date.now(),
    };
    setCurrencyActivity((prev) => {
      const next = [...prev, entry];
      return next.length > MAX_SYSTEM_ACTIVITY ? next.slice(-MAX_SYSTEM_ACTIVITY) : next;
    });
  }, []);

  const pushFactionActivity = useCallback((activity: Omit<FactionActivity, "id" | "receivedAt">) => {
    const entry: FactionActivity = {
      ...activity,
      id: `faction-activity-${Date.now()}-${++systemActivityIdCounter}`,
      receivedAt: Date.now(),
    };
    setFactionActivity((prev) => {
      const next = [...prev, entry];
      return next.length > MAX_SYSTEM_ACTIVITY ? next.slice(-MAX_SYSTEM_ACTIVITY) : next;
    });
  }, []);

  const applyCurrencies = useCallback((value: SetStateAction<CurrencyBalance[]>) => {
    if (typeof value === "function") {
      setCurrencies((prev) => {
        const next = value(prev);
        currenciesRef.current = next;
        return next;
      });
      return;
    }

    const prev = currenciesRef.current;
    const prevById = new Map(prev.map((currency) => [currency.id, currency.balance]));
    for (const currency of value) {
      const previousBalance = prevById.get(currency.id);
      if (previousBalance != null && previousBalance !== currency.balance) {
        pushCurrencyActivity({
          currencyId: currency.id,
          name: currency.name,
          abbreviation: currency.abbreviation,
          delta: currency.balance - previousBalance,
          balance: currency.balance,
        });
      }
    }
    currenciesRef.current = value;
    setCurrencies(value);
  }, [pushCurrencyActivity]);

  const applyFactions = useCallback((value: SetStateAction<FactionStanding[]>) => {
    if (typeof value === "function") {
      setFactions((prev) => {
        const next = value(prev);
        factionsRef.current = next;
        return next;
      });
      return;
    }

    const prev = factionsRef.current;
    const prevById = new Map(prev.map((faction) => [faction.id, faction.reputation]));
    for (const faction of value) {
      const previousReputation = prevById.get(faction.id);
      if (previousReputation != null && previousReputation !== faction.reputation) {
        pushFactionActivity({
          factionId: faction.id,
          name: faction.name,
          delta: faction.reputation - previousReputation,
          reputation: faction.reputation,
          tier: faction.tier,
        });
      }
    }
    factionsRef.current = value;
    setFactions(value);
  }, [pushFactionActivity]);

  const pushGainEvent = useCallback((event: GainEvent) => {
    gainEventsRef.current = [...gainEventsRef.current.slice(-49), event];
    canvasEvents.push(event);
  }, []);

  // Server-side Char.LevelUp GMCP → headline celebration overlay. Each new
  // notification replaces any prior (un-dismissed) banner so rapid multi-level
  // gains surface the highest level reached rather than stacking pop-ups.
  const pushLevelUp = useCallback((notification: Omit<LevelUpNotification, "id">) => {
    levelUpIdRef.current += 1;
    setLevelUpNotification({ ...notification, id: levelUpIdRef.current });
  }, []);

  const pushQuestNotification = useCallback((notification: QuestNotification) => {
    setQuestNotifications((prev) => {
      const next = [...prev, notification];
      return next.length > MAX_QUEST_NOTIFICATIONS ? next.slice(-MAX_QUEST_NOTIFICATIONS) : next;
    });
  }, []);

  const pushFriendNotification = useCallback((notification: FriendNotification) => {
    setFriendNotifications((prev) => {
      const next = [...prev, notification];
      return next.length > MAX_FRIEND_NOTIFICATIONS ? next.slice(-MAX_FRIEND_NOTIFICATIONS) : next;
    });
  }, []);

  const pushCraftingResult = useCallback((result?: CraftingResult) => {
    if (!result) return;
    const verb = result.type === "gather" ? "Gathered" : "Crafted";
    const item = result.itemName ? ` ${result.itemName}${result.quantity && result.quantity > 1 ? ` x${result.quantity}` : ""}` : "";
    const xp = result.xpAwarded > 0 ? ` (+${result.xpAwarded} ${result.skill} XP)` : "";
    const levelUp = result.leveledUp ? ` \u2014 Level up! ${result.skill} \u2192 Lv ${result.newLevel}` : "";
    const text = `${verb}${item}${xp}${levelUp}`;
    pushCombatLogMessage({
      id: ++combatLogIdCounter,
      text,
      style: result.leveledUp ? "xp" : "heal",
      receivedAt: Date.now(),
    });
    // Also surface it as crafting-scoped feedback so the Crafting panel confirms
    // in-place that the item was actually made (appended directly to avoid a
    // duplicate combat-log line from pushUiFeedback).
    setUiFeedbackFeed((prev) => {
      const entry: UiFeedbackEntry = {
        type: "success",
        message: text,
        scope: "crafting",
        id: `ui-feedback-${Date.now()}-${++systemActivityIdCounter}`,
        receivedAt: Date.now(),
      };
      const next = [...prev, entry];
      return next.length > MAX_UI_FEEDBACK_FEED ? next.slice(-MAX_UI_FEEDBACK_FEED) : next;
    });
  }, [pushCombatLogMessage]);

  const pushMailNotification = useCallback((notification?: MailNotification) => {
    if (!notification) return;
    pushUiFeedback({ type: "info", message: `New mail from ${notification.from}` });
  }, [pushUiFeedback]);

  // Room-scoped state cleared when Room.Info reports a new room id, before the
  // follow-up Room.Players/Mobs/Items/Features packets repopulate it (a bare
  // Room.Info with no follow-up — mount fast travel mid-ride — leaves the room
  // empty). Uses the raw setters, not the ctx-wrapped ones, so this transient
  // clear doesn't trip side effects like the features panel auto-close.
  const clearRoomScopedState = useCallback(() => {
    setDialogue(null);
    setQuestsAvailable([]);
    setTrainer(null);
    setPlayers([]);
    setMobs([]);
    setMobInfo([]);
    setRoomItems([]);
    setRoomFeatures([]);
    setCraftingNodes([]);
  }, []);

  // ── GMCP handler ──────────────────────────────────
  const handleGmcp = useCallback(
    (pkg: string, data: unknown) => {
      applyGmcpPackage(pkg, data, {
        setVitals,
        setStatusVarLabels,
        setCharacter,
        setRoom,
        setRoomItems,
        setInventory,
        setEquipment,
        setEquipmentSlotDefs,
        setPlayers,
        setMobs,
        setEffects,
        setSkills,
        setPetSkills,
        setAchievements,
        setGroupInfo,
        setPendingGroupInvite,
        setGuildInfo,
        setPendingGuildInvite,
        setGuildMembers,
        setDialogue,
        setCombatTarget,
        setShop: (value) => {
          setShop(value);
          if (!value) setActivePopout((prev) => prev === "shop" ? null : prev);
        },
        setPuzzle: (value) => {
          setPuzzle(value);
          if (!value) {
            setPuzzleResult(null);
            setActivePopout((prev) => prev === "puzzle" ? null : prev);
          }
        },
        setPuzzleResult,
        setFriends,
        pushFriendNotification,
        setChatByChannel,
        updateMap,
        loadZoneMap,
        pushCombatEvent,
        setCharStats,
        setQuests,
        setQuestsAvailable,
        setDailyQuests,
        setWeeklyQuests,
        setAutoQuest,
        setGlobalQuest,
        pushGainEvent,
        pushLevelUp,
        pushQuestNotification,
        setMobInfo,
        setRoomFeatures: (value) => {
          setRoomFeatures(value);
          // Auto-close the World Features panel when the room has nothing to show
          // (e.g. after walking through a door into a featureless room) so it
          // doesn't linger on the empty "no interactive features" state.
          if (Array.isArray(value) && value.length === 0) {
            setActivePopout((prev) => (prev === "features" ? null : prev));
          }
        },
        setContainerContents,
        setLoginPrompt,
        setLoginError,
        setReconnecting,
        setSavedCharacters,
        resumeTokenRef,
        pendingAuthCharRef,
        lastRoomIdRef,
        clearRoomScopedState,
        setServerAssets,
        setServerCommands,
        setWorldAreas,
        setEmotePresets,
        setServerFeatures,
        pushUiFeedback,
        setStaffWorldInfo,
        setStaffMobTemplates,
        pushBroadcast: (sender: string, message: string) => setBroadcast({ sender, message }),
        setPossessing,
        setLookTarget,
        setConsiderResult,
        setCraftingSkills,
        setCraftingRecipes,
        setCraftingNodes,
        setGatherCooldownUntilMs,
        pushCraftingResult,
        setMailInbox,
        setMailMessage,
        pushMailNotification,
        setWhoPlayers,
        setZoneInstances,
        setSpriteList,
        setHousing,
        setHousingTemplates,
        setTradeState,
        setAuctionListings,
        setLeaderboard,
        setArcanumStatus,
        setArcanumJournal,
        pushArcanumSketch,
        setCurrencies: applyCurrencies,
        setTrainer,
        setUnlockedClasses,
        setWorldTime,
        setWorldWeather,
        setWorldEvents,
        setZoneEnvironment,
        setZoneCinematic,
        // Zone-cinematic autoplay rides the same bridge as the canvas video
        // button — App registers openVideo to open the cinematic modal.
        openCinematic: (url: string) => { canvasCallbacks.openVideo?.(url); },
        setPetState,
        setFactions: applyFactions,
        setBankState,
        setStylistState,
        setFlightState,
        setTravelStatus,
        setBoatState,
        setRecallState,
        setLotteryInfo,
        setDiceResult,
        setJukebox,
        setMusicBox,
        setGuildHall,
        setDuelState,
        setDuelChallenge,
        setDungeonInfo,
        setDungeonCatalog,
        setPrestigeInfo,
        setToast,
        sendGmcp: (p: string, payload: unknown) => { sendGmcpRef.current(p, payload); return true; },
      });
    },
    [applyCurrencies, applyFactions, pushFriendNotification, pushCombatEvent, pushGainEvent, pushLevelUp, pushQuestNotification, pushUiFeedback, pushCraftingResult, pushMailNotification, pushArcanumSketch, updateMap, loadZoneMap, resumeTokenRef, pendingAuthCharRef, sendGmcpRef, clearRoomScopedState],
  );

  // ── Reset all HUD state on disconnect ─────────────
  const resetHud = useCallback(() => {
    setVitals(EMPTY_VITALS);
    setStatusVarLabels(DEFAULT_STATUS_VAR_LABELS);
    setCharacter(EMPTY_CHAR);
    setRoom(EMPTY_ROOM);
    lastRoomIdRef.current = null;
    setPlayers([]);
    setMobs([]);
    setRoomItems([]);
    setEffects([]);
    setSkills([]);
    setPetSkills([]);
    setInventory([]);
    setEquipment({});
    setEquipmentSlotDefs([]);
    setAchievements({ completed: [], inProgress: [] });
    setGroupInfo({ leader: null, members: [] });
    setPendingGroupInvite(null);
    setGuildInfo({ name: null, tag: null, rank: null, motd: null, memberCount: 0, maxSize: 50 });
    setPendingGuildInvite(null);
    setGuildMembers([]);
    setGuildHall(null);
    setFriends([]);
    setFriendNotifications([]);
    setChatByChannel(createEmptyChatByChannel());
    setActiveChatChannel("say");
    setTellTarget("");
    setDialogue(null);
    setWhoPlayers([]);
    setZoneInstances({ zone: null, currentEngineId: null, instances: [] });
    setZoneCinematic(null);
    setCombatTarget(null);
    setConsiderResult(null);
    setLookTarget(null);
    setCharStats(null);
    setQuests([]);
    setQuestsAvailable([]);
    setDailyQuests(null);
    setWeeklyQuests([]);
    setAutoQuest(null);
    setGlobalQuest(null);
    setMobInfo([]);
    setRoomFeatures([]);
    setContainerContents(null);
    setShop(null);
    setPuzzle(null);
    setPuzzleResult(null);
    setQuestNotifications([]);
    setCombatVictoryNotifications([]);
    setFleeNotifications([]);
    setRacialProcNotifications([]);
    setCraftingSkills([]);
    setCraftingRecipes([]);
    setCraftingNodes([]);
    setGatherCooldownUntilMs(0);
    setMailInbox(null);
    setMailMessage(null);
    setLoginPrompt(null);
    setLoginError(null);
    setSavedCharacters([]);
    setHousing(null);
    setHousingTemplates([]);
    setTradeState(null);
    setAuctionListings([]);
    setCurrencies([]);
    currenciesRef.current = [];
    setPetState(null);
    setFactions([]);
    factionsRef.current = [];
    setBankState(null);
    setStylistState(null);
    setFlightState(null);
    setTravelStatus(null);
    setBoatState(null);
    setRecallState(null);
    setLotteryInfo(null);
    setDiceResult(null);
    setMusicBox(null);
    setJukebox(null);
    setDuelState(null);
    setDuelChallenge(null);
    setDungeonInfo(null);
    setDungeonCatalog([]);
    setPrestigeInfo(null);
    setCurrencyActivity([]);
    setFactionActivity([]);
    combatEventsRef.current = [];
    gainEventsRef.current = [];
    setLevelUpNotification(null);
    setCombatLogMessages([]);
    setUiFeedbackFeed([]);
    setActivePopout(null);
    setBroadcast(null);
    setPossessing(null);
    setStaffWorldInfo([]);
    setStaffMobTemplates([]);
    setServerCommands([]);
    setWorldAreas([]);
    setEmotePresets([]);
    setServerFeatures(DEFAULT_SERVER_FEATURES);
    resetMap();
  }, [resetMap]);

  return {
    // Core
    vitals, character, room, statusVarLabels,
    // Room contents
    players, mobs, roomItems, roomFeatures, containerContents, mobInfo,
    // Combat
    effects, combatTarget, combatLogMessages, duelState, duelChallenge,
    // Skills
    skills, setSkills,
    petSkills, setPetSkills,
    // Inventory
    inventory, equipment, equipmentSlotDefs,
    // Progression
    achievements, charStats, prestigeInfo,
    // Social
    groupInfo, pendingGroupInvite, guildInfo, pendingGuildInvite, guildMembers, guildHall,
    friends, friendNotifications, chatByChannel, activeChatChannel, setActiveChatChannel, tellTarget, setTellTarget,
    whoPlayers, tradeState,
    // Quests
    quests, questsAvailable, questNotifications, setQuestNotifications,
    dailyQuests, weeklyQuests, autoQuest, globalQuest,
    // Economy
    shop, puzzle, puzzleResult, auctionListings, currencies, currencyActivity, bankState, stylistState, flightState, travelStatus, boatState, recallState, lotteryInfo, diceResult, jukebox, musicBox,
    // Crafting
    craftingSkills, craftingRecipes, craftingNodes, gatherCooldownUntilMs,
    // World
    dialogue, setDialogue, zoneInstances, worldTime, worldWeather, worldEvents, zoneEnvironment, zoneCinematic, factions, factionActivity, dungeonInfo, dungeonCatalog,
    // Housing & pets
    housing, housingTemplates, petState,
    // Mail
    mailInbox, mailMessage, setMailMessage,
    // Leaderboard
    leaderboard,
    // Arcanum
    arcanumStatus, arcanumJournal, activeSketch,
    // Trainer
    trainer,
    // Login / connection
    loginPrompt, loginError, reconnecting, setReconnecting, savedCharacters, setSavedCharacters,
    // Staff / meta
    serverAssets, serverCommands, worldAreas, emotePresets, serverFeatures, staffWorldInfo, staffMobTemplates,
    lookTarget, setLookTarget, considerResult, setConsiderResult, spriteList,
    // UI
    activePopout, setActivePopout, broadcast, setBroadcast, possessing, toast, setToast, uiFeedbackFeed,
    levelUpNotification, setLevelUpNotification,
    combatVictoryNotifications, setCombatVictoryNotifications,
    fleeNotifications, setFleeNotifications,
    racialProcNotifications, setRacialProcNotifications,
    // Setters needed by App
    setQuestsAvailable,
    // GMCP
    handleGmcp,
    // Reset
    resetHud,
    // Push helpers
    pushCombatLogMessage, pushUiFeedback, clearCombatLog,
  };
}

export type GameState = ReturnType<typeof useGameState>;
