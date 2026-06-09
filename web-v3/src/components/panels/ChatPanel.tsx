import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import type { ChatChannel, ChatMessage, GroupInfo, PendingGroupInvite } from "../../types";

interface ChatPanelProps {
  connected: boolean;
  canChat: boolean;
  playerName: string;
  chatByChannel: Record<ChatChannel, ChatMessage[]>;
  groupInfo: GroupInfo;
  pendingGroupInvite: PendingGroupInvite | null;
  onSendMessage: (channel: ChatChannel, message: string, target: string | null) => boolean;
  onCommand: (command: string) => void;
}

export function ChatPanel({
  connected,
  canChat,
  playerName,
  chatByChannel,
  groupInfo,
  pendingGroupInvite,
  onSendMessage,
  onCommand,
}: ChatPanelProps) {
  const groupFeedRef = useRef<HTMLDivElement | null>(null);
  const [groupDraft, setGroupDraft] = useState("");
  const [groupInviteTarget, setGroupInviteTarget] = useState("");

  const gtellMessages = chatByChannel.gtell;

  useEffect(() => {
    const feed = groupFeedRef.current;
    if (!feed) return;
    feed.scrollTop = feed.scrollHeight;
  }, [gtellMessages.length]);

  const inGroup = groupInfo.members.length > 0;

  return (
    <section className="panel panel-chat" aria-label="Group">
      <div className="chat-shell">

        {(
          !canChat ? (
            <>
              <div aria-hidden="true" />
              <div className="chat-feed" role="region" aria-label="Group members">
                <section className="chat-feed-panel" aria-label="Group subwindow">
                  <p className="empty-note">
                    {connected ? "Log in to unlock social features." : "Reconnect to load social data."}
                  </p>
                </section>
              </div>
              <div aria-hidden="true" />
            </>
          ) : !inGroup ? (
            <>
              <div aria-hidden="true" />
              <div className="chat-feed" role="region" aria-label="Group members">
                <section className="chat-feed-panel" aria-label="Group subwindow">
                  <div className="social-empty-action">
                    <p className="empty-note">You are not in a group.</p>
                    {pendingGroupInvite ? (
                      <div className="invite-card">
                        <p className="invite-card-text"><strong>{pendingGroupInvite.inviterName}</strong> invites you to join their group.</p>
                        <div className="invite-card-actions">
                          <button type="button" className="social-action-btn social-accept-btn" onClick={() => { onCommand("group accept"); }}>Accept</button>
                          <button type="button" className="social-action-btn social-decline-btn" onClick={() => onCommand("group decline")}>Decline</button>
                        </div>
                      </div>
                    ) : (
                      <button type="button" className="social-action-btn social-accept-btn" onClick={() => onCommand("group accept")}>Accept Invite</button>
                    )}
                    <form className="social-action-bar" onSubmit={(e) => { e.preventDefault(); const t = groupInviteTarget.trim(); if (t) { onCommand(`group invite ${t}`); setGroupInviteTarget(""); } }}>
                      <input type="text" className="social-action-input" placeholder="Invite player\u2026" value={groupInviteTarget} onChange={(e) => setGroupInviteTarget(e.target.value)} aria-label="Player to invite" />
                      <button type="submit" className="social-action-btn" disabled={!groupInviteTarget.trim()}>Invite</button>
                    </form>
                  </div>
                </section>
              </div>
              <div aria-hidden="true" />
            </>
          ) : (
            <div className="group-tab-layout">
              <div className="group-tab-members">
                <form className="social-action-bar group-invite-bar" onSubmit={(e) => { e.preventDefault(); const t = groupInviteTarget.trim(); if (t) { onCommand(`group invite ${t}`); setGroupInviteTarget(""); } }}>
                  <input type="text" className="social-action-input" placeholder="Invite player\u2026" value={groupInviteTarget} onChange={(e) => setGroupInviteTarget(e.target.value)} aria-label="Player to invite" disabled={!canChat} />
                  <button type="submit" className="social-action-btn" disabled={!canChat || !groupInviteTarget.trim()}>Invite</button>
                </form>
                <ul className="group-member-list">
                  {groupInfo.members.map((member) => {
                    const isLeader = member.name === groupInfo.leader;
                    const isMe = member.name.localeCompare(playerName, undefined, { sensitivity: "accent" }) === 0;
                    const iAmLeader = playerName.localeCompare(groupInfo.leader ?? "", undefined, { sensitivity: "accent" }) === 0;
                    const hpPct = Math.min(100, (member.hp / Math.max(1, member.maxHp)) * 100);
                    return (
                      <li key={member.name} className="group-member-item">
                        <div className="group-member-header">
                          <span className="group-member-name">
                            {isLeader && <span className="group-leader-badge" title="Leader">&#9733;</span>}
                            {member.name}
                          </span>
                          <span className="group-member-class">
                            {member.playerClass} {member.level}
                            {iAmLeader && !isMe && (
                              <button type="button" className="social-inline-btn social-inline-btn-danger" onClick={() => onCommand(`group kick ${member.name}`)} title={`Kick ${member.name}`}>&times;</button>
                            )}
                          </span>
                        </div>
                        <div className="meter-track group-member-hp-track">
                          <span
                            className="meter-fill meter-fill-hp"
                            style={{ width: `${hpPct}%` }}
                          />
                        </div>
                        <div className="group-member-hp-text">{member.hp} / {member.maxHp}</div>
                      </li>
                    );
                  })}
                </ul>
                <div className="group-footer-actions">
                  <button type="button" className="social-action-btn social-danger-btn" onClick={() => onCommand("group leave")}>Leave Group</button>
                </div>
              </div>

              <div ref={groupFeedRef} className="embedded-chat-feed" role="log" aria-live="polite" aria-label="Group chat messages">
                {gtellMessages.length === 0 ? (
                  <p className="empty-note">No group messages yet.</p>
                ) : (
                  <ul className="chat-message-list">
                    {gtellMessages.map((entry) => {
                      const isSelf = entry.sender.localeCompare(playerName, undefined, { sensitivity: "accent" }) === 0;
                      const time = new Date(entry.receivedAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
                      return (
                        <li key={entry.id} className={`chat-message-item ${isSelf ? "chat-message-item-self" : ""}`}>
                          <div className="chat-message-meta">
                            <span className="chat-message-sender">{isSelf ? "You" : entry.sender}</span>
                            <span className="chat-message-time">{time}</span>
                          </div>
                          <p className="chat-message-body">{entry.message}</p>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </div>

              <form
                className="chat-form"
                onSubmit={(event: FormEvent<HTMLFormElement>) => {
                  event.preventDefault();
                  const sent = onSendMessage("gtell", groupDraft, null);
                  if (sent) setGroupDraft("");
                }}
              >
                <input
                  className="chat-input"
                  type="text"
                  value={groupDraft}
                  onChange={(event) => setGroupDraft(event.target.value)}
                  placeholder="Message your group"
                  aria-label="Group chat message"
                  autoComplete="off"
                  spellCheck={false}
                  disabled={!canChat}
                />
                <button type="submit" className="soft-button" disabled={!canChat}>Send</button>
              </form>
            </div>
          )
        )}

      </div>
    </section>
  );
}
