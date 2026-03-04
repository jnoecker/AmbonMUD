export type MobileTab = "play" | "world" | "chat" | "character";
export type PopoutPanel = "map" | "equipment" | "wearing" | "room" | "mobDetail" | "itemDetail" | null;
export type ChatChannel = "say" | "tell" | "gossip" | "shout" | "ooc" | "gtell" | "gchat";
export type SocialTab = "chat" | "friends" | "guild" | "group" | "who";

export interface GroupMember {
  name: string;
  level: number;
  hp: number;
  maxHp: number;
  playerClass: string;
}

export interface GroupInfo {
  leader: string | null;
  members: GroupMember[];
}

export interface FriendEntry {
  name: string;
  online: boolean;
  level: number | null;
  zone: string | null;
}

export interface FriendNotification {
  id: string;
  name: string;
  event: "online" | "offline";
  receivedAt: number;
}

export interface GuildInfo {
  name: string | null;
  tag: string | null;
  rank: string | null;
  motd: string | null;
  memberCount: number;
  maxSize: number;
}

export interface GuildMemberEntry {
  name: string;
  rank: string;
  online: boolean;
  level: number | null;
}

export interface DialogueChoice {
  index: number;
  text: string;
}

export interface DialogueState {
  mobName: string;
  text: string;
  choices: DialogueChoice[];
}

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
  inCombat: boolean;
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
  image?: string | null;
}

export interface ItemSummary {
  id: string;
  name: string;
  slot: string | null;
  image?: string | null;
}

export interface RoomItem {
  id: string;
  name: string;
  image?: string | null;
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
  image?: string | null;
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

export interface ChatMessage {
  id: string;
  channel: ChatChannel;
  sender: string;
  message: string;
  receivedAt: number;
}

export interface CompletedAchievement {
  id: string;
  name: string;
  title: string | null;
}

export interface InProgressAchievement {
  id: string;
  name: string;
  current: number;
  required: number;
}

export interface AchievementData {
  completed: CompletedAchievement[];
  inProgress: InProgressAchievement[];
}

export interface StatusVarLabels {
  hp: string;
  maxHp: string;
  mana: string;
  maxMana: string;
  level: string;
  xp: string;
}

export interface CombatTarget {
  targetId: string | null;
  targetName: string | null;
  targetHp: number | null;
  targetMaxHp: number | null;
  targetImage: string | null;
}

export interface SkillSummary {
  id: string;
  name: string;
  description: string;
  manaCost: number;
  cooldownMs: number;
  cooldownRemainingMs: number;
  levelRequired: number;
  targetType: string;
  classRestriction: string | null;
  receivedAt: number;
}

