import { useEffect, useMemo, useRef } from "react";
import { GameShell } from "./components/GameShell";
import { Drawer } from "./components/Drawer";
import { ShopPopout } from "./components/ShopPopout";
import { TrainerPanel } from "./components/TrainerPanel";
import { TradePanel } from "./components/TradePanel";
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
import { AuctionPanel } from "./components/panels/AuctionPanel";
import { HelpContent } from "./components/HelpContent";
import { LoginModal } from "./canvas/LoginModal";
import { CharacterPicker } from "./components/CharacterPicker";
import { useGameState } from "./hooks/useGameState";
import { useMudSocket } from "./hooks/useMudSocket";
import { useAudioEngine } from "./hooks/useAudioEngine";
import { useCommandHistory } from "./hooks/useCommandHistory";
import { useQuickbar } from "./hooks/useQuickbar";
import { canvasCallbacks, gameStateRef, pendingCastRef } from "./canvas/GameStateBridge";
import type { ChatChannel } from "./types";
import { titleCaseWords } from "./utils";
import "./styles.css";

function App() {
  const resumeTokenRef = useRef<string | null>(null);
  const pendingAuthCharRef = useRef<string | null>(null);
  const failedAuthCharRef = useRef<string | null>(null);
  const sendGmcpRef = useRef<(pkg: string, payload: unknown) => void>(() => {});
  const intentionalDisconnectRef = useRef(false);
  const connectedRef = useRef(false);

  const state = useGameState({ resumeTokenRef, pendingAuthCharRef, failedAuthCharRef, sendGmcpRef });
  const audio = useAudioEngine();
  const quickbar = useQuickbar(state.skills);

  const { pushHistory } = useCommandHistory(state.serverCommands);

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
      if (document.visibilityState === "visible" && !connectedRef.current && resumeTokenRef.current) {
        state.setReconnecting(true);
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

  // Auto-resend character name after token-auth failure
  useEffect(() => {
    const charName = failedAuthCharRef.current;
    if (state.loginPrompt?.state === "name" && charName) {
      failedAuthCharRef.current = null;
      sendLine(charName);
    }
  }, [state.loginPrompt, sendLine]);  

  const sendCommand = (raw: string) => {
    const command = raw.trim();
    if (command.length === 0) return;
    if (!sendLine(command)) return;
    pushHistory(command);
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
      craftingNodes: state.craftingNodes,
      questTargetRoomIds: new Set(
        state.quests.flatMap((q) =>
          q.objectives.filter((o) => o.current < o.required).flatMap((o) => o.targetRoomIds ?? []),
        ),
      ),
      serverAssets: state.serverAssets,
    };
  });

  // Wire canvas callbacks
  useEffect(() => {
    canvasCallbacks.sendCommand = (cmd: string) => sendCommand(cmd);
    canvasCallbacks.openShop = () => state.setActivePopout("shop");
    canvasCallbacks.openBank = () => state.setActivePopout("bank");
    canvasCallbacks.openTrainer = () => state.setActivePopout("trainer");
    canvasCallbacks.openMap = () => state.setActivePopout("map");
    canvasCallbacks.openRoom = () => state.setActivePopout("room");
    canvasCallbacks.openQuests = () => state.setActivePopout("quests");
    canvasCallbacks.dismissDialogue = () => { state.setDialogue(null); state.setQuestsAvailable([]); };
    return () => {
      canvasCallbacks.sendCommand = null;
      canvasCallbacks.openShop = null;
      canvasCallbacks.openBank = null;
      canvasCallbacks.openTrainer = null;
      canvasCallbacks.openMap = null;
      canvasCallbacks.openRoom = null;
      canvasCallbacks.openQuests = null;
      canvasCallbacks.dismissDialogue = null;
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
  useEffect(() => {
    if (!state.toast) return;
    const t = setTimeout(() => { state.setToast(null); pendingCastRef.current = null; }, 4000);
    return () => clearTimeout(t);
  }, [state.toast]); // eslint-disable-line react-hooks/exhaustive-deps

  // Auto-dismiss look target
  useEffect(() => {
    if (!state.lookTarget) return;
    const t = setTimeout(() => state.setLookTarget(null), 6000);
    return () => clearTimeout(t);
  }, [state.lookTarget]); // eslint-disable-line react-hooks/exhaustive-deps

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

  const drawerTitle = useMemo(() => {
    switch (state.activePopout) {
      case "character": return "Character";
      case "inventory": return "Inventory";
      case "equipment": return "Equipment";
      case "spellbook": return "Spellbook";
      case "quests": return "Quests";
      case "chat": return "Social";
      case "shop": return state.shop?.name ?? "Shop";
      case "trainer": return state.trainer?.name ?? "Trainer";
      case "mail": return "Mail";
      case "crafting": return "Crafting";
      case "housing": return "Housing";
      case "leaderboard": return "Leaderboard";
      case "bank": return "Bank";
      case "auction": return "Auction House";
      case "help": return "Command Reference";
      case "room": return "Room Details";
      case "map": return "World Map";
      default: return "";
    }
  }, [state.activePopout, state.shop?.name, state.trainer?.name]);

  const closeDrawer = () => state.setActivePopout(null);

  return (
    <>
      <GameShell
        connected={connected}
        hasCharacterProfile={hasCharacterProfile}
        vitals={state.vitals}
        combatLogMessages={state.combatLogMessages}
        combatTarget={state.combatTarget}
        quickbarSlots={quickbar.slots}
        activePopout={state.activePopout}
        onCommand={sendCommand}
        onOpenPanel={(panel) => state.setActivePopout(panel)}
        onCastSkill={handleCastSkill}
      />

      <Drawer open={state.activePopout !== null} title={drawerTitle} onClose={closeDrawer}>
        {state.activePopout === "character" && (
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
            worldTime={state.worldTime}
            worldWeather={state.worldWeather}
            worldEvents={state.worldEvents}
            lotteryInfo={state.lotteryInfo}
            duelState={state.duelState}
            duelChallenge={state.duelChallenge}
            dungeonInfo={state.dungeonInfo}
            prestigeInfo={state.prestigeInfo}
            onDismissQuestNotification={(id) => state.setQuestNotifications((prev) => prev.filter((n) => n.id !== id))}
            onAbandonQuest={(name) => sendCommand(`quest abandon ${name}`)}
            onOpenInventory={() => state.setActivePopout("inventory")}
            onOpenEquipment={() => state.setActivePopout("equipment")}
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

        {state.activePopout === "inventory" && (
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

        {state.activePopout === "equipment" && (
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

        {state.activePopout === "chat" && (
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

        {state.activePopout === "shop" && state.shop && (
          <ShopPopout
            shop={state.shop}
            inventory={state.inventory}
            gold={state.vitals.gold}
            onBuyItem={(keyword) => sendCommand(`buy ${keyword}`)}
            onSellItem={(keyword) => sendCommand(`sell ${keyword}`)}
          />
        )}

        {state.activePopout === "trainer" && state.trainer && (
          <TrainerPanel
            trainer={state.trainer}
            playerLevel={state.vitals.level ?? 1}
            playerGold={state.vitals.gold}
            onCommand={sendCommand}
          />
        )}

        {state.activePopout === "spellbook" && (
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

        {state.activePopout === "mail" && (
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
          />
        )}

        {state.activePopout === "crafting" && (
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

        {state.activePopout === "quests" && (
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

        {state.activePopout === "housing" && (
          <HousingPanel
            connected={connected}
            hasCharacterProfile={hasCharacterProfile}
            housing={state.housing}
            room={state.room}
            onSendCommand={sendCommand}
          />
        )}

        {state.activePopout === "leaderboard" && (
          <LeaderboardPanel leaderboard={state.leaderboard} onCommand={sendCommand} />
        )}

        {state.activePopout === "bank" && (
          <BankPanel bankState={state.bankState} onCommand={sendCommand} />
        )}

        {state.activePopout === "auction" && (
          <AuctionPanel listings={state.auctionListings} onCommand={sendCommand} />
        )}

        {state.activePopout === "help" && (
          <HelpContent
            serverCommands={state.serverCommands}
            isStaff={state.character.isStaff}
          />
        )}
      </Drawer>

      {/* Trade is its own modal-like overlay */}
      {state.tradeState?.active && (
        <TradePanel trade={state.tradeState} onCommand={sendCommand} />
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
                sendGmcp("Session.Authenticate", { token });
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
