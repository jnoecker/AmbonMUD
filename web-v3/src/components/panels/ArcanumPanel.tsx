import { useEffect, useMemo, useRef, useState } from "react";
import type { KeyboardEvent } from "react";
import type {
  ArcanumItemEntry,
  ArcanumJournal,
  ArcanumMobEntry,
  ArcanumRoomEntry,
  ArcanumStatus,
} from "../../types";
import { formatZoneName } from "../../utils";

type ArcanumTab = "mobs" | "items" | "rooms";
type ArcanumSort = "name" | "date";

const ARCANUM_TABS: readonly ArcanumTab[] = ["mobs", "items", "rooms"];

const SOURCE_LABEL: Record<string, string> = {
  illuminated: "Illuminated",
  observed: "Observed",
  visited: "Visited",
  purchased: "Purchased",
  crafted: "Crafted",
  gathered: "Gathered",
};

/** Sources that can appear on item pages, in chip order. */
const ITEM_SOURCES = ["illuminated", "observed", "purchased", "crafted", "gathered"];

/** Incremental rendering: cards revealed per "Show more" click. */
const PAGE_SIZE = 60;

interface Props {
  journal: ArcanumJournal | null;
  status: ArcanumStatus | null;
  /** The viewing player's name — their own world-firsts glow. */
  playerName: string;
  connected: boolean;
  onCommand: (cmd: string) => void;
}

/**
 * The Arcanum — the Akathavae's illuminated journal, rendered with the same
 * field-manual sensibility as the monster manual but filtered to what this
 * player has personally recorded: creatures, items, and places, with per-zone
 * completion and permanent world-first credits ("First illuminated by Thalen").
 *
 * Built for the completionist: every tab is searchable, creatures and items
 * sort by name or date recorded, filter chips isolate personal world-firsts
 * and item sources, places group by zone, and large journals render
 * incrementally via "Show more".
 */
export function ArcanumPanel({ journal, status, playerName, connected, onCommand }: Props) {
  const [tab, setTab] = useState<ArcanumTab>("mobs");
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState<ArcanumSort>("name");
  const [firstsOnly, setFirstsOnly] = useState(false);
  const [sourceFilter, setSourceFilter] = useState<string | null>(null);
  // Incremental rendering, keyed to the current lens: any change of tab,
  // search, sort, or filter starts "Show more" over from the first page.
  const lens = `${tab}|${query}|${sort}|${firstsOnly}|${sourceFilter ?? ""}`;
  const [shown, setShown] = useState({ lens, count: PAGE_SIZE });
  const visible = shown.lens === lens ? shown.count : PAGE_SIZE;
  const autoLoaded = useRef(false);

  // Fetch the full journal once on open; Arcanum.Journal GMCP fills `journal`.
  useEffect(() => {
    if (!autoLoaded.current && connected) {
      autoLoaded.current = true;
      onCommand("arcanum");
    }
  }, [connected, onCommand]);

  const trimmedQuery = query.trim().toLowerCase();

  const mobs = useMemo(() => {
    if (!journal) return [];
    let list = journal.mobs;
    if (trimmedQuery) list = list.filter((m) => m.name.toLowerCase().includes(trimmedQuery));
    if (firstsOnly) list = list.filter((m) => m.firstBy === playerName || m.firstSlainBy === playerName);
    return sortEntries(list, sort);
  }, [journal, trimmedQuery, firstsOnly, sort, playerName]);

  const items = useMemo(() => {
    if (!journal) return [];
    let list = journal.items;
    if (trimmedQuery) list = list.filter((i) => i.name.toLowerCase().includes(trimmedQuery));
    if (firstsOnly) list = list.filter((i) => i.firstBy === playerName);
    if (sourceFilter) list = list.filter((i) => i.source === sourceFilter);
    return sortEntries(list, sort);
  }, [journal, trimmedQuery, firstsOnly, sourceFilter, sort, playerName]);

  const rooms = useMemo(() => {
    if (!journal) return [];
    let list = journal.rooms;
    if (trimmedQuery) list = list.filter((r) => r.title.toLowerCase().includes(trimmedQuery));
    if (firstsOnly) list = list.filter((r) => r.firstBy === playerName);
    // Places group by zone (alphabetical), titles alphabetical within each zone.
    return [...list].sort(
      (a, b) => a.zone.localeCompare(b.zone) || a.title.localeCompare(b.title),
    );
  }, [journal, trimmedQuery, firstsOnly, playerName]);

  /** Item sources actually present in this journal, so chips never sit dead. */
  const presentSources = useMemo(() => {
    if (!journal) return [];
    const seen = new Set(journal.items.map((i) => i.source));
    return ITEM_SOURCES.filter((s) => seen.has(s));
  }, [journal]);

  const pledged = journal?.pledged ?? status?.pledged ?? false;

  if (!journal) {
    return (
      <div className="arcanum-panel arcanum-panel-loading">
        <div className="arcanum-loading" role="status" aria-live="polite">
          <span className="arcanum-loading-sigil" aria-hidden="true">✦</span>
          <h2 className="arcanum-heading">The Arcanum</h2>
          <p className="arcanum-empty">Opening your field folio&hellip;</p>
        </div>
      </div>
    );
  }

  const counts: Record<ArcanumTab, number> = {
    mobs: journal.mobs.length,
    items: journal.items.length,
    rooms: journal.rooms.length,
  };
  const filtered: Record<ArcanumTab, number> = {
    mobs: mobs.length,
    items: items.length,
    rooms: rooms.length,
  };
  const activeCount = filtered[tab];
  const filtersActive = trimmedQuery.length > 0 || firstsOnly || (tab === "items" && sourceFilter !== null);

  const firstLine = (firstBy: string | null, verb = "illuminated") => {
    if (!firstBy) return null;
    const mine = firstBy === playerName;
    return (
      <span className={`arcanum-first${mine ? " arcanum-first-mine" : ""}`}>
        ★ First {verb} by {firstBy}
      </span>
    );
  };

  const showMore = (total: number) =>
    total > visible && (
      <button
        type="button"
        className="arcanum-show-more"
        onClick={() => setShown({ lens, count: visible + PAGE_SIZE })}
      >
        Show more ({total - visible} remaining)
      </button>
    );

  /** Empty-state copy: distinguish a blank section from an over-filtered one. */
  const emptyMessage = (blankText: string) => (
    <p className="arcanum-empty">
      {counts[tab] === 0 ? blankText : "No pages match your search or filters."}
    </p>
  );

  const mobCard = (m: ArcanumMobEntry) => (
    <article key={m.key} className="arcanum-card" aria-label={m.name}>
      {m.image && (
        <img className="arcanum-card-art" src={m.image} alt="" loading="lazy" decoding="async" />
      )}
      <div className="arcanum-card-body">
        <span className="arcanum-card-name">{m.name}</span>
        <span className="arcanum-card-meta">
          {SOURCE_LABEL[m.source] ?? m.source}
          {m.timesRecorded > 1 ? ` ×${m.timesRecorded}` : ""}
        </span>
        {firstLine(m.firstBy)}
        {firstLine(m.firstSlainBy, "slain")}
      </div>
    </article>
  );

  const itemCard = (i: ArcanumItemEntry) => (
    <article key={i.key} className="arcanum-card" aria-label={i.name}>
      {i.image && (
        <img className="arcanum-card-art" src={i.image} alt="" loading="lazy" decoding="async" />
      )}
      <div className="arcanum-card-body">
        <span className="arcanum-card-name">{i.name}</span>
        <span className="arcanum-card-meta">
          {SOURCE_LABEL[i.source] ?? i.source}
          {i.slot ? ` · ${i.slot}` : ""}
        </span>
        {firstLine(i.firstBy)}
        {pledged && i.wearable && (
          <button
            type="button"
            className="arcanum-conjure-btn"
            disabled={!connected}
            onClick={() => onCommand(`wardrobe ${i.name}`)}
          >
            Conjure &amp; Wear
          </button>
        )}
      </div>
    </article>
  );

  /** Visible slice of places, grouped by zone with a labelled sticky header per zone. */
  const roomGroups = () => {
    const groups: Array<{ zone: string; rooms: ArcanumRoomEntry[] }> = [];
    for (const r of rooms.slice(0, visible)) {
      const last = groups[groups.length - 1];
      if (last && last.zone === r.zone) last.rooms.push(r);
      else groups.push({ zone: r.zone, rooms: [r] });
    }
    return groups;
  };

  const moveTabFocus = (event: KeyboardEvent<HTMLButtonElement>, current: ArcanumTab) => {
    const currentIndex = ARCANUM_TABS.indexOf(current);
    let nextIndex: number | null = null;
    if (event.key === "ArrowRight") nextIndex = (currentIndex + 1) % ARCANUM_TABS.length;
    if (event.key === "ArrowLeft") nextIndex = (currentIndex - 1 + ARCANUM_TABS.length) % ARCANUM_TABS.length;
    if (event.key === "Home") nextIndex = 0;
    if (event.key === "End") nextIndex = ARCANUM_TABS.length - 1;
    if (nextIndex === null) return;
    event.preventDefault();
    const nextTab = ARCANUM_TABS[nextIndex];
    setTab(nextTab);
    window.requestAnimationFrame(() => document.getElementById(`arcanum-tab-${nextTab}`)?.focus());
  };

  return (
    <div className="arcanum-panel">
      <header className="arcanum-intro">
        <span className="arcanum-kicker">Living field folio</span>
        <h2 className="arcanum-heading">The Arcanum</h2>
        <p className="arcanum-pledge-line">
          {pledged
            ? "You are an Akathavae — a keeper of the Arcanum. The world is your subject."
            : "A field journal of your own travels. Only a pledged Akathavae's record is accepted at the shrines."}
        </p>
      </header>

      <aside className="arcanum-zones" aria-label="Journey completion by zone">
        <div className="arcanum-zone-title">
          <span>Journey ledger</span>
          <span className="arcanum-zone-count">
            {journal.zones.length} {journal.zones.length === 1 ? "realm" : "realms"}
          </span>
        </div>
        <div className="arcanum-zone-list">
          {journal.zones.length === 0 && (
            <p className="arcanum-zone-empty">Your first journey has yet to be recorded.</p>
          )}
          {journal.zones.map((z) => {
            const total = z.roomsTotal + z.mobsTotal + z.itemsTotal;
            const recorded = z.roomsRecorded + z.mobsRecorded + z.itemsRecorded;
            const pct = total > 0 ? Math.round((recorded / total) * 100) : 0;
            const zoneName = formatZoneName(z.zone);
            return (
              <div key={z.zone} className="arcanum-zone">
                <div className="arcanum-zone-head">
                  <span className="arcanum-zone-name" title={zoneName}>{zoneName}</span>
                  <span className="arcanum-zone-pct">{pct}%</span>
                </div>
                <div
                  className="arcanum-zone-bar"
                  role="progressbar"
                  aria-valuenow={pct}
                  aria-valuemin={0}
                  aria-valuemax={100}
                  aria-label={`${zoneName} completion`}
                >
                  <div className="arcanum-zone-fill" style={{ width: `${pct}%` }} />
                </div>
                <div className="arcanum-zone-detail">
                  {z.roomsRecorded}/{z.roomsTotal} places · {z.mobsRecorded}/{z.mobsTotal} creatures ·{" "}
                  {z.itemsRecorded}/{z.itemsTotal} items
                </div>
              </div>
            );
          })}
        </div>
      </aside>

      <section className="arcanum-catalog" aria-label="Recorded pages">
        <div className="arcanum-controls">
          <div className="arcanum-search-wrap">
            <label className="sr-only" htmlFor="arcanum-search">Search the Arcanum</label>
            <span className="arcanum-search-sigil" aria-hidden="true">⌕</span>
            <input
              id="arcanum-search"
              type="search"
              className="arcanum-search"
              placeholder="Search these pages…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </div>
          {tab !== "rooms" && (
            <select
              className="arcanum-sort"
              aria-label="Sort entries"
              value={sort}
              onChange={(e) => setSort(e.target.value as ArcanumSort)}
            >
              <option value="name">By name</option>
              <option value="date">Newest first</option>
            </select>
          )}
          <div className="arcanum-chips" role="group" aria-label="Journal filters">
            <button
              type="button"
              className={`arcanum-chip${firstsOnly ? " arcanum-chip-active" : ""}`}
              aria-pressed={firstsOnly}
              onClick={() => setFirstsOnly((v) => !v)}
            >
              ★ My firsts
            </button>
            {tab === "items" &&
              presentSources.map((s) => (
                <button
                  type="button"
                  key={s}
                  className={`arcanum-chip${sourceFilter === s ? " arcanum-chip-active" : ""}`}
                  aria-pressed={sourceFilter === s}
                  onClick={() => setSourceFilter((cur) => (cur === s ? null : s))}
                >
                  {SOURCE_LABEL[s]}
                </button>
              ))}
          </div>
        </div>

        <div className="arcanum-tabs" role="tablist" aria-label="Arcanum sections">
          {ARCANUM_TABS.map((t) => (
            <button
              type="button"
              id={`arcanum-tab-${t}`}
              key={t}
              role="tab"
              tabIndex={tab === t ? 0 : -1}
              aria-selected={tab === t}
              aria-controls={`arcanum-panel-${t}`}
              className={`arcanum-tab${tab === t ? " arcanum-tab-active" : ""}`}
              onClick={() => setTab(t)}
              onKeyDown={(event) => moveTabFocus(event, t)}
            >
              <span>{t === "mobs" ? "Creatures" : t === "items" ? "Items" : "Places"}</span>
              <span className="arcanum-tab-count">{counts[t]}</span>
            </button>
          ))}
        </div>

        <div className="arcanum-results">
          {filtersActive && (
            <p className="arcanum-filter-note" aria-live="polite">
              Showing {activeCount} of {counts[tab]}{" "}
              {tab === "mobs" ? "creatures" : tab === "items" ? "items" : "places"}.
            </p>
          )}

          {tab === "mobs" && (
            <div
              id="arcanum-panel-mobs"
              role="tabpanel"
              aria-labelledby="arcanum-tab-mobs"
              className="arcanum-tab-panel"
            >
              <div className="arcanum-grid">
                {mobs.length === 0 && emptyMessage("No creatures illuminated yet.")}
                {mobs.slice(0, visible).map(mobCard)}
              </div>
              {showMore(mobs.length)}
            </div>
          )}

          {tab === "items" && (
            <div
              id="arcanum-panel-items"
              role="tabpanel"
              aria-labelledby="arcanum-tab-items"
              className="arcanum-tab-panel"
            >
              <div className="arcanum-grid">
                {items.length === 0 && emptyMessage("No items recorded yet.")}
                {items.slice(0, visible).map(itemCard)}
              </div>
              {showMore(items.length)}
            </div>
          )}

          {tab === "rooms" && (
            <div
              id="arcanum-panel-rooms"
              role="tabpanel"
              aria-labelledby="arcanum-tab-rooms"
              className="arcanum-tab-panel"
            >
              <div className="arcanum-rooms">
                {rooms.length === 0 && emptyMessage("No places recorded yet.")}
                {roomGroups().map((g) => (
                  <section key={g.zone} className="arcanum-room-group">
                    <h3 className="arcanum-room-group-head">{formatZoneName(g.zone)}</h3>
                    {g.rooms.map((r) => (
                      <div key={r.key} className="arcanum-room-row">
                        <span className="arcanum-room-title">{r.title}</span>
                        {firstLine(r.firstBy)}
                      </div>
                    ))}
                  </section>
                ))}
              </div>
              {showMore(rooms.length)}
            </div>
          )}
        </div>
      </section>
    </div>
  );
}

/** Name (default) or newest-recorded-first ordering for creature and item cards. */
function sortEntries<T extends ArcanumMobEntry | ArcanumItemEntry>(list: readonly T[], sort: ArcanumSort): T[] {
  const sorted = [...list];
  if (sort === "date") {
    sorted.sort((a, b) => b.firstRecordedAtMs - a.firstRecordedAtMs || a.name.localeCompare(b.name));
  } else {
    sorted.sort((a, b) => a.name.localeCompare(b.name));
  }
  return sorted;
}
