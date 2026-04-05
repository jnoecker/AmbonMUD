import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent, KeyboardEvent } from "react";
import { FitAddon } from "@xterm/addon-fit";
import { Terminal } from "@xterm/xterm";
import { ActionBar } from "./components/ActionBar";
import { AudioControls } from "./components/AudioControls";
import { PopoutLayer } from "./components/PopoutLayer";
import { ShopPopout } from "./components/ShopPopout";
import { TrainerPanel } from "./components/TrainerPanel";
import { TradePanel } from "./components/TradePanel";
import { ChatPanel } from "./components/panels/ChatPanel";
import { CharacterPanel } from "./components/panels/CharacterPanel";
import { SpellbookPanel } from "./components/SpellbookPanel";
import { QuestPanel } from "./components/panels/QuestPanel";
import { InventoryPanel } from "./components/panels/InventoryPanel";
import { EquipmentPanel } from "./components/panels/EquipmentPanel";
import { PlayPanel } from "./components/panels/PlayPanel";
import { AdminPanel } from "./components/panels/AdminPanel";
import { MailPanel } from "./components/panels/MailPanel";
import { CraftingPanel } from "./components/panels/CraftingPanel";
import { HousingPanel } from "./components/panels/HousingPanel";
import { LeaderboardPanel } from "./components/panels/LeaderboardPanel";
import { CommandPalette } from "./components/CommandPalette";
import { applyGmcpPackage } from "./gmcp/applyGmcpPackage";
import { canvasCallbacks, gameStateRef, pendingCastRef } from "./canvas/GameStateBridge";
import { canvasEvents } from "./canvas/CanvasEventBus";
import { LoginModal } from "./canvas/LoginModal";
import { CharacterPicker } from "./components/CharacterPicker";
import {
  DEFAULT_STATUS_VAR_LABELS,
  EMPTY_CHAR,
  EMPTY_ROOM,
  EMPTY_VITALS,
  MAX_VISIBLE_EFFECTS,
} from "./constants";
import { useCommandHistory } from "./hooks/useCommandHistory";
import { useMiniMap } from "./hooks/useMiniMap";
import { useMudSocket } from "./hooks/useMudSocket";
import { useAudioEngine } from "./hooks/useAudioEngine";
import { useQuickbar } from "./hooks/useQuickbar";
import type {
  LayoutMode,
  AchievementData,
  CharStats,
  ChatChannel,
  ChatMessage,
  CharacterInfo,
  CombatEventData,
  AuctionListing,
  CombatLogMessage,
  CombatTarget,
  CommandEntry,
  CraftingNode,
  CraftingRecipe,
  CraftingResult,
  CraftingSkill,
  DialogueState,
  EmotePreset,
  EquipmentSlotDef,
  FriendEntry,
  FriendNotification,
  GainEvent,
  GroupInfo,
  GuildInfo,
  GuildMemberEntry,
  HousingInfo,
  PendingGroupInvite,
  PendingGuildInvite,
  ItemSummary,
  LoginErrorState,
  LoginPromptState,
  LookTargetInfo,
  MailEntry,
  MailMessage,
  ContainerContents,
  MailNotification,
  MobInfo,
  PopoutPanel,
  RoomFeature,
  QuestAvailable,
  QuestEntry,
  QuestNotification,
  RoomMob,
  RoomItem,
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
  LeaderboardData,
} from "./types";
import { sortExits, titleCaseWords } from "./utils";
import "@xterm/xterm/css/xterm.css";
import "./styles.css";

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

function App() {
  const terminalHiddenRef = useRef<HTMLDivElement | null>(null);
  const terminalOverlayRef = useRef<HTMLDivElement | null>(null);
  const composerInputRef = useRef<HTMLInputElement | null>(null);
  const terminalRef = useRef<Terminal | null>(null);
  const fitAddonRef = useRef<FitAddon | null>(null);

  const [activeChatChannel, setActiveChatChannel] = useState<ChatChannel>("say");
  const [activePopout, setActivePopout] = useState<PopoutPanel>(null);
  const [showAdminPanel, setShowAdminPanel] = useState(false);
  const [showCommandPalette, setShowCommandPalette] = useState(false);
  const [staffInvisible, setStaffInvisible] = useState(false);
  const [broadcast, setBroadcast] = useState<{ sender: string; message: string } | null>(null);
  const [possessing, setPossessing] = useState<string | null>(null);
  const [composerValue, setComposerValue] = useState("");
  const [terminalVisible, setTerminalVisible] = useState(false);
  const [terminalOpaque, setTerminalOpaque] = useState(false);
  const [layoutMode, setLayoutMode] = useState<LayoutMode>(() => {
    try { return (localStorage.getItem("ambonmud_layout_mode") as LayoutMode) ?? "auto"; } catch { return "auto"; }
  });

  // Persist layout mode to localStorage
  useEffect(() => {
    try { localStorage.setItem("ambonmud_layout_mode", layoutMode); } catch { /* ignore */ }
  }, [layoutMode]);

  const [videoUrl, setVideoUrl] = useState<string | null>(null);
  const [videoClosing, setVideoClosing] = useState(false);

  const [vitals, setVitals] = useState<Vitals>(EMPTY_VITALS);
  const [statusVarLabels, setStatusVarLabels] = useState<StatusVarLabels>(DEFAULT_STATUS_VAR_LABELS);
  const [character, setCharacter] = useState<CharacterInfo>(EMPTY_CHAR);
  const [room, setRoom] = useState<RoomState>(EMPTY_ROOM);
  // Derive the effective text-mode flag from user preference + zone data
  const textMode = layoutMode === "text" || (layoutMode === "auto" && !room.graphical);
  const [players, setPlayers] = useState<RoomPlayer[]>([]);
  const [mobs, setMobs] = useState<RoomMob[]>([]);
  const [roomItems, setRoomItems] = useState<RoomItem[]>([]);
  const [effects, setEffects] = useState<StatusEffect[]>([]);
  const [skills, setSkills] = useState<SkillSummary[]>([]);
  const audio = useAudioEngine();
  const quickbar = useQuickbar(skills);
  const [toast, setToast] = useState<string | null>(null);
  const [inventory, setInventory] = useState<ItemSummary[]>([]);
  const [equipment, setEquipment] = useState<Record<string, ItemSummary>>({});
  const [equipmentSlotDefs, setEquipmentSlotDefs] = useState<EquipmentSlotDef[]>([]);
  const [achievements, setAchievements] = useState<AchievementData>({ completed: [], inProgress: [] });
  const [groupInfo, setGroupInfo] = useState<GroupInfo>({ leader: null, members: [] });
  const [pendingGroupInvite, setPendingGroupInvite] = useState<PendingGroupInvite | null>(null);
  const [guildInfo, setGuildInfo] = useState<GuildInfo>({ name: null, tag: null, rank: null, motd: null, memberCount: 0, maxSize: 50 });
  const [pendingGuildInvite, setPendingGuildInvite] = useState<PendingGuildInvite | null>(null);
  const [guildMembers, setGuildMembers] = useState<GuildMemberEntry[]>([]);
  const [friends, setFriends] = useState<FriendEntry[]>([]);
  const [friendNotifications, setFriendNotifications] = useState<FriendNotification[]>([]);
  const [chatByChannel, setChatByChannel] = useState<Record<ChatChannel, ChatMessage[]>>(createEmptyChatByChannel);
  const [dialogue, setDialogue] = useState<DialogueState | null>(null);
  const [whoPlayers, setWhoPlayers] = useState<WhoPlayer[]>([]);
  const [zoneInstances, setZoneInstances] = useState<ZoneInstances>({ zone: null, currentEngineId: null, instances: [] });
  const [combatTarget, setCombatTarget] = useState<CombatTarget | null>(null);
  const [charStats, setCharStats] = useState<CharStats | null>(null);
  const [quests, setQuests] = useState<QuestEntry[]>([]);
  const [questsAvailable, setQuestsAvailable] = useState<QuestAvailable[]>([]);
  const [mobInfo, setMobInfo] = useState<MobInfo[]>([]);
  const [roomFeatures, setRoomFeatures] = useState<RoomFeature[]>([]);
  const [containerContents, setContainerContents] = useState<ContainerContents | null>(null);
  const [shop, setShop] = useState<ShopState | null>(null);
  const [questNotifications, setQuestNotifications] = useState<QuestNotification[]>([]);
  const [craftingSkills, setCraftingSkills] = useState<CraftingSkill[]>([]);
  const [craftingRecipes, setCraftingRecipes] = useState<CraftingRecipe[]>([]);
  const [craftingNodes, setCraftingNodes] = useState<CraftingNode[]>([]);
  const [mailInbox, setMailInbox] = useState<MailEntry[] | null>(null);
  const [mailMessage, setMailMessage] = useState<MailMessage | null>(null);
  const [loginPrompt, setLoginPrompt] = useState<LoginPromptState | null>(null);
  const [loginError, setLoginError] = useState<LoginErrorState | null>(null);
  const [reconnecting, setReconnecting] = useState(false);
  const [savedCharacters, setSavedCharacters] = useState<string[]>([]);
  const resumeTokenRef = useRef<string | null>(null);
  const pendingAuthCharRef = useRef<string | null>(null);
  const failedAuthCharRef = useRef<string | null>(null);
  const connectedRef = useRef(false);
  const intentionalDisconnectRef = useRef(false);
  const [serverAssets, setServerAssets] = useState<Record<string, string>>({});
  const [serverCommands, setServerCommands] = useState<CommandEntry[]>([]);
  const [emotePresets, setEmotePresets] = useState<EmotePreset[]>([]);
  const [staffWorldInfo, setStaffWorldInfo] = useState<StaffWorldZone[]>([]);
  const [staffMobTemplates, setStaffMobTemplates] = useState<StaffMobZone[]>([]);
  const [lookTarget, setLookTarget] = useState<LookTargetInfo | null>(null);
  const [spriteList, setSpriteList] = useState<SpriteList>({ active: null, sprites: [] });
  const [housing, setHousing] = useState<HousingInfo | null>(null);
  const [tradeState, setTradeState] = useState<TradeState | null>(null);
  const [auctionListings, setAuctionListings] = useState<AuctionListing[]>([]); // GMCP Auction.List data for future panel
  void auctionListings; // suppress unused-var lint until auction panel is built
  const [leaderboard, setLeaderboard] = useState<Record<string, LeaderboardData>>({});
  const [trainer, setTrainer] = useState<TrainerData | null>(null);
  const [unlockedClasses, setUnlockedClasses] = useState<string[]>([]);
  void unlockedClasses; // stored for future character sheet multi-class display
  const [worldTime, setWorldTime] = useState<WorldTime | null>(null);
  const [worldWeather, setWorldWeather] = useState<WorldWeather | null>(null);
  const [worldEvents, setWorldEvents] = useState<WorldEvent[]>([]);
  const combatEventsRef = useRef<CombatEventData[]>([]);
  const gainEventsRef = useRef<GainEvent[]>([]);

  const [combatLogMessages, setCombatLogMessages] = useState<CombatLogMessage[]>([]);
  const MAX_COMBAT_LOG = 20;

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

  const MAX_QUEST_NOTIFICATIONS = 5;
  const pushQuestNotification = useCallback((notification: QuestNotification) => {
    setQuestNotifications((prev) => {
      const next = [...prev, notification];
      return next.length > MAX_QUEST_NOTIFICATIONS ? next.slice(-MAX_QUEST_NOTIFICATIONS) : next;
    });
  }, []);

  const { mapCanvasRef, drawMap, updateMap, loadZoneMap, resetMap, startPulse, stopPulse } = useMiniMap();
  const {
    pushHistory,
    applyComposerHistoryUp,
    applyComposerHistoryDown,
    applyComposerCompletion,
    resetComposerTraversal,
    resetComposerCompletion,
  } = useCommandHistory(serverCommands);

  const writeSystem = useCallback((message: string) => {
    terminalRef.current?.write(`\r\n\x1b[2m${message}\x1b[0m\r\n`);
  }, []);

  const MAX_FRIEND_NOTIFICATIONS = 5;
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
    pushUiFeedback({
      type: "info",
      message: `New mail from ${notification.from}`,
    });
  }, [pushUiFeedback]);

  const focusComposer = useCallback(() => {
    window.requestAnimationFrame(() => composerInputRef.current?.focus());
  }, []);

  const fitTerminal = useCallback(() => {
    const term = terminalRef.current;
    const fitAddon = fitAddonRef.current;
    // Fit to whichever container the terminal is currently in
    const host = terminalOverlayRef.current ?? terminalHiddenRef.current;
    if (!term || !fitAddon || !host) return;
    if (host.clientWidth <= 0 || host.clientHeight <= 0) return;

    const width = host.clientWidth;
    const nextFontSize = width < 560 ? 12 : width < 760 ? 13 : 14;
    if (term.options.fontSize !== nextFontSize) {
      term.options.fontSize = nextFontSize;
    }

    fitAddon.fit();
  }, []);

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
    setFriends([]);
    setFriendNotifications([]);
    setChatByChannel(createEmptyChatByChannel());
    setDialogue(null);
    setWhoPlayers([]);
    setZoneInstances({ zone: null, currentEngineId: null, instances: [] });
    setCombatTarget(null);
    setCharStats(null);
    setQuests([]);
    setQuestsAvailable([]);
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
    combatEventsRef.current = [];
    gainEventsRef.current = [];
    setCombatLogMessages([]);
    setActiveChatChannel("say");
    setShowAdminPanel(false);
    setStaffInvisible(false);
    setPossessing(null);
    setStaffWorldInfo([]);
    setStaffMobTemplates([]);
    setServerCommands([]);
    setEmotePresets([]);
    resetMap();
  }, [resetMap]);

  const sendGmcpRef = useRef<(pkg: string, payload: unknown) => void>(() => {});

  const handleGmcp = useCallback(
    (pkg: string, data: unknown) => {
      applyGmcpPackage(
        pkg,
        data,
        {
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
            // Close shop popout when shop data is cleared (player left shop area)
            if (!value) {
              setActivePopout((prev) => prev === "shop" ? null : prev);
            }
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
          setTrainer: (value) => {
            setTrainer(value);
            // Auto-open trainer popout when trainer data arrives
            if (value) {
              setActivePopout("trainer");
            }
          },
          setUnlockedClasses,
          setWorldTime,
          setWorldWeather,
          setWorldEvents,
          sendGmcp: (pkg: string, payload: unknown) => { sendGmcpRef.current(pkg, payload); return true; },
        },
      );
    },
    [pushFriendNotification, pushCombatEvent, pushGainEvent, pushQuestNotification, pushUiFeedback, pushCraftingResult, pushMailNotification, updateMap, loadZoneMap],
  );

  const { connected, liveMessage, connect, disconnect, reconnect, sendLine, sendGmcp } = useMudSocket({
    onOpen: () => {
      focusComposer();
    },
    onTextMessage: (text) => {
      terminalRef.current?.write(text);
    },
    onGmcpMessage: handleGmcp,
    onClose: () => {
      if (intentionalDisconnectRef.current) {
        // Disconnect was intentional (manual reconnect or logout) — skip auto-reconnect.
        intentionalDisconnectRef.current = false;
        return;
      }
      if (resumeTokenRef.current) {
        // Auto-reconnect: keep game state, show reconnecting banner
        setReconnecting(true);
        writeSystem("Connection lost — reconnecting...");
        window.setTimeout(() => reconnect(), 500);
      } else {
        setComposerValue("");
        resetComposerTraversal();
        resetHud();
        audio.stopAll();
        writeSystem("Connection closed.");
      }
    },
    onError: () => {
      writeSystem("Connection error.");
    },
  });

  sendGmcpRef.current = sendGmcp;
  connectedRef.current = connected;

  // When a token-based login fails, the server re-sends Login.Prompt(state:"name").
  // Since we already know the character name, auto-send it so the user goes
  // straight to the password prompt instead of seeing "Enter your character name".
  useEffect(() => {
    const charName = failedAuthCharRef.current;
    if (loginPrompt?.state === "name" && charName) {
      failedAuthCharRef.current = null;
      sendLine(charName);
    }
  }, [loginPrompt, sendLine]);

  const sendCommand = useCallback(
    (raw: string, echo: boolean) => {
      const command = raw.trim();
      if (command.length === 0) return;
      if (!sendLine(command)) {
        writeSystem("Disconnected. Reconnect to send commands.");
        return;
      }

      pushHistory(command);
      if (echo) terminalRef.current?.write(`${command}\r\n`);
    },
    [pushHistory, sendLine, writeSystem],
  );

  useEffect(() => {
    if (!terminalHiddenRef.current) return;

    const term = new Terminal({
      cursorBlink: false,
      disableStdin: true,
      fontFamily: '"JetBrains Mono", "Cascadia Mono", monospace',
      fontSize: 14,
      rows: 30,
      convertEol: false,
      theme: {
        background: "#2f3446",
        foreground: "#d8dcef",
        cursor: "#b9aed8",
        selectionBackground: "rgba(185, 174, 216, 0.34)",
      },
    });

    const fitAddon = new FitAddon();
    term.loadAddon(fitAddon);
    term.open(terminalHiddenRef.current);

    terminalRef.current = term;
    fitAddonRef.current = fitAddon;

    return () => {
      term.dispose();
      fitAddonRef.current = null;
      terminalRef.current = null;
    };
  }, []);

  useEffect(() => {
    connect();
    const onResize = () => {
      fitTerminal();
      drawMap();
    };
    const onBeforeUnload = () => disconnect();

    // Auto-reconnect when returning from background (mobile app switch).
    // Uses connectedRef to avoid re-running the effect on every state change.
    const onVisibilityChange = () => {
      if (document.visibilityState === "visible" && !connectedRef.current && resumeTokenRef.current) {
        setReconnecting(true);
        intentionalDisconnectRef.current = true;
        reconnect();
      }
    };

    window.addEventListener("resize", onResize);
    window.addEventListener("beforeunload", onBeforeUnload);
    document.addEventListener("visibilitychange", onVisibilityChange);

    return () => {
      window.removeEventListener("resize", onResize);
      window.removeEventListener("beforeunload", onBeforeUnload);
      document.removeEventListener("visibilitychange", onVisibilityChange);
      disconnect();
    };
  }, [connect, disconnect, drawMap, fitTerminal, reconnect]);

  // Refit terminal when overlay becomes visible
  useEffect(() => {
    if (!terminalVisible) return;
    const frameFit = window.requestAnimationFrame(() => fitTerminal());
    const delayedFit = window.setTimeout(() => fitTerminal(), 90);
    return () => {
      window.cancelAnimationFrame(frameFit);
      window.clearTimeout(delayedFit);
    };
  }, [terminalVisible, fitTerminal]);

  useEffect(() => {
    const fontSet = document.fonts;
    if (!fontSet) return;
    let cancelled = false;
    const refit = () => {
      if (cancelled) return;
      fitTerminal();
    };
    fontSet.ready.then(refit).catch(() => undefined);
    fontSet.addEventListener("loadingdone", refit);
    return () => {
      cancelled = true;
      fontSet.removeEventListener("loadingdone", refit);
    };
  }, [fitTerminal]);

  useEffect(() => {
    if (!activePopout) return;

    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") {
        setActivePopout(null);
      }
    };

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [activePopout]);

  useEffect(() => {
    if (activePopout !== "map") return;
    const handle = window.requestAnimationFrame(() => drawMap());
    startPulse();
    return () => {
      window.cancelAnimationFrame(handle);
      stopPulse();
    };
  }, [activePopout, drawMap, startPulse, stopPulse]);

  // Reparent terminal into overlay when visible (or always in text mode), back to hidden when not
  useEffect(() => {
    const term = terminalRef.current;
    if (!term) return;
    const termEl = term.element;
    if (!termEl) return;

    if ((terminalVisible || textMode) && terminalOverlayRef.current) {
      terminalOverlayRef.current.appendChild(termEl);
      window.requestAnimationFrame(() => {
        fitTerminal();
        term.scrollToBottom();
      });
      const delayedFit = window.setTimeout(() => {
        fitTerminal();
        term.scrollToBottom();
      }, 80);
      return () => window.clearTimeout(delayedFit);
    } else if (terminalHiddenRef.current && termEl.parentElement !== terminalHiddenRef.current) {
      terminalHiddenRef.current.appendChild(termEl);
    }
  }, [terminalVisible, textMode, fitTerminal]);

  // Sync React state into the game state bridge for PixiJS
  useEffect(() => {
    gameStateRef.current = {
      room,
      vitals,
      mobs,
      players,
      roomItems,
      combatTarget,
      inCombat: vitals.inCombat,
      effects,
      character,
      mobInfo,
      groupInfo,
      dialogue,
      questsAvailable,
      shop,
      craftingNodes,
      questTargetRoomIds: new Set(
        quests.flatMap((q) =>
          q.objectives
            .filter((o) => o.current < o.required)
            .flatMap((o) => o.targetRoomIds ?? []),
        ),
      ),
      serverAssets,
    };
  });

  // Wire sendCommand callback for PixiJS click-to-interact
  useEffect(() => {
    canvasCallbacks.sendCommand = (cmd: string) => sendCommand(cmd, true);
    return () => { canvasCallbacks.sendCommand = null; };
  }, [sendCommand]);

  // Wire prefillCommand callback for context-menu actions that need user input
  useEffect(() => {
    canvasCallbacks.prefillCommand = (text: string) => {
      setComposerValue(text);
      focusComposer();
    };
    return () => { canvasCallbacks.prefillCommand = null; };
  }, [focusComposer]);

  // Wire canvas shop badge to open shop popout
  useEffect(() => {
    canvasCallbacks.openShop = () => setActivePopout("shop");
    return () => { canvasCallbacks.openShop = null; };
  }, []);

  // Wire canvas minimap expand, room expand, and quest panel buttons
  useEffect(() => {
    canvasCallbacks.openMap = () => setActivePopout("map");
    canvasCallbacks.openRoom = () => setActivePopout("room");
    canvasCallbacks.openQuests = () => setActivePopout("quests");
    canvasCallbacks.dismissDialogue = () => { setDialogue(null); setQuestsAvailable([]); };
    return () => { canvasCallbacks.openMap = null; canvasCallbacks.openRoom = null; canvasCallbacks.openQuests = null; canvasCallbacks.dismissDialogue = null; };
  }, []);

  // Wire canvas video cinematic callback
  useEffect(() => {
    canvasCallbacks.openVideo = (url: string) => setVideoUrl(url);
    return () => { canvasCallbacks.openVideo = null; };
  }, []);

  // Ctrl+K toggles the command palette
  useEffect(() => {
    const handler = (e: globalThis.KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === "k") {
        e.preventDefault();
        setShowCommandPalette((prev) => !prev);
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, []);

  // Play room audio when music/ambient URLs change
  useEffect(() => {
    audio.playMusic(room.music ?? null);
  }, [room.music]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    audio.playAmbient(room.ambient ?? null);
  }, [room.ambient]); // eslint-disable-line react-hooks/exhaustive-deps

  // Combat audio effects: speed up + filter on combat, pulse on low HP
  const hpPercent = vitals.maxHp > 0 ? vitals.hp / vitals.maxHp : 1;
  useEffect(() => {
    audio.setCombatState(vitals.inCombat, hpPercent);
  }, [vitals.inCombat, hpPercent]); // eslint-disable-line react-hooks/exhaustive-deps

  const exits = useMemo(() => sortExits(room.exits), [room.exits]);


  const xpText =
    vitals.xpToNextLevel === null
      ? "MAX"
      : `${vitals.xpIntoLevel.toLocaleString()} / ${vitals.xpToNextLevel.toLocaleString()}`;
  const xpValue = vitals.xpToNextLevel === null ? 1 : vitals.xpIntoLevel;
  const xpMax = vitals.xpToNextLevel === null ? 1 : Math.max(1, vitals.xpToNextLevel);
  const visibleEffects = effects.slice(0, MAX_VISIBLE_EFFECTS);
  const hiddenEffectsCount = Math.max(0, effects.length - visibleEffects.length);
  const displayRace = character.race ? titleCaseWords(character.race) : "";
  const displayClassName = character.className ? titleCaseWords(character.className) : "";
  const hasCharacterProfile = character.name !== "-";
  const hasRoomDetails = room.id !== null || room.title !== "-";
  const preLogin = connected && !hasCharacterProfile && !hasRoomDetails;
  const canOpenEquipment = hasCharacterProfile;
  const commandPlaceholder = connected
    ? preLogin
      ? "Log in to begin your adventure"
      : "Type a command"
    : "Reconnect to start playing";
  const popoutTitle =
    activePopout === "map"
      ? "Mini-map"
      : activePopout === "room"
        ? "Room Details"
      : activePopout === "inventory"
        ? "Inventory"
      : activePopout === "equipment"
        ? "Equipment"
      : activePopout === "help"
        ? "Command Reference"
      : activePopout === "character"
        ? "Character"
      : activePopout === "chat"
        ? "Social"
      : activePopout === "shop"
        ? (shop?.name ?? "Shop")
      : activePopout === "trainer"
        ? (trainer?.name ?? "Trainer")
      : activePopout === "spellbook"
        ? "Spellbook"
      : activePopout === "quests"
        ? "Quests"
      : activePopout === "housing"
        ? "Housing"
        : "";

  const submitComposer = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const liveValue = composerInputRef.current?.value ?? composerValue;
    const command = liveValue.trim();
    if (!command) return;
    sendCommand(command, true);
    setComposerValue("");
    resetComposerTraversal();
  };

  const onComposerKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    const liveValue = event.currentTarget.value;

    if (event.key === "ArrowUp") {
      event.preventDefault();
      applyComposerHistoryUp(liveValue, setComposerValue);
      return;
    }

    if (event.key === "ArrowDown") {
      event.preventDefault();
      applyComposerHistoryDown(setComposerValue);
      return;
    }

    if (event.key === "Tab") {
      event.preventDefault();
      applyComposerCompletion(liveValue, setComposerValue);
      return;
    }

    resetComposerCompletion();
  };

  const sendChatMessage = useCallback(
    (channel: ChatChannel, message: string, target: string | null): boolean => {
      if (!connected || !hasCharacterProfile) return false;

      const body = message.trim();
      if (body.length === 0) return false;

      const command =
        channel === "tell"
          ? (() => {
              const targetName = target?.trim() ?? "";
              if (targetName.length === 0) return null;
              return `${channel} ${targetName} ${body}`;
            })()
          : `${channel} ${body}`;
      if (command === null) return false;

      sendCommand(command, true);
      focusComposer();
      return true;
    },
    [connected, focusComposer, hasCharacterProfile, sendCommand],
  );

  const completeCast = useCallback(
    (skillId: string, cooldownMs: number, targetName?: string) => {
      const now = Date.now();
      setSkills((prev) =>
        prev.map((skill) => (
          skill.id === skillId
            ? {
                ...skill,
                cooldownRemainingMs: Math.max(skill.cooldownRemainingMs, cooldownMs),
                receivedAt: now,
              }
            : skill
        )),
      );
      const cmd = targetName ? `cast ${skillId} ${targetName}` : `cast ${skillId}`;
      sendCommand(cmd, false);
      pendingCastRef.current = null;
    },
    [sendCommand],
  );

  const handleCastSkill = useCallback(
    (skillId: string, cooldownMs: number) => {
      const skill = skills.find((s) => s.id === skillId);
      if (!skill) return;
      const needsTarget = skill.targetType === "ENEMY" || skill.targetType === "ALLY";
      // If it needs a target and we already have a combat target, use that
      if (needsTarget && gameStateRef.current.combatTarget?.targetName) {
        completeCast(skillId, cooldownMs, gameStateRef.current.combatTarget.targetName);
        return;
      }
      // If it needs a target but we don't have one, enter targeting mode
      if (needsTarget) {
        pendingCastRef.current = { skillId, skillName: skill.name, cooldownMs, targetType: skill.targetType };
        setToast(`Select a target for ${skill.name}`);
        return;
      }
      completeCast(skillId, cooldownMs);
    },
    [skills, completeCast],
  );

  // Wire up canvas target selection callback
  useEffect(() => {
    canvasCallbacks.onTargetSelected = (targetName: string) => {
      const pending = pendingCastRef.current;
      if (!pending) return;
      setToast(null);
      completeCast(pending.skillId, pending.cooldownMs, targetName);
    };
    return () => { canvasCallbacks.onTargetSelected = null; };
  }, [completeCast]);

  // Keyboard shortcuts: digits 1-6 cast quickbar skills when no text input is focused
  useEffect(() => {
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape" && pendingCastRef.current) {
        pendingCastRef.current = null;
        setToast(null);
        return;
      }
      if (!hasCharacterProfile || !connected) return;
      const target = event.target as HTMLElement;
      if (target.tagName === "INPUT" || target.tagName === "TEXTAREA") return;
      const digit = parseInt(event.key, 10);
      if (digit >= 1 && digit <= 9) {
        const skill = quickbar.slots[digit - 1];
        if (!skill) return;
        const elapsed = Date.now() - skill.receivedAt;
        const remaining = Math.max(0, skill.cooldownRemainingMs - elapsed);
        if (remaining > 0) return;
        handleCastSkill(skill.id, skill.cooldownMs);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [connected, hasCharacterProfile, quickbar.slots, handleCastSkill]);

  // Auto-dismiss look target after 6 seconds
  useEffect(() => {
    if (!lookTarget) return;
    const t = setTimeout(() => setLookTarget(null), 6000);
    return () => clearTimeout(t);
  }, [lookTarget]);

  // Auto-dismiss toast after 4 seconds
  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => {
      setToast(null);
      pendingCastRef.current = null;
    }, 4000);
    return () => clearTimeout(timer);
  }, [toast]);

  return (
    <main className="app-shell">
      <div className="ambient-orb ambient-orb-a" aria-hidden="true" />
      <div className="ambient-orb ambient-orb-b" aria-hidden="true" />

      <header className="top-banner">
        <div>
          <h1 className="top-banner-title">AmbonMUD</h1>
        </div>

        <div className="connection-cluster">
          <button
            type="button"
            className="soft-button layout-mode-button"
            onClick={() => setLayoutMode((m) => m === "auto" ? "text" : m === "text" ? "canvas" : "auto")}
            title={`Layout: ${layoutMode === "auto" ? "Auto" : layoutMode === "text" ? "Text" : "Canvas"} — click to cycle`}
            aria-label={`Layout mode: ${layoutMode}. Click to change.`}
          >
            {layoutMode === "auto" ? "\u2728 Auto" : layoutMode === "text" ? "\uD83D\uDCDC Text" : "\uD83C\uDFA8 Canvas"}
          </button>
          <AudioControls audio={audio} />
          {character.isStaff && (
            <>
              <button
                type="button"
                className="soft-button staff-admin-button"
                onClick={() => setShowAdminPanel(true)}
                title="Staff Administration"
                aria-label="Open staff administration panel"
              >
                Staff
              </button>
              <button
                type="button"
                className={`soft-button staff-invis-button ${staffInvisible ? "staff-invis-active" : ""}`}
                onClick={() => { sendCommand("invis", true); setStaffInvisible(!staffInvisible); }}
                title={staffInvisible ? "You are invisible — click to become visible" : "Click to become invisible"}
                aria-label="Toggle staff invisibility"
                aria-pressed={staffInvisible}
              >
                {staffInvisible ? "\uD83D\uDC41\u200D\uD83D\uDDE8" : "\uD83D\uDC41"}
              </button>
            </>
          )}
          <span
            className={`connection-pill ${connected ? "connection-pill-online" : "connection-pill-offline"}`}
            role="status"
            aria-live="polite"
          >
            {connected ? "Connected" : "Disconnected"}
          </span>
          <button
            type="button"
            className={`soft-button reconnect-btn${connected ? "" : " reconnect-btn-needed"}`}
            onClick={() => { intentionalDisconnectRef.current = true; reconnect(); }}
          >
            Reconnect
          </button>
        </div>
      </header>

      <div className="dashboard">
        <PlayPanel
          preLogin={preLogin}
          terminalOverlayRef={terminalOverlayRef}
          terminalVisible={terminalVisible}
          terminalOpaque={terminalOpaque}
          textMode={textMode}
          combatLogMessages={combatLogMessages}
        />

        <ActionBar
          connected={connected}
          hasCharacterProfile={hasCharacterProfile}
          vitals={vitals}
          quickbarSlots={quickbar.slots}
          shop={shop}
          questCount={quests.length}
          mailUnreadCount={(mailInbox ?? []).filter((m) => !m.read).length}
          activePopout={activePopout}
          onOpenPopout={setActivePopout}
          onCastSkill={handleCastSkill}
          onQuickbarSwap={quickbar.swap}
          onQuickbarAssign={quickbar.assign}
          onQuickbarClear={quickbar.clear}
          composerInputRef={composerInputRef}
          composerValue={composerValue}
          commandPlaceholder={commandPlaceholder}
          onComposerChange={(value) => {
            setComposerValue(value);
            resetComposerCompletion();
            if (value.length > 0) setTerminalOpaque(true);
          }}
          onComposerKeyDown={onComposerKeyDown}
          onComposerFocus={() => {
            setTerminalVisible(true);
          }}
          onComposerBlur={() => {
            setTerminalVisible(false);
            setTerminalOpaque(false);
          }}
          onSubmitComposer={submitComposer}
          zoneInstances={zoneInstances}
          onPhaseSwitch={(engineId) => { sendCommand(`phase ${engineId}`, true); focusComposer(); }}
          mobs={mobs}
          combatTarget={combatTarget}
          onCommand={(cmd) => { sendCommand(cmd, true); focusComposer(); }}
        />
      </div>

      <PopoutLayer
        activePopout={activePopout}
        popoutTitle={popoutTitle}
        room={room}
        exits={exits}
        roomFeatures={roomFeatures}
        containerContents={containerContents}
        mapCanvasRef={mapCanvasRef}
        questMarkerCount={new Set(quests.flatMap((q) => q.objectives.filter((o) => o.current < o.required).flatMap((o) => o.targetRoomIds ?? []))).size}
        isStaff={character.isStaff}
        serverCommands={serverCommands}
        craftingNodes={craftingNodes}
        onClose={() => setActivePopout(null)}
        onFeatureAction={(cmd) => sendCommand(cmd, true)}
      >
        {activePopout === "character" && (
          <CharacterPanel
            connected={connected}
            hasCharacterProfile={hasCharacterProfile}
            canOpenEquipment={canOpenEquipment}
            character={character}
            displayRace={displayRace}
            displayClassName={displayClassName}
            vitals={vitals}
            statusVarLabels={statusVarLabels}
            xpValue={xpValue}
            xpMax={xpMax}
            xpText={xpText}
            effects={effects}
            visibleEffects={visibleEffects}
            hiddenEffectsCount={hiddenEffectsCount}
            achievements={achievements}
            quests={quests}
            questNotifications={questNotifications}
            charStats={charStats}
            guildInfo={guildInfo}
            groupInfo={groupInfo}
            activeTitle={whoPlayers.find((p) => p.name === character.name)?.title ?? null}
            spriteList={spriteList}
            worldTime={worldTime}
            worldWeather={worldWeather}
            worldEvents={worldEvents}
            onDismissQuestNotification={(id) => {
              setQuestNotifications((prev) => prev.filter((n) => n.id !== id));
            }}
            onAbandonQuest={(questName) => {
              sendCommand(`quest abandon ${questName}`, true);
              focusComposer();
            }}
            onOpenInventory={() => setActivePopout("inventory")}
            onOpenEquipment={() => setActivePopout("equipment")}
            onCommand={(cmd) => { sendCommand(cmd, true); focusComposer(); }}
            onLogout={() => {
              // Clear auth token from localStorage for this character
              try {
                const saved = JSON.parse(localStorage.getItem("ambonmud_auth_tokens") ?? "{}") as Record<string, string>;
                delete saved[character.name];
                localStorage.setItem("ambonmud_auth_tokens", JSON.stringify(saved));
              } catch { /* ignore */ }
              // Clear resume token so disconnect doesn't auto-reconnect
              resumeTokenRef.current = null;
              // Tell server to clear the token hash and disconnect
              sendGmcp("Session.Logout", {});
            }}
          />
        )}

        {activePopout === "inventory" && (
          <InventoryPanel
            connected={connected}
            hasCharacterProfile={hasCharacterProfile}
            inventory={inventory}
            players={players}
            canManageItems={connected && hasCharacterProfile}
            roomFeatures={roomFeatures}
            containerContents={containerContents}
            onWearItem={(itemName) => {
              sendCommand(`wear ${itemName}`, true);
              focusComposer();
            }}
            onDropItem={(itemName) => {
              sendCommand(`drop ${itemName}`, true);
              focusComposer();
            }}
            onGiveItem={(itemKeyword, playerName) => {
              sendCommand(`give ${itemKeyword} ${playerName}`, true);
              focusComposer();
            }}
            onCommand={(cmd) => { sendCommand(cmd, true); focusComposer(); }}
          />
        )}

        {activePopout === "equipment" && (
          <EquipmentPanel
            connected={connected}
            hasCharacterProfile={hasCharacterProfile}
            character={character}
            equipment={equipment}
            slotDefs={equipmentSlotDefs}
            canManageItems={connected && hasCharacterProfile}
            onRemoveItem={(slot) => {
              sendCommand(`remove ${slot}`, true);
              focusComposer();
            }}
          />
        )}

        {activePopout === "chat" && (
          <ChatPanel
            connected={connected}
            canChat={connected && hasCharacterProfile}
            playerName={character.name}
            activeChannel={activeChatChannel}
            chatByChannel={chatByChannel}
            emotePresets={emotePresets}
            whoPlayers={whoPlayers}
            groupInfo={groupInfo}
            pendingGroupInvite={pendingGroupInvite}
            guildInfo={guildInfo}
            pendingGuildInvite={pendingGuildInvite}
            guildMembers={guildMembers}
            friends={friends}
            friendNotifications={friendNotifications}
            onChannelChange={setActiveChatChannel}
            onRequestWho={() => {
              sendCommand("who", true);
              focusComposer();
            }}
            onSendMessage={sendChatMessage}
            onCommand={(cmd) => { sendCommand(cmd, true); focusComposer(); }}
          />
        )}

        {activePopout === "shop" && shop && (
          <ShopPopout
            shop={shop}
            inventory={inventory}
            gold={vitals.gold}
            onBuyItem={(keyword) => {
              sendCommand(`buy ${keyword}`, true);
              focusComposer();
            }}
            onSellItem={(keyword) => {
              sendCommand(`sell ${keyword}`, true);
              focusComposer();
            }}
          />
        )}

        {tradeState && tradeState.active && (
          <TradePanel
            trade={tradeState}
            onCommand={(cmd) => { sendCommand(cmd, true); focusComposer(); }}
          />
        )}

        {activePopout === "trainer" && trainer && (
          <TrainerPanel
            trainer={trainer}
            playerLevel={vitals.level ?? 1}
            playerGold={vitals.gold}
            onCommand={(cmd) => { sendCommand(cmd, true); focusComposer(); }}
          />
        )}

        {activePopout === "spellbook" && (
          <SpellbookPanel
            skills={skills}
            quickbarSlotIds={quickbar.slotIds}
            playerClass={displayClassName}
            playerLevel={vitals.level ?? 1}
            availableSkillPoints={trainer?.availableSkillPoints}
            onShowSkillInfo={(skill) => {
              const cd = skill.cooldownMs > 0 ? `${skill.cooldownMs / 1000}s cooldown` : "No cooldown";
              setToast(`${skill.name} — ${skill.manaCost} MP, ${cd}, ${skill.targetType.toLowerCase()} target`);
            }}
            onAssignSlot={quickbar.assign}
          />
        )}

        {activePopout === "mail" && (
          <MailPanel
            connected={connected}
            hasCharacterProfile={hasCharacterProfile}
            inbox={mailInbox}
            openMessage={mailMessage}
            onReadMessage={(index) => {
              sendCommand(`mail read ${index}`, true);
            }}
            onDeleteMessage={(index) => {
              sendCommand(`mail delete ${index}`, true);
            }}
            onCompose={(recipient, body) => {
              sendCommand(`mail send ${recipient}`, false);
              for (const line of body.split("\n")) {
                sendCommand(line, false);
              }
              sendCommand(".", false);
            }}
            onClearMessage={() => setMailMessage(null)}
          />
        )}

        {activePopout === "crafting" && (
          <CraftingPanel
            connected={connected}
            hasCharacterProfile={hasCharacterProfile}
            skills={craftingSkills}
            recipes={craftingRecipes}
            nodes={craftingNodes}
            onGather={(keyword) => sendCommand(`gather ${keyword}`, true)}
            onCraft={(recipeKeyword) => sendCommand(`craft ${recipeKeyword}`, true)}
            onRequestRecipes={() => sendCommand("recipes", true)}
            onLoadSkills={() => sendCommand("craftskills", true)}
          />
        )}

        {activePopout === "quests" && (
          <QuestPanel
            connected={connected}
            hasCharacterProfile={hasCharacterProfile}
            quests={quests}
            questsAvailable={questsAvailable}
            questNotifications={questNotifications}
            onDismissQuestNotification={(id) => {
              setQuestNotifications((prev) => prev.filter((n) => n.id !== id));
            }}
            onAbandonQuest={(questName) => {
              sendCommand(`quest abandon ${questName}`, true);
              focusComposer();
            }}
            onAcceptQuest={(questName) => {
              sendCommand(`accept ${questName}`, true);
              focusComposer();
            }}
          />
        )}

        {activePopout === "housing" && (
          <HousingPanel
            connected={connected}
            hasCharacterProfile={hasCharacterProfile}
            housing={housing}
            room={room}
            onSendCommand={(cmd) => { sendCommand(cmd, true); focusComposer(); }}
          />
        )}

        {activePopout === "leaderboard" && (
          <LeaderboardPanel
            leaderboard={leaderboard}
            onCommand={(cmd) => { sendCommand(cmd, true); focusComposer(); }}
          />
        )}
      </PopoutLayer>

      {reconnecting && (
        <div className="reconnect-banner" role="status" aria-live="polite">
          <span className="reconnect-spinner" aria-hidden="true" />
          Reconnecting...
        </div>
      )}

      {savedCharacters.length >= 1 && !reconnecting && (
        <CharacterPicker
          characters={savedCharacters}
          onSelect={(name) => {
            try {
              const saved = JSON.parse(localStorage.getItem("ambonmud_auth_tokens") ?? "{}") as Record<string, string>;
              const token = saved[name];
              if (token) {
                pendingAuthCharRef.current = name;
                setSavedCharacters([]);
                setLoginPrompt(null);
                setReconnecting(true);
                sendGmcp("Session.Authenticate", { token });
                return;
              }
            } catch { /* ignore */ }
            // Token missing — fall back to normal login.
            // loginPrompt was set alongside savedCharacters in the Login.Prompt
            // handler, so clearing savedCharacters reveals the LoginModal.
            setSavedCharacters([]);
          }}
          onRemoveCharacter={(name) => {
            try {
              const saved = JSON.parse(localStorage.getItem("ambonmud_auth_tokens") ?? "{}") as Record<string, string>;
              delete saved[name];
              localStorage.setItem("ambonmud_auth_tokens", JSON.stringify(saved));
              const remaining = Object.keys(saved);
              if (remaining.length === 0) {
                setSavedCharacters([]);
              } else {
                setSavedCharacters(remaining);
              }
            } catch {
              setSavedCharacters([]);
            }
          }}
          onNewCharacter={() => {
            setSavedCharacters([]);
            // Let the login prompt show naturally (it should already be pending)
          }}
        />
      )}

      {loginPrompt && !reconnecting && savedCharacters.length === 0 && (
        <LoginModal
          loginPrompt={loginPrompt}
          loginError={loginError}
          onSubmit={(value) => {
            sendLine(value);
            terminalRef.current?.write(`${value}\r\n`);
          }}
        />
      )}

      {videoUrl && (
        <div
          className={`video-modal-overlay ${videoClosing ? "video-fade-out" : "video-fade-in"}`}
          role="dialog"
          aria-modal="true"
          aria-label="Video cinematic"
          onClick={() => {
            setVideoClosing(true);
            setTimeout(() => { setVideoUrl(null); setVideoClosing(false); }, 600);
          }}
          onKeyDown={(e) => {
            if (e.key === "Escape") {
              setVideoClosing(true);
              setTimeout(() => { setVideoUrl(null); setVideoClosing(false); }, 600);
            }
          }}
          onAnimationEnd={(e) => {
            if (e.animationName === "videoFadeOut") { setVideoUrl(null); setVideoClosing(false); }
          }}
        >
          <div className="video-modal" onClick={(e) => e.stopPropagation()}>
            <button
              className="video-modal-close"
              aria-label="Close video"
              autoFocus
              onClick={() => {
                setVideoClosing(true);
                setTimeout(() => { setVideoUrl(null); setVideoClosing(false); }, 600);
              }}
            >
              ✕
            </button>
            <video
              ref={(el) => { if (el) el.playbackRate = 0.5; }}
              src={videoUrl}
              controls
              autoPlay
              muted
              className="video-modal-player"
            />
          </div>
        </div>
      )}

      {showAdminPanel && (
        <AdminPanel
          onCommand={(command) => {
            sendCommand(command, true);
            focusComposer();
          }}
          onClose={() => setShowAdminPanel(false)}
          worldInfo={staffWorldInfo}
          mobTemplates={staffMobTemplates}
          whoPlayers={whoPlayers}
        />
      )}

      {showCommandPalette && (
        <CommandPalette
          commands={serverCommands}
          isStaff={character.isStaff}
          onExecute={(cmd) => {
            sendCommand(cmd, true);
            focusComposer();
          }}
          onPrefill={(text) => {
            setComposerValue(text);
            focusComposer();
          }}
          onClose={() => setShowCommandPalette(false)}
        />
      )}

      {/* Hidden terminal container — xterm lives here when popout is closed */}
      <div ref={terminalHiddenRef} className="terminal-hidden" aria-hidden="true" />

      <p className="sr-only" aria-live="polite">{liveMessage}</p>

      {lookTarget && (
        <div className="look-target-card" role="dialog" aria-label="Inspect target" onClick={() => setLookTarget(null)}>
          <div className="look-target-header">
            <span className="look-target-name">{lookTarget.name}</span>
            {lookTarget.level != null && (
              <span className="look-target-meta">
                Lv {lookTarget.level}
                {lookTarget.race && ` ${lookTarget.race}`}
                {lookTarget.playerClass && ` ${lookTarget.playerClass}`}
              </span>
            )}
          </div>
          <p className="look-target-desc">{lookTarget.description}</p>
          {lookTarget.image && <img src={lookTarget.image} alt="" className="look-target-image" />}
        </div>
      )}
      {possessing && (
        <>
          <div className="possession-vignette" aria-hidden="true" />
          <div className="possession-badge" role="status">
            <span className="possession-badge-icon" aria-hidden="true">{"\uD83D\uDC41"}</span>
            Possessing <strong>{possessing}</strong>
          </div>
        </>
      )}
      {broadcast && (
        <div className="broadcast-overlay" role="alertdialog" aria-modal="true" aria-label="Server announcement">
          <div className="broadcast-card">
            <div className="broadcast-header">
              <span className="broadcast-icon" aria-hidden="true">{"\uD83D\uDCE2"}</span>
              <span className="broadcast-title">Server Announcement</span>
            </div>
            <p className="broadcast-message">{broadcast.message}</p>
            <div className="broadcast-footer">
              <span className="broadcast-sender">— {broadcast.sender}</span>
              <button
                type="button"
                className="broadcast-dismiss"
                onClick={() => setBroadcast(null)}
                autoFocus
              >
                Dismiss
              </button>
            </div>
          </div>
        </div>
      )}
      {toast && (
        <div className="game-toast" role="alert">
          {toast}
        </div>
      )}
    </main>
  );
}

export default App;
