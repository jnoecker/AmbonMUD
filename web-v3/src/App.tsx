import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent, KeyboardEvent } from "react";
import { FitAddon } from "@xterm/addon-fit";
import { Terminal } from "@xterm/xterm";
import "@xterm/xterm/css/xterm.css";
import "./styles.css";

type MobileTab = "play" | "world" | "character";
type PopoutPanel = "map" | "equipment" | "wearing" | null;

interface Vitals {
  hp: number;
  maxHp: number;
  mana: number;
  maxMana: number;
  level: number | null;
  xp: number;
  xpIntoLevel: number;
  xpToNextLevel: number | null;
  gold: number;
}

interface CharacterInfo {
  name: string;
  race: string;
  className: string;
  level: number | null;
}

interface RoomState {
  id: string | null;
  title: string;
  description: string;
  exits: Record<string, string>;
}

interface ItemSummary {
  id: string;
  name: string;
}

interface RoomPlayer {
  name: string;
  level: number;
}

interface RoomMob {
  id: string;
  name: string;
  hp: number;
  maxHp: number;
}

interface StatusEffect {
  name: string;
  type: string;
  stacks: number;
  remainingMs: number;
}

interface MapRoom {
  x: number;
  y: number;
  exits: Record<string, string>;
}

interface TabCycle {
  matches: string[];
  index: number;
  originalPrefix: string;
  args: string;
}

const EMPTY_TAB: TabCycle = { matches: [], index: 0, originalPrefix: "", args: "" };
const HISTORY_KEY = "ambonmud_v3_history";
const MAX_HISTORY = 100;
const MAX_VISIBLE_WORLD_PLAYERS = 4;
const MAX_VISIBLE_WORLD_MOBS = 4;
const MAX_VISIBLE_EFFECTS = 4;
const EXIT_ORDER = ["north", "south", "east", "west", "up", "down"];
const COMPASS_DIRECTIONS = ["north", "east", "south", "west", "up", "down"] as const;
const SLOT_ORDER = ["head", "body", "hand"];
const TABS: Array<{ id: MobileTab; label: string }> = [
  { id: "play", label: "Play" },
  { id: "world", label: "World" },
  { id: "character", label: "Character" },
];

const COMMANDS = [
  "look", "north", "south", "east", "west", "up", "down", "say", "tell", "whisper", "shout",
  "gossip", "ooc", "emote", "pose", "who", "score", "inventory", "equipment", "exits", "get", "drop",
  "wear", "remove", "use", "give", "kill", "flee", "cast", "spells", "abilities", "effects", "help",
  "phase", "gold", "list", "buy", "sell", "quit", "clear", "colors",
];

const MAP_OFFSETS: Record<string, { dx: number; dy: number }> = {
  north: { dx: 0, dy: -1 },
  south: { dx: 0, dy: 1 },
  east: { dx: 1, dy: 0 },
  west: { dx: -1, dy: 0 },
  up: { dx: 0.5, dy: -0.5 },
  down: { dx: -0.5, dy: 0.5 },
};

const EMPTY_VITALS: Vitals = {
  hp: 0,
  maxHp: 0,
  mana: 0,
  maxMana: 0,
  level: null,
  xp: 0,
  xpIntoLevel: 0,
  xpToNextLevel: 0,
  gold: 0,
};

const EMPTY_CHAR: CharacterInfo = { name: "-", race: "", className: "", level: null };
const EMPTY_ROOM: RoomState = { id: null, title: "-", description: "", exits: {} };

function safeNumber(value: unknown, fallback = 0): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

function percent(current: number, max: number): number {
  if (max <= 0) return 0;
  return Math.max(0, Math.min(100, Math.round((current / max) * 100)));
}

function parseGmcp(text: string): { pkg: string; data: unknown } | null {
  if (!text.trimStart().startsWith("{")) return null;
  try {
    const parsed = JSON.parse(text) as { gmcp?: unknown; data?: unknown };
    if (typeof parsed.gmcp !== "string" || parsed.gmcp.length === 0) return null;
    return { pkg: parsed.gmcp, data: parsed.data ?? {} };
  } catch {
    return null;
  }
}

function readHistory(): string[] {
  try {
    const raw = window.localStorage.getItem(HISTORY_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed)
      ? parsed.filter((entry): entry is string => typeof entry === "string")
      : [];
  } catch {
    return [];
  }
}

function nextCompletion(value: string, cycle: TabCycle): { value: string; cycle: TabCycle } | null {
  if (value.trimStart().length === 0) return null;
  const parts = value.split(" ");
  const first = parts[0].toLowerCase();

  let next = cycle;
  let nextIndex = 0;

  const advance =
    cycle.matches.length > 0 &&
    cycle.originalPrefix.length > 0 &&
    first !== cycle.originalPrefix &&
    cycle.matches.includes(first);

  if (advance) {
    nextIndex = (cycle.index + 1) % cycle.matches.length;
  } else {
    const matches = COMMANDS.filter((cmd) => cmd.startsWith(first) && cmd !== first);
    if (matches.length === 0) return null;
    next = { matches, index: 0, originalPrefix: first, args: parts.slice(1).join(" ") };
  }

  const command = next.matches[nextIndex];
  const nextValue = next.args.length > 0 ? `${command} ${next.args}` : command;
  return { value: nextValue, cycle: { ...next, index: nextIndex } };
}

function sortExits(exits: Record<string, string>): Array<[string, string]> {
  return Object.entries(exits).sort(([left], [right]) => {
    const li = EXIT_ORDER.indexOf(left);
    const ri = EXIT_ORDER.indexOf(right);
    if (li === -1 && ri === -1) return left.localeCompare(right);
    if (li === -1) return 1;
    if (ri === -1) return -1;
    return li - ri;
  });
}

function Bar({
  label,
  value,
  max,
  text,
  tone,
}: {
  label: string;
  value: number;
  max: number;
  text: string;
  tone: "hp" | "mana" | "xp";
}) {
  const width = percent(value, max);
  return (
    <div className="meter-block">
      <div className="meter-label-row">
        <span>{label}</span>
        <span>{text}</span>
      </div>
      <div className="meter-track" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={width}>
        <span className={`meter-fill meter-fill-${tone}`} style={{ width: `${width}%` }} />
      </div>
    </div>
  );
}

function App() {
  const terminalHostRef = useRef<HTMLDivElement | null>(null);
  const mapCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const terminalRef = useRef<Terminal | null>(null);
  const fitAddonRef = useRef<FitAddon | null>(null);
  const socketRef = useRef<WebSocket | null>(null);

  const connectedRef = useRef(false);
  const inputBufferRef = useRef("");
  const historyRef = useRef<string[]>([]);
  const termHistoryIndexRef = useRef(-1);
  const termSavedInputRef = useRef("");
  const termTabCycleRef = useRef<TabCycle>(EMPTY_TAB);

  const composerHistoryIndexRef = useRef(-1);
  const composerSavedInputRef = useRef("");
  const composerTabCycleRef = useRef<TabCycle>(EMPTY_TAB);

  const visitedRef = useRef<Map<string, MapRoom>>(new Map());
  const currentRoomIdRef = useRef<string | null>(null);

  const [activeTab, setActiveTab] = useState<MobileTab>("play");
  const [activePopout, setActivePopout] = useState<PopoutPanel>(null);
  const [connected, setConnected] = useState(false);
  const [liveMessage, setLiveMessage] = useState("Disconnected.");
  const [composerValue, setComposerValue] = useState("");

  const [vitals, setVitals] = useState<Vitals>(EMPTY_VITALS);
  const [character, setCharacter] = useState<CharacterInfo>(EMPTY_CHAR);
  const [room, setRoom] = useState<RoomState>(EMPTY_ROOM);
  const [players, setPlayers] = useState<RoomPlayer[]>([]);
  const [mobs, setMobs] = useState<RoomMob[]>([]);
  const [effects, setEffects] = useState<StatusEffect[]>([]);
  const [inventory, setInventory] = useState<ItemSummary[]>([]);
  const [equipment, setEquipment] = useState<Record<string, ItemSummary>>({});

  useEffect(() => {
    connectedRef.current = connected;
  }, [connected]);

  useEffect(() => {
    historyRef.current = readHistory();
  }, []);

  const wsUrl = useMemo(() => {
    const scheme = window.location.protocol === "https:" ? "wss" : "ws";
    return `${scheme}://${window.location.host}/ws`;
  }, []);

  const persistHistory = useCallback(() => {
    try {
      window.localStorage.setItem(HISTORY_KEY, JSON.stringify(historyRef.current));
    } catch {
      // ignore
    }
  }, []);

  const pushHistory = useCallback(
    (command: string) => {
      const normalized = command.trim();
      if (normalized.length === 0) return;
      if (historyRef.current.at(-1) === normalized) return;
      historyRef.current.push(normalized);
      while (historyRef.current.length > MAX_HISTORY) historyRef.current.shift();
      persistHistory();
    },
    [persistHistory],
  );

  const writeSystem = useCallback((message: string) => {
    terminalRef.current?.write(`\r\n\x1b[2m${message}\x1b[0m\r\n`);
  }, []);

  const drawMap = useCallback(() => {
    const canvas = mapCanvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const rect = canvas.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    const width = Math.max(1, Math.floor(rect.width));
    const height = Math.max(1, Math.floor(rect.height));

    if (canvas.width !== width * dpr || canvas.height !== height * dpr) {
      canvas.width = width * dpr;
      canvas.height = height * dpr;
    }

    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, width, height);

    const gradient = ctx.createLinearGradient(0, 0, width, height);
    gradient.addColorStop(0, "#fbf8fd");
    gradient.addColorStop(1, "#eff4fb");
    ctx.fillStyle = gradient;
    ctx.fillRect(0, 0, width, height);

    const currentId = currentRoomIdRef.current;
    if (!currentId) return;
    const current = visitedRef.current.get(currentId);
    if (!current) return;

    const cell = 28;
    const radius = 6;
    const centerX = width / 2;
    const centerY = height / 2;

    ctx.strokeStyle = "rgba(107, 107, 123, 0.46)";
    ctx.lineWidth = 1.4;
    for (const node of visitedRef.current.values()) {
      const sx = centerX + (node.x - current.x) * cell;
      const sy = centerY + (node.y - current.y) * cell;
      for (const targetId of Object.values(node.exits)) {
        const target = visitedRef.current.get(targetId);
        if (!target) continue;
        const tx = centerX + (target.x - current.x) * cell;
        const ty = centerY + (target.y - current.y) * cell;
        ctx.beginPath();
        ctx.moveTo(sx, sy);
        ctx.lineTo(tx, ty);
        ctx.stroke();
      }
    }

    for (const [id, node] of visitedRef.current.entries()) {
      const x = centerX + (node.x - current.x) * cell;
      const y = centerY + (node.y - current.y) * cell;
      if (x < -radius || x > width + radius || y < -radius || y > height + radius) continue;

      const currentNode = id === currentId;
      ctx.fillStyle = currentNode ? "#e8c5d8" : "#b8d8e8";
      ctx.beginPath();
      ctx.arc(x, y, currentNode ? radius + 1.8 : radius, 0, Math.PI * 2);
      ctx.fill();

      ctx.strokeStyle = "rgba(107, 107, 123, 0.66)";
      ctx.lineWidth = 1;
      ctx.stroke();

      if (currentNode) {
        ctx.strokeStyle = "rgba(232, 216, 168, 0.95)";
        ctx.lineWidth = 1.8;
        ctx.beginPath();
        ctx.arc(x, y, radius + 5.8, 0, Math.PI * 2);
        ctx.stroke();
      }
    }
  }, []);

  const updateMap = useCallback(
    (roomId: string, exits: Record<string, string>) => {
      currentRoomIdRef.current = roomId;
      const rooms = visitedRef.current;

      if (!rooms.has(roomId)) {
        let placed = false;
        for (const [dir, neighborId] of Object.entries(exits)) {
          const neighbor = rooms.get(neighborId);
          const offset = MAP_OFFSETS[dir];
          if (!neighbor || !offset) continue;
          rooms.set(roomId, { x: neighbor.x - offset.dx, y: neighbor.y - offset.dy, exits });
          placed = true;
          break;
        }

        if (!placed) {
          if (rooms.size === 0) {
            rooms.set(roomId, { x: 0, y: 0, exits });
          } else {
            const previous = Array.from(rooms.values()).at(-1);
            rooms.set(roomId, { x: (previous?.x ?? 0) + 1, y: previous?.y ?? 0, exits });
          }
        }
      } else {
        const node = rooms.get(roomId);
        if (node) node.exits = exits;
      }

      for (const [dir, targetId] of Object.entries(exits)) {
        if (rooms.has(targetId)) continue;
        const source = rooms.get(roomId);
        const offset = MAP_OFFSETS[dir];
        if (!source || !offset) continue;
        rooms.set(targetId, { x: source.x + offset.dx, y: source.y + offset.dy, exits: {} });
      }

      drawMap();
    },
    [drawMap],
  );

  const resetHud = useCallback(() => {
    setVitals(EMPTY_VITALS);
    setCharacter(EMPTY_CHAR);
    setRoom(EMPTY_ROOM);
    setPlayers([]);
    setMobs([]);
    setEffects([]);
    setInventory([]);
    setEquipment({});
    visitedRef.current.clear();
    currentRoomIdRef.current = null;
    drawMap();
  }, [drawMap]);

  const sendCommand = useCallback(
    (raw: string, echo: boolean) => {
      const command = raw.trim();
      if (command.length === 0) return;
      const ws = socketRef.current;
      if (!ws || ws.readyState !== WebSocket.OPEN) {
        writeSystem("Disconnected. Reconnect to send commands.");
        return;
      }

      pushHistory(command);
      ws.send(command);
      if (echo) terminalRef.current?.write(`${command}\r\n`);
    },
    [pushHistory, writeSystem],
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
      const ws = socketRef.current;
      if (!ws || ws.readyState !== WebSocket.OPEN) return;

      if (data === "\x1b[A") {
        if (historyRef.current.length === 0) return;
        if (termHistoryIndexRef.current === -1) {
          termSavedInputRef.current = inputBufferRef.current;
          termHistoryIndexRef.current = historyRef.current.length - 1;
        } else if (termHistoryIndexRef.current > 0) {
          termHistoryIndexRef.current -= 1;
        }
        replaceTerminalInput(historyRef.current[termHistoryIndexRef.current] ?? "");
        return;
      }

      if (data === "\x1b[B") {
        if (termHistoryIndexRef.current === -1) return;
        termHistoryIndexRef.current += 1;
        if (termHistoryIndexRef.current >= historyRef.current.length) {
          termHistoryIndexRef.current = -1;
          replaceTerminalInput(termSavedInputRef.current);
        } else {
          replaceTerminalInput(historyRef.current[termHistoryIndexRef.current] ?? "");
        }
        return;
      }

      for (const ch of data) {
        if (ch === "\r") {
          sendCommand(inputBufferRef.current, false);
          terminalRef.current?.write("\r\n");
          inputBufferRef.current = "";
          setComposerValue("");
          termHistoryIndexRef.current = -1;
          termSavedInputRef.current = "";
          termTabCycleRef.current = EMPTY_TAB;
          continue;
        }

        if (ch === "\u007f") {
          if (inputBufferRef.current.length > 0) {
            inputBufferRef.current = inputBufferRef.current.slice(0, -1);
            terminalRef.current?.write("\b \b");
          }
          termTabCycleRef.current = EMPTY_TAB;
          continue;
        }

        if (ch === "\t") {
          const completion = nextCompletion(inputBufferRef.current, termTabCycleRef.current);
          if (!completion) continue;
          replaceTerminalInput(completion.value);
          termTabCycleRef.current = completion.cycle;
          continue;
        }

        const code = ch.charCodeAt(0);
        if (code >= 0x20 && code !== 0x7f) {
          inputBufferRef.current += ch;
          terminalRef.current?.write(ch);
          termTabCycleRef.current = EMPTY_TAB;
        }
      }
    },
    [replaceTerminalInput, sendCommand],
  );

  const handleGmcp = useCallback(
    (pkg: string, data: unknown) => {
      switch (pkg) {
        case "Char.Vitals": {
          const packet = data as Partial<Record<string, unknown>>;
          setVitals({
            hp: safeNumber(packet.hp),
            maxHp: safeNumber(packet.maxHp, 1),
            mana: safeNumber(packet.mana),
            maxMana: safeNumber(packet.maxMana, 1),
            level: typeof packet.level === "number" ? packet.level : null,
            xp: safeNumber(packet.xp),
            xpIntoLevel: safeNumber(packet.xpIntoLevel),
            xpToNextLevel: packet.xpToNextLevel === null ? null : safeNumber(packet.xpToNextLevel),
            gold: safeNumber(packet.gold),
          });
          break;
        }

        case "Char.Name": {
          const packet = data as Partial<Record<string, unknown>>;
          setCharacter({
            name: typeof packet.name === "string" && packet.name.length > 0 ? packet.name : "-",
            race: typeof packet.race === "string" ? packet.race : "",
            className: typeof packet.class === "string" ? packet.class : "",
            level: typeof packet.level === "number" ? packet.level : null,
          });
          break;
        }

        case "Room.Info": {
          const packet = data as Partial<Record<string, unknown>>;
          const exits = packet.exits && typeof packet.exits === "object" ? (packet.exits as Record<string, string>) : {};
          const id = typeof packet.id === "string" ? packet.id : null;

          setRoom({
            id,
            title: typeof packet.title === "string" && packet.title.length > 0 ? packet.title : "-",
            description: typeof packet.description === "string" ? packet.description : "",
            exits,
          });

          if (id) updateMap(id, exits);
          break;
        }

        case "Char.Items.List": {
          const packet = data as Partial<Record<string, unknown>>;
          const inventoryList = Array.isArray(packet.inventory)
            ? packet.inventory
                .filter((entry): entry is Record<string, unknown> => typeof entry === "object" && entry !== null)
                .map((entry) => ({
                  id: typeof entry.id === "string" ? entry.id : `${Date.now()}-${Math.random()}`,
                  name: typeof entry.name === "string" ? entry.name : "Unknown item",
                }))
            : [];

          const equipmentMap: Record<string, ItemSummary> = {};
          if (packet.equipment && typeof packet.equipment === "object") {
            for (const [slot, entry] of Object.entries(packet.equipment as Record<string, unknown>)) {
              if (!entry || typeof entry !== "object") continue;
              const item = entry as Record<string, unknown>;
              equipmentMap[slot] = {
                id: typeof item.id === "string" ? item.id : `${slot}-${Date.now()}`,
                name: typeof item.name === "string" ? item.name : "Unknown item",
              };
            }
          }

          setInventory(inventoryList);
          setEquipment(equipmentMap);
          break;
        }

        case "Char.Items.Add": {
          const packet = data as Partial<Record<string, unknown>>;
          setInventory((prev) => [
            ...prev,
            {
              id: typeof packet.id === "string" ? packet.id : `${Date.now()}-${Math.random()}`,
              name: typeof packet.name === "string" ? packet.name : "Unknown item",
            },
          ]);
          break;
        }

        case "Char.Items.Remove": {
          const packet = data as Partial<Record<string, unknown>>;
          if (typeof packet.id !== "string") break;
          setInventory((prev) => prev.filter((item) => item.id !== packet.id));
          break;
        }

        case "Room.Players": {
          if (!Array.isArray(data)) {
            setPlayers([]);
            break;
          }
          setPlayers(
            data
              .filter((entry): entry is Record<string, unknown> => typeof entry === "object" && entry !== null)
              .map((entry) => ({
                name: typeof entry.name === "string" ? entry.name : "Unknown",
                level: safeNumber(entry.level),
              })),
          );
          break;
        }

        case "Room.AddPlayer": {
          const packet = data as Partial<Record<string, unknown>>;
          const name = packet.name;
          if (typeof name !== "string") break;
          setPlayers((prev) => {
            if (prev.some((player) => player.name === name)) return prev;
            return [...prev, { name, level: safeNumber(packet.level) }];
          });
          break;
        }

        case "Room.RemovePlayer": {
          const packet = data as Partial<Record<string, unknown>>;
          if (typeof packet.name !== "string") break;
          setPlayers((prev) => prev.filter((player) => player.name !== packet.name));
          break;
        }

        case "Room.Mobs": {
          if (!Array.isArray(data)) {
            setMobs([]);
            break;
          }
          setMobs(
            data
              .filter((entry): entry is Record<string, unknown> => typeof entry === "object" && entry !== null)
              .map((entry) => ({
                id: typeof entry.id === "string" ? entry.id : `${Date.now()}-${Math.random()}`,
                name: typeof entry.name === "string" ? entry.name : "Unknown mob",
                hp: safeNumber(entry.hp),
                maxHp: Math.max(1, safeNumber(entry.maxHp, 1)),
              })),
          );
          break;
        }

        case "Room.AddMob": {
          const packet = data as Partial<Record<string, unknown>>;
          const id = packet.id;
          if (typeof id !== "string") break;
          setMobs((prev) => [
            ...prev,
            {
              id,
              name: typeof packet.name === "string" ? packet.name : "Unknown mob",
              hp: safeNumber(packet.hp),
              maxHp: Math.max(1, safeNumber(packet.maxHp, 1)),
            },
          ]);
          break;
        }

        case "Room.UpdateMob": {
          const packet = data as Partial<Record<string, unknown>>;
          if (typeof packet.id !== "string") break;
          setMobs((prev) =>
            prev.map((mob) => {
              if (mob.id !== packet.id) return mob;
              return {
                ...mob,
                hp: safeNumber(packet.hp, mob.hp),
                maxHp: Math.max(1, safeNumber(packet.maxHp, mob.maxHp)),
              };
            }),
          );
          break;
        }

        case "Room.RemoveMob": {
          const packet = data as Partial<Record<string, unknown>>;
          if (typeof packet.id !== "string") break;
          setMobs((prev) => prev.filter((mob) => mob.id !== packet.id));
          break;
        }

        case "Char.StatusEffects": {
          if (!Array.isArray(data)) {
            setEffects([]);
            break;
          }
          setEffects(
            data
              .filter((entry): entry is Record<string, unknown> => typeof entry === "object" && entry !== null)
              .map((entry) => ({
                name: typeof entry.name === "string" ? entry.name : "Effect",
                type: typeof entry.type === "string" ? entry.type : "BUFF",
                stacks: Math.max(1, safeNumber(entry.stacks, 1)),
                remainingMs: Math.max(0, safeNumber(entry.remainingMs, 0)),
              })),
          );
          break;
        }

        default:
          break;
      }
    },
    [updateMap],
  );

  const connect = useCallback(() => {
    const existing = socketRef.current;
    if (existing && (existing.readyState === WebSocket.OPEN || existing.readyState === WebSocket.CONNECTING)) return;

    const ws = new WebSocket(wsUrl);
    socketRef.current = ws;

    ws.addEventListener("open", () => {
      setConnected(true);
      setLiveMessage("Connected.");
      terminalRef.current?.focus();
    });

    ws.addEventListener("message", (event) => {
      if (typeof event.data !== "string") return;
      const gmcp = parseGmcp(event.data);
      if (gmcp) {
        handleGmcp(gmcp.pkg, gmcp.data);
      } else {
        terminalRef.current?.write(event.data);
      }
    });

    ws.addEventListener("close", () => {
      if (socketRef.current === ws) socketRef.current = null;
      setConnected(false);
      setLiveMessage("Connection closed.");
      inputBufferRef.current = "";
      setComposerValue("");
      resetHud();
      writeSystem("Connection closed.");
    });

    ws.addEventListener("error", () => {
      setLiveMessage("Connection error.");
      writeSystem("Connection error.");
    });
  }, [handleGmcp, resetHud, wsUrl, writeSystem]);

  const disconnect = useCallback(() => {
    const ws = socketRef.current;
    if (!ws) return;
    socketRef.current = null;
    if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) ws.close();
  }, []);

  const reconnect = useCallback(() => {
    disconnect();
    window.setTimeout(() => connect(), 120);
  }, [connect, disconnect]);

  useEffect(() => {
    if (!terminalHostRef.current) return;

    const term = new Terminal({
      cursorBlink: true,
      fontFamily: '"JetBrains Mono", "Cascadia Mono", monospace',
      fontSize: 14,
      rows: 30,
      convertEol: false,
      theme: {
        background: "#ede8f4",
        foreground: "#56586d",
        cursor: "#8c79a7",
        selectionBackground: "rgba(216, 197, 232, 0.3)",
      },
    });

    const fitAddon = new FitAddon();
    term.loadAddon(fitAddon);
    term.open(terminalHostRef.current);
    fitAddon.fit();
    term.focus();

    terminalRef.current = term;
    fitAddonRef.current = fitAddon;
    const dataListener = term.onData(handleTerminalData);

    const resizeObserver = new ResizeObserver(() => {
      fitAddon.fit();
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
  }, [drawMap, handleTerminalData]);

  useEffect(() => {
    connect();
    const onResize = () => {
      fitAddonRef.current?.fit();
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
  }, [connect, disconnect, drawMap]);

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
      : activePopout === "equipment"
        ? "Equipment"
        : "Currently Wearing";

  const submitComposer = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const command = composerValue.trim();
    if (!command) return;
    sendCommand(command, true);
    setComposerValue("");
    composerHistoryIndexRef.current = -1;
    composerSavedInputRef.current = "";
    composerTabCycleRef.current = EMPTY_TAB;
  };

  const onComposerKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "ArrowUp") {
      if (historyRef.current.length === 0) return;
      event.preventDefault();
      if (composerHistoryIndexRef.current === -1) {
        composerSavedInputRef.current = composerValue;
        composerHistoryIndexRef.current = historyRef.current.length - 1;
      } else if (composerHistoryIndexRef.current > 0) {
        composerHistoryIndexRef.current -= 1;
      }
      setComposerValue(historyRef.current[composerHistoryIndexRef.current] ?? "");
      return;
    }

    if (event.key === "ArrowDown") {
      if (composerHistoryIndexRef.current === -1) return;
      event.preventDefault();
      composerHistoryIndexRef.current += 1;
      if (composerHistoryIndexRef.current >= historyRef.current.length) {
        composerHistoryIndexRef.current = -1;
        setComposerValue(composerSavedInputRef.current);
      } else {
        setComposerValue(historyRef.current[composerHistoryIndexRef.current] ?? "");
      }
      return;
    }

    if (event.key === "Tab") {
      event.preventDefault();
      const completion = nextCompletion(composerValue, composerTabCycleRef.current);
      if (!completion) return;
      setComposerValue(completion.value);
      composerTabCycleRef.current = completion.cycle;
      return;
    }

    composerTabCycleRef.current = EMPTY_TAB;
  };

  return (
    <main className="app-shell">
      <div className="ambient-orb ambient-orb-a" aria-hidden="true" />
      <div className="ambient-orb ambient-orb-b" aria-hidden="true" />

      <header className="top-banner">
        <div>
          <p className="top-banner-kicker">AmbonMUD</p>
          <h1 className="top-banner-title">Web Client v3</h1>
          <p className="top-banner-subtitle">Fresh React client with parity gameplay features and a new visual language.</p>
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
        <section className="panel panel-play" aria-label="Gameplay console">
          <header className="panel-header"><h2>Play</h2><p>Terminal output and direct command flow.</p></header>
          {preLogin && (
            <section className="prelogin-banner" aria-label="Login guidance">
              <p className="prelogin-banner-title">Welcome back. Your session is connected.</p>
              <p className="prelogin-banner-text">Use the terminal to enter your character name and password. World and character panels will populate right after login.</p>
            </section>
          )}
          <div className="terminal-card"><div ref={terminalHostRef} className="terminal-host" aria-label="AmbonMUD terminal" /></div>

          <div className="movement-grid" role="toolbar" aria-label="Room exits">
            {exits.length === 0 ? (
              <span className="empty-note">{preLogin ? "Log in to unlock movement." : connected ? "No exits available." : "Reconnect to view exits."}</span>
            ) : (
              exits.map(([direction, target]) => (
                <button
                  key={direction}
                  type="button"
                  className="chip-button"
                  title={`Move ${direction} (${target})`}
                  onClick={() => {
                    sendCommand(direction, true);
                    terminalRef.current?.focus();
                  }}
                >
                  {direction}
                </button>
              ))
            )}
          </div>

          <form className="command-form" onSubmit={submitComposer}>
            <label htmlFor="command-input" className="sr-only">Command input</label>
            <input
              id="command-input"
              className="command-input"
              type="text"
              value={composerValue}
              onChange={(event) => {
                setComposerValue(event.target.value);
                composerTabCycleRef.current = EMPTY_TAB;
              }}
              onKeyDown={onComposerKeyDown}
              placeholder={commandPlaceholder}
              autoComplete="off"
              spellCheck={false}
            />
            <button type="submit" className="soft-button" disabled={!connected}>Send</button>
          </form>
        </section>

        <section className="panel panel-world" aria-label="World state">
          <header className="panel-header panel-header-with-actions">
            <div>
              <h2>World</h2>
              <p>Room context, exits, and local entities.</p>
            </div>
            <button type="button" className="panel-action-button" onClick={() => setActivePopout("map")} disabled={!canOpenMap}>
              Open Map
            </button>
          </header>

          <div className="world-stack">
            <article className="subpanel">
              {hasRoomDetails ? (
                <>
                  <h3>Room</h3>
                  <p className="room-title">{room.title}</p>
                  <p className="room-description">{room.description || "No room description available yet."}</p>
                  <div className="compass-block" aria-label="Current exits">
                    <div className="compass-rose" role="group" aria-label="Directional exits">
                      {COMPASS_DIRECTIONS.map((direction) => {
                        const enabled = availableExitSet.has(direction);
                        return (
                          <button
                            key={direction}
                            type="button"
                            className={`compass-node compass-node-${direction}`}
                            disabled={!enabled}
                            aria-label={enabled ? `Move ${direction}` : `${direction} exit unavailable`}
                            title={enabled ? `Move ${direction}` : `${direction} unavailable`}
                            onClick={() => {
                              sendCommand(direction, true);
                              terminalRef.current?.focus();
                            }}
                          >
                            {direction === "up" ? "U" : direction === "down" ? "D" : direction[0]?.toUpperCase()}
                          </button>
                        );
                      })}
                      <span className="compass-core" aria-hidden="true">A</span>
                    </div>
                    <p className="compass-caption">
                      {exits.length === 0 ? "No exits listed." : `Available: ${exits.map(([direction]) => direction).join(", ")}`}
                    </p>
                  </div>
                </>
              ) : (
                <div className="prelogin-card">
                  <h3>{connected ? "World Gate" : "World Offline"}</h3>
                  <p className="prelogin-card-title">{connected ? "Awaiting your credentials" : "Disconnected from AmbonMUD"}</p>
                  <p className="room-description">
                    {connected
                      ? "Once you finish login in the terminal, this panel will show your current room, exits, players, and nearby mobs."
                      : "Reconnect to establish a session. The world map and local room context will appear as soon as a session is active."}
                  </p>
                  <div className="prelogin-runes" aria-hidden="true">
                    <span />
                    <span />
                    <span />
                  </div>
                </div>
              )}
            </article>

            <article className="subpanel split-list">
              <div>
                <h3>Players</h3>
                {players.length === 0 ? <p className="empty-note">{hasRoomDetails ? "Nobody else is here." : "Online players will appear here after login."}</p> : (
                  <>
                    <ul className="entity-list">
                      {visiblePlayers.map((player) => (
                        <li key={player.name} className="entity-item"><span>{player.name}</span><span className="entity-meta">Lv {player.level}</span></li>
                      ))}
                    </ul>
                    {hiddenPlayersCount > 0 && <p className="empty-note">+{hiddenPlayersCount} more players</p>}
                  </>
                )}
              </div>

              <div>
                <h3>Mobs</h3>
                {mobs.length === 0 ? <p className="empty-note">{hasRoomDetails ? "No mobs in this room." : "Nearby creatures will appear here after login."}</p> : (
                  <>
                    <ul className="entity-list">
                      {visibleMobs.map((mob) => (
                        <li key={mob.id} className="mob-card">
                          <div className="entity-item"><span>{mob.name}</span><span className="entity-meta">{mob.hp}/{mob.maxHp}</span></div>
                          <div className="meter-track"><span className="meter-fill meter-fill-hp" style={{ width: `${percent(mob.hp, mob.maxHp)}%` }} /></div>
                        </li>
                      ))}
                    </ul>
                    {hiddenMobsCount > 0 && <p className="empty-note">+{hiddenMobsCount} more mobs</p>}
                  </>
                )}
              </div>
            </article>
          </div>
        </section>

        <section className="panel panel-character" aria-label="Character status">
          <header className="panel-header panel-header-with-actions">
            <div>
              <h2>Character</h2>
              <p>Identity, progression, and active effects.</p>
            </div>
            <div className="panel-action-row">
              <button type="button" className="panel-action-button" onClick={() => setActivePopout("equipment")} disabled={!canOpenEquipment}>
                Equipment
              </button>
              <button type="button" className="panel-action-button" onClick={() => setActivePopout("wearing")} disabled={!canOpenEquipment}>
                Currently Wearing
              </button>
            </div>
          </header>

          <div className="character-stack">
            <article className="subpanel">
              {hasCharacterProfile ? (
                <>
                  <h3>Identity</h3>
                  <p className="identity-name">{character.name}</p>
                  <p className="identity-detail">
                    {character.level ? `Level ${character.level}` : "Level -"}
                    {character.race ? ` ${character.race}` : ""}
                    {character.className ? ` ${character.className}` : ""}
                  </p>
                </>
              ) : (
                <div className="prelogin-card">
                  <h3>Identity</h3>
                  <p className="prelogin-card-title">{connected ? "Character profile pending" : "No active character"}</p>
                  <p className="room-description">
                    {connected
                      ? "After login, your name, class, race, and level will appear here."
                      : "Reconnect and log in to load your character profile."}
                  </p>
                </div>
              )}
            </article>

            <article className="subpanel meter-stack">
              <h3>Vitals</h3>
              {hasCharacterProfile ? (
                <>
                  <Bar label="HP" tone="hp" value={vitals.hp} max={Math.max(1, vitals.maxHp)} text={`${vitals.hp} / ${vitals.maxHp}`} />
                  <Bar label="Mana" tone="mana" value={vitals.mana} max={Math.max(1, vitals.maxMana)} text={`${vitals.mana} / ${vitals.maxMana}`} />
                  <Bar label="XP" tone="xp" value={xpValue} max={xpMax} text={xpText} />

                  <dl className="stat-grid">
                    <div><dt>Level</dt><dd>{vitals.level ?? "-"}</dd></div>
                    <div><dt>Total XP</dt><dd>{vitals.xp.toLocaleString()}</dd></div>
                    <div><dt>Gold</dt><dd>{vitals.gold.toLocaleString()}</dd></div>
                  </dl>
                </>
              ) : (
                <div className="meter-placeholder-stack">
                  <div className="meter-placeholder-row"><span>HP</span><span>- / -</span></div>
                  <div className="meter-track meter-track-placeholder"><span className="meter-fill meter-fill-placeholder" /></div>
                  <div className="meter-placeholder-row"><span>Mana</span><span>- / -</span></div>
                  <div className="meter-track meter-track-placeholder"><span className="meter-fill meter-fill-placeholder" /></div>
                  <div className="meter-placeholder-row"><span>XP</span><span>- / -</span></div>
                  <div className="meter-track meter-track-placeholder"><span className="meter-fill meter-fill-placeholder" /></div>
                </div>
              )}
            </article>

            <article className="subpanel character-effects">
              <h3>Effects</h3>
              {effects.length === 0 ? <p className="empty-note">{hasCharacterProfile ? "No active effects." : "Effects will appear here during gameplay."}</p> : (
                <>
                  <ul className="effects-list">
                    {visibleEffects.map((effect, index) => {
                      const seconds = Math.max(1, Math.ceil(effect.remainingMs / 1000));
                      const stack = effect.stacks > 1 ? ` x${effect.stacks}` : "";
                      return (
                        <li key={`${effect.name}-${index}`} className="effect-item">
                          <span className="effect-name">{effect.name}{stack}</span>
                          <span className="effect-type">{effect.type}</span>
                          <span className="effect-time">{seconds}s</span>
                        </li>
                      );
                    })}
                  </ul>
                  {hiddenEffectsCount > 0 && <p className="empty-note">+{hiddenEffectsCount} more effects</p>}
                </>
              )}
            </article>
          </div>
        </section>
      </div>

      {activePopout && (
        <div className="popout-backdrop" onClick={() => setActivePopout(null)}>
          <section
            className="popout-dialog"
            role="dialog"
            aria-modal="true"
            aria-label={popoutTitle}
            onClick={(event) => event.stopPropagation()}
          >
            <header className="popout-header">
              <h2>{popoutTitle}</h2>
              <button type="button" className="soft-button popout-close" onClick={() => setActivePopout(null)}>
                Close
              </button>
            </header>

            {activePopout === "map" && (
              <div className="popout-content">
                <canvas
                  ref={mapCanvasRef}
                  className="mini-map mini-map-popout"
                  width={900}
                  height={560}
                  aria-label="Visited room map"
                />
              </div>
            )}

            {activePopout === "equipment" && (
              <div className="popout-content">
                {inventory.length === 0 ? (
                  <p className="empty-note">No equipment in bags right now.</p>
                ) : (
                  <ul className="item-list">
                    {inventory.map((item) => (
                      <li key={item.id}>{item.name}</li>
                    ))}
                  </ul>
                )}
              </div>
            )}

            {activePopout === "wearing" && (
              <div className="popout-content">
                {equipmentSlots.length === 0 ? (
                  <p className="empty-note">Nothing currently worn.</p>
                ) : (
                  <ul className="equipment-list">
                    {equipmentSlots.map((slot) => (
                      <li key={slot}>
                        <span className="equipment-slot">{slot}</span>
                        <span>{equipment[slot]?.name ?? "Unknown"}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )}
          </section>
        </div>
      )}

      <nav className="mobile-tab-bar" aria-label="Mobile sections">
        {TABS.map((tab) => (
          <button
            key={tab.id}
            type="button"
            className={`mobile-tab ${activeTab === tab.id ? "mobile-tab-active" : ""}`}
            onClick={() => setActiveTab(tab.id)}
            aria-pressed={activeTab === tab.id}
          >
            {tab.label}
          </button>
        ))}
      </nav>

      <p className="sr-only" aria-live="polite">{liveMessage}</p>
    </main>
  );
}

export default App;
