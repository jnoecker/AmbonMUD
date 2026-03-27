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

  return (
    <div className="equipment-panel-v2">
      <div className="paperdoll-layout">
        {/* Left: large player sprite with slot markers */}
        <div className="paperdoll-sprite-col">
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

        {/* Right: slot list (head to feet) */}
        <div className="paperdoll-slots-col">
          <ul className="paperdoll-slot-list">
            {slotDefs.map((def) => {
              const item = equipment[def.id];
              const isSelected = selectedSlot === def.id;
              return (
                <li
                  key={def.id}
                  className={`paperdoll-list-item ${isSelected ? "paperdoll-list-item-selected" : ""}`}
                  onClick={() => setSelectedSlot(isSelected ? null : def.id)}
                >
                  <div className="paperdoll-list-header">
                    <span className="paperdoll-list-slot">{def.displayName}</span>
                    <span className={`paperdoll-list-name ${item ? "" : "paperdoll-list-name-empty"}`}>
                      {item ? item.name : "\u2014"}
                    </span>
                  </div>
                  {isSelected && item && (
                    <div className="paperdoll-list-actions">
                      <button
                        type="button"
                        className="paperdoll-remove-btn"
                        disabled={!canManageItems}
                        onClick={(e) => {
                          e.stopPropagation();
                          onRemoveItem(def.id);
                          setSelectedSlot(null);
                        }}
                      >
                        <RemoveItemIcon className="paperdoll-remove-icon" />
                        <span>Remove</span>
                      </button>
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        </div>
      </div>

      {/* Floating item preview overlay */}
      {selectedSlot && selectedItem?.image && (
        <div
          className="paperdoll-preview-overlay"
          onClick={() => setSelectedSlot(null)}
        >
          <div
            className="paperdoll-preview-card"
            key={selectedSlot}
            onClick={(e) => e.stopPropagation()}
          >
            <img
              src={selectedItem.image}
              alt={selectedItem.name}
              className="paperdoll-preview-img"
            />
            <span className="paperdoll-preview-name">{selectedItem.name}</span>
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
            <button
              type="button"
              className="paperdoll-preview-close"
              onClick={() => setSelectedSlot(null)}
              aria-label="Close preview"
            >
              {"\u00D7"}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
