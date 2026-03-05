export type MobileTab = "play" | "world" | "chat" | "character";
export type PopoutPanel = "map" | "equipment" | "wearing" | "room" | "mobDetail" | "itemDetail" | null;
export type ChatChannel = "say" | "tell" | "gossip" | "shout" | "ooc" | "gtell" | "gchat";
export type SocialTab = "chat" | "friends" | "guild" | "group" | "who";

export interface GroupMember {
  name: string;
  level: number;
  hp: number;
  maxHp: number;
  mana: number;
  maxMana: number;
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
  gender: string;
  race: string;
  className: string;
  level: number | null;
  sprite: string | null;
  isStaff: boolean;
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
  keyword: string;
  slot: string | null;
  basePrice?: number;
  image?: string | null;
}

export interface ShopItem {
  id: string;
  name: string;
  keyword: string;
  description: string;
  slot: string | null;
  damage: number;
  armor: number;
  buyPrice: number;
  basePrice: number;
  consumable: boolean;
  image: string | null;
}

export interface ShopState {
  name: string;
  sellMultiplier: number;
  items: ShopItem[];
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

export interface CombatEventData {
  type: string;
  targetName: string | null;
  targetId: string | null;
  abilityId: string | null;
  abilityName: string | null;
  damage: number;
  healing: number;
  absorbed: number;
  shieldRemaining: number;
  sourceIsPlayer: boolean;
  xpGained: number;
  goldGained: number;
}

export interface CharStats {
  strength: number;
  dexterity: number;
  constitution: number;
  intelligence: number;
  wisdom: number;
  charisma: number;
  effectiveStrength: number;
  effectiveDexterity: number;
  effectiveConstitution: number;
  effectiveIntelligence: number;
  effectiveWisdom: number;
  effectiveCharisma: number;
  baseDamageMin: number;
  baseDamageMax: number;
  armor: number;
  dodgePercent: number;
}

export interface QuestObjective {
  description: string;
  current: number;
  required: number;
}

export interface QuestEntry {
  id: string;
  name: string;
  description: string;
  objectives: QuestObjective[];
}

export interface GainEvent {
  type: string;
  amount: number;
  source: string | null;
}

export interface QuestNotification {
  id: string;
  questId: string;
  questName: string;
  event: "complete" | "update";
  receivedAt: number;
}

export interface MobInfo {
  id: string;
  level: number;
  tier: string;
  questGiver: boolean;
  shopKeeper: boolean;
  dialogue: boolean;
}

