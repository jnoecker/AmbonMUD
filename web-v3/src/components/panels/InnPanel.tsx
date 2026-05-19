import type { RecallState } from "../../types";

interface InnPanelProps {
  roomTitle: string;
  recall: RecallState | null;
  onSetRecall: () => void;
  onClose: () => void;
}

export function InnPanel({ roomTitle, recall, onSetRecall, onClose }: InnPanelProps) {
  const displayInn = roomTitle && roomTitle !== "-" ? roomTitle : "this inn";
  const currentRecall = recall?.roomTitle ?? "Not yet set";

  return (
    <div className="inn-panel">
      <div className="panel-header">
        <span className="panel-title">Inn</span>
      </div>

      <div className="inn-panel-content">
        <p className="inn-panel-intro">
          You are at <strong>{displayInn}</strong>. Rest here to make this inn your recall point.
        </p>

        <div className="inn-panel-recall">
          <div className="inn-panel-recall-label">Current recall point</div>
          <div className="inn-panel-recall-value">{currentRecall}</div>
        </div>

        <div className="inn-panel-actions">
          <button type="button" className="inn-panel-primary" onClick={onSetRecall}>
            Rest &amp; Set Recall Here
          </button>
          <button type="button" className="inn-panel-secondary" onClick={onClose}>
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}
