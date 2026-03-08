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
  CompletedAchievement,
  DialogueChoice,
  DialogueState,
  FriendEntry,
  FriendNotification,
  GainEvent,
  GroupInfo,
  GroupMember,
  GuildInfo,
  GuildMemberEntry,
  InProgressAchievement,
  ItemSummary,
  LoginErrorState,
  LoginPromptState,
  MobInfo,
  QuestEntry,
  QuestNotification,
  RoomMob,
  RoomItem,
  RoomPlayer,
  RoomState,
  ShopItem,
  ShopState,
  SkillSummary,
  StatusEffect,
  StatusVarLabels,
  Vitals,
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
  setPlayers: Dispatch<SetStateAction<RoomPlayer[]>>;
  setMobs: Dispatch<SetStateAction<RoomMob[]>>;
  setEffects: Dispatch<SetStateAction<StatusEffect[]>>;
  setSkills: Dispatch<SetStateAction<SkillSummary[]>>;
  setAchievements: Dispatch<SetStateAction<AchievementData>>;
  setGroupInfo: Dispatch<SetStateAction<GroupInfo>>;
  setGuildInfo: Dispatch<SetStateAction<GuildInfo>>;
  setGuildMembers: Dispatch<SetStateAction<GuildMemberEntry[]>>;
  setFriends: Dispatch<SetStateAction<FriendEntry[]>>;
  pushFriendNotification: (notification: FriendNotification) => void;
  setDialogue: Dispatch<SetStateAction<DialogueState | null>>;
  setCombatTarget: Dispatch<SetStateAction<CombatTarget | null>>;
  setShop: Dispatch<SetStateAction<ShopState | null>>;
  setChatByChannel: Dispatch<SetStateAction<Record<ChatChannel, ChatMessage[]>>>;
  updateMap: (roomId: string, exits: Record<string, string>, title: string, image: string | null) => void;
  pushCombatEvent: (event: CombatEventData) => void;
  setCharStats: Dispatch<SetStateAction<CharStats | null>>;
  setQuests: Dispatch<SetStateAction<QuestEntry[]>>;
  pushGainEvent: (event: GainEvent) => void;
  pushQuestNotification: (notification: QuestNotification) => void;
  setMobInfo: Dispatch<SetStateAction<MobInfo[]>>;
  setLoginPrompt: Dispatch<SetStateAction<LoginPromptState | null>>;
  setLoginError: Dispatch<SetStateAction<LoginErrorState | null>>;
  setServerAssets: Dispatch<SetStateAction<Record<string, string>>>;
}

const CHAT_CHANNEL_SET = new Set<ChatChannel>(["say", "tell", "gossip", "shout", "ooc", "gtell", "gchat"]);

function isChatChannel(value: string): value is ChatChannel {
  return CHAT_CHANNEL_SET.has(value as ChatChannel);
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

    case "Room.Info": {
      const packet = data as Partial<Record<string, unknown>>;
      const exits = packet.exits && typeof packet.exits === "object" ? (packet.exits as Record<string, string>) : {};
      const id = typeof packet.id === "string" ? packet.id : null;

      ctx.setRoom({
        id,
        title: typeof packet.title === "string" && packet.title.length > 0 ? packet.title : "-",
        description: typeof packet.description === "string" ? packet.description : "",
        exits,
        image: typeof packet.image === "string" ? packet.image : null,
        video: typeof packet.video === "string" ? packet.video : null,
        music: typeof packet.music === "string" ? packet.music : null,
        ambient: typeof packet.ambient === "string" ? packet.ambient : null,
      });

      if (id) {
        const title = typeof packet.title === "string" && packet.title.length > 0 ? packet.title : "";
        const image = typeof packet.image === "string" ? packet.image : null;
        ctx.updateMap(id, exits, title, image);
      }
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
          return {
            ...mob,
            hp: safeNumber(packet.hp, mob.hp),
            maxHp: Math.max(1, safeNumber(packet.maxHp, mob.maxHp)),
            description: typeof packet.description === "string" ? packet.description : mob.description,
            image: typeof packet.image === "string" ? packet.image : mob.image,
            video: typeof packet.video === "string" ? packet.video : mob.video,
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
      break;
    }

    case "Guild.Info": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.setGuildInfo({
        name: typeof packet.name === "string" ? packet.name : null,
        tag: typeof packet.tag === "string" ? packet.tag : null,
        rank: typeof packet.rank === "string" ? packet.rank : null,
        motd: typeof packet.motd === "string" ? packet.motd : null,
        memberCount: safeNumber(packet.memberCount),
        maxSize: safeNumber(packet.maxSize, 50),
      });
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
      ctx.setQuests(
        data
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
          })),
      );
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
      ctx.pushGainEvent({
        type: typeof packet.type === "string" ? packet.type : "unknown",
        amount: safeNumber(packet.amount),
        source: typeof packet.source === "string" ? packet.source : null,
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

    default:
      break;
  }
}

