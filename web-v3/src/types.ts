export type PopoutPanel = "map" | "inventory" | "equipment" | "room" | "help" | "character" | "chat" | "shop" | "spellbook" | "quests" | "questOffers" | "mail" | "crafting" | "housing" | "leaderboard" | "trainer" | "bank" | "stylist" | "auction" | "dungeon" | "lottery" | "dice" | "puzzle" | "features" | "combatlog" | "inn" | "terminal" | null;
export type SystemPanelView = "dungeon" | "duel" | "lottery" | "pet" | "prestige" | "currencies" | "factions";
export type ChatChannel = "say" | "tell" | "gossip" | "shout" | "ooc" | "gtell" | "gchat";
export type SocialTab = "chat" | "friends" | "guild" | "group" | "who";
export type RoomFeatureType = "door" | "container" | "lever" | "sign";
export type FeaturePopoutFocus = RoomFeatureType | null;

export interface WorldTime {
  period: string;
  hour: number;
  minute: number;
}

export interface WorldWeather {
  zone: string;
  weather: string;
  description: string;
  particleHint: string;
  icon: string;
}

export interface WorldEvent {
  id: string;
  name: string;
  description: string;
}

export interface MoteColor {
  core: string;
  glow: string;
}

export interface SkyGradient {
  top: string;
  bottom: string;
}

export interface ZoneEnvironment {
  zone: string;
  moteColors: MoteColor[];
  skyGradients: Record<string, SkyGradient>;
  transitionColors: string[];
  weatherParticleOverrides: Record<string, string>;
}

/**
 * Zone intro cinematic — powers the map's replay button. Autoplay (first-ever
 * zone entry) is handled as an event via the canvas video bridge, not state:
 * the server marks the cinematic seen immediately, so it requests autoplay
 * at most once per player per zone.
 */
export interface ZoneCinematicState {
  zone: string;
  url: string;
}

export interface CurrencyBalance {
  id: string;
  name: string;
  abbreviation: string;
  balance: number;
}

export interface AuctionListing {
  id: number;
  itemName: string;
  itemId: string;
  price: number;
  seller: string;
}

export interface LeaderboardEntry {
  rank: number;
  name: string;
  score: number;
}

export interface LeaderboardData {
  category: string;
  label: string;
  scoreLabel: string;
  entries: LeaderboardEntry[];
}

export interface TradeItem {
  id: string;
  name: string;
}

export interface TradeState {
  active: boolean;
  partner: string | null;
  myItems: TradeItem[];
  theirItems: TradeItem[];
  myGold: number;
  theirGold: number;
  myAccepted: boolean;
  theirAccepted: boolean;
}

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
  /** Resolved voice-over clip URL for the current node, or null when none. */
  voiceUrl: string | null;
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
  prestigeLevel?: number;
  prestigeXpAvailable?: number | null;
  prestigeNextCost?: number | null;
}

export interface CharacterInfo {
  name: string;
  gender: string;
  race: string;
  className: string;
  level: number | null;
  sprite: string | null;
  isStaff: boolean;
  autolootEnabled: boolean;
  /** When true, room descriptions gain a peek paragraph naming adjacent rooms. */
  autopeekEnabled: boolean;
  /** Wimpy auto-flee threshold (HP%). 0 = disabled. */
  wimpyThresholdPct: number;
  /**
   * True for ephemeral demo characters. Surfaces a "Save your progress" banner
   * in the UI and offers a `/claim` form. Flips to false after a successful claim.
   */
  isDemo: boolean;
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
  trainer?: string | null;
  mapX: number;
  mapY: number;
  housing?: boolean;
  housingOwner?: string | null;
  /** Whether the current zone has custom graphical assets. */
  graphical?: boolean;
  /** Terrain type — drives default background and weather suppression. */
  terrain?: string;
  bank?: boolean;
  stylist?: boolean;
  tavern?: boolean;
  dungeon?: boolean;
  auction?: boolean;
  housingBroker?: boolean;
  inn?: boolean;
  /** True when this is the death sanctum and the player has somewhere to depart back to. */
  canDepart?: boolean;
  /** Auto-peek entries: open exits paired with destination room titles. Empty when autopeek is off. */
  peek?: { direction: string; title: string }[];
}

/** Recall point info — emitted by Char.Recall GMCP. Null fields when not set. */
export interface RecallState {
  roomId: string | null;
  roomTitle: string | null;
}

/** User layout preference: auto follows zone flag, text/canvas force a mode. */
export type LayoutMode = "auto" | "text" | "canvas";

/** Broad server-assigned item category, lowercased. */
export type ItemType =
  | "equipment"
  | "consumable"
  | "quest"
  | "treasure"
  | "misc";

export interface ItemSummary {
  id: string;
  name: string;
  keyword: string;
  slot: string | null;
  basePrice?: number;
  /** Bonus melee damage (top-level — distinct from {@link stats} map). */
  damage?: number;
  /** Bonus armor (top-level — distinct from {@link stats} map). */
  armor?: number;
  image?: string | null;
  video?: string | null;
  stats?: Record<string, number>;
  enchantments?: string[];
  consumable?: boolean;
  useEffect?: string;
  /** Numeric on-use restoration. Sent alongside [useEffect] for consumables that heal HP or mana. */
  onUse?: { healHp: number; healMana: number };
  /** Server-resolved category. Falls back to "misc" if missing for older servers. */
  itemType?: ItemType;
  /** True when the item is soulbound — cannot be dropped, sold, traded, given. */
  questItem?: boolean;
  /** Grouping key used to visually collapse identical instances into a stack. */
  stackKey?: string;
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

export interface PuzzleItem {
  id: string;
  /** "riddle" or "sequence". */
  type: "riddle" | "sequence";
  /** Riddle question text; null for sequence puzzles. */
  question: string | null;
  /** Total steps in a sequence puzzle; null for riddles. */
  totalSteps: number | null;
  /** Current progress (0-based next step) for sequence puzzles; null for riddles. */
  currentStep: number | null;
  /** True if the player has already solved this puzzle. */
  solved: boolean;
}

export interface PuzzleState {
  puzzles: PuzzleItem[];
}

export interface RoomItem {
  id: string;
  name: string;
  description?: string;
  image?: string | null;
  video?: string | null;
  /** Whether the item can be picked up (false for fixed scenery). */
  takeable?: boolean;
}

export interface RoomPlayer {
  name: string;
  level: number;
  sprite?: string | null;
}

export interface RoomMob {
  id: string;
  /**
   * Template/definition key shared by every spawn of the same mob template.
   * Used to coalesce duplicate instances into a single sprite. Distinct mobs
   * with the same display name have different templateKeys and stay separate.
   * Empty string for legacy mobs or mobs without a backing template.
   */
  templateKey: string;
  name: string;
  description?: string;
  hp: number;
  maxHp: number;
  image?: string | null;
  video?: string | null;
  category?: string;
  effects?: StatusEffect[];
  /** Character name of the pet's owner, or undefined for regular mobs. */
  ownerName?: string | null;
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
  /** Terrain key (forest, mountain, …) once the room has been visited. */
  terrain?: string;
  housing?: boolean;
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
  isWhisper?: boolean;
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
  targetCategory: string | null;
}

export type ConsiderRating =
  | "TRIVIAL"
  | "EASY"
  | "FAVORED"
  | "EVEN"
  | "RISKY"
  | "DANGEROUS"
  | "SUICIDAL";

export interface ConsiderResult {
  mobId: string;
  mobName: string;
  mobLevel: number;
  mobMaxHp: number;
  mobCategory: string;
  mobImage: string | null;
  playerLevel: number;
  playerMaxHp: number;
  playerAvgDamage: number;
  mobAvgDamage: number;
  hitsToKillMob: number;
  hitsToKillPlayer: number;
  dodgeChancePct: number;
  winChancePct: number;
  rating: ConsiderRating;
  ratingLabel: string;
  ratingFlavor: string;
  receivedAt: number;
}

/** A clicked creature opened in the monster-manual / bestiary panel. */
export interface MonsterEntry {
  id?: string;
  name: string;
  description: string | null;
  image: string | null;
  video: string | null;
  /** Level from MobInfo (shown before the consider stats arrive). */
  level: number | null;
  info: MobInfo | null;
  isStaff: boolean;
  /** Whether an Attack button + threat assessment apply (false for pets). */
  canAttack: boolean;
}

/** A clicked room item, surfaced in the parchment item card (analogous to the
 *  monster manual). */
export interface ItemEntry {
  id?: string;
  name: string;
  description: string | null;
  image: string | null;
  video: string | null;
  /** Whether the item can be picked up (hides Get for fixed scenery). */
  takeable: boolean;
  /** Set when opened from an equipped slot — surfaces an Unequip action. */
  equippedSlot?: string;
}

/**
 * Per-skill visual archetype that drives how the web client renders the
 * cast moment. Sent in the Char.Skills GMCP payload as `visual.archetype`.
 * Falls back to RANGED_PROJECTILE if unknown.
 */
export type AbilityVisualArchetype =
  | "RANGED_PROJECTILE"
  | "MELEE_STRIKE"
  | "HEAL_AURA"
  | "BUFF_AURA"
  | "DEBUFF_AURA"
  | "AREA_BURST"
  | "SUMMON_POOF";

export interface SkillVisual {
  archetype: AbilityVisualArchetype;
  projectileImage: string | null;
  color: string | null;
  accentColor: string | null;
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
  visual: SkillVisual | null;
  receivedAt: number;
  /**
   * "self" for player abilities (cast via `cast <id>`), "pet" for pet signature skills
   * (cast via `pet <id>`). Defaults to "self" when absent for back-compat.
   */
  source?: "self" | "pet";
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
  petName: string | null;
  /** True when the visible target of a non-damaging cast is the local player. */
  targetIsPlayer: boolean;
  /** Display names of items autolooted from the kill (only set on type=="kill"). */
  lootedItems: string[];
  /** True when the player disengaged via the wimpy threshold rather than typing `flee`. */
  forced: boolean;
  /**
   * Server-rendered narrative line (e.g. custom config templates for mob/pet/ability messages,
   * stat-formula suffixes on melee). When present, the combat log uses it verbatim instead of
   * the hardcoded client template.
   */
  text: string | null;
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
  targetRoomIds?: string[];
}

export interface QuestEntry {
  id: string;
  name: string;
  description: string;
  objectives: QuestObjective[];
  readyToTurnIn?: boolean;
  giverMobId?: string;
}

export interface GainEvent {
  type: string;
  amount: number;
  source: string | null;
  newLevel: number | null;
  hpGained: number | null;
  manaGained: number | null;
}

/** Server-sent celebration data surfaced by the LevelUpBanner overlay. */
export interface LevelUpNotification {
  id: number;
  previousLevel: number;
  newLevel: number;
  levelsGained: number;
  hpGained: number;
  manaGained: number;
  newAbilities: string[];
  skillPointsAvailable: number;
  isMilestone: boolean;
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
  image?: string | null;
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
  type: RoomFeatureType;
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

/**
 * Top-right toast surfaced when the local player lands a killing blow. Built
 * from `Char.Combat.Event` packets of `type: "kill"` and shown once per kill.
 */
export interface CombatVictoryNotification {
  id: string;
  targetName: string;
  xpGained: number;
  goldGained: number;
  lootedItems: string[];
  receivedAt: number;
}

/**
 * Top-right toast surfaced when the local player disengages from combat via
 * `flee` or the wimpy threshold. Built from `Char.Combat.Event` packets of
 * `type: "flee"`.
 */
export interface FleeNotification {
  id: string;
  targetName: string;
  forced: boolean;
  receivedAt: number;
}

export interface QuestRewardItem {
  itemId: string;
  displayName: string;
  count: number;
}

export interface QuestNotification {
  id: string;
  questId: string;
  questName: string;
  event: "complete" | "update" | "accept";
  receivedAt: number;
  // Populated for "complete" events; null/empty for "update".
  questDescription?: string;
  rewards?: {
    xp: number;
    gold: number;
    currencies: Record<string, number>;
    items: QuestRewardItem[];
  };
}

export interface QuestAvailableObjective {
  description: string;
  count: number;
  current: number;
}

export interface QuestAvailableRewards {
  xp: number;
  gold: number;
  currencies: Record<string, number>;
  items: QuestRewardItem[];
}

export interface QuestAvailableReputation {
  faction: string;
  factionName: string;
  min: number | null;
  max: number | null;
}

export interface QuestAvailable {
  id: string;
  name: string;
  description: string;
  giverMobId: string;
  objectives: QuestAvailableObjective[];
  rewards: QuestAvailableRewards;
  levelRequired: number | null;
  reputationRequired: QuestAvailableReputation | null;
  readyToTurnIn: boolean;
  lockedReason: string | null;
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
  combatant: boolean;
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
  | { state: "name"; demoEnabled: boolean }
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

/**
 * One entry in the `World.Areas` GMCP package — a zone with its known level
 * range. `minLevel`/`maxLevel` are null for zones with no level signal.
 */
export interface WorldArea {
  zone: string;
  minLevel: number | null;
  maxLevel: number | null;
}

/**
 * Optional server-side feature flags sent via `Server.Features`. When a flag is
 * false the client should hide the corresponding UI entry (tab, panel, button).
 */
export interface ServerFeatures {
  dailyQuests: boolean;
  weeklyQuests: boolean;
  globalQuests: boolean;
  autoQuests: boolean;
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

export interface UiFeedbackEntry extends UiFeedback {
  id: string;
  receivedAt: number;
}

export interface CurrencyActivity {
  id: string;
  currencyId: string;
  name: string;
  abbreviation: string;
  delta: number;
  balance: number;
  receivedAt: number;
}

export interface FactionActivity {
  id: string;
  factionId: string;
  name: string;
  delta: number;
  reputation: number;
  tier: string;
  receivedAt: number;
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
  /** Item-only: slot name (e.g. "MAIN_HAND"). */
  slot?: string | null;
  /** Item-only: bonus melee damage. */
  damage?: number | null;
  /** Item-only: bonus armor. */
  armor?: number | null;
  /** Item-only: merchant base price in gold. */
  basePrice?: number | null;
  /** Item-only: stat modifiers (e.g. {STR: 2, INT: -1}). */
  stats?: Record<string, number> | null;
  /** Item-only: active enchantment names. */
  enchantments?: string[] | null;
  /** Item-only: true if usable/consumable. */
  consumable?: boolean | null;
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

export interface HousingRoomInfo {
  templateId: string;
  title: string;
  description: string;
}

export interface HousingTemplate {
  id: string;
  title: string;
  description: string;
  cost: number;
  owned: boolean;
  isEntry: boolean;
}

export interface HousingInfo {
  hasHouse: boolean;
  ownerName: string | null;
  rooms: HousingRoomInfo[];
  available: boolean;
}

export interface FactionStanding {
  id: string;
  name: string;
  reputation: number;
  tier: string;
}

export interface TrainerAbility {
  id: string;
  name: string;
  description: string;
  skillPointCost: number;
  levelRequired: number;
  manaCost: number;
  cooldownMs: number;
  targetType: string;
  effectType: string;
  image: string | null;
  /** True when the player cannot currently learn this ability. */
  locked: boolean;
  /** Human-readable reason the ability is locked (e.g. "Requires level 10"), or null when unlocked. */
  lockReason: string | null;
}

/** One class taught by a trainer. Multi-class trainers emit one entry per class. */
export interface TrainerClass {
  className: string;
  classUnlocked: boolean;
  abilities: TrainerAbility[];
}

export interface TrainerData {
  trainerId: string;
  name: string;
  image: string | null;
  availableSkillPoints: number;
  multiclassMinLevel: number;
  /** Gold cost for *this player's next* unlock; already scaled by the multiplier. */
  multiclassGoldCost: number;
  /** Hard cap on the player's total unlocked classes (including starter). */
  multiclassMaxClasses: number;
  /** Player's current unlocked-class count (including starter). */
  multiclassUnlockedCount: number;
  /** One entry per class this trainer teaches. Always non-empty. */
  classes: TrainerClass[];
}

export interface PetState {
  active: boolean;
  name?: string;
  hp?: number;
  maxHp?: number;
  minDamage?: number;
  maxDamage?: number;
  armor?: number;
  image?: string;
}

export interface BankItem {
  id: string;
  name: string;
  keyword: string;
  image?: string | null;
}

export interface BankState {
  gold: number;
  items: BankItem[];
  maxItems: number;
}

export interface StylistRace {
  id: string;
  displayName: string;
  description: string;
  image: string;
  statMods: Record<string, number>;
}

export interface StylistState {
  currentRace: string;
  feeGold: number;
  playerGold: number;
  races: StylistRace[];
}

export interface DailyQuestEntry {
  index: number;
  type: string;
  description: string;
  current: number;
  required: number;
  completed: boolean;
  goldReward: number;
  xpReward: number;
}

export interface DailyQuestBoard {
  quests: DailyQuestEntry[];
  streakDays: number;
}

export interface AutoQuest {
  active: boolean;
  targetMobName?: string;
  killsRequired?: number;
  killsCompleted?: number;
  rewardGold?: number;
  rewardXp?: number;
  timeRemainingMs?: number;
}

export interface GlobalQuestLeaderboardEntry {
  rank: number;
  name: string;
  progress: number;
}

export interface GlobalQuest {
  active: boolean;
  objective?: string;
  objectiveType?: string;
  targetCount?: number;
  playerProgress?: number;
  endsAtMs?: number;
  completed?: boolean;
  leaderboard?: GlobalQuestLeaderboardEntry[];
}

export interface LotteryInfo {
  jackpot: number;
  totalTickets: number;
  playerTickets: number;
  nextDrawingMs: number;
  ticketCost: number;
  maxTicketsPerPlayer: number;
  diceMinBet: number;
  diceMaxBet: number;
  diceWinMultiplier: number;
  /** Roll this value or less on a d100 to win. */
  diceWinThreshold: number;
  diceCooldownMs: number;
}

/** One resolved tavern dice roll, from the Lottery.Gamble GMCP package. */
export interface DiceGambleResult {
  outcome: "win" | "lose";
  bet: number;
  payout: number;
  roll: number;
  needed: number;
  cooldownMs: number;
  /** Client-side monotonic id so the panel can animate each new roll. */
  seq: number;
}

export interface GuildHallRoom {
  id: string;
  template: string;
  title: string;
}

export interface GuildHallInfo {
  guildName: string;
  rooms: GuildHallRoom[];
  membersInHall: number;
  maxRooms: number;
}

export interface DuelChallenge {
  challengerName: string;
  targetName: string;
  direction: "incoming" | "outgoing";
}

export interface DuelState {
  active: boolean;
  opponentName?: string;
  startedAtMs?: number;
}

export interface DungeonInfo {
  active: boolean;
  instanceId?: string;
  name?: string;
  difficulty?: string;
  totalRooms?: number;
  completed?: boolean;
  memberCount?: number;
}

export interface DungeonCatalogDifficulty {
  id: string;
  label: string;
  summary: string;
}

export interface DungeonCatalogEntry {
  id: string;
  name: string;
  description: string;
  minLevel: number;
  portalHint?: string;
  difficulties: DungeonCatalogDifficulty[];
}

export interface PrestigePerk {
  rank: number;
  type: string;
  description: string;
  earned: boolean;
}

export interface PrestigeInfo {
  enabled: boolean;
  requiredLevel: number;
  currentRank: number;
  maxRank: number;
  availableXp: number;
  nextRankCost: number | null;
  perks: PrestigePerk[];
}
