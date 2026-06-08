import { useEffect, useMemo, useRef, useState } from "react";
import type { WhoPlayer } from "../../types";

type WhoSortField = "name" | "level" | "race" | "class";
type SortDir = "asc" | "desc";

interface WhoBoardPanelProps {
  connected: boolean;
  canChat: boolean;
  playerName: string;
  whoPlayers: WhoPlayer[];
  serverAssets: Record<string, string>;
  /** Names already on the player's friends list — hides the Friend action for them. */
  friendNames: string[];
  onRequestWho: () => void;
  onExamine: (player: WhoPlayer) => void;
  onTellPlayer: (name: string) => void;
  onAddFriend: (name: string) => void;
}

/**
 * Standalone "Who" board — the player roster split out of the Social panel
 * into the celestial `who_bg` frame. Everything inside is generated DOM (no
 * paint-matching): a portrait column showing each player's sprite, then name +
 * title, class, race, level, and the Examine / Send Tell action buttons.
 */
export function WhoBoardPanel({
  connected,
  canChat,
  playerName,
  whoPlayers,
  serverAssets,
  friendNames,
  onRequestWho,
  onExamine,
  onTellPlayer,
  onAddFriend,
}: WhoBoardPanelProps) {
  const [filter, setFilter] = useState("");
  const [classFilter, setClassFilter] = useState("");
  const [sort, setSort] = useState<WhoSortField>("name");
  const [sortDir, setSortDir] = useState<SortDir>("asc");

  // Refresh the roster every time the board opens — the panel remounts on open,
  // so descriptions/levels/titles reflect the latest state without a manual refresh.
  const requested = useRef(false);
  useEffect(() => {
    if (canChat && !requested.current) {
      requested.current = true;
      onRequestWho();
    }
  }, [canChat, onRequestWho]);

  const classes = useMemo(
    () => Array.from(new Set(whoPlayers.map((p) => p.playerClass).filter(Boolean))).sort(),
    [whoPlayers],
  );

  const rows = useMemo(() => {
    const lower = filter.toLowerCase();
    const filtered = whoPlayers.filter((p) => {
      if (classFilter && p.playerClass !== classFilter) return false;
      if (!lower) return true;
      return (
        p.name.toLowerCase().includes(lower) ||
        p.race.toLowerCase().includes(lower) ||
        p.playerClass.toLowerCase().includes(lower) ||
        (p.title?.toLowerCase().includes(lower) ?? false)
      );
    });
    const dir = sortDir === "asc" ? 1 : -1;
    return [...filtered].sort((a, b) => {
      switch (sort) {
        case "level": return (a.level - b.level) * dir;
        case "race": return a.race.localeCompare(b.race) * dir;
        case "class": return a.playerClass.localeCompare(b.playerClass) * dir;
        default: return a.name.localeCompare(b.name) * dir;
      }
    });
  }, [whoPlayers, filter, classFilter, sort, sortDir]);

  const toggleSort = (field: WhoSortField) => {
    if (sort === field) setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    else { setSort(field); setSortDir("asc"); }
  };
  const sortMark = (field: WhoSortField) => (sort === field ? (sortDir === "asc" ? " ▴" : " ▾") : "");

  const examineArt = serverAssets["who_examine_btn"];
  const tellArt = serverAssets["who_tell_btn"];
  const friendArt = serverAssets["who_friend_btn"];
  const friendSet = useMemo(() => new Set(friendNames.map((n) => n.toLowerCase())), [friendNames]);

  return (
    <div className="whoboard">
      <div className="whoboard-grid" role="table" aria-label="Players online">
        <div className="whoboard-head" role="row">
          <span className="whoboard-col-portrait" role="columnheader" aria-label="Sprite" />
          <button type="button" role="columnheader" className="whoboard-th whoboard-col-name" onClick={() => toggleSort("name")}>Name{sortMark("name")}</button>
          <button type="button" role="columnheader" className="whoboard-th whoboard-col-class" onClick={() => toggleSort("class")}>Class{sortMark("class")}</button>
          <button type="button" role="columnheader" className="whoboard-th whoboard-col-race" onClick={() => toggleSort("race")}>Race{sortMark("race")}</button>
          <button type="button" role="columnheader" className="whoboard-th whoboard-col-level" onClick={() => toggleSort("level")}>Level{sortMark("level")}</button>
          <span className="whoboard-col-actions" role="columnheader">Actions</span>
        </div>

        <div className="whoboard-body">
          {!canChat ? (
            <p className="whoboard-empty">{connected ? "Log in to see who's online." : "Reconnect to load the roster."}</p>
          ) : rows.length === 0 ? (
            <p className="whoboard-empty">{whoPlayers.length === 0 ? "No players online yet." : "No players match your filter."}</p>
          ) : (
            rows.map((p) => {
              const isSelf = p.name.localeCompare(playerName, undefined, { sensitivity: "accent" }) === 0;
              return (
                <div key={p.name} className="whoboard-row" role="row">
                  <span className="whoboard-col-portrait">
                    <span className="whoboard-portrait">
                      {p.sprite
                        ? <img className="whoboard-sprite" src={p.sprite} alt="" draggable={false} />
                        : <span className="whoboard-sprite-empty" aria-hidden="true" />}
                    </span>
                  </span>
                  <span className="whoboard-col-name">
                    <span className="whoboard-name">{p.name}</span>
                    {p.title && <span className="whoboard-title">{p.title}</span>}
                  </span>
                  <span className="whoboard-col-class">{p.playerClass}</span>
                  <span className="whoboard-col-race">{p.race}</span>
                  <span className="whoboard-col-level">{p.level}</span>
                  <span className="whoboard-col-actions">
                    <button
                      type="button"
                      className="whoboard-action"
                      title={`Examine ${p.name}`}
                      aria-label={`Examine ${p.name}`}
                      onClick={() => onExamine(p)}
                    >
                      {examineArt ? <img src={examineArt} alt="" aria-hidden="true" /> : <span className="whoboard-action-glyph" aria-hidden="true">❦</span>}
                    </button>
                    {!isSelf && (
                      <button
                        type="button"
                        className="whoboard-action"
                        title={`Send a tell to ${p.name}`}
                        aria-label={`Send a tell to ${p.name}`}
                        onClick={() => onTellPlayer(p.name)}
                      >
                        {tellArt ? <img src={tellArt} alt="" aria-hidden="true" /> : <span className="whoboard-action-glyph" aria-hidden="true">✉</span>}
                      </button>
                    )}
                    {!isSelf && !friendSet.has(p.name.toLowerCase()) && (
                      <button
                        type="button"
                        className="whoboard-action"
                        title={`Add ${p.name} as a friend`}
                        aria-label={`Add ${p.name} as a friend`}
                        onClick={() => onAddFriend(p.name)}
                      >
                        {friendArt ? <img src={friendArt} alt="" aria-hidden="true" /> : <span className="whoboard-action-glyph" aria-hidden="true">✚</span>}
                      </button>
                    )}
                  </span>
                </div>
              );
            })
          )}
        </div>
      </div>

      <div className="whoboard-footer">
        <span className="whoboard-count">Total Online: <strong>{whoPlayers.length}</strong></span>
        <input
          type="text"
          className="whoboard-search"
          placeholder="Search by name…"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          aria-label="Search players by name"
        />
        <select
          className="whoboard-classfilter"
          value={classFilter}
          onChange={(e) => setClassFilter(e.target.value)}
          aria-label="Filter by class"
        >
          <option value="">All Classes</option>
          {classes.map((c) => <option key={c} value={c}>{c}</option>)}
        </select>
        <button type="button" className="whoboard-refresh" onClick={onRequestWho} disabled={!canChat} title="Refresh">⟳</button>
      </div>
    </div>
  );
}
