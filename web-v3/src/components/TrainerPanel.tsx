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

  // Selected class tab — defaults to the first class, or the first unlocked one if any are unlocked
  const initialClass = trainer.classes.find((c) => c.classUnlocked)?.className
    ?? trainer.classes[0]?.className
    ?? "";
  const [selectedClass, setSelectedClass] = useState<string>(initialClass);
  const [prevTrainerId, setPrevTrainerId] = useState<string>(trainer.trainerId);

  // Reset the selected tab when the trainer changes (e.g. switching rooms)
  if (trainer.trainerId !== prevTrainerId) {
    setPrevTrainerId(trainer.trainerId);
    setSelectedClass(initialClass);
  }

  const activeClass: TrainerClass | undefined =
    trainer.classes.find((c) => c.className === selectedClass) ?? trainer.classes[0];

  if (!activeClass) {
    return (
      <div className="trainer-panel">
        <div className="trainer-header">
          <span className="trainer-name">{trainer.name}</span>
        </div>
        <p className="trainer-empty">This trainer has no classes configured.</p>
      </div>
    );
  }

  const classLabel = prettyClass(activeClass.className);
  const unlockArg = isMultiClass ? ` ${activeClass.className.toLowerCase()}` : "";
  const unlockCmd = `train unlock${unlockArg}`;

  return (
    <div className="trainer-panel">
      <div className="trainer-header">
        <span className="trainer-name">{trainer.name}</span>
        <span className="trainer-class">
          {isMultiClass
            ? `${trainer.classes.map((c) => prettyClass(c.className)).join(" / ")} Trainer`
            : `${classLabel} Trainer`}
        </span>
      </div>

      {isMultiClass && (
        <div className="trainer-class-tabs" role="tablist" aria-label="Trainer classes">
          {trainer.classes.map((c) => {
            const isActive = c.className === activeClass.className;
            return (
              <button
                key={c.className}
                type="button"
                role="tab"
                aria-selected={isActive}
                className={`trainer-class-tab${isActive ? " trainer-class-tab-active" : ""}${c.classUnlocked ? "" : " trainer-class-tab-locked"}`}
                onClick={() => setSelectedClass(c.className)}
              >
                {prettyClass(c.className)}
                {!c.classUnlocked && <span className="trainer-class-tab-lock" aria-hidden="true">{"\uD83D\uDD12"}</span>}
              </button>
            );
          })}
        </div>
      )}

      <div className="trainer-skill-points">
        <span className="trainer-sp-label">Skill Points Available</span>
        <span className={`trainer-sp-value${hasPoints ? " trainer-sp-available" : " trainer-sp-empty"}`}>
          {trainer.availableSkillPoints}
        </span>
      </div>

      {!activeClass.classUnlocked ? (
        <div className="trainer-locked">
          <p className="trainer-locked-msg">
            The <strong>{classLabel}</strong> class is locked.
          </p>
          <p className="trainer-locked-req">
            Requires level {trainer.multiclassMinLevel} and {trainer.multiclassGoldCost.toLocaleString()} gold.
          </p>
          {(() => {
            const canUnlock = playerLevel >= trainer.multiclassMinLevel && playerGold >= trainer.multiclassGoldCost;
            return (
              <button
                type="button"
                className={`trainer-unlock-btn${canUnlock ? "" : " trainer-unlock-btn-disabled"}`}
                disabled={!canUnlock}
                title={canUnlock ? `Unlock the ${classLabel} class` : "Requirements not met"}
                onClick={() => onCommand(unlockCmd)}
              >
                Unlock {classLabel} ({trainer.multiclassGoldCost.toLocaleString()} gold)
              </button>
            );
          })()}
        </div>
      ) : activeClass.abilities.length === 0 ? (
        <p className="trainer-empty">
          No new {classLabel} abilities available at your level. Keep adventuring!
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
