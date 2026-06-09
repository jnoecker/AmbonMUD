import { useMemo, useState } from "react";
import type { HousingInfo, HousingTemplate, RoomState, UiFeedbackEntry } from "../../types";

interface HousingPanelProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  housing: HousingInfo | null;
  templates: HousingTemplate[];
  room: RoomState;
  uiFeedbackFeed: UiFeedbackEntry[];
  gold: number;
  onSendCommand: (cmd: string) => void;
}

const DIRECTIONS: Array<{ key: string; label: string }> = [
  { key: "n", label: "N" }, { key: "s", label: "S" }, { key: "e", label: "E" },
  { key: "w", label: "W" }, { key: "u", label: "Up" }, { key: "d", label: "Down" },
];

/**
 * Housing Broker — reskinned onto the painted `housing_bg` frame. Your owned
 * rooms inset into the left "Your Estate" card, the room templates into the
 * central "Browse Room Templates" card, and a single Buy bar at the bottom.
 * The entry buys via `house buy`; rooms need a direction, so selecting a room
 * and buying reveals a compact direction picker (`house expand <id> <dir>`).
 */
export function HousingPanel({
  connected,
  hasCharacterProfile,
  housing,
  templates,
  room,
  uiFeedbackFeed,
  gold,
  onSendCommand,
}: HousingPanelProps) {
  const [selected, setSelected] = useState<string | null>(null);
  const [choosingDir, setChoosingDir] = useState(false);

  const activeFeedback = useMemo(
    () => [...uiFeedbackFeed].reverse().find((e) => e.scope === "housing") ?? null,
    [uiFeedbackFeed],
  );

  if (!connected) return <div className="housing-broker housing-broker-empty"><p className="hb-empty">Connect to view housing.</p></div>;
  if (!hasCharacterProfile) return <div className="housing-broker housing-broker-empty"><p className="hb-empty">Log in to view housing.</p></div>;

  const rooms = housing?.rooms ?? [];
  const hasHouse = housing?.hasHouse ?? false;
  const selectedTemplate = templates.find((t) => t.id === selected) ?? null;
  const isEntryBuy = !!selectedTemplate && (selectedTemplate.isEntry || !hasHouse);
  const affordable = !!selectedTemplate && gold >= selectedTemplate.cost;
  const canBuy = !!selectedTemplate && !selectedTemplate.owned && affordable;

  const buyLabel = !selectedTemplate
    ? "Select a Room"
    : selectedTemplate.owned
      ? "Already Owned"
      : selectedTemplate.isEntry
        ? "Purchase Entry"
        : "Buy Selected Room";

  const onBuy = () => {
    if (!selectedTemplate || !canBuy) return;
    if (isEntryBuy) {
      onSendCommand("house buy");
    } else {
      setChoosingDir(true);
    }
  };

  const expand = (dir: string) => {
    if (selectedTemplate) onSendCommand(`house expand ${selectedTemplate.id} ${dir}`);
    setChoosingDir(false);
  };

  return (
    <div className="housing-broker">
      {/* ── Your Estate (left card) ──────────────────────────── */}
      <div className="hb-estate">
        {rooms.length === 0 ? (
          <p className="hb-empty">No rooms yet. Secure a Carriage House to begin your legacy.</p>
        ) : (
          <ul className="hb-owned-list">
            {rooms.map((r, i) => (
              <li key={`${r.templateId}-${i}`} className="hb-owned">
                <span className="hb-owned-name">{r.title}</span>
                <span className="hb-owned-type">{r.templateId}</span>
              </li>
            ))}
          </ul>
        )}
        <div className="hb-owned-total">Total Owned Rooms: {rooms.length}</div>
      </div>

      {/* ── Browse Room Templates (center card) ──────────────── */}
      <div className="hb-templates">
        {templates.length === 0 ? (
          <p className="hb-empty">No templates available here.</p>
        ) : (
          <ul className="hb-template-list">
            {templates.map((t) => (
              <li key={t.id}>
                <button
                  type="button"
                  className={`hb-template${selected === t.id ? " is-selected" : ""}${t.owned ? " is-owned" : ""}`}
                  onClick={() => { setSelected(t.id); setChoosingDir(false); }}
                  aria-pressed={selected === t.id}
                >
                  <span className="hb-template-main">
                    <span className="hb-template-name">
                      {t.title}
                      {t.owned && <span className="hb-tag">Owned</span>}
                      {t.isEntry && <span className="hb-tag hb-tag-entry">Entry</span>}
                    </span>
                    {t.description && <span className="hb-template-desc">{t.description}</span>}
                  </span>
                  <span className="hb-template-cost">
                    <span className="hb-coin" aria-hidden="true">◉</span>
                    {t.cost > 0 ? t.cost.toLocaleString() : "Free"}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      {/* ── Buy bar (bottom) ─────────────────────────────────── */}
      <div className="hb-buybar">
        {activeFeedback && (
          <span className={`hb-feedback hb-feedback-${activeFeedback.type}`}>{activeFeedback.message}</span>
        )}
        {choosingDir ? (
          <div className="hb-dir-picker">
            <span className="hb-dir-label">Attach where?</span>
            {DIRECTIONS.map((d) => (
              <button key={d.key} type="button" className="hb-dir-btn" onClick={() => expand(d.key)}>{d.label}</button>
            ))}
            <button type="button" className="hb-dir-cancel" onClick={() => setChoosingDir(false)}>✕</button>
          </div>
        ) : (
          <button type="button" className="hb-buy-btn" disabled={!canBuy} onClick={onBuy}>{buyLabel}</button>
        )}
        <span className="hb-funds">
          <span className="hb-coin" aria-hidden="true">◉</span>
          Funds: <strong>{gold.toLocaleString()}</strong>
        </span>
      </div>
      {/* room state is read above; reference kept so visiting context stays available */}
      {room.housing === true && room.housingOwner && room.housingOwner !== housing?.ownerName && (
        <div className="hb-visiting">Visiting {room.housingOwner}&apos;s estate</div>
      )}
    </div>
  );
}
