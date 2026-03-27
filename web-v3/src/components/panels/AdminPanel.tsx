import { useMemo, useState } from "react";
import type { FormEvent } from "react";
import type { StaffMobZone, StaffWorldZone, WhoPlayer } from "../../types";

interface AdminPanelProps {
  onCommand: (command: string) => void;
  onClose: () => void;
  worldInfo: StaffWorldZone[];
  mobTemplates: StaffMobZone[];
  whoPlayers: WhoPlayer[];
}

type AdminAction = "goto" | "transfer" | "spawn" | "smite" | "kick" | "setlevel" | "dispel" | "reload" | "shutdown";

const ACTIONS: Array<{ id: AdminAction; label: string; description: string }> = [
  { id: "goto", label: "Goto", description: "Teleport to a room or player" },
  { id: "transfer", label: "Transfer", description: "Move a player to a room" },
  { id: "spawn", label: "Spawn", description: "Spawn a mob" },
  { id: "smite", label: "Smite", description: "Kill a target" },
  { id: "kick", label: "Kick", description: "Disconnect a player" },
  { id: "setlevel", label: "Set Level", description: "Set a player's level" },
  { id: "dispel", label: "Dispel", description: "Remove all effects from a target" },
  { id: "reload", label: "Reload", description: "Reload world data" },
  { id: "shutdown", label: "Shutdown", description: "Shut down the server" },
];

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

      {/* Online players */}
      {filteredPlayers.length > 0 && (
        <div className="teleport-section">
          <h4 className="teleport-section-title">Online Players</h4>
          <ul className="teleport-list">
            {filteredPlayers.map((p) => (
              <li key={p.name} className="teleport-item teleport-item-player">
                <span className="teleport-item-name">{p.name}</span>
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

      {/* Zones and rooms */}
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

export function AdminPanel({ onCommand, onClose, worldInfo, mobTemplates, whoPlayers }: AdminPanelProps) {
  const [activeAction, setActiveAction] = useState<AdminAction | null>(null);
  const [inputA, setInputA] = useState("");
  const [inputB, setInputB] = useState("");
  const [filter, setFilter] = useState("");
  const [transferFilter, setTransferFilter] = useState("");
  const [spawnFilter, setSpawnFilter] = useState("");
  const [showShutdownConfirm, setShowShutdownConfirm] = useState(false);

  const resetForm = () => {
    setInputA("");
    setInputB("");
    setFilter("");
    setTransferFilter("");
    setSpawnFilter("");
    setShowShutdownConfirm(false);
  };

  const selectAction = (action: AdminAction) => {
    setActiveAction(activeAction === action ? null : action);
    resetForm();
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!activeAction) return;

    switch (activeAction) {
      case "goto": {
        const target = inputA.trim();
        if (!target) return;
        onCommand(`goto ${target}`);
        resetForm();
        break;
      }
      case "transfer": {
        const player = inputA.trim();
        const room = inputB.trim();
        if (!player || !room) return;
        onCommand(`transfer ${player} ${room}`);
        resetForm();
        break;
      }
      case "spawn": {
        const mob = inputA.trim();
        if (!mob) return;
        onCommand(`spawn ${mob}`);
        resetForm();
        break;
      }
      case "smite": {
        const target = inputA.trim();
        if (!target) return;
        onCommand(`smite ${target}`);
        resetForm();
        break;
      }
      case "kick": {
        const player = inputA.trim();
        if (!player) return;
        onCommand(`kick ${player}`);
        resetForm();
        break;
      }
      case "setlevel": {
        const player = inputA.trim();
        const level = inputB.trim();
        if (!player || !level) return;
        onCommand(`setlevel ${player} ${level}`);
        resetForm();
        break;
      }
      case "dispel": {
        const target = inputA.trim();
        if (!target) return;
        onCommand(`dispel ${target}`);
        resetForm();
        break;
      }
      case "reload": {
        const scope = inputA.trim();
        onCommand(scope ? `reload ${scope}` : "reload");
        resetForm();
        break;
      }
      case "shutdown": {
        if (!showShutdownConfirm) {
          setShowShutdownConfirm(true);
          return;
        }
        onCommand("shutdown");
        resetForm();
        break;
      }
    }
  };

  return (
    <div className="popout-backdrop" onClick={onClose}>
      <section
        className="popout-dialog admin-dialog"
        role="dialog"
        aria-modal="true"
        aria-label="Staff Administration"
        onClick={(event) => event.stopPropagation()}
      >
        <header className="popout-header">
          <h2>Staff Admin</h2>
          <button type="button" className="soft-button popout-close" onClick={onClose}>
            Close
          </button>
        </header>

        <div className="popout-content admin-content">
          <div className="admin-action-grid">
            {ACTIONS.map((action) => (
              <button
                key={action.id}
                type="button"
                className={`admin-action-tile ${activeAction === action.id ? "admin-action-tile-active" : ""}`}
                onClick={() => selectAction(action.id)}
                aria-pressed={activeAction === action.id}
              >
                <span className="admin-action-label">{action.label}</span>
                <span className="admin-action-desc">{action.description}</span>
              </button>
            ))}
          </div>

          {activeAction && (
            <form className="admin-form" onSubmit={submit}>
              {activeAction === "goto" && (
                <>
                  <label className="admin-field">
                    <span className="admin-field-label">Room ID or Player</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="zone:room or player name"
                      value={inputA}
                      onChange={(e) => setInputA(e.target.value)}
                    />
                  </label>
                  {worldInfo.length > 0 && (
                    <TeleportBrowser
                      worldInfo={worldInfo}
                      whoPlayers={whoPlayers}
                      filter={filter}
                      onSetFilter={setFilter}
                      onSelect={(target) => {
                        onCommand(`goto ${target}`);
                        resetForm();
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
                      onChange={(e) => setInputA(e.target.value)}
                      autoFocus
                    />
                  </label>
                  <label className="admin-field">
                    <span className="admin-field-label">Destination</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="zone:room"
                      value={inputB}
                      onChange={(e) => setInputB(e.target.value)}
                    />
                  </label>
                  {worldInfo.length > 0 && (
                    <TeleportBrowser
                      worldInfo={worldInfo}
                      whoPlayers={[]}
                      filter={transferFilter}
                      onSetFilter={setTransferFilter}
                      onSelect={(target) => {
                        setInputB(target);
                      }}
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
                      onChange={(e) => setInputA(e.target.value)}
                      autoFocus
                    />
                  </label>
                  {mobTemplates.length > 0 && (
                    <MobTemplateBrowser
                      mobTemplates={mobTemplates}
                      filter={spawnFilter}
                      onSetFilter={setSpawnFilter}
                      onSelect={(templateId) => {
                        onCommand(`spawn ${templateId}`);
                        resetForm();
                      }}
                    />
                  )}
                </>
              )}

              {activeAction === "smite" && (
                <label className="admin-field">
                  <span className="admin-field-label">Target</span>
                  <input
                    type="text"
                    className="admin-input"
                    placeholder="Player or mob name"
                    value={inputA}
                    onChange={(e) => setInputA(e.target.value)}
                    autoFocus
                  />
                </label>
              )}

              {activeAction === "kick" && (
                <label className="admin-field">
                  <span className="admin-field-label">Player</span>
                  <input
                    type="text"
                    className="admin-input"
                    placeholder="Player name"
                    value={inputA}
                    onChange={(e) => setInputA(e.target.value)}
                    autoFocus
                  />
                </label>
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
                      onChange={(e) => setInputA(e.target.value)}
                      autoFocus
                    />
                  </label>
                  <label className="admin-field">
                    <span className="admin-field-label">Level</span>
                    <input
                      type="number"
                      className="admin-input"
                      placeholder="1-100"
                      min={1}
                      max={100}
                      value={inputB}
                      onChange={(e) => setInputB(e.target.value)}
                    />
                  </label>
                </>
              )}

              {activeAction === "dispel" && (
                <label className="admin-field">
                  <span className="admin-field-label">Target</span>
                  <input
                    type="text"
                    className="admin-input"
                    placeholder="Player or mob name"
                    value={inputA}
                    onChange={(e) => setInputA(e.target.value)}
                    autoFocus
                  />
                </label>
              )}

              {activeAction === "reload" && (
                <label className="admin-field">
                  <span className="admin-field-label">Scope (optional)</span>
                  <input
                    type="text"
                    className="admin-input"
                    placeholder="world, abilities, effects, or blank for all"
                    value={inputA}
                    onChange={(e) => setInputA(e.target.value)}
                    autoFocus
                  />
                </label>
              )}

              {activeAction === "shutdown" && (
                <div className="admin-shutdown-warning">
                  {showShutdownConfirm
                    ? "Click again to confirm server shutdown."
                    : "This will shut down the server for all players."}
                </div>
              )}

              <button
                type="submit"
                className={`admin-submit ${activeAction === "shutdown" ? "admin-submit-danger" : ""} ${showShutdownConfirm ? "admin-submit-confirm" : ""}`}
              >
                {activeAction === "shutdown"
                  ? showShutdownConfirm
                    ? "Confirm Shutdown"
                    : "Shutdown"
                  : `Run ${ACTIONS.find((a) => a.id === activeAction)?.label ?? ""}`}
              </button>
            </form>
          )}
        </div>
      </section>
    </div>
  );
}
