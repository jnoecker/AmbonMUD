export type MobileTab = "play" | "world" | "character";
export type PopoutPanel = "map" | "equipment" | "wearing" | "room" | null;

export interface Vitals {
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

export interface CharacterInfo {
  name: string;
  race: string;
  className: string;
  level: number | null;
}

export interface RoomState {
  id: string | null;
  title: string;
  description: string;
  exits: Record<string, string>;
}

export interface ItemSummary {
  id: string;
  name: string;
}

export interface RoomPlayer {
  name: string;
  level: number;
}

export interface RoomMob {
  id: string;
  name: string;
  hp: number;
  maxHp: number;
}

export interface StatusEffect {
  name: string;
  type: string;
  stacks: number;
  remainingMs: number;
}

export interface MapRoom {
  x: number;
  y: number;
  exits: Record<string, string>;
}

export interface TabCycle {
  matches: string[];
  index: number;
  originalPrefix: string;
  args: string;
}

