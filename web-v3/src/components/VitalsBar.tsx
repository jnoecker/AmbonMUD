import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import type { PopoutPanel, SkillSummary, Vitals } from "../types";
import { percent } from "../utils";
import {
  CharacterAvatarIcon,
  EquipmentIcon,
  ChatBubbleIcon,
  BankIcon,
  AuctionIcon,
  CraftingIcon,
  HousingIcon,
  MailIcon,
  HelpIcon,
  SpellbookIcon,
  QuestsTabIcon,
  SkillCastIcon,
} from "./Icons";

function skillCategory(skill: SkillSummary): string {
  const t = skill.targetType.toUpperCase();
  const e = skill.effectType.toUpperCase();
  if (t === "SELF") return "skill-self";
  if (t === "ALL_ENEMIES" || e === "AREA_DAMAGE") return "skill-aoe";
  if (t === "ENEMY") {
    if (e === "APPLY_STATUS") return "skill-debuff";
    return "skill-attack";
  }
  if (t === "ALLY" || t === "ALL_ALLIES") {
    if (e === "DIRECT_HEAL") return "skill-heal";
    return "skill-buff";
  }
  return "skill-attack";
}

interface PanelDef {
  panel: PopoutPanel;
  label: string;
  icon: React.ReactNode;
}

const PANELS: PanelDef[] = [
  { panel: "character", label: "Character", icon: <CharacterAvatarIcon className="vbar-icon" /> },
  { panel: "inventory", label: "Inventory", icon: <EquipmentIcon className="vbar-icon" /> },
  { panel: "spellbook", label: "Spellbook", icon: <SpellbookIcon className="vbar-icon" /> },
  { panel: "quests", label: "Quests", icon: <QuestsTabIcon className="vbar-icon" /> },
  { panel: "chat", label: "Social", icon: <ChatBubbleIcon className="vbar-icon" /> },
  { panel: "crafting", label: "Crafting", icon: <CraftingIcon className="vbar-icon" /> },
  { panel: "bank", label: "Bank", icon: <BankIcon className="vbar-icon" /> },
  { panel: "auction", label: "Auction", icon: <AuctionIcon className="vbar-icon" /> },
  { panel: "mail", label: "Mail", icon: <MailIcon className="vbar-icon" /> },
  { panel: "housing", label: "Housing", icon: <HousingIcon className="vbar-icon" /> },
  { panel: "help", label: "Help", icon: <HelpIcon className="vbar-icon" /> },
];

interface VitalsBarProps {
  connected: boolean;
  loggedIn: boolean;
  vitals: Vitals;
  quickbarSlots: (SkillSummary | null)[];
  activePopout: PopoutPanel;
  onOpenPanel: (panel: PopoutPanel) => void;
  onCastSkill: (skillId: string, cooldownMs: number) => void;
  onCommand: (cmd: string) => void;
}

function SkillSlot({ skill, index, onCast }: { skill: SkillSummary; index: number; onCast: (id: string, cd: number) => void }) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (skill.cooldownRemainingMs <= 0) return;
    const interval = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(interval);
  }, [skill.cooldownRemainingMs, skill.receivedAt]);

  const elapsed = now - skill.receivedAt;
  const remaining = skill.cooldownRemainingMs > 0 ? Math.max(0, skill.cooldownRemainingMs - elapsed) : 0;
  const onCooldown = remaining > 0;
  const fraction = onCooldown && skill.cooldownMs > 0 ? remaining / skill.cooldownMs : 0;

  return (
    <button
      type="button"
      className={`vbar-skill ${skillCategory(skill)}${onCooldown ? " vbar-skill-cd" : ""}`}
      disabled={onCooldown}
      onClick={() => onCast(skill.id, skill.cooldownMs)}
      aria-label={`${skill.name} — key ${index + 1}`}
    >
      {skill.image
        ? <img src={skill.image} alt="" className="vbar-skill-img" draggable={false} />
        : <SkillCastIcon className="vbar-skill-icon" classRestriction={skill.classRestriction} targetType={skill.targetType} />
      }
      {onCooldown && <span className="vbar-skill-sweep" style={{ height: `${fraction * 100}%` }} />}
      <span className="vbar-skill-key">{index + 1}</span>
    </button>
  );
}

export function VitalsBar({ connected, loggedIn, vitals, quickbarSlots, activePopout, onOpenPanel, onCastSkill, onCommand }: VitalsBarProps) {
  const [showPanels, setShowPanels] = useState(false);
  const [showInput, setShowInput] = useState(false);
  const [inputValue, setInputValue] = useState("");

  const hasAnySkill = quickbarSlots.some((s) => s !== null);

  const submitInput = (e: FormEvent) => {
    e.preventDefault();
    const cmd = inputValue.trim();
    if (!cmd) return;
    onCommand(cmd);
    setInputValue("");
  };

  return (
    <nav className="vbar" aria-label="Action bar">
      {/* Vitals row */}
      {loggedIn && (
        <div className="vbar-vitals">
          <div className="vbar-vital" title={`HP: ${vitals.hp}/${vitals.maxHp}`}>
            <span className="vbar-vital-label">HP</span>
            <div className="vbar-vital-track">
              <span className="vbar-vital-fill vbar-vital-hp" style={{ width: `${percent(vitals.hp, vitals.maxHp)}%` }} />
            </div>
            <span className="vbar-vital-text">{vitals.hp}/{vitals.maxHp}</span>
          </div>
          <div className="vbar-vital" title={`MP: ${vitals.mana}/${vitals.maxMana}`}>
            <span className="vbar-vital-label">MP</span>
            <div className="vbar-vital-track">
              <span className="vbar-vital-fill vbar-vital-mp" style={{ width: `${percent(vitals.mana, vitals.maxMana)}%` }} />
            </div>
            <span className="vbar-vital-text">{vitals.mana}/{vitals.maxMana}</span>
          </div>
          <div className="vbar-gold">
            <span className="vbar-gold-coin" />
            <span className="vbar-gold-text">{vitals.gold.toLocaleString()}</span>
          </div>
        </div>
      )}

      {/* Quickbar skills (horizontal scroll) */}
      {loggedIn && hasAnySkill && (
        <div className="vbar-skills">
          {quickbarSlots.map((skill, i) =>
            skill ? (
              <SkillSlot key={skill.id} skill={skill} index={i} onCast={onCastSkill} />
            ) : (
              <div key={`empty-${i}`} className="vbar-skill vbar-skill-empty">
                <span className="vbar-skill-key">{i + 1}</span>
              </div>
            ),
          )}
        </div>
      )}

      {/* Mobile-only row: panels-menu toggle + connection status */}
      <div className="vbar-actions">
        {loggedIn && (
          <button
            type="button"
            className={`vbar-btn vbar-btn-menu${showPanels ? " vbar-btn-active" : ""}`}
            onClick={() => setShowPanels(!showPanels)}
            aria-label="Toggle panels menu"
          >
            <svg viewBox="0 0 24 24" className="vbar-icon" fill="none" stroke="currentColor" strokeWidth="2">
              <rect x="3" y="3" width="7" height="7" rx="1.5" />
              <rect x="14" y="3" width="7" height="7" rx="1.5" />
              <rect x="3" y="14" width="7" height="7" rx="1.5" />
              <rect x="14" y="14" width="7" height="7" rx="1.5" />
            </svg>
          </button>
        )}

        <span className={`vbar-status${connected ? " vbar-status-on" : ""}`}>
          {connected ? (loggedIn ? "" : "Log in to play") : "Disconnected"}
        </span>
      </div>

      {/* Panel grid — always rendered; hidden by CSS on mobile unless expanded */}
      <div className={`vbar-panel-grid${showPanels ? " vbar-panel-grid-open" : ""}`}>
        {PANELS.map(({ panel, label, icon }) => (
          <button
            key={panel}
            type="button"
            className={`vbar-panel-btn${activePopout === panel ? " vbar-panel-btn-active" : ""}`}
            onClick={() => { onOpenPanel(panel); setShowPanels(false); }}
            aria-label={label}
          >
            {icon}
            <span className="vbar-panel-label">{label}</span>
          </button>
        ))}
        {/* Command button — toggles the inline text input below */}
        <button
          type="button"
          className={`vbar-panel-btn${showInput ? " vbar-panel-btn-active" : ""}`}
          onClick={() => setShowInput(!showInput)}
          aria-label="Type a command"
        >
          <svg viewBox="0 0 24 24" className="vbar-icon" fill="none" stroke="currentColor" strokeWidth="2">
            <rect x="2" y="4" width="20" height="16" rx="3" />
            <line x1="6" y1="10" x2="18" y2="10" />
            <line x1="6" y1="14" x2="14" y2="14" />
          </svg>
          <span className="vbar-panel-label">Command</span>
        </button>
      </div>

      {/* Expandable text input — appears below the grid when "Command" button is toggled */}
      {showInput && (
        <form className="vbar-input-row" onSubmit={submitInput}>
          <input
            type="text"
            className="vbar-input"
            placeholder="Type a command..."
            value={inputValue}
            onChange={(e) => setInputValue(e.target.value)}
            autoFocus
          />
          <button type="submit" className="vbar-input-send" aria-label="Send">
            <svg viewBox="0 0 24 24" className="vbar-icon" fill="currentColor">
              <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" />
            </svg>
          </button>
        </form>
      )}
    </nav>
  );
}
