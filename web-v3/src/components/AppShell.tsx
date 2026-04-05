import { useCallback, useEffect, useMemo, useRef } from "react";
import type { FormEvent, KeyboardEvent } from "react";
import { FitAddon } from "@xterm/addon-fit";
import { Terminal } from "@xterm/xterm";
import { ActionBar } from "./ActionBar";
import { AudioControls } from "./AudioControls";
import { PopoutLayer } from "./PopoutLayer";
import { ShopPopout } from "./ShopPopout";
import { TrainerPanel } from "./TrainerPanel";
import { TradePanel } from "./TradePanel";
import { ChatPanel } from "./panels/ChatPanel";
import { CharacterPanel } from "./panels/CharacterPanel";
import { SpellbookPanel } from "./SpellbookPanel";
import { QuestPanel } from "./panels/QuestPanel";
import { InventoryPanel } from "./panels/InventoryPanel";
import { EquipmentPanel } from "./panels/EquipmentPanel";
import { PlayPanel } from "./panels/PlayPanel";
import { AdminPanel } from "./panels/AdminPanel";
import { MailPanel } from "./panels/MailPanel";
import { CraftingPanel } from "./panels/CraftingPanel";
import { HousingPanel } from "./panels/HousingPanel";
import { LeaderboardPanel } from "./panels/LeaderboardPanel";
import { BankPanel } from "./panels/BankPanel";
import { AuctionPanel } from "./panels/AuctionPanel";
import { CommandPalette } from "./CommandPalette";
import { applyGmcpPackage } from "../gmcp/applyGmcpPackage";
import type { GmcpDispatchMap } from "../gmcp/applyGmcpPackage";
import { canvasCallbacks, pendingCastRef } from "../canvas/GameStateBridge";
import { canvasEvents } from "../canvas/CanvasEventBus";
import { LoginModal } from "../canvas/LoginModal";
import { CharacterPicker } from "./CharacterPicker";
import { MAX_VISIBLE_EFFECTS } from "../constants";
import { useCommandHistory } from "../hooks/useCommandHistory";
import { useMiniMap } from "../hooks/useMiniMap";
import { useMudSocket } from "../hooks/useMudSocket";
import { useAudioEngine } from "../hooks/useAudioEngine";
import { useQuickbar } from "../hooks/useQuickbar";
import { useCoreState, useCoreDispatch } from "../state/CoreContext";
import { useCombatState, useCombatDispatch } from "../state/CombatContext";
import { useInventoryState, useInventoryDispatch } from "../state/InventoryContext";
import { useSocialState, useSocialDispatch } from "../state/SocialContext";
import { useWorldState, useWorldDispatch } from "../state/WorldContext";
import { useQuestState, useQuestDispatch } from "../state/QuestContext";
import { useUIState, useUIDispatch } from "../state/UIContext";
import type {
  ChatChannel,
  CombatEventData,
  CombatLogMessage,
  CraftingResult,
  GainEvent,
  MailNotification,
  PopoutPanel,
} from "../types";
import { sortExits, titleCaseWords } from "../utils";

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

export function AppShell() {
  // ---- Context state & dispatch ----
  const coreState = useCoreState();
  const coreDispatch = useCoreDispatch();
  const combatState = useCombatState();
  const combatDispatch = useCombatDispatch();
  const inventoryState = useInventoryState();
  const inventoryDispatch = useInventoryDispatch();
  const socialState = useSocialState();
  const socialDispatch = useSocialDispatch();
  const worldState = useWorldState();
  const worldDispatch = useWorldDispatch();
  const questState = useQuestState();
  const questDispatch = useQuestDispatch();
  const uiState = useUIState();
  const uiDispatch = useUIDispatch();

  // ---- Destructure frequently used state ----
  const { vitals, character, charStats, spriteList, unlockedClasses, loginPrompt, loginError, reconnecting, savedCharacters, serverCommands, emotePresets, prestigeInfo } = coreState;
  const { combatTarget, effects, skills, combatLogMessages, duelState, duelChallenge, petState } = combatState;
  const { inventory, equipment, equipmentSlotDefs, shop, craftingSkills, craftingRecipes, craftingNodes, tradeState, auctionListings, bankState, currencies } = inventoryState;
  const { chatByChannel, activeChatChannel, groupInfo, pendingGroupInvite, guildInfo, pendingGuildInvite, guildMembers, guildHall, friends, whoPlayers, lotteryInfo } = socialState;
  const { room, players, mobs, roomFeatures, zoneInstances, worldTime, worldWeather, worldEvents, dungeonInfo } = worldState;
  const { quests, questsAvailable, achievements, dailyQuests, weeklyQuests, autoQuest, globalQuest, questNotifications } = questState;
  const { activePopout, showAdminPanel, showCommandPalette, terminalVisible, terminalOpaque, layoutMode, composerValue, videoUrl, videoClosing, toast, lookTarget, broadcast, staffInvisible, possessing, containerContents, staffWorldInfo, staffMobTemplates, mailInbox, mailMessage, friendNotifications, trainer, housing, leaderboard, factions } = uiState;

  // ---- Refs ----
  const terminalHiddenRef = useRef<HTMLDivElement | null>(null);
  const terminalOverlayRef = useRef<HTMLDivElement | null>(null);
  const composerInputRef = useRef<HTMLInputElement | null>(null);
  const terminalRef = useRef<Terminal | null>(null);
  const fitAddonRef = useRef<FitAddon | null>(null);
  const connectedRef = useRef(false);
  const intentionalDisconnectRef = useRef(false);
  const resumeTokenRef = useRef<string | null>(null);
  const pendingAuthCharRef = useRef<string | null>(null);
  const failedAuthCharRef = useRef<string | null>(null);
  const combatEventsRef = useRef<CombatEventData[]>([]);
  const gainEventsRef = useRef<GainEvent[]>([]);

  // ---- Derived values ----
  const textMode = layoutMode === "text" || (layoutMode === "auto" && !room.graphical);
  const audio = useAudioEngine();
  const quickbar = useQuickbar(skills);
  const exits = useMemo(() => sortExits(room.exits), [room.exits]);
  const xpText = vitals.xpToNextLevel === null ? "MAX" : `${vitals.xpIntoLevel.toLocaleString()} / ${vitals.xpToNextLevel.toLocaleString()}`;
  const xpValue = vitals.xpToNextLevel === null ? 1 : vitals.xpIntoLevel;
  const xpMax = vitals.xpToNextLevel === null ? 1 : Math.max(1, vitals.xpToNextLevel);
  const visibleEffects = effects.slice(0, MAX_VISIBLE_EFFECTS);
  const hiddenEffectsCount = Math.max(0, effects.length - visibleEffects.length);
  const displayRace = character.race ? titleCaseWords(character.race) : "";
  const displayClassName = character.className ? titleCaseWords(character.className) : "";
  const hasCharacterProfile = character.name !== "-";
  const hasRoomDetails = room.id !== null || room.title !== "-";
  const canOpenEquipment = hasCharacterProfile;
  void unlockedClasses; // stored for future character sheet multi-class display

  // Persist layout mode to localStorage
  useEffect(() => {
    try { localStorage.setItem("ambonmud_layout_mode", layoutMode); } catch { /* ignore */ }
  }, [layoutMode]);

  // ---- Imperative callbacks ----
  const pushCombatEvent = useCallback((event: CombatEventData) => {
    combatEventsRef.current = [...combatEventsRef.current.slice(-99), event];
    canvasEvents.push(event);
    const logMsg = combatEventToLogMessage(event);
    if (logMsg) combatDispatch({ type: "PUSH_COMBAT_LOG_MESSAGE", payload: logMsg });
  }, [combatDispatch]);

  const pushGainEvent = useCallback((event: GainEvent) => {
    gainEventsRef.current = [...gainEventsRef.current.slice(-49), event];
    canvasEvents.push(event);
  }, []);

  const pushCraftingResult = useCallback((result?: CraftingResult) => {
    if (!result) return;
    const verb = result.type === "gather" ? "Gathered" : "Crafted";
    const item = result.itemName ? ` ${result.itemName}${result.quantity && result.quantity > 1 ? ` x${result.quantity}` : ""}` : "";
    const xp = result.xpAwarded > 0 ? ` (+${result.xpAwarded} ${result.skill} XP)` : "";
    const levelUp = result.leveledUp ? ` \u2014 Level up! ${result.skill} \u2192 Lv ${result.newLevel}` : "";
    combatDispatch({
      type: "PUSH_COMBAT_LOG_MESSAGE",
      payload: { id: ++combatLogIdCounter, text: `${verb}${item}${xp}${levelUp}`, style: result.leveledUp ? "xp" : "heal", receivedAt: Date.now() },
    });
  }, [combatDispatch]);

  const pushMailNotification = useCallback((notification?: MailNotification) => {
    if (!notification) return;
    combatDispatch({
      type: "PUSH_COMBAT_LOG_MESSAGE",
      payload: { id: ++combatLogIdCounter, text: `New mail from ${notification.from}`, style: "info", receivedAt: Date.now() },
    });
  }, [combatDispatch]);

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

  const focusComposer = useCallback(() => {
    window.requestAnimationFrame(() => composerInputRef.current?.focus());
  }, []);

  const fitTerminal = useCallback(() => {
    const term = terminalRef.current;
    const fitAddon = fitAddonRef.current;
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
    coreDispatch({ type: "RESET" });
    combatDispatch({ type: "RESET" });
    inventoryDispatch({ type: "RESET" });
    socialDispatch({ type: "RESET" });
    worldDispatch({ type: "RESET" });
    questDispatch({ type: "RESET" });
    uiDispatch({ type: "RESET" });
    combatEventsRef.current = [];
    gainEventsRef.current = [];
    resetMap();
  }, [coreDispatch, combatDispatch, inventoryDispatch, socialDispatch, worldDispatch, questDispatch, uiDispatch, resetMap]);

  const sendGmcpRef = useRef<(pkg: string, payload: unknown) => void>(() => {});

  // ---- Build GmcpDispatchMap ----
  const gmcpDispatchMap: GmcpDispatchMap = useMemo(() => ({
    core: coreDispatch,
    combat: combatDispatch,
    inventory: inventoryDispatch,
    social: socialDispatch,
    world: worldDispatch,
    quest: questDispatch,
    ui: uiDispatch,
    updateMap,
    loadZoneMap,
    sendGmcp: (pkg: string, payload: unknown) => { sendGmcpRef.current(pkg, payload); return true; },
    pushCombatEvent,
    pushGainEvent,
    pushCraftingResult,
    pushMailNotification,
    resumeTokenRef,
    pendingAuthCharRef,
    failedAuthCharRef,
  }), [coreDispatch, combatDispatch, inventoryDispatch, socialDispatch, worldDispatch, questDispatch, uiDispatch, updateMap, loadZoneMap, pushCombatEvent, pushGainEvent, pushCraftingResult, pushMailNotification]);

  const handleGmcp = useCallback(
    (pkg: string, data: unknown) => {
      applyGmcpPackage(pkg, data, gmcpDispatchMap);
    },
    [gmcpDispatchMap],
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
        intentionalDisconnectRef.current = false;
        return;
      }
      if (resumeTokenRef.current) {
        coreDispatch({ type: "SET_RECONNECTING", payload: true });
        writeSystem("Connection lost \u2014 reconnecting...");
        window.setTimeout(() => reconnect(), 500);
      } else {
        uiDispatch({ type: "SET_COMPOSER_VALUE", payload: "" });
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

  // Derived values that depend on connected
  const actualPreLogin = connected && !hasCharacterProfile && !hasRoomDetails;
  const commandPlaceholder = connected
    ? actualPreLogin
      ? "Log in to begin your adventure"
      : "Type a command"
    : "Reconnect to start playing";
  const popoutTitle =
    activePopout === "map" ? "Mini-map"
    : activePopout === "room" ? "Room Details"
    : activePopout === "inventory" ? "Inventory"
    : activePopout === "equipment" ? "Equipment"
    : activePopout === "help" ? "Command Reference"
    : activePopout === "character" ? "Character"
    : activePopout === "chat" ? "Social"
    : activePopout === "shop" ? (shop?.name ?? "Shop")
    : activePopout === "trainer" ? (trainer?.name ?? "Trainer")
    : activePopout === "spellbook" ? "Spellbook"
    : activePopout === "quests" ? "Quests"
    : activePopout === "housing" ? "Housing"
    : activePopout === "bank" ? "Bank"
    : activePopout === "auction" ? "Auction House"
    : "";

  // When a token-based login fails, auto-send character name
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

  // ---- Terminal setup ----
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

  // ---- Connection + global event handlers ----
  useEffect(() => {
    connect();
    const onResize = () => { fitTerminal(); drawMap(); };
    const onBeforeUnload = () => disconnect();
    const onVisibilityChange = () => {
      if (document.visibilityState === "visible" && !connectedRef.current && resumeTokenRef.current) {
        coreDispatch({ type: "SET_RECONNECTING", payload: true });
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
  }, [connect, disconnect, drawMap, fitTerminal, reconnect, coreDispatch]);

  // Refit terminal when overlay becomes visible
  useEffect(() => {
    if (!terminalVisible) return;
    const frameFit = window.requestAnimationFrame(() => fitTerminal());
    const delayedFit = window.setTimeout(() => fitTerminal(), 90);
    return () => { window.cancelAnimationFrame(frameFit); window.clearTimeout(delayedFit); };
  }, [terminalVisible, fitTerminal]);

  useEffect(() => {
    const fontSet = document.fonts;
    if (!fontSet) return;
    let cancelled = false;
    const refit = () => { if (cancelled) return; fitTerminal(); };
    fontSet.ready.then(refit).catch(() => undefined);
    fontSet.addEventListener("loadingdone", refit);
    return () => { cancelled = true; fontSet.removeEventListener("loadingdone", refit); };
  }, [fitTerminal]);

  useEffect(() => {
    if (!activePopout) return;
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") uiDispatch({ type: "SET_ACTIVE_POPOUT", payload: null });
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [activePopout, uiDispatch]);

  useEffect(() => {
    if (activePopout !== "map") return;
    const handle = window.requestAnimationFrame(() => drawMap());
    startPulse();
    return () => { window.cancelAnimationFrame(handle); stopPulse(); };
  }, [activePopout, drawMap, startPulse, stopPulse]);

  // Reparent terminal into overlay when visible
  useEffect(() => {
    const term = terminalRef.current;
    if (!term) return;
    const termEl = term.element;
    if (!termEl) return;
    if ((terminalVisible || textMode) && terminalOverlayRef.current) {
      terminalOverlayRef.current.appendChild(termEl);
      window.requestAnimationFrame(() => { fitTerminal(); term.scrollToBottom(); });
      const delayedFit = window.setTimeout(() => { fitTerminal(); term.scrollToBottom(); }, 80);
      return () => window.clearTimeout(delayedFit);
    } else if (terminalHiddenRef.current && termEl.parentElement !== terminalHiddenRef.current) {
      terminalHiddenRef.current.appendChild(termEl);
    }
  }, [terminalVisible, textMode, fitTerminal]);

  // Wire sendCommand callback for PixiJS
  useEffect(() => {
    canvasCallbacks.sendCommand = (cmd: string) => sendCommand(cmd, true);
    return () => { canvasCallbacks.sendCommand = null; };
  }, [sendCommand]);

  useEffect(() => {
    canvasCallbacks.prefillCommand = (text: string) => {
      uiDispatch({ type: "SET_COMPOSER_VALUE", payload: text });
      focusComposer();
    };
    return () => { canvasCallbacks.prefillCommand = null; };
  }, [focusComposer, uiDispatch]);

  useEffect(() => {
    canvasCallbacks.openShop = () => uiDispatch({ type: "SET_ACTIVE_POPOUT", payload: "shop" as PopoutPanel });
    canvasCallbacks.openBank = () => uiDispatch({ type: "SET_ACTIVE_POPOUT", payload: "bank" as PopoutPanel });
    canvasCallbacks.openTrainer = () => uiDispatch({ type: "SET_ACTIVE_POPOUT", payload: "trainer" as PopoutPanel });
    return () => { canvasCallbacks.openShop = null; canvasCallbacks.openBank = null; canvasCallbacks.openTrainer = null; };
  }, [uiDispatch]);

  useEffect(() => {
    canvasCallbacks.openMap = () => uiDispatch({ type: "SET_ACTIVE_POPOUT", payload: "map" as PopoutPanel });
    canvasCallbacks.openRoom = () => uiDispatch({ type: "SET_ACTIVE_POPOUT", payload: "room" as PopoutPanel });
    canvasCallbacks.openQuests = () => uiDispatch({ type: "SET_ACTIVE_POPOUT", payload: "quests" as PopoutPanel });
    canvasCallbacks.dismissDialogue = () => { worldDispatch({ type: "SET_DIALOGUE", payload: null }); questDispatch({ type: "SET_QUESTS_AVAILABLE", payload: [] }); };
    return () => { canvasCallbacks.openMap = null; canvasCallbacks.openRoom = null; canvasCallbacks.openQuests = null; canvasCallbacks.dismissDialogue = null; };
  }, [uiDispatch, worldDispatch, questDispatch]);

  useEffect(() => {
    canvasCallbacks.openVideo = (url: string) => uiDispatch({ type: "SET_VIDEO_URL", payload: url });
    return () => { canvasCallbacks.openVideo = null; };
  }, [uiDispatch]);

  // Ctrl+K toggles command palette
  useEffect(() => {
    const handler = (e: globalThis.KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === "k") {
        e.preventDefault();
        uiDispatch({ type: "SET_SHOW_COMMAND_PALETTE", payload: !showCommandPalette });
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [showCommandPalette, uiDispatch]);

  // Audio
  useEffect(() => { audio.playMusic(room.music ?? null); }, [room.music]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { audio.playAmbient(room.ambient ?? null); }, [room.ambient]); // eslint-disable-line react-hooks/exhaustive-deps
  const hpPercent = vitals.maxHp > 0 ? vitals.hp / vitals.maxHp : 1;
  useEffect(() => { audio.setCombatState(vitals.inCombat, hpPercent); }, [vitals.inCombat, hpPercent]); // eslint-disable-line react-hooks/exhaustive-deps

  const completeCast = useCallback(
    (skillId: string, cooldownMs: number, targetName?: string) => {
      const now = Date.now();
      combatDispatch({ type: "UPDATE_SKILL_COOLDOWN", payload: { abilityId: skillId, cooldownMs, receivedAt: now } });
      const cmd = targetName ? `cast ${skillId} ${targetName}` : `cast ${skillId}`;
      sendCommand(cmd, false);
      pendingCastRef.current = null;
    },
    [sendCommand, combatDispatch],
  );

  const handleCastSkill = useCallback(
    (skillId: string, cooldownMs: number) => {
      const skill = skills.find((s) => s.id === skillId);
      if (!skill) return;
      const needsTarget = skill.targetType === "ENEMY" || skill.targetType === "ALLY";
      if (needsTarget && combatTarget?.targetName) {
        completeCast(skillId, cooldownMs, combatTarget.targetName);
        return;
      }
      if (needsTarget) {
        pendingCastRef.current = { skillId, skillName: skill.name, cooldownMs, targetType: skill.targetType };
        uiDispatch({ type: "SET_TOAST", payload: `Select a target for ${skill.name}` });
        return;
      }
      completeCast(skillId, cooldownMs);
    },
    [skills, combatTarget, completeCast, uiDispatch],
  );

  // Wire canvas target selection
  useEffect(() => {
    canvasCallbacks.onTargetSelected = (targetName: string) => {
      const pending = pendingCastRef.current;
      if (!pending) return;
      uiDispatch({ type: "SET_TOAST", payload: null });
      completeCast(pending.skillId, pending.cooldownMs, targetName);
    };
    return () => { canvasCallbacks.onTargetSelected = null; };
  }, [completeCast, uiDispatch]);

  // Keyboard shortcuts: digits 1-9 cast quickbar skills
  useEffect(() => {
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape" && pendingCastRef.current) {
        pendingCastRef.current = null;
        uiDispatch({ type: "SET_TOAST", payload: null });
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
  }, [connected, hasCharacterProfile, quickbar.slots, handleCastSkill, uiDispatch]);

  // Auto-dismiss look target after 6 seconds
  useEffect(() => {
    if (!lookTarget) return;
    const t = setTimeout(() => uiDispatch({ type: "SET_LOOK_TARGET", payload: null }), 6000);
    return () => clearTimeout(t);
  }, [lookTarget, uiDispatch]);

  // Auto-dismiss toast after 4 seconds
  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => {
      uiDispatch({ type: "SET_TOAST", payload: null });
      pendingCastRef.current = null;
    }, 4000);
    return () => clearTimeout(timer);
  }, [toast, uiDispatch]);

  const submitComposer = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const liveValue = composerInputRef.current?.value ?? composerValue;
    const command = liveValue.trim();
    if (!command) return;
    sendCommand(command, true);
    uiDispatch({ type: "SET_COMPOSER_VALUE", payload: "" });
    resetComposerTraversal();
  };

  const onComposerKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    const liveValue = event.currentTarget.value;
    if (event.key === "ArrowUp") {
      event.preventDefault();
      applyComposerHistoryUp(liveValue, (v) => uiDispatch({ type: "SET_COMPOSER_VALUE", payload: v }));
      return;
    }
    if (event.key === "ArrowDown") {
      event.preventDefault();
      applyComposerHistoryDown((v) => uiDispatch({ type: "SET_COMPOSER_VALUE", payload: v }));
      return;
    }
    if (event.key === "Tab") {
      event.preventDefault();
      applyComposerCompletion(liveValue, (v) => uiDispatch({ type: "SET_COMPOSER_VALUE", payload: v }));
      return;
    }
    resetComposerCompletion();
  };

  const sendChatMessage = useCallback(
    (channel: ChatChannel, message: string, target: string | null): boolean => {
      if (!connected || !hasCharacterProfile) return false;
      const body = message.trim();
      if (body.length === 0) return false;
      const command = channel === "tell"
        ? (() => { const t = target?.trim() ?? ""; if (t.length === 0) return null; return `${channel} ${t} ${body}`; })()
        : `${channel} ${body}`;
      if (command === null) return false;
      sendCommand(command, true);
      focusComposer();
      return true;
    },
    [connected, focusComposer, hasCharacterProfile, sendCommand],
  );

  // ---- JSX ----
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
            onClick={() => uiDispatch({ type: "SET_LAYOUT_MODE", payload: layoutMode === "auto" ? "text" : layoutMode === "text" ? "canvas" : "auto" })}
            title={`Layout: ${layoutMode === "auto" ? "Auto" : layoutMode === "text" ? "Text" : "Canvas"} \u2014 click to cycle`}
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
                onClick={() => uiDispatch({ type: "SET_SHOW_ADMIN_PANEL", payload: true })}
                title="Staff Administration"
                aria-label="Open staff administration panel"
              >
                Staff
              </button>
              <button
                type="button"
                className={`soft-button staff-invis-button ${staffInvisible ? "staff-invis-active" : ""}`}
                onClick={() => { sendCommand("invis", true); uiDispatch({ type: "SET_STAFF_INVISIBLE", payload: !staffInvisible }); }}
                title={staffInvisible ? "You are invisible \u2014 click to become visible" : "Click to become invisible"}
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
          preLogin={actualPreLogin}
          terminalOverlayRef={terminalOverlayRef}
          terminalVisible={terminalVisible}
          terminalOpaque={terminalOpaque}
          textMode={textMode}
        />

        <ActionBar
          quickbarSlots={quickbar.slots}
          onOpenPopout={(p) => uiDispatch({ type: "SET_ACTIVE_POPOUT", payload: p })}
          onCastSkill={handleCastSkill}
          onQuickbarSwap={quickbar.swap}
          onQuickbarAssign={quickbar.assign}
          onQuickbarClear={quickbar.clear}
          composerInputRef={composerInputRef}
          commandPlaceholder={commandPlaceholder}
          onComposerChange={(value) => {
            uiDispatch({ type: "SET_COMPOSER_VALUE", payload: value });
            resetComposerCompletion();
            if (value.length > 0) uiDispatch({ type: "SET_TERMINAL_OPAQUE", payload: true });
          }}
          onComposerKeyDown={onComposerKeyDown}
          onComposerFocus={() => uiDispatch({ type: "SET_TERMINAL_VISIBLE", payload: true })}
          onComposerBlur={() => { uiDispatch({ type: "SET_TERMINAL_VISIBLE", payload: false }); uiDispatch({ type: "SET_TERMINAL_OPAQUE", payload: false }); }}
          onSubmitComposer={submitComposer}
          onPhaseSwitch={(engineId) => { sendCommand(`phase ${engineId}`, true); focusComposer(); }}
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
        onClose={() => uiDispatch({ type: "SET_ACTIVE_POPOUT", payload: null })}
        onFeatureAction={(cmd) => sendCommand(cmd, true)}
      >
        {activePopout === "character" && (
          <CharacterPanel
            onDismissQuestNotification={(id) => questDispatch({ type: "DISMISS_QUEST_NOTIFICATION", payload: id })}
            onAbandonQuest={(questName) => { sendCommand(`quest abandon ${questName}`, true); focusComposer(); }}
            onOpenInventory={() => uiDispatch({ type: "SET_ACTIVE_POPOUT", payload: "inventory" })}
            onOpenEquipment={() => uiDispatch({ type: "SET_ACTIVE_POPOUT", payload: "equipment" })}
            onCommand={(cmd) => { sendCommand(cmd, true); focusComposer(); }}
            onLogout={() => {
              try {
                const saved = JSON.parse(localStorage.getItem("ambonmud_auth_tokens") ?? "{}") as Record<string, string>;
                delete saved[character.name];
                localStorage.setItem("ambonmud_auth_tokens", JSON.stringify(saved));
              } catch { /* ignore */ }
              resumeTokenRef.current = null;
              sendGmcp("Session.Logout", {});
            }}
          />
        )}

        {activePopout === "inventory" && (
          <InventoryPanel
            onWearItem={(itemName) => { sendCommand(`wear ${itemName}`, true); focusComposer(); }}
            onDropItem={(itemName) => { sendCommand(`drop ${itemName}`, true); focusComposer(); }}
            onGiveItem={(itemKeyword, playerName) => { sendCommand(`give ${itemKeyword} ${playerName}`, true); focusComposer(); }}
            onCommand={(cmd) => { sendCommand(cmd, true); focusComposer(); }}
          />
        )}

        {activePopout === "equipment" && (
          <EquipmentPanel
            onRemoveItem={(slot) => { sendCommand(`remove ${slot}`, true); focusComposer(); }}
          />
        )}

        {activePopout === "chat" && (
          <ChatPanel
            onChannelChange={(ch) => socialDispatch({ type: "SET_ACTIVE_CHAT_CHANNEL", payload: ch })}
            onRequestWho={() => { sendCommand("who", true); focusComposer(); }}
            onSendMessage={sendChatMessage}
            onCommand={(cmd) => { sendCommand(cmd, true); focusComposer(); }}
          />
        )}

        {activePopout === "shop" && shop && (
          <ShopPopout
            onBuyItem={(keyword) => { sendCommand(`buy ${keyword}`, true); focusComposer(); }}
            onSellItem={(keyword) => { sendCommand(`sell ${keyword}`, true); focusComposer(); }}
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
              uiDispatch({ type: "SET_TOAST", payload: `${skill.name} \u2014 ${skill.manaCost} MP, ${cd}, ${skill.targetType.toLowerCase()} target` });
            }}
            onAssignSlot={quickbar.assign}
          />
        )}

        {activePopout === "mail" && (
          <MailPanel
            onReadMessage={(index) => sendCommand(`mail read ${index}`, true)}
            onDeleteMessage={(index) => sendCommand(`mail delete ${index}`, true)}
            onCompose={(recipient, body) => {
              sendCommand(`mail send ${recipient}`, false);
              for (const line of body.split("\n")) sendCommand(line, false);
              sendCommand(".", false);
            }}
            onClearMessage={() => uiDispatch({ type: "SET_MAIL_MESSAGE", payload: null })}
          />
        )}

        {activePopout === "crafting" && (
          <CraftingPanel
            onGather={(keyword) => sendCommand(`gather ${keyword}`, true)}
            onCraft={(recipeKeyword) => sendCommand(`craft ${recipeKeyword}`, true)}
            onRequestRecipes={() => sendCommand("recipes", true)}
            onLoadSkills={() => sendCommand("craftskills", true)}
          />
        )}

        {activePopout === "quests" && (
          <QuestPanel
            onDismissQuestNotification={(id) => questDispatch({ type: "DISMISS_QUEST_NOTIFICATION", payload: id })}
            onAbandonQuest={(questName) => { sendCommand(`quest abandon ${questName}`, true); focusComposer(); }}
            onAcceptQuest={(questName) => { sendCommand(`accept ${questName}`, true); focusComposer(); }}
            onCommand={(cmd) => { sendCommand(cmd, true); focusComposer(); }}
          />
        )}

        {activePopout === "housing" && (
          <HousingPanel
            onSendCommand={(cmd) => { sendCommand(cmd, true); focusComposer(); }}
          />
        )}

        {activePopout === "leaderboard" && (
          <LeaderboardPanel
            onCommand={(cmd) => { sendCommand(cmd, true); focusComposer(); }}
          />
        )}

        {activePopout === "bank" && (
          <BankPanel
            onCommand={(cmd: string) => { sendCommand(cmd, true); focusComposer(); }}
          />
        )}

        {activePopout === "auction" && (
          <AuctionPanel
            onCommand={(cmd: string) => { sendCommand(cmd, true); focusComposer(); }}
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
                coreDispatch({ type: "SET_SAVED_CHARACTERS", payload: [] });
                coreDispatch({ type: "SET_LOGIN_PROMPT", payload: null });
                coreDispatch({ type: "SET_RECONNECTING", payload: true });
                sendGmcp("Session.Authenticate", { token });
                return;
              }
            } catch { /* ignore */ }
            coreDispatch({ type: "SET_SAVED_CHARACTERS", payload: [] });
          }}
          onRemoveCharacter={(name) => {
            try {
              const saved = JSON.parse(localStorage.getItem("ambonmud_auth_tokens") ?? "{}") as Record<string, string>;
              delete saved[name];
              localStorage.setItem("ambonmud_auth_tokens", JSON.stringify(saved));
              const remaining = Object.keys(saved);
              coreDispatch({ type: "SET_SAVED_CHARACTERS", payload: remaining.length === 0 ? [] : remaining });
            } catch {
              coreDispatch({ type: "SET_SAVED_CHARACTERS", payload: [] });
            }
          }}
          onNewCharacter={() => coreDispatch({ type: "SET_SAVED_CHARACTERS", payload: [] })}
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
            uiDispatch({ type: "SET_VIDEO_CLOSING", payload: true });
            setTimeout(() => { uiDispatch({ type: "SET_VIDEO_URL", payload: null }); uiDispatch({ type: "SET_VIDEO_CLOSING", payload: false }); }, 600);
          }}
          onKeyDown={(e) => {
            if (e.key === "Escape") {
              uiDispatch({ type: "SET_VIDEO_CLOSING", payload: true });
              setTimeout(() => { uiDispatch({ type: "SET_VIDEO_URL", payload: null }); uiDispatch({ type: "SET_VIDEO_CLOSING", payload: false }); }, 600);
            }
          }}
          onAnimationEnd={(e) => {
            if (e.animationName === "videoFadeOut") { uiDispatch({ type: "SET_VIDEO_URL", payload: null }); uiDispatch({ type: "SET_VIDEO_CLOSING", payload: false }); }
          }}
        >
          <div className="video-modal" onClick={(e) => e.stopPropagation()}>
            <button
              className="video-modal-close"
              aria-label="Close video"
              autoFocus
              onClick={() => {
                uiDispatch({ type: "SET_VIDEO_CLOSING", payload: true });
                setTimeout(() => { uiDispatch({ type: "SET_VIDEO_URL", payload: null }); uiDispatch({ type: "SET_VIDEO_CLOSING", payload: false }); }, 600);
              }}
            >
              {"\u2715"}
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
          onCommand={(command) => { sendCommand(command, true); focusComposer(); }}
          onClose={() => uiDispatch({ type: "SET_SHOW_ADMIN_PANEL", payload: false })}
        />
      )}

      {showCommandPalette && (
        <CommandPalette
          commands={serverCommands}
          isStaff={character.isStaff}
          onExecute={(cmd) => { sendCommand(cmd, true); focusComposer(); }}
          onPrefill={(text) => { uiDispatch({ type: "SET_COMPOSER_VALUE", payload: text }); focusComposer(); }}
          onClose={() => uiDispatch({ type: "SET_SHOW_COMMAND_PALETTE", payload: false })}
        />
      )}

      {/* Hidden terminal container */}
      <div ref={terminalHiddenRef} className="terminal-hidden" aria-hidden="true" />

      <p className="sr-only" aria-live="polite">{liveMessage}</p>

      {lookTarget && (
        <div className="look-target-card" role="dialog" aria-label="Inspect target" onClick={() => uiDispatch({ type: "SET_LOOK_TARGET", payload: null })}>
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
              <span className="broadcast-sender">{"\u2014"} {broadcast.sender}</span>
              <button
                type="button"
                className="broadcast-dismiss"
                onClick={() => uiDispatch({ type: "SET_BROADCAST", payload: null })}
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
