import { useState } from "react";
import type { TrainerAbility, TrainerClass, TrainerData } from "../types";

function cooldownLabel(ms: number): string {
  if (ms <= 0) return "None";
  const secs = ms / 1000;
  return secs >= 60 ? `${Math.round(secs / 60)}m` : `${secs}s`;
}

function effectLabel(effectType: string): string {
  switch (effectType) {
    case "DIRECT_DAMAGE": return "Damage";
    case "AREA_DAMAGE": return "AoE";
    case "DIRECT_HEAL": return "Heal";
    case "BUFF": return "Buff";
    case "DEBUFF": return "Debuff";
    default: return effectType;
  }
}

function prettyClass(className: string): string {
  if (className.length === 0) return "";
  return className.charAt(0).toUpperCase() + className.slice(1).toLowerCase();
}

function AbilityRow({
  ability,
  onLearn,
  canAfford,
}: {
  ability: TrainerAbility;
  onLearn: (id: string) => void;
  canAfford: boolean;
}) {
  const isLocked = ability.locked;
  const canLearn = !isLocked && canAfford;
  const buttonTitle = isLocked
    ? ability.lockReason ?? "Requirements not met"
    : canAfford
      ? `Learn ${ability.name}`
      : "Not enough skill points";
  const rowClass = `trainer-ability-row${isLocked ? " trainer-ability-row-locked" : ""}`;
  return (
    <div className={rowClass}>
      <div className="trainer-ability-icon">
        {ability.image ? (
          <img src={ability.image} alt="" className="trainer-ability-img" />
        ) : (
          <div className="trainer-ability-placeholder" />
        )}
        {isLocked && (
          <span className="trainer-ability-lock-overlay" aria-hidden="true">
            {"🔒"}
          </span>
        )}
      </div>
      <div className="trainer-ability-info">
        <span className="trainer-ability-name">{ability.name}</span>
        <span className="trainer-ability-desc">{ability.description}</span>
        <div className="trainer-ability-meta">
          <span className="trainer-meta-tag">{effectLabel(ability.effectType)}</span>
          <span className="trainer-meta-tag">{ability.manaCost} MP</span>
          <span className="trainer-meta-tag">CD: {cooldownLabel(ability.cooldownMs)}</span>
          <span className={`trainer-meta-tag${isLocked ? " trainer-meta-tag-lock" : ""}`}>
            Lv {ability.levelRequired}
          </span>
          <span className="trainer-meta-tag">
            {ability.skillPointCost === 0
              ? "Auto"
              : `${ability.skillPointCost} SP`}
          </span>
        </div>
        {isLocked && ability.lockReason && (
          <span className="trainer-ability-lock-reason">{ability.lockReason}</span>
        )}
      </div>
      <button
        type="button"
        className={`trainer-learn-btn${canLearn ? "" : " trainer-learn-btn-disabled"}`}
        disabled={!canLearn}
        title={buttonTitle}
        aria-label={isLocked ? `${ability.name} locked — ${ability.lockReason ?? "requirements not met"}` : `Learn ${ability.name}`}
        onClick={() => onLearn(ability.id)}
      >
        {isLocked ? "Locked" : "Learn"}
      </button>
    </div>
  );
}

interface TrainerPanelProps {
  trainer: TrainerData;
  playerLevel: number;
  playerGold: number;
  onCommand: (cmd: string) => void;
}

export function TrainerPanel({ trainer, playerLevel, playerGold, onCommand }: TrainerPanelProps) {
  const hasPoints = trainer.availableSkillPoints > 0;
  const isMultiClass = trainer.classes.length > 1;

  // Selected class — defaults to the first class, or the first unlocked one.
  const initialClass = trainer.classes.find((c) => c.classUnlocked)?.className
    ?? trainer.classes[0]?.className
    ?? "";
  const [selectedClass, setSelectedClass] = useState<string>(initialClass);
  const [prevTrainerId, setPrevTrainerId] = useState<string>(trainer.trainerId);
  const [menuOpen, setMenuOpen] = useState(false);

  // Reset selection when the trainer changes (e.g. switching rooms).
  if (trainer.trainerId !== prevTrainerId) {
    setPrevTrainerId(trainer.trainerId);
    setSelectedClass(initialClass);
    setMenuOpen(false);
  }

  const activeClass: TrainerClass | undefined =
    trainer.classes.find((c) => c.className === selectedClass) ?? trainer.classes[0];

  if (!activeClass) {
    return (
      <div className="trainer-panel">
        <p className="trainer-empty">This trainer has no classes configured.</p>
      </div>
    );
  }

  const classLabel = prettyClass(activeClass.className);
  const unlockArg = isMultiClass ? ` ${activeClass.className.toLowerCase()}` : "";
  const unlockCmd = `train unlock${unlockArg}`;

  return (
    <div className="trainer-panel">
      {/* Centered wooden class plaque — the "topic" on the board. Click to swap
          class or multiclass. */}
      <div className="trainer-sign-wrap">
        <button
          type="button"
          className={`trainer-class-sign${menuOpen ? " trainer-class-sign-open" : ""}`}
          onClick={() => { if (isMultiClass) setMenuOpen((o) => !o); }}
          aria-haspopup={isMultiClass || undefined}
          aria-expanded={isMultiClass ? menuOpen : undefined}
          disabled={!isMultiClass}
          title={isMultiClass ? "Change class / multiclass" : classLabel}
        >
          <span className="trainer-class-sign-label">{classLabel}</span>
          {isMultiClass && <span className="trainer-class-sign-caret" aria-hidden="true">▾</span>}
        </button>

        {menuOpen && isMultiClass && (
          <div className="trainer-class-menu" role="menu" aria-label="Classes">
            {trainer.classes.map((c) => {
              const isActive = c.className === activeClass.className;
              return (
                <button
                  key={c.className}
                  type="button"
                  role="menuitemradio"
                  aria-checked={isActive}
                  className={`trainer-class-opt${isActive ? " trainer-class-opt-active" : ""}${c.classUnlocked ? "" : " trainer-class-opt-locked"}`}
                  onClick={() => { setSelectedClass(c.className); setMenuOpen(false); }}
                >
                  <span className="trainer-class-opt-name">{prettyClass(c.className)}</span>
                  {c.classUnlocked
                    ? (isActive && <span className="trainer-class-opt-check" aria-hidden="true">✓</span>)
                    : <span className="trainer-class-opt-lock" aria-hidden="true">{"🔒"}</span>}
                </button>
              );
            })}
            <p className="trainer-class-menu-hint">
              {trainer.multiclassUnlockedCount}/{trainer.multiclassMaxClasses} classes · multiclass at Lv {trainer.multiclassMinLevel}
            </p>
          </div>
        )}
      </div>

      {/* Skill points, chalked onto the board. */}
      <div className={`trainer-sp-chalk${hasPoints ? " trainer-sp-chalk-has" : ""}`}>
        <span className="trainer-sp-chalk-value">{trainer.availableSkillPoints}</span>
        <span className="trainer-sp-chalk-label">
          skill point{trainer.availableSkillPoints === 1 ? "" : "s"} to spend
        </span>
      </div>

      {!activeClass.classUnlocked ? (
        <div className="trainer-locked">
          <p className="trainer-locked-msg">
            The <strong>{classLabel}</strong> class is locked.
          </p>
          {(() => {
            const atLimit = trainer.multiclassUnlockedCount >= trainer.multiclassMaxClasses;
            if (atLimit) {
              return (
                <>
                  <p className="trainer-locked-req">
                    You have reached the limit of {trainer.multiclassMaxClasses} unlocked classes.
                  </p>
                  <button
                    type="button"
                    className="trainer-unlock-btn trainer-unlock-btn-disabled"
                    disabled
                    title="Class limit reached"
                  >
                    Class limit reached
                  </button>
                </>
              );
            }
            const canUnlock =
              playerLevel >= trainer.multiclassMinLevel && playerGold >= trainer.multiclassGoldCost;
            return (
              <>
                <p className="trainer-locked-req">
                  Requires level {trainer.multiclassMinLevel} and {trainer.multiclassGoldCost.toLocaleString()} gold.
                </p>
                <button
                  type="button"
                  className={`trainer-unlock-btn${canUnlock ? "" : " trainer-unlock-btn-disabled"}`}
                  disabled={!canUnlock}
                  title={canUnlock ? `Unlock the ${classLabel} class` : "Requirements not met"}
                  onClick={() => onCommand(unlockCmd)}
                >
                  Unlock {classLabel} ({trainer.multiclassGoldCost.toLocaleString()} gold)
                </button>
              </>
            );
          })()}
        </div>
      ) : activeClass.abilities.length === 0 ? (
        <p className="trainer-empty">
          No new {classLabel} lessons at your level. Keep adventuring!
        </p>
      ) : (
        <div className="trainer-ability-list">
          {activeClass.abilities.map((ability) => (
            <AbilityRow
              key={ability.id}
              ability={ability}
              canAfford={hasPoints}
              onLearn={(id) => onCommand(`train learn ${id}`)}
            />
          ))}
        </div>
      )}
    </div>
  );
}
