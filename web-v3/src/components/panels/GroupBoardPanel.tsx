import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import type { ChatChannel, ChatMessage, GroupInfo, PendingGroupInvite } from "../../types";

interface GroupBoardPanelProps {
  connected: boolean;
  canChat: boolean;
  playerName: string;
  groupInfo: GroupInfo;
  pendingGroupInvite: PendingGroupInvite | null;
  gtellMessages: ChatMessage[];
  onSendMessage: (channel: ChatChannel, message: string, target: string | null) => boolean;
  onCommand: (command: string) => void;
}

/**
 * Standalone "Group" board — the party panel split out of the Social panel
 * onto the stained-glass `group_bg` frame (the last piece; the legacy Social
 * panel retires with it). Not-in-a-group shows pending invites + an invite
 * field; in-a-group shows members with HP/Mana bars, group chat, and Leave.
 */
export function GroupBoardPanel({
  connected,
  canChat,
  playerName,
  groupInfo,
  pendingGroupInvite,
  gtellMessages,
  onSendMessage,
  onCommand,
}: GroupBoardPanelProps) {
  const feedRef = useRef<HTMLDivElement | null>(null);
  const [draft, setDraft] = useState("");
  const [inviteTarget, setInviteTarget] = useState("");

  const inGroup = groupInfo.members.length > 0;
  const iAmLeader = playerName.localeCompare(groupInfo.leader ?? "", undefined, { sensitivity: "accent" }) === 0;

  useEffect(() => {
    const feed = feedRef.current;
    if (feed) feed.scrollTop = feed.scrollHeight;
  }, [gtellMessages.length, inGroup]);

  if (!canChat) {
    return (
      <div className="groupboard groupboard-centered">
        <p className="gp-empty">{connected ? "Log in to view your party." : "Reconnect to load group data."}</p>
      </div>
    );
  }

  const inviteForm = (
    <form
      className="gp-invite-bar"
      onSubmit={(e) => { e.preventDefault(); const t = inviteTarget.trim(); if (t) { onCommand(`group invite ${t}`); setInviteTarget(""); } }}
    >
      <input type="text" className="gp-input" placeholder="Enter player name…" value={inviteTarget} onChange={(e) => setInviteTarget(e.target.value)} aria-label="Player to invite" />
      <button type="submit" className="gp-btn gp-btn-invite" disabled={!inviteTarget.trim()}>＋ Invite</button>
    </form>
  );

  // ── Not in a group ────────────────────────────────────────
  if (!inGroup) {
    return (
      <div className="groupboard groupboard-state-a">
        <section className="gp-card gp-intro">
          <span className="gp-intro-emblem" aria-hidden="true">✦</span>
          <div className="gp-intro-text">
            <p className="gp-intro-title">You are not in a group.</p>
            <p className="gp-intro-sub">Invite someone to start adventuring together!</p>
            {inviteForm}
          </div>
        </section>

        <section className="gp-card">
          <h3 className="gp-card-title">Pending Invitations ({pendingGroupInvite ? 1 : 0})</h3>
          {pendingGroupInvite ? (
            <div className="gp-invite-row">
              <span className="gp-invite-emblem" aria-hidden="true">✦</span>
              <div className="gp-invite-info">
                <span className="gp-invite-name">{pendingGroupInvite.inviterName}</span>
                <span className="gp-invite-meta">invites you to their group</span>
              </div>
              <div className="gp-invite-actions">
                <button type="button" className="gp-btn gp-btn-accept" onClick={() => onCommand("group accept")}>✓ Accept</button>
                <button type="button" className="gp-btn gp-btn-decline" onClick={() => onCommand("group decline")}>✕ Decline</button>
              </div>
            </div>
          ) : (
            <p className="gp-empty">No pending invitations.</p>
          )}
        </section>
      </div>
    );
  }

  // ── In a group ────────────────────────────────────────────
  return (
    <div className="groupboard groupboard-state-b">
      <section className="gp-card gp-members">
        <h3 className="gp-card-title">Group Members ({groupInfo.members.length})</h3>
        <ul className="gp-member-list">
          {groupInfo.members.map((m) => {
            const isLeader = m.name === groupInfo.leader;
            const isMe = m.name.localeCompare(playerName, undefined, { sensitivity: "accent" }) === 0;
            const hpPct = Math.min(100, (m.hp / Math.max(1, m.maxHp)) * 100);
            const manaPct = Math.min(100, (m.mana / Math.max(1, m.maxMana)) * 100);
            return (
              <li key={m.name} className="gp-member">
                <span className="gp-member-portrait" aria-hidden="true">✦</span>
                <div className="gp-member-id">
                  <span className="gp-member-name">
                    {isLeader && <span className="gp-crown" title="Leader" aria-label="Leader">♛</span>}
                    {m.name}
                  </span>
                  <span className="gp-member-class">{m.playerClass} · Lv {m.level}</span>
                </div>
                <div className="gp-member-bars">
                  <div className="gp-bar">
                    <span className="gp-bar-key">HP</span>
                    <div className="gp-bar-track" role="progressbar" aria-label={`${m.name} health`} aria-valuenow={m.hp} aria-valuemin={0} aria-valuemax={m.maxHp}><span className="gp-bar-fill gp-bar-hp" style={{ width: `${hpPct}%` }} /><span className="gp-bar-text">{m.hp} / {m.maxHp}</span></div>
                  </div>
                  <div className="gp-bar">
                    <span className="gp-bar-key">MP</span>
                    <div className="gp-bar-track" role="progressbar" aria-label={`${m.name} mana`} aria-valuenow={m.mana} aria-valuemin={0} aria-valuemax={m.maxMana}><span className="gp-bar-fill gp-bar-mana" style={{ width: `${manaPct}%` }} /><span className="gp-bar-text">{m.mana} / {m.maxMana}</span></div>
                  </div>
                </div>
                {iAmLeader && !isMe && (
                  <button type="button" className="gp-kick" title={`Remove ${m.name}`} aria-label={`Remove ${m.name}`} onClick={() => onCommand(`group kick ${m.name}`)}>✕</button>
                )}
              </li>
            );
          })}
        </ul>
        {inviteForm}
      </section>

      <section className="gp-card gp-chat">
        <h3 className="gp-card-title">Group Chat</h3>
        <div ref={feedRef} className="gp-chat-feed" role="log" aria-live="polite" aria-label="Group chat messages">
          {gtellMessages.length === 0 ? (
            <p className="gp-empty">No group messages yet.</p>
          ) : (
            <ul className="gp-chat-list">
              {gtellMessages.map((entry) => {
                const isSelf = entry.sender.localeCompare(playerName, undefined, { sensitivity: "accent" }) === 0;
                return (
                  <li key={entry.id} className="gp-chat-msg">
                    <span className="gp-chat-sender">{isSelf ? "You" : entry.sender}:</span>
                    <span className="gp-chat-body">{entry.message}</span>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
        <form
          className="gp-chat-form"
          onSubmit={(e: FormEvent<HTMLFormElement>) => { e.preventDefault(); if (onSendMessage("gtell", draft, null)) setDraft(""); }}
        >
          <input className="gp-input" type="text" value={draft} onChange={(e) => setDraft(e.target.value)} placeholder="Message your group…" aria-label="Group chat message" autoComplete="off" spellCheck={false} />
          <button type="submit" className="gp-btn gp-btn-invite">Send</button>
        </form>
      </section>

      <div className="gp-footer">
        <button type="button" className="gp-btn gp-btn-leave" onClick={() => onCommand("group leave")}>Leave Group</button>
      </div>
    </div>
  );
}
