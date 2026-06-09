import { useMemo, useState } from "react";
import type { FormEvent } from "react";
import type { RoomMob, StaffMobZone, StaffWorldZone, UiFeedbackEntry, WhoPlayer } from "../../types";
import {
  ADMIN_ACTION_SECTIONS,
  ADMIN_ACTIONS,
  ADMIN_RELOAD_SCOPES,
  buildAdminCommand,
  getAdminActionDefinition,
  requiresAdminConfirmation,
} from "./AdminPanel.logic";
import type { AdminAction } from "./AdminPanel.logic";

interface AdminPanelProps {
  onCommand: (command: string) => void;
  onClose: () => void;
  /** Painted `admin_bg` frame URL; null falls back to the plain dialog skin. */
  backgroundImage: string | null;
  worldInfo: StaffWorldZone[];
  mobTemplates: StaffMobZone[];
  whoPlayers: WhoPlayer[];
  roomMobs: RoomMob[];
  currentPlayerName: string;
  possessing: string | null;
  invisible: boolean;
  feedbackFeed: UiFeedbackEntry[];
  /** Global painted assets; used here for the optional stained-glass command button. */
  serverAssets?: Record<string, string>;
}

function TeleportBrowser({
  worldInfo,
  whoPlayers,
  filter,
  onSetFilter,
  onSelect,
  label,
}: {
  worldInfo: StaffWorldZone[];
  whoPlayers: WhoPlayer[];
  filter: string;
  onSetFilter: (v: string) => void;
  onSelect: (target: string) => void;
  label: string;
}) {
  const [expandedZone, setExpandedZone] = useState<string | null>(null);
  const lowerFilter = filter.toLowerCase();

  const filteredPlayers = useMemo(
    () => whoPlayers.filter((p) => p.name.toLowerCase().includes(lowerFilter)),
    [whoPlayers, lowerFilter],
  );

  const filteredZones = useMemo(() => {
    if (!lowerFilter) return worldInfo;
    return worldInfo
      .map((z) => ({
        ...z,
        rooms: z.rooms.filter(
          (r) =>
            r.id.toLowerCase().includes(lowerFilter) ||
            r.title.toLowerCase().includes(lowerFilter),
        ),
      }))
      .filter((z) => z.zone.toLowerCase().includes(lowerFilter) || z.rooms.length > 0);
  }, [worldInfo, lowerFilter]);

  return (
    <div className="teleport-browser">
      <input
        type="text"
        className="admin-input teleport-filter"
        placeholder="Filter zones, rooms, players..."
        value={filter}
        onChange={(e) => onSetFilter(e.target.value)}
        autoFocus
      />

      {filteredPlayers.length > 0 && (
        <div className="teleport-section">
          <h4 className="teleport-section-title">Online Players</h4>
          <ul className="teleport-list">
            {filteredPlayers.map((p) => (
              <li key={p.name} className="teleport-item teleport-item-player">
                <span className="teleport-item-room">
                  <span className="teleport-item-name">{p.name}</span>
                  <span className="teleport-item-title">
                    Lv {p.level} - {p.race} {p.playerClass}
                  </span>
                </span>
                <button
                  type="button"
                  className="teleport-go-btn"
                  onClick={() => onSelect(p.name)}
                >
                  {label}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      {filteredZones.map((z) => {
        const isExpanded = expandedZone === z.zone || lowerFilter.length > 0;
        const roomCount = z.rooms.length;
        return (
          <div key={z.zone} className="teleport-section">
            <button
              type="button"
              className={`teleport-zone-header ${isExpanded ? "teleport-zone-header-expanded" : ""}`}
              onClick={() => setExpandedZone(expandedZone === z.zone ? null : z.zone)}
            >
              <span className="teleport-zone-arrow">{isExpanded ? "\u25BE" : "\u25B8"}</span>
              <span className="teleport-zone-name">{z.zone}</span>
              <span className="teleport-zone-count">{roomCount} rooms</span>
            </button>
            {isExpanded && (
              <ul className="teleport-list">
                {z.rooms.map((room) => (
                  <li key={room.id} className="teleport-item">
                    <span className="teleport-item-room">
                      <span className="teleport-item-id">{room.id}</span>
                      {room.title && (
                        <span className="teleport-item-title">{room.title}</span>
                      )}
                    </span>
                    <button
                      type="button"
                      className="teleport-go-btn"
                      onClick={() => onSelect(room.id)}
                    >
                      {label}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        );
      })}

      {filteredZones.length === 0 && filteredPlayers.length === 0 && (
        <p className="empty-note">No matches for "{filter}"</p>
      )}
    </div>
  );
}

function MobTemplateBrowser({
  mobTemplates,
  filter,
  onSetFilter,
  onSelect,
}: {
  mobTemplates: StaffMobZone[];
  filter: string;
  onSetFilter: (v: string) => void;
  onSelect: (templateId: string) => void;
}) {
  const [expandedZone, setExpandedZone] = useState<string | null>(null);
  const lowerFilter = filter.toLowerCase();

  const filteredZones = useMemo(() => {
    if (!lowerFilter) return mobTemplates;
    return mobTemplates
      .map((z) => ({
        ...z,
        mobs: z.mobs.filter(
          (m) =>
            m.id.toLowerCase().includes(lowerFilter) ||
            m.name.toLowerCase().includes(lowerFilter),
        ),
      }))
      .filter((z) => z.zone.toLowerCase().includes(lowerFilter) || z.mobs.length > 0);
  }, [mobTemplates, lowerFilter]);

  return (
    <div className="teleport-browser">
      <input
        type="text"
        className="admin-input teleport-filter"
        placeholder="Filter mob templates..."
        value={filter}
        onChange={(e) => onSetFilter(e.target.value)}
      />
      {filteredZones.map((z) => {
        const isExpanded = expandedZone === z.zone || lowerFilter.length > 0;
        return (
          <div key={z.zone} className="teleport-section">
            <button
              type="button"
              className={`teleport-zone-header ${isExpanded ? "teleport-zone-header-expanded" : ""}`}
              onClick={() => setExpandedZone(expandedZone === z.zone ? null : z.zone)}
            >
              <span className="teleport-zone-arrow">{isExpanded ? "\u25BE" : "\u25B8"}</span>
              <span className="teleport-zone-name">{z.zone}</span>
              <span className="teleport-zone-count">{z.mobs.length} mobs</span>
            </button>
            {isExpanded && (
              <ul className="teleport-list">
                {z.mobs.map((mob) => (
                  <li key={mob.id} className="teleport-item">
                    <span className="teleport-item-room">
                      <span className="teleport-item-id">{mob.id}</span>
                      <span className="teleport-item-title">{mob.name}</span>
                    </span>
                    <button
                      type="button"
                      className="teleport-go-btn"
                      onClick={() => onSelect(mob.id)}
                    >
                      Spawn
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        );
      })}
      {filteredZones.length === 0 && (
        <p className="empty-note">No mob templates match &ldquo;{filter}&rdquo;</p>
      )}
    </div>
  );
}

function TargetBrowser({
  whoPlayers = [],
  roomMobs = [],
  filter,
  onSetFilter,
  onSelect,
  buttonLabel,
  emptyMessage,
  currentPlayerName,
  disabledPlayerNames = [],
  playerSectionTitle = "Online Players",
  mobSectionTitle = "Current Room Mobs",
}: {
  whoPlayers?: WhoPlayer[];
  roomMobs?: RoomMob[];
  filter: string;
  onSetFilter: (v: string) => void;
  onSelect: (target: string) => void;
  buttonLabel: string;
  emptyMessage: string;
  currentPlayerName: string;
  disabledPlayerNames?: string[];
  playerSectionTitle?: string;
  mobSectionTitle?: string;
}) {
  const lowerFilter = filter.toLowerCase();
  const filteredPlayers = useMemo(
    () =>
      [...whoPlayers]
        .sort((a, b) => a.name.localeCompare(b.name))
        .filter(
          (player) =>
            player.name.toLowerCase().includes(lowerFilter) ||
            player.race.toLowerCase().includes(lowerFilter) ||
            player.playerClass.toLowerCase().includes(lowerFilter),
        ),
    [whoPlayers, lowerFilter],
  );
  const filteredMobs = useMemo(
    () =>
      [...roomMobs]
        .sort((a, b) => a.name.localeCompare(b.name))
        .filter(
          (mob) =>
            mob.name.toLowerCase().includes(lowerFilter) ||
            mob.id.toLowerCase().includes(lowerFilter),
        ),
    [roomMobs, lowerFilter],
  );

  return (
    <div className="teleport-browser">
      <input
        type="text"
        className="admin-input teleport-filter"
        placeholder="Filter live targets..."
        value={filter}
        onChange={(e) => onSetFilter(e.target.value)}
      />

      {filteredPlayers.length > 0 && (
        <div className="teleport-section">
          <h4 className="teleport-section-title">{playerSectionTitle}</h4>
          <ul className="teleport-list">
            {filteredPlayers.map((player) => {
              const isDisabled = disabledPlayerNames.includes(player.name);
              const isSelf = player.name === currentPlayerName;
              return (
                <li key={player.name} className="teleport-item teleport-item-player">
                  <span className="teleport-item-room">
                    <span className="teleport-item-name">
                      {player.name}
                      {isSelf ? " (you)" : ""}
                    </span>
                    <span className="teleport-item-meta">
                      Lv {player.level} - {player.race} {player.playerClass}
                    </span>
                  </span>
                  <button
                    type="button"
                    className="teleport-go-btn"
                    disabled={isDisabled}
                    onClick={() => onSelect(player.name)}
                  >
                    {isDisabled ? "Blocked" : buttonLabel}
                  </button>
                </li>
              );
            })}
          </ul>
        </div>
      )}

      {filteredMobs.length > 0 && (
        <div className="teleport-section">
          <h4 className="teleport-section-title">{mobSectionTitle}</h4>
          <ul className="teleport-list">
            {filteredMobs.map((mob) => (
              <li key={mob.id} className="teleport-item">
                <span className="teleport-item-room">
                  <span className="teleport-item-title">{mob.name}</span>
                  <span className="teleport-item-meta">HP {mob.hp}/{mob.maxHp}</span>
                </span>
                <button
                  type="button"
                  className="teleport-go-btn"
                  onClick={() => onSelect(mob.name)}
                >
                  {buttonLabel}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      {filteredPlayers.length === 0 && filteredMobs.length === 0 && (
        <p className="empty-note">{emptyMessage}</p>
      )}
    </div>
  );
}

export function AdminPanel({
  onCommand,
  onClose,
  backgroundImage,
  worldInfo,
  mobTemplates,
  whoPlayers,
  roomMobs,
  currentPlayerName,
  possessing,
  invisible,
  feedbackFeed,
  serverAssets = {},
}: AdminPanelProps) {
  const [activeAction, setActiveAction] = useState<AdminAction | null>(null);
  const [inputA, setInputA] = useState("");
  const [inputB, setInputB] = useState("");
  const [gotoFilter, setGotoFilter] = useState("");
  const [transferPlayerFilter, setTransferPlayerFilter] = useState("");
  const [transferRoomFilter, setTransferRoomFilter] = useState("");
  const [spawnFilter, setSpawnFilter] = useState("");
  const [smiteFilter, setSmiteFilter] = useState("");
  const [kickFilter, setKickFilter] = useState("");
  const [setLevelFilter, setSetLevelFilter] = useState("");
  const [setStaffFilter, setSetStaffFilter] = useState("");
  const [setGoldFilter, setSetGoldFilter] = useState("");
  const [setRaceFilter, setSetRaceFilter] = useState("");
  const [setClassFilter, setSetClassFilter] = useState("");
  const [setGenderFilter, setSetGenderFilter] = useState("");
  const [setXpFilter, setSetXpFilter] = useState("");
  const [healFilter, setHealFilter] = useState("");
  const [pinfoFilter, setPinfoFilter] = useState("");
  const [dispelFilter, setDispelFilter] = useState("");
  const [possessFilter, setPossessFilter] = useState("");
  const [confirmKey, setConfirmKey] = useState<string | null>(null);

  const updateInputA = (value: string) => {
    setInputA(value);
    setConfirmKey(null);
  };

  const updateInputB = (value: string) => {
    setInputB(value);
    setConfirmKey(null);
  };

  const syncInputA = (value: string) => {
    if (inputA === value) {
      setInputA(value);
      return;
    }
    updateInputA(value);
  };

  const resetForm = () => {
    setInputA("");
    setInputB("");
    setGotoFilter("");
    setTransferPlayerFilter("");
    setTransferRoomFilter("");
    setSpawnFilter("");
    setSmiteFilter("");
    setKickFilter("");
    setSetLevelFilter("");
    setSetGoldFilter("");
    setSetRaceFilter("");
    setSetClassFilter("");
    setSetGenderFilter("");
    setSetXpFilter("");
    setHealFilter("");
    setPinfoFilter("");
    setDispelFilter("");
    setPossessFilter("");
    setConfirmKey(null);
  };

  const adminFeedback = useMemo(
    () => [...feedbackFeed].reverse().filter((entry) => entry.scope === "admin"),
    [feedbackFeed],
  );

  const activeFeedback = useMemo(() => {
    if (adminFeedback.length === 0) return null;
    if (activeAction) {
      const exactMatch = adminFeedback.find((entry) => entry.command === activeAction);
      if (exactMatch) return exactMatch;
    }
    return adminFeedback[0];
  }, [adminFeedback, activeAction]);

  const activeDefinition = activeAction ? getAdminActionDefinition(activeAction) : null;
  const pendingCommand = activeAction ? buildAdminCommand(activeAction, inputA, inputB) : null;
  const pendingConfirmKey =
    activeAction && pendingCommand ? `${activeAction}:${pendingCommand}` : null;
  const confirmArmed = pendingConfirmKey !== null && confirmKey === pendingConfirmKey;

  const executeAction = (action: AdminAction, command: string) => {
    if (requiresAdminConfirmation(action)) {
      const nextKey = `${action}:${command}`;
      if (confirmKey !== nextKey) {
        setConfirmKey(nextKey);
        return;
      }
    }
    onCommand(command);
    resetForm();
  };

  const selectAction = (action: AdminAction) => {
    if (action === "return" || action === "invis") {
      const command = buildAdminCommand(action, "", "");
      if (command) executeAction(action, command);
      setActiveAction(null);
      return;
    }
    setActiveAction((current) => (current === action ? null : action));
    resetForm();
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!activeAction || !pendingCommand) return;
    executeAction(activeAction, pendingCommand);
  };

  // Painted-frame CSS variables. The command tiles fall back to a gilded-glass
  // gradient (see styles.css) and pick up a real stained-glass asset if the
  // server advertises `staff_action_btn` / `staff_action_btn_active`.
  const skinVars: Record<string, string> = {};
  if (backgroundImage) skinVars["--admin-bg"] = `url("${backgroundImage}")`;
  if (serverAssets.staff_action_btn) {
    skinVars["--admin-action-btn"] = `url("${serverAssets.staff_action_btn}")`;
    // The asset carries its own gold frame, so suppress the CSS edge/rounding
    // that the gradient fallback uses (avoids a double border and clipped corners).
    skinVars["--admin-action-edge"] = "transparent";
    skinVars["--admin-action-radius"] = "0px";
  }
  if (serverAssets.staff_action_btn_active) {
    skinVars["--admin-action-btn-active"] = `url("${serverAssets.staff_action_btn_active}")`;
  }

  return (
    <div className="popout-backdrop" onClick={onClose}>
      <section
        className={`popout-dialog admin-dialog${backgroundImage ? " admin-dialog-skinned" : ""}`}
        style={Object.keys(skinVars).length > 0 ? skinVars : undefined}
        role="dialog"
        aria-modal="true"
        aria-label="Staff Administration"
        onClick={(event) => event.stopPropagation()}
      >
        {backgroundImage ? (
          <button type="button" className="admin-skin-close" onClick={onClose} aria-label="Close">
            ✕
          </button>
        ) : (
          <header className="popout-header">
            <h2>Admin Console</h2>
            <button type="button" className="soft-button popout-close" onClick={onClose}>
              Close
            </button>
          </header>
        )}

        <div className="popout-content admin-content">
          <div className="admin-status-strip">
            <div className="admin-status-card">
              <span className="admin-status-label">Visibility</span>
              <strong>{invisible ? "Invisible" : "Visible"}</strong>
            </div>
            <div className="admin-status-card">
              <span className="admin-status-label">Possession</span>
              <strong>{possessing ?? "Body"}</strong>
            </div>
            <div className="admin-status-card">
              <span className="admin-status-label">Players Online</span>
              <strong>{whoPlayers.length}</strong>
            </div>
          </div>

          <div className="admin-main-pane">
          {activeFeedback && (
            <p className={`admin-feedback-banner admin-feedback-banner-${activeFeedback.type}`}>
              {activeFeedback.message}
            </p>
          )}

          <div className="admin-workspace">
          <div className="admin-section-grid">
            {ADMIN_ACTION_SECTIONS.map((section) => {
              const sectionActions = ADMIN_ACTIONS.filter((entry) => entry.section === section.id);
              return (
                <section key={section.id} className="admin-action-section">
                  <h3 className="admin-section-title">{section.title}</h3>
                  <div className="admin-action-grid">
                    {sectionActions.map((action) => (
                      <button
                        key={action.id}
                        type="button"
                        className={`admin-action-tile ${activeAction === action.id ? "admin-action-tile-active" : ""} admin-action-tile-${action.tone}`}
                        onClick={() => selectAction(action.id)}
                        aria-pressed={activeAction === action.id}
                      >
                        <span className="admin-action-label">{action.label}</span>
                        <span className="admin-action-desc">{action.description}</span>
                      </button>
                    ))}
                  </div>
                </section>
              );
            })}
          </div>

          <div className="admin-form-pane">
          {activeAction && activeDefinition ? (
            <form className="admin-form" onSubmit={submit}>
              <h3 className="admin-form-title">{activeDefinition.label}</h3>

              {activeAction === "goto" && (
                <>
                  <label className="admin-field">
                    <span className="admin-field-label">Room ID or Player</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="zone:room or player name"
                      value={inputA}
                      onChange={(e) => updateInputA(e.target.value)}
                    />
                  </label>
                  {worldInfo.length > 0 && (
                    <TeleportBrowser
                      worldInfo={worldInfo}
                      whoPlayers={whoPlayers}
                      filter={gotoFilter}
                      onSetFilter={setGotoFilter}
                      onSelect={(target) => {
                        const command = buildAdminCommand("goto", target, "");
                        if (command) {
                          syncInputA(target);
                          executeAction("goto", command);
                        }
                      }}
                      label="Go"
                    />
                  )}
                </>
              )}

              {activeAction === "transfer" && (
                <>
                  <label className="admin-field">
                    <span className="admin-field-label">Player</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="Player name"
                      value={inputA}
                      onChange={(e) => updateInputA(e.target.value)}
                      autoFocus
                    />
                  </label>
                  <TargetBrowser
                    whoPlayers={whoPlayers}
                    roomMobs={[]}
                    filter={transferPlayerFilter}
                    onSetFilter={setTransferPlayerFilter}
                    onSelect={(target) => updateInputA(target)}
                    buttonLabel="Select"
                    emptyMessage="No online players match that filter."
                    currentPlayerName={currentPlayerName}
                    playerSectionTitle="Transferable Players"
                  />
                  <label className="admin-field">
                    <span className="admin-field-label">Destination</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="zone:room"
                      value={inputB}
                      onChange={(e) => updateInputB(e.target.value)}
                    />
                  </label>
                  {worldInfo.length > 0 && (
                    <TeleportBrowser
                      worldInfo={worldInfo}
                      whoPlayers={[]}
                      filter={transferRoomFilter}
                      onSetFilter={setTransferRoomFilter}
                      onSelect={(target) => updateInputB(target)}
                      label="Select"
                    />
                  )}
                </>
              )}

              {activeAction === "spawn" && (
                <>
                  <label className="admin-field">
                    <span className="admin-field-label">Mob template</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="mob-template-id"
                      value={inputA}
                      onChange={(e) => updateInputA(e.target.value)}
                      autoFocus
                    />
                  </label>
                  {mobTemplates.length > 0 && (
                    <MobTemplateBrowser
                      mobTemplates={mobTemplates}
                      filter={spawnFilter}
                      onSetFilter={setSpawnFilter}
                      onSelect={(templateId) => {
                        const command = buildAdminCommand("spawn", templateId, "");
                        if (command) {
                          syncInputA(templateId);
                          executeAction("spawn", command);
                        }
                      }}
                    />
                  )}
                </>
              )}

              {activeAction === "smite" && (
                <>
                  <label className="admin-field">
                    <span className="admin-field-label">Target</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="Player or mob name"
                      value={inputA}
                      onChange={(e) => updateInputA(e.target.value)}
                      autoFocus
                    />
                  </label>
                  <TargetBrowser
                    whoPlayers={whoPlayers}
                    roomMobs={roomMobs}
                    filter={smiteFilter}
                    onSetFilter={setSmiteFilter}
                    onSelect={(target) => {
                      syncInputA(target);
                      const command = buildAdminCommand("smite", target, "");
                      if (command) executeAction("smite", command);
                    }}
                    buttonLabel="Smite"
                    emptyMessage="No live player or room mob matches that filter."
                    currentPlayerName={currentPlayerName}
                    disabledPlayerNames={[currentPlayerName]}
                  />
                </>
              )}

              {activeAction === "kick" && (
                <>
                  <label className="admin-field">
                    <span className="admin-field-label">Player</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="Player name"
                      value={inputA}
                      onChange={(e) => updateInputA(e.target.value)}
                      autoFocus
                    />
                  </label>
                  <TargetBrowser
                    whoPlayers={whoPlayers}
                    roomMobs={[]}
                    filter={kickFilter}
                    onSetFilter={setKickFilter}
                    onSelect={(target) => {
                      syncInputA(target);
                      const command = buildAdminCommand("kick", target, "");
                      if (command) executeAction("kick", command);
                    }}
                    buttonLabel="Kick"
                    emptyMessage="No online player matches that filter."
                    currentPlayerName={currentPlayerName}
                    disabledPlayerNames={[currentPlayerName]}
                  />
                </>
              )}

              {activeAction === "setlevel" && (
                <>
                  <label className="admin-field">
                    <span className="admin-field-label">Player</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="Player name"
                      value={inputA}
                      onChange={(e) => updateInputA(e.target.value)}
                      autoFocus
                    />
                  </label>
                  <TargetBrowser
                    whoPlayers={whoPlayers}
                    roomMobs={[]}
                    filter={setLevelFilter}
                    onSetFilter={setSetLevelFilter}
                    onSelect={(target) => updateInputA(target)}
                    buttonLabel="Select"
                    emptyMessage="No online player matches that filter."
                    currentPlayerName={currentPlayerName}
                  />
                  <label className="admin-field">
                    <span className="admin-field-label">Level</span>
                    <input
                      type="number"
                      className="admin-input"
                      placeholder="1-100"
                      min={1}
                      max={100}
                      value={inputB}
                      onChange={(e) => updateInputB(e.target.value)}
                    />
                  </label>
                </>
              )}

              {activeAction === "setstaff" && (
                <>
                  <label className="admin-field">
                    <span className="admin-field-label">Player</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="Player name"
                      value={inputA}
                      onChange={(e) => updateInputA(e.target.value)}
                      autoFocus
                    />
                  </label>
                  <TargetBrowser
                    whoPlayers={whoPlayers}
                    roomMobs={[]}
                    filter={setStaffFilter}
                    onSetFilter={setSetStaffFilter}
                    onSelect={(target) => updateInputA(target)}
                    buttonLabel="Select"
                    emptyMessage="No online player matches that filter."
                    currentPlayerName={currentPlayerName}
                    disabledPlayerNames={[currentPlayerName]}
                  />
                  <label className="admin-field">
                    <span className="admin-field-label">Action</span>
                  </label>
                  <div className="admin-choice-row">
                    {(["grant", "revoke"] as const).map((choice) => (
                      <button
                        key={choice}
                        type="button"
                        className={`admin-choice-chip ${(choice === "grant" && inputB !== "revoke") || (choice === "revoke" && inputB === "revoke") ? "admin-choice-chip-active" : ""}`}
                        onClick={() => updateInputB(choice)}
                      >
                        {choice === "grant" ? "Grant Staff" : "Revoke Staff"}
                      </button>
                    ))}
                  </div>
                </>
              )}

              {activeAction === "setgold" && (
                <>
                  <label className="admin-field">
                    <span className="admin-field-label">Player</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="Player name"
                      value={inputA}
                      onChange={(e) => updateInputA(e.target.value)}
                      autoFocus
                    />
                  </label>
                  <TargetBrowser
                    whoPlayers={whoPlayers}
                    roomMobs={[]}
                    filter={setGoldFilter}
                    onSetFilter={setSetGoldFilter}
                    onSelect={(target) => updateInputA(target)}
                    buttonLabel="Select"
                    emptyMessage="No online player matches that filter."
                    currentPlayerName={currentPlayerName}
                  />
                  <label className="admin-field">
                    <span className="admin-field-label">Gold</span>
                    <input
                      type="number"
                      className="admin-input"
                      placeholder="0"
                      min={0}
                      value={inputB}
                      onChange={(e) => updateInputB(e.target.value)}
                    />
                  </label>
                </>
              )}

              {activeAction === "setrace" && (
                <>
                  <label className="admin-field">
                    <span className="admin-field-label">Player</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="Player name"
                      value={inputA}
                      onChange={(e) => updateInputA(e.target.value)}
                      autoFocus
                    />
                  </label>
                  <TargetBrowser
                    whoPlayers={whoPlayers}
                    roomMobs={[]}
                    filter={setRaceFilter}
                    onSetFilter={setSetRaceFilter}
                    onSelect={(target) => updateInputA(target)}
                    buttonLabel="Select"
                    emptyMessage="No online player matches that filter."
                    currentPlayerName={currentPlayerName}
                  />
                  <label className="admin-field">
                    <span className="admin-field-label">Race</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="e.g. HUMAN, DRAGON, KITTY"
                      value={inputB}
                      onChange={(e) => updateInputB(e.target.value)}
                    />
                  </label>
                </>
              )}

              {activeAction === "setclass" && (
                <>
                  <label className="admin-field">
                    <span className="admin-field-label">Player</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="Player name"
                      value={inputA}
                      onChange={(e) => updateInputA(e.target.value)}
                      autoFocus
                    />
                  </label>
                  <TargetBrowser
                    whoPlayers={whoPlayers}
                    roomMobs={[]}
                    filter={setClassFilter}
                    onSetFilter={setSetClassFilter}
                    onSelect={(target) => updateInputA(target)}
                    buttonLabel="Select"
                    emptyMessage="No online player matches that filter."
                    currentPlayerName={currentPlayerName}
                  />
                  <label className="admin-field">
                    <span className="admin-field-label">Class</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="e.g. WARRIOR, MAGE, CLERIC"
                      value={inputB}
                      onChange={(e) => updateInputB(e.target.value)}
                    />
                  </label>
                </>
              )}

              {activeAction === "setgender" && (
                <>
                  <label className="admin-field">
                    <span className="admin-field-label">Player</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="Player name"
                      value={inputA}
                      onChange={(e) => updateInputA(e.target.value)}
                      autoFocus
                    />
                  </label>
                  <TargetBrowser
                    whoPlayers={whoPlayers}
                    roomMobs={[]}
                    filter={setGenderFilter}
                    onSetFilter={setSetGenderFilter}
                    onSelect={(target) => updateInputA(target)}
                    buttonLabel="Select"
                    emptyMessage="No online player matches that filter."
                    currentPlayerName={currentPlayerName}
                  />
                  <label className="admin-field">
                    <span className="admin-field-label">Gender</span>
                  </label>
                  <div className="admin-choice-row">
                    {(["male", "female", "enby"] as const).map((choice) => (
                      <button
                        key={choice}
                        type="button"
                        className={`admin-choice-chip ${inputB.toLowerCase() === choice ? "admin-choice-chip-active" : ""}`}
                        onClick={() => updateInputB(choice)}
                      >
                        {choice === "enby" ? "Enby" : choice.charAt(0).toUpperCase() + choice.slice(1)}
                      </button>
                    ))}
                  </div>
                </>
              )}

              {activeAction === "setxp" && (
                <>
                  <label className="admin-field">
                    <span className="admin-field-label">Player</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="Player name"
                      value={inputA}
                      onChange={(e) => updateInputA(e.target.value)}
                      autoFocus
                    />
                  </label>
                  <TargetBrowser
                    whoPlayers={whoPlayers}
                    roomMobs={[]}
                    filter={setXpFilter}
                    onSetFilter={setSetXpFilter}
                    onSelect={(target) => updateInputA(target)}
                    buttonLabel="Select"
                    emptyMessage="No online player matches that filter."
                    currentPlayerName={currentPlayerName}
                  />
                  <label className="admin-field">
                    <span className="admin-field-label">XP Total</span>
                    <input
                      type="number"
                      className="admin-input"
                      placeholder="0"
                      min={0}
                      value={inputB}
                      onChange={(e) => updateInputB(e.target.value)}
                    />
                  </label>
                </>
              )}

              {activeAction === "heal" && (
                <>
                  <label className="admin-field">
                    <span className="admin-field-label">Player (blank = self)</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="Player name or leave blank for self"
                      value={inputA}
                      onChange={(e) => updateInputA(e.target.value)}
                      autoFocus
                    />
                  </label>
                  <TargetBrowser
                    whoPlayers={whoPlayers}
                    roomMobs={[]}
                    filter={healFilter}
                    onSetFilter={setHealFilter}
                    onSelect={(target) => {
                      syncInputA(target);
                      const command = buildAdminCommand("heal", target, "");
                      if (command) executeAction("heal", command);
                    }}
                    buttonLabel="Heal"
                    emptyMessage="No online player matches that filter."
                    currentPlayerName={currentPlayerName}
                  />
                </>
              )}

              {activeAction === "pinfo" && (
                <>
                  <label className="admin-field">
                    <span className="admin-field-label">Player</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="Player name"
                      value={inputA}
                      onChange={(e) => updateInputA(e.target.value)}
                      autoFocus
                    />
                  </label>
                  <TargetBrowser
                    whoPlayers={whoPlayers}
                    roomMobs={[]}
                    filter={pinfoFilter}
                    onSetFilter={setPinfoFilter}
                    onSelect={(target) => {
                      syncInputA(target);
                      const command = buildAdminCommand("pinfo", target, "");
                      if (command) executeAction("pinfo", command);
                    }}
                    buttonLabel="Inspect"
                    emptyMessage="No online player matches that filter."
                    currentPlayerName={currentPlayerName}
                  />
                </>
              )}

              {activeAction === "dispel" && (
                <>
                  <label className="admin-field">
                    <span className="admin-field-label">Target</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="Player or mob name"
                      value={inputA}
                      onChange={(e) => updateInputA(e.target.value)}
                      autoFocus
                    />
                  </label>
                  <TargetBrowser
                    whoPlayers={whoPlayers}
                    roomMobs={roomMobs}
                    filter={dispelFilter}
                    onSetFilter={setDispelFilter}
                    onSelect={(target) => {
                      syncInputA(target);
                      const command = buildAdminCommand("dispel", target, "");
                      if (command) executeAction("dispel", command);
                    }}
                    buttonLabel="Dispel"
                    emptyMessage="No live player or room mob matches that filter."
                    currentPlayerName={currentPlayerName}
                  />
                </>
              )}

              {activeAction === "reload" && (
                <>
                  <label className="admin-field">
                    <span className="admin-field-label">Scope</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="world, abilities, effects, or all"
                      value={inputA}
                      onChange={(e) => updateInputA(e.target.value)}
                      autoFocus
                    />
                  </label>
                  <div className="admin-choice-row">
                    {ADMIN_RELOAD_SCOPES.map((scope) => {
                      const isActive =
                        (scope === "all" && inputA.trim().length === 0) || inputA.trim() === scope;
                      return (
                        <button
                          key={scope}
                          type="button"
                          className={`admin-choice-chip ${isActive ? "admin-choice-chip-active" : ""}`}
                          onClick={() => updateInputA(scope === "all" ? "" : scope)}
                        >
                          {scope === "all" ? "Everything" : scope}
                        </button>
                      );
                    })}
                  </div>
                </>
              )}

              {activeAction === "broadcast" && (
                <label className="admin-field">
                  <span className="admin-field-label">Announcement</span>
                  <textarea
                    className="admin-input admin-textarea"
                    placeholder="Server-wide announcement..."
                    value={inputA}
                    rows={3}
                    onChange={(e) => updateInputA(e.target.value)}
                    autoFocus
                  />
                </label>
              )}

              {activeAction === "possess" && (
                <>
                  <label className="admin-field">
                    <span className="admin-field-label">Mob in current room</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="Mob keyword or name"
                      value={inputA}
                      onChange={(e) => updateInputA(e.target.value)}
                      autoFocus
                    />
                  </label>
                  <TargetBrowser
                    whoPlayers={[]}
                    roomMobs={roomMobs}
                    filter={possessFilter}
                    onSetFilter={setPossessFilter}
                    onSelect={(target) => {
                      syncInputA(target);
                      const command = buildAdminCommand("possess", target, "");
                      if (command) executeAction("possess", command);
                    }}
                    buttonLabel="Possess"
                    emptyMessage="No mobs are currently available in this room."
                    currentPlayerName={currentPlayerName}
                    mobSectionTitle="Possessable Room Mobs"
                  />
                </>
              )}

              {activeAction === "shutdown" && (
                <div className="admin-shutdown-warning">
                  {confirmArmed
                    ? "Shutdown is armed. Submit again to disconnect all players and stop the server."
                    : "This shuts down the live server for every connected player."}
                </div>
              )}

              <div className="admin-submit-row">
                <button
                  type="submit"
                  disabled={pendingCommand === null}
                  className={`admin-submit ${requiresAdminConfirmation(activeAction) ? "admin-submit-danger" : ""} ${confirmArmed ? "admin-submit-confirm" : ""}`}
                >
                  {confirmArmed
                    ? `Confirm ${activeDefinition.label}`
                    : `Run ${activeDefinition.label}`}
                </button>
              </div>
            </form>
          ) : (
            <p className="admin-form-hint">Select an action from the left to configure and run it.</p>
          )}
          </div>
          </div>
          </div>
        </div>
      </section>
    </div>
  );
}
