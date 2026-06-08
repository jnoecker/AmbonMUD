import { useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent } from "react";
import { TellIcon } from "../Icons";
import type { ChatChannel, ChatMessage, FriendEntry, FriendNotification, GroupInfo, GuildHallInfo, GuildInfo, GuildMemberEntry, PendingGroupInvite, PendingGuildInvite, SocialTab } from "../../types";

interface ChatPanelProps {
  connected: boolean;
  canChat: boolean;
  playerName: string;
  chatByChannel: Record<ChatChannel, ChatMessage[]>;
  groupInfo: GroupInfo;
  pendingGroupInvite: PendingGroupInvite | null;
  guildInfo: GuildInfo;
  pendingGuildInvite: PendingGuildInvite | null;
  guildMembers: GuildMemberEntry[];
  guildHall: GuildHallInfo | null;
  friends: FriendEntry[];
  friendNotifications: FriendNotification[];
  onSendMessage: (channel: ChatChannel, message: string, target: string | null) => boolean;
  onTellPlayer: (target: string) => void;
  onCommand: (command: string) => void;
}

const SOCIAL_TABS: Array<{ id: SocialTab; label: string }> = [
  { id: "friends", label: "Friends" },
  { id: "guild", label: "Guild" },
  { id: "group", label: "Group" },
];

export function ChatPanel({
  connected,
  canChat,
  playerName,
  chatByChannel,
  groupInfo,
  pendingGroupInvite,
  guildInfo,
  pendingGuildInvite,
  guildMembers,
  guildHall,
  friends,
  friendNotifications,
  onSendMessage,
  onTellPlayer,
  onCommand,
}: ChatPanelProps) {
  const feedRef = useRef<HTMLDivElement | null>(null);
  const guildFeedRef = useRef<HTMLDivElement | null>(null);
  const groupFeedRef = useRef<HTMLDivElement | null>(null);
  const [guildDraft, setGuildDraft] = useState("");
  const [groupDraft, setGroupDraft] = useState("");
  const [activeSocialTab, setActiveSocialTab] = useState<SocialTab>("friends");
  // Guild action state
  const [guildCreateName, setGuildCreateName] = useState("");
  const [guildCreateTag, setGuildCreateTag] = useState("");
  const [guildInviteTarget, setGuildInviteTarget] = useState("");
  const [guildMotdDraft, setGuildMotdDraft] = useState("");
  const [guildEditingMotd, setGuildEditingMotd] = useState(false);
  const [guildConfirmAction, setGuildConfirmAction] = useState<"leave" | "disband" | null>(null);
  // Group action state
  const [groupInviteTarget, setGroupInviteTarget] = useState("");
  // Friends action state
  const [friendAddTarget, setFriendAddTarget] = useState("");
  const [friendConfirmRemove, setFriendConfirmRemove] = useState<string | null>(null);

  const gchatMessages = chatByChannel.gchat;
  const gtellMessages = chatByChannel.gtell;

  useEffect(() => {
    const feed = feedRef.current;
    if (!feed) return;
    feed.scrollTop = feed.scrollHeight;
  }, [activeSocialTab]);

  useEffect(() => {
    const feed = guildFeedRef.current;
    if (!feed) return;
    feed.scrollTop = feed.scrollHeight;
  }, [activeSocialTab, gchatMessages.length]);

  useEffect(() => {
    const feed = groupFeedRef.current;
    if (!feed) return;
    feed.scrollTop = feed.scrollHeight;
  }, [activeSocialTab, gtellMessages.length]);

  const handleSocialTabChange = (tab: SocialTab) => {
    setActiveSocialTab(tab);
  };

  const handleTellFromWho = (target: string) => {
    onTellPlayer(target);
  };

  const inGroup = groupInfo.members.length > 0;
  const inGuild = guildInfo.name !== null;

  const rankLabel = (rank: string) => {
    switch (rank) {
      case "LEADER": return "Leader";
      case "OFFICER": return "Officer";
      default: return "Member";
    }
  };

  const sortedGuildMembers = useMemo(() => {
    const rankOrder: Record<string, number> = { LEADER: 0, OFFICER: 1, MEMBER: 2 };
    return [...guildMembers].sort((a, b) => {
      const onlineDiff = (a.online ? 0 : 1) - (b.online ? 0 : 1);
      if (onlineDiff !== 0) return onlineDiff;
      const rankDiff = (rankOrder[a.rank] ?? 2) - (rankOrder[b.rank] ?? 2);
      if (rankDiff !== 0) return rankDiff;
      return a.name.localeCompare(b.name);
    });
  }, [guildMembers]);

  const sortedFriends = useMemo(() => {
    return [...friends].sort((a, b) => {
      const onlineDiff = (a.online ? 0 : 1) - (b.online ? 0 : 1);
      if (onlineDiff !== 0) return onlineDiff;
      return a.name.localeCompare(b.name);
    });
  }, [friends]);

  const [friendsNow, setFriendsNow] = useState(() => Date.now());
  const hasFriendNotifications = friendNotifications.length > 0;

  useEffect(() => {
    if (!hasFriendNotifications || activeSocialTab !== "friends") return;
    const interval = window.setInterval(() => setFriendsNow(Date.now()), 5_000);
    return () => window.clearInterval(interval);
  }, [hasFriendNotifications, activeSocialTab]);

  const recentNotifications = useMemo(() => {
    return friendNotifications.filter((n) => friendsNow - n.receivedAt < 30_000);
  }, [friendNotifications, friendsNow]);

  return (
    <section className="panel panel-chat" aria-label="Social channels">
      <div className="social-tabs" role="tablist" aria-label="Social sections">
        {SOCIAL_TABS.map((tab) => (
          <button
            key={tab.id}
            type="button"
            role="tab"
            className={`social-tab ${activeSocialTab === tab.id ? "social-tab-active" : ""}`}
            onClick={() => handleSocialTabChange(tab.id)}
            aria-selected={activeSocialTab === tab.id}
          >
            {tab.label}
          </button>
        ))}
      </div>

      <div className="chat-shell">

        {activeSocialTab === "friends" && (
          <>
            <form className="social-action-bar" onSubmit={(e) => { e.preventDefault(); const t = friendAddTarget.trim(); if (t) { onCommand(`friend add ${t}`); setFriendAddTarget(""); } }}>
              <input
                type="text"
                className="social-action-input"
                placeholder="Add friend\u2026"
                value={friendAddTarget}
                onChange={(e) => setFriendAddTarget(e.target.value)}
                aria-label="Friend name to add"
                disabled={!canChat}
              />
              <button type="submit" className="social-action-btn" disabled={!canChat || !friendAddTarget.trim()}>Add</button>
            </form>
            <div ref={feedRef} className="chat-feed" role="region" aria-label="Friends list">
              <section className="chat-feed-panel chat-feed-panel-flip" aria-label="Friends subwindow">
                {!canChat ? (
                  <p className="empty-note">
                    {connected ? "Log in to unlock social features." : "Reconnect to load social data."}
                  </p>
                ) : sortedFriends.length === 0 && recentNotifications.length === 0 ? (
                  <p className="empty-note">No friends yet. Add someone above to get started.</p>
                ) : (
                  <div className="friends-panel-content">
                    {recentNotifications.length > 0 && (
                      <ul className="friend-notifications">
                        {recentNotifications.map((n) => (
                          <li key={n.id} className={`friend-notification friend-notification-${n.event}`}>
                            <span className={`friend-status-dot friend-status-dot-${n.event === "online" ? "online" : "offline"}`} />
                            <span className="friend-notification-text">
                              {n.name} {n.event === "online" ? "came online" : "went offline"}
                            </span>
                          </li>
                        ))}
                      </ul>
                    )}
                    {sortedFriends.length > 0 && (
                      <ul className="friend-list">
                        {sortedFriends.map((friend) => (
                          <li key={friend.name} className={`friend-item ${friend.online ? "" : "friend-item-offline"}`}>
                            <div className="friend-item-left">
                              <span className={`friend-status-dot friend-status-dot-${friend.online ? "online" : "offline"}`} />
                              <span className="friend-item-name">{friend.name}</span>
                            </div>
                            <div className="friend-item-right">
                              {friend.online && friend.zone && <span className="friend-item-zone">{friend.zone}</span>}
                              {friend.level !== null && <span className="friend-item-level">Lv {friend.level}</span>}
                              {friend.online && (
                                <button
                                  type="button"
                                  className="who-tell-button"
                                  title={`Tell ${friend.name}`}
                                  aria-label={`Tell ${friend.name}`}
                                  onClick={() => handleTellFromWho(friend.name)}
                                >
                                  <TellIcon className="who-tell-icon" />
                                </button>
                              )}
                              {friendConfirmRemove === friend.name ? (
                                <span className="social-confirm-inline">
                                  <button type="button" className="social-confirm-yes" aria-label="Confirm removal" onClick={() => { onCommand(`friend remove ${friend.name}`); setFriendConfirmRemove(null); }}>Remove?</button>
                                  <button type="button" className="social-confirm-no" aria-label="Cancel removal" onClick={() => setFriendConfirmRemove(null)}>&times;</button>
                                </span>
                              ) : (
                                <button
                                  type="button"
                                  className="social-remove-btn"
                                  title={`Remove ${friend.name}`}
                                  aria-label={`Remove ${friend.name}`}
                                  onClick={() => setFriendConfirmRemove(friend.name)}
                                >
                                  &times;
                                </button>
                              )}
                            </div>
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                )}
              </section>
            </div>
            <div aria-hidden="true" />
          </>
        )}

        {activeSocialTab === "guild" && (
          !canChat ? (
            <>
              <div aria-hidden="true" />
              <div className="chat-feed" role="region" aria-label="Guild info">
                <section className="chat-feed-panel" aria-label="Guild subwindow">
                  <p className="empty-note">
                    {connected ? "Log in to unlock social features." : "Reconnect to load social data."}
                  </p>
                </section>
              </div>
              <div aria-hidden="true" />
            </>
          ) : !inGuild ? (
            <>
              <div aria-hidden="true" />
              <div className="chat-feed" role="region" aria-label="Guild info">
                <section className="chat-feed-panel" aria-label="Guild subwindow">
                  <div className="social-empty-action">
                    <p className="empty-note">You are not in a guild.</p>
                    {pendingGuildInvite ? (
                      <div className="invite-card">
                        <p className="invite-card-text"><strong>{pendingGuildInvite.inviterName}</strong> invites you to join <strong>{pendingGuildInvite.guildName}</strong> [{pendingGuildInvite.guildTag}].</p>
                        <div className="invite-card-actions">
                          <button type="button" className="social-action-btn social-accept-btn" onClick={() => { onCommand("guild accept"); }}>Accept</button>
                          <button type="button" className="social-action-btn social-decline-btn" onClick={() => onCommand("guild decline")}>Decline</button>
                        </div>
                      </div>
                    ) : (
                      <button type="button" className="social-action-btn social-accept-btn" onClick={() => onCommand("guild accept")}>Accept Invite</button>
                    )}
                    <form className="guild-create-form" onSubmit={(e) => { e.preventDefault(); const n = guildCreateName.trim(); const t = guildCreateTag.trim(); if (n && t) { onCommand(`guild create ${n} ${t}`); setGuildCreateName(""); setGuildCreateTag(""); } }}>
                      <input type="text" className="social-action-input" placeholder="Guild name" value={guildCreateName} onChange={(e) => setGuildCreateName(e.target.value)} aria-label="Guild name" />
                      <input type="text" className="social-action-input guild-tag-input" placeholder="Tag" value={guildCreateTag} onChange={(e) => setGuildCreateTag(e.target.value)} aria-label="Guild tag" maxLength={5} />
                      <button type="submit" className="social-action-btn" disabled={!guildCreateName.trim() || !guildCreateTag.trim()}>Create</button>
                    </form>
                  </div>
                </section>
              </div>
              <div aria-hidden="true" />
            </>
          ) : (
            <div className="guild-tab-layout">
              <div className="guild-tab-info">
                <div className="guild-compact-header">
                  <span className="guild-name">{guildInfo.name}</span>
                  {guildInfo.tag && <span className="guild-tag">[{guildInfo.tag}]</span>}
                  {guildInfo.rank && <span className="guild-compact-rank">{rankLabel(guildInfo.rank)}</span>}
                </div>
                <div className="guild-motd">
                  <span className="guild-motd-label">MOTD</span>
                  {guildEditingMotd ? (
                    <form className="guild-motd-edit" onSubmit={(e) => { e.preventDefault(); onCommand(`guild motd ${guildMotdDraft}`); setGuildEditingMotd(false); }}>
                      <input type="text" className="social-action-input" value={guildMotdDraft} onChange={(e) => setGuildMotdDraft(e.target.value)} aria-label="Guild MOTD" autoFocus />
                      <button type="submit" className="social-action-btn">Save</button>
                      <button type="button" className="social-action-btn social-cancel-btn" onClick={() => setGuildEditingMotd(false)}>Cancel</button>
                    </form>
                  ) : (
                    <div className="guild-motd-display">
                      <p className="guild-motd-text">{guildInfo.motd || "No message set."}</p>
                      {(guildInfo.rank === "LEADER" || guildInfo.rank === "OFFICER") && (
                        <button type="button" className="social-edit-btn" onClick={() => { setGuildMotdDraft(guildInfo.motd ?? ""); setGuildEditingMotd(true); }} title="Edit MOTD">&#9998;</button>
                      )}
                    </div>
                  )}
                </div>
                <form className="social-action-bar guild-invite-bar" onSubmit={(e) => { e.preventDefault(); const t = guildInviteTarget.trim(); if (t) { onCommand(`guild invite ${t}`); setGuildInviteTarget(""); } }}>
                  <input type="text" className="social-action-input" placeholder="Invite player\u2026" value={guildInviteTarget} onChange={(e) => setGuildInviteTarget(e.target.value)} aria-label="Player to invite" disabled={!canChat} />
                  <button type="submit" className="social-action-btn" disabled={!canChat || !guildInviteTarget.trim()}>Invite</button>
                </form>
                <div className="guild-roster-header">
                  Roster ({guildInfo.memberCount} / {guildInfo.maxSize})
                </div>
                <div className="guild-roster-scroll">
                  {sortedGuildMembers.length === 0 ? (
                    <p className="empty-note">No roster data yet.</p>
                  ) : (
                    <ul className="guild-member-list">
                      {sortedGuildMembers.map((member) => {
                        const isMe = member.name.localeCompare(playerName, undefined, { sensitivity: "accent" }) === 0;
                        const canManage = guildInfo.rank === "LEADER" || guildInfo.rank === "OFFICER";
                        const isLeader = guildInfo.rank === "LEADER";
                        const showActions = canManage && !isMe && member.rank !== "LEADER";
                        return (
                          <li key={member.name} className={`guild-member-item ${member.online ? "" : "guild-member-offline"}`}>
                            <span className="guild-member-name">{member.name}</span>
                            <span className="guild-member-details">
                              <span className={`guild-member-status ${member.online ? "guild-member-status-online" : "guild-member-status-offline"}`} />
                              <span className="guild-member-rank">{rankLabel(member.rank)}</span>
                              {member.level !== null && <span className="guild-member-level">Lv {member.level}</span>}
                              {showActions && (
                                <span className="guild-member-actions">
                                  {isLeader && member.rank === "MEMBER" && (
                                    <button type="button" className="social-inline-btn" onClick={() => onCommand(`guild promote ${member.name}`)} title="Promote">&#9650;</button>
                                  )}
                                  {isLeader && member.rank === "OFFICER" && (
                                    <button type="button" className="social-inline-btn" onClick={() => onCommand(`guild demote ${member.name}`)} title="Demote">&#9660;</button>
                                  )}
                                  <button type="button" className="social-inline-btn social-inline-btn-danger" onClick={() => onCommand(`guild kick ${member.name}`)} title="Kick">&times;</button>
                                </span>
                              )}
                            </span>
                          </li>
                        );
                      })}
                    </ul>
                  )}
                </div>
                {guildHall && (
                  <div className="guild-hall-section">
                    <div className="guild-hall-header">
                      <span className="guild-hall-label">Guild Hall</span>
                      <span className="guild-hall-meta">{guildHall.rooms.length} / {guildHall.maxRooms} rooms &middot; {guildHall.membersInHall} inside</span>
                    </div>
                    {guildHall.rooms.length > 0 && (
                      <ul className="guild-hall-room-list">
                        {guildHall.rooms.map((room) => (
                          <li key={room.id} className="guild-hall-room-item">
                            <span className="guild-hall-room-title">{room.title}</span>
                            <span className="guild-hall-room-template">{room.template}</span>
                          </li>
                        ))}
                      </ul>
                    )}
                    <div className="guild-hall-actions">
                      <button type="button" className="social-action-btn" onClick={() => onCommand("guild hallenter")}>Enter Hall</button>
                      {(guildInfo.rank === "LEADER" || guildInfo.rank === "OFFICER") && (
                        <button type="button" className="social-action-btn" onClick={() => onCommand("guild hallexpand")}>Expand</button>
                      )}
                    </div>
                  </div>
                )}
                {!guildHall && (guildInfo.rank === "LEADER" || guildInfo.rank === "OFFICER") && (
                  <div className="guild-hall-section">
                    <div className="guild-hall-header">
                      <span className="guild-hall-label">Guild Hall</span>
                    </div>
                    <p className="empty-note">No guild hall yet.</p>
                    <div className="guild-hall-actions">
                      <button type="button" className="social-action-btn" onClick={() => onCommand("guild hallbuy")}>Purchase Hall</button>
                    </div>
                  </div>
                )}
                <div className="guild-footer-actions">
                  {guildConfirmAction === "leave" ? (
                    <span className="social-confirm-inline">
                      <span>Leave guild?</span>
                      <button type="button" className="social-confirm-yes" onClick={() => { onCommand("guild leave"); setGuildConfirmAction(null); }}>Yes</button>
                      <button type="button" className="social-confirm-no" onClick={() => setGuildConfirmAction(null)}>No</button>
                    </span>
                  ) : guildConfirmAction === "disband" ? (
                    <span className="social-confirm-inline">
                      <span>Disband guild?</span>
                      <button type="button" className="social-confirm-yes" onClick={() => { onCommand("guild disband"); setGuildConfirmAction(null); }}>Yes</button>
                      <button type="button" className="social-confirm-no" onClick={() => setGuildConfirmAction(null)}>No</button>
                    </span>
                  ) : (
                    <>
                      <button type="button" className="social-action-btn social-danger-btn" onClick={() => setGuildConfirmAction("leave")}>Leave</button>
                      {guildInfo.rank === "LEADER" && (
                        <button type="button" className="social-action-btn social-danger-btn" onClick={() => setGuildConfirmAction("disband")}>Disband</button>
                      )}
                    </>
                  )}
                </div>
              </div>

              <div ref={guildFeedRef} className="embedded-chat-feed" role="log" aria-live="polite" aria-label="Guild chat messages">
                {gchatMessages.length === 0 ? (
                  <p className="empty-note">No guild messages yet.</p>
                ) : (
                  <ul className="chat-message-list">
                    {gchatMessages.map((entry) => {
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
                  const sent = onSendMessage("gchat", guildDraft, null);
                  if (sent) setGuildDraft("");
                }}
              >
                <input
                  className="chat-input"
                  type="text"
                  value={guildDraft}
                  onChange={(event) => setGuildDraft(event.target.value)}
                  placeholder="Message your guild"
                  aria-label="Guild chat message"
                  autoComplete="off"
                  spellCheck={false}
                  disabled={!canChat}
                />
                <button type="submit" className="soft-button" disabled={!canChat}>Send</button>
              </form>
            </div>
          )
        )}

        {activeSocialTab === "group" && (
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
