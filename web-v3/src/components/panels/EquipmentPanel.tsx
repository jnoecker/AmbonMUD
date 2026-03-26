import { useState } from "react";
import type { CharacterInfo, EquipmentSlotDef, ItemSummary } from "../../types";
import { RemoveItemIcon } from "../Icons";

interface EquipmentPanelProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  character: CharacterInfo;
  equipment: Record<string, ItemSummary>;
  slotDefs: EquipmentSlotDef[];
  canManageItems: boolean;
  onRemoveItem: (slot: string) => void;
}

export function EquipmentPanel({
  connected,
  hasCharacterProfile,
  character,
  equipment,
  slotDefs,
  canManageItems,
  onRemoveItem,
}: EquipmentPanelProps) {
  const [selectedSlot, setSelectedSlot] = useState<string | null>(null);

  if (!connected || !hasCharacterProfile) {
    return <p className="empty-note">Log in to view equipment.</p>;
  }

  const selectedItem = selectedSlot ? equipment[selectedSlot] : null;
  const selectedDef = selectedSlot ? slotDefs.find((d) => d.id === selectedSlot) : null;

  return (
    <div className="equipment-panel-v2">
      {/* Paper doll area */}
      <div className="paperdoll-container">
        <div className="paperdoll-sprite-wrap">
          {character.sprite ? (
            <img
              src={character.sprite}
              alt={`${character.name}`}
              className="paperdoll-sprite"
            />
          ) : (
            <div className="paperdoll-sprite paperdoll-sprite-placeholder" />
          )}

          {/* Slot markers positioned via percentage coordinates */}
          {slotDefs.map((def) => {
            const item = equipment[def.id];
            const isSelected = selectedSlot === def.id;
            const isEmpty = !item;
            return (
              <button
                key={def.id}
                type="button"
                className={`paperdoll-slot ${isEmpty ? "paperdoll-slot-empty" : "paperdoll-slot-filled"} ${isSelected ? "paperdoll-slot-selected" : ""}`}
                style={{ left: `${def.x}%`, top: `${def.y}%` }}
                title={isEmpty ? `${def.displayName} — empty` : `${def.displayName}: ${item.name}`}
                onClick={() => setSelectedSlot(isSelected ? null : def.id)}
              >
                {item?.image ? (
                  <img src={item.image} alt="" className="paperdoll-slot-img" />
                ) : (
                  <span className="paperdoll-slot-letter">
                    {def.displayName.charAt(0)}
                  </span>
                )}
              </button>
            );
          })}
        </div>
      </div>

      {/* Selected slot detail */}
      {selectedSlot && selectedDef && (
        <div className="paperdoll-detail">
          <div className="paperdoll-detail-header">
            <span className="paperdoll-detail-slot">{selectedDef.displayName}</span>
            {selectedItem ? (
              <span className="paperdoll-detail-name">{selectedItem.name}</span>
            ) : (
              <span className="paperdoll-detail-empty">Empty</span>
            )}
          </div>
          {selectedItem && (
            <div className="paperdoll-detail-actions">
              <button
                type="button"
                className="paperdoll-remove-btn"
                disabled={!canManageItems}
                onClick={() => {
                  onRemoveItem(selectedSlot);
                  setSelectedSlot(null);
                }}
              >
                <RemoveItemIcon className="paperdoll-remove-icon" />
                <span>Remove</span>
              </button>
            </div>
          )}
        </div>
      )}

      {/* Slot list summary below the doll */}
      <ul className="paperdoll-slot-list">
        {slotDefs.map((def) => {
          const item = equipment[def.id];
          return (
            <li
              key={def.id}
              className={`paperdoll-list-item ${selectedSlot === def.id ? "paperdoll-list-item-selected" : ""}`}
              onClick={() => setSelectedSlot(selectedSlot === def.id ? null : def.id)}
            >
              <span className="paperdoll-list-slot">{def.displayName}</span>
              <span className={`paperdoll-list-name ${item ? "" : "paperdoll-list-name-empty"}`}>
                {item ? item.name : "—"}
              </span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
