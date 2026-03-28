import type { Dispatch, SetStateAction } from "react";
import type {
  AchievementData,
  CharStats,
  StatEntry,
  ChatChannel,
  ChatMessage,
  CharacterInfo,
  CombatEventData,
  CombatTarget,
  CommandEntry,
  EmotePreset,
  CompletedAchievement,
  ContainerContents,
  CraftingNode,
  CraftingRecipe,
  CraftingResult,
  CraftingSkill,
  DialogueChoice,
  DialogueState,
  EquipmentSlotDef,
  FriendEntry,
  FriendNotification,
  GainEvent,
  GroupInfo,
  GroupMember,
  GuildInfo,
  GuildMemberEntry,
  PendingGroupInvite,
  PendingGuildInvite,
  InProgressAchievement,
  ItemSummary,
  LookTargetInfo,
  LoginErrorState,
  LoginPromptState,
  MailEntry,
  MailMessage,
  MailNotification,
  MobInfo,
  RoomFeature,
  QuestAvailable,
  QuestEntry,
  QuestNotification,
  RoomMob,
  RoomItem,
  RoomPlayer,
  RoomState,
  ShopItem,
  StaffMobZone,
  StaffWorldZone,
  ShopState,
  SkillSummary,
  SpriteEntry,
  SpriteList,
  StatusEffect,
  StatusVarLabels,
  UiFeedback,
  Vitals,
  WhoPlayer,
  ZoneInstances,
  ZoneInstanceItem,
} from "../types";
import { MAX_CHAT_MESSAGES_PER_CHANNEL } from "../constants";
import { safeNumber } from "../utils";

interface GmcpContext {
  setVitals: Dispatch<SetStateAction<Vitals>>;
  setStatusVarLabels: Dispatch<SetStateAction<StatusVarLabels>>;
  setCharacter: Dispatch<SetStateAction<CharacterInfo>>;
  setRoom: Dispatch<SetStateAction<RoomState>>;
  setRoomItems: Dispatch<SetStateAction<RoomItem[]>>;
  setInventory: Dispatch<SetStateAction<ItemSummary[]>>;
  setEquipment: Dispatch<SetStateAction<Record<string, ItemSummary>>>;
  setEquipmentSlotDefs: Dispatch<SetStateAction<EquipmentSlotDef[]>>;
  setPlayers: Dispatch<SetStateAction<RoomPlayer[]>>;
  setMobs: Dispatch<SetStateAction<RoomMob[]>>;
  setEffects: Dispatch<SetStateAction<StatusEffect[]>>;
  setSkills: Dispatch<SetStateAction<SkillSummary[]>>;
  setAchievements: Dispatch<SetStateAction<AchievementData>>;
  setGroupInfo: Dispatch<SetStateAction<GroupInfo>>;
  setPendingGroupInvite: Dispatch<SetStateAction<PendingGroupInvite | null>>;
  setGuildInfo: Dispatch<SetStateAction<GuildInfo>>;
  setPendingGuildInvite: Dispatch<SetStateAction<PendingGuildInvite | null>>;
  setGuildMembers: Dispatch<SetStateAction<GuildMemberEntry[]>>;
  setFriends: Dispatch<SetStateAction<FriendEntry[]>>;
  pushFriendNotification: (notification: FriendNotification) => void;
  setDialogue: Dispatch<SetStateAction<DialogueState | null>>;
  setCombatTarget: Dispatch<SetStateAction<CombatTarget | null>>;
  setShop: Dispatch<SetStateAction<ShopState | null>>;
  setChatByChannel: Dispatch<SetStateAction<Record<ChatChannel, ChatMessage[]>>>;
  updateMap: (roomId: string, exits: Record<string, string>, title: string, image: string | null, mapX: number, mapY: number) => void;
  loadZoneMap: (zone: string, rooms: Array<{ id: string; x: number; y: number; exits: Record<string, string> }>) => void;
  pushCombatEvent: (event: CombatEventData) => void;
  setCharStats: Dispatch<SetStateAction<CharStats | null>>;
  setQuests: Dispatch<SetStateAction<QuestEntry[]>>;
  setQuestsAvailable: Dispatch<SetStateAction<QuestAvailable[]>>;
  pushGainEvent: (event: GainEvent) => void;
  pushQuestNotification: (notification: QuestNotification) => void;
  setMobInfo: Dispatch<SetStateAction<MobInfo[]>>;
  setRoomFeatures: Dispatch<SetStateAction<RoomFeature[]>>;
  setContainerContents: Dispatch<SetStateAction<ContainerContents | null>>;
  setLoginPrompt: Dispatch<SetStateAction<LoginPromptState | null>>;
  setLoginError: Dispatch<SetStateAction<LoginErrorState | null>>;
  setServerAssets: Dispatch<SetStateAction<Record<string, string>>>;
  setServerCommands: Dispatch<SetStateAction<CommandEntry[]>>;
  setEmotePresets: Dispatch<SetStateAction<EmotePreset[]>>;
  pushUiFeedback: (feedback: UiFeedback) => void;
  setStaffWorldInfo: Dispatch<SetStateAction<StaffWorldZone[]>>;
  setStaffMobTemplates: Dispatch<SetStateAction<StaffMobZone[]>>;
  setLookTarget: Dispatch<SetStateAction<LookTargetInfo | null>>;
  pushBroadcast: (sender: string, message: string) => void;
  setPossessing: Dispatch<SetStateAction<string | null>>;
  setCraftingSkills: Dispatch<SetStateAction<CraftingSkill[]>>;
  setCraftingRecipes: Dispatch<SetStateAction<CraftingRecipe[]>>;
  setCraftingNodes: Dispatch<SetStateAction<CraftingNode[]>>;
  pushCraftingResult: (result?: CraftingResult) => void;
  setMailInbox: Dispatch<SetStateAction<MailEntry[] | null>>;
  setMailMessage: Dispatch<SetStateAction<MailMessage | null>>;
  pushMailNotification: (notification?: MailNotification) => void;
  setWhoPlayers: Dispatch<SetStateAction<WhoPlayer[]>>;
  setZoneInstances: Dispatch<SetStateAction<ZoneInstances>>;
  setSpriteList: Dispatch<SetStateAction<SpriteList>>;
  sendGmcp: (pkg: string, payload: unknown) => void;
}

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

export function applyGmcpPackage(
  pkg: string,
  data: unknown,
  ctx: GmcpContext,
) {
  switch (pkg) {
    case "Char.StatusVars": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.setStatusVarLabels((prev) => ({
        hp: typeof packet.hp === "string" ? packet.hp : prev.hp,
        maxHp: typeof packet.maxHp === "string" ? packet.maxHp : prev.maxHp,
        mana: typeof packet.mana === "string" ? packet.mana : prev.mana,
        maxMana: typeof packet.maxMana === "string" ? packet.maxMana : prev.maxMana,
        level: typeof packet.level === "string" ? packet.level : prev.level,
        xp: typeof packet.xp === "string" ? packet.xp : prev.xp,
      }));
      break;
    }

    case "Char.Vitals": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.setVitals({
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
      });
      break;
    }

    case "Char.Combat": {
      const packet = data as Partial<Record<string, unknown>>;
      const targetId = typeof packet.targetId === "string" ? packet.targetId : null;
      if (targetId === null) {
        ctx.setCombatTarget(null);
      } else {
        ctx.setCombatTarget({
          targetId,
          targetName: typeof packet.targetName === "string" ? packet.targetName : null,
          targetHp: typeof packet.targetHp === "number" ? packet.targetHp : null,
          targetMaxHp: typeof packet.targetMaxHp === "number" ? packet.targetMaxHp : null,
          targetImage: typeof packet.targetImage === "string" ? packet.targetImage : null,
        });
      }
      break;
    }

    case "Char.Name": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.setCharacter({
        name: typeof packet.name === "string" && packet.name.length > 0 ? packet.name : "-",
        gender: typeof packet.gender === "string" ? packet.gender : "",
        race: typeof packet.race === "string" ? packet.race : "",
        className: typeof packet.class === "string" ? packet.class : "",
        level: typeof packet.level === "number" ? packet.level : null,
        sprite: typeof packet.sprite === "string" ? packet.sprite : null,
        isStaff: packet.isStaff === true,
      });
      // Login complete — dismiss modal
      ctx.setLoginPrompt(null);
      ctx.setLoginError(null);
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
      ctx.setSpriteList({ active, sprites });
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

      // Detect actual room change (not just a look/refresh of the same room)
      ctx.setRoom((prev) => {
        if (prev.id !== id && prev.id !== null) {
          // Moved to a different room — clear dialogue/quest offers
          ctx.setDialogue(null);
          ctx.setQuestsAvailable([]);
        }
        return { id, title, description, exits, image, video, music, ambient, station, mapX, mapY };
      });

      if (id) {
        ctx.updateMap(id, exits, title === "-" ? "" : title, image, mapX, mapY);
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
      ctx.setZoneInstances({ zone, currentEngineId, instances });
      break;
    }

    case "Char.Equipment.Slots": {
      if (!Array.isArray(data)) break;
      ctx.setEquipmentSlotDefs(
        data
          .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
          .map((e) => ({
            id: typeof e.id === "string" ? e.id : "",
            displayName: typeof e.displayName === "string" ? e.displayName : "",
            order: typeof e.order === "number" ? e.order : 0,
            x: typeof e.x === "number" ? e.x : 50,
            y: typeof e.y === "number" ? e.y : 50,
          }))
          .sort((a, b) => a.order - b.order),
      );
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
          };
        }
      }

      ctx.setInventory(inventoryList);
      ctx.setEquipment(equipmentMap);
      break;
    }

    case "Char.Items.Add": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.setInventory((prev) => [
        ...prev,
        {
          id: typeof packet.id === "string" ? packet.id : `${Date.now()}-${Math.random()}`,
          name: typeof packet.name === "string" ? packet.name : "Unknown item",
          keyword: typeof packet.keyword === "string" ? packet.keyword : (typeof packet.name === "string" ? packet.name : "item"),
          slot: typeof packet.slot === "string" ? packet.slot : null,
          basePrice: typeof packet.basePrice === "number" ? packet.basePrice : undefined,
          image: typeof packet.image === "string" ? packet.image : null,
          video: typeof packet.video === "string" ? packet.video : null,
        },
      ]);
      break;
    }

    case "Char.Items.Remove": {
      const packet = data as Partial<Record<string, unknown>>;
      if (typeof packet.id !== "string") break;
      ctx.setInventory((prev) => prev.filter((item) => item.id !== packet.id));
      break;
    }

    case "Room.Items": {
      if (!Array.isArray(data)) {
        ctx.setRoomItems([]);
        break;
      }
      ctx.setRoomItems(
        data
          .filter((entry): entry is Record<string, unknown> => typeof entry === "object" && entry !== null)
          .map((entry, index) => ({
            id: typeof entry.id === "string" ? entry.id : `room-item-${index}-${Date.now()}`,
            name: typeof entry.name === "string" ? entry.name : "Unknown item",
            description: typeof entry.description === "string" ? entry.description : undefined,
            image: typeof entry.image === "string" ? entry.image : null,
            video: typeof entry.video === "string" ? entry.video : null,
          })),
      );
      break;
    }

    case "Room.LookTarget": {
      const packet = data as Partial<Record<string, unknown>>;
      const targetType = typeof packet.type === "string" ? packet.type : "item";
      ctx.setLookTarget({
        type: targetType as LookTargetInfo["type"],
        name: typeof packet.name === "string" ? packet.name : "Unknown",
        description: typeof packet.description === "string" ? packet.description : "",
        image: typeof packet.image === "string" ? packet.image : null,
        level: typeof packet.level === "number" ? packet.level : null,
        race: typeof packet.race === "string" ? packet.race : null,
        playerClass: typeof packet.class === "string" ? (packet.class as string) : null,
        receivedAt: Date.now(),
      });
      break;
    }

    case "Room.Players": {
      if (!Array.isArray(data)) {
        ctx.setPlayers([]);
        break;
      }
      ctx.setPlayers(
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
      ctx.setPlayers((prev) => {
        if (prev.some((player) => player.name === name)) return prev;
        return [...prev, { name, level: safeNumber(packet.level) }];
      });
      break;
    }

    case "Room.RemovePlayer": {
      const packet = data as Partial<Record<string, unknown>>;
      if (typeof packet.name !== "string") break;
      ctx.setPlayers((prev) => prev.filter((player) => player.name !== packet.name));
      break;
    }

    case "Room.Mobs": {
      if (!Array.isArray(data)) {
        ctx.setMobs([]);
        break;
      }
      ctx.setMobs(
        data
          .filter((entry): entry is Record<string, unknown> => typeof entry === "object" && entry !== null)
          .map((entry) => ({
            id: typeof entry.id === "string" ? entry.id : `${Date.now()}-${Math.random()}`,
            name: typeof entry.name === "string" ? entry.name : "Unknown mob",
            description: typeof entry.description === "string" ? entry.description : undefined,
            hp: safeNumber(entry.hp),
            maxHp: Math.max(1, safeNumber(entry.maxHp, 1)),
            image: typeof entry.image === "string" ? entry.image : null,
            video: typeof entry.video === "string" ? entry.video : null,
            effects: parseMobEffects(entry.effects),
          })),
      );
      break;
    }

    case "Room.AddMob": {
      const packet = data as Partial<Record<string, unknown>>;
      const id = packet.id;
      if (typeof id !== "string") break;
      ctx.setMobs((prev) => [
        ...prev,
        {
          id,
          name: typeof packet.name === "string" ? packet.name : "Unknown mob",
          description: typeof packet.description === "string" ? packet.description : undefined,
          hp: safeNumber(packet.hp),
          maxHp: Math.max(1, safeNumber(packet.maxHp, 1)),
          image: typeof packet.image === "string" ? packet.image : null,
          video: typeof packet.video === "string" ? packet.video : null,
          effects: parseMobEffects(packet.effects),
        },
      ]);
      break;
    }

    case "Room.UpdateMob": {
      const packet = data as Partial<Record<string, unknown>>;
      if (typeof packet.id !== "string") break;
      const updatedHp = safeNumber(packet.hp);
      const updatedMaxHp = Math.max(1, safeNumber(packet.maxHp, 1));
      ctx.setMobs((prev) =>
        prev.map((mob) => {
          if (mob.id !== packet.id) return mob;
          const effects = parseMobEffects(packet.effects);
          return {
            ...mob,
            hp: safeNumber(packet.hp, mob.hp),
            maxHp: Math.max(1, safeNumber(packet.maxHp, mob.maxHp)),
            description: typeof packet.description === "string" ? packet.description : mob.description,
            image: typeof packet.image === "string" ? packet.image : mob.image,
            video: typeof packet.video === "string" ? packet.video : mob.video,
            effects: effects !== undefined ? effects : mob.effects,
          };
        }),
      );
      // Keep combat target HP in sync
      ctx.setCombatTarget((prev) =>
        prev && prev.targetId === packet.id
          ? { ...prev, targetHp: updatedHp, targetMaxHp: updatedMaxHp }
          : prev,
      );
      break;
    }

    case "Room.RemoveMob": {
      const packet = data as Partial<Record<string, unknown>>;
      if (typeof packet.id !== "string") break;
      ctx.setMobs((prev) => prev.filter((mob) => mob.id !== packet.id));
      // Clear combat target if the removed mob was our target
      ctx.setCombatTarget((prev) => (prev && prev.targetId === packet.id ? null : prev));
      break;
    }

    case "Char.StatusEffects": {
      if (!Array.isArray(data)) {
        ctx.setEffects([]);
        break;
      }
      ctx.setEffects(
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

    case "Char.Skills": {
      const now = Date.now();
      if (!Array.isArray(data)) {
        ctx.setSkills([]);
        break;
      }
      ctx.setSkills(
        data
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
      );
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
      ctx.setAchievements({ completed, inProgress });
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
      ctx.setGroupInfo({ leader, members });
      // Clear pending invite when group info arrives (player joined a group)
      if (leader) ctx.setPendingGroupInvite(null);
      break;
    }

    case "Group.Invite": {
      const packet = data as Partial<Record<string, unknown>>;
      const inviterName = typeof packet.inviterName === "string" ? packet.inviterName : "Unknown";
      ctx.setPendingGroupInvite({ inviterName, receivedAt: Date.now() });
      break;
    }

    case "Guild.Info": {
      const packet = data as Partial<Record<string, unknown>>;
      const guildName = typeof packet.name === "string" ? packet.name : null;
      ctx.setGuildInfo({
        name: guildName,
        tag: typeof packet.tag === "string" ? packet.tag : null,
        rank: typeof packet.rank === "string" ? packet.rank : null,
        motd: typeof packet.motd === "string" ? packet.motd : null,
        memberCount: safeNumber(packet.memberCount),
        maxSize: safeNumber(packet.maxSize, 50),
      });
      // Clear pending invite when guild info arrives (player joined a guild)
      if (guildName) ctx.setPendingGuildInvite(null);
      break;
    }

    case "Guild.Invite": {
      const packet = data as Partial<Record<string, unknown>>;
      const inviterName = typeof packet.inviterName === "string" ? packet.inviterName : "Unknown";
      const guildName = typeof packet.guildName === "string" ? packet.guildName : "Unknown";
      const guildTag = typeof packet.guildTag === "string" ? packet.guildTag : "";
      ctx.setPendingGuildInvite({ inviterName, guildName, guildTag, receivedAt: Date.now() });
      break;
    }

    case "Guild.Members": {
      if (!Array.isArray(data)) {
        ctx.setGuildMembers([]);
        break;
      }
      ctx.setGuildMembers(
        data
          .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
          .map((e) => ({
            name: typeof e.name === "string" ? e.name : "Unknown",
            rank: typeof e.rank === "string" ? e.rank : "MEMBER",
            online: e.online === true,
            level: typeof e.level === "number" ? e.level : null,
          })),
      );
      break;
    }

    case "Guild.Chat": {
      const packet = data as Partial<Record<string, unknown>>;
      const sender = typeof packet.sender === "string" && packet.sender.length > 0 ? packet.sender : "Unknown";
      const message = typeof packet.message === "string" ? packet.message.trim() : "";
      if (message.length === 0) break;

      const entry: ChatMessage = {
        id: `${Date.now()}-${Math.random()}`,
        channel: "gchat",
        sender,
        message,
        receivedAt: Date.now(),
      };

      ctx.setChatByChannel((prev) => {
        const next = [...prev.gchat, entry];
        if (next.length > MAX_CHAT_MESSAGES_PER_CHANNEL) {
          next.splice(0, next.length - MAX_CHAT_MESSAGES_PER_CHANNEL);
        }
        return { ...prev, gchat: next };
      });
      break;
    }

    case "Friends.List": {
      if (!Array.isArray(data)) {
        ctx.setFriends([]);
        break;
      }
      ctx.setFriends(
        data
          .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
          .map((e) => ({
            name: typeof e.name === "string" ? e.name : "Unknown",
            online: e.online === true,
            level: typeof e.level === "number" ? e.level : null,
            zone: typeof e.zone === "string" ? e.zone : null,
          })),
      );
      break;
    }

    case "Friends.Online": {
      const packet = data as Partial<Record<string, unknown>>;
      const name = typeof packet.name === "string" ? packet.name : null;
      if (!name) break;
      const level = typeof packet.level === "number" ? packet.level : null;
      ctx.setFriends((prev) => {
        const existing = prev.find((f) => f.name === name);
        if (existing) {
          return prev.map((f) => f.name === name ? { ...f, online: true, level } : f);
        }
        return [...prev, { name, online: true, level, zone: null }];
      });
      ctx.pushFriendNotification({
        id: `${Date.now()}-${Math.random()}`,
        name,
        event: "online",
        receivedAt: Date.now(),
      });
      break;
    }

    case "Friends.Offline": {
      const packet = data as Partial<Record<string, unknown>>;
      const name = typeof packet.name === "string" ? packet.name : null;
      if (!name) break;
      ctx.setFriends((prev) =>
        prev.map((f) => f.name === name ? { ...f, online: false, zone: null } : f),
      );
      ctx.pushFriendNotification({
        id: `${Date.now()}-${Math.random()}`,
        name,
        event: "offline",
        receivedAt: Date.now(),
      });
      break;
    }

    case "Comm.Channel": {
      const packet = data as Partial<Record<string, unknown>>;
      const incomingChannel = typeof packet.channel === "string" ? packet.channel.toLowerCase() : "";
      const mappedChannel = incomingChannel === "whisper" ? "tell" : incomingChannel;
      if (!isChatChannel(mappedChannel)) break;

      const sender = typeof packet.sender === "string" && packet.sender.length > 0 ? packet.sender : "Unknown";
      const message = typeof packet.message === "string" ? packet.message.trim() : "";
      if (message.length === 0) break;

      const entry: ChatMessage = {
        id: `${Date.now()}-${Math.random()}`,
        channel: mappedChannel,
        sender,
        message,
        receivedAt: Date.now(),
      };

      ctx.setChatByChannel((prev) => {
        const next = [...prev[mappedChannel], entry];
        if (next.length > MAX_CHAT_MESSAGES_PER_CHANNEL) {
          next.splice(0, next.length - MAX_CHAT_MESSAGES_PER_CHANNEL);
        }
        return { ...prev, [mappedChannel]: next };
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
      ctx.setDialogue({ mobName, text, choices });
      break;
    }

    case "Dialogue.End": {
      ctx.setDialogue(null);
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
      ctx.setCharStats({
        stats,
        baseDamageMin: safeNumber(packet.baseDamageMin),
        baseDamageMax: safeNumber(packet.baseDamageMax),
        armor: safeNumber(packet.armor),
        dodgePercent: safeNumber(packet.dodgePercent),
      });
      break;
    }

    case "Quest.List": {
      if (!Array.isArray(data)) {
        ctx.setQuests([]);
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
                }))
            : [],
        }));
      ctx.setQuests(parsedQuests);
      // Remove newly-active quests from available offers so accept buttons disappear
      const activeIds = new Set(parsedQuests.map((q) => q.id));
      ctx.setQuestsAvailable((prev) => {
        const filtered = prev.filter((q) => !activeIds.has(q.id));
        return filtered.length === prev.length ? prev : filtered;
      });
      break;
    }

    case "Quest.Update": {
      const packet = data as Partial<Record<string, unknown>>;
      const questId = typeof packet.questId === "string" ? packet.questId : null;
      const objIndex = typeof packet.objectiveIndex === "number" ? packet.objectiveIndex : -1;
      if (!questId || objIndex < 0) break;
      ctx.setQuests((prev) =>
        prev.map((q) => {
          if (q.id !== questId) return q;
          const objectives = q.objectives.map((o, i) =>
            i === objIndex
              ? { ...o, current: safeNumber(packet.current, o.current), required: safeNumber(packet.required, o.required) }
              : o,
          );
          return { ...q, objectives };
        }),
      );
      break;
    }

    case "Quest.Complete": {
      const packet = data as Partial<Record<string, unknown>>;
      const questId = typeof packet.questId === "string" ? packet.questId : null;
      if (!questId) break;
      const questName = typeof packet.questName === "string" ? packet.questName : "Quest";
      ctx.setQuests((prev) => prev.filter((q) => q.id !== questId));
      ctx.pushQuestNotification({
        id: `${Date.now()}-${Math.random()}`,
        questId,
        questName,
        event: "complete",
        receivedAt: Date.now(),
      });
      break;
    }

    case "Quest.Available": {
      if (!Array.isArray(data)) {
        ctx.setQuestsAvailable([]);
        break;
      }
      ctx.setQuestsAvailable(
        data
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
      );
      break;
    }

    case "Char.Cooldown": {
      const packet = data as Partial<Record<string, unknown>>;
      const abilityId = typeof packet.abilityId === "string" ? packet.abilityId : null;
      const cooldownMs = safeNumber(packet.cooldownMs);
      if (!abilityId || cooldownMs <= 0) break;
      const now = Date.now();
      ctx.setSkills((prev) =>
        prev.map((s) =>
          s.id === abilityId
            ? { ...s, cooldownRemainingMs: cooldownMs, receivedAt: now }
            : s,
        ),
      );
      break;
    }

    case "Char.Gain": {
      const packet = data as Partial<Record<string, unknown>>;
      const nl = typeof packet.newLevel === "number" ? packet.newLevel : null;
      const hpG = typeof packet.hpGained === "number" ? packet.hpGained : null;
      const manaG = typeof packet.manaGained === "number" ? packet.manaGained : null;
      ctx.pushGainEvent({
        type: typeof packet.type === "string" ? packet.type : "unknown",
        amount: safeNumber(packet.amount),
        source: typeof packet.source === "string" ? packet.source : null,
        newLevel: nl,
        hpGained: hpG,
        manaGained: manaG,
      });
      break;
    }

    case "Room.MobInfo": {
      if (!Array.isArray(data)) {
        ctx.setMobInfo([]);
        break;
      }
      ctx.setMobInfo(
        data
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
      );
      break;
    }

    case "Room.Features": {
      ctx.setContainerContents(null);
      if (!Array.isArray(data)) {
        ctx.setRoomFeatures([]);
        break;
      }
      ctx.setRoomFeatures(
        data
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
      );
      break;
    }

    case "Room.ContainerContents": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.setContainerContents({
        featureId: typeof packet.featureId === "string" ? packet.featureId : "",
        name: typeof packet.name === "string" ? packet.name : "",
        keyword: typeof packet.keyword === "string" ? packet.keyword : "",
        items: Array.isArray(packet.items)
          ? packet.items
              .filter((i): i is Record<string, unknown> => typeof i === "object" && i !== null)
              .map((i) => ({ name: typeof i.name === "string" ? i.name : "", keyword: typeof i.keyword === "string" ? i.keyword : "" }))
          : [],
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
      ctx.setShop({ name, sellMultiplier, items });
      break;
    }

    case "Shop.Close": {
      ctx.setShop(null);
      break;
    }

    case "Server.Assets": {
      const packet = data as Record<string, string>;
      ctx.setServerAssets(packet);
      break;
    }

    case "Server.Commands": {
      const packet = data as { commands?: unknown[] };
      const commands: CommandEntry[] = (packet.commands ?? [])
        .filter((c): c is Record<string, unknown> => typeof c === "object" && c !== null)
        .map((c) => ({
          name: String(c.name ?? ""),
          usage: String(c.usage ?? ""),
          description: String(c.description ?? ""),
          category: String(c.category ?? ""),
          staff: c.staff === true,
          requiresTarget: c.requiresTarget === true,
        }));
      ctx.setServerCommands(commands);
      break;
    }

    case "Server.Broadcast": {
      const packet = data as Partial<Record<string, unknown>>;
      const sender = typeof packet.sender === "string" ? packet.sender : "System";
      const message = typeof packet.message === "string" ? packet.message : "";
      if (message.length > 0) ctx.pushBroadcast(sender, message);
      break;
    }

    case "Server.EmotePresets": {
      if (!Array.isArray(data)) break;
      const presets: EmotePreset[] = data
        .filter((p): p is Record<string, unknown> => typeof p === "object" && p !== null)
        .map((p) => ({
          label: String(p.label ?? ""),
          emoji: String(p.emoji ?? ""),
          action: String(p.action ?? ""),
        }));
      ctx.setEmotePresets(presets);
      break;
    }

    case "Server.Who": {
      const packet = data as { players?: unknown[] };
      const players: WhoPlayer[] = (packet.players ?? [])
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
        }));
      ctx.setWhoPlayers(players);
      break;
    }

    case "Login.Prompt": {
      const packet = data as LoginPromptState;
      ctx.setLoginPrompt(packet);
      ctx.setLoginError(null);
      break;
    }

    case "Login.Error": {
      const packet = data as LoginErrorState;
      ctx.setLoginError(packet);
      break;
    }

    case "UI.Feedback": {
      const raw = data as Record<string, unknown>;
      const packet: UiFeedback = {
        type: (raw.type as UiFeedback["type"]) ?? "info",
        message: typeof raw.message === "string" ? raw.message : "",
        code: typeof raw.code === "string" ? raw.code : undefined,
        scope: typeof raw.scope === "string" ? raw.scope : undefined,
        command: typeof raw.command === "string" ? raw.command : undefined,
      };
      ctx.pushUiFeedback(packet);
      break;
    }

    case "Crafting.Skills": {
      if (!Array.isArray(data)) break;
      ctx.setCraftingSkills(
        data
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
      );
      break;
    }

    case "Crafting.Recipes": {
      if (!Array.isArray(data)) break;
      ctx.setCraftingRecipes(
        data
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
      );
      break;
    }

    case "Crafting.Nodes": {
      if (!Array.isArray(data)) {
        ctx.setCraftingNodes([]);
        break;
      }
      ctx.setCraftingNodes(
        data
          .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
          .map((e) => ({
            id: typeof e.id === "string" ? e.id : "",
            name: typeof e.name === "string" ? e.name : "",
            skill: typeof e.skill === "string" ? e.skill : "",
            skillRequired: safeNumber(e.skillRequired, 1),
            image: typeof e.image === "string" ? e.image : null,
          })),
      );
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

    case "Mail.List": {
      if (!Array.isArray(data)) {
        ctx.setMailInbox([]);
        break;
      }
      ctx.setMailInbox(
        data
          .filter((e): e is Record<string, unknown> => typeof e === "object" && e !== null)
          .map((e) => ({
            index: safeNumber(e.index, 0),
            id: typeof e.id === "string" ? e.id : "",
            from: typeof e.from === "string" ? e.from : "",
            date: safeNumber(e.date, 0),
            read: e.read === true,
            preview: typeof e.preview === "string" ? e.preview : "",
          })),
      );
      break;
    }

    case "Mail.Message": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.setMailMessage({
        index: safeNumber(packet.index, 0),
        id: typeof packet.id === "string" ? packet.id : "",
        from: typeof packet.from === "string" ? packet.from : "",
        body: typeof packet.body === "string" ? packet.body : "",
        date: safeNumber(packet.date, 0),
        read: packet.read === true,
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

    case "Core.Ping": {
      ctx.sendGmcp("Core.Ping", {});
      break;
    }

    case "Staff.WorldInfo": {
      if (!Array.isArray(data)) break;
      ctx.setStaffWorldInfo(
        data
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
      );
      break;
    }

    case "Staff.Possession": {
      const packet = data as Partial<Record<string, unknown>>;
      const active = packet.active === true;
      const mobName = typeof packet.mobName === "string" ? packet.mobName : null;
      ctx.setPossessing(active ? mobName : null);
      break;
    }

    case "Staff.MobTemplates": {
      if (!Array.isArray(data)) break;
      ctx.setStaffMobTemplates(
        data
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
      );
      break;
    }

    default:
      break;
  }
}

