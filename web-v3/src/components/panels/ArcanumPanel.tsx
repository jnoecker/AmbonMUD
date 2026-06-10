import { useEffect, useRef, useState } from "react";
import type { ArcanumJournal, ArcanumStatus } from "../../types";

type ArcanumTab = "mobs" | "items" | "rooms";

const SOURCE_LABEL: Record<string, string> = {
  illuminated: "Illuminated",
  observed: "Observed",
  visited: "Visited",
  purchased: "Purchased",
  crafted: "Crafted",
  gathered: "Gathered",
};

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
 */
export function ArcanumPanel({ journal, status, playerName, connected, onCommand }: Props) {
  const [tab, setTab] = useState<ArcanumTab>("mobs");
  const autoLoaded = useRef(false);

  // Fetch the full journal once on open; Arcanum.Journal GMCP fills `journal`.
  useEffect(() => {
    if (!autoLoaded.current && connected) {
      autoLoaded.current = true;
      onCommand("arcanum");
    }
  }, [connected, onCommand]);

  const pledged = journal?.pledged ?? status?.pledged ?? false;

  if (!journal) {
    return (
      <div className="arcanum-panel">
        <p className="arcanum-empty">Opening the Arcanum&hellip;</p>
      </div>
    );
  }

  const counts: Record<ArcanumTab, number> = {
    mobs: journal.mobs.length,
    items: journal.items.length,
    rooms: journal.rooms.length,
  };

  const firstLine = (firstBy: string | null) => {
    if (!firstBy) return null;
    const mine = firstBy === playerName;
    return (
      <span className={`arcanum-first${mine ? " arcanum-first-mine" : ""}`}>
        ★ First illuminated by {firstBy}
      </span>
    );
  };

  return (
    <div className="arcanum-panel">
      <p className="arcanum-pledge-line">
        {pledged
          ? "You are an Akathavae — a keeper of the Arcanum. The world is your subject."
          : "These pages were written under a pledge you no longer keep. They earn nothing while you bear arms."}
      </p>

      {journal.zones.length > 0 && (
        <div className="arcanum-zones">
          {journal.zones.map((z) => {
            const total = z.roomsTotal + z.mobsTotal;
            const recorded = z.roomsRecorded + z.mobsRecorded;
            const pct = total > 0 ? Math.round((recorded / total) * 100) : 0;
            return (
              <div key={z.zone} className="arcanum-zone">
                <div className="arcanum-zone-head">
                  <span className="arcanum-zone-name">{z.zone}</span>
                  <span className="arcanum-zone-pct">{pct}%</span>
                </div>
                <div
                  className="arcanum-zone-bar"
                  role="progressbar"
                  aria-valuenow={pct}
                  aria-valuemin={0}
                  aria-valuemax={100}
                  aria-label={`${z.zone} completion`}
                >
                  <div className="arcanum-zone-fill" style={{ width: `${pct}%` }} />
                </div>
                <div className="arcanum-zone-detail">
                  {z.roomsRecorded}/{z.roomsTotal} places · {z.mobsRecorded}/{z.mobsTotal} creatures
                </div>
              </div>
            );
          })}
        </div>
      )}

      <div className="arcanum-tabs" role="tablist">
        {(["mobs", "items", "rooms"] as const).map((t) => (
          <button
            key={t}
            role="tab"
            aria-selected={tab === t}
            className={`arcanum-tab${tab === t ? " arcanum-tab-active" : ""}`}
            onClick={() => setTab(t)}
          >
            {t === "mobs" ? "Creatures" : t === "items" ? "Items" : "Places"} ({counts[t]})
          </button>
        ))}
      </div>

      {tab === "mobs" && (
        <div className="arcanum-grid">
          {journal.mobs.length === 0 && <p className="arcanum-empty">No creatures illuminated yet.</p>}
          {journal.mobs.map((m) => (
            <div key={m.key} className="arcanum-card">
              {m.image && <img className="arcanum-card-art" src={m.image} alt="" loading="lazy" />}
              <div className="arcanum-card-body">
                <span className="arcanum-card-name">{m.name}</span>
                <span className="arcanum-card-meta">
                  {SOURCE_LABEL[m.source] ?? m.source}
                  {m.timesRecorded > 1 ? ` ×${m.timesRecorded}` : ""}
                </span>
                {firstLine(m.firstBy)}
              </div>
            </div>
          ))}
        </div>
      )}

      {tab === "items" && (
        <div className="arcanum-grid">
          {journal.items.length === 0 && <p className="arcanum-empty">No items recorded yet.</p>}
          {journal.items.map((i) => (
            <div key={i.key} className="arcanum-card">
              {i.image && <img className="arcanum-card-art" src={i.image} alt="" loading="lazy" />}
              <div className="arcanum-card-body">
                <span className="arcanum-card-name">{i.name}</span>
                <span className="arcanum-card-meta">
                  {SOURCE_LABEL[i.source] ?? i.source}
                  {i.slot ? ` · ${i.slot}` : ""}
                </span>
                {firstLine(i.firstBy)}
                {pledged && i.wearable && (
                  <button
                    className="arcanum-conjure-btn"
                    disabled={!connected}
                    onClick={() => onCommand(`wardrobe ${i.name}`)}
                  >
                    Conjure &amp; Wear
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      {tab === "rooms" && (
        <div className="arcanum-rooms">
          {journal.rooms.length === 0 && <p className="arcanum-empty">No places recorded yet.</p>}
          {journal.rooms.map((r) => (
            <div key={r.key} className="arcanum-room-row">
              <span className="arcanum-room-title">{r.title}</span>
              <span className="arcanum-room-zone">{r.zone}</span>
              {firstLine(r.firstBy)}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
