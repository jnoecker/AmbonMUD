import type { Dispatch } from "react";
import type { CoreAction } from "../state/CoreContext";
import type { CombatAction } from "../state/CombatContext";
import type { InventoryAction } from "../state/InventoryContext";
import type { SocialAction } from "../state/SocialContext";
import type { WorldAction } from "../state/WorldContext";
import type { QuestAction } from "../state/QuestContext";
import type { UIAction } from "../state/UIContext";
import type {
  ChatChannel,
  CombatEventData,
  CompletedAchievement,
  CraftingResult,
  DailyQuestEntry,
  DialogueChoice,
  GainEvent,
  GlobalQuestLeaderboardEntry,
  GroupMember,
  GuildHallRoom,
  HousingRoomInfo,
  InProgressAchievement,
  ItemSummary,
  LeaderboardEntry,
  LoginErrorState,
  LoginPromptState,
  LookTargetInfo,
  MailNotification,
  PrestigePerk,
  RoomMob,
  ShopItem,
  SpriteEntry,
  StatEntry,
  StatusEffect,
  TrainerAbility,
  TrainerData,
  UiFeedback,
  ZoneInstanceItem,
} from "../types";
import { safeNumber } from "../utils";

export interface GmcpDispatchMap {
  core: Dispatch<CoreAction>;
  combat: Dispatch<CombatAction>;
  inventory: Dispatch<InventoryAction>;
  social: Dispatch<SocialAction>;
  world: Dispatch<WorldAction>;
  quest: Dispatch<QuestAction>;
  ui: Dispatch<UIAction>;
  // Non-dispatch utilities that remain imperative:
  updateMap: (roomId: string, exits: Record<string, string>, title: string, image: string | null, mapX: number, mapY: number, housing?: boolean) => void;
  loadZoneMap: (zone: string, rooms: Array<{ id: string; x: number; y: number; exits: Record<string, string> }>) => void;
  sendGmcp: (pkg: string, payload: unknown) => boolean;
  pushCombatEvent: (event: CombatEventData) => void;
  pushGainEvent: (event: GainEvent) => void;
  pushCraftingResult: (result?: CraftingResult) => void;
  pushMailNotification: (notification?: MailNotification) => void;
  resumeTokenRef: { current: string | null };
  pendingAuthCharRef: { current: string | null };
  failedAuthCharRef: { current: string | null };
}

let combatLogIdCounter = 0;

const CHAT_CHANNEL_SET = new Set<ChatChannel>(["say", "tell", "gossip", "shout", "ooc", "gtell", "gchat"]);

function isChatChannel(value: string): value is ChatChannel {
  return CHAT_CHANNEL_SET.has(value as ChatChannel);
}

function parseMobEffects(raw: unknown): StatusEffect[] | undefined {
  if (!Array.isArray(raw)) return undefined;
  const effects = raw
    .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
    .map((e) => ({
      name: String(e.name ?? ""),
      type: String(e.type ?? ""),
      remainingMs: safeNumber(e.remainingMs),
      stacks: safeNumber(e.stacks, 1),
    }));
  return effects.length > 0 ? effects : undefined;
}

function parseMob(packet: Record<string, unknown>): RoomMob {
  return {
    id: typeof packet.id === "string" ? packet.id : `${Date.now()}-${Math.random()}`,
    name: typeof packet.name === "string" ? packet.name : "Unknown mob",
    description: typeof packet.description === "string" ? packet.description : undefined,
    hp: safeNumber(packet.hp),
    maxHp: Math.max(1, safeNumber(packet.maxHp, 1)),
    image: typeof packet.image === "string" ? packet.image : null,
    video: typeof packet.video === "string" ? packet.video : null,
    effects: parseMobEffects(packet.effects),
  };
}

export function applyGmcpPackage(
  pkg: string,
  data: unknown,
  ctx: GmcpDispatchMap,
) {
  switch (pkg) {
    case "Char.StatusVars": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.core({
        type: "SET_STATUS_VAR_LABELS",
        payload: {
          hp: typeof packet.hp === "string" ? packet.hp : "HP",
          maxHp: typeof packet.maxHp === "string" ? packet.maxHp : "Max HP",
          mana: typeof packet.mana === "string" ? packet.mana : "Mana",
          maxMana: typeof packet.maxMana === "string" ? packet.maxMana : "Max Mana",
          level: typeof packet.level === "string" ? packet.level : "Level",
          xp: typeof packet.xp === "string" ? packet.xp : "XP",
        },
      });
      break;
    }

    case "Char.Vitals": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.core({
        type: "SET_VITALS",
        payload: {
          hp: safeNumber(packet.hp),
          maxHp: safeNumber(packet.maxHp, 1),
          mana: safeNumber(packet.mana),
          maxMana: safeNumber(packet.maxMana, 1),
          level: typeof packet.level === "number" ? packet.level : null,
          xp: safeNumber(packet.xp),
          xpIntoLevel: safeNumber(packet.xpIntoLevel),
          xpToNextLevel: packet.xpToNextLevel === null ? null : safeNumber(packet.xpToNextLevel),
          gold: safeNumber(packet.gold),
          inCombat: packet.inCombat === true,
          prestigeLevel: safeNumber(packet.prestigeLevel, 0),
          prestigeXpAvailable: packet.prestigeXpAvailable != null ? safeNumber(packet.prestigeXpAvailable) : null,
          prestigeNextCost: packet.prestigeNextCost != null ? safeNumber(packet.prestigeNextCost) : null,
        },
      });
      break;
    }

    case "Char.Combat": {
      const packet = data as Partial<Record<string, unknown>>;
      const targetId = typeof packet.targetId === "string" ? packet.targetId : null;
      if (targetId === null) {
        ctx.combat({ type: "SET_COMBAT_TARGET", payload: null });
      } else {
        ctx.combat({
          type: "SET_COMBAT_TARGET",
          payload: {
            targetId,
            targetName: typeof packet.targetName === "string" ? packet.targetName : null,
            targetHp: typeof packet.targetHp === "number" ? packet.targetHp : null,
            targetMaxHp: typeof packet.targetMaxHp === "number" ? packet.targetMaxHp : null,
            targetImage: typeof packet.targetImage === "string" ? packet.targetImage : null,
          },
        });
      }
      break;
    }

    case "Char.Name": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.core({
        type: "SET_CHARACTER",
        payload: {
          name: typeof packet.name === "string" && packet.name.length > 0 ? packet.name : "-",
          gender: typeof packet.gender === "string" ? packet.gender : "",
          race: typeof packet.race === "string" ? packet.race : "",
          className: typeof packet.class === "string" ? packet.class : "",
          level: typeof packet.level === "number" ? packet.level : null,
          sprite: typeof packet.sprite === "string" ? packet.sprite : null,
          isStaff: packet.isStaff === true,
        },
      });
      // Login complete — dismiss modal
      ctx.core({ type: "SET_LOGIN_PROMPT", payload: null });
      ctx.core({ type: "SET_LOGIN_ERROR", payload: null });
      break;
    }

    case "Char.Sprites": {
      const packet = data as Partial<Record<string, unknown>>;
      const active = typeof packet.active === "string" ? packet.active : null;
      const rawSprites = Array.isArray(packet.sprites) ? packet.sprites : [];
      const sprites: SpriteEntry[] = rawSprites
        .filter((s): s is Record<string, unknown> => s !== null && typeof s === "object")
        .map((s) => ({
          imageId: typeof s.imageId === "string" ? s.imageId : "",
          displayName: typeof s.displayName === "string" ? s.displayName : "",
          category: typeof s.category === "string" ? s.category : "",
          imagePath: typeof s.imagePath === "string" ? s.imagePath : "",
        }))
        .filter((s) => s.imageId.length > 0);
      ctx.core({ type: "SET_SPRITE_LIST", payload: { active, sprites } });
      break;
    }

    case "Room.Info": {
      const packet = data as Partial<Record<string, unknown>>;
      const exits = packet.exits && typeof packet.exits === "object" ? (packet.exits as Record<string, string>) : {};
      const id = typeof packet.id === "string" ? packet.id : null;
      const mapX = typeof packet.mapX === "number" ? packet.mapX : 0;
      const mapY = typeof packet.mapY === "number" ? packet.mapY : 0;
      const title = typeof packet.title === "string" && packet.title.length > 0 ? packet.title : "-";
      const description = typeof packet.description === "string" ? packet.description : "";
      const image = typeof packet.image === "string" ? packet.image : null;
      const video = typeof packet.video === "string" ? packet.video : null;
      const music = typeof packet.music === "string" ? packet.music : null;
      const ambient = typeof packet.ambient === "string" ? packet.ambient : null;
      const station = typeof packet.station === "string" ? packet.station : null;
      const trainer = typeof packet.trainer === "string" ? packet.trainer : null;
      const housing = packet.housing === true;
      const housingOwner = typeof packet.housingOwner === "string" ? packet.housingOwner : null;
      const graphical = packet.graphical === true;
      const bank = packet.bank === true;
      const tavern = packet.tavern === true;

      // Cross-context: clear dialogue and available quests on room change
      ctx.world({ type: "SET_DIALOGUE", payload: null });
      ctx.quest({ type: "SET_QUESTS_AVAILABLE", payload: [] });

      ctx.world({
        type: "SET_ROOM",
        payload: { id, title, description, exits, image, video, music, ambient, station, trainer, mapX, mapY, housing, housingOwner, graphical, bank, tavern },
      });

      if (id) {
        ctx.updateMap(id, exits, title === "-" ? "" : title, image, mapX, mapY, housing);
      }
      break;
    }

    case "Zone.Map": {
      const packet = data as Partial<Record<string, unknown>>;
      const zone = typeof packet.zone === "string" ? packet.zone : "";
      const rooms = Array.isArray(packet.rooms)
        ? packet.rooms
            .filter((r): r is Record<string, unknown> => typeof r === "object" && r !== null)
            .map((r) => ({
              id: typeof r.id === "string" ? r.id : "",
              x: typeof r.x === "number" ? r.x : 0,
              y: typeof r.y === "number" ? r.y : 0,
              exits: r.exits && typeof r.exits === "object" ? (r.exits as Record<string, string>) : {},
            }))
        : [];
      if (zone && rooms.length > 0) {
        ctx.loadZoneMap(zone, rooms);
      }
      break;
    }

    case "Zone.Instances": {
      const packet = data as Partial<Record<string, unknown>>;
      const zone = typeof packet.zone === "string" ? packet.zone : null;
      const currentEngineId = typeof packet.currentEngineId === "string" ? packet.currentEngineId : null;
      const instances: ZoneInstanceItem[] = Array.isArray(packet.instances)
        ? packet.instances
          .filter((i): i is Record<string, unknown> => typeof i === "object" && i !== null)
          .map((i) => ({
            engineId: String(i.engineId ?? ""),
            playerCount: safeNumber(i.playerCount),
            capacity: safeNumber(i.capacity, 200),
            isCurrent: i.isCurrent === true,
          }))
        : [];
      ctx.world({ type: "SET_ZONE_INSTANCES", payload: { zone, currentEngineId, instances } });
      break;
    }

    case "Char.Equipment.Slots": {
      if (!Array.isArray(data)) break;
      ctx.inventory({
        type: "SET_EQUIPMENT_SLOT_DEFS",
        payload: data
          .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
          .map((e) => ({
            id: typeof e.id === "string" ? e.id : "",
            displayName: typeof e.displayName === "string" ? e.displayName : "",
            order: typeof e.order === "number" ? e.order : 0,
            x: typeof e.x === "number" ? e.x : 50,
            y: typeof e.y === "number" ? e.y : 50,
          }))
          .sort((a, b) => a.order - b.order),
      });
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
              keyword: typeof entry.keyword === "string" ? entry.keyword : (typeof entry.name === "string" ? entry.name : "item"),
              slot: typeof entry.slot === "string" ? entry.slot : null,
              basePrice: typeof entry.basePrice === "number" ? entry.basePrice : undefined,
              image: typeof entry.image === "string" ? entry.image : null,
              video: typeof entry.video === "string" ? entry.video : null,
              stats: entry.stats && typeof entry.stats === "object" ? entry.stats as Record<string, number> : undefined,
              enchantments: Array.isArray(entry.enchantments) ? entry.enchantments.filter((e): e is string => typeof e === "string") : undefined,
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
            keyword: typeof item.keyword === "string" ? item.keyword : (typeof item.name === "string" ? item.name : "item"),
            slot,
            image: typeof item.image === "string" ? item.image : null,
            video: typeof item.video === "string" ? item.video : null,
            stats: item.stats && typeof item.stats === "object" ? item.stats as Record<string, number> : undefined,
            enchantments: Array.isArray(item.enchantments) ? item.enchantments.filter((e): e is string => typeof e === "string") : undefined,
          };
        }
      }

      ctx.inventory({ type: "SET_INVENTORY", payload: inventoryList });
      ctx.inventory({ type: "SET_EQUIPMENT", payload: equipmentMap });
      break;
    }

    case "Char.Items.Add": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.inventory({
        type: "ADD_INVENTORY_ITEM",
        payload: {
          id: typeof packet.id === "string" ? packet.id : `${Date.now()}-${Math.random()}`,
          name: typeof packet.name === "string" ? packet.name : "Unknown item",
          keyword: typeof packet.keyword === "string" ? packet.keyword : (typeof packet.name === "string" ? packet.name : "item"),
          slot: typeof packet.slot === "string" ? packet.slot : null,
          basePrice: typeof packet.basePrice === "number" ? packet.basePrice : undefined,
          image: typeof packet.image === "string" ? packet.image : null,
          video: typeof packet.video === "string" ? packet.video : null,
          stats: packet.stats && typeof packet.stats === "object" ? packet.stats as Record<string, number> : undefined,
          enchantments: Array.isArray(packet.enchantments) ? (packet.enchantments as unknown[]).filter((e): e is string => typeof e === "string") : undefined,
        },
      });
      break;
    }

    case "Char.Items.Remove": {
      const packet = data as Partial<Record<string, unknown>>;
      if (typeof packet.id !== "string") break;
      ctx.inventory({ type: "REMOVE_INVENTORY_ITEM", payload: packet.id });
      break;
    }

    case "Room.Items": {
      if (!Array.isArray(data)) {
        ctx.world({ type: "SET_ROOM_ITEMS", payload: [] });
        break;
      }
      ctx.world({
        type: "SET_ROOM_ITEMS",
        payload: data
          .filter((entry): entry is Record<string, unknown> => typeof entry === "object" && entry !== null)
          .map((entry, index) => ({
            id: typeof entry.id === "string" ? entry.id : `room-item-${index}-${Date.now()}`,
            name: typeof entry.name === "string" ? entry.name : "Unknown item",
            description: typeof entry.description === "string" ? entry.description : undefined,
            image: typeof entry.image === "string" ? entry.image : null,
            video: typeof entry.video === "string" ? entry.video : null,
          })),
      });
      break;
    }

    case "Room.LookTarget": {
      const packet = data as Partial<Record<string, unknown>>;
      const targetType = typeof packet.type === "string" ? packet.type : "item";
      ctx.ui({
        type: "SET_LOOK_TARGET",
        payload: {
          type: targetType as LookTargetInfo["type"],
          name: typeof packet.name === "string" ? packet.name : "Unknown",
          description: typeof packet.description === "string" ? packet.description : "",
          image: typeof packet.image === "string" ? packet.image : null,
          level: typeof packet.level === "number" ? packet.level : null,
          race: typeof packet.race === "string" ? packet.race : null,
          playerClass: typeof packet.class === "string" ? (packet.class as string) : null,
          receivedAt: Date.now(),
        },
      });
      break;
    }

    case "Room.Players": {
      if (!Array.isArray(data)) {
        ctx.world({ type: "SET_PLAYERS", payload: [] });
        break;
      }
      ctx.world({
        type: "SET_PLAYERS",
        payload: data
          .filter((entry): entry is Record<string, unknown> => typeof entry === "object" && entry !== null)
          .map((entry) => ({
            name: typeof entry.name === "string" ? entry.name : "Unknown",
            level: safeNumber(entry.level),
          })),
      });
      break;
    }

    case "Room.AddPlayer": {
      const packet = data as Partial<Record<string, unknown>>;
      const name = packet.name;
      if (typeof name !== "string") break;
      ctx.world({ type: "ADD_PLAYER", payload: { name, level: safeNumber(packet.level) } });
      break;
    }

    case "Room.RemovePlayer": {
      const packet = data as Partial<Record<string, unknown>>;
      if (typeof packet.name !== "string") break;
      ctx.world({ type: "REMOVE_PLAYER", payload: packet.name });
      break;
    }

    case "Room.Mobs": {
      if (!Array.isArray(data)) {
        ctx.world({ type: "SET_MOBS", payload: [] });
        break;
      }
      ctx.world({
        type: "SET_MOBS",
        payload: data
          .filter((entry): entry is Record<string, unknown> => typeof entry === "object" && entry !== null)
          .map((entry) => parseMob(entry)),
      });
      break;
    }

    case "Room.AddMob": {
      const packet = data as Partial<Record<string, unknown>>;
      const id = packet.id;
      if (typeof id !== "string") break;
      ctx.world({ type: "ADD_MOB", payload: parseMob(packet as Record<string, unknown>) });
      break;
    }

    case "Room.UpdateMob": {
      const packet = data as Partial<Record<string, unknown>>;
      if (typeof packet.id !== "string") break;
      const updatedHp = safeNumber(packet.hp);
      const updatedMaxHp = Math.max(1, safeNumber(packet.maxHp, 1));
      const effects = parseMobEffects(packet.effects);
      // We dispatch UPDATE_MOB with the partial data; the reducer merges via id match
      // Since WorldContext UPDATE_MOB replaces the full mob, we need to construct the full mob
      // But we don't have prev state here. We need a PATCH_MOB action instead.
      // For now, dispatch the update data and let the mob merge happen.
      // Actually WorldContext.UPDATE_MOB does a full replace by id. We'll need to
      // construct a complete mob object. But we're missing the old fields.
      // Let's add a PATCH_MOB action to WorldContext that only updates provided fields.
      // Simpler approach: use the existing world dispatch with a custom action.
      // We'll handle this by dispatching a dedicated update that the reducer can handle.
      ctx.world({
        type: "UPDATE_MOB",
        payload: {
          id: packet.id,
          name: typeof packet.name === "string" ? packet.name : "Unknown mob",
          description: typeof packet.description === "string" ? packet.description : undefined,
          hp: safeNumber(packet.hp, 0),
          maxHp: Math.max(1, safeNumber(packet.maxHp, 1)),
          image: typeof packet.image === "string" ? packet.image : null,
          video: typeof packet.video === "string" ? packet.video : null,
          effects,
        },
      });
      // Cross-context: keep combat target HP in sync
      ctx.combat({ type: "UPDATE_COMBAT_TARGET_HP", payload: { targetId: packet.id, hp: updatedHp, maxHp: updatedMaxHp } });
      break;
    }

    case "Room.RemoveMob": {
      const packet = data as Partial<Record<string, unknown>>;
      if (typeof packet.id !== "string") break;
      ctx.world({ type: "REMOVE_MOB", payload: packet.id });
      // Cross-context: clear combat target if the removed mob was our target
      ctx.combat({ type: "CLEAR_COMBAT_TARGET_IF", payload: packet.id });
      break;
    }

    case "Char.StatusEffects": {
      if (!Array.isArray(data)) {
        ctx.combat({ type: "SET_EFFECTS", payload: [] });
        break;
      }
      ctx.combat({
        type: "SET_EFFECTS",
        payload: data
          .filter((entry): entry is Record<string, unknown> => typeof entry === "object" && entry !== null)
          .map((entry) => ({
            name: typeof entry.name === "string" ? entry.name : "Effect",
            type: typeof entry.type === "string" ? entry.type : "BUFF",
            stacks: Math.max(1, safeNumber(entry.stacks, 1)),
            remainingMs: Math.max(0, safeNumber(entry.remainingMs, 0)),
          })),
      });
      break;
    }

    case "Char.Skills": {
      const now = Date.now();
      if (!Array.isArray(data)) {
        ctx.combat({ type: "SET_SKILLS", payload: [] });
        break;
      }
      ctx.combat({
        type: "SET_SKILLS",
        payload: data
          .filter((entry): entry is Record<string, unknown> => typeof entry === "object" && entry !== null)
          .map((entry, index) => ({
            id: typeof entry.id === "string" ? entry.id : `skill-${index}`,
            name: typeof entry.name === "string" ? entry.name : "Unknown skill",
            description: typeof entry.description === "string" ? entry.description : "",
            manaCost: Math.max(0, safeNumber(entry.manaCost)),
            cooldownMs: Math.max(0, safeNumber(entry.cooldownMs)),
            cooldownRemainingMs: Math.max(0, safeNumber(entry.cooldownRemainingMs)),
            levelRequired: Math.max(1, safeNumber(entry.levelRequired, 1)),
            targetType: typeof entry.targetType === "string" ? entry.targetType : "ENEMY",
            effectType: typeof entry.effectType === "string" ? entry.effectType : "DIRECT_DAMAGE",
            classRestriction: typeof entry.classRestriction === "string" ? entry.classRestriction : null,
            image: typeof entry.image === "string" ? entry.image : null,
            receivedAt: now,
          })),
      });
      break;
    }

    case "Char.Achievements": {
      const packet = data as Partial<Record<string, unknown>>;
      const completed: CompletedAchievement[] = Array.isArray(packet.completed)
        ? packet.completed
            .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
            .map((e) => ({
              id: typeof e.id === "string" ? e.id : "",
              name: typeof e.name === "string" ? e.name : "Unknown",
              title: typeof e.title === "string" ? e.title : null,
            }))
        : [];
      const inProgress: InProgressAchievement[] = Array.isArray(packet.inProgress)
        ? packet.inProgress
            .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
            .map((e) => ({
              id: typeof e.id === "string" ? e.id : "",
              name: typeof e.name === "string" ? e.name : "Unknown",
              current: safeNumber(e.current),
              required: safeNumber(e.required, 1),
            }))
        : [];
      ctx.quest({ type: "SET_ACHIEVEMENTS", payload: { completed, inProgress } });
      break;
    }

    case "Group.Info": {
      const packet = data as Partial<Record<string, unknown>>;
      const leader = typeof packet.leader === "string" ? packet.leader : null;
      const members: GroupMember[] = Array.isArray(packet.members)
        ? packet.members
            .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
            .map((e) => ({
              name: typeof e.name === "string" ? e.name : "Unknown",
              level: safeNumber(e.level, 1),
              hp: safeNumber(e.hp),
              maxHp: safeNumber(e.maxHp, 1),
              mana: safeNumber(e.mana),
              maxMana: safeNumber(e.maxMana, 1),
              playerClass: typeof e.class === "string" ? e.class : "",
            }))
        : [];
      ctx.social({ type: "SET_GROUP_INFO", payload: { leader, members } });
      // Clear pending invite when group info arrives (player joined a group)
      if (leader) ctx.social({ type: "SET_PENDING_GROUP_INVITE", payload: null });
      break;
    }

    case "Group.Invite": {
      const packet = data as Partial<Record<string, unknown>>;
      const inviterName = typeof packet.inviterName === "string" ? packet.inviterName : "Unknown";
      ctx.social({ type: "SET_PENDING_GROUP_INVITE", payload: { inviterName, receivedAt: Date.now() } });
      break;
    }

    case "Guild.Info": {
      const packet = data as Partial<Record<string, unknown>>;
      const guildName = typeof packet.name === "string" ? packet.name : null;
      ctx.social({
        type: "SET_GUILD_INFO",
        payload: {
          name: guildName,
          tag: typeof packet.tag === "string" ? packet.tag : null,
          rank: typeof packet.rank === "string" ? packet.rank : null,
          motd: typeof packet.motd === "string" ? packet.motd : null,
          memberCount: safeNumber(packet.memberCount),
          maxSize: safeNumber(packet.maxSize, 50),
        },
      });
      // Clear pending invite when guild info arrives (player joined a guild)
      if (guildName) ctx.social({ type: "SET_PENDING_GUILD_INVITE", payload: null });
      break;
    }

    case "Guild.Invite": {
      const packet = data as Partial<Record<string, unknown>>;
      const inviterName = typeof packet.inviterName === "string" ? packet.inviterName : "Unknown";
      const guildName = typeof packet.guildName === "string" ? packet.guildName : "Unknown";
      const guildTag = typeof packet.guildTag === "string" ? packet.guildTag : "";
      ctx.social({ type: "SET_PENDING_GUILD_INVITE", payload: { inviterName, guildName, guildTag, receivedAt: Date.now() } });
      break;
    }

    case "Guild.Members": {
      if (!Array.isArray(data)) {
        ctx.social({ type: "SET_GUILD_MEMBERS", payload: [] });
        break;
      }
      ctx.social({
        type: "SET_GUILD_MEMBERS",
        payload: data
          .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
          .map((e) => ({
            name: typeof e.name === "string" ? e.name : "Unknown",
            rank: typeof e.rank === "string" ? e.rank : "MEMBER",
            online: e.online === true,
            level: typeof e.level === "number" ? e.level : null,
          })),
      });
      break;
    }

    case "Guild.Chat": {
      const packet = data as Partial<Record<string, unknown>>;
      const sender = typeof packet.sender === "string" && packet.sender.length > 0 ? packet.sender : "Unknown";
      const message = typeof packet.message === "string" ? packet.message.trim() : "";
      if (message.length === 0) break;

      ctx.social({
        type: "PUSH_CHAT_MESSAGE",
        payload: {
          id: `${Date.now()}-${Math.random()}`,
          channel: "gchat" as ChatChannel,
          sender,
          message,
          receivedAt: Date.now(),
        },
      });
      break;
    }

    case "Friends.List": {
      if (!Array.isArray(data)) {
        ctx.social({ type: "SET_FRIENDS", payload: [] });
        break;
      }
      ctx.social({
        type: "SET_FRIENDS",
        payload: data
          .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
          .map((e) => ({
            name: typeof e.name === "string" ? e.name : "Unknown",
            online: e.online === true,
            level: typeof e.level === "number" ? e.level : null,
            zone: typeof e.zone === "string" ? e.zone : null,
          })),
      });
      break;
    }

    case "Friends.Online": {
      const packet = data as Partial<Record<string, unknown>>;
      const name = typeof packet.name === "string" ? packet.name : null;
      if (!name) break;
      const level = typeof packet.level === "number" ? packet.level : null;
      ctx.social({ type: "UPDATE_FRIEND_ONLINE", payload: { name, level } });
      ctx.ui({
        type: "PUSH_FRIEND_NOTIFICATION",
        payload: { id: `${Date.now()}-${Math.random()}`, name, event: "online", receivedAt: Date.now() },
      });
      break;
    }

    case "Friends.Offline": {
      const packet = data as Partial<Record<string, unknown>>;
      const name = typeof packet.name === "string" ? packet.name : null;
      if (!name) break;
      ctx.social({ type: "UPDATE_FRIEND_OFFLINE", payload: name });
      ctx.ui({
        type: "PUSH_FRIEND_NOTIFICATION",
        payload: { id: `${Date.now()}-${Math.random()}`, name, event: "offline", receivedAt: Date.now() },
      });
      break;
    }

    case "Comm.Channel": {
      const packet = data as Partial<Record<string, unknown>>;
      const incomingChannel = typeof packet.channel === "string" ? packet.channel.toLowerCase() : "";
      const isWhisper = incomingChannel === "whisper";
      const mappedChannel = isWhisper ? "tell" : incomingChannel;
      if (!isChatChannel(mappedChannel)) break;

      const sender = typeof packet.sender === "string" && packet.sender.length > 0 ? packet.sender : "Unknown";
      const message = typeof packet.message === "string" ? packet.message.trim() : "";
      if (message.length === 0) break;

      ctx.social({
        type: "PUSH_CHAT_MESSAGE",
        payload: {
          id: `${Date.now()}-${Math.random()}`,
          channel: mappedChannel,
          sender,
          message,
          receivedAt: Date.now(),
          ...(isWhisper && { isWhisper: true }),
        },
      });
      break;
    }

    case "Dialogue.Node": {
      const packet = data as Partial<Record<string, unknown>>;
      const mobName = typeof packet.mobName === "string" ? packet.mobName : "Unknown";
      const text = typeof packet.text === "string" ? packet.text : "";
      const choices: DialogueChoice[] = Array.isArray(packet.choices)
        ? packet.choices
            .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
            .map((e) => ({
              index: typeof e.index === "number" ? e.index : 0,
              text: typeof e.text === "string" ? e.text : "",
            }))
        : [];
      ctx.world({ type: "SET_DIALOGUE", payload: { mobName, text, choices } });
      break;
    }

    case "Dialogue.End": {
      ctx.world({ type: "SET_DIALOGUE", payload: null });
      break;
    }

    case "Char.Combat.Event": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.pushCombatEvent({
        type: typeof packet.type === "string" ? packet.type : "UNKNOWN",
        targetName: typeof packet.targetName === "string" ? packet.targetName : null,
        targetId: typeof packet.targetId === "string" ? packet.targetId : null,
        abilityId: typeof packet.abilityId === "string" ? packet.abilityId : null,
        abilityName: typeof packet.abilityName === "string" ? packet.abilityName : null,
        damage: safeNumber(packet.damage),
        healing: safeNumber(packet.healing),
        absorbed: safeNumber(packet.absorbed),
        shieldRemaining: safeNumber(packet.shieldRemaining),
        sourceIsPlayer: packet.sourceIsPlayer === true,
        effectName: typeof packet.effectName === "string" ? packet.effectName : null,
        killerName: typeof packet.killerName === "string" ? packet.killerName : null,
        killerIsPlayer: packet.killerIsPlayer === true,
        attackerName: typeof packet.attackerName === "string" ? packet.attackerName : null,
        xpGained: safeNumber(packet.xpGained),
        goldGained: safeNumber(packet.goldGained),
      });
      break;
    }

    case "Char.Stats": {
      const packet = data as Partial<Record<string, unknown>>;
      const rawStats = Array.isArray(packet.stats) ? packet.stats : [];
      const stats: StatEntry[] = rawStats
        .filter((s): s is Record<string, unknown> => typeof s === "object" && s !== null)
        .map((s) => ({
          id: typeof s.id === "string" ? s.id : "",
          name: typeof s.name === "string" ? s.name : "",
          abbrev: typeof s.abbrev === "string" ? s.abbrev : "",
          base: safeNumber(s.base),
          effective: safeNumber(s.effective),
        }));
      ctx.core({
        type: "SET_CHAR_STATS",
        payload: {
          stats,
          baseDamageMin: safeNumber(packet.baseDamageMin),
          baseDamageMax: safeNumber(packet.baseDamageMax),
          armor: safeNumber(packet.armor),
          dodgePercent: safeNumber(packet.dodgePercent),
        },
      });
      break;
    }

    case "Quest.List": {
      if (!Array.isArray(data)) {
        ctx.quest({ type: "SET_QUESTS", payload: [] });
        break;
      }
      const parsedQuests = data
        .filter((entry): entry is Record<string, unknown> => typeof entry === "object" && entry !== null)
        .map((entry) => ({
          id: typeof entry.id === "string" ? entry.id : "",
          name: typeof entry.name === "string" ? entry.name : "Unknown Quest",
          description: typeof entry.description === "string" ? entry.description : "",
          objectives: Array.isArray(entry.objectives)
            ? entry.objectives
                .filter((o): o is Record<string, unknown> => typeof o === "object" && o !== null)
                .map((o) => ({
                  description: typeof o.description === "string" ? o.description : "",
                  current: safeNumber(o.current),
                  required: safeNumber(o.required, 1),
                  ...(Array.isArray(o.targetRoomIds) && o.targetRoomIds.length > 0 && {
                    targetRoomIds: o.targetRoomIds.filter((r): r is string => typeof r === "string"),
                  }),
                }))
            : [],
        }));
      // SET_QUESTS_AND_FILTER_AVAILABLE sets quests and removes newly-active ones from available
      ctx.quest({ type: "SET_QUESTS_AND_FILTER_AVAILABLE", payload: parsedQuests });
      break;
    }

    case "Quest.Update": {
      const packet = data as Partial<Record<string, unknown>>;
      const questId = typeof packet.questId === "string" ? packet.questId : null;
      const objIndex = typeof packet.objectiveIndex === "number" ? packet.objectiveIndex : -1;
      if (!questId || objIndex < 0) break;
      ctx.quest({
        type: "UPDATE_QUEST_OBJECTIVE",
        payload: {
          questId,
          objectiveIndex: objIndex,
          current: safeNumber(packet.current),
          required: safeNumber(packet.required),
        },
      });
      break;
    }

    case "Quest.Complete": {
      const packet = data as Partial<Record<string, unknown>>;
      const questId = typeof packet.questId === "string" ? packet.questId : null;
      if (!questId) break;
      const questName = typeof packet.questName === "string" ? packet.questName : "Quest";
      ctx.quest({ type: "REMOVE_QUEST", payload: questId });
      ctx.quest({
        type: "PUSH_QUEST_NOTIFICATION",
        payload: {
          id: `${Date.now()}-${Math.random()}`,
          questId,
          questName,
          event: "complete",
          receivedAt: Date.now(),
        },
      });
      break;
    }

    case "Quest.Available": {
      if (!Array.isArray(data)) {
        ctx.quest({ type: "SET_QUESTS_AVAILABLE", payload: [] });
        break;
      }
      ctx.quest({
        type: "SET_QUESTS_AVAILABLE",
        payload: data
          .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
          .map((e) => {
            const objectives = Array.isArray(e.objectives)
              ? e.objectives
                  .filter((o): o is Record<string, unknown> => typeof o === "object" && o !== null)
                  .map((o) => ({
                    description: typeof o.description === "string" ? o.description : "",
                    count: safeNumber(o.count),
                  }))
              : [];
            const rewards = typeof e.rewards === "object" && e.rewards !== null
              ? e.rewards as Record<string, unknown>
              : {};
            return {
              id: typeof e.id === "string" ? e.id : "",
              name: typeof e.name === "string" ? e.name : "",
              description: typeof e.description === "string" ? e.description : "",
              giverMobId: typeof e.giverMobId === "string" ? e.giverMobId : "",
              objectives,
              rewards: {
                xp: safeNumber(rewards.xp),
                gold: safeNumber(rewards.gold),
              },
            };
          }),
      });
      break;
    }

    case "Quest.Daily": {
      const packet = data as Partial<Record<string, unknown>>;
      const rawQuests = Array.isArray(packet.quests) ? packet.quests : [];
      const quests: DailyQuestEntry[] = rawQuests
        .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
        .map((e) => ({
          index: safeNumber(e.index),
          type: typeof e.type === "string" ? e.type : "",
          description: typeof e.description === "string" ? e.description : "",
          current: safeNumber(e.current),
          required: safeNumber(e.required),
          completed: e.completed === true,
          goldReward: safeNumber(e.goldReward),
          xpReward: safeNumber(e.xpReward),
        }));
      ctx.quest({ type: "SET_DAILY_QUESTS", payload: { quests, streakDays: safeNumber(packet.streakDays) } });
      break;
    }

    case "Quest.Weekly": {
      const rawQuests = Array.isArray(data) ? data : [];
      const quests: DailyQuestEntry[] = rawQuests
        .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
        .map((e) => ({
          index: safeNumber(e.index),
          type: typeof e.type === "string" ? e.type : "",
          description: typeof e.description === "string" ? e.description : "",
          current: safeNumber(e.current),
          required: safeNumber(e.required),
          completed: e.completed === true,
          goldReward: safeNumber(e.goldReward),
          xpReward: safeNumber(e.xpReward),
        }));
      ctx.quest({ type: "SET_WEEKLY_QUESTS", payload: quests });
      break;
    }

    case "Quest.Auto": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.quest({
        type: "SET_AUTO_QUEST",
        payload: {
          active: packet.active === true,
          targetMobName: typeof packet.targetMobName === "string" ? packet.targetMobName : undefined,
          killsRequired: typeof packet.killsRequired === "number" ? packet.killsRequired : undefined,
          killsCompleted: typeof packet.killsCompleted === "number" ? packet.killsCompleted : undefined,
          rewardGold: typeof packet.rewardGold === "number" ? packet.rewardGold : undefined,
          rewardXp: typeof packet.rewardXp === "number" ? packet.rewardXp : undefined,
          timeRemainingMs: typeof packet.timeRemainingMs === "number" ? packet.timeRemainingMs : undefined,
        },
      });
      break;
    }

    case "Quest.Global": {
      const packet = data as Partial<Record<string, unknown>>;
      if (packet.active === true) {
        const rawLeaderboard = Array.isArray(packet.leaderboard) ? packet.leaderboard : [];
        const leaderboard: GlobalQuestLeaderboardEntry[] = rawLeaderboard
          .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
          .map((e) => ({
            rank: safeNumber(e.rank),
            name: typeof e.name === "string" ? e.name : "",
            progress: safeNumber(e.progress),
          }));
        ctx.quest({
          type: "SET_GLOBAL_QUEST",
          payload: {
            active: true,
            objective: typeof packet.objective === "string" ? packet.objective : undefined,
            objectiveType: typeof packet.objectiveType === "string" ? packet.objectiveType : undefined,
            targetCount: typeof packet.targetCount === "number" ? packet.targetCount : undefined,
            playerProgress: typeof packet.playerProgress === "number" ? packet.playerProgress : undefined,
            endsAtMs: typeof packet.endsAtMs === "number" ? packet.endsAtMs : undefined,
            completed: packet.completed === true,
            leaderboard,
          },
        });
      } else {
        ctx.quest({ type: "SET_GLOBAL_QUEST", payload: { active: false } });
      }
      break;
    }

    case "Char.Cooldown": {
      const packet = data as Partial<Record<string, unknown>>;
      const abilityId = typeof packet.abilityId === "string" ? packet.abilityId : null;
      const cooldownMs = safeNumber(packet.cooldownMs);
      if (!abilityId || cooldownMs <= 0) break;
      ctx.combat({ type: "UPDATE_SKILL_COOLDOWN", payload: { abilityId, cooldownMs, receivedAt: Date.now() } });
      break;
    }

    case "Char.Gain": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.pushGainEvent({
        type: typeof packet.type === "string" ? packet.type : "unknown",
        amount: safeNumber(packet.amount),
        source: typeof packet.source === "string" ? packet.source : null,
        newLevel: typeof packet.newLevel === "number" ? packet.newLevel : null,
        hpGained: typeof packet.hpGained === "number" ? packet.hpGained : null,
        manaGained: typeof packet.manaGained === "number" ? packet.manaGained : null,
      });
      break;
    }

    case "Room.MobInfo": {
      if (!Array.isArray(data)) {
        ctx.world({ type: "SET_MOB_INFO", payload: [] });
        break;
      }
      ctx.world({
        type: "SET_MOB_INFO",
        payload: data
          .filter((entry): entry is Record<string, unknown> => typeof entry === "object" && entry !== null)
          .map((entry) => ({
            id: typeof entry.id === "string" ? entry.id : "",
            level: safeNumber(entry.level, 1),
            tier: typeof entry.tier === "string" ? entry.tier : "standard",
            questGiver: entry.questGiver === true,
            questAvailable: entry.questAvailable === true,
            questComplete: entry.questComplete === true,
            shopKeeper: entry.shopKeeper === true,
            dialogue: entry.dialogue === true,
            aggressive: entry.aggressive === true,
          })),
      });
      break;
    }

    case "Room.Features": {
      ctx.ui({ type: "SET_CONTAINER_CONTENTS", payload: null });
      if (!Array.isArray(data)) {
        ctx.world({ type: "SET_ROOM_FEATURES", payload: [] });
        break;
      }
      ctx.world({
        type: "SET_ROOM_FEATURES",
        payload: data
          .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
          .map((e) => ({
            id: typeof e.id === "string" ? e.id : "",
            name: typeof e.name === "string" ? e.name : "",
            keyword: typeof e.keyword === "string" ? e.keyword : "",
            type: (e.type === "door" || e.type === "container" || e.type === "lever" || e.type === "sign")
              ? e.type : "sign" as const,
            state: typeof e.state === "string" ? e.state : null,
            direction: typeof e.direction === "string" ? e.direction : null,
            locked: typeof e.locked === "boolean" ? e.locked : null,
            keyRequired: typeof e.keyRequired === "boolean" ? e.keyRequired : null,
            text: typeof e.text === "string" ? e.text : null,
          })),
      });
      break;
    }

    case "Room.ContainerContents": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.ui({
        type: "SET_CONTAINER_CONTENTS",
        payload: {
          featureId: typeof packet.featureId === "string" ? packet.featureId : "",
          name: typeof packet.name === "string" ? packet.name : "",
          keyword: typeof packet.keyword === "string" ? packet.keyword : "",
          items: Array.isArray(packet.items)
            ? packet.items
                .filter((i): i is Record<string, unknown> => typeof i === "object" && i !== null)
                .map((i) => ({ name: typeof i.name === "string" ? i.name : "", keyword: typeof i.keyword === "string" ? i.keyword : "" }))
            : [],
        },
      });
      break;
    }

    case "Shop.List": {
      const packet = data as Partial<Record<string, unknown>>;
      const name = typeof packet.name === "string" ? packet.name : "Shop";
      const sellMultiplier = typeof packet.sellMultiplier === "number" ? packet.sellMultiplier : 0.5;
      const items: ShopItem[] = Array.isArray(packet.items)
        ? packet.items
            .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
            .map((e) => ({
              id: typeof e.id === "string" ? e.id : "",
              name: typeof e.name === "string" ? e.name : "Unknown",
              keyword: typeof e.keyword === "string" ? e.keyword : "",
              description: typeof e.description === "string" ? e.description : "",
              slot: typeof e.slot === "string" ? e.slot : null,
              damage: safeNumber(e.damage),
              armor: safeNumber(e.armor),
              buyPrice: safeNumber(e.buyPrice),
              basePrice: safeNumber(e.basePrice),
              consumable: e.consumable === true,
              image: typeof e.image === "string" ? e.image : null,
              video: typeof e.video === "string" ? e.video : null,
            }))
        : [];
      ctx.inventory({ type: "SET_SHOP", payload: { name, sellMultiplier, items } });
      break;
    }

    case "Shop.Close": {
      ctx.inventory({ type: "SET_SHOP", payload: null });
      // Cross-context: close shop popout if it's currently open
      ctx.ui({ type: "CLOSE_POPOUT_IF", payload: "shop" });
      break;
    }

    case "Server.Assets": {
      const packet = data as Record<string, string>;
      ctx.core({ type: "SET_SERVER_ASSETS", payload: packet });
      break;
    }

    case "Server.Commands": {
      const packet = data as { commands?: unknown[] };
      const commands = (packet.commands ?? [])
        .filter((c): c is Record<string, unknown> => typeof c === "object" && c !== null)
        .map((c) => ({
          name: String(c.name ?? ""),
          usage: String(c.usage ?? ""),
          description: String(c.description ?? ""),
          category: String(c.category ?? ""),
          staff: c.staff === true,
          requiresTarget: c.requiresTarget === true,
        }));
      ctx.core({ type: "SET_SERVER_COMMANDS", payload: commands });
      break;
    }

    case "Server.Broadcast": {
      const packet = data as Partial<Record<string, unknown>>;
      const sender = typeof packet.sender === "string" ? packet.sender : "System";
      const message = typeof packet.message === "string" ? packet.message : "";
      if (message.length > 0) ctx.ui({ type: "PUSH_BROADCAST", payload: { sender, message } });
      break;
    }

    case "Server.EmotePresets": {
      if (!Array.isArray(data)) break;
      ctx.core({
        type: "SET_EMOTE_PRESETS",
        payload: data
          .filter((p): p is Record<string, unknown> => typeof p === "object" && p !== null)
          .map((p) => ({
            label: String(p.label ?? ""),
            emoji: String(p.emoji ?? ""),
            action: String(p.action ?? ""),
          })),
      });
      break;
    }

    case "Server.Who": {
      const packet = data as { players?: unknown[] };
      ctx.social({
        type: "SET_WHO_PLAYERS",
        payload: (packet.players ?? [])
          .filter((p): p is Record<string, unknown> => typeof p === "object" && p !== null)
          .map((p) => ({
            name: String(p.name ?? ""),
            level: safeNumber(p.level),
            race: String(p.race ?? ""),
            playerClass: String(p.class ?? ""),
            title: typeof p.title === "string" ? p.title : null,
            guild: typeof p.guild === "string" ? p.guild : null,
            groupSize: safeNumber(p.groupSize),
            idle: safeNumber(p.idle),
          })),
      });
      break;
    }

    case "Login.Prompt": {
      const packet = data as LoginPromptState;
      // Only attempt auto-authentication on the initial "name" prompt.
      if (packet.state !== "name") {
        ctx.core({ type: "SET_LOGIN_PROMPT", payload: packet });
        ctx.core({ type: "SET_LOGIN_ERROR", payload: null });
        break;
      }
      // Priority: resume token (short-lived reconnect) > auth token (long-lived remember-me)
      if (ctx.resumeTokenRef.current) {
        ctx.core({ type: "SET_RECONNECTING", payload: true });
        ctx.sendGmcp("Session.Resume", { token: ctx.resumeTokenRef.current });
      } else {
        // Check for saved auth tokens in localStorage
        let savedTokens: Record<string, string> = {};
        try {
          savedTokens = JSON.parse(localStorage.getItem("ambonmud_auth_tokens") ?? "{}") as Record<string, string>;
        } catch { /* localStorage unavailable */ }
        const names = Object.keys(savedTokens);

        if (names.length >= 1) {
          // Show character picker with saved characters + "create new" option
          ctx.core({ type: "SET_SAVED_CHARACTERS", payload: names });
          ctx.core({ type: "SET_LOGIN_PROMPT", payload: packet });
          ctx.core({ type: "SET_LOGIN_ERROR", payload: null });
        } else {
          ctx.core({ type: "SET_LOGIN_PROMPT", payload: packet });
          ctx.core({ type: "SET_LOGIN_ERROR", payload: null });
        }
      }
      break;
    }

    case "Session.ResumeToken": {
      const packet = data as { token?: string; expiresIn?: number };
      if (typeof packet.token === "string" && packet.token.length > 0) {
        ctx.resumeTokenRef.current = packet.token;
      }
      break;
    }

    case "Session.ResumeResult": {
      const packet = data as { success?: boolean };
      if (packet.success) {
        ctx.core({ type: "SET_RECONNECTING", payload: false });
        ctx.resumeTokenRef.current = null;
      } else {
        ctx.core({ type: "SET_RECONNECTING", payload: false });
        ctx.resumeTokenRef.current = null;
        ctx.core({ type: "SET_LOGIN_PROMPT", payload: data as LoginPromptState });
        ctx.core({ type: "SET_LOGIN_ERROR", payload: null });
      }
      break;
    }

    case "Session.AuthToken": {
      const packet = data as { token?: string; characterName?: string; expiresInDays?: number };
      if (typeof packet.token === "string" && packet.token.length > 0 && typeof packet.characterName === "string") {
        try {
          const saved = JSON.parse(localStorage.getItem("ambonmud_auth_tokens") ?? "{}") as Record<string, string>;
          saved[packet.characterName] = packet.token;
          localStorage.setItem("ambonmud_auth_tokens", JSON.stringify(saved));
        } catch { /* localStorage unavailable */ }
      }
      break;
    }

    case "Session.AuthResult": {
      const packet = data as { success?: boolean; message?: string };
      if (packet.success) {
        ctx.core({ type: "SET_RECONNECTING", payload: false });
        ctx.pendingAuthCharRef.current = null;
      } else {
        ctx.core({ type: "SET_RECONNECTING", payload: false });
        ctx.core({ type: "SET_LOGIN_ERROR", payload: null });
        const failedChar = ctx.pendingAuthCharRef.current;
        ctx.pendingAuthCharRef.current = null;
        ctx.failedAuthCharRef.current = failedChar;
        if (failedChar) {
          try {
            const saved = JSON.parse(localStorage.getItem("ambonmud_auth_tokens") ?? "{}") as Record<string, string>;
            delete saved[failedChar];
            localStorage.setItem("ambonmud_auth_tokens", JSON.stringify(saved));
          } catch { /* ignore */ }
        }
      }
      break;
    }

    case "Login.Error": {
      const packet = data as LoginErrorState;
      ctx.core({ type: "SET_LOGIN_ERROR", payload: packet });
      break;
    }

    case "UI.Feedback": {
      const raw = data as Record<string, unknown>;
      const feedback: UiFeedback = {
        type: (raw.type as UiFeedback["type"]) ?? "info",
        message: typeof raw.message === "string" ? raw.message : "",
        code: typeof raw.code === "string" ? raw.code : undefined,
        scope: typeof raw.scope === "string" ? raw.scope : undefined,
        command: typeof raw.command === "string" ? raw.command : undefined,
      };
      // Push as combat log message (same as old pushUiFeedback)
      const style = feedback.type === "error" ? "error" : feedback.type === "success" ? "heal" : "info";
      ctx.combat({
        type: "PUSH_COMBAT_LOG_MESSAGE",
        payload: { id: ++combatLogIdCounter, text: feedback.message, style, receivedAt: Date.now() },
      });
      break;
    }

    case "Crafting.Skills": {
      if (!Array.isArray(data)) break;
      ctx.inventory({
        type: "SET_CRAFTING_SKILLS",
        payload: data
          .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
          .map((e) => ({
            id: typeof e.id === "string" ? e.id : "",
            name: typeof e.name === "string" ? e.name : "",
            level: safeNumber(e.level, 1),
            xp: safeNumber(e.xp, 0),
            xpToNext: safeNumber(e.xpToNext, 0),
            maxLevel: safeNumber(e.maxLevel, 100),
            type: e.type === "gathering" ? "gathering" as const : "crafting" as const,
          })),
      });
      break;
    }

    case "Crafting.Recipes": {
      if (!Array.isArray(data)) break;
      ctx.inventory({
        type: "SET_CRAFTING_RECIPES",
        payload: data
          .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
          .map((e) => ({
            id: typeof e.id === "string" ? e.id : "",
            name: typeof e.name === "string" ? e.name : "",
            skill: typeof e.skill === "string" ? e.skill : "",
            skillRequired: safeNumber(e.skillRequired, 1),
            levelRequired: safeNumber(e.levelRequired, 1),
            materials: Array.isArray(e.materials)
              ? e.materials
                  .filter((m): m is Record<string, unknown> => typeof m === "object" && m !== null)
                  .map((m) => ({ name: typeof m.name === "string" ? m.name : "", quantity: safeNumber(m.quantity, 1) }))
              : [],
            outputName: typeof e.outputName === "string" ? e.outputName : "",
            outputQuantity: safeNumber(e.outputQuantity, 1),
          })),
      });
      break;
    }

    case "Crafting.Nodes": {
      if (!Array.isArray(data)) {
        ctx.inventory({ type: "SET_CRAFTING_NODES", payload: [] });
        break;
      }
      ctx.inventory({
        type: "SET_CRAFTING_NODES",
        payload: data
          .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
          .map((e) => ({
            id: typeof e.id === "string" ? e.id : "",
            name: typeof e.name === "string" ? e.name : "",
            skill: typeof e.skill === "string" ? e.skill : "",
            skillRequired: safeNumber(e.skillRequired, 1),
            image: typeof e.image === "string" ? e.image : null,
          })),
      });
      break;
    }

    case "Crafting.Result": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.pushCraftingResult({
        type: packet.type === "gather" ? "gather" : "craft",
        skill: typeof packet.skill === "string" ? packet.skill : "",
        xpAwarded: safeNumber(packet.xpAwarded, 0),
        leveledUp: packet.leveledUp === true,
        newLevel: safeNumber(packet.newLevel, 0),
        itemName: typeof packet.itemName === "string" ? packet.itemName : null,
        quantity: typeof packet.quantity === "number" ? packet.quantity : null,
      });
      break;
    }

    case "Char.Pet": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.combat({
        type: "SET_PET_STATE",
        payload: {
          active: !!packet.active,
          name: typeof packet.name === "string" ? packet.name : undefined,
          hp: typeof packet.hp === "number" ? packet.hp : undefined,
          maxHp: typeof packet.maxHp === "number" ? packet.maxHp : undefined,
          minDamage: typeof packet.minDamage === "number" ? packet.minDamage : undefined,
          maxDamage: typeof packet.maxDamage === "number" ? packet.maxDamage : undefined,
          armor: typeof packet.armor === "number" ? packet.armor : undefined,
          image: typeof packet.image === "string" ? packet.image : undefined,
        },
      });
      break;
    }

    case "Char.Bank": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.inventory({
        type: "SET_BANK_STATE",
        payload: {
          gold: safeNumber(packet.gold, 0),
          items: Array.isArray(packet.items)
            ? (packet.items as Array<Record<string, unknown>>)
              .filter((i): i is Record<string, unknown> => typeof i === "object" && i !== null)
              .map((i) => ({
                id: typeof i.id === "string" ? i.id : "",
                name: typeof i.name === "string" ? i.name : "",
                keyword: typeof i.keyword === "string" ? i.keyword : "",
                image: typeof i.image === "string" ? i.image : null,
              }))
            : [],
          maxItems: safeNumber(packet.maxItems, 0),
        },
      });
      break;
    }

    case "Char.Factions": {
      const factionPacket = data;
      ctx.ui({
        type: "SET_FACTIONS",
        payload: Array.isArray(factionPacket)
          ? factionPacket
            .filter((f): f is Record<string, unknown> => typeof f === "object" && f !== null)
            .map((f) => ({
              id: typeof f.id === "string" ? f.id : "",
              name: typeof f.name === "string" ? f.name : "",
              reputation: safeNumber(f.reputation, 0),
              tier: typeof f.tier === "string" ? f.tier : "Neutral",
            }))
          : [],
      });
      break;
    }

    case "Char.Currencies": {
      ctx.inventory({
        type: "SET_CURRENCIES",
        payload: Array.isArray(data)
          ? data
            .filter((c): c is Record<string, unknown> => typeof c === "object" && c !== null)
            .map((c) => ({
              id: typeof c.id === "string" ? c.id : "",
              name: typeof c.name === "string" ? c.name : "",
              abbreviation: typeof c.abbreviation === "string" ? c.abbreviation : "",
              balance: safeNumber(c.balance),
            }))
          : [],
      });
      break;
    }

    case "World.Time": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.world({
        type: "SET_WORLD_TIME",
        payload: {
          period: typeof packet.period === "string" ? packet.period : "DAY",
          hour: safeNumber(packet.hour),
          minute: safeNumber(packet.minute),
        },
      });
      break;
    }

    case "World.Weather": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.world({
        type: "SET_WORLD_WEATHER",
        payload: {
          zone: typeof packet.zone === "string" ? packet.zone : "",
          weather: typeof packet.weather === "string" ? packet.weather : "CLEAR",
          description: typeof packet.description === "string" ? packet.description : "",
        },
      });
      break;
    }

    case "World.Events": {
      ctx.world({
        type: "SET_WORLD_EVENTS",
        payload: Array.isArray(data)
          ? data
              .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
              .map((e) => ({
                id: typeof e.id === "string" ? e.id : "",
                name: typeof e.name === "string" ? e.name : "",
                description: typeof e.description === "string" ? e.description : "",
              }))
          : [],
      });
      break;
    }

    case "Auction.List": {
      if (!Array.isArray(data)) {
        ctx.inventory({ type: "SET_AUCTION_LISTINGS", payload: [] });
        break;
      }
      ctx.inventory({
        type: "SET_AUCTION_LISTINGS",
        payload: data
          .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
          .map((e) => ({
            id: safeNumber(e.id, 0),
            itemName: typeof e.itemName === "string" ? e.itemName : "",
            itemId: typeof e.itemId === "string" ? e.itemId : "",
            price: safeNumber(e.price, 0),
            seller: typeof e.seller === "string" ? e.seller : "",
          })),
      });
      break;
    }

    case "Leaderboard.Data": {
      const packet = data as Partial<Record<string, unknown>>;
      const category = typeof packet.category === "string" ? packet.category : "";
      const label = typeof packet.label === "string" ? packet.label : category;
      const scoreLabel = typeof packet.scoreLabel === "string" ? packet.scoreLabel : "";
      const entries: LeaderboardEntry[] = Array.isArray(packet.entries)
        ? packet.entries
            .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
            .map((e) => ({
              rank: safeNumber(e.rank, 0),
              name: typeof e.name === "string" ? e.name : "",
              score: safeNumber(e.score, 0),
            }))
        : [];
      ctx.ui({ type: "MERGE_LEADERBOARD", payload: { category, data: { category, label, scoreLabel, entries } } });
      break;
    }

    case "Trade.State": {
      const packet = data as Partial<Record<string, unknown>>;
      if (packet.active === true) {
        const parseItems = (arr: unknown): Array<{ id: string; name: string }> => {
          if (!Array.isArray(arr)) return [];
          return arr
            .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
            .map((e) => ({
              id: typeof e.id === "string" ? e.id : "",
              name: typeof e.name === "string" ? e.name : "",
            }));
        };
        ctx.inventory({
          type: "SET_TRADE_STATE",
          payload: {
            active: true,
            partner: typeof packet.partner === "string" ? packet.partner : null,
            myItems: parseItems(packet.myItems),
            theirItems: parseItems(packet.theirItems),
            myGold: safeNumber(packet.myGold, 0),
            theirGold: safeNumber(packet.theirGold, 0),
            myAccepted: packet.myAccepted === true,
            theirAccepted: packet.theirAccepted === true,
          },
        });
      } else {
        ctx.inventory({ type: "SET_TRADE_STATE", payload: null });
      }
      break;
    }

    case "Mail.List": {
      if (!Array.isArray(data)) {
        ctx.ui({ type: "SET_MAIL_INBOX", payload: [] });
        break;
      }
      ctx.ui({
        type: "SET_MAIL_INBOX",
        payload: data
          .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
          .map((e) => ({
            index: safeNumber(e.index, 0),
            id: typeof e.id === "string" ? e.id : "",
            from: typeof e.from === "string" ? e.from : "",
            date: safeNumber(e.date, 0),
            read: e.read === true,
            preview: typeof e.preview === "string" ? e.preview : "",
          })),
      });
      break;
    }

    case "Mail.Message": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.ui({
        type: "SET_MAIL_MESSAGE",
        payload: {
          index: safeNumber(packet.index, 0),
          id: typeof packet.id === "string" ? packet.id : "",
          from: typeof packet.from === "string" ? packet.from : "",
          body: typeof packet.body === "string" ? packet.body : "",
          date: safeNumber(packet.date, 0),
          read: packet.read === true,
        },
      });
      break;
    }

    case "Mail.Notification": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.pushMailNotification({
        from: typeof packet.from === "string" ? packet.from : "",
        unreadCount: safeNumber(packet.unreadCount, 0),
      });
      break;
    }

    case "Housing.Info": {
      const packet = data as Partial<Record<string, unknown>>;
      const hasHouse = packet.hasHouse === true;
      const ownerName = typeof packet.ownerName === "string" ? packet.ownerName : null;
      const rooms: HousingRoomInfo[] = Array.isArray(packet.rooms)
        ? packet.rooms
            .filter((r): r is Record<string, unknown> => typeof r === "object" && r !== null)
            .map((r) => ({
              templateId: typeof r.templateId === "string" ? r.templateId : "",
              title: typeof r.title === "string" ? r.title : "",
              description: typeof r.description === "string" ? r.description : "",
            }))
        : [];
      ctx.ui({ type: "SET_HOUSING", payload: { hasHouse, ownerName, rooms } });
      break;
    }

    case "Core.Ping": {
      ctx.sendGmcp("Core.Ping", {});
      break;
    }

    case "Staff.WorldInfo": {
      if (!Array.isArray(data)) break;
      ctx.ui({
        type: "SET_STAFF_WORLD_INFO",
        payload: data
          .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
          .map((e) => ({
            zone: typeof e.zone === "string" ? e.zone : "",
            rooms: Array.isArray(e.rooms)
              ? e.rooms
                  .filter((r): r is Record<string, unknown> => typeof r === "object" && r !== null)
                  .map((r) => ({
                    id: typeof r.id === "string" ? r.id : "",
                    title: typeof r.title === "string" ? r.title : "",
                  }))
              : [],
          })),
      });
      break;
    }

    case "Staff.Possession": {
      const packet = data as Partial<Record<string, unknown>>;
      const active = packet.active === true;
      const mobName = typeof packet.mobName === "string" ? packet.mobName : null;
      ctx.ui({ type: "SET_POSSESSING", payload: active ? mobName : null });
      break;
    }

    case "Staff.MobTemplates": {
      if (!Array.isArray(data)) break;
      ctx.ui({
        type: "SET_STAFF_MOB_TEMPLATES",
        payload: data
          .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
          .map((e) => ({
            zone: typeof e.zone === "string" ? e.zone : "",
            mobs: Array.isArray(e.mobs)
              ? e.mobs
                  .filter((m): m is Record<string, unknown> => typeof m === "object" && m !== null)
                  .map((m) => ({
                    id: typeof m.id === "string" ? m.id : "",
                    name: typeof m.name === "string" ? m.name : "",
                  }))
              : [],
          })),
      });
      break;
    }

    case "Trainer.List": {
      const packet = data as Partial<Record<string, unknown>>;
      const abilities: TrainerAbility[] = Array.isArray(packet.abilities)
        ? packet.abilities
            .filter((a): a is Record<string, unknown> => typeof a === "object" && a !== null)
            .map((a) => ({
              id: typeof a.id === "string" ? a.id : "",
              name: typeof a.name === "string" ? a.name : "",
              description: typeof a.description === "string" ? a.description : "",
              levelRequired: safeNumber(a.levelRequired, 1),
              manaCost: safeNumber(a.manaCost),
              cooldownMs: safeNumber(a.cooldownMs),
              targetType: typeof a.targetType === "string" ? a.targetType : "ENEMY",
              effectType: typeof a.effectType === "string" ? a.effectType : "DIRECT_DAMAGE",
              image: typeof a.image === "string" ? a.image : null,
            }))
        : [];
      const trainer: TrainerData = {
        trainerId: typeof packet.trainerId === "string" ? packet.trainerId : "",
        name: typeof packet.name === "string" ? packet.name : "Trainer",
        className: typeof packet.class === "string" ? packet.class : "",
        image: typeof packet.image === "string" ? packet.image : null,
        classUnlocked: packet.classUnlocked === true,
        availableSkillPoints: safeNumber(packet.availableSkillPoints),
        multiclassMinLevel: safeNumber(packet.multiclassMinLevel, 10),
        multiclassGoldCost: safeNumber(packet.multiclassGoldCost, 500),
        abilities,
      };
      ctx.ui({ type: "SET_TRAINER", payload: trainer });
      break;
    }

    case "Char.Classes": {
      const packet = data as Partial<Record<string, unknown>>;
      const unlockedClasses: string[] = Array.isArray(packet.unlockedClasses)
        ? packet.unlockedClasses.filter((c): c is string => typeof c === "string")
        : [];
      ctx.core({ type: "SET_UNLOCKED_CLASSES", payload: unlockedClasses });
      break;
    }

    case "Lottery.Info": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.social({
        type: "SET_LOTTERY_INFO",
        payload: {
          jackpot: safeNumber(packet.jackpot, 0),
          totalTickets: safeNumber(packet.totalTickets, 0),
          playerTickets: safeNumber(packet.playerTickets, 0),
          nextDrawingMs: safeNumber(packet.nextDrawingMs, 0),
        },
      });
      break;
    }

    case "Guild.Hall": {
      const packet = data as Partial<Record<string, unknown>>;
      const rooms: GuildHallRoom[] = Array.isArray(packet.rooms)
        ? (packet.rooms as unknown[]).map((r) => {
            const entry = r as Partial<Record<string, unknown>>;
            return {
              id: typeof entry.id === "string" ? entry.id : "",
              template: typeof entry.template === "string" ? entry.template : "",
              title: typeof entry.title === "string" ? entry.title : "",
            };
          })
        : [];
      ctx.social({
        type: "SET_GUILD_HALL",
        payload: {
          guildName: typeof packet.guildName === "string" ? packet.guildName : "",
          rooms,
          membersInHall: safeNumber(packet.membersInHall, 0),
          maxRooms: safeNumber(packet.maxRooms, 0),
        },
      });
      break;
    }

    case "Duel.State": {
      const packet = data as Partial<Record<string, unknown>>;
      const active = packet.active === true;
      ctx.combat({
        type: "SET_DUEL_STATE",
        payload: {
          active,
          opponentName: typeof packet.opponentName === "string" ? packet.opponentName : undefined,
          startedAtMs: typeof packet.startedAtMs === "number" ? packet.startedAtMs : undefined,
        },
      });
      if (!active) ctx.combat({ type: "SET_DUEL_CHALLENGE", payload: null });
      break;
    }

    case "Duel.Challenge": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.combat({
        type: "SET_DUEL_CHALLENGE",
        payload: {
          challengerName: typeof packet.challengerName === "string" ? packet.challengerName : "",
          targetName: typeof packet.targetName === "string" ? packet.targetName : "",
          direction: packet.direction === "incoming" ? "incoming" : "outgoing",
        },
      });
      break;
    }

    case "Dungeon.Info": {
      const packet = data as Partial<Record<string, unknown>>;
      const active = packet.active === true;
      ctx.world({
        type: "SET_DUNGEON_INFO",
        payload: active ? {
          active: true,
          instanceId: typeof packet.instanceId === "string" ? packet.instanceId : undefined,
          name: typeof packet.name === "string" ? packet.name : undefined,
          difficulty: typeof packet.difficulty === "string" ? packet.difficulty : undefined,
          totalRooms: typeof packet.totalRooms === "number" ? packet.totalRooms : undefined,
          completed: packet.completed === true,
          memberCount: typeof packet.memberCount === "number" ? packet.memberCount : undefined,
        } : null,
      });
      break;
    }

    case "Prestige.Info": {
      const packet = data as Partial<Record<string, unknown>>;
      const perks: PrestigePerk[] = Array.isArray(packet.perks)
        ? (packet.perks as unknown[]).map((p) => {
            const entry = p as Partial<Record<string, unknown>>;
            return {
              rank: safeNumber(entry.rank, 0),
              type: typeof entry.type === "string" ? entry.type : "",
              description: typeof entry.description === "string" ? entry.description : "",
              earned: entry.earned === true,
            };
          })
        : [];
      ctx.core({
        type: "SET_PRESTIGE_INFO",
        payload: {
          enabled: packet.enabled === true,
          currentRank: safeNumber(packet.currentRank, 0),
          maxRank: safeNumber(packet.maxRank, 0),
          availableXp: safeNumber(packet.availableXp, 0),
          nextRankCost: packet.nextRankCost != null ? safeNumber(packet.nextRankCost) : null,
          perks,
        },
      });
      break;
    }

    default:
      break;
  }
}
