import { useMemo, useState } from "react";
import type { ContainerContents, ItemSummary, ItemType, RoomFeature, RoomPlayer } from "../../types";
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
  /** Resolved server assets, for the optional action-button icons. */
  serverAssets: Record<string, string>;
  onWearItem: (itemName: string) => void;
  onDropItem: (itemName: string) => void;
  onGiveItem: (itemKeyword: string, playerName: string) => void;
  /** Open the parchment item card (same window as clicking a ground item). */
  onExamineItem: (item: ItemSummary, image: string | null) => void;
  onCommand: (command: string) => void;
  /** Pulse the equip action on wearable items as a first-time onboarding cue. */
  equipHint?: boolean;
}

interface ItemStack {
  /** Server stackKey, or a synthetic per-instance key for older servers. */
  key: string;
  /** First instance in the group — used for display name, keyword, and image. */
  lead: ItemSummary;
  /** All instances sharing the stackKey (count = instances.length). */
  instances: ItemSummary[];
}

type SectionKey = "equipment" | "consumable" | "quest" | "treasure" | "misc";

interface Section {
  key: SectionKey;
  title: string;
  hint: string | null;
  stacks: ItemStack[];
}

function resolveItemType(item: ItemSummary): ItemType {
  if (item.itemType) return item.itemType;
  if (item.questItem) return "quest";
  if (item.slot) return "equipment";
  if (item.consumable) return "consumable";
  if ((item.basePrice ?? 0) > 0) return "treasure";
  return "misc";
}

function groupAndCategorize(items: ItemSummary[]): Section[] {
  const buckets: Record<SectionKey, Map<string, ItemStack>> = {
    equipment: new Map(),
    consumable: new Map(),
    quest: new Map(),
    treasure: new Map(),
    misc: new Map(),
  };

  for (const item of items) {
    const type = resolveItemType(item);
    // Fall back to a unique per-instance key when the server did not provide one:
    // that means every instance renders as its own stack of 1 (safe degrade).
    const key = item.stackKey ?? `__instance__:${item.id}`;
    const bucket = buckets[type];
    const existing = bucket.get(key);
    if (existing) {
      existing.instances.push(item);
    } else {
      bucket.set(key, { key, lead: item, instances: [item] });
    }
  }

  const order: { key: SectionKey; title: string; hint: string | null }[] = [
    { key: "equipment", title: "Equipment", hint: null },
    { key: "consumable", title: "Consumables", hint: null },
    { key: "quest", title: "Quest Items", hint: "Soulbound — cannot be dropped or sold." },
    { key: "treasure", title: "Valuables", hint: "Sell to a shopkeeper for gold." },
    { key: "misc", title: "Other", hint: null },
  ];

  return order
    .map(({ key, title, hint }) => ({
      key,
      title,
      hint,
      stacks: Array.from(buckets[key].values()),
    }))
    .filter((section) => section.stacks.length > 0);
}

function categoryChipLabel(type: ItemType): string {
  switch (type) {
    case "equipment":
      return "Gear";
    case "consumable":
      return "Use";
    case "quest":
      return "Quest";
    case "treasure":
      return "Sell";
    case "misc":
      return "Misc";
  }
}

export function InventoryPanel({
  connected,
  hasCharacterProfile,
  inventory,
  players,
  canManageItems,
  roomFeatures,
  containerContents,
  serverAssets,
  onWearItem,
  onDropItem,
  onGiveItem,
  onExamineItem,
  onCommand,
  equipHint = false,
}: InventoryPanelProps) {
  const [givePickerStackKey, setGivePickerStackKey] = useState<string | null>(null);
  const containers = useMemo(
    () => roomFeatures.filter((f) => f.type === "container"),
    [roomFeatures],
  );
  const activeContainerKeyword = containerContents?.keyword ?? null;
  const sections = useMemo(() => groupAndCategorize(inventory), [inventory]);

  if (!connected || !hasCharacterProfile) {
    return <p className="empty-note">Log in to view inventory.</p>;
  }

  if (inventory.length === 0) {
    return (
      <div className="inventory-empty-state">
        <div className="inventory-empty-pocket" aria-hidden="true">
          <span className="inventory-empty-pocket-flap" />
        </div>
        <p className="empty-note inventory-empty-note">Your satchel is empty.</p>
        <p className="inventory-empty-sub">Pick things up and they&rsquo;ll be tucked away here.</p>
      </div>
    );
  }

  // A custom action icon when its asset is registered; else null (the caller
  // supplies a unicode/SVG fallback).
  const actionAsset = (key: string) =>
    serverAssets[key] ? (
      <img className="inventory-action-img" src={serverAssets[key]} alt="" aria-hidden="true" />
    ) : null;

  const renderStack = (stack: ItemStack, type: ItemType) => {
    const item = stack.lead;
    const count = stack.instances.length;
    const isQuest = type === "quest" || !!item.questItem;
    const isVendorFodder = type === "treasure" && !item.slot && !item.consumable;
    const image = resolveItemImage(item);

    const rowClasses = [
      "inventory-item-row",
      `inventory-item-row-${type}`,
      isQuest ? "inventory-item-row-quest" : "",
      isVendorFodder ? "inventory-item-row-vendor" : "",
    ]
      .filter(Boolean)
      .join(" ");

    return (
      <li key={stack.key} className="inventory-item">
        <div className={rowClasses}>
          <div className="inventory-item-info">
            <div className="inventory-item-thumb-wrap">
              {image && <img src={image} alt="" className="inventory-item-thumb" />}
              {count > 1 && (
                <span className="inventory-stack-count" aria-label={`${count} in stack`}>
                  ×{count}
                </span>
              )}
            </div>
            <div className="inventory-item-copy">
              <span className="inventory-item-name">{item.name}</span>
              <div className="inventory-item-meta">
                <span className={`inventory-item-chip inventory-item-chip-${type}`}>
                  {categoryChipLabel(type)}
                </span>
                {item.slot && <span className="inventory-item-slot">{item.slot}</span>}
                {!item.slot && item.consumable && item.useEffect && (
                  <span className="inventory-item-effect" title={item.useEffect}>
                    {item.useEffect}
                  </span>
                )}
                {isVendorFodder && (item.basePrice ?? 0) > 0 && (
                  <span className="inventory-item-price" title="Vendor price">
                    {item.basePrice}g
                  </span>
                )}
              </div>
            </div>
          </div>
          <div className="inventory-item-actions">
            <button
              type="button"
              className="inventory-action-btn inventory-action-btn-icon inventory-action-examine"
              aria-label={`Examine ${item.name}`}
              title={`Examine ${item.name}`}
              onClick={() => onExamineItem(item, image)}
            >
              {actionAsset("action_look") ?? <span className="inventory-action-glyph" aria-hidden="true">❍</span>}
            </button>
            {!item.slot && item.consumable && (
              <button
                type="button"
                className="inventory-action-btn inventory-action-btn-icon inventory-action-use"
                aria-label={`Use ${item.name}`}
                title={`Use ${item.name}${item.useEffect ? ` - ${item.useEffect}` : ""}`}
                disabled={!canManageItems}
                onClick={() => onCommand(`use ${item.keyword}`)}
              >
                {actionAsset("action_use") ?? <span className="inventory-action-glyph" aria-hidden="true">⚗</span>}
              </button>
            )}
            {item.slot && (
              <button
                type="button"
                className={`inventory-action-btn inventory-action-btn-icon inventory-action-equip${equipHint ? " inventory-action-equip-hint" : ""}`}
                aria-label={`Equip ${item.name}`}
                title={equipHint ? `Equip ${item.name}` : `Wear ${item.name}`}
                disabled={!canManageItems}
                onClick={() => onWearItem(item.name)}
              >
                {actionAsset("action_equip") ?? <WearItemIcon className="inventory-action-icon" />}
              </button>
            )}
            {containerContents && !isQuest && (
              <button
                type="button"
                className="inventory-action-btn inventory-action-btn-icon inventory-action-put"
                aria-label={`Store ${item.name} in ${containerContents.name}`}
                title={`Store ${item.name} in ${containerContents.name}`}
                disabled={!canManageItems}
                onClick={() => onCommand(`put ${item.keyword} in ${containerContents.keyword}`)}
              >
                {actionAsset("action_store") ?? <span className="inventory-action-glyph" aria-hidden="true">⊞</span>}
              </button>
            )}
            {players.length > 0 && !isQuest && (
              <button
                type="button"
                className={`inventory-action-btn inventory-action-btn-icon${givePickerStackKey === stack.key ? " inventory-action-btn-active" : ""}`}
                title={`Give ${item.name}`}
                aria-label={`Give ${item.name}`}
                aria-expanded={givePickerStackKey === stack.key}
                disabled={!canManageItems}
                onClick={() => setGivePickerStackKey(givePickerStackKey === stack.key ? null : stack.key)}
              >
                <GiveItemIcon className="inventory-action-icon" />
              </button>
            )}
            {!isQuest && (
              <button
                type="button"
                className="inventory-action-btn inventory-action-btn-icon"
                title={count > 1 ? `Drop one ${item.name}` : `Drop ${item.name}`}
                aria-label={count > 1 ? `Drop one ${item.name}` : `Drop ${item.name}`}
                disabled={!canManageItems}
                onClick={() => onDropItem(item.name)}
              >
                {actionAsset("action_drop") ?? <DropItemIcon className="inventory-action-icon" />}
              </button>
            )}
            {isQuest && (
              <span
                className="inventory-item-soulbound"
                title="Quest items cannot be dropped, given, sold, or traded."
                aria-label="Soulbound"
              >
                Soulbound
              </span>
            )}
          </div>
        </div>
        {givePickerStackKey === stack.key && (
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
                  setGivePickerStackKey(null);
                }}
              >
                {player.name}
              </button>
            ))}
          </div>
        )}
      </li>
    );
  };

  return (
    <div className="inventory-panel">
      {containerContents && (
        <div className="inventory-active-container" role="status" aria-live="polite">
          <span className="inventory-active-container-label">Storing in</span>
          <span className="inventory-active-container-name" title={containerContents.name}>{containerContents.name}</span>
        </div>
      )}
      {sections.map((section) => (
        <section key={section.key} className={`inventory-section inventory-section-${section.key}`}>
          <div className="inventory-section-header">
            <h3 className="inventory-section-title">{section.title}</h3>
            <span className="inventory-section-count" aria-label={`${section.stacks.length} stacks`}>
              {section.stacks.length}
            </span>
          </div>
          {section.hint && <p className="inventory-section-hint">{section.hint}</p>}
          <ul className="inventory-list">
            {section.stacks.map((stack) => renderStack(stack, section.key))}
          </ul>
        </section>
      ))}
      {containers.length > 0 && (
        <section className="inventory-section inventory-section-containers">
          <div className="inventory-section-header">
            <h3 className="inventory-section-title">Containers</h3>
          </div>
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
