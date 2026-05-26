import { useState } from "react";
import type { CharacterInfo, EquipmentSlotDef, ItemSummary } from "../../types";
import { RemoveItemIcon } from "../Icons";
import { resolveItemImage } from "../../imageDefaults";

interface EquipmentPanelProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  character: CharacterInfo;
  equipment: Record<string, ItemSummary>;
  slotDefs: EquipmentSlotDef[];
  canManageItems: boolean;
  onRemoveItem: (slot: string) => void;
}

function renderItemBonuses(item: ItemSummary) {
  const badges: { key: string; label: string; tone: string }[] = [];
  if ((item.damage ?? 0) > 0) {
    badges.push({ key: "dmg", label: `+${item.damage} dmg`, tone: "damage" });
  }
  if ((item.armor ?? 0) > 0) {
    badges.push({ key: "arm", label: `+${item.armor} arm`, tone: "armor" });
  }
  if (item.stats) {
    for (const [stat, value] of Object.entries(item.stats)) {
      if (value !== 0) {
        badges.push({
          key: `stat-${stat}`,
          label: `${value > 0 ? "+" : ""}${value} ${stat}`,
          tone: "stat",
        });
      }
    }
  }
  if (item.enchantments && item.enchantments.length > 0) {
    badges.push({
      key: "enchant",
      label: item.enchantments.join(", "),
      tone: "enchant",
    });
  }
  if (badges.length === 0) return null;
  return (
    <div className="paperdoll-list-badges">
      {badges.map((b) => (
        <span key={b.key} className={`paperdoll-list-badge paperdoll-list-badge-${b.tone}`}>
          {b.label}
        </span>
      ))}
    </div>
  );
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
                  {(item ? resolveItemImage(item) : null) ? (
                    <img src={resolveItemImage(item!)!} alt="" className="paperdoll-slot-img" />
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
              const thumb = item ? resolveItemImage(item) : null;
              return (
                <li
                  key={def.id}
                  className={`paperdoll-list-item ${item ? "paperdoll-list-item-filled" : "paperdoll-list-item-empty"} ${isSelected ? "paperdoll-list-item-selected" : ""}`}
                  onClick={() => setSelectedSlot(isSelected ? null : def.id)}
                >
                  <div className="paperdoll-card-thumb">
                    {thumb ? (
                      <img src={thumb} alt="" className="paperdoll-card-thumb-img" />
                    ) : (
                      <span className="paperdoll-card-thumb-letter">{def.displayName.charAt(0)}</span>
                    )}
                  </div>
                  <div className="paperdoll-card-body">
                    <span className="paperdoll-list-slot">{def.displayName}</span>
                    <span className={`paperdoll-list-name ${item ? "" : "paperdoll-list-name-empty"}`}>
                      {item ? item.name : "Empty"}
                    </span>
                    {item && renderItemBonuses(item)}
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
                  </div>
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
