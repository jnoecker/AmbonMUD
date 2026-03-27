import type {
  ChatChannel,
  CharacterInfo,
  RoomState,
  StatusVarLabels,
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
  // Navigation
  "look", "north", "south", "east", "west", "up", "down", "exits", "recall",
  // Communication
  "say", "tell", "whisper", "shout", "gossip", "ooc", "emote", "pose", "who",
  // Combat & abilities
  "kill", "flee", "cast", "spells", "abilities", "skills", "effects", "dispel",
  // Inventory & equipment
  "inventory", "equipment", "get", "drop", "wear", "remove", "use", "give", "put",
  // World interaction
  "open", "close", "lock", "unlock", "search", "pull", "read",
  // Shops
  "list", "buy", "sell",
  // Progression & character
  "score", "gold", "title", "gender", "achievements",
  // Quests & dialogue
  "quest", "accept", "talk",
  // Groups & guilds
  "group", "guild", "gtell", "gchat",
  // Friends & mail
  "friend", "mail",
  // Crafting
  "gather", "craft", "recipes", "craftskills",
  // Sprites
  "sprite",
  // Utility
  "phase", "help", "quit", "clear", "colors",
];

export const MAP_OFFSETS: Record<string, { dx: number; dy: number }> = {
  north: { dx: 0, dy: -1 },
  south: { dx: 0, dy: 1 },
  east: { dx: 1, dy: 0 },
  west: { dx: -1, dy: 0 },
  up: { dx: 1, dy: -1 },
  down: { dx: -1, dy: 1 },
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
  inCombat: false,
};

export const DEFAULT_STATUS_VAR_LABELS: StatusVarLabels = {
  hp: "HP",
  maxHp: "Max HP",
  mana: "Mana",
  maxMana: "Max Mana",
  level: "Level",
  xp: "XP",
};

export const EMPTY_CHAR: CharacterInfo = { name: "-", gender: "", race: "", className: "", level: null, sprite: null, isStaff: false };
export const EMPTY_ROOM: RoomState = { id: null, title: "-", description: "", exits: {}, mapX: 0, mapY: 0 };

