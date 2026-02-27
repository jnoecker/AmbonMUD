import { useEffect, useState } from "react";
import { COMPASS_DIRECTIONS } from "../../constants";
import type { RoomItem, RoomMob, RoomPlayer, RoomState, SkillSummary } from "../../types";
import { percent } from "../../utils";
import { AttackIcon, CompassCoreIcon, DirectionIcon, ExpandRoomIcon, MapScrollIcon, PickupIcon, SkillCastIcon } from "../Icons";

interface WorldPanelProps {
  connected: boolean;
  hasRoomDetails: boolean;
  canOpenMap: boolean;
  room: RoomState;
  exits: Array<[string, string]>;
  availableExitSet: Set<string>;
  players: RoomPlayer[];
  mobs: RoomMob[];
  visiblePlayers: RoomPlayer[];
  hiddenPlayersCount: number;
  visibleMobs: RoomMob[];
  hiddenMobsCount: number;
  roomItems: RoomItem[];
  visibleRoomItems: RoomItem[];
  hiddenRoomItemsCount: number;
  showSkillsPanel: boolean;
  skills: SkillSummary[];
  onOpenMap: () => void;
  onOpenRoom: () => void;
  onRefreshSkills: () => void;
  onCastSkill: (skillId: string, cooldownMs: number) => void;
  onMove: (direction: string) => void;
  onAttackMob: (mobName: string) => void;
  onPickUpItem: (itemName: string) => void;
}

export function WorldPanel({
  connected,
  hasRoomDetails,
  canOpenMap,
  room,
  exits,
  availableExitSet,
  players,
  mobs,
  visiblePlayers,
  hiddenPlayersCount,
  visibleMobs,
  hiddenMobsCount,
  roomItems,
  visibleRoomItems,
  hiddenRoomItemsCount,
  showSkillsPanel,
  skills,
  onOpenMap,
  onOpenRoom,
  onRefreshSkills,
  onCastSkill,
  onMove,
  onAttackMob,
  onPickUpItem,
}: WorldPanelProps) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (!showSkillsPanel) return undefined;
    const interval = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(interval);
  }, [showSkillsPanel]);

  return (
    <section className="panel panel-world" aria-label="World state">
      <div className="world-stack">
        <article className="subpanel">
          {hasRoomDetails ? (
            <div className="room-main">
              <div className="room-title-row">
                <p className="room-title">{room.title}</p>
                <button
                  type="button"
                  className="map-icon-trigger room-map-trigger"
                  onClick={onOpenMap}
                  disabled={!canOpenMap}
                  aria-label={canOpenMap ? "Open mini-map" : "Mini-map unavailable before login"}
                  title={canOpenMap ? "Open mini-map" : "Mini-map unavailable before login"}
                >
                  <MapScrollIcon className="map-icon-svg" />
                  <span className="sr-only">Mini-map</span>
                </button>
              </div>
              <div className="room-description-wrap" aria-label="Room description">
                <p className="room-description">{room.description || "No room description available yet."}</p>
                <button
                  type="button"
                  className="map-icon-trigger room-expand-trigger"
                  onClick={onOpenRoom}
                  aria-label="Expand room text"
                  title="Expand room text"
                >
                  <ExpandRoomIcon className="map-icon-svg room-expand-icon" />
                  <span className="sr-only">Expand room text</span>
                </button>
              </div>
              <div className="compass-block" aria-label="Current exits">
                <div className="compass-rose" role="group" aria-label="Directional exits">
                  {COMPASS_DIRECTIONS.map((direction) => {
                    const enabled = availableExitSet.has(direction);
                    return (
                      <button
                        key={direction}
                        type="button"
                        className={`compass-node compass-node-${direction}`}
                        disabled={!enabled}
                        aria-label={enabled ? `Move ${direction}` : `${direction} exit unavailable`}
                        title={enabled ? `Move ${direction}` : `${direction} unavailable`}
                        onClick={() => onMove(direction)}
                      >
                        <DirectionIcon direction={direction} className="compass-node-icon" />
                      </button>
                    );
                  })}
                  <span className="compass-core" aria-hidden="true">
                    <CompassCoreIcon className="compass-core-icon" />
                  </span>
                </div>
                <p className="compass-caption">
                  {exits.length === 0 ? "No exits listed." : `Available: ${exits.map(([direction]) => direction).join(", ")}`}
                </p>
              </div>
            </div>
          ) : (
            <div className="prelogin-card">
              <h3>{connected ? "World Gate" : "World Offline"}</h3>
              <p className="prelogin-card-title">{connected ? "Awaiting your credentials" : "Disconnected from AmbonMUD"}</p>
              <p className="room-description">
                {connected
                  ? "Once you finish login in the terminal, this panel will show your current room, exits, players, and nearby mobs."
                  : "Reconnect to establish a session. The world map and local room context will appear as soon as a session is active."}
              </p>
              <div className="prelogin-runes" aria-hidden="true">
                <span />
                <span />
                <span />
              </div>
            </div>
          )}
        </article>

        {showSkillsPanel ? (
          <article className="subpanel split-list skills-combat-panel" aria-label="Combat skills">
            <div className="skills-combat-header">
              <h3>Skills</h3>
              <button
                type="button"
                className="soft-button"
                onClick={onRefreshSkills}
                disabled={!connected || !hasRoomDetails}
              >
                Refresh
              </button>
            </div>
            {skills.length === 0 ? (
              <p className="empty-note">No skills loaded yet. Press refresh or try `skills`.</p>
            ) : (
              <ul className="skills-combat-list">
                {skills.map((skill) => {
                  const elapsed = Math.max(0, now - skill.receivedAt);
                  const remainingMs = Math.max(0, skill.cooldownRemainingMs - elapsed);
                  const cooldownSeconds = Math.ceil(remainingMs / 1000);
                  const isReady = remainingMs <= 0;
                  return (
                    <li key={skill.id} className="skills-combat-item">
                      <div className="skills-combat-item-top">
                        <span className="skills-combat-name">{skill.name}</span>
                        <span className="skills-combat-meta">{skill.manaCost} mana</span>
                      </div>
                      <p className="skills-combat-desc">{skill.description || `${skill.targetType} skill`}</p>
                      <div className="skills-combat-actions">
                        <span className={`skills-combat-cooldown ${isReady ? "skills-combat-cooldown-ready" : ""}`}>
                          {isReady ? "Ready" : `${cooldownSeconds}s`}
                        </span>
                        <button
                          type="button"
                          className="mob-command-button"
                          title={`Cast ${skill.name}`}
                          aria-label={`Cast ${skill.name}`}
                          onClick={() => onCastSkill(skill.id, skill.cooldownMs)}
                        >
                          <SkillCastIcon
                            className="mob-command-icon"
                            classRestriction={skill.classRestriction}
                            targetType={skill.targetType}
                          />
                        </button>
                      </div>
                    </li>
                  );
                })}
              </ul>
            )}
          </article>
        ) : (
          <article className="subpanel split-list">
            <div>
              <h3>Players</h3>
              {players.length === 0 ? <p className="empty-note">{hasRoomDetails ? "Nobody else is here." : "Online players will appear here after login."}</p> : (
                <>
                  <ul className="entity-list">
                    {visiblePlayers.map((player) => (
                      <li key={player.name} className="entity-item"><span>{player.name}</span><span className="entity-meta">Lv {player.level}</span></li>
                    ))}
                  </ul>
                  {hiddenPlayersCount > 0 && <p className="empty-note">+{hiddenPlayersCount} more players</p>}
                </>
              )}
            </div>

            <div>
              <h3>Mobs</h3>
              {mobs.length === 0 ? <p className="empty-note">{hasRoomDetails ? "No mobs in this room." : "Nearby creatures will appear here after login."}</p> : (
                <>
                  <ul className="entity-list">
                    {visibleMobs.map((mob) => (
                      <li key={mob.id} className="mob-card">
                        <div className="entity-item">
                          <span>{mob.name}</span>
                          <span className="mob-meta-actions">
                            <span className="entity-meta">{mob.hp}/{mob.maxHp}</span>
                            <button
                              type="button"
                              className="mob-command-button"
                              title={`Attack ${mob.name}`}
                              aria-label={`Attack ${mob.name}`}
                              onClick={() => onAttackMob(mob.name)}
                            >
                              <AttackIcon className="mob-command-icon" />
                            </button>
                          </span>
                        </div>
                        <div className="meter-track"><span className="meter-fill meter-fill-hp" style={{ width: `${percent(mob.hp, mob.maxHp)}%` }} /></div>
                      </li>
                    ))}
                  </ul>
                  {hiddenMobsCount > 0 && <p className="empty-note">+{hiddenMobsCount} more mobs</p>}
                </>
              )}
            </div>

            <div>
              <h3>Items</h3>
              {roomItems.length === 0 ? <p className="empty-note">{hasRoomDetails ? "No items in this room." : "Room items will appear here after login."}</p> : (
                <>
                  <ul className="entity-list">
                    {visibleRoomItems.map((item, index) => (
                      <li key={`${item.id}-${index}`} className="entity-item">
                        <span>{item.name}</span>
                        <button
                          type="button"
                          className="mob-command-button"
                          title={`Pick up ${item.name}`}
                          aria-label={`Pick up ${item.name}`}
                          disabled={!connected || !hasRoomDetails}
                          onClick={() => onPickUpItem(item.name)}
                        >
                          <PickupIcon className="mob-command-icon" />
                        </button>
                      </li>
                    ))}
                  </ul>
                  {hiddenRoomItemsCount > 0 && <p className="empty-note">+{hiddenRoomItemsCount} more items</p>}
                </>
              )}
            </div>
          </article>
        )}
      </div>
    </section>
  );
}

