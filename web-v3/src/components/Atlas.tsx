import { useMemo, useState } from "react";
import type { WorldArea } from "../types";

interface AtlasProps {
  areas: WorldArea[];
  currentZone: string | null;
}

interface SortState {
  key: "zone" | "level";
  dir: "asc" | "desc";
}

function formatLevelRange(area: WorldArea): string {
  if (area.minLevel == null || area.maxLevel == null) return "—";
  if (area.minLevel === area.maxLevel) return String(area.minLevel);
  return `${area.minLevel}–${area.maxLevel}`;
}

function formatZoneName(zone: string): string {
  return zone
    .split("_")
    .filter((part) => part.length > 0)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

export function Atlas({ areas, currentZone }: AtlasProps) {
  const [search, setSearch] = useState("");
  const [minFilter, setMinFilter] = useState("");
  const [maxFilter, setMaxFilter] = useState("");
  const [showUnknown, setShowUnknown] = useState(true);
  const [sort, setSort] = useState<SortState>({ key: "level", dir: "asc" });

  const filtered = useMemo(() => {
    const min = minFilter.trim() === "" ? null : Number.parseInt(minFilter, 10);
    const max = maxFilter.trim() === "" ? null : Number.parseInt(maxFilter, 10);
    const minOk = min != null && Number.isFinite(min);
    const maxOk = max != null && Number.isFinite(max);
    const query = search.trim().toLowerCase();

    return areas.filter((area) => {
      if (query.length > 0) {
        const haystack = `${area.zone} ${formatZoneName(area.zone)}`.toLowerCase();
        if (!haystack.includes(query)) return false;
      }
      const unknownRange = area.minLevel == null || area.maxLevel == null;
      if (unknownRange) return showUnknown && !minOk && !maxOk;
      if (minOk && (area.maxLevel ?? 0) < min) return false;
      if (maxOk && (area.minLevel ?? 0) > max) return false;
      return true;
    });
  }, [areas, search, minFilter, maxFilter, showUnknown]);

  const sorted = useMemo(() => {
    const list = [...filtered];
    list.sort((a, b) => {
      if (sort.key === "zone") {
        const cmp = a.zone.localeCompare(b.zone);
        return sort.dir === "asc" ? cmp : -cmp;
      }
      // level sort — unknown ranges sink to the bottom regardless of direction
      const aMin = a.minLevel ?? Number.MAX_SAFE_INTEGER;
      const bMin = b.minLevel ?? Number.MAX_SAFE_INTEGER;
      const cmp = aMin - bMin || a.zone.localeCompare(b.zone);
      return sort.dir === "asc" ? cmp : -cmp;
    });
    return list;
  }, [filtered, sort]);

  function toggleSort(key: SortState["key"]) {
    setSort((prev) => (prev.key === key ? { key, dir: prev.dir === "asc" ? "desc" : "asc" } : { key, dir: "asc" }));
  }

  function sortIndicator(key: SortState["key"]): string {
    if (sort.key !== key) return "";
    return sort.dir === "asc" ? " ▲" : " ▼";
  }

  function ariaSort(key: SortState["key"]): "ascending" | "descending" | undefined {
    if (sort.key !== key) return undefined;
    return sort.dir === "asc" ? "ascending" : "descending";
  }

  if (areas.length === 0) {
    return (
      <div className="atlas-empty">
        <p>No areas reported by the server yet.</p>
      </div>
    );
  }

  return (
    <div className="atlas-body">
      <div className="atlas-toolbar" role="search">
        <label className="atlas-field">
          <span className="atlas-field-label">Search</span>
          <input
            className="atlas-input"
            type="search"
            placeholder="zone name…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </label>
        <label className="atlas-field atlas-field-narrow">
          <span className="atlas-field-label">Min lvl</span>
          <input
            className="atlas-input"
            type="number"
            inputMode="numeric"
            min={1}
            placeholder="—"
            value={minFilter}
            onChange={(e) => setMinFilter(e.target.value)}
          />
        </label>
        <label className="atlas-field atlas-field-narrow">
          <span className="atlas-field-label">Max lvl</span>
          <input
            className="atlas-input"
            type="number"
            inputMode="numeric"
            min={1}
            placeholder="—"
            value={maxFilter}
            onChange={(e) => setMaxFilter(e.target.value)}
          />
        </label>
        <label className="atlas-checkbox">
          <input
            type="checkbox"
            checked={showUnknown}
            onChange={(e) => setShowUnknown(e.target.checked)}
          />
          <span>Unlisted ranges</span>
        </label>
        <span className="atlas-count" role="status" aria-live="polite">
          {sorted.length} of {areas.length}
        </span>
      </div>

      <div className="atlas-table-wrapper">
        <table className="atlas-table">
          <thead>
            <tr>
              <th scope="col" aria-sort={ariaSort("zone")}>
                <button type="button" className="atlas-sort" onClick={() => toggleSort("zone")}>
                  Area<span aria-hidden="true">{sortIndicator("zone")}</span>
                </button>
              </th>
              <th scope="col" className="atlas-col-level" aria-sort={ariaSort("level")}>
                <button type="button" className="atlas-sort" onClick={() => toggleSort("level")}>
                  Level<span aria-hidden="true">{sortIndicator("level")}</span>
                </button>
              </th>
            </tr>
          </thead>
          <tbody>
            {sorted.length === 0 ? (
              <tr>
                <td colSpan={2} className="atlas-no-results">
                  No areas match the current filters.
                </td>
              </tr>
            ) : (
              sorted.map((area) => {
                const here = area.zone === currentZone;
                return (
                  <tr key={area.zone} className={here ? "atlas-row-current" : undefined}>
                    <th scope="row" className="atlas-cell-zone">
                      <span className="atlas-zone-name">{formatZoneName(area.zone)}</span>
                      <span className="atlas-zone-id">{area.zone}</span>
                      {here && (
                        <span className="atlas-here-pill" aria-label="You are here">
                          you are here
                        </span>
                      )}
                    </th>
                    <td className="atlas-cell-level">{formatLevelRange(area)}</td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
