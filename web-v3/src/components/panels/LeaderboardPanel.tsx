import { useEffect, useRef } from "react";
import type { LeaderboardData } from "../../types";

const CATEGORIES = [
  { key: "level", label: "Top Level" },
  { key: "achievements", label: "Achievements" },
  { key: "crafting", label: "Crafting" },
  { key: "dungeons", label: "Dungeons" },
  { key: "kills", label: "Kills" },
];

interface Props {
  leaderboard: Record<string, LeaderboardData>;
  onCommand: (cmd: string) => void;
}

export function LeaderboardPanel({ leaderboard, onCommand }: Props) {
  const autoLoaded = useRef(false);

  useEffect(() => {
    if (!autoLoaded.current) {
      autoLoaded.current = true;
      for (const c of CATEGORIES) {
        onCommand(`leaderboard ${c.key}`);
      }
    }
  }, [onCommand]);

  const receivedCats = CATEGORIES.filter((c) => leaderboard[c.key] !== undefined);
  const awaitingResponse = receivedCats.length === 0;

  return (
    <div className="leaderboard-panel">
      <div className="panel-header">
        <span className="panel-title">Leaderboards</span>
      </div>

      <div className="leaderboard-content">
        <div className="leaderboard-cat-buttons">
          {CATEGORIES.map((c) => (
            <button
              key={c.key}
              className="leaderboard-cat-btn"
              onClick={() => onCommand(`leaderboard ${c.key}`)}
            >
              {c.label}
            </button>
          ))}
        </div>

        {awaitingResponse && (
          <div className="leaderboard-empty">
            <p>Loading rankings&hellip;</p>
          </div>
        )}

        {receivedCats.map((c) => {
          const data = leaderboard[c.key];
          return (
            <div key={c.key} className="leaderboard-category">
              <div className="leaderboard-cat-header">{data.label}</div>
              {data.entries.length === 0 ? (
                <p className="leaderboard-empty-note">No entries yet — be the first to rank!</p>
              ) : (
                <table className="leaderboard-table">
                  <caption className="sr-only">
                    {data.label} rankings — columns: rank, player, score
                  </caption>
                  <tbody>
                    {data.entries.map((e) => (
                      <tr key={e.rank} className="leaderboard-row">
                        <td className="leaderboard-rank">#{e.rank}</td>
                        <td className="leaderboard-name">{e.name}</td>
                        <td className="leaderboard-score">
                          {e.score.toLocaleString()} {data.scoreLabel}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
