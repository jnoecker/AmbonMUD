import type { FormEvent, KeyboardEvent, MouseEvent, RefObject } from "react";
import type { CombatTarget, RoomItem, RoomMob } from "../../types";
import { percent } from "../../utils";
import { AttackIcon, CrosshairIcon, DirectionIcon, FleeIcon, PickupIcon, TalkIcon } from "../Icons";
import { isDirection } from "../isDirection";

interface PlayPanelProps {
  preLogin: boolean;
  connected: boolean;
  hasRoomDetails: boolean;
  roomImage: string | null | undefined;
  roomTitle: string;
  exits: Array<[string, string]>;
  mobs: RoomMob[];
  roomItems: RoomItem[];
  combatTarget: CombatTarget | null;
  terminalHostRef: RefObject<HTMLDivElement | null>;
  commandInputRef: RefObject<HTMLInputElement | null>;
  composerValue: string;
  commandPlaceholder: string;
  onTerminalMouseDown: (event: MouseEvent<HTMLDivElement>) => void;
  onComposerChange: (value: string) => void;
  onComposerKeyDown: (event: KeyboardEvent<HTMLInputElement>) => void;
  onSubmitComposer: (event: FormEvent<HTMLFormElement>) => void;
  onMove: (direction: string) => void;
  onFlee: () => void;
  onTalkToMob: (mobName: string) => void;
  onAttackMob: (mobName: string) => void;
  onPickUpItem: (itemName: string) => void;
  onOpenMobDetail: (mob: RoomMob) => void;
  onOpenItemDetail: (item: RoomItem) => void;
}

export function PlayPanel({
  preLogin,
  connected,
  hasRoomDetails,
  roomImage,
  roomTitle,
  exits,
  mobs,
  roomItems,
  combatTarget,
  terminalHostRef,
  commandInputRef,
  composerValue,
  commandPlaceholder,
  onTerminalMouseDown,
  onComposerChange,
  onComposerKeyDown,
  onSubmitComposer,
  onMove,
  onFlee,
  onTalkToMob,
  onAttackMob,
  onPickUpItem,
  onOpenMobDetail,
  onOpenItemDetail,
}: PlayPanelProps) {
  const showEntities = hasRoomDetails && (mobs.length > 0 || roomItems.length > 0);

  return (
    <section className="panel panel-play" aria-label="Gameplay console">
      {preLogin && (
        <section className="prelogin-banner" aria-label="Login guidance">
          <p className="prelogin-banner-title">Welcome back. Your session is connected.</p>
          <p className="prelogin-banner-text">Use the command bar below to enter your character name and password. World and character panels will populate right after login.</p>
        </section>
      )}
      {roomImage && (
        <div className="room-banner" aria-label="Room scene">
          <img src={roomImage} alt={roomTitle} className="room-banner-image" />
        </div>
      )}
      {combatTarget?.targetId && (
        <div className="combat-target-mini" aria-label="Combat target">
          <CrosshairIcon className="combat-mini-icon" />
          <span className="combat-mini-name">{combatTarget.targetName}</span>
          <div className="combat-mini-bar">
            <span
              className="meter-fill meter-fill-hp"
              style={{ width: `${percent(combatTarget.targetHp ?? 0, combatTarget.targetMaxHp ?? 1)}%` }}
            />
          </div>
          <span className="combat-mini-hp">{combatTarget.targetHp}/{combatTarget.targetMaxHp}</span>
        </div>
      )}
      {showEntities && (
        <div className="play-entities" aria-label="Room contents">
          {mobs.length > 0 && (
            <div className="play-entity-group">
              <h3 className="play-entity-heading">Mobs</h3>
              <ul className="entity-list">
                {mobs.map((mob) => (
                  <li key={mob.id} className="mob-card">
                    <div className="entity-item">
                      <button
                        type="button"
                        className="entity-name-with-thumb entity-thumb-clickable"
                        title={`View ${mob.name} details`}
                        onClick={() => onOpenMobDetail(mob)}
                      >
                        {mob.image && <img src={mob.image} alt="" className="entity-thumb play-entity-thumb" />}
                        {mob.name}
                      </button>
                      <span className="mob-meta-actions">
                        <span className="entity-meta">{mob.hp}/{mob.maxHp}</span>
                        <button
                          type="button"
                          className="mob-command-button"
                          title={`Talk to ${mob.name}`}
                          aria-label={`Talk to ${mob.name}`}
                          onClick={() => onTalkToMob(mob.name)}
                        >
                          <TalkIcon className="mob-command-icon" />
                        </button>
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
            </div>
          )}
          {roomItems.length > 0 && (
            <div className="play-entity-group">
              <h3 className="play-entity-heading">Items</h3>
              <ul className="entity-list">
                {roomItems.map((item, index) => (
                  <li key={`${item.id}-${index}`} className="entity-item">
                    <button
                      type="button"
                      className="entity-name-with-thumb entity-thumb-clickable"
                      title={`View ${item.name} details`}
                      onClick={() => onOpenItemDetail(item)}
                    >
                      {item.image && <img src={item.image} alt="" className="entity-thumb play-entity-thumb" />}
                      {item.name}
                    </button>
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
            </div>
          )}
        </div>
      )}
      <div className="terminal-card" onMouseDown={onTerminalMouseDown}><div ref={terminalHostRef} className="terminal-host" aria-label="AmbonMUD terminal" /></div>

      <form className="command-form" onSubmit={onSubmitComposer}>
        <label htmlFor="command-input" className="sr-only">Command input</label>
        <input
          ref={commandInputRef}
          id="command-input"
          className="command-input"
          type="text"
          value={composerValue}
          onChange={(event) => onComposerChange(event.target.value)}
          onKeyDown={onComposerKeyDown}
          placeholder={commandPlaceholder}
          autoComplete="off"
          spellCheck={false}
        />
        <button type="submit" className="soft-button" disabled={!connected}>Send</button>
      </form>

      <div className="movement-grid" role="toolbar" aria-label="Room exits">
        {exits.length === 0 ? (
          preLogin ? null : <span className="empty-note">{connected ? "No exits available." : "Reconnect to view exits."}</span>
        ) : (
          exits.map(([direction, target]) => {
            const normalized = direction.toLowerCase();
            const knownDirection = isDirection(normalized);
            return (
              <button
                key={direction}
                type="button"
                className="chip-button"
                title={`Move ${direction} (${target})`}
                onClick={() => onMove(direction)}
              >
                {knownDirection && <DirectionIcon direction={normalized} className="chip-direction-icon" />}
                <span className="chip-label">{direction}</span>
              </button>
            );
          })
        )}
        {connected && hasRoomDetails && (
          <button
            type="button"
            className="chip-button chip-button-utility"
            title="Attempt to flee from combat"
            onClick={onFlee}
          >
            <FleeIcon className="chip-direction-icon" />
            <span className="chip-label">flee</span>
          </button>
        )}
      </div>
    </section>
  );
}
