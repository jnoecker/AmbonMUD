import { useMemo, useState } from "react";

interface HelpCommand {
  syntax: string;
  description: string;
}

interface HelpCategory {
  name: string;
  commands: HelpCommand[];
}

const HELP_CATEGORIES: HelpCategory[] = [
  {
    name: "Navigation",
    commands: [
      { syntax: "look / l", description: "Look around the room (or look <direction>)" },
      { syntax: "n / s / e / w / u / d", description: "Move in a direction" },
      { syntax: "exits / ex", description: "List available exits" },
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
    name: "Utility",
    commands: [
      { syntax: "help / ?", description: "Show this help" },
      { syntax: "ansi on / off", description: "Toggle color output" },
      { syntax: "colors", description: "Preview ANSI color palette" },
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
      { syntax: "dispel <player|mob>", description: "Remove all effects" },
      { syntax: "shutdown", description: "Shut down the server" },
    ],
  },
];

interface HelpContentProps {
  isStaff: boolean;
}

export function HelpContent({ isStaff }: HelpContentProps) {
  const [search, setSearch] = useState("");

  const filteredCategories = useMemo(() => {
    const query = search.trim().toLowerCase();
    const categories = isStaff
      ? HELP_CATEGORIES
      : HELP_CATEGORIES.filter((cat) => cat.name !== "Staff");

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
  }, [search, isStaff]);

  return (
    <div className="help-content">
      <div className="help-search-wrap">
        <input
          type="text"
          className="help-search-input"
          placeholder="Search commands..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          autoFocus
        />
      </div>

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
