import { useState } from "react";
import type { HousingInfo, RoomState } from "../../types";

interface HousingPanelProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  housing: HousingInfo | null;
  room: RoomState;
  onSendCommand: (cmd: string) => void;
}

export function HousingPanel({
  connected,
  hasCharacterProfile,
  housing,
  room,
  onSendCommand,
}: HousingPanelProps) {
  const [editField, setEditField] = useState<"title" | "desc" | null>(null);
  const [editValue, setEditValue] = useState("");

  if (!connected) return <p className="empty-note">Connect to view housing.</p>;
  if (!hasCharacterProfile) return <p className="empty-note">Log in to view housing.</p>;

  const inOwnHouse = room.housing === true && room.housingOwner === housing?.ownerName;
  const inAnyHouse = room.housing === true;

  if (!housing?.hasHouse) {
    return (
      <div className="housing-panel">
        {inAnyHouse && (
          <div className="housing-visiting-banner">
            Visiting {room.housingOwner ?? "someone"}&apos;s house
          </div>
        )}
        <div className="housing-empty-state">
          <span className="housing-empty-icon">{"\u{1F3E0}"}</span>
          <p className="empty-note">You don't own a house yet.</p>
          <p className="housing-hint">Visit a housing broker to purchase one.</p>
        </div>
      </div>
    );
  }

  const startEdit = (field: "title" | "desc") => {
    setEditField(field);
    setEditValue("");
  };

  const submitEdit = () => {
    if (!editField || !editValue.trim()) return;
    if (editField === "title") {
      onSendCommand(`house describe title ${editValue.trim()}`);
    } else {
      onSendCommand(`house describe desc ${editValue.trim()}`);
    }
    setEditField(null);
    setEditValue("");
  };

  const cancelEdit = () => {
    setEditField(null);
    setEditValue("");
  };

  return (
    <div className="housing-panel">
      <div className="housing-owner-header">
        <span className="housing-owner-name">{housing.ownerName}&apos;s House</span>
        <span className="housing-room-count">{housing.rooms.length} room{housing.rooms.length !== 1 ? "s" : ""}</span>
      </div>

      {inOwnHouse && (
        <div className="housing-actions">
          <button type="button" className="housing-action-btn" onClick={() => onSendCommand("house guests")}>
            Guests
          </button>
          <button type="button" className="housing-action-btn" onClick={() => onSendCommand("house list")}>
            Templates
          </button>
        </div>
      )}

      {inAnyHouse && !inOwnHouse && (
        <div className="housing-visiting-banner">
          Visiting {room.housingOwner ?? "someone"}&apos;s house
        </div>
      )}

      <ul className="housing-room-list">
        {housing.rooms.map((r, i) => (
          <li key={`${r.templateId}-${i}`} className="housing-room-item">
            <div className="housing-room-header">
              <span className="housing-room-title">{r.title}</span>
              <span className="housing-room-template">{r.templateId}</span>
            </div>
            <p className="housing-room-desc">{r.description}</p>
          </li>
        ))}
      </ul>

      {inOwnHouse && (
        <div className="housing-customize-section">
          <p className="housing-section-label">Customize This Room</p>
          {editField === null ? (
            <div className="housing-edit-buttons">
              <button type="button" className="housing-action-btn" onClick={() => startEdit("title")}>
                Edit Title
              </button>
              <button type="button" className="housing-action-btn" onClick={() => startEdit("desc")}>
                Edit Description
              </button>
            </div>
          ) : (
            <div className="housing-edit-form">
              <label className="housing-edit-label">
                {editField === "title" ? "New Title" : "New Description"}
              </label>
              {editField === "title" ? (
                <input
                  type="text"
                  className="housing-edit-input"
                  maxLength={60}
                  value={editValue}
                  onChange={(e) => setEditValue(e.target.value)}
                  onKeyDown={(e) => { if (e.key === "Enter") submitEdit(); if (e.key === "Escape") cancelEdit(); }}
                  autoFocus
                  placeholder="Enter new title..."
                />
              ) : (
                <textarea
                  className="housing-edit-textarea"
                  maxLength={500}
                  rows={3}
                  value={editValue}
                  onChange={(e) => setEditValue(e.target.value)}
                  onKeyDown={(e) => { if (e.key === "Escape") cancelEdit(); }}
                  autoFocus
                  placeholder="Enter new description..."
                />
              )}
              <div className="housing-edit-actions">
                <button type="button" className="housing-action-btn housing-save-btn" onClick={submitEdit}>Save</button>
                <button type="button" className="housing-action-btn" onClick={cancelEdit}>Cancel</button>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
