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
    if (serverCommands.length === 0) return [];
    const categories = buildFromServerCommands(serverCommands, isStaff);

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

  if (serverCommands.length === 0) {
    return (
      <div className="help-content">
        <p className="empty-note">Loading commands&hellip;</p>
      </div>
    );
  }

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
