import type {
  ChatChannel,
  CharacterInfo,
  MobileTab,
  RoomState,
  TabCycle,
  Vitals,
} from "./types";

export const EMPTY_TAB: TabCycle = { matches: [], index: 0, originalPrefix: "", args: "" };
export const HISTORY_KEY = "ambonmud_v3_history";
export const MAX_HISTORY = 100;
export const MAX_VISIBLE_WORLD_PLAYERS = 4;
export const MAX_VISIBLE_WORLD_MOBS = 4;
export const MAX_VISIBLE_WORLD_ITEMS = 4;
export const MAX_VISIBLE_EFFECTS = 4;
export const EXIT_ORDER = ["north", "south", "east", "west", "up", "down"];
export const COMPASS_DIRECTIONS = ["north", "east", "south", "west", "up", "down"] as const;
export type Direction = (typeof COMPASS_DIRECTIONS)[number];
export const SLOT_ORDER = ["head", "body", "hand"];
export const TABS: Array<{ id: MobileTab; label: string }> = [
  { id: "play", label: "Play" },
  { id: "world", label: "World" },
  { id: "chat", label: "Social" },
  { id: "character", label: "Character" },
];
export const CHAT_CHANNELS: Array<{
  id: ChatChannel;
  label: string;
  requiresTarget: boolean;
  messagePlaceholder: string;
  targetPlaceholder: string | null;
}> = [
  {
    id: "say",
    label: "Say",
    requiresTarget: false,
    messagePlaceholder: "Say something in the room",
    targetPlaceholder: null,
  },
  {
    id: "tell",
    label: "Tell",
    requiresTarget: true,
    messagePlaceholder: "Private message",
    targetPlaceholder: "Player name",
  },
  {
    id: "gossip",
    label: "Gossip",
    requiresTarget: false,
    messagePlaceholder: "Global gossip message",
    targetPlaceholder: null,
  },
  {
    id: "shout",
    label: "Shout",
    requiresTarget: false,
    messagePlaceholder: "Shout to your zone",
    targetPlaceholder: null,
  },
  {
    id: "ooc",
    label: "OOC",
    requiresTarget: false,
    messagePlaceholder: "Out of character message",
    targetPlaceholder: null,
  },
];
export const MAX_CHAT_MESSAGES_PER_CHANNEL = 120;

export const COMMANDS = [
  "look", "north", "south", "east", "west", "up", "down", "say", "tell", "whisper", "shout",
  "gossip", "ooc", "emote", "pose", "who", "score", "inventory", "equipment", "exits", "get", "drop",
  "wear", "remove", "use", "give", "kill", "flee", "cast", "spells", "abilities", "effects", "help",
  "phase", "gold", "list", "buy", "sell", "quit", "clear", "colors",
];

export const MAP_OFFSETS: Record<string, { dx: number; dy: number }> = {
  north: { dx: 0, dy: -1 },
  south: { dx: 0, dy: 1 },
  east: { dx: 1, dy: 0 },
  west: { dx: -1, dy: 0 },
  up: { dx: 0.5, dy: -0.5 },
  down: { dx: -0.5, dy: 0.5 },
};

export const EMPTY_VITALS: Vitals = {
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

export const EMPTY_CHAR: CharacterInfo = { name: "-", race: "", className: "", level: null };
export const EMPTY_ROOM: RoomState = { id: null, title: "-", description: "", exits: {} };

