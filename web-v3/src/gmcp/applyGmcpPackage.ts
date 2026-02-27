import type { Dispatch, SetStateAction } from "react";
import type {
  ChatChannel,
  ChatMessage,
  CharacterInfo,
  ItemSummary,
  RoomMob,
  RoomItem,
  RoomPlayer,
  RoomState,
  SkillSummary,
  StatusEffect,
  Vitals,
} from "../types";
import { MAX_CHAT_MESSAGES_PER_CHANNEL } from "../constants";
import { safeNumber } from "../utils";

interface GmcpContext {
  setVitals: Dispatch<SetStateAction<Vitals>>;
  setCharacter: Dispatch<SetStateAction<CharacterInfo>>;
  setRoom: Dispatch<SetStateAction<RoomState>>;
  setRoomItems: Dispatch<SetStateAction<RoomItem[]>>;
  setInventory: Dispatch<SetStateAction<ItemSummary[]>>;
  setEquipment: Dispatch<SetStateAction<Record<string, ItemSummary>>>;
  setPlayers: Dispatch<SetStateAction<RoomPlayer[]>>;
  setMobs: Dispatch<SetStateAction<RoomMob[]>>;
  setEffects: Dispatch<SetStateAction<StatusEffect[]>>;
  setSkills: Dispatch<SetStateAction<SkillSummary[]>>;
  setChatByChannel: Dispatch<SetStateAction<Record<ChatChannel, ChatMessage[]>>>;
  updateMap: (roomId: string, exits: Record<string, string>) => void;
}

const CHAT_CHANNEL_SET = new Set<ChatChannel>(["say", "tell", "gossip", "shout", "ooc"]);

function isChatChannel(value: string): value is ChatChannel {
  return CHAT_CHANNEL_SET.has(value as ChatChannel);
}

export function applyGmcpPackage(
  pkg: string,
  data: unknown,
  ctx: GmcpContext,
) {
  switch (pkg) {
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

    case "Char.Name": {
      const packet = data as Partial<Record<string, unknown>>;
      ctx.setCharacter({
        name: typeof packet.name === "string" && packet.name.length > 0 ? packet.name : "-",
        race: typeof packet.race === "string" ? packet.race : "",
        className: typeof packet.class === "string" ? packet.class : "",
        level: typeof packet.level === "number" ? packet.level : null,
      });
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
      });

      if (id) ctx.updateMap(id, exits);
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
              slot: typeof entry.slot === "string" ? entry.slot : null,
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
            slot,
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
          slot: typeof packet.slot === "string" ? packet.slot : null,
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
            hp: safeNumber(entry.hp),
            maxHp: Math.max(1, safeNumber(entry.maxHp, 1)),
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
          hp: safeNumber(packet.hp),
          maxHp: Math.max(1, safeNumber(packet.maxHp, 1)),
        },
      ]);
      break;
    }

    case "Room.UpdateMob": {
      const packet = data as Partial<Record<string, unknown>>;
      if (typeof packet.id !== "string") break;
      ctx.setMobs((prev) =>
        prev.map((mob) => {
          if (mob.id !== packet.id) return mob;
          return {
            ...mob,
            hp: safeNumber(packet.hp, mob.hp),
            maxHp: Math.max(1, safeNumber(packet.maxHp, mob.maxHp)),
          };
        }),
      );
      break;
    }

    case "Room.RemoveMob": {
      const packet = data as Partial<Record<string, unknown>>;
      if (typeof packet.id !== "string") break;
      ctx.setMobs((prev) => prev.filter((mob) => mob.id !== packet.id));
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
            receivedAt: now,
          })),
      );
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

    default:
      break;
  }
}

