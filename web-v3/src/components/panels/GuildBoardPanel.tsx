import { useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent } from "react";
import type { ChatChannel, ChatMessage, GuildHallInfo, GuildInfo, GuildMemberEntry, PendingGuildInvite } from "../../types";

interface GuildBoardPanelProps {
  connected: boolean;
  canChat: boolean;
  playerName: string;
  guildInfo: GuildInfo;
  pendingGuildInvite: PendingGuildInvite | null;
  guildMembers: GuildMemberEntry[];
  guildHall: GuildHallInfo | null;
  gchatMessages: ChatMessage[];
  onSendMessage: (channel: ChatChannel, message: string, target: string | null) => boolean;
  onCommand: (command: string) => void;
}

function rankLabel(rank: string): string {
  switch (rank) {
    case "LEADER": return "Leader";
    case "OFFICER": return "Officer";
    default: return "Member";
  }
}

/**
 * Standalone "Guild" board — the guild archive split out of the Social panel
 * onto the fae-botanical `guild_bg` parchment frame. Two states: not-in-a-guild
 * (incoming invite + create form) and in-a-guild (identity, MOTD, roster,
 * officer controls, and guild chat). Built in plain CSS over the painted frame.
 */
export function GuildBoardPanel({
  connected,
  canChat,
  playerName,
  guildInfo,
  pendingGuildInvite,
  guildMembers,
  guildHall,
  gchatMessages,
  onSendMessage,
  onCommand,
}: GuildBoardPanelProps) {
  const feedRef = useRef<HTMLDivElement | null>(null);
  const [draft, setDraft] = useState("");
  const [createName, setCreateName] = useState("");
  const [createTag, setCreateTag] = useState("");
  const [inviteTarget, setInviteTarget] = useState("");
  const [motdDraft, setMotdDraft] = useState("");
  const [editingMotd, setEditingMotd] = useState(false);
  const [confirmAction, setConfirmAction] = useState<"leave" | "disband" | null>(null);
  const [selected, setSelected] = useState<string | null>(null);

  const inGuild = guildInfo.name !== null;
  const isLeader = guildInfo.rank === "LEADER";
  const canManage = isLeader || guildInfo.rank === "OFFICER";

  const sortedMembers = useMemo(() => {
    const order: Record<string, number> = { LEADER: 0, OFFICER: 1, MEMBER: 2 };
    return [...guildMembers].sort((a, b) => {
      const onlineDiff = (a.online ? 0 : 1) - (b.online ? 0 : 1);
      if (onlineDiff !== 0) return onlineDiff;
      const rankDiff = (order[a.rank] ?? 2) - (order[b.rank] ?? 2);
      if (rankDiff !== 0) return rankDiff;
      return a.name.localeCompare(b.name);
    });
  }, [guildMembers]);

  const selectedMember = sortedMembers.find((m) => m.name === selected) ?? null;
  const selectedIsMe = selectedMember?.name.localeCompare(playerName, undefined, { sensitivity: "accent" }) === 0;

  useEffect(() => {
    const feed = feedRef.current;
    if (feed) feed.scrollTop = feed.scrollHeight;
  }, [gchatMessages.length, inGuild]);

  if (!canChat) {
    return (
      <div className="guildboard guildboard-centered">
        <p className="gb-empty">{connected ? "Log in to view your guild." : "Reconnect to load guild data."}</p>
      </div>
    );
  }

  // ── State A — not in a guild ──────────────────────────────
  if (!inGuild) {
    return (
      <div className="guildboard guildboard-state-a">
        <section className="gb-card gb-invites">
          <h3 className="gb-card-title">Invites</h3>
          {pendingGuildInvite ? (
            <div className="gb-invite-row">
              <div className="gb-invite-info">
                <span className="gb-invite-guild">{pendingGuildInvite.guildName} <span className="gb-tag">[{pendingGuildInvite.guildTag}]</span></span>
                <span className="gb-invite-from">from {pendingGuildInvite.inviterName}</span>
              </div>
              <div className="gb-invite-actions">
                <button type="button" className="gb-btn gb-btn-accept" onClick={() => onCommand("guild accept")}>Accept</button>
                <button type="button" className="gb-btn gb-btn-decline" onClick={() => onCommand("guild decline")}>Decline</button>
              </div>
            </div>
          ) : (
            <p className="gb-empty">No pending invites.</p>
          )}
        </section>

        <section className="gb-card gb-create">
          <h3 className="gb-card-title">Create Guild</h3>
          <form
            className="gb-create-form"
            onSubmit={(e) => {
              e.preventDefault();
              const n = createName.trim();
              const t = createTag.trim();
              if (n && t) { onCommand(`guild create ${n} ${t}`); setCreateName(""); setCreateTag(""); }
            }}
          >
            <label className="gb-field">
              <span className="gb-label">Name <em>(required)</em></span>
              <input type="text" className="gb-input" placeholder="Enter guild name…" value={createName} onChange={(e) => setCreateName(e.target.value)} aria-label="Guild name" />
            </label>
            <label className="gb-field">
              <span className="gb-label">Tag <em>(required)</em></span>
              <input type="text" className="gb-input gb-input-tag" placeholder="2–5 chars…" value={createTag} maxLength={5} onChange={(e) => setCreateTag(e.target.value)} aria-label="Guild tag" />
            </label>
            <button type="submit" className="gb-btn gb-btn-primary gb-create-btn" disabled={!createName.trim() || !createTag.trim()}>Create Guild</button>
          </form>
        </section>
      </div>
    );
  }

  // ── State B — in a guild ──────────────────────────────────
  return (
    <div className="guildboard guildboard-state-b">
      <header className="gb-identity">
        <div className="gb-crest" aria-hidden="true">
          <span className="gb-crest-tag">{guildInfo.tag ?? "✦"}</span>
        </div>
        <div className="gb-identity-text">
          <h2 className="gb-guild-name">{guildInfo.name}{guildInfo.tag && <span className="gb-tag"> [{guildInfo.tag}]</span>}</h2>
          <p className="gb-identity-meta">
            {guildInfo.rank && <>Rank: {rankLabel(guildInfo.rank)}</>}
            {guildInfo.rank && " · "}Members: {guildInfo.memberCount} / {guildInfo.maxSize}
          </p>
        </div>
      </header>

      <section className="gb-card gb-motd">
        <div className="gb-card-titlerow">
          <h3 className="gb-card-title">MOTD</h3>
          {canManage && !editingMotd && (
            <button type="button" className="gb-icon-btn" title="Set MOTD" aria-label="Set MOTD" onClick={() => { setMotdDraft(guildInfo.motd ?? ""); setEditingMotd(true); }}>&#9998;</button>
          )}
        </div>
        {editingMotd ? (
          <form className="gb-motd-edit" onSubmit={(e) => { e.preventDefault(); onCommand(`guild motd ${motdDraft}`); setEditingMotd(false); }}>
            <input type="text" className="gb-input" value={motdDraft} onChange={(e) => setMotdDraft(e.target.value)} aria-label="Guild MOTD" autoFocus />
            <button type="submit" className="gb-btn gb-btn-accept">Save</button>
            <button type="button" className="gb-btn gb-btn-ghost" onClick={() => setEditingMotd(false)}>Cancel</button>
          </form>
        ) : (
          <p className="gb-motd-text">{guildInfo.motd || "No message set."}</p>
        )}
      </section>

      <div className="gb-columns">
        <section className="gb-card gb-roster">
          <h3 className="gb-card-title">Roster ({guildInfo.memberCount}/{guildInfo.maxSize})</h3>
          <div className="gb-roster-scroll">
            {sortedMembers.length === 0 ? (
              <p className="gb-empty">No roster data yet.</p>
            ) : (
              <ul className="gb-member-list">
                {sortedMembers.map((m, i) => (
                  <li key={m.name}>
                    <button
                      type="button"
                      className={`gb-member${selected === m.name ? " is-selected" : ""}${m.online ? "" : " is-offline"}`}
                      onClick={() => setSelected(selected === m.name ? null : m.name)}
                      aria-pressed={selected === m.name}
                    >
                      <span className="gb-member-num">{i + 1}.</span>
                      <span className={`gb-member-dot ${m.online ? "is-online" : "is-offline"}`} aria-hidden="true" />
                      <span className="gb-member-name">{m.name}</span>
                      {m.level !== null && <span className="gb-member-level">Lv {m.level}</span>}
                      <span className="gb-member-rank">{rankLabel(m.rank)}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </section>

        {canManage ? (
          <section className="gb-card gb-controls">
            <h3 className="gb-card-title">Officer Controls</h3>
            <p className="gb-controls-hint">{selectedMember ? `Managing ${selectedMember.name}` : "Select a member to manage."}</p>
            <div className="gb-controls-actions">
              <button
                type="button"
                className="gb-btn gb-btn-kick"
                disabled={!selectedMember || selectedIsMe || selectedMember?.rank === "LEADER"}
                onClick={() => { if (selectedMember) { onCommand(`guild kick ${selectedMember.name}`); setSelected(null); } }}
              >Kick</button>
              <button
                type="button"
                className="gb-btn gb-btn-promote"
                disabled={!isLeader || !selectedMember || selectedMember?.rank !== "MEMBER"}
                onClick={() => { if (selectedMember) onCommand(`guild promote ${selectedMember.name}`); }}
              >Promote</button>
              <button
                type="button"
                className="gb-btn gb-btn-demote"
                disabled={!isLeader || !selectedMember || selectedMember?.rank !== "OFFICER"}
                onClick={() => { if (selectedMember) onCommand(`guild demote ${selectedMember.name}`); }}
              >Demote</button>
              <button
                type="button"
                className="gb-btn gb-btn-setmotd"
                onClick={() => { setMotdDraft(guildInfo.motd ?? ""); setEditingMotd(true); }}
              >Set MOTD</button>
            </div>
            <form className="gb-invite-bar" onSubmit={(e) => { e.preventDefault(); const t = inviteTarget.trim(); if (t) { onCommand(`guild invite ${t}`); setInviteTarget(""); } }}>
              <input type="text" className="gb-input" placeholder="Invite player…" value={inviteTarget} onChange={(e) => setInviteTarget(e.target.value)} aria-label="Player to invite" />
              <button type="submit" className="gb-btn gb-btn-ghost" disabled={!inviteTarget.trim()}>Invite</button>
            </form>
          </section>
        ) : (
          <section className="gb-card gb-controls">
            <h3 className="gb-card-title">Guild</h3>
            <p className="gb-controls-hint">Members coordinate here. Officers can manage the roster.</p>
          </section>
        )}
      </div>

      {(guildHall || canManage) && (
        <section className="gb-card gb-hall">
          <div className="gb-card-titlerow">
            <h3 className="gb-card-title">Guild Hall</h3>
            {guildHall && <span className="gb-hall-meta">{guildHall.rooms.length}/{guildHall.maxRooms} rooms · {guildHall.membersInHall} inside</span>}
          </div>
          <div className="gb-hall-actions">
            {guildHall ? (
              <>
                <button type="button" className="gb-btn gb-btn-ghost" onClick={() => onCommand("guild hallenter")}>Enter Hall</button>
                {canManage && <button type="button" className="gb-btn gb-btn-ghost" onClick={() => onCommand("guild hallexpand")}>Expand</button>}
              </>
            ) : (
              <button type="button" className="gb-btn gb-btn-ghost" onClick={() => onCommand("guild hallbuy")}>Purchase Hall</button>
            )}
          </div>
        </section>
      )}

      <section className="gb-card gb-chat">
        <h3 className="gb-card-title">Guild Chat</h3>
        <div ref={feedRef} className="gb-chat-feed" role="log" aria-live="polite" aria-label="Guild chat messages">
          {gchatMessages.length === 0 ? (
            <p className="gb-empty">No guild messages yet.</p>
          ) : (
            <ul className="gb-chat-list">
              {gchatMessages.map((entry) => {
                const isSelf = entry.sender.localeCompare(playerName, undefined, { sensitivity: "accent" }) === 0;
                return (
                  <li key={entry.id} className="gb-chat-msg">
                    <span className="gb-chat-sender">[{isSelf ? "You" : entry.sender}]</span>
                    <span className="gb-chat-body">{entry.message}</span>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
        <form
          className="gb-chat-form"
          onSubmit={(e: FormEvent<HTMLFormElement>) => { e.preventDefault(); if (onSendMessage("gchat", draft, null)) setDraft(""); }}
        >
          <span className="gb-chat-say">Say:</span>
          <input className="gb-input" type="text" value={draft} onChange={(e) => setDraft(e.target.value)} placeholder="Type your message…" aria-label="Guild chat message" autoComplete="off" spellCheck={false} />
          <button type="submit" className="gb-btn gb-btn-primary">Send</button>
        </form>
      </section>

      <div className="gb-footer">
        {confirmAction === "leave" ? (
          <span className="gb-confirm"><span>Leave guild?</span><button type="button" className="gb-btn gb-btn-kick" onClick={() => { onCommand("guild leave"); setConfirmAction(null); }}>Yes</button><button type="button" className="gb-btn gb-btn-ghost" onClick={() => setConfirmAction(null)}>No</button></span>
        ) : confirmAction === "disband" ? (
          <span className="gb-confirm"><span>Disband guild?</span><button type="button" className="gb-btn gb-btn-kick" onClick={() => { onCommand("guild disband"); setConfirmAction(null); }}>Yes</button><button type="button" className="gb-btn gb-btn-ghost" onClick={() => setConfirmAction(null)}>No</button></span>
        ) : (
          <>
            <button type="button" className="gb-btn gb-btn-ghost gb-leave" onClick={() => setConfirmAction("leave")}>Leave Guild</button>
            {isLeader && <button type="button" className="gb-btn gb-btn-kick" onClick={() => setConfirmAction("disband")}>Disband</button>}
          </>
        )}
      </div>
    </div>
  );
}
