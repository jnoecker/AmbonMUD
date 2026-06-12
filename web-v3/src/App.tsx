import { Suspense, lazy, useEffect, useMemo, useRef, useState } from "react";
import type { KeyboardEvent as ReactKeyboardEvent } from "react";
import { GameShell } from "./components/GameShell";
import { Drawer } from "./components/Drawer";
import { PeekSentence } from "./components/PeekSentence";
import { PuzzlePopout } from "./components/PuzzlePopout";
import { ShopPopout } from "./components/ShopPopout";
import { TrainerPanel } from "./components/TrainerPanel";
import { TradePanel } from "./components/TradePanel";
import { WorldFeaturesPopout } from "./components/WorldFeaturesPopout";
import { featureArt, pickFocusedFeature } from "./components/worldFeatures";
import { GameNarrator } from "./components/GameNarrator";
import { SpellbookPanel } from "./components/SpellbookPanel";

// Drawer panels and field-manual overlays are only reachable after login, so
// they load on demand (React.lazy) instead of riding in the entry chunk.
// vite.config.ts groups them into a shared "panels" chunk (one fetch on first
// drawer open) with the staff-only AdminPanel split out separately.
const ChatBoardPanel = lazy(() => import("./components/panels/ChatBoardPanel").then((m) => ({ default: m.ChatBoardPanel })));
const WhoBoardPanel = lazy(() => import("./components/panels/WhoBoardPanel").then((m) => ({ default: m.WhoBoardPanel })));
const GuildBoardPanel = lazy(() => import("./components/panels/GuildBoardPanel").then((m) => ({ default: m.GuildBoardPanel })));
const FriendsBoardPanel = lazy(() => import("./components/panels/FriendsBoardPanel").then((m) => ({ default: m.FriendsBoardPanel })));
const GroupBoardPanel = lazy(() => import("./components/panels/GroupBoardPanel").then((m) => ({ default: m.GroupBoardPanel })));
const PlayerExaminePanel = lazy(() => import("./components/panels/PlayerExaminePanel").then((m) => ({ default: m.PlayerExaminePanel })));
const CharacterPanel = lazy(() => import("./components/panels/CharacterPanel").then((m) => ({ default: m.CharacterPanel })));
const QuestPanel = lazy(() => import("./components/panels/QuestPanel").then((m) => ({ default: m.QuestPanel })));
const QuestOfferPanel = lazy(() => import("./components/panels/QuestOfferPanel").then((m) => ({ default: m.QuestOfferPanel })));
const InventoryPanel = lazy(() => import("./components/panels/InventoryPanel").then((m) => ({ default: m.InventoryPanel })));
const EquipmentPanel = lazy(() => import("./components/panels/EquipmentPanel").then((m) => ({ default: m.EquipmentPanel })));
const MailPanel = lazy(() => import("./components/panels/MailPanel").then((m) => ({ default: m.MailPanel })));
const MonsterManualPanel = lazy(() => import("./components/panels/MonsterManualPanel").then((m) => ({ default: m.MonsterManualPanel })));
const ItemManualPanel = lazy(() => import("./components/panels/ItemManualPanel").then((m) => ({ default: m.ItemManualPanel })));
const ArcanumPanel = lazy(() => import("./components/panels/ArcanumPanel").then((m) => ({ default: m.ArcanumPanel })));
const CraftingPanel = lazy(() => import("./components/panels/CraftingPanel").then((m) => ({ default: m.CraftingPanel })));
const ProfessionsPanel = lazy(() => import("./components/panels/ProfessionsPanel").then((m) => ({ default: m.ProfessionsPanel })));
const HousingPanel = lazy(() => import("./components/panels/HousingPanel").then((m) => ({ default: m.HousingPanel })));
const BankPanel = lazy(() => import("./components/panels/BankPanel").then((m) => ({ default: m.BankPanel })));
const StylistPanel = lazy(() => import("./components/panels/StylistPanel").then((m) => ({ default: m.StylistPanel })));
const InnPanel = lazy(() => import("./components/panels/InnPanel").then((m) => ({ default: m.InnPanel })));
const AuctionPanel = lazy(() => import("./components/panels/AuctionPanel").then((m) => ({ default: m.AuctionPanel })));
const DungeonPanel = lazy(() => import("./components/panels/DungeonPanel").then((m) => ({ default: m.DungeonPanel })));
const LotteryPanel = lazy(() => import("./components/panels/LotteryPanel").then((m) => ({ default: m.LotteryPanel })));
const DicePanel = lazy(() => import("./components/panels/DicePanel").then((m) => ({ default: m.DicePanel })));
const JukeboxPanel = lazy(() => import("./components/panels/JukeboxPanel").then((m) => ({ default: m.JukeboxPanel })));
const AdminPanel = lazy(() => import("./components/panels/AdminPanel").then((m) => ({ default: m.AdminPanel })));
const CombatLogPanel = lazy(() => import("./components/panels/CombatLogPanel").then((m) => ({ default: m.CombatLogPanel })));
import { HelpContent } from "./components/HelpContent";
import { TerminalOverlay } from "./components/TerminalOverlay";
import { Atlas } from "./components/Atlas";
import { DemoBanner } from "./components/DemoBanner";
import { LevelUpBanner } from "./components/LevelUpBanner";
import { QuestCompleteToast } from "./components/QuestCompleteToast";
import { CombatVictoryToast } from "./components/CombatVictoryToast";
import { FleeToast } from "./components/FleeToast";
import { LoginModal } from "./canvas/LoginModal";
import { CharacterPicker } from "./components/CharacterPicker";
import { CommandPalette } from "./components/CommandPalette";
import { useGameState } from "./hooks/useGameState";
import { useMudSocket } from "./hooks/useMudSocket";
import { useTerminal } from "./hooks/useTerminal";
import { useAudioEngine } from "./hooks/useAudioEngine";
import { useCommandHistory } from "./hooks/useCommandHistory";
import { useMiniMap } from "./hooks/useMiniMap";
import { useQuickbar } from "./hooks/useQuickbar";
import { useOnboarding } from "./hooks/useOnboarding";
import { canvasCallbacks, gameStateRef, pendingCastRef } from "./canvas/GameStateBridge";
import type {
  ChatChannel,
  ConsiderRating,
  FeaturePopoutFocus,
  ItemEntry,
  LookTargetInfo,
  MonsterEntry,
  PopoutPanel,
  WhoPlayer,
} from "./types";
import { sortExits, titleCaseWords } from "./utils";
import "./styles.css";

// ── Consider (threat assessment) helpers ────────────────────────────────────
const CONSIDER_TIER_CLASS: Record<ConsiderRating, string> = {
  TRIVIAL: "consider-tier-trivial",
  EASY: "consider-tier-easy",
  FAVORED: "consider-tier-favored",
  EVEN: "consider-tier-even",
  RISKY: "consider-tier-risky",
  DANGEROUS: "consider-tier-dangerous",
  SUICIDAL: "consider-tier-suicidal",
};

// ── Look-target (Examine) helpers ───────────────────────────────────────────
function formatItemSlot(slot: string): string {
  return titleCaseWords(slot.replace(/_/g, " "));
}

function formatStatLabel(key: string): string {
  return titleCaseWords(key.replace(/_/g, " "));
}

function formatStatValue(value: number): string {
  return value > 0 ? `+${value}` : `${value}`;
}

function hasItemStatContent(info: LookTargetInfo): boolean {
  if (info.type !== "item") return false;
  if (info.damage != null && info.damage !== 0) return true;
  if (info.armor != null && info.armor !== 0) return true;
  if (info.basePrice != null && info.basePrice > 0) return true;
  if (info.slot) return true;
  if (info.consumable) return true;
  if (info.stats && Object.values(info.stats).some((v) => v !== 0)) return true;
  if (info.enchantments && info.enchantments.length > 0) return true;
  return false;
}

function renderItemStats(info: LookTargetInfo) {
  if (!hasItemStatContent(info)) return null;
  const coreStats: Array<{ label: string; value: string; tone: "damage" | "armor" | "value" | "neutral" }> = [];
  if (info.damage != null && info.damage !== 0) {
    coreStats.push({ label: "Damage", value: formatStatValue(info.damage), tone: "damage" });
  }
  if (info.armor != null && info.armor !== 0) {
    coreStats.push({ label: "Armor", value: formatStatValue(info.armor), tone: "armor" });
  }
  if (info.basePrice != null && info.basePrice > 0) {
    coreStats.push({ label: "Value", value: `${info.basePrice}g`, tone: "value" });
  }
  if (info.consumable) {
    coreStats.push({ label: "Type", value: "Consumable", tone: "neutral" });
  }
  const statEntries = info.stats
    ? Object.entries(info.stats).filter(([, v]) => v !== 0)
    : [];

  return (
    <div className="look-target-stats" role="group" aria-label="Item details">
      {coreStats.length > 0 && (
        <ul className="look-target-stat-grid">
          {coreStats.map((s) => (
            <li key={s.label} className={`look-target-stat look-target-stat-${s.tone}`}>
              <span className="look-target-stat-label">{s.label}</span>
              <span className="look-target-stat-value">{s.value}</span>
            </li>
          ))}
        </ul>
      )}
      {statEntries.length > 0 && (
        <ul className="look-target-modifier-list">
          {statEntries.map(([key, value]) => (
            <li key={key} className={`look-target-modifier ${value > 0 ? "is-positive" : "is-negative"}`}>
              <span className="look-target-modifier-label">{formatStatLabel(key)}</span>
              <span className="look-target-modifier-value">{formatStatValue(value)}</span>
            </li>
          ))}
        </ul>
      )}
      {info.enchantments && info.enchantments.length > 0 && (
        <ul className="look-target-enchantments">
          {info.enchantments.map((e, i) => (
            <li key={`${e}-${i}`} className="look-target-enchantment">{e}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

function TrainerAutoLoad({ onCommand }: { onCommand: (cmd: string) => void }) {
  const sent = useRef(false);
  useEffect(() => {
    if (!sent.current) { sent.current = true; onCommand("train list"); }
  }, [onCommand]);
  return <p className="empty-note">Loading trainer data&hellip;</p>;
}

// URLs already handed to the browser for prefetching, so the preload effect
// never recreates an Image for art it has seen this session.
const preloadedArt = new Set<string>();

function App() {
  const resumeTokenRef = useRef<string | null>(null);
  const pendingAuthCharRef = useRef<string | null>(null);
  const sendGmcpRef = useRef<(pkg: string, payload: unknown) => void>(() => {});
  const intentionalDisconnectRef = useRef(false);
  const connectedRef = useRef(false);

  // Cinematic video state — driven by canvas openVideo callback (room videos,
  // zone-cinematic autoplay, and the map's replay button all route through it)
  const [videoUrl, setVideoUrl] = useState<string | null>(null);
  const [videoClosing, setVideoClosing] = useState(false);
  // Close-button refs for the canvas modals — focused on open so the opener
  // can be restored on dismiss (see the modal focus effects below).
  const videoCloseRef = useRef<HTMLButtonElement | null>(null);
  const mobDetailCloseRef = useRef<HTMLButtonElement | null>(null);
  const imagePreviewCloseRef = useRef<HTMLButtonElement | null>(null);

  // Expanded mob detail card — opened from canvas Look button
  const [mobDetail, setMobDetail] = useState<{ name: string; description: string; image: string | null } | null>(null);

  // Monster-manual / bestiary panel (clicked mob or pet)
  const [monster, setMonster] = useState<MonsterEntry | null>(null);
  // Player examine card (Examine from the Who board) — reuses the manual style.
  const [examinePlayer, setExaminePlayer] = useState<WhoPlayer | null>(null);
  // Player field-manual card for a player clicked in the room (full context menu).
  const [roomPlayer, setRoomPlayer] = useState<WhoPlayer | null>(null);
  // Item card (clicked room item)
  const [item, setItem] = useState<ItemEntry | null>(null);
  // When Examine is clicked we `look` the item and route the resulting
  // Room.LookTarget into the item card (so it carries the full description).
  const pendingExamineRef = useRef<{ image: string | null; equippedSlot?: string } | null>(null);
  // Inn modal (key-on-a-hook recall) — a click-away modal, not a drawer panel.
  const [showInn, setShowInn] = useState(false);

  // Full-size image preview — opened by clicking the entity preview sprite
  const [imagePreviewUrl, setImagePreviewUrl] = useState<string | null>(null);

  // Ctrl+K command palette
  const [showCommandPalette, setShowCommandPalette] = useState(false);
  const [featureFocus, setFeatureFocus] = useState<FeaturePopoutFocus>(null);

  // Staff admin panel + invisibility toggle
  const [showAdminPanel, setShowAdminPanel] = useState(false);
  const [staffInvisible, setStaffInvisible] = useState(false);
  // Bumped by the canvas Claim button (demo characters); opens the claim modal.
  const [claimRequestId, setClaimRequestId] = useState(0);
  const [mapTab, setMapTab] = useState<"map" | "atlas">("map");

  // Lifted command-input state — VitalsBar renders it controlled, palette/canvas can prefill
  const [inputValue, setInputValue] = useState("");

  const prefillInput = (text: string) => {
    setInputValue(text);
  };

  // Terminal host elements — refs stay in App (attached to JSX below) so the
  // useTerminal return value carries no refs (react-hooks/refs rule).
  const terminalHiddenRef = useRef<HTMLDivElement | null>(null);
  const terminalOverlayRef = useRef<HTMLDivElement | null>(null);

  // Minimap canvas + drawing helpers (owns its own ref, kept out of useGameState)
  const {
    mapCanvasRef,
    drawMap,
    updateMap,
    loadZoneMap,
    resetMap,
    startPulse,
    stopPulse,
    onMapPointerDown,
    onMapPointerMove,
    onMapPointerUp,
    onMapWheel,
    zoomIn: mapZoomIn,
    zoomOut: mapZoomOut,
    recenter: mapRecenter,
  } = useMiniMap();

  const state = useGameState(
    { resumeTokenRef, pendingAuthCharRef, sendGmcpRef },
    { updateMap, loadZoneMap, resetMap },
  );
  const audio = useAudioEngine();
  // Player abilities feed the quickbar and spellbook. Pet skills get their own auto-populated
  // PetBar (Shift+1/2/3) so they don't compete for quickbar slots; `handleCastSkill` still
  // routes pet skills through `pet <id>` when invoked.
  const quickbar = useQuickbar(state.skills, state.character.name);

  const {
    pushHistory,
    applyComposerHistoryUp,
    applyComposerHistoryDown,
    applyComposerCompletion,
    resetComposerTraversal,
    resetComposerCompletion,
  } = useCommandHistory(state.serverCommands);

  // Session-long telnet-style log; the GUI stays primary and the overlay
  // summons this accumulated stream on demand.
  const {
    open: terminalOpen,
    opaque: terminalOpaque,
    setOpaque: setTerminalOpaque,
    openTerminal,
    closeTerminal,
    write: writeTerminal,
    echoCommand,
    writeSystem,
    hasSelection: terminalHasSelection,
    setInkTheme,
  } = useTerminal({
    hiddenHostRef: terminalHiddenRef,
    overlayHostRef: terminalOverlayRef,
    screenReaderMode: state.character.screenReaderEnabled,
  });

  // Parchment scroll art → dark ink palette; absent art → light-on-dark.
  const terminalParchmentBg = state.serverAssets["terminal_parchment_bg"] ?? null;
  useEffect(() => {
    setInkTheme(terminalParchmentBg !== null);
  }, [terminalParchmentBg, setInkTheme]);

  // Wire up the WebSocket
  const { connected, liveMessage, connect, disconnect, reconnect, sendLine, sendGmcp } = useMudSocket({
    onOpen: () => {},
    onTextMessage: writeTerminal,
    onGmcpMessage: state.handleGmcp,
    onClose: () => {
      if (intentionalDisconnectRef.current) {
        intentionalDisconnectRef.current = false;
        return;
      }
      if (resumeTokenRef.current) {
        state.setReconnecting(true);
        writeSystem("Connection lost — reconnecting...");
        window.setTimeout(() => reconnect(), 500);
      } else {
        state.resetHud();
        audio.stopAll();
        writeSystem("Connection closed.");
      }
    },
    onError: () => {},
  });

  // Keep refs in sync with the latest values from useMudSocket
  useEffect(() => { sendGmcpRef.current = sendGmcp; }, [sendGmcp]);
  useEffect(() => { connectedRef.current = connected; }, [connected]);

  // Connect on mount, auto-reconnect on visibility change
  useEffect(() => {
    connect();
    const onBeforeUnload = () => disconnect();
    const onVisibilityChange = () => {
      if (document.visibilityState !== "visible" || connectedRef.current) return;
      // Prefer the in-memory resume token (short-lived grace period) when valid;
      // otherwise fall back to any persistent auth token in localStorage so the
      // picker / auto-relog can recover the session even after long backgrounding.
      if (resumeTokenRef.current) {
        state.setReconnecting(true);
        intentionalDisconnectRef.current = true;
        reconnect();
        return;
      }
      let hasAuthTokens = false;
      try {
        const saved = JSON.parse(localStorage.getItem("ambonmud_auth_tokens") ?? "{}") as Record<string, string>;
        hasAuthTokens = Object.keys(saved).length > 0;
      } catch { /* ignore */ }
      if (hasAuthTokens) {
        intentionalDisconnectRef.current = true;
        reconnect();
      }
    };
    window.addEventListener("beforeunload", onBeforeUnload);
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => {
      window.removeEventListener("beforeunload", onBeforeUnload);
      document.removeEventListener("visibilitychange", onVisibilityChange);
      disconnect();
    };
  }, [connect, disconnect, reconnect]); // eslint-disable-line react-hooks/exhaustive-deps

  // Warm the browser cache for painted panel art the moment Server.Assets lands.
  // Skins are applied as CSS background-images, so a panel only starts fetching
  // its PNG when it first paints — until the bytes arrive the element shows its
  // fallback (the purple jewel-tone gradient for drawers/admin/etc.), which reads
  // as a flash. Server.Assets arrives at login, well before any in-game panel
  // opens, so a fire-and-forget preload here means those panels paint warm. Skip
  // inline data: URIs (already resolved, nothing to fetch).
  useEffect(() => {
    for (const url of Object.values(state.serverAssets)) {
      if (typeof url !== "string" || url.startsWith("data:") || preloadedArt.has(url)) continue;
      preloadedArt.add(url);
      const img = new Image();
      img.decoding = "async";
      img.src = url;
    }
  }, [state.serverAssets]);

  const sendCommand = (raw: string) => {
    const command = raw.trim();
    if (command.length === 0) return;
    if (!sendLine(command)) return;
    // `claim [newname] <password>` carries the user's password — mask it in
    // the terminal echo (like a telnet server suppressing echo at a password
    // prompt) and keep it out of the persisted command history.
    const carriesSecret = /^claim\s/i.test(command);
    // Local echo into the background log, like a telnet client — GUI-driven
    // commands show up there too, so the log reads as a faithful session.
    echoCommand(carriesSecret ? "claim ********" : command);
    if (!carriesSecret) pushHistory(command);
    resetComposerTraversal();
  };

  // Accept a quest and surface an "accepted" toast (no server signal for accept,
  // so we fire it optimistically from the name we already have on the offer).
  const acceptQuest = (questId: string) => {
    sendCommand(`accept #${questId}`);
    const q = state.questsAvailable.find((x) => x.id === questId);
    state.setQuestNotifications((prev) => [
      {
        id: `${Date.now()}-${Math.random()}`,
        questId,
        questName: q?.name ?? "Quest",
        event: "accept" as const,
        receivedAt: Date.now(),
        questDescription: q?.description,
      },
      ...prev,
    ]);
  };

  // Inline command-input keydown: history navigation + tab completion
  const handleInputKeyDown = (event: ReactKeyboardEvent<HTMLInputElement>) => {
    const liveValue = event.currentTarget.value;
    if (event.key === "ArrowUp") {
      event.preventDefault();
      applyComposerHistoryUp(liveValue, setInputValue);
      return;
    }
    if (event.key === "ArrowDown") {
      event.preventDefault();
      applyComposerHistoryDown(setInputValue);
      return;
    }
    if (event.key === "Tab") {
      event.preventDefault();
      applyComposerCompletion(liveValue, setInputValue);
      return;
    }
    resetComposerCompletion();
  };

  const openPanel = (panel: PopoutPanel, preferredType: FeaturePopoutFocus = null) => {
    setFeatureFocus(panel === "features" ? preferredType : null);
    state.setActivePopout(panel);
  };



  // Sync state into canvas bridge for PixiJS
  useEffect(() => {
    gameStateRef.current = {
      room: state.room,
      vitals: state.vitals,
      mobs: state.mobs,
      players: state.players,
      roomItems: state.roomItems,
      combatTarget: state.combatTarget,
      inCombat: state.vitals.inCombat,
      effects: state.effects,
      character: state.character,
      mobInfo: state.mobInfo,
      groupInfo: state.groupInfo,
      dialogue: state.dialogue,
      monsterPanelOpen: monster !== null,
      quests: state.quests,
      questsAvailable: state.questsAvailable,
      shop: state.shop,
      stylistState: state.stylistState,
      puzzle: state.puzzle,
      craftingNodes: state.craftingNodes,
      roomFeatures: state.roomFeatures,
      containerContents: state.containerContents,
      questTargetRoomIds: new Set(
        state.quests.flatMap((q) =>
          q.objectives.filter((o) => o.current < o.required).flatMap((o) => o.targetRoomIds ?? []),
        ),
      ),
      serverAssets: state.serverAssets,
      worldTime: state.worldTime,
      worldWeather: state.worldWeather,
      zoneEnvironment: state.zoneEnvironment,
      skillsById: new Map(
        [...state.skills, ...state.petSkills].map((s) => [s.id, s]),
      ),
    };
  });

  // Wire canvas callbacks
  useEffect(() => {
    canvasCallbacks.sendCommand = (cmd: string) => sendCommand(cmd);
    canvasCallbacks.openShop = () => openPanel("shop");
    canvasCallbacks.openAuction = () => openPanel("auction");
    canvasCallbacks.openPuzzle = () => openPanel("puzzle");
    canvasCallbacks.openFeatures = (preferredType?: FeaturePopoutFocus) => {
      // If the user clicked the canvas chest badge and there is exactly one
      // container in the room that's closed + unlocked, open it for them so
      // the panel opens already showing contents (paired with auto-search
      // inside WorldFeaturesPopout).
      if (preferredType === "container") {
        const containers = (gameStateRef.current.roomFeatures ?? []).filter(
          (f) => f.type === "container",
        );
        if (containers.length === 1) {
          const only = containers[0];
          if (only.state === "closed" && only.locked !== true) {
            sendCommand(`open ${only.keyword}`);
          }
        }
      }
      // Door badge: if there is exactly one door and it's simply closed (not
      // locked), open it in one click. For a locked door the server-side
      // unlock command already resolves to OPEN in one step when the player
      // has the key, so the feature panel's "Unlock" button is sufficient.
      if (preferredType === "door") {
        const doors = (gameStateRef.current.roomFeatures ?? []).filter(
          (f) => f.type === "door",
        );
        if (doors.length === 1) {
          const only = doors[0];
          if (only.state === "closed" && only.locked !== true) {
            sendCommand(`open ${only.keyword}`);
          }
        }
      }
      openPanel("features", preferredType ?? null);
    };
    canvasCallbacks.openBank = () => openPanel("bank");
    canvasCallbacks.openStylist = () => openPanel("stylist");
    canvasCallbacks.openTrainer = () => openPanel("trainer");
    canvasCallbacks.openCrafting = () => openPanel("crafting");
    canvasCallbacks.openDungeon = () => openPanel("dungeon");
    canvasCallbacks.openLottery = () => openPanel("lottery");
    canvasCallbacks.openDice = () => openPanel("dice");
    canvasCallbacks.openJukebox = () => openPanel("jukebox");
    canvasCallbacks.openHousing = () => openPanel("housing");
    canvasCallbacks.openInn = () => setShowInn(true);
    canvasCallbacks.openAdminPanel = () => setShowAdminPanel(true);
    canvasCallbacks.toggleInvis = () => { sendCommand("invis"); setStaffInvisible((v) => !v); };
    canvasCallbacks.openClaim = () => setClaimRequestId((n) => n + 1);
    canvasCallbacks.openMail = () => openPanel("mail");
    canvasCallbacks.openMap = () => openPanel("map");
    canvasCallbacks.openRoom = () => openPanel("room");
    canvasCallbacks.openCharacter = () => openPanel("character");
    canvasCallbacks.openQuests = () => openPanel("quests");
    canvasCallbacks.dismissDialogue = () => {
      // Tell the server to drop dialogue state too — otherwise the next "1"-style
      // input would still resolve to a DialogueChoice on the server.
      if (gameStateRef.current.dialogue) sendCommand("bye");
      state.setDialogue(null);
    };
    canvasCallbacks.openQuestOffers = (mobKeyword: string) => {
      sendCommand(`qoffers ${mobKeyword}`);
      openPanel("questOffers");
    };
    canvasCallbacks.openVideo = (url: string) => setVideoUrl(url);
    canvasCallbacks.openMobDetail = (detail) => setMobDetail(detail);
    canvasCallbacks.openMonsterManual = (entry) => {
      // Clear any stale assessment so the new creature shows "Assessing…" until
      // its own consider result arrives.
      state.setConsiderResult(null);
      setMonster(entry);
    };
    canvasCallbacks.openItemManual = (entry) => setItem(entry);
    canvasCallbacks.openPlayerCard = (rp) => {
      // Enrich the sparse RoomPlayer with live Who data (title/description/class)
      // when available, so the card reads fully; otherwise show what we have.
      const wp = state.whoPlayers.find((p) => p.name.toLowerCase() === rp.name.toLowerCase());
      setRoomPlayer(wp ?? {
        name: rp.name,
        level: rp.level,
        race: "",
        playerClass: "",
        title: null,
        guild: null,
        groupSize: 0,
        idle: 0,
        sprite: rp.sprite ?? null,
        description: null,
      });
    };
    canvasCallbacks.openImagePreview = (url: string) => setImagePreviewUrl(url);
    canvasCallbacks.prefillCommand = (text: string) => prefillInput(text);
    return () => {
      canvasCallbacks.sendCommand = null;
      canvasCallbacks.openShop = null;
      canvasCallbacks.openAuction = null;
      canvasCallbacks.openPuzzle = null;
      canvasCallbacks.openFeatures = null;
      canvasCallbacks.openBank = null;
      canvasCallbacks.openStylist = null;
      canvasCallbacks.openTrainer = null;
      canvasCallbacks.openCrafting = null;
      canvasCallbacks.openDungeon = null;
      canvasCallbacks.openLottery = null;
      canvasCallbacks.openJukebox = null;
      canvasCallbacks.openHousing = null;
      canvasCallbacks.openInn = null;
      canvasCallbacks.openAdminPanel = null;
      canvasCallbacks.toggleInvis = null;
      canvasCallbacks.openClaim = null;
      canvasCallbacks.openMail = null;
      canvasCallbacks.openMap = null;
      canvasCallbacks.openRoom = null;
      canvasCallbacks.openCharacter = null;
      canvasCallbacks.openQuests = null;
      canvasCallbacks.dismissDialogue = null;
      canvasCallbacks.openQuestOffers = null;
      canvasCallbacks.openVideo = null;
      canvasCallbacks.openMobDetail = null;
      canvasCallbacks.openMonsterManual = null;
      canvasCallbacks.openPlayerCard = null;
      canvasCallbacks.openItemManual = null;
      canvasCallbacks.openImagePreview = null;
      canvasCallbacks.prefillCommand = null;
    };
  }, [sendCommand]); // eslint-disable-line react-hooks/exhaustive-deps

  // Cast skill flow
  const completeCast = (skillId: string, cooldownMs: number, targetName?: string) => {
    const now = Date.now();
    state.setSkills((prev) =>
      prev.map((skill) =>
        skill.id === skillId
          ? { ...skill, cooldownRemainingMs: Math.max(skill.cooldownRemainingMs, cooldownMs), receivedAt: now }
          : skill,
      ),
    );
    const cmd = targetName ? `cast ${skillId} ${targetName}` : `cast ${skillId}`;
    sendCommand(cmd);
    pendingCastRef.current = null;
  };

  // Pet skills always target the owner's current combat target server-side, so we just
  // fire `pet <id>` and let the server pick a target / return an error if not in combat.
  const completePetCast = (skillId: string, cooldownMs: number) => {
    const now = Date.now();
    state.setPetSkills((prev) =>
      prev.map((skill) =>
        skill.id === skillId
          ? { ...skill, cooldownRemainingMs: Math.max(skill.cooldownRemainingMs, cooldownMs), receivedAt: now }
          : skill,
      ),
    );
    sendCommand(`pet ${skillId}`);
  };

  const handleCastSkill = (skillId: string, cooldownMs: number) => {
    const petSkill = state.petSkills.find((s) => s.id === skillId);
    if (petSkill) {
      completePetCast(skillId, cooldownMs);
      return;
    }
    const skill = state.skills.find((s) => s.id === skillId);
    if (!skill) return;
    const t = skill.targetType.toUpperCase();
    const needsTarget = t === "ENEMY" || t === "ALLY";
    if (needsTarget && gameStateRef.current.combatTarget?.targetName) {
      completeCast(skillId, cooldownMs, gameStateRef.current.combatTarget.targetName);
      return;
    }
    if (needsTarget) {
      pendingCastRef.current = { skillId, skillName: skill.name, cooldownMs, targetType: t };
      state.setToast(`Select a target for ${skill.name}`);
      return;
    }
    completeCast(skillId, cooldownMs);
  };

  // Wire canvas target selection
  useEffect(() => {
    canvasCallbacks.onTargetSelected = (targetName: string) => {
      const pending = pendingCastRef.current;
      if (!pending) return;
      state.setToast(null);
      completeCast(pending.skillId, pending.cooldownMs, targetName);
    };
    return () => { canvasCallbacks.onTargetSelected = null; };
  }, [completeCast]); // eslint-disable-line react-hooks/exhaustive-deps

  // Audio for room music/ambient. A jukebox track (paid by anyone in the room)
  // overrides the room's default music for everyone until it ends. Its start
  // anchor (derived from the playlist duration + countdown snapshot) position-
  // syncs late joiners to the part of the song the room is already hearing.
  const jukeboxNow = state.jukebox?.nowPlaying ?? null;
  const jukeboxMusic = jukeboxNow?.url ?? null;
  const jukeboxDuration = jukeboxNow
    ? state.jukebox?.songs.find((s) => s.number === jukeboxNow.number)?.durationSeconds ?? 0
    : 0;
  const jukeboxStartedAtMs = jukeboxNow && jukeboxDuration > 0
    ? jukeboxNow.receivedAt - Math.max(0, jukeboxDuration - jukeboxNow.secondsRemaining) * 1000
    : null;
  // Deps stay URL-keyed on purpose: Jukebox.Info re-emissions for the same
  // playing song refresh the anchor by a rounding second or two, and reseeking
  // an already-synced track for that would be an audible glitch.
  useEffect(() => { audio.playMusic(jukeboxMusic ?? state.room.music ?? null, jukeboxMusic ? jukeboxStartedAtMs : null); }, [jukeboxMusic, state.room.music]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { audio.playAmbient(state.room.ambient ?? null); }, [state.room.ambient]); // eslint-disable-line react-hooks/exhaustive-deps
  // Dialogue voice-over: play the current node's clip when it changes (a new node
  // supersedes the previous one via playVoice's internal stop).
  useEffect(() => {
    const url = state.dialogue?.voiceUrl ?? null;
    if (url) audio.playVoice(url);
  }, [state.dialogue?.voiceUrl]); // eslint-disable-line react-hooks/exhaustive-deps
  // ...and stop it the instant the dialogue window closes — the conversation
  // ended, was dismissed, or the player left the room (issue #1249). The voice
  // should only play while the window is open.
  const dialogueOpen = state.dialogue !== null;
  useEffect(() => {
    if (!dialogueOpen) audio.playVoice(null);
  }, [dialogueOpen]); // eslint-disable-line react-hooks/exhaustive-deps

  // Combat audio
  const hpPercent = state.vitals.maxHp > 0 ? state.vitals.hp / state.vitals.maxHp : 1;
  useEffect(() => { audio.setCombatState(state.vitals.inCombat, hpPercent); }, [state.vitals.inCombat, hpPercent]); // eslint-disable-line react-hooks/exhaustive-deps

  // Auto-dismiss toast
  const setToast = state.setToast;
  useEffect(() => {
    if (!state.toast) return;
    const t = setTimeout(() => { setToast(null); pendingCastRef.current = null; }, 4000);
    return () => clearTimeout(t);
  }, [state.toast, setToast]);

  // Redraw minimap canvas when the map drawer opens
  useEffect(() => {
    if (state.activePopout !== "map" || mapTab !== "map") return;
    const handle = window.requestAnimationFrame(() => drawMap());
    startPulse();
    return () => {
      window.cancelAnimationFrame(handle);
      stopPulse();
    };
  }, [state.activePopout, mapTab, drawMap, startPulse, stopPulse]);

  // Close look-target modal on Escape
  useEffect(() => {
    if (!state.lookTarget) return;
    const handler = (e: globalThis.KeyboardEvent) => {
      if (e.key === "Escape") state.setLookTarget(null);
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [state.lookTarget]); // eslint-disable-line react-hooks/exhaustive-deps

  // Route an Examine-triggered item look into the parchment item card (instead
  // of the default look-target inspect modal), carrying its full description.
  useEffect(() => {
    const lt = state.lookTarget;
    const pending = pendingExamineRef.current;
    if (!pending || !lt || lt.type !== "item") return;
    pendingExamineRef.current = null;
    // Defer out of the effect body so we swap modals in a fresh tick rather
    // than cascading renders synchronously.
    queueMicrotask(() => {
      setItem({
        name: lt.name,
        description: lt.description || null,
        image: lt.image ?? pending.image,
        video: null,
        takeable: false,
        equippedSlot: pending.equippedSlot,
      });
      state.setLookTarget(null);
    });
  }, [state.lookTarget]); // eslint-disable-line react-hooks/exhaustive-deps

  // Close consider card on Escape
  useEffect(() => {
    if (!state.considerResult) return;
    const handler = (e: globalThis.KeyboardEvent) => {
      if (e.key === "Escape") state.setConsiderResult(null);
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [state.considerResult]); // eslint-disable-line react-hooks/exhaustive-deps

  // Canvas modals (mob detail / image preview / video): move focus to the
  // close button while open, restore it to the opener on dismiss, and close
  // on Escape regardless of where focus currently sits (WCAG 2.4.3 / 2.1.2).
  useEffect(() => {
    if (!mobDetail) return;
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    mobDetailCloseRef.current?.focus();
    const handler = (e: globalThis.KeyboardEvent) => {
      if (e.key === "Escape") setMobDetail(null);
    };
    window.addEventListener("keydown", handler);
    return () => {
      window.removeEventListener("keydown", handler);
      opener?.focus();
    };
  }, [mobDetail]);

  useEffect(() => {
    if (!imagePreviewUrl) return;
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    imagePreviewCloseRef.current?.focus();
    const handler = (e: globalThis.KeyboardEvent) => {
      if (e.key === "Escape") setImagePreviewUrl(null);
    };
    window.addEventListener("keydown", handler);
    return () => {
      window.removeEventListener("keydown", handler);
      opener?.focus();
    };
  }, [imagePreviewUrl]);

  useEffect(() => {
    if (!videoUrl) return;
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    videoCloseRef.current?.focus();
    const handler = (e: globalThis.KeyboardEvent) => {
      if (e.key === "Escape") {
        setVideoClosing(true);
        setTimeout(() => { setVideoUrl(null); setVideoClosing(false); }, 600);
      }
    };
    window.addEventListener("keydown", handler);
    return () => {
      window.removeEventListener("keydown", handler);
      opener?.focus();
    };
  }, [videoUrl]);

  // Close NPC dialogue / quest offers on Escape — but only when the
  // conversation has reached a state with no further choices (mirrors the
  // click-to-dismiss behaviour in DialogueOverlay). A mid-conversation Escape
  // is ignored so players don't accidentally abandon an active choice branch.
  const dialogue = state.dialogue;
  const hasQuestsAvailable = state.questsAvailable.length > 0;
  useEffect(() => {
    if (!dialogue && !hasQuestsAvailable) return;
    const hasActiveChoices = dialogue !== null && dialogue.choices.length > 0;
    if (hasActiveChoices) return;
    const handler = (e: globalThis.KeyboardEvent) => {
      if (e.key === "Escape") {
        const target = e.target as HTMLElement | null;
        if (target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA")) return;
        canvasCallbacks.dismissDialogue?.();
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [dialogue, hasQuestsAvailable]);

  // Ctrl+K (or Cmd+K) opens the command palette
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

  // Close the dungeon panel when the player actually enters a dungeon — the
  // catalog-plus-"Resume Run" view confuses users into hammering the re-enter
  // button. The conditional toolbar button remains for intentional access.
  const dungeonActive = state.dungeonInfo?.active ?? false;
  const prevDungeonActiveRef = useRef(dungeonActive);
  useEffect(() => {
    if (dungeonActive && !prevDungeonActiveRef.current) {
      state.setActivePopout((prev) => (prev === "dungeon" ? null : prev));
    }
    prevDungeonActiveRef.current = dungeonActive;
  }, [dungeonActive]); // eslint-disable-line react-hooks/exhaustive-deps

  // Keyboard digit shortcuts
  useEffect(() => {
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape" && pendingCastRef.current) {
        pendingCastRef.current = null;
        state.setToast(null);
        return;
      }
      const target = event.target as HTMLElement;
      if (target.tagName === "INPUT" || target.tagName === "TEXTAREA") return;
      // Quickbar first: if the keypress resolved to a digit character, route there
      // regardless of Shift state. This keeps AZERTY/similar layouts working — typing
      // "1" requires Shift on those layouts, so a Shift-first branch would hijack it.
      const keyDigit = parseInt(event.key, 10);
      if (keyDigit >= 1 && keyDigit <= 9) {
        const skill = quickbar.slots[keyDigit - 1];
        if (!skill) return;
        const elapsed = Date.now() - skill.receivedAt;
        const remaining = Math.max(0, skill.cooldownRemainingMs - elapsed);
        if (remaining > 0) return;
        handleCastSkill(skill.id, skill.cooldownMs);
        return;
      }
      // Otherwise, Shift+DigitN (QWERTY shift produces "!"/"@"/"#" — not a digit) routes
      // to the pet bar. Using event.code makes this layout-independent for that case.
      if (event.shiftKey && event.code.startsWith("Digit")) {
        const petDigit = parseInt(event.code.slice(5), 10);
        if (petDigit >= 1 && petDigit <= 9) {
          const skill = state.petSkills[petDigit - 1];
          if (!skill) return;
          const elapsed = Date.now() - skill.receivedAt;
          const remaining = Math.max(0, skill.cooldownRemainingMs - elapsed);
          if (remaining > 0) return;
          handleCastSkill(skill.id, skill.cooldownMs);
        }
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [quickbar.slots, state.petSkills, handleCastSkill]); // eslint-disable-line react-hooks/exhaustive-deps

  const sendChatMessage = (channel: ChatChannel, message: string, target: string | null): boolean => {
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
    sendCommand(command);
    return true;
  };

  const hasCharacterProfile = state.character.name !== "-";

  // First-time onboarding hints: pulse the Inventory sidebar button until opened,
  // then pulse the Equip action on wearable items until one is worn. Persisted
  // per-character in localStorage so we only nag new players.
  const onboarding = useOnboarding(hasCharacterProfile ? state.character.name : "");
  const hasUnequippedWearable = useMemo(
    () => state.inventory.some((i) => i.slot != null),
    [state.inventory],
  );
  const showEquipHint =
    hasCharacterProfile
    && hasUnequippedWearable
    && !onboarding.flags.equipHintDone;

  // Opening the inventory drawer counts as acknowledging the sidebar hint.
  useEffect(() => {
    if (state.activePopout === "inventory" && !onboarding.flags.invHintDone) {
      onboarding.markInvHintDone();
    }
  }, [state.activePopout, onboarding]);

  // Once the player has no unequipped wearables left, the equip hint is done.
  useEffect(() => {
    if (
      onboarding.flags.invHintDone
      && !onboarding.flags.equipHintDone
      && !hasUnequippedWearable
      && hasCharacterProfile
    ) {
      onboarding.markEquipHintDone();
    }
  }, [onboarding, hasUnequippedWearable, hasCharacterProfile]);

  const displayRace = state.character.race ? titleCaseWords(state.character.race) : "";
  const displayClassName = state.character.className ? titleCaseWords(state.character.className) : "";
  const xpText = state.vitals.xpToNextLevel === null
    ? "MAX"
    : `${state.vitals.xpIntoLevel.toLocaleString()} / ${state.vitals.xpToNextLevel.toLocaleString()}`;
  const xpValue = state.vitals.xpToNextLevel === null ? 1 : state.vitals.xpIntoLevel;
  const xpMax = state.vitals.xpToNextLevel === null ? 1 : Math.max(1, state.vitals.xpToNextLevel);
  const visibleEffects = state.effects.slice(0, 4);
  const hiddenEffectsCount = Math.max(0, state.effects.length - visibleEffects.length);

  // The drawer body keeps rendering the previous panel for the duration of the close
  // animation (~300ms). Without this, the parent clears the children the instant
  // activePopout flips to null, leaving an empty animating frame.
  const [drawerPanel, setDrawerPanel] = useState(state.activePopout);
  const [prevActivePopout, setPrevActivePopout] = useState(state.activePopout);
  const currentZone = useMemo(() => {
    const id = state.room.id;
    if (!id) return null;
    const colon = id.indexOf(":");
    return colon > 0 ? id.slice(0, colon) : null;
  }, [state.room.id]);
  if (state.activePopout !== prevActivePopout) {
    setPrevActivePopout(state.activePopout);
    if (state.activePopout !== null) {
      // Opening or switching panels — update immediately (no animation gap)
      setDrawerPanel(state.activePopout);
    }
    // Closing (null) — leave drawerPanel alone; the timeout below clears it after the animation
  }
  useEffect(() => {
    if (state.activePopout !== null) return;
    const t = window.setTimeout(() => setDrawerPanel(null), 300);
    return () => window.clearTimeout(t);
  }, [state.activePopout]);

  // React 19 registers `wheel` as a passive listener, so calling preventDefault()
  // inside the synthetic onWheel handler is ignored. Attach a native non-passive
  // listener so wheel-zooming the world map doesn't also scroll the drawer/page.
  useEffect(() => {
    if (drawerPanel !== "map") return;
    const canvas = mapCanvasRef.current;
    if (!canvas) return;
    const stop = (e: WheelEvent) => e.preventDefault();
    canvas.addEventListener("wheel", stop, { passive: false });
    return () => canvas.removeEventListener("wheel", stop);
  }, [drawerPanel]); // eslint-disable-line react-hooks/exhaustive-deps

  // The single feature a freshly-opened features panel shows — drives the Drawer
  // title and skin background so the chrome matches what WorldFeaturesPopout renders.
  const featurePanelFeature = useMemo(
    () => (drawerPanel === "features" ? pickFocusedFeature(state.roomFeatures, featureFocus) : null),
    [drawerPanel, state.roomFeatures, featureFocus],
  );

  const drawerTitle = useMemo(() => {
    switch (drawerPanel) {
      case "character": return "Character";
      case "inventory": return "Inventory";
      case "equipment": return "Equipment";
      case "spellbook": return "Spellbook";
      case "quests": return "Quests";
      case "questOffers": return "Quest Offers";
      case "chatboard": return "Social Board";
      case "whoboard": return "Who";
      case "guildboard": return "Guild";
      case "friendsboard": return "Friends";
      case "groupboard": return "Group";
      case "shop": return state.shop?.name ?? "Shop";
      case "puzzle": return "Puzzle";
      case "features": return featurePanelFeature
        ? (featurePanelFeature.type === "container" ? "" : featurePanelFeature.name)
        : "Interactive Feature";
      case "trainer": return "Trainer";
      case "mail": return "Mail";
      case "crafting": return "Crafting";
      case "professions": return "Professions";
      case "housing": return "Housing";
      case "leaderboard": return "Leaderboard";
      case "bank": return "The Vault";
      case "stylist": return "Stylist";
      case "inn": return state.room.title !== "-" ? state.room.title : "Inn";
      case "auction": return "Auction House";
      case "dungeon": return "Dungeon";
      case "lottery": return "Lottery";
      case "dice": return "Dice Table";
      case "jukebox": return "Jukebox";
      case "combatlog": return "Battle Journal";
      case "arcanum": return "The Arcanum";
      case "help": return "Command Reference";
      case "room": return state.room.title !== "-" ? state.room.title : "Room Details";
      case "map": return "World Map";
      default: return "";
    }
  }, [drawerPanel, state.shop?.name, state.room.title, featurePanelFeature]);

  const sortedExits = useMemo(() => sortExits(state.room.exits), [state.room.exits]);
  const questMarkerCount = useMemo(
    () => new Set(
      state.quests.flatMap((q) =>
        q.objectives.filter((o) => o.current < o.required).flatMap((o) => o.targetRoomIds ?? []),
      ),
    ).size,
    [state.quests],
  );

  const closeDrawer = () => openPanel(null);

  return (
    <>
      {state.character.isDemo && (
        <DemoBanner
          autoOpen={(state.character.level ?? 1) >= 2}
          openRequestId={claimRequestId}
          backgroundImage={state.serverAssets["login_claim_bg"] ?? null}
          backgroundImagePortrait={state.serverAssets["login_claim_bg_portrait"] ?? null}
          // sendLine (not sendCommand) — claim contains the user's new password,
          // so we must NOT push it into command history / localStorage.
          onClaim={(line) => { sendLine(line); }}
        />
      )}
      <GameShell
        connected={connected}
        hasCharacterProfile={hasCharacterProfile}
        vitals={state.vitals}
        room={state.room}
        exits={sortedExits}
        serverAssets={state.serverAssets}
        worldTime={state.worldTime}
        worldWeather={state.worldWeather}
        worldEvents={state.worldEvents}
        combatLogMessages={state.combatLogMessages}
        combatTarget={state.combatTarget}
        inCombat={state.vitals.inCombat}
        inventory={state.inventory}
        quickbarSlots={quickbar.slots}
        petSkills={state.petSkills}
        onCastSkill={handleCastSkill}
        onQuickbarSwap={quickbar.swap}
        onQuickbarAssign={quickbar.assign}
        onQuickbarClear={quickbar.clear}
        activePopout={state.activePopout}
        arcanumPledged={state.arcanumStatus?.pledged ?? false}
        onCommand={sendCommand}
        onOpenPanel={(panel) => openPanel(panel)}
        onOpenTerminal={openTerminal}
        audio={audio}
      />

      <Drawer
        open={state.activePopout !== null}
        title={drawerTitle}
        onClose={closeDrawer}
        variant={
          drawerPanel === "puzzle"
            ? "tome"
            : drawerPanel === "chatboard"
            ? "board"
            : drawerPanel === "whoboard"
            ? "starframe"
            : drawerPanel === "guildboard"
            ? "archive"
            : drawerPanel === "friendsboard"
            ? "starframe"
            : drawerPanel === "groupboard"
            ? "stainedglass"
            : drawerPanel === "help"
            ? "codex"
            : drawerPanel === "stylist"
            ? "boudoir"
            : drawerPanel === "housing"
            ? "estate"
            : drawerPanel === "lottery"
            ? "fortune"
            : drawerPanel === "map"
            ? "chart"
            : drawerPanel === "dice"
            ? "dicetable"
            : drawerPanel === "jukebox"
            ? "jukebox"
            : drawerPanel === "auction"
            ? "auctionhouse"
            : drawerPanel === "crafting"
            ? "forge"
            : drawerPanel === "professions"
            ? "professions"
            : drawerPanel === "character"
            ? "cabinet"
            : drawerPanel === "bank"
            ? "vault"
            : drawerPanel === "features"
            ? "feature"
            : drawerPanel === "inventory"
            ? "satchel"
            : drawerPanel === "equipment"
              ? "equipment"
              : drawerPanel === "shop"
                ? "shop"
                : drawerPanel === "trainer"
                  ? "trainer"
                  : drawerPanel === "combatlog"
                    ? "journal"
                    : drawerPanel === "quests"
                      ? "questboard"
                      : drawerPanel === "spellbook"
                        ? "grimoire"
                        : drawerPanel === "mail"
                          ? "mail"
                          : "default"
        }
        skinBg={
          drawerPanel === "puzzle"
            ? (state.puzzle?.puzzles.find((p) => p.backgroundImage)?.backgroundImage
                ?? state.serverAssets["puzzle_bg"])
            : drawerPanel === "whoboard"
            ? state.serverAssets["who_bg"]
            : drawerPanel === "guildboard"
            ? state.serverAssets["guild_bg"]
            : drawerPanel === "friendsboard"
            ? state.serverAssets["friends_bg"]
            : drawerPanel === "groupboard"
            ? state.serverAssets["group_bg"]
            : drawerPanel === "help"
            ? state.serverAssets["command_reference_bg"]
            : drawerPanel === "stylist"
            ? state.serverAssets["stylist_bg"]
            : drawerPanel === "housing"
            ? state.serverAssets["housing_bg"]
            : drawerPanel === "lottery"
            ? state.serverAssets["lottery_bg"]
            : drawerPanel === "map"
            ? state.serverAssets["map_background"]
            : drawerPanel === "dice"
            ? state.serverAssets["dice_bg"]
            : drawerPanel === "jukebox"
            ? state.serverAssets["jukebox_bg"]
            : drawerPanel === "auction"
            ? state.serverAssets["auction_bg"]
            : drawerPanel === "crafting"
            ? state.serverAssets["crafting_bg"]
            : drawerPanel === "professions"
            ? state.serverAssets["professions_bg"]
            : drawerPanel === "character"
            ? state.serverAssets["character_bg"]
            : drawerPanel === "bank"
            ? state.serverAssets["bank_bg"]
            : drawerPanel === "features"
            ? (featurePanelFeature && featurePanelFeature.type !== "lever"
                ? (featureArt(featurePanelFeature, state.serverAssets) ?? undefined)
                : undefined)
            : drawerPanel === "inventory"
            ? state.serverAssets["inventory_satchel_bg"]
            : drawerPanel === "equipment"
              ? state.serverAssets["equipment_bg"]
              : drawerPanel === "shop"
                ? state.serverAssets["shop_bg"]
                : drawerPanel === "trainer"
                  ? state.serverAssets["trainer_bg"]
                  : drawerPanel === "combatlog"
                    ? state.serverAssets["journal_bg"]
                    : drawerPanel === "quests"
                      ? state.serverAssets["quest_board_bg"]
                      : drawerPanel === "spellbook"
                        ? state.serverAssets["spellbook_bg"]
                        : drawerPanel === "mail"
                          ? state.serverAssets["mail_bg"]
                          : undefined
        }
        initialHeight={drawerPanel === "chatboard" || drawerPanel === "whoboard" || drawerPanel === "guildboard" || drawerPanel === "friendsboard" || drawerPanel === "groupboard" || drawerPanel === "help" || drawerPanel === "stylist" || drawerPanel === "housing" || drawerPanel === "lottery" || drawerPanel === "dice" || drawerPanel === "jukebox" || drawerPanel === "auction" || drawerPanel === "crafting" || drawerPanel === "professions" ? 0.94 : undefined}
      >
        {/* Panels are lazy chunks; the drawer chrome shows while one loads. */}
        <Suspense fallback={null}>
        {drawerPanel === "character" && (
          <CharacterPanel
            connected={connected}
            hasCharacterProfile={hasCharacterProfile}
            canOpenEquipment={hasCharacterProfile}
            character={state.character}
            displayRace={displayRace}
            displayClassName={displayClassName}
            vitals={state.vitals}
            statusVarLabels={state.statusVarLabels}
            xpValue={xpValue}
            xpMax={xpMax}
            xpText={xpText}
            effects={state.effects}
            visibleEffects={visibleEffects}
            hiddenEffectsCount={hiddenEffectsCount}
            achievements={state.achievements}
            quests={state.quests}
            questNotifications={state.questNotifications}
            charStats={state.charStats}
            guildInfo={state.guildInfo}
            groupInfo={state.groupInfo}
            activeTitle={state.character.title}
            spriteList={state.spriteList}
            currencies={state.currencies}
            factions={state.factions}
            petState={state.petState}
            prestigeInfo={state.prestigeInfo}
            leaderboard={state.leaderboard}
            serverAssets={state.serverAssets}
            onDismissQuestNotification={(id) => state.setQuestNotifications((prev) => prev.filter((n) => n.id !== id))}
            onAbandonQuest={(name) => sendCommand(`quest abandon ${name}`)}
            onOpenInventory={() => openPanel("inventory")}
            onOpenEquipment={() => openPanel("equipment")}
            onOpenProfessions={() => openPanel("professions")}
            onCommand={sendCommand}
            onLogout={() => {
              try {
                const saved = JSON.parse(localStorage.getItem("ambonmud_auth_tokens") ?? "{}") as Record<string, string>;
                delete saved[state.character.name];
                localStorage.setItem("ambonmud_auth_tokens", JSON.stringify(saved));
              } catch { /* ignore */ }
              resumeTokenRef.current = null;
              sendGmcp("Session.Logout", {});
            }}
          />
        )}

        {drawerPanel === "inventory" && (
          <InventoryPanel
            connected={connected}
            hasCharacterProfile={hasCharacterProfile}
            inventory={state.inventory}
            players={state.players}
            canManageItems={connected && hasCharacterProfile}
            roomFeatures={state.roomFeatures}
            containerContents={state.containerContents}
            serverAssets={state.serverAssets}
            onWearItem={(name) => sendCommand(`wear ${name}`)}
            onDropItem={(name) => sendCommand(`drop ${name}`)}
            onGiveItem={(keyword, player) => sendCommand(`give ${keyword} ${player}`)}
            onExamineItem={(it, image) => {
              // Fetch the full look; the response (Room.LookTarget) is routed
              // into the item card by the effect below.
              pendingExamineRef.current = { image };
              sendCommand(`look ${it.keyword}`);
              window.setTimeout(() => { pendingExamineRef.current = null; }, 2500);
            }}
            onCommand={sendCommand}
            equipHint={showEquipHint}
          />
        )}

        {drawerPanel === "equipment" && (
          <EquipmentPanel
            connected={connected}
            hasCharacterProfile={hasCharacterProfile}
            character={state.character}
            equipment={state.equipment}
            slotDefs={state.equipmentSlotDefs}
            onExamineItem={(it, image, slot) => {
              pendingExamineRef.current = { image, equippedSlot: slot };
              sendCommand(`look ${it.keyword}`);
              window.setTimeout(() => { pendingExamineRef.current = null; }, 2500);
            }}
          />
        )}

        {drawerPanel === "chatboard" && (
          <ChatBoardPanel
            connected={connected}
            canChat={connected && hasCharacterProfile}
            playerName={state.character.name}
            activeChannel={state.activeChatChannel}
            chatByChannel={state.chatByChannel}
            emotePresets={state.emotePresets}
            tellTarget={state.tellTarget}
            backgroundImage={state.serverAssets["chat_bg"] ?? null}
            onTellTargetChange={state.setTellTarget}
            onChannelChange={state.setActiveChatChannel}
            onSendMessage={sendChatMessage}
            onCommand={sendCommand}
          />
        )}

        {drawerPanel === "whoboard" && (
          <WhoBoardPanel
            connected={connected}
            canChat={connected && hasCharacterProfile}
            playerName={state.character.name}
            whoPlayers={state.whoPlayers}
            serverAssets={state.serverAssets}
            friendNames={state.friends.map((f) => f.name)}
            onRequestWho={() => sendCommand("who")}
            onExamine={(p) => setExaminePlayer(p)}
            onTellPlayer={(name) => {
              state.setActiveChatChannel("tell");
              state.setTellTarget(name);
              openPanel("chatboard");
            }}
            onAddFriend={(name) => sendCommand(`friend add ${name}`)}
          />
        )}

        {drawerPanel === "guildboard" && (
          <GuildBoardPanel
            connected={connected}
            canChat={connected && hasCharacterProfile}
            playerName={state.character.name}
            guildInfo={state.guildInfo}
            pendingGuildInvite={state.pendingGuildInvite}
            guildMembers={state.guildMembers}
            guildHall={state.guildHall}
            gchatMessages={state.chatByChannel.gchat}
            onSendMessage={sendChatMessage}
            onCommand={sendCommand}
          />
        )}

        {drawerPanel === "friendsboard" && (
          <FriendsBoardPanel
            connected={connected}
            canChat={connected && hasCharacterProfile}
            friends={state.friends}
            friendNotifications={state.friendNotifications}
            whoPlayers={state.whoPlayers}
            serverAssets={state.serverAssets}
            onRequestWho={() => sendCommand("who")}
            onExamine={(p) => setExaminePlayer(p)}
            onTellPlayer={(name) => {
              state.setActiveChatChannel("tell");
              state.setTellTarget(name);
              openPanel("chatboard");
            }}
            onAddFriend={(name) => sendCommand(`friend add ${name}`)}
            onRemoveFriend={(name) => sendCommand(`friend remove ${name}`)}
          />
        )}

        {drawerPanel === "groupboard" && (
          <GroupBoardPanel
            connected={connected}
            canChat={connected && hasCharacterProfile}
            playerName={state.character.name}
            groupInfo={state.groupInfo}
            pendingGroupInvite={state.pendingGroupInvite}
            gtellMessages={state.chatByChannel.gtell}
            onSendMessage={sendChatMessage}
            onCommand={sendCommand}
          />
        )}

        {drawerPanel === "shop" && state.shop && (
          <ShopPopout
            shop={state.shop}
            inventory={state.inventory}
            equipment={state.equipment}
            gold={state.vitals.gold}
            onBuyItem={(keyword) => sendCommand(`buy ${keyword}`)}
            onSellItem={(keyword) => sendCommand(`sell ${keyword}`)}
            onShowBuyItem={(it) => setItem({
              name: it.name,
              description: it.description || null,
              image: it.image,
              video: it.video,
              takeable: false,
            })}
            onShowSellItem={(it, image) => {
              pendingExamineRef.current = { image };
              sendCommand(`look ${it.keyword}`);
              window.setTimeout(() => { pendingExamineRef.current = null; }, 2500);
            }}
          />
        )}

        {drawerPanel === "puzzle" && state.puzzle && (
          <PuzzlePopout
            puzzle={state.puzzle}
            puzzleResult={state.puzzleResult}
            serverAssets={state.serverAssets}
            onCommand={sendCommand}
          />
        )}

        {drawerPanel === "features" && (
          <WorldFeaturesPopout
            roomFeatures={state.roomFeatures}
            containerContents={state.containerContents}
            preferredType={featureFocus}
            serverAssets={state.serverAssets}
            onCommand={sendCommand}
          />
        )}

        {drawerPanel === "trainer" && (
          state.trainer ? (
            <TrainerPanel
              trainer={state.trainer}
              playerLevel={state.vitals.level ?? 1}
              playerGold={state.vitals.gold}
              onCommand={sendCommand}
            />
          ) : (
            <TrainerAutoLoad onCommand={sendCommand} />
          )
        )}

        {drawerPanel === "spellbook" && (
          <SpellbookPanel
            skills={state.skills}
            quickbarSlotIds={quickbar.slotIds}
            playerClass={displayClassName}
            playerLevel={state.vitals.level ?? 1}
            availableSkillPoints={state.trainer?.availableSkillPoints}
            onShowSkillInfo={(skill) => {
              const cd = skill.cooldownMs > 0 ? `${skill.cooldownMs / 1000}s cooldown` : "No cooldown";
              state.setToast(`${skill.name} — ${skill.manaCost} MP, ${cd}`);
            }}
            onAssignSlot={quickbar.assign}
            onOpenCrafting={() => openPanel("crafting")}
          />
        )}

        {drawerPanel === "mail" && (
          <MailPanel
            connected={connected}
            hasCharacterProfile={hasCharacterProfile}
            inbox={state.mailInbox}
            openMessage={state.mailMessage}
            inventory={state.inventory}
            gold={state.vitals.gold}
            onReadMessage={(index) => sendCommand(`mail read ${index}`)}
            onDeleteMessage={(index) => sendCommand(`mail delete ${index}`)}
            onCompose={(recipient, body, goldAmount, itemKeyword) => {
              let cmd = `mail send ${recipient}`;
              if (goldAmount > 0) cmd += ` gold ${goldAmount}`;
              if (itemKeyword) cmd += ` item ${itemKeyword}`;
              sendCommand(cmd);
              for (const line of body.split("\n")) sendCommand(line);
              sendCommand(".");
            }}
            onClaim={(index) => sendCommand(`mail claim ${index}`)}
            onClearMessage={() => state.setMailMessage(null)}
            onCommand={sendCommand}
          />
        )}

        {drawerPanel === "crafting" && (
          <CraftingPanel
            connected={connected}
            hasCharacterProfile={hasCharacterProfile}
            recipes={state.craftingRecipes}
            skills={state.craftingSkills}
            inventory={state.inventory}
            uiFeedbackFeed={state.uiFeedbackFeed}
            onCraft={(recipeKeyword) => sendCommand(`craft ${recipeKeyword}`)}
            onRequestRecipes={() => sendCommand("recipes")}
            onLoadSkills={() => sendCommand("craftskills")}
          />
        )}

        {drawerPanel === "professions" && (
          <ProfessionsPanel
            connected={connected}
            hasCharacterProfile={hasCharacterProfile}
            skills={state.craftingSkills}
            onLoadSkills={() => sendCommand("craftskills")}
          />
        )}

        {drawerPanel === "quests" && (
          <QuestPanel
            connected={connected}
            hasCharacterProfile={hasCharacterProfile}
            quests={state.quests}
            questsAvailable={state.questsAvailable}
            questNotifications={state.questNotifications}
            dailyQuests={state.dailyQuests}
            weeklyQuests={state.weeklyQuests}
            autoQuest={state.autoQuest}
            globalQuest={state.globalQuest}
            features={state.serverFeatures}
            onDismissQuestNotification={(id) => state.setQuestNotifications((prev) => prev.filter((n) => n.id !== id))}
            onAbandonQuest={(name) => sendCommand(`quest abandon ${name}`)}
            onAcceptQuest={(name) => sendCommand(`accept ${name}`)}
            onCommand={sendCommand}
          />
        )}

        {drawerPanel === "questOffers" && (
          <QuestOfferPanel
            connected={connected}
            questsAvailable={state.questsAvailable}
            onAcceptQuest={acceptQuest}
            onTurnInQuest={(questId) => sendCommand(`quest turnin #${questId}`)}
          />
        )}

        {drawerPanel === "housing" && (
          <HousingPanel
            connected={connected}
            hasCharacterProfile={hasCharacterProfile}
            housing={state.housing}
            templates={state.housingTemplates}
            room={state.room}
            uiFeedbackFeed={state.uiFeedbackFeed}
            gold={state.vitals.gold}
            onSendCommand={sendCommand}
          />
        )}

        {drawerPanel === "bank" && (
          <BankPanel bankState={state.bankState} serverAssets={state.serverAssets} onCommand={sendCommand} />
        )}

        {drawerPanel === "stylist" && (
          <StylistPanel stylistState={state.stylistState} serverAssets={state.serverAssets} onCommand={sendCommand} />
        )}

        {drawerPanel === "auction" && (
          <AuctionPanel
            listings={state.auctionListings}
            inventory={state.inventory}
            playerName={state.character.name}
            isDemo={state.character.isDemo}
            feedbackFeed={state.uiFeedbackFeed}
            onCommand={sendCommand}
          />
        )}

        {drawerPanel === "dungeon" && (
          <DungeonPanel
            dungeonInfo={state.dungeonInfo}
            dungeonCatalog={state.dungeonCatalog}
            uiFeedbackFeed={state.uiFeedbackFeed}
            onCommand={sendCommand}
          />
        )}

        {drawerPanel === "lottery" && (
          <LotteryPanel
            lotteryInfo={state.lotteryInfo}
            uiFeedbackFeed={state.uiFeedbackFeed}
            onCommand={sendCommand}
          />
        )}

        {drawerPanel === "dice" && (
          <DicePanel
            diceResult={state.diceResult}
            lotteryInfo={state.lotteryInfo}
            uiFeedbackFeed={state.uiFeedbackFeed}
            gold={state.vitals.gold}
            assets={state.serverAssets}
            onCommand={sendCommand}
          />
        )}

        {drawerPanel === "jukebox" && (
          <JukeboxPanel
            jukebox={state.jukebox}
            gold={state.vitals.gold}
            selfName={state.character.name}
            uiFeedbackFeed={state.uiFeedbackFeed}
            assets={state.serverAssets}
            onCommand={sendCommand}
          />
        )}

        {drawerPanel === "combatlog" && (
          <CombatLogPanel
            messages={state.combatLogMessages}
            onClearLog={state.clearCombatLog}
          />
        )}

        {drawerPanel === "arcanum" && (
          <ArcanumPanel
            journal={state.arcanumJournal}
            status={state.arcanumStatus}
            playerName={state.character.name}
            connected={connected}
            onCommand={sendCommand}
          />
        )}

        {drawerPanel === "help" && (
          <HelpContent
            serverCommands={state.serverCommands}
            isStaff={state.character.isStaff}
          />
        )}

        {drawerPanel === "map" && (
          <div className="drawer-map-body">
            <div className="drawer-map-toolbar">
              <div className="drawer-map-tabs" role="tablist" aria-label="Map views">
                <button
                  type="button"
                  role="tab"
                  aria-selected={mapTab === "map"}
                  className={`drawer-map-tab ${mapTab === "map" ? "drawer-map-tab-active" : ""}`}
                  onClick={() => setMapTab("map")}
                >
                  Local Map
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={mapTab === "atlas"}
                  className={`drawer-map-tab ${mapTab === "atlas" ? "drawer-map-tab-active" : ""}`}
                  onClick={() => setMapTab("atlas")}
                >
                  Atlas
                </button>
              </div>
              {state.zoneCinematic && state.zoneCinematic.zone === currentZone && (
                <button
                  type="button"
                  className="drawer-map-cinematic-btn"
                  title="Replay this zone's intro cinematic"
                  aria-label="Replay zone cinematic"
                  onClick={() => setVideoUrl(state.zoneCinematic?.url ?? null)}
                >
                  <span aria-hidden="true">{"▶"}</span> Zone cinematic
                </button>
              )}
            </div>
            {/* Keep the canvas mounted so the minimap renderer can keep drawing into it
                even while the Atlas tab is in front — switching back must not lose state. */}
            <div className="drawer-map-canvas-wrap" hidden={mapTab !== "map"}>
              <canvas
                ref={mapCanvasRef}
                className="mini-map mini-map-popout"
                width={1280}
                height={760}
                role="img"
                onPointerDown={onMapPointerDown}
                onPointerMove={onMapPointerMove}
                onPointerUp={onMapPointerUp}
                onPointerLeave={onMapPointerUp}
                onWheel={onMapWheel}
                aria-label={questMarkerCount > 0
                  ? `Visited room map — ${questMarkerCount} quest objective${questMarkerCount !== 1 ? "s" : ""} marked`
                  : "Visited room map"}
              />
              <div className="map-zoom-controls" aria-hidden="false">
                <button type="button" className="map-zoom-btn" onClick={mapZoomIn} title="Zoom in" aria-label="Zoom in">+</button>
                <button type="button" className="map-zoom-btn" onClick={mapZoomOut} title="Zoom out" aria-label="Zoom out">−</button>
                <button type="button" className="map-zoom-btn" onClick={mapRecenter} title="Re-center on you" aria-label="Re-center on you">⌖</button>
              </div>
            </div>
            {mapTab === "atlas" && (
              <Atlas areas={state.worldAreas} currentZone={currentZone} />
            )}
          </div>
        )}

        {drawerPanel === "room" && (
          <article className="room-popout-copy">
            {state.room.image && (
              <img src={state.room.image} alt={state.room.title} className="room-popout-image" />
            )}
            {state.room.video && (
              <video
                src={state.room.video}
                controls
                className="room-popout-video"
                style={{ width: "100%", maxHeight: 300, borderRadius: 8, marginTop: 8 }}
              />
            )}
            <p className="room-popout-text">{state.room.description || "No room description available yet."}</p>
            {state.room.peek && state.room.peek.length > 0 && (
              <p className="room-popout-peek"><PeekSentence peek={state.room.peek} /></p>
            )}
            <div className="room-popout-exits">
              {sortedExits.length === 0 ? (
                <span>No exits listed.</span>
              ) : (
                <>
                  <span className="room-popout-exits-label">Exits:</span>
                  <span className="room-popout-exits-buttons">
                    {sortedExits.map(([direction]) => (
                      <button
                        key={direction}
                        type="button"
                        className="room-exit-btn"
                        title="Click to move, Shift+Click to peek"
                        aria-label={`Move ${direction}`}
                        onClick={(e) => sendCommand(e.shiftKey ? `look ${direction}` : direction)}
                      >
                        {direction}
                      </button>
                    ))}
                  </span>
                </>
              )}
            </div>
            {state.room.canDepart && (
              <div className="room-popout-depart">
                <button
                  type="button"
                  className="room-depart-btn"
                  title="Return to where you fell"
                  aria-label="Depart from the sanctum"
                  onClick={() => sendCommand("depart")}
                >
                  Depart through the spirit gate
                </button>
              </div>
            )}
            {(state.room.station || state.craftingNodes.length > 0) && (
              <div className="room-resources-section">
                {state.room.station && (
                  <p className="room-resource-line">
                    <span className="room-resource-icon" aria-hidden="true">{"\u2692"}</span>
                    Crafting station: <strong>{state.room.station.split("_").map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(" ")}</strong>
                  </p>
                )}
                {state.craftingNodes.length > 0 && (
                  <div className="room-resource-nodes">
                    <span className="room-resource-icon" aria-hidden="true">{"\uD83C\uDF3F"}</span>
                    <span>Resources: </span>
                    {state.craftingNodes.map((node) => (
                      <button
                        key={node.id}
                        type="button"
                        className="room-resource-btn"
                        title={`Gather ${node.name} (${node.skill} ${node.skillRequired}+)`}
                        aria-label={`Gather ${node.name}`}
                        onClick={() => sendCommand(`gather ${node.name}`)}
                      >
                        {node.name}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            )}
            {state.roomFeatures.length > 0 && (
              <div className="room-features-section">
                <div className="room-features-heading">
                  <h4 className="room-features-title">Interactive Features</h4>
                  <button
                    type="button"
                    className="room-feature-btn room-feature-panel-link"
                    onClick={() => {
                      openPanel("features");
                    }}
                  >
                    Open feature panel
                  </button>
                </div>
                <ul className="room-features-list" role="list">
                  {state.roomFeatures.map((f) => (
                    <li key={f.id} className="room-feature-item">
                      <span className="room-feature-name">{f.name}</span>
                      {f.state && <span className="room-feature-state">({f.state})</span>}
                      <span className="room-feature-actions">
                        {(f.type === "door" || f.type === "container") && f.state === "closed" && (
                          <button className="room-feature-btn" aria-label={`Open ${f.name}`} onClick={() => sendCommand(`open ${f.keyword}`)}>Open</button>
                        )}
                        {(f.type === "door" || f.type === "container") && f.state === "open" && (
                          <button className="room-feature-btn" aria-label={`${f.type === "container" ? "Search" : "Close"} ${f.name}`} onClick={() => sendCommand(f.type === "container" ? `search ${f.keyword}` : `close ${f.keyword}`)}>
                            {f.type === "container" ? "Search" : "Close"}
                          </button>
                        )}
                        {f.type === "container" && f.state === "open" && (
                          <button className="room-feature-btn" aria-label={`Close ${f.name}`} onClick={() => sendCommand(`close ${f.keyword}`)}>Close</button>
                        )}
                        {(f.type === "door" || f.type === "container") && f.state === "closed" && f.locked === false && (
                          <button className="room-feature-btn" aria-label={`Lock ${f.name}`} onClick={() => sendCommand(`lock ${f.keyword}`)}>Lock</button>
                        )}
                        {(f.type === "door" || f.type === "container") && f.state === "locked" && (
                          <button className="room-feature-btn" aria-label={`Unlock ${f.name}`} onClick={() => sendCommand(`unlock ${f.keyword}`)}>Unlock</button>
                        )}
                        {f.type === "lever" && (
                          <button className="room-feature-btn" aria-label={`Pull ${f.name}`} onClick={() => sendCommand(`pull ${f.keyword}`)}>Pull</button>
                        )}
                        {f.type === "sign" && (
                          <button className="room-feature-btn" aria-label={`Read ${f.name}`} onClick={() => sendCommand(`read ${f.keyword}`)}>Read</button>
                        )}
                      </span>
                      {f.type === "sign" && f.text && (
                        <p className="room-sign-text">{f.text}</p>
                      )}
                    </li>
                  ))}
                </ul>
                {state.containerContents && (
                  <div className="container-contents-section">
                    <h4 className="room-features-title">In the {state.containerContents.name}</h4>
                    {state.containerContents.items.length === 0 ? (
                      <p className="empty-note">Empty.</p>
                    ) : (
                      <ul className="container-contents-list" role="list">
                        {state.containerContents.items.map((item) => (
                          <li key={item.keyword} className="container-contents-item">
                            <span>{item.name}</span>
                            <button className="room-feature-btn" onClick={() => sendCommand(`get ${item.keyword} from ${state.containerContents!.keyword}`)}>Take</button>
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                )}
              </div>
            )}
          </article>
        )}
        </Suspense>
      </Drawer>

      {/* Trade is its own modal-like overlay */}
      {state.tradeState?.active && (
        <TradePanel
          trade={state.tradeState}
          inventory={state.inventory}
          playerGold={state.vitals.gold}
          onCommand={sendCommand}
        />
      )}

      {/* Login flow modals */}
      {state.savedCharacters.length >= 1 && !state.reconnecting && (
        <CharacterPicker
          characters={state.savedCharacters}
          backgroundImage={state.serverAssets["login_picker_bg"] ?? null}
          backgroundImagePortrait={state.serverAssets["login_picker_bg_portrait"] ?? null}
          onSelect={(name) => {
            try {
              const saved = JSON.parse(localStorage.getItem("ambonmud_auth_tokens") ?? "{}") as Record<string, string>;
              const token = saved[name];
              if (token) {
                pendingAuthCharRef.current = name;
                state.setSavedCharacters([]);
                state.setReconnecting(true);
                sendGmcp("Session.Authenticate", { token, name });
                return;
              }
            } catch { /* ignore */ }
            state.setSavedCharacters([]);
          }}
          onRemoveCharacter={(name) => {
            try {
              const saved = JSON.parse(localStorage.getItem("ambonmud_auth_tokens") ?? "{}") as Record<string, string>;
              delete saved[name];
              localStorage.setItem("ambonmud_auth_tokens", JSON.stringify(saved));
              const remaining = Object.keys(saved);
              state.setSavedCharacters(remaining.length === 0 ? [] : remaining);
            } catch {
              state.setSavedCharacters([]);
            }
          }}
          onNewCharacter={() => state.setSavedCharacters([])}
        />
      )}

      {state.loginPrompt && !state.reconnecting && state.savedCharacters.length === 0 && (
        <LoginModal
          loginPrompt={state.loginPrompt}
          loginError={state.loginError}
          serverAssets={state.serverAssets}
          onSubmit={(value) => sendLine(value)}
        />
      )}

      {state.reconnecting && (
        <div className="reconnect-banner" role="status" aria-live="polite" aria-busy="true">
          <span className="reconnect-spinner" aria-hidden="true" />
          Reconnecting...
        </div>
      )}

      {/* Command palette — opened by Ctrl/Cmd+K */}
      {showCommandPalette && (
        <CommandPalette
          commands={state.serverCommands}
          isStaff={state.character.isStaff}
          onExecute={(cmd) => sendCommand(cmd)}
          onPrefill={(text) => prefillInput(text)}
          onClose={() => setShowCommandPalette(false)}
        />
      )}

      {/* Cinematic video modal — triggered by canvas openVideo callback */}
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
          onAnimationEnd={(e) => {
            if (e.animationName === "videoFadeOut") { setVideoUrl(null); setVideoClosing(false); }
          }}
        >
          <div className="video-modal" onClick={(e) => e.stopPropagation()}>
            <button
              ref={videoCloseRef}
              className="video-modal-close"
              aria-label="Close video"
              onClick={() => {
                setVideoClosing(true);
                setTimeout(() => { setVideoUrl(null); setVideoClosing(false); }, 600);
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

      {/* Expanded mob detail card — full description + larger portrait */}
      {mobDetail && (
        <div
          className="entity-detail-overlay"
          role="dialog"
          aria-modal="true"
          aria-label={`${mobDetail.name} details`}
          onClick={() => setMobDetail(null)}
        >
          <div className="entity-detail-card" onClick={(e) => e.stopPropagation()}>
            <button
              ref={mobDetailCloseRef}
              className="entity-detail-close"
              aria-label="Close"
              onClick={() => setMobDetail(null)}
            >
              {"✕"}
            </button>
            {mobDetail.image && (
              <button
                type="button"
                className="entity-detail-portrait-btn"
                aria-label="Zoom portrait"
                onClick={() => setImagePreviewUrl(mobDetail.image)}
              >
                <img
                  className="entity-detail-portrait"
                  src={mobDetail.image}
                  alt={mobDetail.name}
                />
              </button>
            )}
            <h2 className="entity-detail-name">{mobDetail.name}</h2>
            <p className="entity-detail-description">{mobDetail.description}</p>
          </div>
        </div>
      )}

      {/* Full-size image preview — opened by clicking an entity portrait */}
      {imagePreviewUrl && (
        <div
          className="image-preview-overlay"
          role="dialog"
          aria-modal="true"
          aria-label="Image preview"
          onClick={() => setImagePreviewUrl(null)}
        >
          <button
            ref={imagePreviewCloseRef}
            className="image-preview-close"
            aria-label="Close image"
            onClick={() => setImagePreviewUrl(null)}
          >
            {"✕"}
          </button>
          <img
            className="image-preview-img"
            src={imagePreviewUrl}
            alt="Enlarged portrait"
            onClick={(e) => e.stopPropagation()}
          />
        </div>
      )}

      {/* Staff admin panel (the STAFF pill lives on the canvas, in GameShell). */}
      {state.character.isStaff && showAdminPanel && (
        <Suspense fallback={null}>
        <AdminPanel
          onCommand={(command) => {
            sendCommand(command);
            if (command === "invis") setStaffInvisible((value) => !value);
          }}
          onClose={() => setShowAdminPanel(false)}
          backgroundImage={state.serverAssets["admin_bg"] ?? null}
          worldInfo={state.staffWorldInfo}
          mobTemplates={state.staffMobTemplates}
          whoPlayers={state.whoPlayers}
          roomMobs={state.mobs}
          currentPlayerName={state.character.name}
          possessing={state.possessing}
          invisible={staffInvisible}
          feedbackFeed={state.uiFeedbackFeed}
          serverAssets={state.serverAssets}
        />
        </Suspense>
      )}

      {/* Toast */}
      {state.toast && (
        <div className="game-toast" role="alert">{state.toast}</div>
      )}

      {/* Look target card */}
      {state.lookTarget && (
        <div
          className="look-target-backdrop"
          role="presentation"
          onClick={() => state.setLookTarget(null)}
        >
          <div
            className="look-target-card"
            role="dialog"
            aria-modal="true"
            aria-label={`Inspect ${state.lookTarget.name}`}
            onClick={(e) => e.stopPropagation()}
          >
            <button
              type="button"
              className="look-target-close"
              aria-label="Close"
              onClick={() => state.setLookTarget(null)}
            >
              &#x2715;
            </button>
            <div className="look-target-header">
              <span className="look-target-name">{state.lookTarget.name}</span>
              {state.lookTarget.level != null && (
                <span className="look-target-meta">
                  Lv {state.lookTarget.level}
                  {state.lookTarget.race && ` ${state.lookTarget.race}`}
                  {state.lookTarget.playerClass && ` ${state.lookTarget.playerClass}`}
                </span>
              )}
              {state.lookTarget.type === "item" && state.lookTarget.slot && (
                <span className="look-target-slot">{formatItemSlot(state.lookTarget.slot)}</span>
              )}
            </div>
            {state.lookTarget.image && (
              <img src={state.lookTarget.image} alt={`Illustration of ${state.lookTarget.name}`} className="look-target-image" />
            )}
            {state.lookTarget.description && (
              <p className="look-target-desc">{state.lookTarget.description}</p>
            )}
            {state.lookTarget.type === "item" && renderItemStats(state.lookTarget)}
          </div>
        </div>
      )}

      {/* Monster manual — bestiary page for a clicked creature (consolidates the
          old look + consider + attack popouts). */}
      {monster && (
        <Suspense fallback={null}>
        <MonsterManualPanel
          key={monster.id ?? monster.name}
          monster={monster}
          consider={state.considerResult}
          bg={state.serverAssets["monster_manual_bg"]}
          serverAssets={state.serverAssets}
          connected={connected}
          akathavaePledged={state.arcanumStatus?.pledged ?? false}
          questsAvailable={state.questsAvailable}
          dialogue={state.dialogue}
          onClose={() => { setMonster(null); state.setConsiderResult(null); }}
          onCommand={sendCommand}
          onZoomImage={(url) => setImagePreviewUrl(url)}
          onQuest={(mobName) => sendCommand(`qoffers ${mobName}`)}
          onAcceptQuest={acceptQuest}
          onTurnInQuest={(questId) => sendCommand(`quest turnin #${questId}`)}
          onDialogueChoice={(index) => sendCommand(`${index}`)}
          onDialogueDismiss={() => { if (state.dialogue) sendCommand("bye"); state.setDialogue(null); }}
          onDialogueEnd={() => state.setDialogue(null)}
          onShop={() => { sendCommand("list"); openPanel("shop"); }}
          onVideo={(url) => setVideoUrl(url)}
        />
        </Suspense>
      )}

      {/* Player examine — Who-board "Examine" opens the player in the manual style. */}
      {examinePlayer && (
        <Suspense fallback={null}>
        <PlayerExaminePanel
          key={examinePlayer.name}
          player={examinePlayer}
          selfName={state.character.name}
          bg={state.serverAssets["monster_manual_bg"]}
          onClose={() => setExaminePlayer(null)}
          onTellPlayer={(name) => {
            state.setActiveChatChannel("tell");
            state.setTellTarget(name);
            openPanel("chatboard");
          }}
        />
        </Suspense>
      )}

      {/* Player field-manual card for a player clicked in the room (full context menu). */}
      {roomPlayer && (
        <Suspense fallback={null}>
        <PlayerExaminePanel
          key={roomPlayer.name}
          player={roomPlayer}
          selfName={state.character.name}
          context="room"
          isStaff={state.character.isStaff}
          inventory={state.inventory}
          serverAssets={state.serverAssets}
          bg={state.serverAssets["monster_manual_bg"]}
          onClose={() => setRoomPlayer(null)}
          onCommand={sendCommand}
          onTellPlayer={() => { /* room context uses the inline composer */ }}
        />
        </Suspense>
      )}

      {/* Item card — parchment "field manual" page for a clicked room item. */}
      {item && (
        <Suspense fallback={null}>
        <ItemManualPanel
          key={item.id ?? item.name}
          item={item}
          bg={state.serverAssets["item_manual_bg"] ?? state.serverAssets["monster_manual_bg"]}
          serverAssets={state.serverAssets}
          onClose={() => setItem(null)}
          onCommand={sendCommand}
          onZoomImage={(url) => setImagePreviewUrl(url)}
          onVideo={(url) => setVideoUrl(url)}
        />
        </Suspense>
      )}

      {/* Inn — key-on-a-hook recall modal (click away to dismiss). */}
      {showInn && (
        <Suspense fallback={null}>
        <InnPanel
          roomTitle={state.room.title}
          recall={state.recallState}
          serverAssets={state.serverAssets}
          onSetRecall={() => { sendCommand("rest"); setShowInn(false); }}
          onClose={() => setShowInn(false)}
        />
        </Suspense>
      )}

      {/* Consider — verbal threat assessment for a mob (typed `consider`). Hidden
          while the monster manual is open (it shows the assessment inline). */}
      {state.considerResult && !monster && (
        <div
          className="consider-backdrop"
          role="presentation"
          onClick={() => state.setConsiderResult(null)}
        >
          <div
            className={`consider-card ${CONSIDER_TIER_CLASS[state.considerResult.rating]}`}
            role="dialog"
            aria-modal="true"
            aria-label={`Threat assessment for ${state.considerResult.mobName}`}
            onClick={(e) => e.stopPropagation()}
          >
            <button
              type="button"
              className="consider-close"
              aria-label="Close"
              onClick={() => state.setConsiderResult(null)}
            >
              &#x2715;
            </button>
            <div className="consider-header">
              <span className="consider-tier-badge">{state.considerResult.ratingLabel}</span>
              <span className="consider-name">{state.considerResult.mobName}</span>
              <span className="consider-meta">
                Lv {state.considerResult.mobLevel} {state.considerResult.mobCategory}
              </span>
            </div>
            <p className="consider-flavor">{state.considerResult.ratingFlavor}</p>
            <div className="consider-winrate" aria-label="Estimated win chance">
              <div className="consider-winrate-label">
                <span>Estimated win chance</span>
                <span className="consider-winrate-pct">{state.considerResult.winChancePct}%</span>
              </div>
              <div className="consider-winrate-bar" role="progressbar"
                aria-valuenow={state.considerResult.winChancePct}
                aria-valuemin={0} aria-valuemax={100}>
                <div
                  className="consider-winrate-fill"
                  style={{ width: `${state.considerResult.winChancePct}%` }}
                />
              </div>
            </div>
            <dl className="consider-stats">
              <div className="consider-stat">
                <dt>Your hit</dt>
                <dd>~{state.considerResult.playerAvgDamage} dmg</dd>
              </div>
              <div className="consider-stat">
                <dt>Their hit</dt>
                <dd>~{state.considerResult.mobAvgDamage} dmg</dd>
              </div>
              <div className="consider-stat">
                <dt>Hits to kill {state.considerResult.mobName.split(" ").slice(-1)[0]}</dt>
                <dd>{state.considerResult.hitsToKillMob}</dd>
              </div>
              <div className="consider-stat">
                <dt>Hits to kill you</dt>
                <dd>{state.considerResult.hitsToKillPlayer}</dd>
              </div>
              {state.considerResult.dodgeChancePct > 0 && (
                <div className="consider-stat">
                  <dt>Your dodge</dt>
                  <dd>{state.considerResult.dodgeChancePct}%</dd>
                </div>
              )}
              <div className="consider-stat">
                <dt>Level diff</dt>
                <dd>
                  {state.considerResult.mobLevel - state.considerResult.playerLevel > 0 ? "+" : ""}
                  {state.considerResult.mobLevel - state.considerResult.playerLevel}
                </dd>
              </div>
            </dl>
          </div>
        </div>
      )}

      {/* Level-up celebration — headline moment on XP/kill → level threshold */}
      <LevelUpBanner
        notification={state.levelUpNotification}
        onDismiss={() => state.setLevelUpNotification(null)}
      />

      {/* Quest turn-in summary toast — shows quest name + rewards. */}
      <QuestCompleteToast
        notifications={state.questNotifications}
        onDismiss={(id) =>
          state.setQuestNotifications((prev) => prev.filter((n) => n.id !== id))
        }
      />

      {/* Combat victory toast — surfaces kills (target, XP, gold, autolooted items). */}
      <CombatVictoryToast
        notifications={state.combatVictoryNotifications}
        onDismiss={(id) =>
          state.setCombatVictoryNotifications((prev) => prev.filter((n) => n.id !== id))
        }
      />

      {/* Flee toast — surfaces voluntary and wimpy-forced disengages. */}
      <FleeToast
        notifications={state.fleeNotifications}
        onDismiss={(id) =>
          state.setFleeNotifications((prev) => prev.filter((n) => n.id !== id))
        }
      />

      {/* Server broadcast */}
      {state.broadcast && (
        <div className="broadcast-overlay" role="alertdialog" aria-modal="true" aria-label="Server announcement">
          <div className="broadcast-card">
            <div className="broadcast-header">
              <span className="broadcast-icon" aria-hidden="true">{"\uD83D\uDCE2"}</span>
              <span className="broadcast-title">Server Announcement</span>
            </div>
            <p className="broadcast-message">{state.broadcast.message}</p>
            <div className="broadcast-footer">
              <span className="broadcast-sender">— {state.broadcast.sender}</span>
              <button type="button" className="broadcast-dismiss" onClick={() => state.setBroadcast(null)} autoFocus>
                Dismiss
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Full-screen terminal — the session-long server log, summoned from the
          dock input (desktop) or the services stack (small screens). */}
      <TerminalOverlay
        open={terminalOpen}
        opaque={terminalOpaque}
        hostRef={terminalOverlayRef}
        hasSelection={terminalHasSelection}
        parchmentBg={terminalParchmentBg}
        quillUrl={state.serverAssets["desk_quill"] ?? null}
        inputValue={inputValue}
        onInputChange={(value) => {
          setInputValue(value);
          resetComposerCompletion();
          if (value.length > 0) setTerminalOpaque(true);
        }}
        onInputKeyDown={handleInputKeyDown}
        onCommand={sendCommand}
        onClose={closeTerminal}
      />

      {/* Hidden terminal container — keeps xterm alive (and accumulating
          server text) off-screen while the GUI is in charge. */}
      <div ref={terminalHiddenRef} className="terminal-hidden" aria-hidden="true" />

      <p className="sr-only" aria-live="polite">{liveMessage}</p>

      {/* Spoken narration (rooms, low HP, tells) for screen-reader mode. */}
      <GameNarrator
        enabled={state.character.screenReaderEnabled}
        room={state.room}
        exits={sortedExits}
        vitals={state.vitals}
        tells={state.chatByChannel.tell}
        chatBoardOpen={state.activePopout === "chatboard"}
      />
    </>
  );
}

export default App;
