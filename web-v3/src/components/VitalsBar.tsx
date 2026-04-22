import { useEffect, useRef, useState } from "react";
import type { DragEvent, FormEvent, KeyboardEvent } from "react";
import type { PopoutPanel, SkillSummary, Vitals } from "../types";
import type { AudioEngine } from "../hooks/useAudioEngine";
import { AudioControls } from "./AudioControls";
import { percent } from "../utils";
import {
  CharacterAvatarIcon,
  EquipmentIcon,
  ChatBubbleIcon,
  AuctionIcon,
  CraftingIcon,
  DungeonIcon,
  HousingIcon,
  MailIcon,
  HelpIcon,
  ScoreTabIcon,
  SpellbookIcon,
  QuestsTabIcon,
  SkillCastIcon,
  AttackIcon,
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
  { panel: "combatlog", label: "Combat Log", icon: <AttackIcon className="vbar-icon" /> },
  { panel: "chat", label: "Social", icon: <ChatBubbleIcon className="vbar-icon" /> },
  { panel: "crafting", label: "Crafting", icon: <CraftingIcon className="vbar-icon" /> },
  { panel: "auction", label: "Auction", icon: <AuctionIcon className="vbar-icon" /> },
  { panel: "mail", label: "Mail", icon: <MailIcon className="vbar-icon" /> },
  { panel: "housing", label: "Housing", icon: <HousingIcon className="vbar-icon" /> },
  { panel: "leaderboard", label: "Leaderboard", icon: <ScoreTabIcon className="vbar-icon" /> },
  { panel: "help", label: "Help", icon: <HelpIcon className="vbar-icon" /> },
];

interface VitalsBarProps {
  connected: boolean;
  loggedIn: boolean;
  vitals: Vitals;
  quickbarSlots: (SkillSummary | null)[];
  onQuickbarSwap: (fromIndex: number, toIndex: number) => void;
  onQuickbarAssign: (slotIndex: number, skillId: string) => void;
  onQuickbarClear: (slotIndex: number) => void;
  activePopout: PopoutPanel;
  onOpenPanel: (panel: PopoutPanel) => void;
  onCastSkill: (skillId: string, cooldownMs: number) => void;
  onCommand: (cmd: string) => void;
  onReconnect?: () => void;
  dungeonActive: boolean;
  audio: AudioEngine;
  inputValue: string;
  onInputChange: (value: string) => void;
  onInputKeyDown?: (event: KeyboardEvent<HTMLInputElement>) => void;
  onHeightChange?: (height: number) => void;
  /** Pulse the Inventory panel button to draw first-time player attention. */
  inventoryHint?: boolean;
}

interface SkillSlotProps {
  skill: SkillSummary;
  index: number;
  onCast: (id: string, cd: number) => void;
  onDragStart: (event: DragEvent<HTMLButtonElement>, index: number) => void;
  onDragOver: (event: DragEvent<HTMLElement>) => void;
  onDragLeave: (event: DragEvent<HTMLElement>) => void;
  onDrop: (event: DragEvent<HTMLElement>, index: number) => void;
  onClear: (index: number) => void;
}

function SkillSlot({ skill, index, onCast, onDragStart, onDragOver, onDragLeave, onDrop, onClear }: SkillSlotProps) {
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
      title={`${skill.name} (${skill.manaCost} mana) — key ${index + 1}\nDrag to reorder, right-click to remove`}
      onClick={() => onCast(skill.id, skill.cooldownMs)}
      onContextMenu={(e) => { e.preventDefault(); onClear(index); }}
      draggable
      onDragStart={(e) => onDragStart(e, index)}
      onDragOver={onDragOver}
      onDragLeave={onDragLeave}
      onDrop={(e) => onDrop(e, index)}
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

function EmptySkillSlot({
  index,
  onDragOver,
  onDragLeave,
  onDrop,
}: {
  index: number;
  onDragOver: (event: DragEvent<HTMLElement>) => void;
  onDragLeave: (event: DragEvent<HTMLElement>) => void;
  onDrop: (event: DragEvent<HTMLElement>, index: number) => void;
}) {
  return (
    <div
      className="vbar-skill vbar-skill-empty"
      title={`Slot ${index + 1} — drag a spell here`}
      onDragOver={onDragOver}
      onDragLeave={onDragLeave}
      onDrop={(e) => onDrop(e, index)}
    >
      <span className="vbar-skill-key">{index + 1}</span>
    </div>
  );
}

export function VitalsBar({
  connected,
  loggedIn,
  vitals,
  quickbarSlots,
  onQuickbarSwap,
  onQuickbarAssign,
  onQuickbarClear,
  activePopout,
  onOpenPanel,
  onCastSkill,
  onCommand,
  onReconnect,
  dungeonActive,
  audio,
  inputValue,
  onInputChange,
  onInputKeyDown,
  onHeightChange,
  inventoryHint = false,
}: VitalsBarProps) {
  const [showPanels, setShowPanels] = useState(false);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const navRef = useRef<HTMLElement | null>(null);

  const hasAnySkill = quickbarSlots.some((s) => s !== null);

  useEffect(() => {
    const node = navRef.current;
    if (!node || !onHeightChange) return;

    const reportHeight = () => {
      onHeightChange(Math.ceil(node.getBoundingClientRect().height));
    };

    reportHeight();

    const resizeObserver = new ResizeObserver(() => {
      reportHeight();
    });
    resizeObserver.observe(node);

    return () => {
      resizeObserver.disconnect();
    };
  }, [onHeightChange, loggedIn, hasAnySkill, showPanels, connected]);

  const submitInput = (e: FormEvent) => {
    e.preventDefault();
    const cmd = inputValue.trim();
    if (!cmd) return;
    onCommand(cmd);
    onInputChange("");
  };

  // Drag-and-drop handlers for the quickbar
  const handleSkillDragStart = (e: DragEvent<HTMLButtonElement>, index: number) => {
    e.dataTransfer.setData("quickbar-index", String(index));
    e.dataTransfer.effectAllowed = "move";
  };

  const handleSkillDragOver = (e: DragEvent<HTMLElement>) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = "move";
    const target = e.currentTarget as HTMLElement;
    if (!target.classList.contains("drag-over")) target.classList.add("drag-over");
  };

  const handleSkillDragLeave = (e: DragEvent<HTMLElement>) => {
    (e.currentTarget as HTMLElement).classList.remove("drag-over");
  };

  const handleSkillDrop = (e: DragEvent<HTMLElement>, toIndex: number) => {
    e.preventDefault();
    (e.currentTarget as HTMLElement).classList.remove("drag-over");
    const fromIndexStr = e.dataTransfer.getData("quickbar-index");
    if (fromIndexStr) {
      onQuickbarSwap(parseInt(fromIndexStr, 10), toIndex);
      return;
    }
    const skillId = e.dataTransfer.getData("skill-id");
    if (skillId) {
      onQuickbarAssign(toIndex, skillId);
    }
  };

  return (
    <nav ref={navRef} className="vbar" aria-label="Action bar">
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
          <AudioControls audio={audio} />
        </div>
      )}

      {/* Quickbar skills (horizontal scroll) */}
      {loggedIn && hasAnySkill && (
        <div className="vbar-skills">
          {quickbarSlots.map((skill, i) =>
            skill ? (
              <SkillSlot
                key={skill.id}
                skill={skill}
                index={i}
                onCast={onCastSkill}
                onDragStart={handleSkillDragStart}
                onDragOver={handleSkillDragOver}
                onDragLeave={handleSkillDragLeave}
                onDrop={handleSkillDrop}
                onClear={onQuickbarClear}
              />
            ) : (
              <EmptySkillSlot
                key={`empty-${i}`}
                index={i}
                onDragOver={handleSkillDragOver}
                onDragLeave={handleSkillDragLeave}
                onDrop={handleSkillDrop}
              />
            ),
          )}
        </div>
      )}

      {/* Mobile-only row: panels-menu toggle + connection status (also shown on desktop when disconnected) */}
      <div className={`vbar-actions${!connected ? " vbar-actions-show" : ""}`}>
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

        {!connected && onReconnect && (
          <button type="button" className="vbar-btn vbar-btn-reconnect" onClick={onReconnect}>
            Reconnect
          </button>
        )}
      </div>

      {/* Panel grid — always rendered; hidden by CSS on mobile unless expanded */}
      <div className={`vbar-panel-grid${showPanels ? " vbar-panel-grid-open" : ""}`}>
        {PANELS.map(({ panel, label, icon }) => {
          const isInventoryHint = inventoryHint && panel === "inventory" && activePopout !== "inventory";
          const classes = [
            "vbar-panel-btn",
            activePopout === panel ? "vbar-panel-btn-active" : "",
            isInventoryHint ? "vbar-panel-btn-hint" : "",
          ].filter(Boolean).join(" ");
          return (
            <button
              key={panel}
              type="button"
              className={classes}
              onClick={() => { onOpenPanel(panel); setShowPanels(false); }}
              aria-label={isInventoryHint ? `${label} (new item)` : label}
            >
              {icon}
              <span className="vbar-panel-label">{label}</span>
              {isInventoryHint && <span className="vbar-panel-btn-hint-dot" aria-hidden="true" />}
            </button>
          );
        })}
        {dungeonActive && (
          <button
            type="button"
            className={`vbar-panel-btn vbar-panel-btn-dungeon${activePopout === "dungeon" ? " vbar-panel-btn-active" : ""}`}
            onClick={() => { onOpenPanel("dungeon"); setShowPanels(false); }}
            aria-label="Dungeon"
          >
            <DungeonIcon className="vbar-icon" />
            <span className="vbar-panel-label">Dungeon</span>
          </button>
        )}
      </div>

      {/* Persistent command input — always available below the panel grid */}
      {loggedIn && (
        <form className="vbar-input-row" onSubmit={submitInput}>
          <input
            ref={inputRef}
            type="text"
            className="vbar-input"
            placeholder="Type a command..."
            value={inputValue}
            onChange={(e) => onInputChange(e.target.value)}
            onKeyDown={onInputKeyDown}
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
