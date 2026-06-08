import { useEffect, useMemo, useRef, useState } from "react";
import type { FriendEntry, FriendNotification, WhoPlayer } from "../../types";

interface FriendsBoardPanelProps {
  connected: boolean;
  canChat: boolean;
  friends: FriendEntry[];
  friendNotifications: FriendNotification[];
  whoPlayers: WhoPlayer[];
  serverAssets: Record<string, string>;
  onRequestWho: () => void;
  onExamine: (player: WhoPlayer) => void;
  onTellPlayer: (name: string) => void;
  onAddFriend: (name: string) => void;
  onRemoveFriend: (name: string) => void;
}

/**
 * Standalone "Friends" board — split out of the Social panel onto the celestial
 * `friends_bg` conservatory frame. Generated DOM (no paint-matching): an
 * add-a-friend field, then a roster of friends with portrait, name + title,
 * location, level, and the shared Examine / Tell action buttons plus Remove.
 *
 * FriendEntry is sparse (name/online/level/zone), so each row is enriched from
 * the live Who roster (sprite, title, examine data) — the board refreshes Who
 * on open so online friends carry full detail.
 */
export function FriendsBoardPanel({
  connected,
  canChat,
  friends,
  friendNotifications,
  whoPlayers,
  serverAssets,
  onRequestWho,
  onExamine,
  onTellPlayer,
  onAddFriend,
  onRemoveFriend,
}: FriendsBoardPanelProps) {
  const [addTarget, setAddTarget] = useState("");
  const [confirmRemove, setConfirmRemove] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());

  // Refresh Who on open so online friends pick up sprites/titles/examine data.
  const requested = useRef(false);
  useEffect(() => {
    if (canChat && !requested.current) { requested.current = true; onRequestWho(); }
  }, [canChat, onRequestWho]);

  const whoByName = useMemo(() => {
    const m = new Map<string, WhoPlayer>();
    for (const p of whoPlayers) m.set(p.name.toLowerCase(), p);
    return m;
  }, [whoPlayers]);

  const sortedFriends = useMemo(
    () => [...friends].sort((a, b) => {
      const onlineDiff = (a.online ? 0 : 1) - (b.online ? 0 : 1);
      if (onlineDiff !== 0) return onlineDiff;
      return a.name.localeCompare(b.name);
    }),
    [friends],
  );

  const onlineCount = friends.filter((f) => f.online).length;

  // Expire the transient "came online / went offline" banners after 30s.
  const hasNotes = friendNotifications.length > 0;
  useEffect(() => {
    if (!hasNotes) return;
    const t = window.setInterval(() => setNow(Date.now()), 5_000);
    return () => window.clearInterval(t);
  }, [hasNotes]);
  const recentNotes = useMemo(
    () => friendNotifications.filter((n) => now - n.receivedAt < 30_000),
    [friendNotifications, now],
  );

  const examineArt = serverAssets["who_examine_btn"];
  const tellArt = serverAssets["who_tell_btn"];

  const examineFriend = (f: FriendEntry) => {
    const wp = whoByName.get(f.name.toLowerCase());
    onExamine(wp ?? {
      name: f.name,
      level: f.level ?? 0,
      race: "",
      playerClass: "",
      title: null,
      guild: null,
      groupSize: 0,
      idle: 0,
      sprite: null,
      description: null,
    });
  };

  return (
    <div className="friendsboard">
      <section className="fb-add-card">
        <h3 className="fb-add-title">Add a Friend</h3>
        <form
          className="fb-add-form"
          onSubmit={(e) => { e.preventDefault(); const t = addTarget.trim(); if (t) { onAddFriend(t); setAddTarget(""); } }}
        >
          <input
            type="text"
            className="fb-add-input"
            placeholder="Enter character name…"
            value={addTarget}
            onChange={(e) => setAddTarget(e.target.value)}
            aria-label="Friend name to add"
            disabled={!canChat}
          />
          <button type="submit" className="fb-add-btn" disabled={!canChat || !addTarget.trim()}>Add</button>
        </form>
      </section>

      <div className="fb-list-head">
        <h3 className="fb-list-title">Online Friends</h3>
        <span className="fb-online-count">{onlineCount} Online <span className="fb-online-dot" aria-hidden="true" /></span>
      </div>

      <div className="fb-grid" role="table" aria-label="Friends">
        <div className="fb-head" role="row">
          <span className="fb-col-portrait" role="columnheader" aria-label="Portrait" />
          <span className="fb-col-name" role="columnheader">Friend Name</span>
          <span className="fb-col-loc" role="columnheader">Location</span>
          <span className="fb-col-level" role="columnheader">Level</span>
          <span className="fb-col-actions" role="columnheader">Actions</span>
        </div>

        <div className="fb-body">
          {!canChat ? (
            <p className="fb-empty">{connected ? "Log in to see your friends." : "Reconnect to load social data."}</p>
          ) : sortedFriends.length === 0 && recentNotes.length === 0 ? (
            <p className="fb-empty">No friends yet. Add someone above to get started.</p>
          ) : (
            <>
              {recentNotes.length > 0 && (
                <ul className="fb-notes">
                  {recentNotes.map((n) => (
                    <li key={n.id} className={`fb-note fb-note-${n.event}`}>
                      <span className={`fb-dot ${n.event === "online" ? "is-online" : "is-offline"}`} aria-hidden="true" />
                      {n.name} {n.event === "online" ? "came online" : "went offline"}
                    </li>
                  ))}
                </ul>
              )}
              {sortedFriends.map((f) => {
                const wp = whoByName.get(f.name.toLowerCase());
                return (
                  <div key={f.name} className={`fb-row${f.online ? "" : " is-offline"}`} role="row">
                    <span className="fb-col-portrait">
                      <span className="fb-portrait">
                        <span className={`fb-dot fb-portrait-dot ${f.online ? "is-online" : "is-offline"}`} aria-hidden="true" />
                        {wp?.sprite
                          ? <img className="fb-sprite" src={wp.sprite} alt="" draggable={false} />
                          : <span className="fb-sprite-empty" aria-hidden="true" />}
                      </span>
                    </span>
                    <span className="fb-col-name">
                      <span className="fb-name">{f.name}</span>
                      {wp?.title && <span className="fb-title">{wp.title}</span>}
                    </span>
                    <span className="fb-col-loc">{f.online ? (f.zone ?? "—") : "Offline"}</span>
                    <span className="fb-col-level">{f.level ?? wp?.level ?? "—"}</span>
                    <span className="fb-col-actions">
                      <button type="button" className="whoboard-action" title={`Examine ${f.name}`} aria-label={`Examine ${f.name}`} onClick={() => examineFriend(f)}>
                        {examineArt ? <img src={examineArt} alt="" aria-hidden="true" /> : <span className="whoboard-action-glyph" aria-hidden="true">❦</span>}
                      </button>
                      {f.online && (
                        <button type="button" className="whoboard-action" title={`Send a tell to ${f.name}`} aria-label={`Send a tell to ${f.name}`} onClick={() => onTellPlayer(f.name)}>
                          {tellArt ? <img src={tellArt} alt="" aria-hidden="true" /> : <span className="whoboard-action-glyph" aria-hidden="true">✉</span>}
                        </button>
                      )}
                      {confirmRemove === f.name ? (
                        <span className="fb-confirm">
                          <button type="button" className="fb-confirm-yes" aria-label="Confirm removal" onClick={() => { onRemoveFriend(f.name); setConfirmRemove(null); }}>Remove?</button>
                          <button type="button" className="fb-confirm-no" aria-label="Cancel" onClick={() => setConfirmRemove(null)}>×</button>
                        </span>
                      ) : (
                        <button type="button" className="whoboard-action fb-remove" title={`Remove ${f.name}`} aria-label={`Remove ${f.name}`} onClick={() => setConfirmRemove(f.name)}>
                          <span className="whoboard-action-glyph" aria-hidden="true">×</span>
                        </button>
                      )}
                    </span>
                  </div>
                );
              })}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
