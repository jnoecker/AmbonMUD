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
  const activeCats = CATEGORIES.filter((c) => {
    const data = leaderboard[c.key];
    return data && data.entries.length > 0;
  });

  return (
    <div className="leaderboard-panel">
      <div className="panel-header">
        <span className="panel-title">Leaderboards</span>
      </div>

      {activeCats.length === 0 ? (
        <div className="leaderboard-empty">
          <p>No leaderboard data yet.</p>
          <p>Use the leaderboard command to load rankings.</p>
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
        </div>
      ) : (
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

          {activeCats.map((c) => {
            const data = leaderboard[c.key];
            return (
              <div key={c.key} className="leaderboard-category">
                <div className="leaderboard-cat-header">{data.label}</div>
                <table className="leaderboard-table">
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
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
