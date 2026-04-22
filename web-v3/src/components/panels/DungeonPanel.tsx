import { useMemo, useState } from "react";
import type { DungeonCatalogEntry, DungeonInfo, UiFeedbackEntry } from "../../types";

interface DungeonPanelProps {
  dungeonInfo: DungeonInfo | null;
  dungeonCatalog: DungeonCatalogEntry[];
  uiFeedbackFeed: UiFeedbackEntry[];
  onCommand: (command: string) => void;
}

export function DungeonPanel({ dungeonInfo, dungeonCatalog, uiFeedbackFeed, onCommand }: DungeonPanelProps) {
  const [selectedDifficulty, setSelectedDifficulty] = useState("normal");

  const activeFeedback = useMemo(
    () => [...uiFeedbackFeed].reverse().find((e) => e.scope === "dungeon") ?? null,
    [uiFeedbackFeed],
  );

  const availableDungeons = useMemo(
    () => [...dungeonCatalog].sort((a, b) => a.minLevel - b.minLevel || a.name.localeCompare(b.name)),
    [dungeonCatalog],
  );

  return (
    <div className="dungeon-panel">
      <div className="panel-header">
        <span className="panel-title">Dungeons</span>
      </div>

      {activeFeedback && (
        <p className={`systems-local-message systems-local-message-${activeFeedback.type}`}>
          {activeFeedback.message}
        </p>
      )}

      {dungeonInfo?.active ? (
        <article className="systems-card systems-dungeon-active">
          <div className="systems-card-header">
            <div>
              <p className="systems-card-label">Active Run</p>
              <h4>{dungeonInfo.name ?? "Dungeon in progress"}</h4>
            </div>
            {dungeonInfo.completed && <span className="systems-pill systems-pill-success">Completed</span>}
          </div>
          <dl className="systems-stat-grid">
            <div><dt>Difficulty</dt><dd>{dungeonInfo.difficulty ?? "Unknown"}</dd></div>
            <div><dt>Party</dt><dd>{dungeonInfo.memberCount ?? 1}</dd></div>
            <div><dt>Rooms</dt><dd>{dungeonInfo.totalRooms ?? "-"}</dd></div>
            <div><dt>Status</dt><dd>{dungeonInfo.completed ? "Boss defeated" : "In progress"}</dd></div>
          </dl>
          <div className="systems-action-row">
            {!dungeonInfo.completed && (
              <button type="button" className="systems-primary-btn" onClick={() => onCommand("dungeon enter resume")}>
                Resume Run
              </button>
            )}
            <button
              type="button"
              className={dungeonInfo.completed ? "systems-primary-btn" : "systems-secondary-btn"}
              onClick={() => onCommand("dungeon leave")}
            >
              {dungeonInfo.completed ? "Return to Portal" : "Leave Dungeon"}
            </button>
          </div>
        </article>
      ) : (
        <div className="systems-card-list">
          {availableDungeons.length === 0 && (
            <article className="systems-card">
              <p className="systems-card-copy">No dungeon catalog is available from the server right now.</p>
            </article>
          )}
          {availableDungeons.map((dungeon) => {
            const availableDifficulties = dungeon.difficulties.length > 0
              ? dungeon.difficulties
              : [{ id: "normal", label: "Normal", summary: "Standard dungeon pacing and rewards." }];
            const chosenDifficulty = availableDifficulties.some((d) => d.id === selectedDifficulty)
              ? selectedDifficulty
              : availableDifficulties[0].id;
            return (
              <article key={dungeon.id} className="systems-card">
                <div className="systems-card-header">
                  <div>
                    <p className="systems-card-label">Available Dungeon</p>
                    <h4>{dungeon.name}</h4>
                  </div>
                  <span className="systems-pill">Min Lv {dungeon.minLevel}</span>
                </div>
                <p className="systems-card-copy">{dungeon.description}</p>
                {dungeon.portalHint && <p className="systems-detail-line">{dungeon.portalHint}</p>}
                <div className="systems-choice-list">
                  {availableDifficulties.map((difficulty) => (
                    <button
                      key={difficulty.id}
                      type="button"
                      className={`systems-choice-card ${chosenDifficulty === difficulty.id ? "systems-choice-card-active" : ""}`}
                      onClick={() => setSelectedDifficulty(difficulty.id)}
                    >
                      <span className="systems-choice-title">{difficulty.label}</span>
                      <span className="systems-choice-copy">{difficulty.summary}</span>
                    </button>
                  ))}
                </div>
                <div className="systems-action-row">
                  <button
                    type="button"
                    className="systems-primary-btn"
                    onClick={() => onCommand(`dungeon enter ${dungeon.id} ${chosenDifficulty}`)}
                  >
                    Enter Dungeon
                  </button>
                </div>
              </article>
            );
          })}
        </div>
      )}
    </div>
  );
}
