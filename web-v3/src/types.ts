export type PopoutPanel = "map" | "inventory" | "equipment" | "room" | "help" | "character" | "chat" | "shop" | "spellbook" | "quests" | "mail" | "crafting" | null;
export type ChatChannel = "say" | "tell" | "gossip" | "shout" | "ooc" | "gtell" | "gchat";
export type SocialTab = "chat" | "friends" | "guild" | "group" | "who";

export interface WhoPlayer {
  name: string;
  level: number;
  race: string;
  playerClass: string;
  title: string | null;
  guild: string | null;
  groupSize: number;
  idle: number;
}

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

export interface PendingGroupInvite {
  inviterName: string;
  receivedAt: number;
}

export interface PendingGuildInvite {
  inviterName: string;
  guildName: string;
  guildTag: string;
  receivedAt: number;
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
  video?: string | null;
  music?: string | null;
  ambient?: string | null;
  station?: string | null;
  mapX: number;
  mapY: number;
}

export interface ItemSummary {
  id: string;
  name: string;
  keyword: string;
  slot: string | null;
  basePrice?: number;
  image?: string | null;
  video?: string | null;
}

export interface EquipmentSlotDef {
  id: string;
  displayName: string;
  order: number;
  x: number;
  y: number;
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
  video: string | null;
}

export interface ShopState {
  name: string;
  sellMultiplier: number;
  items: ShopItem[];
}

export interface RoomItem {
  id: string;
  name: string;
  description?: string;
  image?: string | null;
  video?: string | null;
}

export interface RoomPlayer {
  name: string;
  level: number;
}

export interface RoomMob {
  id: string;
  name: string;
  description?: string;
  hp: number;
  maxHp: number;
  image?: string | null;
  video?: string | null;
  effects?: StatusEffect[];
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
  title: string;
  image: string | null;
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
  effectType: string;
  classRestriction: string | null;
  image: string | null;
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
  effectName: string | null;
  killerName: string | null;
  killerIsPlayer: boolean;
  attackerName: string | null;
  xpGained: number;
  goldGained: number;
}

export interface StatEntry {
  id: string;
  name: string;
  abbrev: string;
  base: number;
  effective: number;
}

export interface CharStats {
  stats: StatEntry[];
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
  newLevel: number | null;
  hpGained: number | null;
  manaGained: number | null;
}

export interface CraftingSkill {
  id: string;
  name: string;
  level: number;
  xp: number;
  xpToNext: number;
  maxLevel: number;
  type: "gathering" | "crafting";
}

export interface CraftingRecipe {
  id: string;
  name: string;
  skill: string;
  skillRequired: number;
  levelRequired: number;
  materials: Array<{ name: string; quantity: number }>;
  outputName: string;
  outputQuantity: number;
}

export interface CraftingNode {
  id: string;
  name: string;
  skill: string;
  skillRequired: number;
}

export interface CraftingResult {
  type: "gather" | "craft";
  skill: string;
  xpAwarded: number;
  leveledUp: boolean;
  newLevel: number;
  itemName: string | null;
  quantity: number | null;
}

export interface RoomFeature {
  id: string;
  name: string;
  keyword: string;
  type: "door" | "container" | "lever" | "sign";
  state: string | null;
  direction: string | null;
  locked: boolean | null;
  keyRequired: boolean | null;
  text: string | null;
}

export interface ContainerContents {
  featureId: string;
  name: string;
  keyword: string;
  items: Array<{ name: string; keyword: string }>;
}

export interface MailEntry {
  index: number;
  id: string;
  from: string;
  date: number;
  read: boolean;
  preview: string;
}

export interface MailMessage {
  index: number;
  id: string;
  from: string;
  body: string;
  date: number;
  read: boolean;
}

export interface MailNotification {
  from: string;
  unreadCount: number;
}

export interface QuestNotification {
  id: string;
  questId: string;
  questName: string;
  event: "complete" | "update";
  receivedAt: number;
}

export interface QuestAvailableObjective {
  description: string;
  count: number;
}

export interface QuestAvailableRewards {
  xp: number;
  gold: number;
}

export interface QuestAvailable {
  id: string;
  name: string;
  description: string;
  giverMobId: string;
  objectives: QuestAvailableObjective[];
  rewards: QuestAvailableRewards;
}

export interface ZoneInstanceItem {
  engineId: string;
  playerCount: number;
  capacity: number;
  isCurrent: boolean;
}

export interface ZoneInstances {
  zone: string | null;
  currentEngineId: string | null;
  instances: ZoneInstanceItem[];
}

export interface MobInfo {
  id: string;
  level: number;
  tier: string;
  questGiver: boolean;
  questAvailable: boolean;
  questComplete: boolean;
  shopKeeper: boolean;
  dialogue: boolean;
  aggressive: boolean;
}

export interface LoginRaceOption {
  id: string;
  name: string;
  stats: string;
  description?: string;
  backstory?: string;
  traits?: string[];
  abilities?: string[];
  image?: string;
}

export interface LoginClassOption {
  id: string;
  name: string;
  stats: string;
  description?: string;
  backstory?: string;
  image?: string;
}

export type LoginPromptState =
  | { state: "name" }
  | { state: "password"; name: string }
  | { state: "confirmCreate"; name: string }
  | { state: "newPassword"; name: string }
  | { state: "raceSelection"; name: string; races: LoginRaceOption[] }
  | { state: "classSelection"; name: string; race: string; classes: LoginClassOption[] };

export interface LoginErrorState {
  state: string;
  message: string;
}

export interface CommandEntry {
  name: string;
  usage: string;
  description: string;
  category: string;
  staff: boolean;
  requiresTarget: boolean;
}

export interface EmotePreset {
  label: string;
  emoji: string;
  action: string;
}

export interface StaffWorldRoom {
  id: string;
  title: string;
}

export interface StaffWorldZone {
  zone: string;
  rooms: StaffWorldRoom[];
}

export interface StaffMobTemplate {
  id: string;
  name: string;
}

export interface StaffMobZone {
  zone: string;
  mobs: StaffMobTemplate[];
}

export interface UiFeedback {
  type: "error" | "info" | "success";
  message: string;
  /** Stable machine-readable code, e.g. "INSUFFICIENT_GOLD", "TARGET_NOT_FOUND" */
  code?: string;
  /** Which system generated the feedback, e.g. "combat", "shop", "crafting" */
  scope?: string;
  /** The command that triggered the feedback, e.g. "buy", "cast" */
  command?: string;
}

export interface LookTargetInfo {
  type: "mob" | "player" | "item" | "feature";
  name: string;
  description: string;
  image?: string | null;
  level?: number | null;
  race?: string | null;
  playerClass?: string | null;
  receivedAt: number;
}

export interface CombatLogMessage {
  id: number;
  text: string;
  style: "damage" | "heal" | "info" | "error" | "kill" | "dodge" | "xp";
  receivedAt: number;
}

export interface SpriteEntry {
  imageId: string;
  displayName: string;
  category: string;
  imagePath: string;
}

export interface SpriteList {
  active: string | null;
  sprites: SpriteEntry[];
}

