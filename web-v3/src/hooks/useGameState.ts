import { useCallback, useRef, useState } from "react";
import { applyGmcpPackage } from "../gmcp/applyGmcpPackage";
import { canvasEvents } from "../canvas/CanvasEventBus";
import {
  DEFAULT_STATUS_VAR_LABELS,
  EMPTY_CHAR,
  EMPTY_ROOM,
  EMPTY_VITALS,
} from "../constants";
import { useMiniMap } from "./useMiniMap";
import type {
  AchievementData,
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
  DungeonInfo,
  EmotePreset,
  EquipmentSlotDef,
  FactionStanding,
  FriendEntry,
  FriendNotification,
  GainEvent,
  GlobalQuest,
  GroupInfo,
  GuildHallInfo,
  GuildInfo,
  GuildMemberEntry,
  HousingInfo,
  ItemSummary,
  LeaderboardData,
  LoginErrorState,
  LoginPromptState,
  LookTargetInfo,
  LotteryInfo,
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
  RoomFeature,
  RoomItem,
  RoomMob,
  RoomPlayer,
  RoomState,
  ShopState,
  SkillSummary,
  SpriteList,
  StaffMobZone,
  StaffWorldZone,
  StatusEffect,
  StatusVarLabels,
  TradeState,
  TrainerData,
  UiFeedback,
  Vitals,
  WhoPlayer,
  WorldEvent,
  WorldTime,
  WorldWeather,
  ZoneInstances,
} from "../types";

function createEmptyChatByChannel(): Record<ChatChannel, ChatMessage[]> {
  return { say: [], tell: [], gossip: [], shout: [], ooc: [], gtell: [], gchat: [] };
}

let combatLogIdCounter = 0;

function combatEventToLogMessage(event: CombatEventData): CombatLogMessage | null {
  const now = Date.now();
  const id = ++combatLogIdCounter;
  switch (event.type) {
    case "meleeHit":
      return event.sourceIsPlayer
        ? { id, text: `You hit ${event.targetName} for ${event.damage} damage.`, style: "damage", receivedAt: now }
        : { id, text: `${event.targetName} hits you for ${event.damage} damage!`, style: "damage", receivedAt: now };
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

const MAX_COMBAT_LOG = 20;
const MAX_QUEST_NOTIFICATIONS = 5;
const MAX_FRIEND_NOTIFICATIONS = 5;

export interface AuthRefs {
  resumeTokenRef: React.MutableRefObject<string | null>;
  pendingAuthCharRef: React.MutableRefObject<string | null>;
  failedAuthCharRef: React.MutableRefObject<string | null>;
  sendGmcpRef: React.MutableRefObject<(pkg: string, payload: unknown) => void>;
}

export function useGameState(authRefs: AuthRefs) {
  const { resumeTokenRef, pendingAuthCharRef, failedAuthCharRef, sendGmcpRef } = authRefs;
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

  // ── Combat ────────────────────────────────────────
  const [effects, setEffects] = useState<StatusEffect[]>([]);
  const [combatTarget, setCombatTarget] = useState<CombatTarget | null>(null);
  const [combatLogMessages, setCombatLogMessages] = useState<CombatLogMessage[]>([]);
  const combatEventsRef = useRef<CombatEventData[]>([]);
  const gainEventsRef = useRef<GainEvent[]>([]);
  const [duelState, setDuelState] = useState<DuelState | null>(null);
  const [duelChallenge, setDuelChallenge] = useState<DuelChallenge | null>(null);

  // ── Skills & quickbar ─────────────────────────────
  const [skills, setSkills] = useState<SkillSummary[]>([]);

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
  const [auctionListings, setAuctionListings] = useState<AuctionListing[]>([]);
  const [currencies, setCurrencies] = useState<CurrencyBalance[]>([]);
  const [bankState, setBankState] = useState<BankState | null>(null);
  const [lotteryInfo, setLotteryInfo] = useState<LotteryInfo | null>(null);

  // ── Crafting ──────────────────────────────────────
  const [craftingSkills, setCraftingSkills] = useState<CraftingSkill[]>([]);
  const [craftingRecipes, setCraftingRecipes] = useState<CraftingRecipe[]>([]);
  const [craftingNodes, setCraftingNodes] = useState<CraftingNode[]>([]);

  // ── World ─────────────────────────────────────────
  const [dialogue, setDialogue] = useState<DialogueState | null>(null);
  const [zoneInstances, setZoneInstances] = useState<ZoneInstances>({ zone: null, currentEngineId: null, instances: [] });
  const [worldTime, setWorldTime] = useState<WorldTime | null>(null);
  const [worldWeather, setWorldWeather] = useState<WorldWeather | null>(null);
  const [worldEvents, setWorldEvents] = useState<WorldEvent[]>([]);
  const [factions, setFactions] = useState<FactionStanding[]>([]);
  const [dungeonInfo, setDungeonInfo] = useState<DungeonInfo | null>(null);

  // ── Housing & pets ────────────────────────────────
  const [housing, setHousing] = useState<HousingInfo | null>(null);
  const [petState, setPetState] = useState<PetState | null>(null);

  // ── Mail ──────────────────────────────────────────
  const [mailInbox, setMailInbox] = useState<MailEntry[] | null>(null);
  const [mailMessage, setMailMessage] = useState<MailMessage | null>(null);

  // ── Leaderboard ───────────────────────────────────
  const [leaderboard, setLeaderboard] = useState<Record<string, LeaderboardData>>({});

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
  const [emotePresets, setEmotePresets] = useState<EmotePreset[]>([]);
  const [staffWorldInfo, setStaffWorldInfo] = useState<StaffWorldZone[]>([]);
  const [staffMobTemplates, setStaffMobTemplates] = useState<StaffMobZone[]>([]);
  const [lookTarget, setLookTarget] = useState<LookTargetInfo | null>(null);
  const [spriteList, setSpriteList] = useState<SpriteList>({ active: null, sprites: [] });

  // ── UI chrome ─────────────────────────────────────
  const [activePopout, setActivePopout] = useState<PopoutPanel>(null);
  const [broadcast, setBroadcast] = useState<{ sender: string; message: string } | null>(null);
  const [possessing, setPossessing] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  // ── Minimap ───────────────────────────────────────
  const { mapCanvasRef, drawMap, updateMap, loadZoneMap, resetMap, startPulse, stopPulse } = useMiniMap();

  // ── Push helpers ──────────────────────────────────
  const pushCombatLogMessage = useCallback((msg: CombatLogMessage) => {
    setCombatLogMessages((prev) => {
      const next = [...prev, msg];
      return next.length > MAX_COMBAT_LOG ? next.slice(-MAX_COMBAT_LOG) : next;
    });
  }, []);

  const pushCombatEvent = useCallback((event: CombatEventData) => {
    combatEventsRef.current = [...combatEventsRef.current.slice(-99), event];
    canvasEvents.push(event);
    const logMsg = combatEventToLogMessage(event);
    if (logMsg) pushCombatLogMessage(logMsg);
  }, [pushCombatLogMessage]);

  const pushUiFeedback = useCallback((feedback: UiFeedback) => {
    const style = feedback.type === "error" ? "error" : feedback.type === "success" ? "heal" : "info";
    pushCombatLogMessage({
      id: ++combatLogIdCounter,
      text: feedback.message,
      style,
      receivedAt: Date.now(),
    });
  }, [pushCombatLogMessage]);

  const pushGainEvent = useCallback((event: GainEvent) => {
    gainEventsRef.current = [...gainEventsRef.current.slice(-49), event];
    canvasEvents.push(event);
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
    pushCombatLogMessage({
      id: ++combatLogIdCounter,
      text: `${verb}${item}${xp}${levelUp}`,
      style: result.leveledUp ? "xp" : "heal",
      receivedAt: Date.now(),
    });
  }, [pushCombatLogMessage]);

  const pushMailNotification = useCallback((notification?: MailNotification) => {
    if (!notification) return;
    pushUiFeedback({ type: "info", message: `New mail from ${notification.from}` });
  }, [pushUiFeedback]);

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
        pushQuestNotification,
        setMobInfo,
        setRoomFeatures,
        setContainerContents,
        setLoginPrompt,
        setLoginError,
        setReconnecting,
        setSavedCharacters,
        resumeTokenRef,
        pendingAuthCharRef,
        failedAuthCharRef,
        setServerAssets,
        setServerCommands,
        setEmotePresets,
        pushUiFeedback,
        setStaffWorldInfo,
        setStaffMobTemplates,
        pushBroadcast: (sender: string, message: string) => setBroadcast({ sender, message }),
        setPossessing,
        setLookTarget,
        setCraftingSkills,
        setCraftingRecipes,
        setCraftingNodes,
        pushCraftingResult,
        setMailInbox,
        setMailMessage,
        pushMailNotification,
        setWhoPlayers,
        setZoneInstances,
        setSpriteList,
        setHousing,
        setTradeState,
        setAuctionListings,
        setLeaderboard,
        setCurrencies,
        setTrainer,
        setUnlockedClasses,
        setWorldTime,
        setWorldWeather,
        setWorldEvents,
        setPetState,
        setFactions,
        setBankState,
        setLotteryInfo,
        setGuildHall,
        setDuelState,
        setDuelChallenge,
        setDungeonInfo,
        setPrestigeInfo,
        sendGmcp: (p: string, payload: unknown) => { sendGmcpRef.current(p, payload); return true; },
      });
    },
    [pushFriendNotification, pushCombatEvent, pushGainEvent, pushQuestNotification, pushUiFeedback, pushCraftingResult, pushMailNotification, updateMap, loadZoneMap, resumeTokenRef, pendingAuthCharRef, failedAuthCharRef, sendGmcpRef],
  );

  // ── Reset all HUD state on disconnect ─────────────
  const resetHud = useCallback(() => {
    setVitals(EMPTY_VITALS);
    setStatusVarLabels(DEFAULT_STATUS_VAR_LABELS);
    setCharacter(EMPTY_CHAR);
    setRoom(EMPTY_ROOM);
    setPlayers([]);
    setMobs([]);
    setRoomItems([]);
    setEffects([]);
    setSkills([]);
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
    setDialogue(null);
    setWhoPlayers([]);
    setZoneInstances({ zone: null, currentEngineId: null, instances: [] });
    setCombatTarget(null);
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
    setQuestNotifications([]);
    setCraftingSkills([]);
    setCraftingRecipes([]);
    setCraftingNodes([]);
    setMailInbox(null);
    setMailMessage(null);
    setLoginPrompt(null);
    setLoginError(null);
    setSavedCharacters([]);
    setHousing(null);
    setTradeState(null);
    setAuctionListings([]);
    setPetState(null);
    setBankState(null);
    setLotteryInfo(null);
    setDuelState(null);
    setDuelChallenge(null);
    setDungeonInfo(null);
    setPrestigeInfo(null);
    combatEventsRef.current = [];
    gainEventsRef.current = [];
    setCombatLogMessages([]);
    setActivePopout(null);
    setBroadcast(null);
    setPossessing(null);
    setStaffWorldInfo([]);
    setStaffMobTemplates([]);
    setServerCommands([]);
    setEmotePresets([]);
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
    // Inventory
    inventory, equipment, equipmentSlotDefs,
    // Progression
    achievements, charStats, prestigeInfo,
    // Social
    groupInfo, pendingGroupInvite, guildInfo, pendingGuildInvite, guildMembers, guildHall,
    friends, friendNotifications, chatByChannel, activeChatChannel, setActiveChatChannel,
    whoPlayers, tradeState,
    // Quests
    quests, questsAvailable, questNotifications, setQuestNotifications,
    dailyQuests, weeklyQuests, autoQuest, globalQuest,
    // Economy
    shop, auctionListings, currencies, bankState, lotteryInfo,
    // Crafting
    craftingSkills, craftingRecipes, craftingNodes,
    // World
    dialogue, setDialogue, zoneInstances, worldTime, worldWeather, worldEvents, factions, dungeonInfo,
    // Housing & pets
    housing, petState,
    // Mail
    mailInbox, mailMessage, setMailMessage,
    // Leaderboard
    leaderboard,
    // Trainer
    trainer,
    // Login / connection
    loginPrompt, loginError, reconnecting, setReconnecting, savedCharacters, setSavedCharacters,
    // Staff / meta
    serverAssets, serverCommands, emotePresets, staffWorldInfo, staffMobTemplates,
    lookTarget, setLookTarget, spriteList,
    // UI
    activePopout, setActivePopout, broadcast, setBroadcast, possessing, toast, setToast,
    // Minimap
    mapCanvasRef, drawMap, startPulse, stopPulse,
    // Setters needed by App
    setQuestsAvailable,
    // GMCP
    handleGmcp,
    // Reset
    resetHud,
    // Push helpers
    pushCombatLogMessage, pushUiFeedback,
  };
}

export type GameState = ReturnType<typeof useGameState>;
