import { useMemo, useState } from "react";
import type { SkillSummary } from "../types";

type Category = "ALL" | "DAMAGE" | "HEAL" | "BUFF" | "DEBUFF" | "UTILITY";

const CATEGORIES: { key: Category; label: string }[] = [
  { key: "ALL", label: "All" },
  { key: "DAMAGE", label: "Damage" },
  { key: "HEAL", label: "Heal" },
  { key: "BUFF", label: "Buff" },
  { key: "DEBUFF", label: "Debuff" },
  { key: "UTILITY", label: "Utility" },
];

const SLOT_COUNT = 9;

interface SpellbookPanelProps {
  skills: SkillSummary[];
  quickbarSlotIds: (string | null)[];
  onShowSkillInfo: (skill: SkillSummary) => void;
  onAssignSlot: (slotIndex: number, skillId: string) => void;
  playerClass: string;
  playerLevel: number;
  availableSkillPoints?: number;
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

function skillCategory(skill: SkillSummary): Category {
  const t = skill.targetType.toUpperCase();
  const e = skill.effectType.toUpperCase();
  if (e === "DIRECT_DAMAGE" || e === "AREA_DAMAGE") return "DAMAGE";
  if (e === "DIRECT_HEAL") return "HEAL";
  if (e === "APPLY_STATUS") {
    if (t === "ENEMY" || t === "ALL_ENEMIES") return "DEBUFF";
    return "BUFF";
  }
  // Taunt and threat-shaping abilities live under UTILITY.
  return "UTILITY";
}

function prettyClass(className: string): string {
  if (className.length === 0) return "";
  return className.charAt(0).toUpperCase() + className.slice(1).toLowerCase();
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

  const isPet = skill.source === "pet";
  return (
    <div className="spellbook-card-wrapper">
      <button
        type="button"
        className={`spellbook-card${isPet ? " spellbook-card-pet" : ""}`}
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
            {isPet ? (
              <span className="spellbook-stat spellbook-stat-pet" title="Pet skill — triggered via `pet &lt;skill&gt;`">
                Pet
              </span>
            ) : (
              <span className="spellbook-stat spellbook-stat-mana">{skill.manaCost} MP</span>
            )}
            <span className="spellbook-stat spellbook-stat-cd">CD: {cooldownLabel(skill.cooldownMs)}</span>
            <span className="spellbook-stat spellbook-stat-target">{targetLabel(skill.targetType)}</span>
            {!isPet && (
              <span className="spellbook-stat spellbook-stat-level">Lv {skill.levelRequired}</span>
            )}
            {skill.classRestriction && (
              <span className="spellbook-stat spellbook-stat-class">{prettyClass(skill.classRestriction)}</span>
            )}
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
        <div
          className="spellbook-slot-picker"
          role="menu"
          aria-label="Assign to quickbar slot"
          onKeyDown={(e) => { if (e.key === "Escape") { e.stopPropagation(); setShowSlotPicker(false); } }}
        >
          {Array.from({ length: SLOT_COUNT }, (_, i) => (
            <button
              key={i}
              type="button"
              role="menuitem"
              className="spellbook-slot-pick"
              autoFocus={i === 0}
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
  availableSkillPoints,
}: SpellbookPanelProps) {
  const [activeCategory, setActiveCategory] = useState<Category>("ALL");
  const [activeClass, setActiveClass] = useState<string>("ALL");
  const [query, setQuery] = useState("");

  // Map skill ID → slot number (1-based) for badge display
  const skillSlotMap = useMemo(() => {
    const m = new Map<string, number>();
    for (let i = 0; i < quickbarSlotIds.length; i++) {
      const id = quickbarSlotIds[i];
      if (id) m.set(id, i + 1);
    }
    return m;
  }, [quickbarSlotIds]);

  const availableCategories = useMemo(() => {
    const present = new Set(skills.map((s) => skillCategory(s)));
    return CATEGORIES.filter((c) => c.key === "ALL" || present.has(c.key));
  }, [skills]);

  const availableClasses = useMemo(() => {
    const set = new Set<string>();
    for (const s of skills) {
      if (s.classRestriction) set.add(s.classRestriction);
    }
    return Array.from(set).sort();
  }, [skills]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return skills.filter((s) => {
      if (activeCategory !== "ALL" && skillCategory(s) !== activeCategory) return false;
      if (activeClass !== "ALL" && s.classRestriction !== activeClass) return false;
      if (q.length > 0 && !s.name.toLowerCase().includes(q)) return false;
      return true;
    });
  }, [skills, activeCategory, activeClass, query]);

  if (skills.length === 0) {
    return (
      <div className="spellbook-empty">
        <p>No abilities learned yet.</p>
        <p className="spellbook-empty-hint">Visit a class trainer and spend skill points to learn abilities.</p>
        {availableSkillPoints !== undefined && availableSkillPoints > 0 && (
          <p className="spellbook-empty-hint spellbook-sp-hint">
            You have <strong>{availableSkillPoints}</strong> skill point{availableSkillPoints !== 1 ? "s" : ""} ready to spend!
          </p>
        )}
      </div>
    );
  }

  return (
    <div className="spellbook">
      <div className="spellbook-header">
        <span className="spellbook-class">{playerClass} Spellbook</span>
        <span className="spellbook-level">Level {playerLevel}</span>
        {availableSkillPoints !== undefined && (
          <span
            className={`spellbook-sp-badge${availableSkillPoints > 0 ? " spellbook-sp-badge-available" : ""}`}
            title="Available skill points"
          >
            {availableSkillPoints} SP
          </span>
        )}
      </div>
      <p className="spellbook-hint">Click a spell for info. Press + to assign to quickbar (keys 1-9).</p>
      <div className="spellbook-search">
        <input
          type="search"
          className="spellbook-search-input"
          placeholder="Search abilities…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Search abilities by name"
        />
        <span className="spellbook-result-count" aria-live="polite">
          {filtered.length} / {skills.length}
        </span>
      </div>
      {availableCategories.length > 2 && (
        <div className="spellbook-tabs" role="tablist" aria-label="Filter by ability type">
          {availableCategories.map((cat) => (
            <button
              key={cat.key}
              type="button"
              role="tab"
              className={`spellbook-tab${activeCategory === cat.key ? " spellbook-tab-active" : ""}`}
              aria-selected={activeCategory === cat.key}
              onClick={() => setActiveCategory(cat.key)}
            >
              {cat.label}
            </button>
          ))}
        </div>
      )}
      {availableClasses.length > 1 && (
        <div className="spellbook-class-pills" role="tablist" aria-label="Filter by source class">
          <button
            type="button"
            role="tab"
            className={`spellbook-class-pill${activeClass === "ALL" ? " spellbook-class-pill-active" : ""}`}
            aria-selected={activeClass === "ALL"}
            onClick={() => setActiveClass("ALL")}
          >
            All classes
          </button>
          {availableClasses.map((cls) => (
            <button
              key={cls}
              type="button"
              role="tab"
              className={`spellbook-class-pill${activeClass === cls ? " spellbook-class-pill-active" : ""}`}
              aria-selected={activeClass === cls}
              onClick={() => setActiveClass(cls)}
            >
              {prettyClass(cls)}
            </button>
          ))}
        </div>
      )}
      {filtered.length === 0 ? (
        <div className="spellbook-empty">
          <p>No abilities match the current filters.</p>
          <p className="spellbook-empty-hint">Try clearing the search or switching tabs.</p>
        </div>
      ) : (
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
      )}
    </div>
  );
}
