import type { CharacterInfo, EquipmentSlotDef, ItemSummary } from "../../types";
import { resolveItemImage } from "../../imageDefaults";

interface EquipmentPanelProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  character: CharacterInfo;
  equipment: Record<string, ItemSummary>;
  slotDefs: EquipmentSlotDef[];
  /** Open the parchment item card (full look) for an equipped slot. */
  onExamineItem: (item: ItemSummary, image: string | null, slot: string) => void;
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
  onExamineItem,
}: EquipmentPanelProps) {
  if (!connected || !hasCharacterProfile) {
    return <p className="empty-note">Log in to view equipment.</p>;
  }

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
              const isEmpty = !item;
              const img = item ? resolveItemImage(item) : null;
              return (
                <button
                  key={def.id}
                  type="button"
                  className={`paperdoll-slot ${isEmpty ? "paperdoll-slot-empty" : "paperdoll-slot-filled"}`}
                  style={{ left: `${def.x}%`, top: `${def.y}%` }}
                  title={isEmpty ? `${def.displayName} — empty` : `${def.displayName}: ${item.name}`}
                  onClick={() => { if (item) onExamineItem(item, img, def.id); }}
                >
                  {img ? (
                    <img src={img} alt="" className="paperdoll-slot-img" />
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

        {/* Right: slot cards (head to feet); click a filled slot for its detail */}
        <div className="paperdoll-slots-col">
          <ul className="paperdoll-slot-list">
            {slotDefs.map((def) => {
              const item = equipment[def.id];
              const thumb = item ? resolveItemImage(item) : null;
              return (
                <li
                  key={def.id}
                  className={`paperdoll-list-item ${item ? "paperdoll-list-item-filled" : "paperdoll-list-item-empty"}`}
                  role={item ? "button" : undefined}
                  tabIndex={item ? 0 : undefined}
                  aria-label={item ? `${def.displayName}: ${item.name} — examine` : undefined}
                  onClick={() => { if (item) onExamineItem(item, thumb, def.id); }}
                  onKeyDown={(e) => {
                    if (item && (e.key === "Enter" || e.key === " ")) {
                      e.preventDefault();
                      onExamineItem(item, thumb, def.id);
                    }
                  }}
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
                  </div>
                </li>
              );
            })}
          </ul>
        </div>
      </div>
    </div>
  );
}
