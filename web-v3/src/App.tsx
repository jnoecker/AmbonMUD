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
import { InventoryPanel } from "./components/panels/InventoryPanel";
import { EquipmentPanel } from "./components/panels/EquipmentPanel";
import { MailPanel } from "./components/panels/MailPanel";
import { CraftingPanel } from "./components/panels/CraftingPanel";
import { HousingPanel } from "./components/panels/HousingPanel";
import { LeaderboardPanel } from "./components/panels/LeaderboardPanel";
import { BankPanel } from "./components/panels/BankPanel";
import { StylistPanel } from "./components/panels/StylistPanel";
import { AuctionPanel } from "./components/panels/AuctionPanel";
import { DungeonPanel } from "./components/panels/DungeonPanel";
import { LotteryPanel } from "./components/panels/LotteryPanel";
import { AdminPanel } from "./components/panels/AdminPanel";
import { WorldAtmosphereHud } from "./components/WorldAtmosphereHud";
import { HelpContent } from "./components/HelpContent";
import { LoginModal } from "./canvas/LoginModal";
import { CharacterPicker } from "./components/CharacterPicker";
import { CommandPalette } from "./components/CommandPalette";
import { useGameState } from "./hooks/useGameState";
import { useMudSocket } from "./hooks/useMudSocket";
import { useAudioEngine } from "./hooks/useAudioEngine";
import { useCommandHistory } from "./hooks/useCommandHistory";
import { useMiniMap } from "./hooks/useMiniMap";
import { useQuickbar } from "./hooks/useQuickbar";
import { canvasCallbacks, gameStateRef, pendingCastRef } from "./canvas/GameStateBridge";
import type { ChatChannel, FeaturePopoutFocus, PopoutPanel } from "./types";
import { sortExits, titleCaseWords } from "./utils";
import "./styles.css";

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

  // Ctrl+K command palette
  const [showCommandPalette, setShowCommandPalette] = useState(false);
  const [featureFocus, setFeatureFocus] = useState<FeaturePopoutFocus>(null);

  // Staff admin panel + invisibility toggle
  const [showAdminPanel, setShowAdminPanel] = useState(false);
  const [staffInvisible, setStaffInvisible] = useState(false);

  // Lifted command-input state — VitalsBar renders it controlled, palette/canvas can prefill
  const [inputValue, setInputValue] = useState("");

  const prefillInput = (text: string) => {
    setInputValue(text);
  };

  // Minimap canvas + drawing helpers (owns its own ref, kept out of useGameState)
  const { mapCanvasRef, drawMap, updateMap, loadZoneMap, resetMap, startPulse, stopPulse } = useMiniMap();

  const state = useGameState(
    { resumeTokenRef, pendingAuthCharRef, sendGmcpRef },
    { updateMap, loadZoneMap, resetMap },
  );
  const audio = useAudioEngine();
  const quickbar = useQuickbar(state.skills);

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
    };
  });

  // Wire canvas callbacks
  useEffect(() => {
    canvasCallbacks.sendCommand = (cmd: string) => sendCommand(cmd);
    canvasCallbacks.openShop = () => openPanel("shop");
    canvasCallbacks.openAuction = () => openPanel("auction");
    canvasCallbacks.openPuzzle = () => openPanel("puzzle");
    canvasCallbacks.openFeatures = (preferredType?: FeaturePopoutFocus) => openPanel("features", preferredType ?? null);
    canvasCallbacks.openBank = () => openPanel("bank");
    canvasCallbacks.openStylist = () => openPanel("stylist");
    canvasCallbacks.openTrainer = () => openPanel("trainer");
    canvasCallbacks.openDungeon = () => openPanel("dungeon");
    canvasCallbacks.openLottery = () => openPanel("lottery");
    canvasCallbacks.openHousing = () => openPanel("housing");
    canvasCallbacks.openMap = () => openPanel("map");
    canvasCallbacks.openRoom = () => openPanel("room");
    canvasCallbacks.openQuests = () => openPanel("quests");
    canvasCallbacks.dismissDialogue = () => { state.setDialogue(null); state.setQuestsAvailable([]); };
    canvasCallbacks.openVideo = (url: string) => setVideoUrl(url);
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
      canvasCallbacks.openDungeon = null;
      canvasCallbacks.openLottery = null;
      canvasCallbacks.openHousing = null;
      canvasCallbacks.openMap = null;
      canvasCallbacks.openRoom = null;
      canvasCallbacks.openQuests = null;
      canvasCallbacks.dismissDialogue = null;
      canvasCallbacks.openVideo = null;
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

  const handleCastSkill = (skillId: string, cooldownMs: number) => {
    const skill = state.skills.find((s) => s.id === skillId);
    if (!skill) return;
    const needsTarget = skill.targetType === "ENEMY" || skill.targetType === "ALLY";
    if (needsTarget && gameStateRef.current.combatTarget?.targetName) {
      completeCast(skillId, cooldownMs, gameStateRef.current.combatTarget.targetName);
      return;
    }
    if (needsTarget) {
      pendingCastRef.current = { skillId, skillName: skill.name, cooldownMs, targetType: skill.targetType };
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
    if (state.activePopout !== "map") return;
    const handle = window.requestAnimationFrame(() => drawMap());
    startPulse();
    return () => {
      window.cancelAnimationFrame(handle);
      stopPulse();
    };
  }, [state.activePopout, drawMap, startPulse, stopPulse]);

  // Auto-dismiss look target
  useEffect(() => {
    if (!state.lookTarget) return;
    const t = setTimeout(() => state.setLookTarget(null), 6000);
    return () => clearTimeout(t);
  }, [state.lookTarget]); // eslint-disable-line react-hooks/exhaustive-deps

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
  }, [quickbar.slots, handleCastSkill]); // eslint-disable-line react-hooks/exhaustive-deps

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

  const drawerTitle = useMemo(() => {
    switch (drawerPanel) {
      case "character": return "Character";
      case "inventory": return "Inventory";
      case "equipment": return "Equipment";
      case "spellbook": return "Spellbook";
      case "quests": return "Quests";
      case "chat": return "Social";
      case "shop": return state.shop?.name ?? "Shop";
      case "puzzle": return "Puzzle";
      case "features": return "World Features";
      case "trainer": return state.trainer?.name ?? "Trainer";
      case "mail": return "Mail";
      case "crafting": return "Crafting";
      case "housing": return "Housing";
      case "leaderboard": return "Leaderboard";
      case "bank": return "Bank";
      case "stylist": return "Stylist";
      case "auction": return "Auction House";
      case "dungeon": return "Dungeon";
      case "lottery": return "Lottery";
      case "help": return "Command Reference";
      case "room": return state.room.title !== "-" ? state.room.title : "Room Details";
      case "map": return "World Map";
      default: return "";
    }
  }, [drawerPanel, state.shop?.name, state.trainer?.name, state.room.title]);

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
      <GameShell
        connected={connected}
        hasCharacterProfile={hasCharacterProfile}
        vitals={state.vitals}
        combatLogMessages={state.combatLogMessages}
        combatTarget={state.combatTarget}
        quickbarSlots={quickbar.slots}
        onQuickbarSwap={quickbar.swap}
        onQuickbarAssign={quickbar.assign}
        onQuickbarClear={quickbar.clear}
        activePopout={state.activePopout}
        onCommand={sendCommand}
        onOpenPanel={(panel) => openPanel(panel)}
        onCastSkill={handleCastSkill}
        onReconnect={() => { intentionalDisconnectRef.current = true; reconnect(); }}
        dungeonActive={state.dungeonInfo?.active ?? false}
        audio={audio}
        inputValue={inputValue}
        onInputChange={(value) => {
          setInputValue(value);
          resetComposerCompletion();
        }}
        onInputKeyDown={handleInputKeyDown}
      />

      <Drawer open={state.activePopout !== null} title={drawerTitle} onClose={closeDrawer}>
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
            onWearItem={(name) => sendCommand(`wear ${name}`)}
            onDropItem={(name) => sendCommand(`drop ${name}`)}
            onGiveItem={(keyword, player) => sendCommand(`give ${keyword} ${player}`)}
            onCommand={sendCommand}
          />
        )}

        {drawerPanel === "equipment" && (
          <EquipmentPanel
            connected={connected}
            hasCharacterProfile={hasCharacterProfile}
            character={state.character}
            equipment={state.equipment}
            slotDefs={state.equipmentSlotDefs}
            canManageItems={connected && hasCharacterProfile}
            onRemoveItem={(slot) => sendCommand(`remove ${slot}`)}
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
            gold={state.vitals.gold}
            onBuyItem={(keyword) => sendCommand(`buy ${keyword}`)}
            onSellItem={(keyword) => sendCommand(`sell ${keyword}`)}
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
            onDismissQuestNotification={(id) => state.setQuestNotifications((prev) => prev.filter((n) => n.id !== id))}
            onAbandonQuest={(name) => sendCommand(`quest abandon ${name}`)}
            onAcceptQuest={(name) => sendCommand(`accept ${name}`)}
            onCommand={sendCommand}
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

        {drawerPanel === "leaderboard" && (
          <LeaderboardPanel leaderboard={state.leaderboard} onCommand={sendCommand} />
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

        {drawerPanel === "help" && (
          <HelpContent
            serverCommands={state.serverCommands}
            isStaff={state.character.isStaff}
          />
        )}

        {drawerPanel === "map" && (
          <div className="drawer-map-body">
            <canvas
              ref={mapCanvasRef}
              className="mini-map mini-map-popout"
              width={900}
              height={560}
              role="img"
              aria-label={questMarkerCount > 0
                ? `Visited room map — ${questMarkerCount} quest objective${questMarkerCount !== 1 ? "s" : ""} marked`
                : "Visited room map"}
            />
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

      {/* World atmosphere HUD — time, weather, events on the canvas */}
      {hasCharacterProfile && (
        <WorldAtmosphereHud
          worldTime={state.worldTime}
          worldWeather={state.worldWeather}
          worldEvents={state.worldEvents}
        />
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
        <div className="look-target-card" role="dialog" aria-label="Inspect target" onClick={() => state.setLookTarget(null)}>
          <div className="look-target-header">
            <span className="look-target-name">{state.lookTarget.name}</span>
            {state.lookTarget.level != null && (
              <span className="look-target-meta">
                Lv {state.lookTarget.level}
                {state.lookTarget.race && ` ${state.lookTarget.race}`}
                {state.lookTarget.playerClass && ` ${state.lookTarget.playerClass}`}
              </span>
            )}
          </div>
          <p className="look-target-desc">{state.lookTarget.description}</p>
          {state.lookTarget.image && <img src={state.lookTarget.image} alt="" className="look-target-image" />}
        </div>
      )}

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
