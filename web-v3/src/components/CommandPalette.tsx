import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { CommandEntry } from "../types";

interface CommandPaletteProps {
  commands: CommandEntry[];
  isStaff: boolean;
  onExecute: (command: string) => void;
  onPrefill: (text: string) => void;
  onClose: () => void;
}

const CATEGORY_ORDER = [
  "navigation",
  "communication",
  "items",
  "combat",
  "progression",
  "shops",
  "quests",
  "groups",
  "guilds",
  "crafting",
  "world",
  "social",
  "utility",
  "admin",
];

function categoryLabel(cat: string): string {
  return cat.charAt(0).toUpperCase() + cat.slice(1);
}

/**
 * Extract a runnable command prefix from a usage string.
 * For "tell/t <player> <msg>" this yields "tell".
 * For "guild create <name> <tag>" this yields "guild create".
 */
function extractCommandPrefix(usage: string): string {
  // Take the portion before the first '<' or '[' (angle-bracket arguments)
  const beforeArg = usage.split(/[<[]/)[0].trim();
  // Take the first alias (before any '/')
  const parts = beforeArg.split(/\s+/);
  const resolved = parts.map((p) => p.split("/")[0]);
  return resolved.join(" ").trim();
}

export function CommandPalette({
  commands,
  isStaff,
  onExecute,
  onPrefill,
  onClose,
}: CommandPaletteProps) {
  const [query, setQuery] = useState("");
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const [selectedIndex, setSelectedIndex] = useState(0);

  // Focus input on mount
  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  // Filter commands based on query and staff status
  const filtered = useMemo(() => {
    const visible = isStaff ? commands : commands.filter((c) => !c.staff);
    if (!query.trim()) return visible;
    const q = query.toLowerCase();
    return visible.filter(
      (c) =>
        c.name.toLowerCase().includes(q) ||
        c.usage.toLowerCase().includes(q) ||
        c.description.toLowerCase().includes(q) ||
        c.category.toLowerCase().includes(q),
    );
  }, [commands, isStaff, query]);

  // Group filtered commands by category, respecting CATEGORY_ORDER
  const grouped = useMemo(() => {
    const groups = new Map<string, CommandEntry[]>();
    for (const cmd of filtered) {
      const list = groups.get(cmd.category) ?? [];
      list.push(cmd);
      groups.set(cmd.category, list);
    }
    const ordered: Array<[string, CommandEntry[]]> = [];
    for (const cat of CATEGORY_ORDER) {
      const list = groups.get(cat);
      if (list) ordered.push([cat, list]);
    }
    // Any categories not in the order list go at the end
    for (const [cat, list] of groups) {
      if (!CATEGORY_ORDER.includes(cat)) ordered.push([cat, list]);
    }
    return ordered;
  }, [filtered]);

  // Flat list for keyboard navigation
  const flatItems = useMemo(() => filtered, [filtered]);

  // Reset selection when filter changes
  useEffect(() => {
    setSelectedIndex(0);
  }, [query]);

  const handleSelect = useCallback(
    (cmd: CommandEntry) => {
      const prefix = extractCommandPrefix(cmd.usage);
      if (cmd.requiresTarget) {
        onPrefill(prefix + " ");
      } else {
        onExecute(prefix);
      }
      onClose();
    },
    [onExecute, onPrefill, onClose],
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
        return;
      }
      if (e.key === "ArrowDown") {
        e.preventDefault();
        setSelectedIndex((prev) => Math.min(prev + 1, flatItems.length - 1));
        return;
      }
      if (e.key === "ArrowUp") {
        e.preventDefault();
        setSelectedIndex((prev) => Math.max(prev - 1, 0));
        return;
      }
      if (e.key === "Enter" && flatItems.length > 0) {
        e.preventDefault();
        const cmd = flatItems[selectedIndex];
        if (cmd) handleSelect(cmd);
      }
    },
    [flatItems, selectedIndex, handleSelect, onClose],
  );

  // Scroll selected item into view
  useEffect(() => {
    const container = listRef.current;
    if (!container) return;
    const el = container.querySelector(`[data-palette-index="${selectedIndex}"]`);
    if (el) el.scrollIntoView({ block: "nearest" });
  }, [selectedIndex]);

  // Build a global index counter for flat-item mapping
  let flatIndex = 0;

  return (
    <div className="cmd-palette-backdrop" onClick={onClose} role="presentation">
      <div
        className="cmd-palette"
        role="dialog"
        aria-modal="true"
        aria-label="Command Palette"
        onClick={(e) => e.stopPropagation()}
        onKeyDown={handleKeyDown}
      >
        <div className="cmd-palette-header">
          <div className="cmd-palette-search-row">
            <svg className="cmd-palette-search-icon" viewBox="0 0 20 20" fill="none" aria-hidden="true">
              <circle cx="8.5" cy="8.5" r="5.5" stroke="currentColor" strokeWidth="1.8" />
              <path d="M12.5 12.5 17 17" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
            <input
              ref={inputRef}
              className="cmd-palette-input"
              type="text"
              placeholder="Search commands..."
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              autoComplete="off"
              spellCheck={false}
            />
            <kbd className="cmd-palette-kbd">Esc</kbd>
          </div>
        </div>

        <div className="cmd-palette-list" ref={listRef}>
          {flatItems.length === 0 && (
            <div className="cmd-palette-empty">No commands match your search.</div>
          )}
          {grouped.map(([category, items]) => (
            <div key={category} className="cmd-palette-group">
              <div className="cmd-palette-group-label">{categoryLabel(category)}</div>
              {items.map((cmd) => {
                const idx = flatIndex++;
                return (
                  <button
                    key={cmd.name}
                    type="button"
                    className={`cmd-palette-item${idx === selectedIndex ? " cmd-palette-item-selected" : ""}${cmd.staff ? " cmd-palette-item-staff" : ""}`}
                    data-palette-index={idx}
                    onClick={() => handleSelect(cmd)}
                    onMouseEnter={() => setSelectedIndex(idx)}
                  >
                    <span className="cmd-palette-item-usage">{cmd.usage}</span>
                    <span className="cmd-palette-item-desc">{cmd.description}</span>
                    {cmd.staff && <span className="cmd-palette-item-badge">Staff</span>}
                  </button>
                );
              })}
            </div>
          ))}
        </div>

        <div className="cmd-palette-footer">
          <span className="cmd-palette-hint">
            <kbd className="cmd-palette-kbd-sm">&uarr;&darr;</kbd> Navigate
          </span>
          <span className="cmd-palette-hint">
            <kbd className="cmd-palette-kbd-sm">Enter</kbd> Select
          </span>
          <span className="cmd-palette-hint">
            <kbd className="cmd-palette-kbd-sm">Esc</kbd> Close
          </span>
        </div>
      </div>
    </div>
  );
}
