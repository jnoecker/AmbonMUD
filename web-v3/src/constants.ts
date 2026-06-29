import type {
  ChatChannel,
  CharacterInfo,
  RoomState,
  ServerFeatures,
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

export const MAP_OFFSETS: Record<string, { dx: number; dy: number }> = {
  north: { dx: 0, dy: -1 },
  south: { dx: 0, dy: 1 },
  east: { dx: 1, dy: 0 },
  west: { dx: -1, dy: 0 },
  up: { dx: 1, dy: -1 },
  down: { dx: -1, dy: 1 },
};

/**
 * Deterministic hue (0–359°) for a zone name. Used to colour-code rooms that sit
 * just across a zone boundary on the minimap so each neighbouring zone reads as a
 * distinct region. Stable across sessions (pure function of the name).
 */
export function zoneHue(zone: string): number {
  let h = 0;
  for (let i = 0; i < zone.length; i++) h = (Math.imul(h, 31) + zone.charCodeAt(i)) | 0;
  return ((h % 360) + 360) % 360;
}

/** Resolve a zone's tint to 8-bit RGB. Mid saturation/lightness so it reads on
 *  both the light parchment minimap and the dark expanded chart. */
function zoneRgb(zone: string, saturation: number, lightness: number): { r: number; g: number; b: number } {
  const h = zoneHue(zone) / 360;
  const hue2rgb = (p: number, q: number, t: number): number => {
    let tt = t;
    if (tt < 0) tt += 1;
    if (tt > 1) tt -= 1;
    if (tt < 1 / 6) return p + (q - p) * 6 * tt;
    if (tt < 1 / 2) return q;
    if (tt < 2 / 3) return p + (q - p) * (2 / 3 - tt) * 6;
    return p;
  };
  const q = lightness < 0.5 ? lightness * (1 + saturation) : lightness + saturation - lightness * saturation;
  const p = 2 * lightness - q;
  return {
    r: Math.round(hue2rgb(p, q, h + 1 / 3) * 255),
    g: Math.round(hue2rgb(p, q, h) * 255),
    b: Math.round(hue2rgb(p, q, h - 1 / 3) * 255),
  };
}

/** Zone-boundary tint as a packed 0xRRGGBB integer (for PixiJS `tint`/`color`). */
export function zoneColor(zone: string, saturation = 0.62, lightness = 0.58): number {
  const { r, g, b } = zoneRgb(zone, saturation, lightness);
  return (r << 16) | (g << 8) | b;
}

/** Zone-boundary tint as a `#rrggbb` CSS string (for Canvas 2D fill/stroke). */
export function zoneColorCss(zone: string, saturation = 0.62, lightness = 0.58): string {
  const { r, g, b } = zoneRgb(zone, saturation, lightness);
  return `#${((1 << 24) | (r << 16) | (g << 8) | b).toString(16).slice(1)}`;
}

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

export const EMPTY_CHAR: CharacterInfo = { name: "-", gender: "", race: "", className: "", level: null, sprite: null, isStaff: false, title: null, autolootEnabled: false, autopeekEnabled: false, screenReaderEnabled: false, wimpyThresholdPct: 10, isDemo: false };
export const DEFAULT_SERVER_FEATURES: ServerFeatures = {
  dailyQuests: false,
  weeklyQuests: false,
  globalQuests: false,
  autoQuests: false,
};
export const EMPTY_ROOM: RoomState = { id: null, title: "-", description: "", exits: {}, mapX: 0, mapY: 0, graphical: false, terrain: "outside" };

