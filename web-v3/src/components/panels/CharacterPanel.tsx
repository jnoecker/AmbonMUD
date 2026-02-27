import type { CharacterInfo, StatusEffect, Vitals } from "../../types";
import { Bar, CharacterAvatarIcon, EquipmentIcon, WearingIcon } from "../Icons";

interface CharacterPanelProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  canOpenEquipment: boolean;
  character: CharacterInfo;
  displayRace: string;
  displayClassName: string;
  vitals: Vitals;
  xpValue: number;
  xpMax: number;
  xpText: string;
  effects: StatusEffect[];
  visibleEffects: StatusEffect[];
  hiddenEffectsCount: number;
  onOpenEquipment: () => void;
  onOpenWearing: () => void;
}

export function CharacterPanel({
  connected,
  hasCharacterProfile,
  canOpenEquipment,
  character,
  displayRace,
  displayClassName,
  vitals,
  xpValue,
  xpMax,
  xpText,
  effects,
  visibleEffects,
  hiddenEffectsCount,
  onOpenEquipment,
  onOpenWearing,
}: CharacterPanelProps) {
  return (
    <section className="panel panel-character" aria-label="Character status">
      <header className="panel-header panel-header-with-actions">
        <div>
          <h2 className="panel-header-icon-title" title="Identity, progression, and active effects.">
            <CharacterAvatarIcon className="panel-header-avatar-icon" />
            <span className="sr-only">Character</span>
          </h2>
        </div>
        <div className="panel-action-row">
          <button
            type="button"
            className="panel-action-button panel-action-button-icon"
            onClick={onOpenEquipment}
            disabled={!canOpenEquipment}
            title={canOpenEquipment ? "Equipment" : "Equipment unavailable before login"}
            aria-label={canOpenEquipment ? "Open equipment" : "Equipment unavailable before login"}
          >
            <EquipmentIcon className="panel-action-icon" />
            <span className="sr-only">Equipment</span>
          </button>
          <button
            type="button"
            className="panel-action-button panel-action-button-icon"
            onClick={onOpenWearing}
            disabled={!canOpenEquipment}
            title={canOpenEquipment ? "Currently Wearing" : "Currently wearing unavailable before login"}
            aria-label={canOpenEquipment ? "Open currently wearing" : "Currently wearing unavailable before login"}
          >
            <WearingIcon className="panel-action-icon" />
            <span className="sr-only">Currently Wearing</span>
          </button>
        </div>
      </header>

      <div className="character-stack">
        <article className="subpanel">
          {hasCharacterProfile ? (
            <>
              <h3>Identity</h3>
              <p className="identity-name">{character.name}</p>
              <p className="identity-detail">
                {character.level ? `Level ${character.level}` : "Level -"}
                {displayRace ? ` ${displayRace}` : ""}
                {displayClassName ? ` ${displayClassName}` : ""}
              </p>
            </>
          ) : (
            <div className="prelogin-card">
              <h3>Identity</h3>
              <p className="prelogin-card-title">{connected ? "Character profile pending" : "No active character"}</p>
              <p className="room-description">
                {connected
                  ? "After login, your name, class, race, and level will appear here."
                  : "Reconnect and log in to load your character profile."}
              </p>
            </div>
          )}
        </article>

        <article className="subpanel meter-stack">
          <h3>Vitals</h3>
          {hasCharacterProfile ? (
            <>
              <Bar label="HP" tone="hp" value={vitals.hp} max={Math.max(1, vitals.maxHp)} text={`${vitals.hp} / ${vitals.maxHp}`} />
              <Bar label="Mana" tone="mana" value={vitals.mana} max={Math.max(1, vitals.maxMana)} text={`${vitals.mana} / ${vitals.maxMana}`} />
              <Bar label="XP" tone="xp" value={xpValue} max={xpMax} text={xpText} />

              <dl className="stat-grid">
                <div><dt>Level</dt><dd>{vitals.level ?? "-"}</dd></div>
                <div><dt>Total XP</dt><dd>{vitals.xp.toLocaleString()}</dd></div>
                <div><dt>Gold</dt><dd>{vitals.gold.toLocaleString()}</dd></div>
              </dl>
            </>
          ) : (
            <div className="meter-placeholder-stack">
              <div className="meter-placeholder-row"><span>HP</span><span>- / -</span></div>
              <div className="meter-track meter-track-placeholder"><span className="meter-fill meter-fill-placeholder" /></div>
              <div className="meter-placeholder-row"><span>Mana</span><span>- / -</span></div>
              <div className="meter-track meter-track-placeholder"><span className="meter-fill meter-fill-placeholder" /></div>
              <div className="meter-placeholder-row"><span>XP</span><span>- / -</span></div>
              <div className="meter-track meter-track-placeholder"><span className="meter-fill meter-fill-placeholder" /></div>
            </div>
          )}
        </article>

        <article className="subpanel character-effects">
          <h3>Effects</h3>
          {effects.length === 0 ? <p className="empty-note">{hasCharacterProfile ? "No active effects." : "Effects will appear here during gameplay."}</p> : (
            <>
              <ul className="effects-list">
                {visibleEffects.map((effect, index) => {
                  const seconds = Math.max(1, Math.ceil(effect.remainingMs / 1000));
                  const stack = effect.stacks > 1 ? ` x${effect.stacks}` : "";
                  return (
                    <li key={`${effect.name}-${index}`} className="effect-item">
                      <span className="effect-name">{effect.name}{stack}</span>
                      <span className="effect-type">{effect.type}</span>
                      <span className="effect-time">{seconds}s</span>
                    </li>
                  );
                })}
              </ul>
              {hiddenEffectsCount > 0 && <p className="empty-note">+{hiddenEffectsCount} more effects</p>}
            </>
          )}
        </article>
      </div>
    </section>
  );
}

