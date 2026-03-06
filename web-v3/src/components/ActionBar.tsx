import { useEffect, useState } from "react";
import type { FormEvent, KeyboardEvent, RefObject } from "react";
import type { PopoutPanel, ShopState, SkillSummary, Vitals } from "../types";
import { percent } from "../utils";
import {
  CharacterAvatarIcon,
  EquipmentIcon,
  ChatBubbleIcon,
  ShopIcon,
  HelpIcon,
  SkillCastIcon,
  SendIcon,
} from "./Icons";

interface ActionBarProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  vitals: Vitals;
  skills: SkillSummary[];
  shop: ShopState | null;
  activePopout: PopoutPanel;
  onOpenPopout: (panel: PopoutPanel) => void;
  onCastSkill: (skillId: string, cooldownMs: number) => void;
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
}: {
  skill: SkillSummary;
  index: number;
  onCast: (skillId: string, cooldownMs: number) => void;
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
      title={`${skill.name} (${skill.manaCost} mana) — key ${index + 1}`}
      disabled={onCooldown}
      onClick={() => onCast(skill.id, skill.cooldownMs)}
    >
      <SkillCastIcon className="action-bar-skill-icon" classRestriction={skill.classRestriction} targetType={skill.targetType} />
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

export function ActionBar({
  connected,
  hasCharacterProfile,
  vitals,
  skills,
  shop,
  activePopout,
  onOpenPopout,
  onCastSkill,
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
    { panel: "chat", label: "Social", icon: <ChatBubbleIcon className="action-bar-btn-icon" />, requiresProfile: true },
    { panel: "help", label: "Help", icon: <HelpIcon className="action-bar-btn-icon" />, requiresProfile: false },
  ];

  const shopActive = shop !== null;
  const visibleSkills = skills.slice(0, 6);

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
        </div>
      )}

      {loggedIn && visibleSkills.length > 0 && (
        <div className="action-bar-skills">
          {visibleSkills.map((skill, i) => (
            <SkillSlot key={skill.id} skill={skill} index={i} onCast={onCastSkill} />
          ))}
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
