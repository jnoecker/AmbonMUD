import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent, KeyboardEvent } from "react";
import { FitAddon } from "@xterm/addon-fit";
import { Terminal } from "@xterm/xterm";
import { MobileTabBar } from "./components/MobileTabBar";
import { PopoutLayer } from "./components/PopoutLayer";
import { ChatPanel } from "./components/panels/ChatPanel";
import { CharacterPanel } from "./components/panels/CharacterPanel";
import { PlayPanel } from "./components/panels/PlayPanel";
import { WorldPanel } from "./components/panels/WorldPanel";
import { applyGmcpPackage } from "./gmcp/applyGmcpPackage";
import {
  EMPTY_CHAR,
  EMPTY_ROOM,
  EMPTY_VITALS,
  MAX_VISIBLE_EFFECTS,
  MAX_VISIBLE_WORLD_ITEMS,
  MAX_VISIBLE_WORLD_MOBS,
  MAX_VISIBLE_WORLD_PLAYERS,
  SLOT_ORDER,
} from "./constants";
import { useCommandHistory } from "./hooks/useCommandHistory";
import { useMiniMap } from "./hooks/useMiniMap";
import { useMudSocket } from "./hooks/useMudSocket";
import type {
  ChatChannel,
  ChatMessage,
  CharacterInfo,
  ItemSummary,
  MobileTab,
  PopoutPanel,
  RoomMob,
  RoomItem,
  RoomPlayer,
  RoomState,
  StatusEffect,
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
  const terminalHostRef = useRef<HTMLDivElement | null>(null);
  const composerInputRef = useRef<HTMLInputElement | null>(null);
  const terminalRef = useRef<Terminal | null>(null);
  const fitAddonRef = useRef<FitAddon | null>(null);

  const [activeTab, setActiveTab] = useState<MobileTab>("play");
  const [activeChatChannel, setActiveChatChannel] = useState<ChatChannel>("say");
  const [activePopout, setActivePopout] = useState<PopoutPanel>(null);
  const [composerValue, setComposerValue] = useState("");

  const [vitals, setVitals] = useState<Vitals>(EMPTY_VITALS);
  const [character, setCharacter] = useState<CharacterInfo>(EMPTY_CHAR);
  const [room, setRoom] = useState<RoomState>(EMPTY_ROOM);
  const [players, setPlayers] = useState<RoomPlayer[]>([]);
  const [mobs, setMobs] = useState<RoomMob[]>([]);
  const [roomItems, setRoomItems] = useState<RoomItem[]>([]);
  const [effects, setEffects] = useState<StatusEffect[]>([]);
  const [inventory, setInventory] = useState<ItemSummary[]>([]);
  const [equipment, setEquipment] = useState<Record<string, ItemSummary>>({});
  const [chatByChannel, setChatByChannel] = useState<Record<ChatChannel, ChatMessage[]>>(createEmptyChatByChannel);
  const [whoPlayers, setWhoPlayers] = useState<string[]>([]);

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

  const focusComposer = useCallback(() => {
    window.requestAnimationFrame(() => composerInputRef.current?.focus());
  }, []);

  const fitTerminal = useCallback(() => {
    const term = terminalRef.current;
    const fitAddon = fitAddonRef.current;
    const host = terminalHostRef.current;
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
    setCharacter(EMPTY_CHAR);
    setRoom(EMPTY_ROOM);
    setPlayers([]);
    setMobs([]);
    setRoomItems([]);
    setEffects([]);
    setInventory([]);
    setEquipment({});
    setChatByChannel(createEmptyChatByChannel());
    setWhoPlayers([]);
    setActiveChatChannel("say");
    resetMap();
  }, [resetMap]);

  const handleGmcp = useCallback(
    (pkg: string, data: unknown) => {
      applyGmcpPackage(
        pkg,
        data,
        {
          setVitals,
          setCharacter,
          setRoom,
          setRoomItems,
          setInventory,
          setEquipment,
          setPlayers,
          setMobs,
          setEffects,
          setChatByChannel,
          updateMap,
        },
      );
    },
    [updateMap],
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
    if (!terminalHostRef.current) return;

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
    term.open(terminalHostRef.current);

    terminalRef.current = term;
    fitAddonRef.current = fitAddon;
    fitTerminal();

    const resizeObserver = new ResizeObserver(() => {
      fitTerminal();
      drawMap();
    });
    resizeObserver.observe(terminalHostRef.current);
    const firstFrameFit = window.requestAnimationFrame(() => fitTerminal());
    const delayedFit = window.setTimeout(() => fitTerminal(), 80);

    return () => {
      resizeObserver.disconnect();
      window.cancelAnimationFrame(firstFrameFit);
      window.clearTimeout(delayedFit);
      term.dispose();
      fitAddonRef.current = null;
      terminalRef.current = null;
    };
  }, [drawMap, fitTerminal]);

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

  useEffect(() => {
    if (activeTab !== "play") return;
    const frameFit = window.requestAnimationFrame(() => fitTerminal());
    const delayedFit = window.setTimeout(() => fitTerminal(), 90);
    return () => {
      window.cancelAnimationFrame(frameFit);
      window.clearTimeout(delayedFit);
    };
  }, [activeTab, connected, character.name, room.id, room.title, fitTerminal]);

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
  const visiblePlayers = players.slice(0, MAX_VISIBLE_WORLD_PLAYERS);
  const hiddenPlayersCount = Math.max(0, players.length - visiblePlayers.length);
  const visibleMobs = mobs.slice(0, MAX_VISIBLE_WORLD_MOBS);
  const hiddenMobsCount = Math.max(0, mobs.length - visibleMobs.length);
  const visibleRoomItems = roomItems.slice(0, MAX_VISIBLE_WORLD_ITEMS);
  const hiddenRoomItemsCount = Math.max(0, roomItems.length - visibleRoomItems.length);
  const visibleEffects = effects.slice(0, MAX_VISIBLE_EFFECTS);
  const hiddenEffectsCount = Math.max(0, effects.length - visibleEffects.length);
  const displayRace = character.race ? titleCaseWords(character.race) : "";
  const displayClassName = character.className ? titleCaseWords(character.className) : "";
  const hasCharacterProfile = character.name !== "-";
  const hasRoomDetails = room.id !== null || room.title !== "-";
  const preLogin = connected && !hasCharacterProfile && !hasRoomDetails;
  const availableExitSet = useMemo(() => new Set(exits.map(([direction]) => direction.toLowerCase())), [exits]);
  const canOpenMap = hasRoomDetails;
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

  return (
    <main className="app-shell">
      <div className="ambient-orb ambient-orb-a" aria-hidden="true" />
      <div className="ambient-orb ambient-orb-b" aria-hidden="true" />

      <header className="top-banner">
        <div>
          <h1 className="top-banner-title">AmbonMUD</h1>
        </div>

        <div className="connection-cluster">
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

      <div className="dashboard" data-active-tab={activeTab}>
        <PlayPanel
          preLogin={preLogin}
          connected={connected}
          hasRoomDetails={hasRoomDetails}
          exits={exits}
          terminalHostRef={terminalHostRef}
          commandInputRef={composerInputRef}
          composerValue={composerValue}
          commandPlaceholder={commandPlaceholder}
          onTerminalMouseDown={(event) => {
            event.preventDefault();
            focusComposer();
          }}
          onComposerChange={(value) => {
            setComposerValue(value);
            resetComposerCompletion();
          }}
          onComposerKeyDown={onComposerKeyDown}
          onSubmitComposer={submitComposer}
          onMove={(direction) => {
            sendCommand(direction, true);
            focusComposer();
          }}
          onFlee={() => {
            sendCommand("flee", true);
            focusComposer();
          }}
        />

        <WorldPanel
          connected={connected}
          hasRoomDetails={hasRoomDetails}
          canOpenMap={canOpenMap}
          room={room}
          exits={exits}
          availableExitSet={availableExitSet}
          players={players}
          mobs={mobs}
          visiblePlayers={visiblePlayers}
          hiddenPlayersCount={hiddenPlayersCount}
          visibleMobs={visibleMobs}
          hiddenMobsCount={hiddenMobsCount}
          roomItems={roomItems}
          visibleRoomItems={visibleRoomItems}
          hiddenRoomItemsCount={hiddenRoomItemsCount}
          onOpenMap={() => setActivePopout("map")}
          onOpenRoom={() => setActivePopout("room")}
          onMove={(direction) => {
            sendCommand(direction, true);
            focusComposer();
          }}
          onAttackMob={(mobName) => {
            sendCommand(`kill ${mobName}`, true);
            focusComposer();
          }}
          onPickUpItem={(itemName) => {
            sendCommand(`get ${itemName}`, true);
            focusComposer();
          }}
        />

        <ChatPanel
          connected={connected}
          canChat={connected && hasCharacterProfile}
          playerName={character.name}
          activeChannel={activeChatChannel}
          messages={chatByChannel[activeChatChannel]}
          whoPlayers={whoPlayers}
          onChannelChange={setActiveChatChannel}
          onRequestWho={() => {
            sendCommand("who", true);
            focusComposer();
          }}
          onSendMessage={sendChatMessage}
        />

        <CharacterPanel
          connected={connected}
          hasCharacterProfile={hasCharacterProfile}
          canOpenEquipment={canOpenEquipment}
          character={character}
          displayRace={displayRace}
          displayClassName={displayClassName}
          vitals={vitals}
          xpValue={xpValue}
          xpMax={xpMax}
          xpText={xpText}
          effects={effects}
          visibleEffects={visibleEffects}
          hiddenEffectsCount={hiddenEffectsCount}
          onOpenEquipment={() => setActivePopout("equipment")}
          onOpenWearing={() => setActivePopout("wearing")}
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
        onClose={() => setActivePopout(null)}
      />

      <MobileTabBar activeTab={activeTab} onTabChange={setActiveTab} />

      <p className="sr-only" aria-live="polite">{liveMessage}</p>
    </main>
  );
}

export default App;
