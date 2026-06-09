import { useMemo, useState } from "react";
import type { CommandEntry } from "../types";

interface HelpCommand {
  name: string;
  syntax: string;
  description: string;
}

interface HelpCategory {
  key: string;
  name: string;
  glyph: string;
  commands: HelpCommand[];
}

const CATEGORY_LABELS: Record<string, string> = {
  navigation: "Navigation",
  communication: "Communication",
  items: "Inventory",
  world: "World Interaction",
  combat: "Combat",
  progression: "Character",
  social: "NPCs & Social",
  shops: "Shops",
  quests: "Quests",
  groups: "Groups",
  guilds: "Guild",
  crafting: "Crafting",
  utility: "Utility",
  admin: "Staff",
};

// Glyph fallbacks per category (a painted icon set can replace these later).
const CATEGORY_GLYPHS: Record<string, string> = {
  navigation: "✦",
  communication: "❝",
  items: "▤",
  world: "❦",
  combat: "⚔",
  progression: "❂",
  social: "❧",
  shops: "⚖",
  quests: "★",
  groups: "⚇",
  guilds: "⚜",
  crafting: "⚒",
  utility: "⚙",
  admin: "✪",
};

const CATEGORY_ORDER: string[] = [
  "navigation", "communication", "items", "world", "combat", "progression",
  "social", "shops", "quests", "groups", "guilds", "crafting", "utility", "admin",
];

function buildCategories(commands: CommandEntry[], isStaff: boolean): HelpCategory[] {
  const grouped = new Map<string, HelpCommand[]>();
  for (const cmd of commands) {
    if (cmd.staff && !isStaff) continue;
    if (!grouped.has(cmd.category)) grouped.set(cmd.category, []);
    grouped.get(cmd.category)!.push({ name: cmd.name, syntax: cmd.usage, description: cmd.description });
  }
  const ordered: HelpCategory[] = [];
  const seen = new Set<string>();
  for (const key of CATEGORY_ORDER) {
    const cmds = grouped.get(key);
    if (!cmds) continue;
    ordered.push({ key, name: CATEGORY_LABELS[key] ?? key, glyph: CATEGORY_GLYPHS[key] ?? "✦", commands: cmds });
    seen.add(key);
  }
  for (const [key, cmds] of grouped) {
    if (seen.has(key)) continue;
    ordered.push({ key, name: CATEGORY_LABELS[key] ?? key, glyph: CATEGORY_GLYPHS[key] ?? "✦", commands: cmds });
  }
  return ordered;
}

interface HelpContentProps {
  isStaff: boolean;
  serverCommands: CommandEntry[];
}

/**
 * Command Reference — the painted open-book "Arcanum". Categories down the
 * left page, a searchable command table (command / usage / description) across
 * the two parchment pages. The frame comes from the `command_reference_bg`
 * skin; content is inset into the painted pages.
 */
export function HelpContent({ isStaff, serverCommands }: HelpContentProps) {
  const [search, setSearch] = useState("");
  const [activeCategory, setActiveCategory] = useState<string | null>(null);

  const categories = useMemo(() => buildCategories(serverCommands, isStaff), [serverCommands, isStaff]);

  const query = search.trim().toLowerCase();
  const current = activeCategory ?? categories[0]?.key ?? null;

  // Search spans every category; otherwise show the selected category's commands.
  const rows = useMemo(() => {
    if (query.length > 0) {
      return categories.flatMap((cat) =>
        cat.commands
          .filter((c) => c.name.toLowerCase().includes(query) || c.syntax.toLowerCase().includes(query) || c.description.toLowerCase().includes(query))
          .map((c) => ({ ...c, glyph: cat.glyph })),
      );
    }
    const cat = categories.find((c) => c.key === current);
    return cat ? cat.commands.map((c) => ({ ...c, glyph: cat.glyph })) : [];
  }, [query, categories, current]);

  if (serverCommands.length === 0) {
    return (
      <div className="command-reference command-reference-loading">
        <p className="cr-empty">Inscribing the Arcanum…</p>
      </div>
    );
  }

  return (
    <div className="command-reference">
      {/* ── Categories (left page) ───────────────────────────── */}
      <nav className="cr-categories" aria-label="Command categories">
        {categories.map((cat) => (
          <button
            key={cat.key}
            type="button"
            className={`cr-cat${current === cat.key && query.length === 0 ? " is-active" : ""}`}
            onClick={() => { setActiveCategory(cat.key); setSearch(""); }}
            aria-pressed={current === cat.key && query.length === 0}
          >
            <span className="cr-cat-glyph" aria-hidden="true">{cat.glyph}</span>
            <span className="cr-cat-name">{cat.name}</span>
          </button>
        ))}
      </nav>

      {/* ── Search + command table (center pages) ────────────── */}
      <div className="cr-main">
        <div className="cr-search-wrap">
          <span className="cr-search-icon" aria-hidden="true">⚲</span>
          <input
            type="text"
            className="cr-search"
            placeholder="Search commands…"
            aria-label="Search commands"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            autoFocus
          />
        </div>

        <div className="cr-table-head" aria-hidden="true">
          <span className="cr-col-cmd">Command</span>
          <span className="cr-col-usage">Usage</span>
          <span className="cr-col-desc">Description</span>
        </div>

        <div className="cr-rows" role="list">
          {rows.length === 0 ? (
            <p className="cr-empty">No commands match your search.</p>
          ) : (
            rows.map((r) => (
              <div key={`${r.name}-${r.syntax}`} className="cr-row" role="listitem">
                <span className="cr-col-cmd">
                  <span className="cr-row-glyph" aria-hidden="true">{r.glyph}</span>
                  <span className="cr-cmd-name">{r.name}</span>
                </span>
                <span className="cr-col-usage">{r.syntax}</span>
                <span className="cr-col-desc">{r.description}</span>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
}
