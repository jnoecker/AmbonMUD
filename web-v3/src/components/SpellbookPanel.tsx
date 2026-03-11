import { useMemo, useState } from "react";
import type { SkillSummary } from "../types";

type TargetTab = "ALL" | "ENEMY" | "SELF" | "ALLY" | "ALL_ENEMIES";
const TARGET_TABS: { key: TargetTab; label: string }[] = [
  { key: "ALL", label: "All" },
  { key: "ENEMY", label: "Enemy" },
  { key: "ALL_ENEMIES", label: "AoE" },
  { key: "SELF", label: "Self" },
  { key: "ALLY", label: "Ally" },
];

const SLOT_COUNT = 9;

interface SpellbookPanelProps {
  skills: SkillSummary[];
  quickbarSlotIds: (string | null)[];
  onShowSkillInfo: (skill: SkillSummary) => void;
  onAssignSlot: (slotIndex: number, skillId: string) => void;
  playerClass: string;
  playerLevel: number;
}

function cooldownLabel(ms: number): string {
  if (ms <= 0) return "None";
  const secs = ms / 1000;
  return secs >= 60 ? `${Math.round(secs / 60)}m` : `${secs}s`;
}

function targetLabel(t: string): string {
  switch (t) {
    case "ENEMY": return "Enemy";
    case "SELF": return "Self";
    case "ALLY": return "Ally";
    default: return t;
  }
}

function SkillCard({
  skill,
  slotNumber,
  onShowInfo,
  onAssignSlot,
}: {
  skill: SkillSummary;
  slotNumber: number | null;
  onShowInfo: (skill: SkillSummary) => void;
  onAssignSlot: (slotIndex: number, skillId: string) => void;
}) {
  const [showSlotPicker, setShowSlotPicker] = useState(false);

  return (
    <div className="spellbook-card-wrapper">
      <button
        type="button"
        className="spellbook-card"
        onClick={() => onShowInfo(skill)}
        title={`${skill.name} — click for info`}
      >
        <div className="spellbook-card-icon">
          {skill.image ? (
            <img src={skill.image} alt="" className="spellbook-card-image" />
          ) : (
            <div className="spellbook-card-placeholder" />
          )}
        </div>
        <div className="spellbook-card-info">
          <span className="spellbook-card-name">{skill.name}</span>
          <span className="spellbook-card-desc">{skill.description}</span>
          <div className="spellbook-card-stats">
            <span className="spellbook-stat spellbook-stat-mana">{skill.manaCost} MP</span>
            <span className="spellbook-stat spellbook-stat-cd">CD: {cooldownLabel(skill.cooldownMs)}</span>
            <span className="spellbook-stat spellbook-stat-target">{targetLabel(skill.targetType)}</span>
            <span className="spellbook-stat spellbook-stat-level">Lv {skill.levelRequired}</span>
          </div>
        </div>
      </button>
      <button
        type="button"
        className={`spellbook-assign-btn${showSlotPicker ? " spellbook-assign-btn-active" : ""}`}
        title={slotNumber !== null ? `Quickbar slot ${slotNumber}` : "Assign to quickbar"}
        onClick={() => setShowSlotPicker(!showSlotPicker)}
      >
        {slotNumber !== null ? slotNumber : "+"}
      </button>
      {showSlotPicker && (
        <div className="spellbook-slot-picker">
          {Array.from({ length: SLOT_COUNT }, (_, i) => (
            <button
              key={i}
              type="button"
              className="spellbook-slot-pick"
              onClick={() => {
                onAssignSlot(i, skill.id);
                setShowSlotPicker(false);
              }}
            >
              {i + 1}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

export function SpellbookPanel({
  skills,
  quickbarSlotIds,
  onShowSkillInfo,
  onAssignSlot,
  playerClass,
  playerLevel,
}: SpellbookPanelProps) {
  const [activeTab, setActiveTab] = useState<TargetTab>("ALL");

  // Map skill ID → slot number (1-based) for badge display
  const skillSlotMap = useMemo(() => {
    const m = new Map<string, number>();
    for (let i = 0; i < quickbarSlotIds.length; i++) {
      const id = quickbarSlotIds[i];
      if (id) m.set(id, i + 1);
    }
    return m;
  }, [quickbarSlotIds]);

  const availableTabs = useMemo(() => {
    const types = new Set(skills.map((s) => s.targetType));
    return TARGET_TABS.filter((t) => t.key === "ALL" || types.has(t.key));
  }, [skills]);

  const filtered = useMemo(
    () => activeTab === "ALL" ? skills : skills.filter((s) => s.targetType === activeTab),
    [skills, activeTab],
  );

  if (skills.length === 0) {
    return (
      <div className="spellbook-empty">
        <p>No abilities learned yet.</p>
        <p className="spellbook-empty-hint">Gain levels to unlock new abilities for your class.</p>
      </div>
    );
  }

  return (
    <div className="spellbook">
      <div className="spellbook-header">
        <span className="spellbook-class">{playerClass} Spellbook</span>
        <span className="spellbook-level">Level {playerLevel}</span>
      </div>
      {availableTabs.length > 2 && (
        <div className="spellbook-tabs" role="tablist">
          {availableTabs.map((tab) => (
            <button
              key={tab.key}
              type="button"
              role="tab"
              className={`spellbook-tab${activeTab === tab.key ? " spellbook-tab-active" : ""}`}
              aria-selected={activeTab === tab.key}
              onClick={() => setActiveTab(tab.key)}
            >
              {tab.label}
            </button>
          ))}
        </div>
      )}
      <div className="spellbook-grid">
        {filtered.map((skill) => (
          <SkillCard
            key={skill.id}
            skill={skill}
            slotNumber={skillSlotMap.get(skill.id) ?? null}
            onShowInfo={onShowSkillInfo}
            onAssignSlot={onAssignSlot}
          />
        ))}
      </div>
    </div>
  );
}
