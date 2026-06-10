import { useEffect, useState } from "react";
import type { DragEvent } from "react";
import type { SkillSummary } from "../types";
import { SkillCastIcon } from "./Icons";

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

function useSkillCooldown(skill: SkillSummary): { onCooldown: boolean; fraction: number } {
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
  return { onCooldown, fraction };
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
  const { onCooldown, fraction } = useSkillCooldown(skill);
  const cooldownSeconds = Math.ceil((fraction * skill.cooldownMs) / 1000);

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
      aria-label={`${skill.name}, ${skill.manaCost} mana, key ${index + 1}${onCooldown ? `, on cooldown, ${cooldownSeconds} seconds remaining` : ""}`}
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

function PetSkillSlot({ skill, index, onCast }: { skill: SkillSummary; index: number; onCast: (id: string, cd: number) => void }) {
  const { onCooldown, fraction } = useSkillCooldown(skill);
  const cooldownSeconds = Math.ceil((fraction * skill.cooldownMs) / 1000);

  return (
    <button
      type="button"
      className={`vbar-skill vbar-pet-skill${onCooldown ? " vbar-skill-cd" : ""}`}
      disabled={onCooldown}
      title={`${skill.name} (pet) — Shift+${index + 1}`}
      onClick={() => onCast(skill.id, skill.cooldownMs)}
      aria-label={`${skill.name} pet skill, Shift+${index + 1}${onCooldown ? `, on cooldown, ${cooldownSeconds} seconds remaining` : ""}`}
    >
      {skill.image
        ? <img src={skill.image} alt="" className="vbar-skill-img" draggable={false} />
        : <SkillCastIcon className="vbar-skill-icon" classRestriction={null} targetType={skill.targetType} />
      }
      {onCooldown && <span className="vbar-skill-sweep" style={{ height: `${fraction * 100}%` }} />}
      <span className="vbar-skill-key">{`⇧${index + 1}`}</span>
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

interface SkillBarProps {
  quickbarSlots: (SkillSummary | null)[];
  petSkills: SkillSummary[];
  onCastSkill: (skillId: string, cooldownMs: number) => void;
  onQuickbarSwap: (fromIndex: number, toIndex: number) => void;
  onQuickbarAssign: (slotIndex: number, skillId: string) => void;
  onQuickbarClear: (slotIndex: number) => void;
}

/**
 * Quickbar + pet skill bar, overlaid bottom-center on the canvas. Extracted
 * from the retired bottom action bar; reuses the existing .vbar-skill styling.
 */
export function SkillBar({
  quickbarSlots,
  petSkills,
  onCastSkill,
  onQuickbarSwap,
  onQuickbarAssign,
  onQuickbarClear,
}: SkillBarProps) {
  const hasAnySkill = quickbarSlots.some((s) => s !== null);
  const hasPet = petSkills.length > 0;
  if (!hasAnySkill && !hasPet) return null;

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
    <div className="skill-bar" role="group" aria-label="Skill bar">
      {hasAnySkill && quickbarSlots.map((skill, i) =>
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
      {hasPet && (
        <>
          {hasAnySkill && <span className="vbar-petbar-divider" aria-hidden="true" />}
          <div className="vbar-petbar" role="group" aria-label="Pet skills">
            {petSkills.map((skill, i) => (
              <PetSkillSlot key={skill.id} skill={skill} index={i} onCast={onCastSkill} />
            ))}
          </div>
        </>
      )}
    </div>
  );
}
