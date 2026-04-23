import { useMemo, useState } from "react";
import type { ContainerContents, ItemSummary, RoomFeature, RoomPlayer } from "../../types";
import { DropItemIcon, GiveItemIcon, WearItemIcon } from "../Icons";
import { resolveItemImage } from "../../imageDefaults";

interface InventoryPanelProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  inventory: ItemSummary[];
  players: RoomPlayer[];
  canManageItems: boolean;
  roomFeatures: RoomFeature[];
  containerContents: ContainerContents | null;
  onWearItem: (itemName: string) => void;
  onDropItem: (itemName: string) => void;
  onGiveItem: (itemKeyword: string, playerName: string) => void;
  onCommand: (command: string) => void;
  /** Pulse the equip action on wearable items as a first-time onboarding cue. */
  equipHint?: boolean;
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
  roomFeatures,
  containerContents,
  onWearItem,
  onDropItem,
  onGiveItem,
  onCommand,
  equipHint = false,
}: InventoryPanelProps) {
  const [givePickerItemId, setGivePickerItemId] = useState<string | null>(null);
  const containers = useMemo(
    () => roomFeatures.filter((f) => f.type === "container"),
    [roomFeatures],
  );
  const activeContainerKeyword = containerContents?.keyword ?? null;

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
        <div className="inventory-item-info">
          {resolveItemImage(item) && <img src={resolveItemImage(item)!} alt="" className="inventory-item-thumb" />}
          <div className="inventory-item-copy">
            <span className="inventory-item-name">{item.name}</span>
            <div className="inventory-item-meta">
              {item.slot && <span className="inventory-item-slot">{item.slot}</span>}
              {!item.slot && item.consumable && item.useEffect && (
                <span className="inventory-item-effect" title={item.useEffect}>{item.useEffect}</span>
              )}
            </div>
          </div>
        </div>
        <div className="inventory-item-actions">
          <button
            type="button"
            className="inventory-action-btn inventory-action-btn-pill inventory-action-examine"
            aria-label={`Examine ${item.name}`}
            title={`Examine ${item.name}`}
            onClick={() => onCommand(`look ${item.keyword}`)}
          >
            Examine
          </button>
          {!item.slot && item.consumable && (
            <button
              type="button"
              className="inventory-action-btn inventory-action-btn-pill inventory-action-use"
              aria-label={`Use ${item.name}`}
              title={`Use ${item.name}${item.useEffect ? ` - ${item.useEffect}` : ""}`}
              disabled={!canManageItems}
              onClick={() => onCommand(`use ${item.keyword}`)}
            >
              Use
            </button>
          )}
          {item.slot && (
            <button
              type="button"
              className={`inventory-action-btn inventory-action-btn-pill inventory-action-equip${equipHint ? " inventory-action-equip-hint" : ""}`}
              aria-label={`Equip ${item.name}`}
              title={equipHint ? `Equip ${item.name}` : `Wear ${item.name}`}
              disabled={!canManageItems}
              onClick={() => onWearItem(item.name)}
            >
              <WearItemIcon className="inventory-action-icon" />
              <span>Equip</span>
            </button>
          )}
          {containerContents && (
            <button
              type="button"
              className="inventory-action-btn inventory-action-btn-pill inventory-action-put"
              aria-label={`Store ${item.name} in ${containerContents.name}`}
              title={`Store ${item.name} in ${containerContents.name}`}
              disabled={!canManageItems}
              onClick={() => onCommand(`put ${item.keyword} in ${containerContents.keyword}`)}
            >
              Store
            </button>
          )}
          {players.length > 0 && (
            <button
              type="button"
              className={`inventory-action-btn inventory-action-btn-icon${givePickerItemId === item.id ? " inventory-action-btn-active" : ""}`}
              title={`Give ${item.name}`}
              aria-label={`Give ${item.name}`}
              aria-expanded={givePickerItemId === item.id}
              disabled={!canManageItems}
              onClick={() => setGivePickerItemId(givePickerItemId === item.id ? null : item.id)}
            >
              <GiveItemIcon className="inventory-action-icon" />
            </button>
          )}
          <button
            type="button"
            className="inventory-action-btn inventory-action-btn-icon"
            title={`Drop ${item.name}`}
            aria-label={`Drop ${item.name}`}
            disabled={!canManageItems}
            onClick={() => onDropItem(item.name)}
          >
            <DropItemIcon className="inventory-action-icon" />
          </button>
        </div>
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
      {containerContents && (
        <div className="inventory-active-container" role="status" aria-live="polite">
          <span className="inventory-active-container-label">Storing in</span>
          <span className="inventory-active-container-name" title={containerContents.name}>{containerContents.name}</span>
        </div>
      )}
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
      {containers.length > 0 && (
        <section className="inventory-section">
          <h3 className="inventory-section-title">Containers</h3>
          <div className="container-list">
            {containers.map((container) => (
              <div
                key={container.id}
                className={`container-entry${activeContainerKeyword === container.keyword ? " container-entry-active" : ""}`}
              >
                <div className="container-entry-header">
                  <span className="container-entry-name">{container.name}</span>
                  <button
                    type="button"
                    className="inventory-action-btn inventory-action-btn-pill inventory-action-use"
                    title={`Search ${container.name}`}
                    aria-label={`Search ${container.name}`}
                    disabled={!canManageItems}
                    onClick={() => onCommand(`search ${container.keyword}`)}
                  >
                    Search
                  </button>
                </div>
                {containerContents && containerContents.keyword === container.keyword && containerContents.items.length > 0 && (
                  <ul className="container-items">
                    {containerContents.items.map((ci, idx) => (
                      <li key={`${ci.keyword}-${idx}`} className="container-item">
                        <span className="container-item-name">{ci.name}</span>
                        <button
                          type="button"
                          className="inventory-action-btn inventory-action-btn-pill inventory-action-examine"
                          title={`Examine ${ci.name}`}
                          aria-label={`Examine ${ci.name}`}
                          onClick={() => onCommand(`look ${ci.keyword}`)}
                        >
                          Examine
                        </button>
                        <button
                          type="button"
                          className="inventory-action-btn inventory-action-btn-pill inventory-action-use"
                          title={`Take ${ci.name}`}
                          aria-label={`Take ${ci.name}`}
                          disabled={!canManageItems}
                          onClick={() => onCommand(`get ${ci.keyword} from ${container.keyword}`)}
                        >
                          Take
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}
