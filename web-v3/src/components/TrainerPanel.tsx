import type { TrainerAbility, TrainerData } from "../types";

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

function AbilityRow({
  ability,
  onLearn,
  canAfford,
}: {
  ability: TrainerAbility;
  onLearn: (id: string) => void;
  canAfford: boolean;
}) {
  return (
    <div className="trainer-ability-row">
      <div className="trainer-ability-icon">
        {ability.image ? (
          <img src={ability.image} alt="" className="trainer-ability-img" />
        ) : (
          <div className="trainer-ability-placeholder" />
        )}
      </div>
      <div className="trainer-ability-info">
        <span className="trainer-ability-name">{ability.name}</span>
        <span className="trainer-ability-desc">{ability.description}</span>
        <div className="trainer-ability-meta">
          <span className="trainer-meta-tag">{effectLabel(ability.effectType)}</span>
          <span className="trainer-meta-tag">{ability.manaCost} MP</span>
          <span className="trainer-meta-tag">CD: {cooldownLabel(ability.cooldownMs)}</span>
          <span className="trainer-meta-tag">Lv {ability.levelRequired}</span>
        </div>
      </div>
      <button
        type="button"
        className={`trainer-learn-btn${canAfford ? "" : " trainer-learn-btn-disabled"}`}
        disabled={!canAfford}
        title={canAfford ? `Learn ${ability.name}` : "Not enough skill points"}
        onClick={() => onLearn(ability.id)}
      >
        Learn
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

  if (!trainer.classUnlocked) {
    const canUnlock = playerLevel >= trainer.multiclassMinLevel && playerGold >= trainer.multiclassGoldCost;
    return (
      <div className="trainer-panel">
        <div className="trainer-header">
          <span className="trainer-name">{trainer.name}</span>
          <span className="trainer-class">{trainer.className} Trainer</span>
        </div>
        <div className="trainer-locked">
          <p className="trainer-locked-msg">
            The <strong>{trainer.className}</strong> class is locked.
          </p>
          <p className="trainer-locked-req">
            Requires level {trainer.multiclassMinLevel} and {trainer.multiclassGoldCost.toLocaleString()} gold.
          </p>
          <button
            type="button"
            className={`trainer-unlock-btn${canUnlock ? "" : " trainer-unlock-btn-disabled"}`}
            disabled={!canUnlock}
            title={canUnlock ? `Unlock the ${trainer.className} class` : "Requirements not met"}
            onClick={() => onCommand("train unlock")}
          >
            Unlock Class ({trainer.multiclassGoldCost.toLocaleString()} gold)
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="trainer-panel">
      <div className="trainer-header">
        <span className="trainer-name">{trainer.name}</span>
        <span className="trainer-class">{trainer.className} Trainer</span>
      </div>

      <div className="trainer-skill-points">
        <span className="trainer-sp-label">Skill Points Available</span>
        <span className={`trainer-sp-value${hasPoints ? " trainer-sp-available" : " trainer-sp-empty"}`}>
          {trainer.availableSkillPoints}
        </span>
      </div>

      {trainer.abilities.length === 0 ? (
        <p className="trainer-empty">
          No new abilities available at your level. Keep adventuring!
        </p>
      ) : (
        <div className="trainer-ability-list">
          {trainer.abilities.map((ability) => (
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
