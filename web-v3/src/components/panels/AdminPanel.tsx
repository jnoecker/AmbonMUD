import { useState } from "react";
import type { FormEvent } from "react";

interface AdminPanelProps {
  onCommand: (command: string) => void;
  onClose: () => void;
}

type AdminAction = "goto" | "transfer" | "spawn" | "smite" | "kick" | "shutdown";

const ACTIONS: Array<{ id: AdminAction; label: string; description: string }> = [
  { id: "goto", label: "Goto", description: "Teleport to a room" },
  { id: "transfer", label: "Transfer", description: "Move a player to your location" },
  { id: "spawn", label: "Spawn", description: "Spawn a mob" },
  { id: "smite", label: "Smite", description: "Kill a target" },
  { id: "kick", label: "Kick", description: "Disconnect a player" },
  { id: "shutdown", label: "Shutdown", description: "Shut down the server" },
];

export function AdminPanel({ onCommand, onClose }: AdminPanelProps) {
  const [activeAction, setActiveAction] = useState<AdminAction | null>(null);
  const [inputA, setInputA] = useState("");
  const [inputB, setInputB] = useState("");
  const [showShutdownConfirm, setShowShutdownConfirm] = useState(false);

  const resetForm = () => {
    setInputA("");
    setInputB("");
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
                <label className="admin-field">
                  <span className="admin-field-label">Room ID</span>
                  <input
                    type="text"
                    className="admin-input"
                    placeholder="zone:room"
                    value={inputA}
                    onChange={(e) => setInputA(e.target.value)}
                    autoFocus
                  />
                </label>
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
                    <span className="admin-field-label">Room ID</span>
                    <input
                      type="text"
                      className="admin-input"
                      placeholder="zone:room"
                      value={inputB}
                      onChange={(e) => setInputB(e.target.value)}
                    />
                  </label>
                </>
              )}

              {activeAction === "spawn" && (
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
