import { useState } from "react";
import type { ItemSummary, RoomPlayer } from "../../types";
import { DropItemIcon, GiveItemIcon, WearItemIcon } from "../Icons";

interface InventoryPanelProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  inventory: ItemSummary[];
  players: RoomPlayer[];
  canManageItems: boolean;
  onWearItem: (itemName: string) => void;
  onDropItem: (itemName: string) => void;
  onGiveItem: (itemKeyword: string, playerName: string) => void;
}

function categorize(items: ItemSummary[]): { wearable: ItemSummary[]; other: ItemSummary[] } {
  const wearable: ItemSummary[] = [];
  const other: ItemSummary[] = [];
  for (const item of items) {
    if (item.slot) {
      wearable.push(item);
    } else {
      other.push(item);
    }
  }
  return { wearable, other };
}

export function InventoryPanel({
  connected,
  hasCharacterProfile,
  inventory,
  players,
  canManageItems,
  onWearItem,
  onDropItem,
  onGiveItem,
}: InventoryPanelProps) {
  const [givePickerItemId, setGivePickerItemId] = useState<string | null>(null);

  if (!connected || !hasCharacterProfile) {
    return <p className="empty-note">Log in to view inventory.</p>;
  }

  if (inventory.length === 0) {
    return (
      <div className="inventory-empty-state">
        <p className="empty-note">Your bags are empty.</p>
      </div>
    );
  }

  const { wearable, other } = categorize(inventory);

  const renderItem = (item: ItemSummary) => (
    <li key={item.id} className="inventory-item">
      <div className="inventory-item-row">
        <span className="inventory-item-info">
          {item.image && <img src={item.image} alt="" className="inventory-item-thumb" />}
          <span className="inventory-item-name">{item.name}</span>
          {item.slot && <span className="inventory-item-slot">{item.slot}</span>}
        </span>
        <span className="inventory-item-actions">
          {item.slot && (
            <button
              type="button"
              className="inventory-action-btn inventory-action-equip"
              title={`Wear ${item.name}`}
              disabled={!canManageItems}
              onClick={() => onWearItem(item.name)}
            >
              <WearItemIcon className="inventory-action-icon" />
            </button>
          )}
          {players.length > 0 && (
            <button
              type="button"
              className={`inventory-action-btn ${givePickerItemId === item.id ? "inventory-action-btn-active" : ""}`}
              title={`Give ${item.name}`}
              disabled={!canManageItems}
              onClick={() => setGivePickerItemId(givePickerItemId === item.id ? null : item.id)}
            >
              <GiveItemIcon className="inventory-action-icon" />
            </button>
          )}
          <button
            type="button"
            className="inventory-action-btn"
            title={`Drop ${item.name}`}
            disabled={!canManageItems}
            onClick={() => onDropItem(item.name)}
          >
            <DropItemIcon className="inventory-action-icon" />
          </button>
        </span>
      </div>
      {givePickerItemId === item.id && (
        <div className="inventory-give-picker" role="listbox" aria-label={`Give ${item.name} to`}>
          <span className="inventory-give-label">Give to:</span>
          {players.map((player) => (
            <button
              key={player.name}
              type="button"
              role="option"
              className="inventory-give-option"
              onClick={() => {
                onGiveItem(item.keyword, player.name);
                setGivePickerItemId(null);
              }}
            >
              {player.name}
            </button>
          ))}
        </div>
      )}
    </li>
  );

  return (
    <div className="inventory-panel">
      {wearable.length > 0 && (
        <section className="inventory-section">
          <h3 className="inventory-section-title">Equipment</h3>
          <ul className="inventory-list">{wearable.map(renderItem)}</ul>
        </section>
      )}
      {other.length > 0 && (
        <section className="inventory-section">
          <h3 className="inventory-section-title">Items</h3>
          <ul className="inventory-list">{other.map(renderItem)}</ul>
        </section>
      )}
    </div>
  );
}
