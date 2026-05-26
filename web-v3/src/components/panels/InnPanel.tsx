import { useEffect, useState } from "react";
import type { RecallState } from "../../types";

interface InnPanelProps {
  roomTitle: string;
  recall: RecallState | null;
  /** Optional art: inn_key (the hanging key) and inn_bg (card background). */
  serverAssets: Record<string, string>;
  onSetRecall: () => void;
  onClose: () => void;
}

/**
 * Cozy inn modal: a brass key hanging on a hook. Take the key to set this inn
 * as your recall point (with a small confirm step). No close button — click
 * away (or Escape) to leave.
 */
export function InnPanel({ roomTitle, recall, serverAssets, onSetRecall, onClose }: InnPanelProps) {
  const [confirming, setConfirming] = useState(false);
  const displayInn = roomTitle && roomTitle !== "-" ? roomTitle : "this inn";
  const currentRecall = recall?.roomTitle ?? "Not yet set";
  const keyArt = serverAssets["inn_key"];
  const bg = serverAssets["inn_bg"];

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div
      className="inn-backdrop"
      role="dialog"
      aria-modal="true"
      aria-label={`Rest at ${displayInn}`}
      onClick={onClose}
    >
      <div
        className={`inn-card${bg ? " inn-card-skinned" : ""}`}
        style={bg ? { ["--inn-bg" as string]: `url("${bg}")` } : undefined}
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="inn-title">{displayInn}</h2>

        <div className="inn-hook-area">
          <span className="inn-hook" aria-hidden="true" />
          <button
            type="button"
            className={`inn-key${confirming ? " inn-key-held" : ""}`}
            onClick={() => setConfirming(true)}
            aria-label={`Take the key to set your recall point at ${displayInn}`}
            title="Rest &amp; set recall here"
          >
            {keyArt ? (
              <img className="inn-key-img" src={keyArt} alt="" aria-hidden="true" />
            ) : (
              <svg className="inn-key-svg" viewBox="0 0 44 118" aria-hidden="true">
                <circle cx="22" cy="22" r="13" fill="none" stroke="currentColor" strokeWidth="5" />
                <rect x="19" y="34" width="6" height="64" rx="2" fill="currentColor" />
                <rect x="25" y="74" width="12" height="6" rx="1.5" fill="currentColor" />
                <rect x="25" y="86" width="15" height="6" rx="1.5" fill="currentColor" />
              </svg>
            )}
          </button>
        </div>

        {confirming ? (
          <div className="inn-confirm">
            <p className="inn-confirm-text">
              Hang your key here and make <strong>{displayInn}</strong> your recall point?
            </p>
            <button type="button" className="inn-confirm-btn" onClick={onSetRecall}>
              Rest Here
            </button>
            <p className="inn-hint">Click anywhere to cancel.</p>
          </div>
        ) : (
          <>
            <p className="inn-sub">Take the key to rest here and set your recall point.</p>
            <div className="inn-recall">
              <span className="inn-recall-label">Current recall point</span>
              <span className="inn-recall-value">{currentRecall}</span>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
