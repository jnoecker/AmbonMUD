import { useMemo, useState } from "react";
import type { CommandEntry } from "../types";

interface HelpCommand {
  syntax: string;
  description: string;
}

interface HelpCategory {
  name: string;
  commands: HelpCommand[];
}

/** Display-name mapping for server category keys. */
const CATEGORY_LABELS: Record<string, string> = {
  navigation: "Navigation",
  communication: "Communication",
  items: "Inventory & Equipment",
  world: "World Interaction",
  combat: "Combat",
  progression: "Character",
  social: "NPCs & Social",
  shops: "NPCs & Shops",
  quests: "Quests & Achievements",
  groups: "Groups",
  guilds: "Guild",
  crafting: "Crafting",
  utility: "Utility",
  admin: "Staff",
};

/** Controls the display order of categories. Unlisted categories appear at the end. */
const CATEGORY_ORDER: string[] = [
  "navigation",
  "communication",
  "items",
  "world",
  "combat",
  "progression",
  "social",
  "shops",
  "quests",
  "groups",
  "guilds",
  "crafting",
  "utility",
  "admin",
];

/**
 * Static fallback used before the server sends `Server.Commands`.
 * Kept in sync with `defaultCommandEntries()` in AppConfig.kt.
 */
const FALLBACK_CATEGORIES: HelpCategory[] = [
  {
    name: "Navigation",
    commands: [
      { syntax: "look / l [target|direction]", description: "Look around, at a target, or in a direction" },
      { syntax: "n / s / e / w / u / d", description: "Move in a direction" },
      { syntax: "exits / ex", description: "List available exits" },
      { syntax: "recall", description: "Return to your recall point" },
    ],
  },
  {
    name: "Communication",
    commands: [
      { syntax: "say <msg>  or  '<msg>", description: "Speak to the room" },
      { syntax: "tell / t <player> <msg>", description: "Private message a player" },
      { syntax: "whisper / wh <player> <msg>", description: "Whisper to a player" },
      { syntax: "gossip / gs <msg>", description: "Global chat channel" },
      { syntax: "shout / sh <msg>", description: "Shout to your zone" },
      { syntax: "ooc <msg>", description: "Out-of-character channel" },
      { syntax: "emote <msg>", description: "Perform an emote" },
      { syntax: "pose <msg>", description: "Strike a pose" },
      { syntax: "who", description: "List online players" },
    ],
  },
  {
    name: "Inventory & Equipment",
    commands: [
      { syntax: "inventory / inv / i", description: "View your inventory" },
      { syntax: "equipment / eq", description: "View worn equipment" },
      { syntax: "wear / equip <item>", description: "Equip an item" },
      { syntax: "remove / unequip <slot>", description: "Unequip from a slot" },
      { syntax: "get / take <item>", description: "Pick up an item" },
      { syntax: "drop <item>", description: "Drop an item" },
      { syntax: "use <item>", description: "Use a consumable item" },
      { syntax: "give <item> <player>", description: "Give an item to a player" },
      { syntax: "put <item> in <container>", description: "Place an item in a container" },
      { syntax: "get <item> from <container>", description: "Take an item from a container" },
    ],
  },
  {
    name: "World Interaction",
    commands: [
      { syntax: "open <door|container>", description: "Open a door or container" },
      { syntax: "close <door|container>", description: "Close a door or container" },
      { syntax: "lock <door|container>", description: "Lock with a key" },
      { syntax: "unlock <door|container>", description: "Unlock with a key" },
      { syntax: "search <container>", description: "Search a container for its contents" },
      { syntax: "pull <object>", description: "Pull a lever or object" },
      { syntax: "read <object>", description: "Read a sign or inscription" },
    ],
  },
  {
    name: "Combat",
    commands: [
      { syntax: "kill <mob>", description: "Attack a mob" },
      { syntax: "flee", description: "Attempt to flee combat" },
      { syntax: "cast / c <spell> [target]", description: "Cast a spell or ability" },
      { syntax: "spells / abilities / skills", description: "List your abilities" },
      { syntax: "effects / buffs / debuffs", description: "View active status effects" },
    ],
  },
  {
    name: "Character",
    commands: [
      { syntax: "score / sc", description: "View your character sheet" },
      { syntax: "gold / balance", description: "Check your gold" },
      { syntax: "title <titleName>", description: "Set an earned title" },
      { syntax: "title clear", description: "Remove your title" },
      { syntax: "gender <male|female|enby>", description: "Set your gender" },
      { syntax: "sprite list", description: "View available sprites" },
      { syntax: "sprite set <id>", description: "Set your character sprite" },
      { syntax: "sprite default", description: "Reset to default sprite" },
    ],
  },
  {
    name: "NPCs & Shops",
    commands: [
      { syntax: "talk <npc>", description: "Start a conversation with an NPC" },
      { syntax: "list / shop", description: "Browse a shop's wares" },
      { syntax: "buy <item>", description: "Purchase from a shop" },
      { syntax: "sell <item>", description: "Sell to a shop" },
    ],
  },
  {
    name: "Quests & Achievements",
    commands: [
      { syntax: "quest log / list", description: "View active quests" },
      { syntax: "quest info <name>", description: "Quest details" },
      { syntax: "quest abandon <name>", description: "Abandon a quest" },
      { syntax: "accept <quest>", description: "Accept a quest from an NPC" },
      { syntax: "achievements / ach", description: "View achievements" },
    ],
  },
  {
    name: "Groups",
    commands: [
      { syntax: "group invite <player>", description: "Invite to your group" },
      { syntax: "group accept", description: "Accept a group invite" },
      { syntax: "group leave", description: "Leave your group" },
      { syntax: "group kick <player>", description: "Kick from group" },
      { syntax: "group list", description: "List group members" },
      { syntax: "gtell / gt <msg>", description: "Group chat" },
    ],
  },
  {
    name: "Guild",
    commands: [
      { syntax: "guild create <name> <tag>", description: "Create a guild" },
      { syntax: "guild disband", description: "Disband your guild" },
      { syntax: "guild invite <player>", description: "Invite to guild" },
      { syntax: "guild accept", description: "Accept a guild invite" },
      { syntax: "guild leave", description: "Leave your guild" },
      { syntax: "guild kick <player>", description: "Remove from guild" },
      { syntax: "guild promote / demote <player>", description: "Change member rank" },
      { syntax: "guild motd <message>", description: "Set guild message of the day" },
      { syntax: "guild roster", description: "View guild members" },
      { syntax: "guild info", description: "Guild overview" },
      { syntax: "gchat / g <msg>", description: "Guild chat" },
    ],
  },
  {
    name: "Friends",
    commands: [
      { syntax: "friend list", description: "View your friends list" },
      { syntax: "friend add <player>", description: "Add a friend" },
      { syntax: "friend remove <player>", description: "Remove a friend" },
    ],
  },
  {
    name: "Mail",
    commands: [
      { syntax: "mail list", description: "View your inbox" },
      { syntax: "mail read <number>", description: "Read a message" },
      { syntax: "mail send <player>", description: "Compose a message" },
      { syntax: "mail delete <number>", description: "Delete a message" },
      { syntax: "mail abort", description: "Cancel composing a message" },
    ],
  },
  {
    name: "Crafting",
    commands: [
      { syntax: "gather <node>", description: "Gather from a resource node" },
      { syntax: "craft <recipe>", description: "Craft an item from a recipe" },
      { syntax: "recipes [filter]", description: "Browse available recipes" },
      { syntax: "craftskills / professions", description: "View crafting skill levels" },
    ],
  },
  {
    name: "Utility",
    commands: [
      { syntax: "help / ?", description: "Show this help" },
      { syntax: "ansi on / off", description: "Toggle color output" },
      { syntax: "colors", description: "Preview ANSI color palette" },
      { syntax: "phase / layer", description: "Switch zone instance" },
      { syntax: "clear", description: "Clear the terminal" },
      { syntax: "quit / exit", description: "Disconnect" },
    ],
  },
  {
    name: "Staff",
    commands: [
      { syntax: "goto <zone:room>", description: "Teleport to a room" },
      { syntax: "transfer <player> <room>", description: "Move a player" },
      { syntax: "spawn <mob-template>", description: "Spawn a mob" },
      { syntax: "smite <player|mob>", description: "Instantly kill a target" },
      { syntax: "kick <player>", description: "Disconnect a player" },
      { syntax: "setlevel <player> <level>", description: "Set a player's level" },
      { syntax: "dispel <player|mob>", description: "Remove all effects" },
      { syntax: "reload [scope]", description: "Reload world data" },
      { syntax: "shutdown", description: "Shut down the server" },
    ],
  },
];

/** Build HelpCategory[] from Server.Commands GMCP data. */
function buildFromServerCommands(commands: CommandEntry[], isStaff: boolean): HelpCategory[] {
  const grouped = new Map<string, HelpCommand[]>();
  for (const cmd of commands) {
    if (cmd.staff && !isStaff) continue;
    const key = cmd.category;
    if (!grouped.has(key)) grouped.set(key, []);
    grouped.get(key)!.push({ syntax: cmd.usage, description: cmd.description });
  }

  const categories: HelpCategory[] = [];
  for (const key of CATEGORY_ORDER) {
    const cmds = grouped.get(key);
    if (!cmds) continue;
    categories.push({ name: CATEGORY_LABELS[key] ?? key, commands: cmds });
    grouped.delete(key);
  }
  // Append any categories not in CATEGORY_ORDER
  for (const [key, cmds] of grouped) {
    categories.push({ name: CATEGORY_LABELS[key] ?? key, commands: cmds });
  }
  return categories;
}

interface HelpContentProps {
  isStaff: boolean;
  serverCommands: CommandEntry[];
}

export function HelpContent({ isStaff, serverCommands }: HelpContentProps) {
  const [search, setSearch] = useState("");

  const filteredCategories = useMemo(() => {
    const categories =
      serverCommands.length > 0
        ? buildFromServerCommands(serverCommands, isStaff)
        : isStaff
          ? FALLBACK_CATEGORIES
          : FALLBACK_CATEGORIES.filter((cat) => cat.name !== "Staff");

    const query = search.trim().toLowerCase();
    if (query.length === 0) return categories;

    return categories
      .map((cat) => ({
        ...cat,
        commands: cat.commands.filter(
          (cmd) =>
            cmd.syntax.toLowerCase().includes(query) ||
            cmd.description.toLowerCase().includes(query),
        ),
      }))
      .filter((cat) => cat.commands.length > 0);
  }, [search, isStaff, serverCommands]);

  return (
    <div className="help-content">
      <div className="help-search-wrap">
        <input
          type="text"
          className="help-search-input"
          placeholder="Search commands..."
          aria-label="Search help commands"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          autoFocus
        />
      </div>

      {search.trim().length > 0 && (
        <div className="sr-only" aria-live="polite" aria-atomic="true">
          {filteredCategories.reduce((n, cat) => n + cat.commands.length, 0)} commands found
        </div>
      )}

      {filteredCategories.length === 0 ? (
        <p className="empty-note">No commands match your search.</p>
      ) : (
        <div className="help-category-list">
          {filteredCategories.map((cat) => (
            <section key={cat.name} className="help-category">
              <h3 className="help-category-title">{cat.name}</h3>
              <dl className="help-command-list">
                {cat.commands.map((cmd) => (
                  <div key={cmd.syntax} className="help-command-entry">
                    <dt className="help-command-syntax">{cmd.syntax}</dt>
                    <dd className="help-command-desc">{cmd.description}</dd>
                  </div>
                ))}
              </dl>
            </section>
          ))}
        </div>
      )}
    </div>
  );
}
