import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent, KeyboardEvent } from "react";
import { FitAddon } from "@xterm/addon-fit";
import { Terminal } from "@xterm/xterm";
import { ActionBar } from "./components/ActionBar";
import { AudioControls } from "./components/AudioControls";
import { PopoutLayer } from "./components/PopoutLayer";
import { ShopPopout } from "./components/ShopPopout";
import { ChatPanel } from "./components/panels/ChatPanel";
import { CharacterPanel } from "./components/panels/CharacterPanel";
import { SpellbookPanel } from "./components/SpellbookPanel";
import { PlayPanel } from "./components/panels/PlayPanel";
import { AdminPanel } from "./components/panels/AdminPanel";
import { applyGmcpPackage } from "./gmcp/applyGmcpPackage";
import { canvasCallbacks, gameStateRef, pendingCastRef } from "./canvas/GameStateBridge";
import { canvasEvents } from "./canvas/CanvasEventBus";
import { LoginModal } from "./canvas/LoginModal";
import {
  DEFAULT_STATUS_VAR_LABELS,
  EMPTY_CHAR,
  EMPTY_ROOM,
  EMPTY_VITALS,
  MAX_VISIBLE_EFFECTS,
  SLOT_ORDER,
} from "./constants";
import { useCommandHistory } from "./hooks/useCommandHistory";
import { useMiniMap } from "./hooks/useMiniMap";
import { useMudSocket } from "./hooks/useMudSocket";
import { useAudioEngine } from "./hooks/useAudioEngine";
import { useQuickbar } from "./hooks/useQuickbar";
import type {
  AchievementData,
  CharStats,
  ChatChannel,
  ChatMessage,
  CharacterInfo,
  CombatEventData,
  CombatTarget,
  DialogueState,
  FriendEntry,
  FriendNotification,
  GainEvent,
  GroupInfo,
  GuildInfo,
  GuildMemberEntry,
  ItemSummary,
  LoginErrorState,
  LoginPromptState,
  MobInfo,
  PopoutPanel,
  QuestEntry,
  QuestNotification,
  RoomMob,
  RoomItem,
  RoomPlayer,
  RoomState,
  ShopState,
  SkillSummary,
  StatusEffect,
  StatusVarLabels,
  Vitals,
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

function isAsciiLetter(code: number): boolean {
  return (code >= 65 && code <= 90) || (code >= 97 && code <= 122);
}

function stripAnsiSequences(input: string): string {
  let output = "";
  let i = 0;
  while (i < input.length) {
    if (input.charCodeAt(i) === 0x1b && input[i + 1] === "[") {
      i += 2;
      while (i < input.length && !isAsciiLetter(input.charCodeAt(i))) {
        i += 1;
      }
      if (i < input.length) i += 1;
      continue;
    }

    output += input[i];
    i += 1;
  }
  return output;
}

function parseWhoEntries(messageChunk: string): string[] | null {
  const clean = stripAnsiSequences(messageChunk);
  const lines = clean.replace(/\r/g, "\n").split("\n");
  for (const line of lines) {
    const markerIndex = line.indexOf("Online:");
    if (markerIndex === -1) continue;
    const payload = line.slice(markerIndex + "Online:".length).trim();
    if (payload.length === 0) return [];
    return payload.split(",").map((entry) => entry.trim()).filter((entry) => entry.length > 0);
  }
  return null;
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
  const [composerValue, setComposerValue] = useState("");
  const [terminalVisible, setTerminalVisible] = useState(false);
  const [terminalOpaque, setTerminalOpaque] = useState(false);
  const [videoUrl, setVideoUrl] = useState<string | null>(null);
  const [videoClosing, setVideoClosing] = useState(false);

  const [vitals, setVitals] = useState<Vitals>(EMPTY_VITALS);
  const [statusVarLabels, setStatusVarLabels] = useState<StatusVarLabels>(DEFAULT_STATUS_VAR_LABELS);
  const [character, setCharacter] = useState<CharacterInfo>(EMPTY_CHAR);
  const [room, setRoom] = useState<RoomState>(EMPTY_ROOM);
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
  const [achievements, setAchievements] = useState<AchievementData>({ completed: [], inProgress: [] });
  const [groupInfo, setGroupInfo] = useState<GroupInfo>({ leader: null, members: [] });
  const [guildInfo, setGuildInfo] = useState<GuildInfo>({ name: null, tag: null, rank: null, motd: null, memberCount: 0, maxSize: 50 });
  const [guildMembers, setGuildMembers] = useState<GuildMemberEntry[]>([]);
  const [friends, setFriends] = useState<FriendEntry[]>([]);
  const [friendNotifications, setFriendNotifications] = useState<FriendNotification[]>([]);
  const [chatByChannel, setChatByChannel] = useState<Record<ChatChannel, ChatMessage[]>>(createEmptyChatByChannel);
  const [dialogue, setDialogue] = useState<DialogueState | null>(null);
  const [whoPlayers, setWhoPlayers] = useState<string[]>([]);
  const [combatTarget, setCombatTarget] = useState<CombatTarget | null>(null);
  const [, setCharStats] = useState<CharStats | null>(null);
  const [quests, setQuests] = useState<QuestEntry[]>([]);
  const [mobInfo, setMobInfo] = useState<MobInfo[]>([]);
  const [shop, setShop] = useState<ShopState | null>(null);
  const [questNotifications, setQuestNotifications] = useState<QuestNotification[]>([]);
  const [loginPrompt, setLoginPrompt] = useState<LoginPromptState | null>(null);
  const [loginError, setLoginError] = useState<LoginErrorState | null>(null);
  const combatEventsRef = useRef<CombatEventData[]>([]);
  const gainEventsRef = useRef<GainEvent[]>([]);

  const pushCombatEvent = useCallback((event: CombatEventData) => {
    combatEventsRef.current = [...combatEventsRef.current.slice(-99), event];
    canvasEvents.push(event);
  }, []);

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

  const { mapCanvasRef, drawMap, updateMap, resetMap } = useMiniMap();
  const {
    pushHistory,
    applyComposerHistoryUp,
    applyComposerHistoryDown,
    applyComposerCompletion,
    resetComposerTraversal,
    resetComposerCompletion,
  } = useCommandHistory();

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
    setAchievements({ completed: [], inProgress: [] });
    setGroupInfo({ leader: null, members: [] });
    setGuildInfo({ name: null, tag: null, rank: null, motd: null, memberCount: 0, maxSize: 50 });
    setGuildMembers([]);
    setFriends([]);
    setFriendNotifications([]);
    setChatByChannel(createEmptyChatByChannel());
    setDialogue(null);
    setWhoPlayers([]);
    setCombatTarget(null);
    setCharStats(null);
    setQuests([]);
    setMobInfo([]);
    setShop(null);
    setQuestNotifications([]);
    setLoginPrompt(null);
    setLoginError(null);
    combatEventsRef.current = [];
    gainEventsRef.current = [];
    setActiveChatChannel("say");
    setShowAdminPanel(false);
    resetMap();
  }, [resetMap]);

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
          setPlayers,
          setMobs,
          setEffects,
          setSkills,
          setAchievements,
          setGroupInfo,
          setGuildInfo,
          setGuildMembers,
          setDialogue,
          setCombatTarget,
          setShop: (value) => {
            setShop(value);
            if (value) {
              setActivePopout("shop");
            } else {
              setActivePopout((prev) => prev === "shop" ? null : prev);
            }
          },
          setFriends,
          pushFriendNotification,
          setChatByChannel,
          updateMap,
          pushCombatEvent,
          setCharStats,
          setQuests,
          pushGainEvent,
          pushQuestNotification,
          setMobInfo,
          setLoginPrompt,
          setLoginError,
        },
      );
    },
    [pushFriendNotification, pushCombatEvent, pushGainEvent, pushQuestNotification, updateMap],
  );

  const { connected, liveMessage, connect, disconnect, reconnect, sendLine } = useMudSocket({
    onOpen: () => {
      focusComposer();
    },
    onTextMessage: (text) => {
      terminalRef.current?.write(text);
      const who = parseWhoEntries(text);
      if (who) setWhoPlayers(who);
    },
    onGmcpMessage: handleGmcp,
    onClose: () => {
      setComposerValue("");
      resetComposerTraversal();
      resetHud();
      audio.stopAll();
      writeSystem("Connection closed.");
    },
    onError: () => {
      writeSystem("Connection error.");
    },
  });

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

    window.addEventListener("resize", onResize);
    window.addEventListener("beforeunload", onBeforeUnload);

    return () => {
      window.removeEventListener("resize", onResize);
      window.removeEventListener("beforeunload", onBeforeUnload);
      disconnect();
    };
  }, [connect, disconnect, drawMap, fitTerminal]);

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
    return () => window.cancelAnimationFrame(handle);
  }, [activePopout, drawMap]);

  // Reparent terminal into overlay when visible, back to hidden when not
  useEffect(() => {
    const term = terminalRef.current;
    if (!term) return;
    const termEl = term.element;
    if (!termEl) return;

    if (terminalVisible && terminalOverlayRef.current) {
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
  }, [terminalVisible, fitTerminal]);

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
      shop,
    };
  });

  // Wire sendCommand callback for PixiJS click-to-interact
  useEffect(() => {
    canvasCallbacks.sendCommand = (cmd: string) => sendCommand(cmd, true);
    return () => { canvasCallbacks.sendCommand = null; };
  }, [sendCommand]);

  // Wire canvas shop badge to open shop popout
  useEffect(() => {
    canvasCallbacks.openShop = () => setActivePopout("shop");
    return () => { canvasCallbacks.openShop = null; };
  }, []);

  // Wire canvas minimap expand and room expand buttons
  useEffect(() => {
    canvasCallbacks.openMap = () => setActivePopout("map");
    canvasCallbacks.openRoom = () => setActivePopout("room");
    return () => { canvasCallbacks.openMap = null; canvasCallbacks.openRoom = null; };
  }, []);

  // Wire canvas video cinematic callback
  useEffect(() => {
    canvasCallbacks.openVideo = (url: string) => setVideoUrl(url);
    return () => { canvasCallbacks.openVideo = null; };
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

  const equipmentSlots = useMemo(() => {
    const slots = Object.keys(equipment);
    return slots.sort((left, right) => {
      const li = SLOT_ORDER.indexOf(left);
      const ri = SLOT_ORDER.indexOf(right);
      if (li === -1 && ri === -1) return left.localeCompare(right);
      if (li === -1) return 1;
      if (ri === -1) return -1;
      return li - ri;
    });
  }, [equipment]);

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
      ? "Login through the terminal to begin your journey"
      : "Type a command"
    : "Reconnect to start playing";
  const popoutTitle =
    activePopout === "map"
      ? "Mini-map"
      : activePopout === "room"
        ? "Room Details"
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
      : activePopout === "spellbook"
        ? "Spellbook"
        : "Currently Wearing";

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
      sendCommand(cmd, true);
      pendingCastRef.current = null;
      focusComposer();
    },
    [focusComposer, sendCommand],
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
        pendingCastRef.current = { skillId, skillName: skill.name, targetType: skill.targetType };
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
      completeCast(pending.skillId, 0, targetName);
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
          <AudioControls audio={audio} />
          {character.isStaff && (
            <button
              type="button"
              className="soft-button staff-admin-button"
              onClick={() => setShowAdminPanel(true)}
              title="Staff Administration"
              aria-label="Open staff administration panel"
            >
              Staff
            </button>
          )}
          <span
            className={`connection-pill ${connected ? "connection-pill-online" : "connection-pill-offline"}`}
            role="status"
            aria-live="polite"
          >
            {connected ? "Connected" : "Disconnected"}
          </span>
          <button type="button" className="soft-button" onClick={reconnect}>Reconnect</button>
        </div>
      </header>

      <div className="dashboard">
        <PlayPanel
          preLogin={preLogin}
          terminalOverlayRef={terminalOverlayRef}
          terminalVisible={terminalVisible}
          terminalOpaque={terminalOpaque}
        />

        <ActionBar
          connected={connected}
          hasCharacterProfile={hasCharacterProfile}
          vitals={vitals}
          quickbarSlots={quickbar.slots}
          shop={shop}
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
        />
      </div>

      <PopoutLayer
        activePopout={activePopout}
        popoutTitle={popoutTitle}
        room={room}
        exits={exits}
        inventory={inventory}
        equipment={equipment}
        equipmentSlots={equipmentSlots}
        mapCanvasRef={mapCanvasRef}
        canManageItems={connected && hasCharacterProfile}
        players={players}
        isStaff={character.isStaff}
        onWearItem={(itemName) => {
          sendCommand(`wear ${itemName}`, true);
          focusComposer();
        }}
        onDropItem={(itemName) => {
          sendCommand(`drop ${itemName}`, true);
          focusComposer();
        }}
        onRemoveItem={(slot) => {
          sendCommand(`remove ${slot}`, true);
          focusComposer();
        }}
        onGiveItem={(itemKeyword, playerName) => {
          sendCommand(`give ${itemKeyword} ${playerName}`, true);
          focusComposer();
        }}
        onClose={() => setActivePopout(null)}
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
            onDismissQuestNotification={(id) => {
              setQuestNotifications((prev) => prev.filter((n) => n.id !== id));
            }}
            onAbandonQuest={(questName) => {
              sendCommand(`quest abandon ${questName}`, true);
              focusComposer();
            }}
            onOpenEquipment={() => setActivePopout("equipment")}
            onOpenWearing={() => setActivePopout("wearing")}
          />
        )}

        {activePopout === "chat" && (
          <ChatPanel
            connected={connected}
            canChat={connected && hasCharacterProfile}
            playerName={character.name}
            activeChannel={activeChatChannel}
            chatByChannel={chatByChannel}
            whoPlayers={whoPlayers}
            groupInfo={groupInfo}
            guildInfo={guildInfo}
            guildMembers={guildMembers}
            friends={friends}
            friendNotifications={friendNotifications}
            onChannelChange={setActiveChatChannel}
            onRequestWho={() => {
              sendCommand("who", true);
              focusComposer();
            }}
            onSendMessage={sendChatMessage}
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

        {activePopout === "spellbook" && (
          <SpellbookPanel
            skills={skills}
            quickbarSlotIds={quickbar.slotIds}
            playerClass={displayClassName}
            playerLevel={vitals.level ?? 1}
            onShowSkillInfo={(skill) => {
              const cd = skill.cooldownMs > 0 ? `${skill.cooldownMs / 1000}s cooldown` : "No cooldown";
              setToast(`${skill.name} — ${skill.manaCost} MP, ${cd}, ${skill.targetType.toLowerCase()} target`);
            }}
            onAssignSlot={quickbar.assign}
          />
        )}
      </PopoutLayer>

      {loginPrompt && (
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
          onClick={() => {
            setVideoClosing(true);
            setTimeout(() => { setVideoUrl(null); setVideoClosing(false); }, 600);
          }}
          onAnimationEnd={(e) => {
            if (e.animationName === "videoFadeOut") { setVideoUrl(null); setVideoClosing(false); }
          }}
        >
          <div className="video-modal" onClick={(e) => e.stopPropagation()}>
            <button className="video-modal-close" onClick={() => {
              setVideoClosing(true);
              setTimeout(() => { setVideoUrl(null); setVideoClosing(false); }, 600);
            }}>✕</button>
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
        />
      )}

      {/* Hidden terminal container — xterm lives here when popout is closed */}
      <div ref={terminalHiddenRef} className="terminal-hidden" aria-hidden="true" />

      <p className="sr-only" aria-live="polite">{liveMessage}</p>

      {toast && (
        <div className="game-toast" role="alert">
          {toast}
        </div>
      )}
    </main>
  );
}

export default App;
