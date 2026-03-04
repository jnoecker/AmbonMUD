import { useEffect, useState } from "react";
import type { CombatTarget, RoomMob, SkillSummary, Vitals } from "../../types";
import { percent } from "../../utils";
import { CrosshairIcon, FleeIcon, RefreshIcon, SkillCastIcon } from "../Icons";

interface CombatPanelProps {
  connected: boolean;
  hasRoomDetails: boolean;
  combatTarget: CombatTarget | null;
  vitals: Vitals;
  skills: SkillSummary[];
  mobs: RoomMob[];
  onCastSkill: (skillId: string, cooldownMs: number) => void;
  onRefreshSkills: () => void;
  onFlee: () => void;
  onAttackMob: (mobName: string) => void;
}

export function CombatPanel({
  connected,
  hasRoomDetails,
  combatTarget,
  vitals,
  skills,
  mobs,
  onCastSkill,
  onRefreshSkills,
  onFlee,
  onAttackMob,
}: CombatPanelProps) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const interval = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(interval);
  }, []);

  const hasTarget = combatTarget !== null && combatTarget.targetId !== null;
  const targetHp = combatTarget?.targetHp ?? 0;
  const targetMaxHp = combatTarget?.targetMaxHp ?? 1;

  return (
    <article className="subpanel combat-panel" aria-label="Combat">
      {/* Target section */}
      <div className="combat-target-card">
        {hasTarget ? (
          <>
            <div className="combat-target-info">
              {combatTarget.targetImage ? (
                <img src={combatTarget.targetImage} alt="" className="combat-target-thumb" />
              ) : (
                <span className="combat-target-thumb combat-target-thumb-placeholder">
                  <CrosshairIcon className="combat-target-thumb-icon" />
                </span>
              )}
              <div className="combat-target-details">
                <span className="combat-target-name">{combatTarget.targetName}</span>
                <span className="combat-target-hp-text">{targetHp} / {targetMaxHp}</span>
              </div>
            </div>
            <div className="meter-track">
              <span className="meter-fill meter-fill-hp" style={{ width: `${percent(targetHp, targetMaxHp)}%` }} />
            </div>
          </>
        ) : (
          <div className="combat-in-combat-badge">
            <CrosshairIcon className="combat-badge-icon" />
            <span>In Combat</span>
          </div>
        )}
      </div>

      {/* Compact mob picker for switching targets */}
      {mobs.length > 1 && (
        <div className="combat-target-select">
          {mobs.map((mob) => {
            const isCurrentTarget = combatTarget?.targetId === mob.id;
            return (
              <button
                key={mob.id}
                type="button"
                className={`combat-target-option ${isCurrentTarget ? "combat-target-option-active" : ""}`}
                title={isCurrentTarget ? `Currently targeting ${mob.name}` : `Switch target to ${mob.name}`}
                disabled={isCurrentTarget}
                onClick={() => onAttackMob(mob.name)}
              >
                <span className="combat-target-option-name">{mob.name}</span>
                <span className="combat-target-option-hp">{mob.hp}/{mob.maxHp}</span>
              </button>
            );
          })}
        </div>
      )}

      {/* Ability grid */}
      {skills.length > 0 && (
        <div className="combat-ability-grid">
          {skills.map((skill) => {
            const elapsed = Math.max(0, now - skill.receivedAt);
            const remainingMs = Math.max(0, skill.cooldownRemainingMs - elapsed);
            const cooldownSeconds = Math.ceil(remainingMs / 1000);
            const isReady = remainingMs <= 0;
            const hasEnoughMana = vitals.mana >= skill.manaCost;
            const isDisabled = !isReady || !hasEnoughMana;
            const cooldownFraction = skill.cooldownMs > 0 ? remainingMs / skill.cooldownMs : 0;

            return (
              <button
                key={skill.id}
                type="button"
                className={`combat-ability-button ${isDisabled ? "combat-ability-button-disabled" : ""}`}
                title={
                  !isReady
                    ? `${skill.name} — ${cooldownSeconds}s cooldown`
                    : !hasEnoughMana
                      ? `${skill.name} — not enough mana (${skill.manaCost})`
                      : `Cast ${skill.name} (${skill.manaCost} mana)`
                }
                disabled={isDisabled}
                onClick={() => onCastSkill(skill.id, skill.cooldownMs)}
              >
                <span className="combat-ability-icon-wrap">
                  <SkillCastIcon
                    className="combat-ability-icon"
                    classRestriction={skill.classRestriction}
                    targetType={skill.targetType}
                  />
                </span>
                <span className="combat-ability-label">
                  <span className="combat-ability-name">{skill.name}</span>
                  <span className="combat-ability-cost">{skill.manaCost}m</span>
                </span>
                {!isReady && (
                  <span
                    className="combat-ability-cooldown-bar"
                    style={{ width: `${cooldownFraction * 100}%` }}
                  />
                )}
              </button>
            );
          })}
        </div>
      )}

      {/* Action bar */}
      <div className="combat-action-bar">
        <button
          type="button"
          className="combat-flee-button"
          title="Flee from combat"
          onClick={onFlee}
        >
          <FleeIcon className="combat-action-icon" />
          <span>Flee</span>
        </button>
        <button
          type="button"
          className="soft-button"
          onClick={onRefreshSkills}
          disabled={!connected || !hasRoomDetails}
          title="Refresh skills"
        >
          <RefreshIcon className="combat-action-icon" />
          <span>Skills</span>
        </button>
      </div>
    </article>
  );
}
