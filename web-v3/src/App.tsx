import { useEffect, useMemo, useRef, useState } from "react";
import type { KeyboardEvent as ReactKeyboardEvent } from "react";
import { GameShell } from "./components/GameShell";
import { Drawer } from "./components/Drawer";
import { PuzzlePopout } from "./components/PuzzlePopout";
import { ShopPopout } from "./components/ShopPopout";
import { TrainerPanel } from "./components/TrainerPanel";
import { TradePanel } from "./components/TradePanel";
import { WorldFeaturesPopout } from "./components/WorldFeaturesPopout";
import { ChatPanel } from "./components/panels/ChatPanel";
import { CharacterPanel } from "./components/panels/CharacterPanel";
import { SpellbookPanel } from "./components/SpellbookPanel";
import { QuestPanel } from "./components/panels/QuestPanel";
import { QuestOfferPanel } from "./components/panels/QuestOfferPanel";
import { InventoryPanel } from "./components/panels/InventoryPanel";
import { EquipmentPanel } from "./components/panels/EquipmentPanel";
import { MailPanel } from "./components/panels/MailPanel";
import { MonsterManualPanel } from "./components/panels/MonsterManualPanel";
import { ItemManualPanel } from "./components/panels/ItemManualPanel";
import { CraftingPanel } from "./components/panels/CraftingPanel";
import { HousingPanel } from "./components/panels/HousingPanel";
import { BankPanel } from "./components/panels/BankPanel";
import { StylistPanel } from "./components/panels/StylistPanel";
import { InnPanel } from "./components/panels/InnPanel";
import { AuctionPanel } from "./components/panels/AuctionPanel";
import { DungeonPanel } from "./components/panels/DungeonPanel";
import { LotteryPanel } from "./components/panels/LotteryPanel";
import { AdminPanel } from "./components/panels/AdminPanel";
import { CombatLogPanel } from "./components/panels/CombatLogPanel";
import { HelpContent } from "./components/HelpContent";
import { CommandInput } from "./components/CommandInput";
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

function App() {
  const resumeTokenRef = useRef<string | null>(null);
  const pendingAuthCharRef = useRef<string | null>(null);
  const sendGmcpRef = useRef<(pkg: string, payload: unknown) => void>(() => {});
  const intentionalDisconnectRef = useRef(false);
  const connectedRef = useRef(false);

  // Cinematic video state — driven by canvas openVideo callback
  const [videoUrl, setVideoUrl] = useState<string | null>(null);
  const [videoClosing, setVideoClosing] = useState(false);

  // Expanded mob detail card — opened from canvas Look button
  const [mobDetail, setMobDetail] = useState<{ name: string; description: string; image: string | null } | null>(null);

  // Monster-manual / bestiary panel (clicked mob or pet)
  const [monster, setMonster] = useState<MonsterEntry | null>(null);
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
  const [mapTab, setMapTab] = useState<"map" | "atlas">("map");

  // Lifted command-input state — VitalsBar renders it controlled, palette/canvas can prefill
  const [inputValue, setInputValue] = useState("");

  const prefillInput = (text: string) => {
    setInputValue(text);
  };

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

  // Wire up the WebSocket
  const { connected, liveMessage, connect, disconnect, reconnect, sendLine, sendGmcp } = useMudSocket({
    onOpen: () => {},
    onTextMessage: () => {
      // Terminal removed — server text other than GMCP is silently ignored.
      // The login modal handles the auth flow via Login.* GMCP packages.
    },
    onGmcpMessage: state.handleGmcp,
    onClose: () => {
      if (intentionalDisconnectRef.current) {
        intentionalDisconnectRef.current = false;
        return;
      }
      if (resumeTokenRef.current) {
        state.setReconnecting(true);
        window.setTimeout(() => reconnect(), 500);
      } else {
        state.resetHud();
        audio.stopAll();
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

  const sendCommand = (raw: string) => {
    const command = raw.trim();
    if (command.length === 0) return;
    if (!sendLine(command)) return;
    pushHistory(command);
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
    canvasCallbacks.openHousing = () => openPanel("housing");
    canvasCallbacks.openInn = () => setShowInn(true);
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
    canvasCallbacks.openImagePreview = (url: string) => setImagePreviewUrl(url);
    // In-canvas entity menu (Pixi) can't paint above DOM overlays, so flag the
    // root while it's open and let CSS fade the room sign out of the way.
    canvasCallbacks.onEntityMenu = (open: boolean) =>
      document.documentElement.classList.toggle("entity-menu-open", open);
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
      canvasCallbacks.openHousing = null;
      canvasCallbacks.openInn = null;
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
      canvasCallbacks.openItemManual = null;
      canvasCallbacks.openImagePreview = null;
      canvasCallbacks.onEntityMenu = null;
      document.documentElement.classList.remove("entity-menu-open");
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

  // Audio for room music/ambient
  useEffect(() => { audio.playMusic(state.room.music ?? null); }, [state.room.music]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { audio.playAmbient(state.room.ambient ?? null); }, [state.room.ambient]); // eslint-disable-line react-hooks/exhaustive-deps

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

  const drawerTitle = useMemo(() => {
    switch (drawerPanel) {
      case "character": return "Character";
      case "inventory": return "Inventory";
      case "equipment": return "Equipment";
      case "spellbook": return "Spellbook";
      case "quests": return "Quests";
      case "questOffers": return "Quest Offers";
      case "chat": return "Social";
      case "shop": return state.shop?.name ?? "Shop";
      case "puzzle": return "Puzzle";
      case "features": return "World Features";
      case "trainer": return "Trainer";
      case "mail": return "Mail";
      case "crafting": return "Crafting";
      case "housing": return "Housing";
      case "leaderboard": return "Leaderboard";
      case "bank": return "Bank";
      case "stylist": return "Stylist";
      case "inn": return state.room.title !== "-" ? state.room.title : "Inn";
      case "auction": return "Auction House";
      case "dungeon": return "Dungeon";
      case "lottery": return "Lottery";
      case "combatlog": return "Battle Journal";
      case "help": return "Command Reference";
      case "terminal": return "Terminal";
      case "room": return state.room.title !== "-" ? state.room.title : "Room Details";
      case "map": return "World Map";
      default: return "";
    }
  }, [drawerPanel, state.shop?.name, state.room.title]);

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
        onCommand={sendCommand}
        onOpenPanel={(panel) => openPanel(panel)}
        audio={audio}
      />

      <Drawer
        open={state.activePopout !== null}
        title={drawerTitle}
        onClose={closeDrawer}
        variant={
          drawerPanel === "inventory"
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
                        : drawerPanel === "terminal"
                          ? "desk"
                          : drawerPanel === "mail"
                            ? "mail"
                            : "default"
        }
        skinBg={
          drawerPanel === "inventory"
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
                        : drawerPanel === "terminal"
                          ? state.serverAssets["terminal_bg"]
                          : drawerPanel === "mail"
                            ? state.serverAssets["mail_bg"]
                            : undefined
        }
      >
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
            activeTitle={state.whoPlayers.find((p) => p.name === state.character.name)?.title ?? null}
            spriteList={state.spriteList}
            currencies={state.currencies}
            factions={state.factions}
            petState={state.petState}
            prestigeInfo={state.prestigeInfo}
            leaderboard={state.leaderboard}
            onDismissQuestNotification={(id) => state.setQuestNotifications((prev) => prev.filter((n) => n.id !== id))}
            onAbandonQuest={(name) => sendCommand(`quest abandon ${name}`)}
            onOpenInventory={() => openPanel("inventory")}
            onOpenEquipment={() => openPanel("equipment")}
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

        {drawerPanel === "chat" && (
          <ChatPanel
            connected={connected}
            canChat={connected && hasCharacterProfile}
            playerName={state.character.name}
            activeChannel={state.activeChatChannel}
            chatByChannel={state.chatByChannel}
            emotePresets={state.emotePresets}
            whoPlayers={state.whoPlayers}
            groupInfo={state.groupInfo}
            pendingGroupInvite={state.pendingGroupInvite}
            guildInfo={state.guildInfo}
            pendingGuildInvite={state.pendingGuildInvite}
            guildMembers={state.guildMembers}
            guildHall={state.guildHall}
            friends={state.friends}
            friendNotifications={state.friendNotifications}
            onChannelChange={state.setActiveChatChannel}
            onRequestWho={() => sendCommand("who")}
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
          <PuzzlePopout puzzle={state.puzzle} onCommand={sendCommand} />
        )}

        {drawerPanel === "features" && (
          <WorldFeaturesPopout
            roomTitle={state.room.title !== "-" ? state.room.title : "Current Room"}
            roomFeatures={state.roomFeatures}
            containerContents={state.containerContents}
            preferredType={featureFocus}
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
          />
        )}

        {drawerPanel === "mail" && (
          <MailPanel
            connected={connected}
            hasCharacterProfile={hasCharacterProfile}
            inbox={state.mailInbox}
            openMessage={state.mailMessage}
            onReadMessage={(index) => sendCommand(`mail read ${index}`)}
            onDeleteMessage={(index) => sendCommand(`mail delete ${index}`)}
            onCompose={(recipient, body) => {
              sendCommand(`mail send ${recipient}`);
              for (const line of body.split("\n")) sendCommand(line);
              sendCommand(".");
            }}
            onClearMessage={() => state.setMailMessage(null)}
            onCommand={sendCommand}
          />
        )}

        {drawerPanel === "crafting" && (
          <CraftingPanel
            connected={connected}
            hasCharacterProfile={hasCharacterProfile}
            skills={state.craftingSkills}
            recipes={state.craftingRecipes}
            nodes={state.craftingNodes}
            gatherCooldownUntilMs={state.gatherCooldownUntilMs}
            uiFeedbackFeed={state.uiFeedbackFeed}
            onGather={(keyword) => sendCommand(`gather ${keyword}`)}
            onCraft={(recipeKeyword) => sendCommand(`craft ${recipeKeyword}`)}
            onRequestRecipes={() => sendCommand("recipes")}
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
            room={state.room}
            uiFeedbackFeed={state.uiFeedbackFeed}
            onSendCommand={sendCommand}
          />
        )}

        {drawerPanel === "bank" && (
          <BankPanel bankState={state.bankState} onCommand={sendCommand} />
        )}

        {drawerPanel === "stylist" && (
          <StylistPanel stylistState={state.stylistState} onCommand={sendCommand} />
        )}

        {drawerPanel === "auction" && (
          <AuctionPanel
            listings={state.auctionListings}
            inventory={state.inventory}
            playerName={state.character.name}
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

        {drawerPanel === "combatlog" && (
          <CombatLogPanel
            messages={state.combatLogMessages}
            onClearLog={state.clearCombatLog}
          />
        )}

        {drawerPanel === "help" && (
          <HelpContent
            serverCommands={state.serverCommands}
            isStaff={state.character.isStaff}
          />
        )}

        {drawerPanel === "terminal" && (
          <div className="terminal-overlay-body terminal-desk">
            <div className="terminal-desk-slip">
              <span className="terminal-desk-quill" aria-hidden="true">
                {state.serverAssets["desk_quill"]
                  ? <img src={state.serverAssets["desk_quill"]} alt="" className="terminal-desk-quill-img" />
                  : "✒"}
              </span>
              <p className="terminal-overlay-note">
                Pen a command and dispatch it to the world.
              </p>
              <CommandInput
                inputValue={inputValue}
                onInputChange={(value) => {
                  setInputValue(value);
                  resetComposerCompletion();
                }}
                onInputKeyDown={handleInputKeyDown}
                onCommand={sendCommand}
              />
            </div>
          </div>
        )}

        {drawerPanel === "map" && (
          <div className="drawer-map-body">
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
          onSubmit={(value) => sendLine(value)}
        />
      )}

      {state.reconnecting && (
        <div className="reconnect-banner" role="status" aria-live="polite">
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
          onKeyDown={(e) => {
            if (e.key === "Escape") setMobDetail(null);
          }}
        >
          <div className="entity-detail-card" onClick={(e) => e.stopPropagation()}>
            <button
              className="entity-detail-close"
              aria-label="Close"
              autoFocus
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
          onKeyDown={(e) => {
            if (e.key === "Escape") setImagePreviewUrl(null);
          }}
        >
          <button
            className="image-preview-close"
            aria-label="Close image"
            autoFocus
            onClick={() => setImagePreviewUrl(null)}
          >
            {"✕"}
          </button>
          <img
            className="image-preview-img"
            src={imagePreviewUrl}
            alt=""
            onClick={(e) => e.stopPropagation()}
          />
        </div>
      )}

      {/* Staff-only floating controls + admin panel */}
      {state.character.isStaff && (
        <>
          <div className="staff-fab">
            <button
              type="button"
              className="staff-fab-btn"
              onClick={() => setShowAdminPanel(true)}
              title="Open staff admin panel"
              aria-label="Open staff admin panel"
            >
              Staff
            </button>
            <button
              type="button"
              className={`staff-fab-btn staff-fab-invis${staffInvisible ? " staff-fab-invis-active" : ""}`}
              onClick={() => { sendCommand("invis"); setStaffInvisible((v) => !v); }}
              title={staffInvisible ? "You are invisible — click to reappear" : "Become invisible"}
              aria-label="Toggle staff invisibility"
              aria-pressed={staffInvisible}
            >
              {staffInvisible ? "\uD83D\uDC41\u200D\uD83D\uDDE8" : "\uD83D\uDC41"}
            </button>
          </div>

          {showAdminPanel && (
            <AdminPanel
              onCommand={(command) => {
                sendCommand(command);
                if (command === "invis") setStaffInvisible((value) => !value);
              }}
              onClose={() => setShowAdminPanel(false)}
              worldInfo={state.staffWorldInfo}
              mobTemplates={state.staffMobTemplates}
              whoPlayers={state.whoPlayers}
              roomMobs={state.mobs}
              currentPlayerName={state.character.name}
              possessing={state.possessing}
              invisible={staffInvisible}
              feedbackFeed={state.uiFeedbackFeed}
            />
          )}
        </>
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
            {state.lookTarget.image && <img src={state.lookTarget.image} alt="" className="look-target-image" />}
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
        <MonsterManualPanel
          key={monster.id ?? monster.name}
          monster={monster}
          consider={state.considerResult}
          bg={state.serverAssets["monster_manual_bg"]}
          serverAssets={state.serverAssets}
          connected={connected}
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
      )}

      {/* Item card — parchment "field manual" page for a clicked room item. */}
      {item && (
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
      )}

      {/* Inn — key-on-a-hook recall modal (click away to dismiss). */}
      {showInn && (
        <InnPanel
          roomTitle={state.room.title}
          recall={state.recallState}
          serverAssets={state.serverAssets}
          onSetRecall={() => { sendCommand("rest"); setShowInn(false); }}
          onClose={() => setShowInn(false)}
        />
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

      <p className="sr-only" aria-live="polite">{liveMessage}</p>
    </>
  );
}

export default App;
