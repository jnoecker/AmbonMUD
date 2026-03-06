import { useEffect, useState } from "react";
import type { DragEvent, FormEvent, KeyboardEvent, RefObject } from "react";
import type { PopoutPanel, ShopState, SkillSummary, Vitals } from "../types";
import { percent } from "../utils";
import {
  CharacterAvatarIcon,
  EquipmentIcon,
  ChatBubbleIcon,
  ShopIcon,
  HelpIcon,
  SpellbookIcon,
  SkillCastIcon,
  SendIcon,
} from "./Icons";

interface ActionBarProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  vitals: Vitals;
  quickbarSlots: (SkillSummary | null)[];
  shop: ShopState | null;
  activePopout: PopoutPanel;
  onOpenPopout: (panel: PopoutPanel) => void;
  onCastSkill: (skillId: string, cooldownMs: number) => void;
  onQuickbarSwap: (fromIndex: number, toIndex: number) => void;
  onQuickbarAssign: (slotIndex: number, skillId: string) => void;
  onQuickbarClear: (slotIndex: number) => void;
  composerInputRef: RefObject<HTMLInputElement | null>;
  composerValue: string;
  commandPlaceholder: string;
  onComposerChange: (value: string) => void;
  onComposerKeyDown: (event: KeyboardEvent<HTMLInputElement>) => void;
  onComposerFocus: () => void;
  onComposerBlur: () => void;
  onSubmitComposer: (event: FormEvent<HTMLFormElement>) => void;
}

interface PanelButton {
  panel: PopoutPanel;
  label: string;
  icon: React.ReactNode;
  requiresProfile: boolean;
  badge?: number;
}

function SkillSlot({
  skill,
  index,
  onCast,
  onDragStart,
  onDragOver,
  onDragLeave,
  onDrop,
  onClear,
}: {
  skill: SkillSummary;
  index: number;
  onCast: (skillId: string, cooldownMs: number) => void;
  onDragStart: (e: DragEvent, index: number) => void;
  onDragOver: (e: DragEvent) => void;
  onDragLeave: (e: DragEvent) => void;
  onDrop: (e: DragEvent, index: number) => void;
  onClear: (index: number) => void;
}) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (skill.cooldownRemainingMs <= 0) return;
    const interval = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(interval);
  }, [skill.cooldownRemainingMs, skill.receivedAt]);

  const elapsed = now - skill.receivedAt;
  const remaining = Math.max(0, skill.cooldownRemainingMs - elapsed);
  const onCooldown = remaining > 0;
  const cooldownFraction = onCooldown && skill.cooldownMs > 0
    ? remaining / skill.cooldownMs
    : 0;

  return (
    <button
      type="button"
      className={`action-bar-skill${onCooldown ? " action-bar-skill-cooldown" : ""}`}
      title={`${skill.name} (${skill.manaCost} mana) — key ${index + 1}\nRight-click to remove`}
      disabled={onCooldown}
      draggable
      onClick={() => onCast(skill.id, skill.cooldownMs)}
      onContextMenu={(e) => { e.preventDefault(); onClear(index); }}
      onDragStart={(e) => onDragStart(e, index)}
      onDragOver={onDragOver}
      onDragLeave={onDragLeave}
      onDrop={(e) => onDrop(e, index)}
    >
      {skill.image
        ? <img src={skill.image} alt="" className="action-bar-skill-img" draggable={false} />
        : <SkillCastIcon className="action-bar-skill-icon" classRestriction={skill.classRestriction} targetType={skill.targetType} />
      }
      {onCooldown && (
        <span
          className="action-bar-skill-sweep"
          style={{ height: `${cooldownFraction * 100}%` }}
        />
      )}
      <span className="action-bar-skill-key">{index + 1}</span>
    </button>
  );
}

function EmptySlot({
  index,
  onDragOver,
  onDragLeave,
  onDrop,
}: {
  index: number;
  onDragOver: (e: DragEvent) => void;
  onDragLeave: (e: DragEvent) => void;
  onDrop: (e: DragEvent, index: number) => void;
}) {
  return (
    <div
      className="action-bar-skill action-bar-skill-empty"
      title={`Slot ${index + 1} — drag a spell here`}
      onDragOver={onDragOver}
      onDragLeave={onDragLeave}
      onDrop={(e) => onDrop(e, index)}
    >
      <span className="action-bar-skill-key">{index + 1}</span>
    </div>
  );
}

export function ActionBar({
  connected,
  hasCharacterProfile,
  vitals,
  quickbarSlots,
  shop,
  activePopout,
  onOpenPopout,
  onCastSkill,
  onQuickbarSwap,
  onQuickbarAssign,
  onQuickbarClear,
  composerInputRef,
  composerValue,
  commandPlaceholder,
  onComposerChange,
  onComposerKeyDown,
  onComposerFocus,
  onComposerBlur,
  onSubmitComposer,
}: ActionBarProps) {
  const loggedIn = connected && hasCharacterProfile;

  const panels: PanelButton[] = [
    { panel: "character", label: "Character", icon: <CharacterAvatarIcon className="action-bar-btn-icon" />, requiresProfile: true },
    { panel: "equipment", label: "Equipment", icon: <EquipmentIcon className="action-bar-btn-icon" />, requiresProfile: true },
    { panel: "spellbook", label: "Spellbook", icon: <SpellbookIcon className="action-bar-btn-icon" />, requiresProfile: true },
    { panel: "chat", label: "Social", icon: <ChatBubbleIcon className="action-bar-btn-icon" />, requiresProfile: true },
    { panel: "help", label: "Help", icon: <HelpIcon className="action-bar-btn-icon" />, requiresProfile: false },
  ];

  const shopActive = shop !== null;
  const hasAnySlot = quickbarSlots.some((s) => s !== null);

  // Drag-and-drop handlers for quickbar reordering
  const handleDragStart = (e: DragEvent, index: number) => {
    e.dataTransfer.setData("quickbar-index", String(index));
    e.dataTransfer.effectAllowed = "move";
  };

  const handleDragOver = (e: DragEvent) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = "move";
    const target = (e.currentTarget as HTMLElement);
    if (!target.classList.contains("drag-over")) target.classList.add("drag-over");
  };

  const handleDragLeave = (e: DragEvent) => {
    (e.currentTarget as HTMLElement).classList.remove("drag-over");
  };

  const handleDrop = (e: DragEvent, toIndex: number) => {
    e.preventDefault();
    (e.currentTarget as HTMLElement).classList.remove("drag-over");
    // From quickbar slot reorder
    const fromIndexStr = e.dataTransfer.getData("quickbar-index");
    if (fromIndexStr) {
      onQuickbarSwap(parseInt(fromIndexStr, 10), toIndex);
      return;
    }
    // From spellbook drag
    const skillId = e.dataTransfer.getData("skill-id");
    if (skillId) {
      onQuickbarAssign(toIndex, skillId);
    }
  };

  return (
    <nav className="action-bar" aria-label="Action bar">
      <div className="action-bar-panels">
        {panels.map((btn) => {
          const disabled = btn.requiresProfile && !loggedIn;
          const active = activePopout === btn.panel;
          return (
            <button
              key={btn.panel}
              type="button"
              className={`action-bar-btn${active ? " action-bar-btn-active" : ""}${disabled ? " action-bar-btn-disabled" : ""}`}
              title={btn.label}
              aria-label={btn.label}
              disabled={disabled}
              onClick={() => onOpenPopout(active ? null : btn.panel)}
            >
              {btn.icon}
            </button>
          );
        })}
        {shopActive && (
          <button
            type="button"
            className={`action-bar-btn action-bar-btn-shop${activePopout === "shop" ? " action-bar-btn-active" : ""}`}
            title={shop.name}
            aria-label={`Open ${shop.name}`}
            onClick={() => onOpenPopout(activePopout === "shop" ? null : "shop")}
          >
            <ShopIcon className="action-bar-btn-icon" />
          </button>
        )}
      </div>

      {loggedIn && (
        <div className="action-bar-vitals">
          <div className="action-bar-vital" title={`HP: ${vitals.hp} / ${vitals.maxHp}`}>
            <span className="action-bar-vital-label">HP</span>
            <div className="action-bar-vital-track">
              <span className="action-bar-vital-fill action-bar-vital-fill-hp" style={{ width: `${percent(vitals.hp, vitals.maxHp)}%` }} />
            </div>
            <span className="action-bar-vital-text">{vitals.hp}/{vitals.maxHp}</span>
          </div>
          <div className="action-bar-vital" title={`Mana: ${vitals.mana} / ${vitals.maxMana}`}>
            <span className="action-bar-vital-label">MP</span>
            <div className="action-bar-vital-track">
              <span className="action-bar-vital-fill action-bar-vital-fill-mana" style={{ width: `${percent(vitals.mana, vitals.maxMana)}%` }} />
            </div>
            <span className="action-bar-vital-text">{vitals.mana}/{vitals.maxMana}</span>
          </div>
          {vitals.xpToNextLevel !== null && (
            <div className="action-bar-vital" title={`XP: ${vitals.xpIntoLevel} / ${vitals.xpToNextLevel}`}>
              <span className="action-bar-vital-label">XP</span>
              <div className="action-bar-vital-track">
                <span className="action-bar-vital-fill action-bar-vital-fill-xp" style={{ width: `${percent(vitals.xpIntoLevel, vitals.xpToNextLevel)}%` }} />
              </div>
              <span className="action-bar-vital-text">{vitals.xpIntoLevel}/{vitals.xpToNextLevel}</span>
            </div>
          )}
          <div className="action-bar-gold" title={`Gold: ${vitals.gold}`}>
            <span className="action-bar-gold-coin" />
            <span className="action-bar-gold-text">{vitals.gold.toLocaleString()}</span>
          </div>
        </div>
      )}

      {loggedIn && hasAnySlot && (
        <div className="action-bar-skills">
          {quickbarSlots.map((skill, i) =>
            skill ? (
              <SkillSlot
                key={`slot-${i}`}
                skill={skill}
                index={i}
                onCast={onCastSkill}
                onDragStart={handleDragStart}
                onDragOver={handleDragOver}
                onDragLeave={handleDragLeave}
                onDrop={handleDrop}
                onClear={onQuickbarClear}
              />
            ) : (
              <EmptySlot
                key={`slot-${i}`}
                index={i}
                onDragOver={handleDragOver}
                onDragLeave={handleDragLeave}
                onDrop={handleDrop}
              />
            ),
          )}
        </div>
      )}

      <form className="action-bar-command" onSubmit={onSubmitComposer}>
        <label htmlFor="command-input" className="sr-only">Command input</label>
        <input
          ref={composerInputRef}
          id="command-input"
          className="command-input"
          type="text"
          value={composerValue}
          onChange={(event) => onComposerChange(event.target.value)}
          onKeyDown={onComposerKeyDown}
          onFocus={onComposerFocus}
          onBlur={onComposerBlur}
          placeholder={commandPlaceholder}
          autoComplete="off"
          spellCheck={false}
        />
        <button type="submit" className="soft-button action-bar-send" disabled={!connected}>
          <SendIcon className="action-bar-send-icon" />
        </button>
      </form>
    </nav>
  );
}
