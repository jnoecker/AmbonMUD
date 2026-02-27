import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent, KeyboardEvent } from "react";
import { FitAddon } from "@xterm/addon-fit";
import { Terminal } from "@xterm/xterm";
import { MobileTabBar } from "./components/MobileTabBar";
import { PopoutLayer } from "./components/PopoutLayer";
import { CharacterPanel } from "./components/panels/CharacterPanel";
import { PlayPanel } from "./components/panels/PlayPanel";
import { WorldPanel } from "./components/panels/WorldPanel";
import { applyGmcpPackage } from "./gmcp/applyGmcpPackage";
import {
  EMPTY_CHAR,
  EMPTY_ROOM,
  EMPTY_VITALS,
  MAX_VISIBLE_EFFECTS,
  MAX_VISIBLE_WORLD_MOBS,
  MAX_VISIBLE_WORLD_PLAYERS,
  SLOT_ORDER,
} from "./constants";
import { useCommandHistory } from "./hooks/useCommandHistory";
import { useMiniMap } from "./hooks/useMiniMap";
import { useMudSocket } from "./hooks/useMudSocket";
import type {
  CharacterInfo,
  ItemSummary,
  MobileTab,
  PopoutPanel,
  RoomMob,
  RoomPlayer,
  RoomState,
  StatusEffect,
  Vitals,
} from "./types";
import { sortExits, titleCaseWords } from "./utils";
import "@xterm/xterm/css/xterm.css";
import "./styles.css";

function App() {
  const terminalHostRef = useRef<HTMLDivElement | null>(null);
  const terminalRef = useRef<Terminal | null>(null);
  const fitAddonRef = useRef<FitAddon | null>(null);

  const connectedRef = useRef(false);
  const inputBufferRef = useRef("");

  const [activeTab, setActiveTab] = useState<MobileTab>("play");
  const [activePopout, setActivePopout] = useState<PopoutPanel>(null);
  const [composerValue, setComposerValue] = useState("");

  const [vitals, setVitals] = useState<Vitals>(EMPTY_VITALS);
  const [character, setCharacter] = useState<CharacterInfo>(EMPTY_CHAR);
  const [room, setRoom] = useState<RoomState>(EMPTY_ROOM);
  const [players, setPlayers] = useState<RoomPlayer[]>([]);
  const [mobs, setMobs] = useState<RoomMob[]>([]);
  const [effects, setEffects] = useState<StatusEffect[]>([]);
  const [inventory, setInventory] = useState<ItemSummary[]>([]);
  const [equipment, setEquipment] = useState<Record<string, ItemSummary>>({});

  const { mapCanvasRef, drawMap, updateMap, resetMap } = useMiniMap();
  const {
    pushHistory,
    applyTerminalHistoryUp,
    applyTerminalHistoryDown,
    applyTerminalCompletion,
    applyComposerHistoryUp,
    applyComposerHistoryDown,
    applyComposerCompletion,
    resetTerminalTraversal,
    resetComposerTraversal,
    resetTerminalCompletion,
    resetComposerCompletion,
  } = useCommandHistory();

  const writeSystem = useCallback((message: string) => {
    terminalRef.current?.write(`\r\n\x1b[2m${message}\x1b[0m\r\n`);
  }, []);

  const fitTerminal = useCallback(() => {
    const term = terminalRef.current;
    const fitAddon = fitAddonRef.current;
    const host = terminalHostRef.current;
    if (!term || !fitAddon || !host) return;
    if (host.clientWidth < 220 || host.clientHeight < 120) return;

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
    setEffects([]);
    setInventory([]);
    setEquipment({});
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
          setInventory,
          setEquipment,
          setPlayers,
          setMobs,
          setEffects,
          updateMap,
        },
      );
    },
    [updateMap],
  );

  const { connected, liveMessage, connect, disconnect, reconnect, sendLine } = useMudSocket({
    onOpen: () => {
      terminalRef.current?.focus();
    },
    onTextMessage: (text) => {
      terminalRef.current?.write(text);
    },
    onGmcpMessage: handleGmcp,
    onClose: () => {
      inputBufferRef.current = "";
      setComposerValue("");
      resetTerminalTraversal();
      resetComposerTraversal();
      resetHud();
      writeSystem("Connection closed.");
    },
    onError: () => {
      writeSystem("Connection error.");
    },
  });

  useEffect(() => {
    connectedRef.current = connected;
  }, [connected]);

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

  const clearTerminalInput = useCallback(() => {
    if (!inputBufferRef.current) return;
    terminalRef.current?.write("\b \b".repeat(inputBufferRef.current.length));
  }, []);

  const replaceTerminalInput = useCallback(
    (next: string) => {
      clearTerminalInput();
      inputBufferRef.current = next;
      terminalRef.current?.write(next);
    },
    [clearTerminalInput],
  );

  const handleTerminalData = useCallback(
    (data: string) => {
      if (!connectedRef.current) return;

      if (data === "\x1b[A") {
        applyTerminalHistoryUp(inputBufferRef.current, replaceTerminalInput);
        return;
      }

      if (data === "\x1b[B") {
        applyTerminalHistoryDown(replaceTerminalInput);
        return;
      }

      for (const ch of data) {
        if (ch === "\r") {
          sendCommand(inputBufferRef.current, false);
          terminalRef.current?.write("\r\n");
          inputBufferRef.current = "";
          setComposerValue("");
          resetTerminalTraversal();
          continue;
        }

        if (ch === "\u007f") {
          if (inputBufferRef.current.length > 0) {
            inputBufferRef.current = inputBufferRef.current.slice(0, -1);
            terminalRef.current?.write("\b \b");
          }
          resetTerminalCompletion();
          continue;
        }

        if (ch === "\t") {
          applyTerminalCompletion(inputBufferRef.current, replaceTerminalInput);
          continue;
        }

        const code = ch.charCodeAt(0);
        if (code >= 0x20 && code !== 0x7f) {
          inputBufferRef.current += ch;
          terminalRef.current?.write(ch);
          resetTerminalCompletion();
        }
      }
    },
    [
      applyTerminalCompletion,
      applyTerminalHistoryDown,
      applyTerminalHistoryUp,
      replaceTerminalInput,
      resetTerminalCompletion,
      resetTerminalTraversal,
      sendCommand,
    ],
  );

  useEffect(() => {
    if (!terminalHostRef.current) return;

    const term = new Terminal({
      cursorBlink: true,
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
    term.focus();

    const dataListener = term.onData(handleTerminalData);

    const resizeObserver = new ResizeObserver(() => {
      fitTerminal();
      drawMap();
    });
    resizeObserver.observe(terminalHostRef.current);

    return () => {
      dataListener.dispose();
      resizeObserver.disconnect();
      term.dispose();
      fitAddonRef.current = null;
      terminalRef.current = null;
    };
  }, [drawMap, fitTerminal, handleTerminalData]);

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
    const handle = window.requestAnimationFrame(() => fitTerminal());
    return () => window.cancelAnimationFrame(handle);
  }, [activeTab, fitTerminal]);

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
    const command = composerValue.trim();
    if (!command) return;
    sendCommand(command, true);
    setComposerValue("");
    resetComposerTraversal();
  };

  const onComposerKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "ArrowUp") {
      event.preventDefault();
      applyComposerHistoryUp(composerValue, setComposerValue);
      return;
    }

    if (event.key === "ArrowDown") {
      event.preventDefault();
      applyComposerHistoryDown(setComposerValue);
      return;
    }

    if (event.key === "Tab") {
      event.preventDefault();
      applyComposerCompletion(composerValue, setComposerValue);
      return;
    }

    resetComposerCompletion();
  };

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
          composerValue={composerValue}
          commandPlaceholder={commandPlaceholder}
          onComposerChange={(value) => {
            setComposerValue(value);
            resetComposerCompletion();
          }}
          onComposerKeyDown={onComposerKeyDown}
          onSubmitComposer={submitComposer}
          onMove={(direction) => {
            sendCommand(direction, true);
            terminalRef.current?.focus();
          }}
          onFlee={() => {
            sendCommand("flee", true);
            terminalRef.current?.focus();
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
          onOpenMap={() => setActivePopout("map")}
          onOpenRoom={() => setActivePopout("room")}
          onMove={(direction) => {
            sendCommand(direction, true);
            terminalRef.current?.focus();
          }}
          onAttackMob={(mobName) => {
            sendCommand(`kill ${mobName}`, true);
            terminalRef.current?.focus();
          }}
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
        onClose={() => setActivePopout(null)}
      />

      <MobileTabBar activeTab={activeTab} onTabChange={setActiveTab} />

      <p className="sr-only" aria-live="polite">{liveMessage}</p>
    </main>
  );
}

export default App;
