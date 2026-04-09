import { useEffect, useMemo, useState } from "react";
import type { AchievementData, CharacterInfo, CharStats, CurrencyBalance, DuelChallenge, DuelState, DungeonInfo, FactionStanding, GroupInfo, GuildInfo, LotteryInfo, PetState, PrestigeInfo, QuestEntry, QuestNotification, SpriteEntry, SpriteList, StatusEffect, StatusVarLabels, SystemPanelView, Vitals, WorldEvent, WorldTime, WorldWeather } from "../../types";
import { AchievementsTabIcon, Bar, CharacterAvatarIcon, EffectsTabIcon, EquipmentIcon, FactionsTabIcon, QuestsTabIcon, ScoreTabIcon, StatsTabIcon, VitalsTabIcon, WearingIcon } from "../Icons";

type DetailTab = "vitals" | "effects" | "achievements" | "quests" | "stats" | "score" | "factions";

function factionTierClass(tier: string): "positive" | "neutral" | "negative" {
  switch (tier) {
    case "Friendly":
    case "Honored":
    case "Revered":
      return "positive";
    case "Unfriendly":
    case "Hostile":
    case "Hated":
      return "negative";
    default:
      return "neutral";
  }
}

function formatDrawingCountdown(ms: number): string {
  if (ms <= 0) return "Drawing soon!";
  const totalSec = Math.floor(ms / 1000);
  const hours = Math.floor(totalSec / 3600);
  const minutes = Math.floor((totalSec % 3600) / 60);
  const seconds = totalSec % 60;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m ${seconds}s`;
  return `${seconds}s`;
}

interface CharacterPanelProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  canOpenEquipment: boolean;
  character: CharacterInfo;
  displayRace: string;
  displayClassName: string;
  vitals: Vitals;
  statusVarLabels: StatusVarLabels;
  xpValue: number;
  xpMax: number;
  xpText: string;
  effects: StatusEffect[];
  visibleEffects: StatusEffect[];
  hiddenEffectsCount: number;
  achievements: AchievementData;
  quests: QuestEntry[];
  questNotifications: QuestNotification[];
  charStats: CharStats | null;
  guildInfo: GuildInfo;
  groupInfo: GroupInfo;
  activeTitle: string | null;
  spriteList: SpriteList;
  currencies: CurrencyBalance[];
  factions: FactionStanding[];
  petState: PetState | null;
  worldTime: WorldTime | null;
  worldWeather: WorldWeather | null;
  worldEvents: WorldEvent[];
  lotteryInfo: LotteryInfo | null;
  duelState: DuelState | null;
  duelChallenge: DuelChallenge | null;
  dungeonInfo: DungeonInfo | null;
  prestigeInfo: PrestigeInfo | null;
  onDismissQuestNotification: (id: string) => void;
  onAbandonQuest: (questName: string) => void;
  onOpenInventory: () => void;
  onOpenEquipment: () => void;
  onOpenSystem: (view: SystemPanelView) => void;
  onCommand: (command: string) => void;
  onLogout: () => void;
}

export function CharacterPanel({
  connected,
  hasCharacterProfile,
  canOpenEquipment,
  character,
  displayRace,
  displayClassName,
  vitals,
  statusVarLabels,
  xpValue,
  xpMax,
  xpText,
  effects,
  visibleEffects,
  hiddenEffectsCount,
  achievements,
  quests,
  questNotifications,
  charStats,
  guildInfo,
  groupInfo,
  activeTitle,
  spriteList,
  currencies,
  factions,
  petState,
  worldTime,
  worldWeather,
  worldEvents,
  lotteryInfo,
  duelState,
  duelChallenge,
  dungeonInfo,
  prestigeInfo,
  onDismissQuestNotification,
  onAbandonQuest,
  onOpenInventory,
  onOpenEquipment,
  onOpenSystem,
  onCommand,
  onLogout,
}: CharacterPanelProps) {
  const [activeDetailTab, setActiveDetailTab] = useState<DetailTab>("vitals");
  const [expandedQuestId, setExpandedQuestId] = useState<string | null>(null);
  const [showSpriteSelector, setShowSpriteSelector] = useState(false);
  const [showDescriptionEditor, setShowDescriptionEditor] = useState(false);
  const [descriptionDraft, setDescriptionDraft] = useState("");

  const totalAchievements = achievements.completed.length + achievements.inProgress.length;
  const unlockedTitles = useMemo(
    () => achievements.completed.filter((a) => a.title !== null).map((a) => a.title as string),
    [achievements.completed],
  );

  const CATEGORY_LABELS: Record<string, string> = { general: "Sprites", tier: "Tier Sprites", achievement: "Achievement Sprites", staff: "Staff Sprites" };
  const CATEGORY_ORDER = ["general", "tier", "achievement", "staff"];
  const spritesByCategory = useMemo(() => {
    const grouped = new Map<string, SpriteEntry[]>();
    for (const sprite of spriteList.sprites) {
      const cat = sprite.category || "other";
      const list = grouped.get(cat);
      if (list) list.push(sprite);
      else grouped.set(cat, [sprite]);
    }
    return grouped;
  }, [spriteList.sprites]);

  // Auto-dismiss quest notifications after 6 seconds
  useEffect(() => {
    if (questNotifications.length === 0) return;
    const timer = window.setTimeout(() => {
      onDismissQuestNotification(questNotifications[0].id);
    }, 6000);
    return () => window.clearTimeout(timer);
  }, [questNotifications, onDismissQuestNotification]);

  return (
    <section className="panel panel-character" aria-label="Character status">
      <header className="panel-header panel-header-with-actions">
        <div>
          <h2 className="panel-header-icon-title" title="Identity, progression, and active effects.">
            <CharacterAvatarIcon className="panel-header-avatar-icon" />
            <span className="panel-header-inline-label">Character</span>
          </h2>
        </div>
        <div className="panel-action-row">
          <button
            type="button"
            className="panel-action-button"
            onClick={() => onOpenSystem("currencies")}
            title="Open systems"
            aria-label="Open systems"
          >
            Systems
          </button>
          <button
            type="button"
            className="panel-action-button panel-action-button-icon"
            onClick={onOpenInventory}
            disabled={!canOpenEquipment}
            title={canOpenEquipment ? "Inventory" : "Inventory unavailable before login"}
            aria-label={canOpenEquipment ? "Open inventory" : "Inventory unavailable before login"}
          >
            <EquipmentIcon className="panel-action-icon" />
            <span className="sr-only">Inventory</span>
          </button>
          <button
            type="button"
            className="panel-action-button panel-action-button-icon"
            onClick={onOpenEquipment}
            disabled={!canOpenEquipment}
            title={canOpenEquipment ? "Equipment" : "Equipment unavailable before login"}
            aria-label={canOpenEquipment ? "Open equipment" : "Equipment unavailable before login"}
          >
            <WearingIcon className="panel-action-icon" />
            <span className="sr-only">Equipment</span>
          </button>
        </div>
      </header>

      <div className="character-stack">
        <article className="subpanel character-identity-subpanel">
          {hasCharacterProfile ? (
            <>
              <div className="identity-row">
                {character.sprite && (
                  <button
                    type="button"
                    className={`character-sprite-button ${showSpriteSelector ? "character-sprite-button-active" : ""}`}
                    onClick={() => setShowSpriteSelector((prev) => !prev)}
                    title={spriteList.sprites.length > 0 ? "Change sprite" : character.name}
                    disabled={spriteList.sprites.length === 0}
                  >
                    <img
                      src={character.sprite}
                      alt={`${character.name} sprite`}
                      className="character-sprite-img"
                    />
                  </button>
                )}
                <div className="identity-text">
                  <p className="identity-name">{character.name}</p>
                  <p className="identity-detail">
                    {[displayRace, displayClassName].filter((part) => part.length > 0).join(" ") || "-"}
                  </p>
                </div>
              </div>
              {showSpriteSelector && spriteList.sprites.length > 0 && (
                <div className="sprite-selector">
                  <div className="sprite-selector-header">
                    <span className="sprite-selector-title">Choose Sprite</span>
                    <button
                      type="button"
                      className="sprite-selector-close"
                      onClick={() => setShowSpriteSelector(false)}
                      aria-label="Close sprite selector"
                    >
                      &times;
                    </button>
                  </div>
                  <button
                    type="button"
                    className={`sprite-option sprite-option-default ${spriteList.active === null ? "sprite-option-active" : ""}`}
                    onClick={() => { onCommand("sprite default"); setShowSpriteSelector(false); }}
                  >
                    <span className="sprite-option-auto-label">Auto</span>
                    <span className="sprite-option-name">Default (Auto)</span>
                  </button>
                  {CATEGORY_ORDER.filter((cat) => spritesByCategory.has(cat)).map((cat) => (
                    <div key={cat} className="sprite-category">
                      <p className="sprite-category-label">{CATEGORY_LABELS[cat] ?? cat}</p>
                      <div className="sprite-grid">
                        {spritesByCategory.get(cat)!.map((sprite) => (
                          <button
                            key={sprite.imageId}
                            type="button"
                            className={`sprite-option ${spriteList.active === sprite.imageId ? "sprite-option-active" : ""}`}
                            onClick={() => { onCommand(`sprite set ${sprite.imageId}`); setShowSpriteSelector(false); }}
                            title={sprite.displayName}
                          >
                            <img
                              src={sprite.imagePath}
                              alt={sprite.displayName}
                              className="sprite-option-img"
                            />
                            <span className="sprite-option-name">{sprite.displayName}</span>
                          </button>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              )}
              <div className="identity-controls">
                <label className="identity-select-group">
                  <span className="identity-select-label">Title</span>
                  <select
                    className="identity-select"
                    value={activeTitle ?? ""}
                    onChange={(e) => {
                      const val = e.target.value;
                      onCommand(val ? `title ${val}` : "title clear");
                    }}
                  >
                    <option value="">None</option>
                    {unlockedTitles.map((t) => (
                      <option key={t} value={t}>{t}</option>
                    ))}
                  </select>
                </label>
                <label className="identity-select-group">
                  <span className="identity-select-label">Gender</span>
                  <select
                    className="identity-select"
                    value={character.gender}
                    onChange={(e) => onCommand(`gender ${e.target.value}`)}
                  >
                    <option value="male">Male</option>
                    <option value="female">Female</option>
                    <option value="enby">Enby</option>
                  </select>
                </label>
              </div>
              <div className="description-editor-section">
                <button
                  type="button"
                  className="description-editor-toggle"
                  onClick={() => setShowDescriptionEditor((prev) => !prev)}
                  aria-expanded={showDescriptionEditor}
                >
                  <span className="description-editor-toggle-label">Edit Description</span>
                  <span className={`description-editor-toggle-chevron ${showDescriptionEditor ? "description-editor-toggle-chevron-open" : ""}`} aria-hidden="true">&rsaquo;</span>
                </button>
                {showDescriptionEditor && (
                  <div className="description-editor-body">
                    <textarea
                      className="description-editor-textarea"
                      maxLength={500}
                      rows={4}
                      placeholder="Describe your character's appearance..."
                      value={descriptionDraft}
                      onChange={(e) => setDescriptionDraft(e.target.value)}
                    />
                    <div className="description-editor-footer">
                      <span className={`description-editor-counter ${descriptionDraft.length >= 450 ? "description-editor-counter-warn" : ""} ${descriptionDraft.length >= 500 ? "description-editor-counter-limit" : ""}`}>
                        {descriptionDraft.length} / 500
                      </span>
                      <div className="description-editor-actions">
                        <button
                          type="button"
                          className="description-editor-btn description-editor-clear-btn"
                          onClick={() => { onCommand("describe clear"); setDescriptionDraft(""); }}
                        >
                          Clear
                        </button>
                        <button
                          type="button"
                          className="description-editor-btn description-editor-save-btn"
                          disabled={descriptionDraft.trim().length === 0}
                          onClick={() => { onCommand(`describe ${descriptionDraft.trim()}`); setShowDescriptionEditor(false); }}
                        >
                          Save
                        </button>
                      </div>
                    </div>
                  </div>
                )}
              </div>
              <dl className="stat-grid identity-stat-grid">
                <div><dt>Level</dt><dd>{vitals.level ?? character.level ?? "-"}</dd></div>
                <div><dt>Total XP</dt><dd>{vitals.xp.toLocaleString()}</dd></div>
                <div><dt>Gold</dt><dd>{vitals.gold.toLocaleString()}</dd></div>
              </dl>
            </>
          ) : (
            <div className="prelogin-card">
              <p className="prelogin-card-title">{connected ? "Awaiting login" : "No active character"}</p>
              <p className="room-description">
                {connected
                  ? "Your name, class, race, and level will appear here after login."
                  : "Reconnect and log in to load your character profile."}
              </p>
            </div>
          )}
        </article>

        <article className="subpanel character-detail-subpanel">
          <div className="character-detail-tabs" role="tablist" aria-label="Character detail sections">
            <button
              type="button"
              role="tab"
              aria-selected={activeDetailTab === "vitals"}
              aria-label="Vitals"
              title="Vitals"
              className={`character-detail-tab ${activeDetailTab === "vitals" ? "character-detail-tab-active" : ""}`}
              onClick={() => setActiveDetailTab("vitals")}
            >
              <VitalsTabIcon className="detail-tab-icon" />
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={activeDetailTab === "effects"}
              aria-label="Effects"
              title="Effects"
              className={`character-detail-tab ${activeDetailTab === "effects" ? "character-detail-tab-active" : ""}`}
              onClick={() => setActiveDetailTab("effects")}
            >
              <EffectsTabIcon className="detail-tab-icon" />
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={activeDetailTab === "achievements"}
              aria-label="Achievements"
              title="Achievements"
              className={`character-detail-tab ${activeDetailTab === "achievements" ? "character-detail-tab-active" : ""}`}
              onClick={() => setActiveDetailTab("achievements")}
            >
              <AchievementsTabIcon className="detail-tab-icon" />
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={activeDetailTab === "quests"}
              aria-label="Quests"
              title="Quests"
              className={`character-detail-tab ${activeDetailTab === "quests" ? "character-detail-tab-active" : ""}`}
              onClick={() => setActiveDetailTab("quests")}
            >
              <QuestsTabIcon className="detail-tab-icon" />
              {quests.length > 0 && <span className="quest-tab-badge">{quests.length}</span>}
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={activeDetailTab === "stats"}
              aria-label="Stats"
              title="Stats"
              className={`character-detail-tab ${activeDetailTab === "stats" ? "character-detail-tab-active" : ""}`}
              onClick={() => setActiveDetailTab("stats")}
            >
              <StatsTabIcon className="detail-tab-icon" />
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={activeDetailTab === "score"}
              aria-label="Score"
              title="Score"
              className={`character-detail-tab ${activeDetailTab === "score" ? "character-detail-tab-active" : ""}`}
              onClick={() => setActiveDetailTab("score")}
            >
              <ScoreTabIcon className="detail-tab-icon" />
            </button>
            {factions.length > 0 && (
              <button
                type="button"
                role="tab"
                aria-selected={activeDetailTab === "factions"}
                aria-label="Factions"
                title="Factions"
                className={`character-detail-tab ${activeDetailTab === "factions" ? "character-detail-tab-active" : ""}`}
                onClick={() => setActiveDetailTab("factions")}
              >
                <FactionsTabIcon className="detail-tab-icon" />
              </button>
            )}
          </div>

          <div className="character-detail-body">
            {activeDetailTab === "vitals" && (
              <section
                key="vitals"
                className="character-detail-panel character-detail-panel-flip meter-stack"
                role="tabpanel"
                aria-label="Vitals"
              >
                {hasCharacterProfile ? (
                  <>
                    <Bar label={statusVarLabels.hp} tone="hp" value={vitals.hp} max={Math.max(1, vitals.maxHp)} text={`${vitals.hp} / ${vitals.maxHp}`} />
                    <Bar label={statusVarLabels.mana} tone="mana" value={vitals.mana} max={Math.max(1, vitals.maxMana)} text={`${vitals.mana} / ${vitals.maxMana}`} />
                    <Bar label={statusVarLabels.xp} tone="xp" value={xpValue} max={xpMax} text={xpText} />
                  </>
                ) : (
                  <div className="meter-placeholder-stack">
                    <div className="meter-placeholder-row"><span>{statusVarLabels.hp}</span><span>- / -</span></div>
                    <div className="meter-track meter-track-placeholder"><span className="meter-fill meter-fill-placeholder" /></div>
                    <div className="meter-placeholder-row"><span>{statusVarLabels.mana}</span><span>- / -</span></div>
                    <div className="meter-track meter-track-placeholder"><span className="meter-fill meter-fill-placeholder" /></div>
                    <div className="meter-placeholder-row"><span>{statusVarLabels.xp}</span><span>- / -</span></div>
                    <div className="meter-track meter-track-placeholder"><span className="meter-fill meter-fill-placeholder" /></div>
                  </div>
                )}
              </section>
            )}

            {activeDetailTab === "effects" && (
              <section
                key="effects"
                className="character-detail-panel character-detail-panel-flip character-effects"
                role="tabpanel"
                aria-label="Effects"
              >
                {effects.length === 0 ? <p className="empty-note">{hasCharacterProfile ? "No active effects. Cast a spell or use a potion to gain buffs." : "Effects will appear here during gameplay."}</p> : (
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
              </section>
            )}

            {activeDetailTab === "achievements" && (
              <section
                key="achievements"
                className="character-detail-panel character-detail-panel-flip character-achievements"
                role="tabpanel"
                aria-label="Achievements"
              >
                {totalAchievements === 0 ? (
                  <p className="empty-note">{hasCharacterProfile ? "No achievements yet. Explore the world and complete challenges to earn them." : "Achievements will appear here during gameplay."}</p>
                ) : (
                  <ul className="achievements-list">
                    {achievements.completed.map((a) => (
                      <li key={a.id} className="achievement-item achievement-item-completed">
                        <span className="achievement-name">{a.name}</span>
                        {a.title && <span className="achievement-title">{a.title}</span>}
                      </li>
                    ))}
                    {achievements.inProgress.map((a) => (
                      <li key={a.id} className="achievement-item achievement-item-progress">
                        <div className="achievement-progress-header">
                          <span className="achievement-name">{a.name}</span>
                          <span className="achievement-progress-text">{a.current} / {a.required}</span>
                        </div>
                        <div className="meter-track achievement-progress-track">
                          <span
                            className="meter-fill meter-fill-xp"
                            style={{ width: `${Math.min(100, (a.current / Math.max(1, a.required)) * 100)}%` }}
                          />
                        </div>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            )}

            {activeDetailTab === "quests" && (
              <section
                key="quests"
                className="character-detail-panel character-detail-panel-flip character-quests"
                role="tabpanel"
                aria-label="Quests"
              >
                {questNotifications.length > 0 && (
                  <div className="quest-notifications">
                    {questNotifications.map((n) => (
                      <div
                        key={n.id}
                        className={`quest-notification quest-notification-${n.event}`}
                        role="status"
                      >
                        <span className="quest-notification-text">
                          {n.event === "complete" ? "Completed: " : "Updated: "}
                          <strong>{n.questName}</strong>
                        </span>
                        <button
                          type="button"
                          className="quest-notification-dismiss"
                          onClick={() => onDismissQuestNotification(n.id)}
                          aria-label="Dismiss notification"
                        >
                          ×
                        </button>
                      </div>
                    ))}
                  </div>
                )}
                {quests.length === 0 ? (
                  <p className="empty-note">{hasCharacterProfile ? "No active quests. Talk to NPCs to discover available quests." : "Quests will appear here during gameplay."}</p>
                ) : (
                  <ul className="quest-list">
                    {quests.map((quest) => {
                      const isExpanded = expandedQuestId === quest.id;
                      const totalObjectives = quest.objectives.length;
                      const completedObjectives = quest.objectives.filter((o) => o.current >= o.required).length;
                      return (
                        <li key={quest.id} className={`quest-item ${isExpanded ? "quest-item-expanded" : ""}`}>
                          <button
                            type="button"
                            className="quest-item-header"
                            onClick={() => setExpandedQuestId(isExpanded ? null : quest.id)}
                            aria-expanded={isExpanded}
                          >
                            <span className="quest-item-name">{quest.name}</span>
                            <span className="quest-item-progress-badge">
                              {completedObjectives}/{totalObjectives}
                            </span>
                          </button>
                          {isExpanded && (
                            <div className="quest-item-details">
                              <p className="quest-item-description">{quest.description}</p>
                              <ul className="quest-objectives">
                                {quest.objectives.map((obj, idx) => {
                                  const done = obj.current >= obj.required;
                                  return (
                                    <li key={idx} className={`quest-objective ${done ? "quest-objective-done" : ""}`}>
                                      <div className="quest-objective-header">
                                        <span className="quest-objective-check">{done ? "✓" : "○"}</span>
                                        <span className="quest-objective-text">{obj.description}</span>
                                        <span className="quest-objective-count">{obj.current}/{obj.required}</span>
                                      </div>
                                      {!done && (
                                        <div className="meter-track quest-objective-track">
                                          <span
                                            className="meter-fill meter-fill-quest"
                                            style={{ width: `${Math.min(100, (obj.current / Math.max(1, obj.required)) * 100)}%` }}
                                          />
                                        </div>
                                      )}
                                    </li>
                                  );
                                })}
                              </ul>
                              <div className="quest-item-actions">
                                <button
                                  type="button"
                                  className="quest-abandon-button"
                                  onClick={() => onAbandonQuest(quest.name)}
                                >
                                  Abandon
                                </button>
                              </div>
                            </div>
                          )}
                        </li>
                      );
                    })}
                  </ul>
                )}
              </section>
            )}

            {activeDetailTab === "stats" && (
              <section
                key="stats"
                className="character-detail-panel character-detail-panel-flip character-stats"
                role="tabpanel"
                aria-label="Stats"
              >
                {!hasCharacterProfile ? (
                  <p className="empty-note">Stats will appear here after login.</p>
                ) : !charStats ? (
                  <p className="empty-note">Awaiting stat data from server.</p>
                ) : (
                  <div className="stats-content">
                    <table className="stats-attribute-table">
                      <thead>
                        <tr>
                          <th className="stats-th">Attribute</th>
                          <th className="stats-th stats-th-num">Base</th>
                          <th className="stats-th stats-th-num">Eff.</th>
                        </tr>
                      </thead>
                      <tbody>
                        {charStats.stats.map((stat) => {
                          const diff = stat.effective - stat.base;
                          return (
                            <tr key={stat.id} className="stats-row">
                              <td className="stats-td stats-td-name" title={stat.name}>{stat.abbrev}</td>
                              <td className="stats-td stats-td-num">{stat.base}</td>
                              <td className={`stats-td stats-td-num ${diff > 0 ? "stats-buff" : diff < 0 ? "stats-debuff" : ""}`}>
                                {stat.effective}
                                {diff !== 0 && <span className="stats-diff">({diff > 0 ? "+" : ""}{diff})</span>}
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                    <div className="stats-derived">
                      <div className="stats-derived-row">
                        <span className="stats-derived-label">Damage</span>
                        <span className="stats-derived-value">{charStats.baseDamageMin}&ndash;{charStats.baseDamageMax}</span>
                      </div>
                      <div className="stats-derived-row">
                        <span className="stats-derived-label">Armor</span>
                        <span className="stats-derived-value">{charStats.armor}</span>
                      </div>
                      <div className="stats-derived-row">
                        <span className="stats-derived-label">Dodge</span>
                        <span className="stats-derived-value">{charStats.dodgePercent}%</span>
                      </div>
                    </div>
                  </div>
                )}
              </section>
            )}

            {activeDetailTab === "score" && (
              <section
                key="score"
                className="character-detail-panel character-detail-panel-flip character-score"
                role="tabpanel"
                aria-label="Score"
              >
                {!hasCharacterProfile ? (
                  <p className="empty-note">Character summary will appear here after login.</p>
                ) : (
                  <div className="score-content">
                    <div className="score-identity">
                      <span className="score-name">{character.name}</span>
                      {activeTitle && <span className="score-title">{activeTitle}</span>}
                    </div>
                    <dl className="score-grid">
                      <div><dt>Race</dt><dd>{displayRace}</dd></div>
                      <div><dt>Class</dt><dd>{displayClassName}</dd></div>
                      <div><dt>Level</dt><dd>{vitals.level ?? character.level ?? "-"}</dd></div>
                      <div><dt>Gold</dt><dd>{vitals.gold.toLocaleString()}</dd></div>
                    </dl>
                    <div className="score-vitals">
                      <div className="score-vital-row">
                        <span className="score-vital-label">{statusVarLabels.hp}</span>
                        <span className="score-vital-value">{vitals.hp} / {vitals.maxHp}</span>
                      </div>
                      <div className="score-vital-row">
                        <span className="score-vital-label">{statusVarLabels.mana}</span>
                        <span className="score-vital-value">{vitals.mana} / {vitals.maxMana}</span>
                      </div>
                      <div className="score-vital-row">
                        <span className="score-vital-label">{statusVarLabels.xp}</span>
                        <span className="score-vital-value">{xpText}</span>
                      </div>
                    </div>
                    {charStats && charStats.stats.length > 0 && (
                      <div className="score-stats-row">
                        {charStats.stats.map((stat) => (
                          <span key={stat.id} className="score-stat-chip" title={stat.name}>
                            <span className="score-stat-abbrev">{stat.abbrev}</span>
                            <span className="score-stat-val">{stat.effective}</span>
                          </span>
                        ))}
                      </div>
                    )}
                    {charStats && (
                      <dl className="score-grid score-grid-combat">
                        <div><dt>Damage</dt><dd>{charStats.baseDamageMin}&ndash;{charStats.baseDamageMax}</dd></div>
                        <div><dt>Armor</dt><dd>{charStats.armor}</dd></div>
                        <div><dt>Dodge</dt><dd>{charStats.dodgePercent}%</dd></div>
                      </dl>
                    )}
                    <div className="score-social">
                      {guildInfo.name && (
                        <div className="score-social-row">
                          <span className="score-social-label">Guild</span>
                          <span className="score-social-value">{guildInfo.name}{guildInfo.tag ? ` [${guildInfo.tag}]` : ""}</span>
                        </div>
                      )}
                      {groupInfo.members.length > 0 && (
                        <div className="score-social-row">
                          <span className="score-social-label">Group</span>
                          <span className="score-social-value">{groupInfo.members.length} member{groupInfo.members.length !== 1 ? "s" : ""}</span>
                        </div>
                      )}
                    </div>
                    {currencies.length > 0 && (
                      <div className="score-currencies">
                        <div className="score-section-header">
                          <p className="score-currencies-label">Currencies</p>
                          <button type="button" className="soft-button score-open-btn" onClick={() => onOpenSystem("currencies")}>Open Wallet</button>
                        </div>
                        <dl className="score-currencies-grid">
                          {currencies.map((c) => (
                            <div key={c.id}>
                              <dt title={c.abbreviation}>{c.name}</dt>
                              <dd>{c.balance.toLocaleString()}</dd>
                            </div>
                          ))}
                        </dl>
                      </div>
                    )}
                    {((vitals.prestigeLevel ?? 0) > 0 || vitals.prestigeNextCost != null) && (
                      <div className="score-prestige">
                        <div className="score-section-header">
                          <p className="score-prestige-label">Prestige</p>
                          <button type="button" className="soft-button score-open-btn" onClick={() => onOpenSystem("prestige")}>Open</button>
                        </div>
                        <dl className="score-prestige-grid">
                          <div>
                            <dt>Rank</dt>
                            <dd>{vitals.prestigeLevel ?? 0}</dd>
                          </div>
                          {vitals.prestigeXpAvailable != null && (
                            <div>
                              <dt>Available XP</dt>
                              <dd>{vitals.prestigeXpAvailable.toLocaleString()}</dd>
                            </div>
                          )}
                          {vitals.prestigeNextCost != null && (
                            <div>
                              <dt>Next Rank Cost</dt>
                              <dd>{vitals.prestigeNextCost.toLocaleString()}</dd>
                            </div>
                          )}
                        </dl>
                        <div className="score-prestige-actions">
                          <button
                            type="button"
                            className="soft-button score-prestige-btn"
                            disabled={vitals.prestigeXpAvailable == null || vitals.prestigeNextCost == null || vitals.prestigeXpAvailable < vitals.prestigeNextCost}
                            onClick={() => onCommand("prestige")}
                            title={
                              vitals.prestigeXpAvailable != null && vitals.prestigeNextCost != null && vitals.prestigeXpAvailable >= vitals.prestigeNextCost
                                ? "Advance to next prestige rank"
                                : "Not enough XP to prestige"
                            }
                          >
                            Prestige Up
                          </button>
                          <button
                            type="button"
                            className="soft-button score-prestige-btn"
                            onClick={() => onOpenSystem("prestige")}
                            title="Open the full prestige view"
                          >
                            Open Details
                          </button>
                        </div>
                      </div>
                    )}
                    {lotteryInfo && (
                      <div className="score-lottery">
                        <div className="score-section-header">
                          <p className="score-lottery-label">Lottery</p>
                          <button type="button" className="soft-button score-open-btn" onClick={() => onOpenSystem("lottery")}>Play</button>
                        </div>
                        <dl className="score-lottery-grid">
                          <div>
                            <dt>Jackpot</dt>
                            <dd className="score-lottery-jackpot">{lotteryInfo.jackpot.toLocaleString()} gold</dd>
                          </div>
                          <div>
                            <dt>Your Tickets</dt>
                            <dd>{lotteryInfo.playerTickets} / {lotteryInfo.totalTickets}</dd>
                          </div>
                          <div>
                            <dt>Next Drawing</dt>
                            <dd>{formatDrawingCountdown(lotteryInfo.nextDrawingMs)}</dd>
                          </div>
                        </dl>
                        <div className="score-lottery-actions">
                          <button
                            type="button"
                            className="score-lottery-btn"
                            onClick={() => onCommand("lottery buy 1")}
                          >
                            Buy Ticket
                          </button>
                          <button
                            type="button"
                            className="score-lottery-btn score-lottery-btn-secondary"
                            onClick={() => onCommand("lottery")}
                          >
                            Check Info
                          </button>
                        </div>
                      </div>
                    )}
                    {duelState?.active && (
                      <div className="score-duel">
                        <div className="score-section-header">
                          <p className="score-section-label">Duel</p>
                          <button type="button" className="soft-button score-open-btn" onClick={() => onOpenSystem("duel")}>Open</button>
                        </div>
                        <p className="score-section-value">Fighting <strong>{duelState.opponentName ?? "unknown"}</strong></p>
                      </div>
                    )}
                    {duelChallenge && !duelState?.active && (
                      <div className="score-duel">
                        <div className="score-section-header">
                          <p className="score-section-label">Duel Challenge</p>
                          <button type="button" className="soft-button score-open-btn" onClick={() => onOpenSystem("duel")}>Open</button>
                        </div>
                        {duelChallenge.direction === "incoming" ? (
                          <div className="score-duel-challenge">
                            <p className="score-section-value"><strong>{duelChallenge.challengerName}</strong> challenges you!</p>
                            <div className="score-duel-actions">
                              <button type="button" className="soft-button" onClick={() => onCommand("duel accept")}>Accept</button>
                              <button type="button" className="soft-button" onClick={() => onCommand("duel decline")}>Decline</button>
                            </div>
                          </div>
                        ) : (
                          <p className="score-section-value">Challenging <strong>{duelChallenge.targetName}</strong>&hellip;</p>
                        )}
                      </div>
                    )}
                    {dungeonInfo?.active && (
                      <div className="score-dungeon">
                        <div className="score-section-header">
                          <p className="score-section-label">Dungeon</p>
                          <button type="button" className="soft-button score-open-btn" onClick={() => onOpenSystem("dungeon")}>Open</button>
                        </div>
                        <dl className="score-dungeon-grid">
                          {dungeonInfo.name && (
                            <div><dt>Name</dt><dd>{dungeonInfo.name}</dd></div>
                          )}
                          {dungeonInfo.difficulty && (
                            <div><dt>Difficulty</dt><dd>{dungeonInfo.difficulty}</dd></div>
                          )}
                          {dungeonInfo.totalRooms != null && (
                            <div><dt>Rooms</dt><dd>{dungeonInfo.totalRooms}</dd></div>
                          )}
                          {dungeonInfo.memberCount != null && (
                            <div><dt>Party</dt><dd>{dungeonInfo.memberCount}</dd></div>
                          )}
                        </dl>
                        {dungeonInfo.completed && <p className="score-dungeon-complete">Boss defeated!</p>}
                        <div className="score-dungeon-actions">
                          <button type="button" className="soft-button" onClick={() => onCommand("dungeon leave")}>Leave Dungeon</button>
                        </div>
                      </div>
                    )}
                    {prestigeInfo && prestigeInfo.perks.length > 0 && (
                      <div className="score-prestige-perks">
                        <div className="score-section-header">
                          <p className="score-section-label">Prestige Perks</p>
                          <button type="button" className="soft-button score-open-btn" onClick={() => onOpenSystem("prestige")}>Open</button>
                        </div>
                        <ul className="prestige-perk-list">
                          {prestigeInfo.perks.map((perk) => (
                            <li key={perk.rank} className={`prestige-perk-item ${perk.earned ? "prestige-perk-earned" : "prestige-perk-locked"}`}>
                              <span className="prestige-perk-rank">{perk.earned ? "\u2713" : perk.rank}</span>
                              <span className="prestige-perk-desc">{perk.description}</span>
                            </li>
                          ))}
                        </ul>
                      </div>
                    )}
                  </div>
                )}
              </section>
            )}

            {activeDetailTab === "factions" && (
              <section
                key="factions"
                className="character-detail-panel character-detail-panel-flip character-factions"
                role="tabpanel"
                aria-label="Factions"
              >
                {factions.length === 0 ? (
                  <p className="empty-note">No faction standings yet.</p>
                ) : (
                  <>
                    <div className="score-section-header score-section-header-spaced">
                      <p className="score-section-label">Faction Journal</p>
                      <button type="button" className="soft-button score-open-btn" onClick={() => onOpenSystem("factions")}>Open</button>
                    </div>
                    <ul className="factions-list">
                      {factions.map((f) => (
                        <li key={f.id} className="faction-item">
                          <div className="faction-header">
                            <span className="faction-name">{f.name}</span>
                            <span className={`faction-tier faction-tier-${factionTierClass(f.tier)}`}>{f.tier}</span>
                          </div>
                          <div className="faction-rep-row">
                            <div className="meter-track faction-rep-track">
                              <span
                                className={`meter-fill faction-rep-fill faction-rep-fill-${factionTierClass(f.tier)}`}
                                style={{ width: `${Math.min(100, Math.max(0, ((f.reputation + 1000) / 2000) * 100))}%` }}
                              />
                            </div>
                            <span className="faction-rep-value">{f.reputation}</span>
                          </div>
                        </li>
                      ))}
                    </ul>
                  </>
                )}
              </section>
            )}
          </div>
        </article>

        {hasCharacterProfile && petState?.active && (
          <article className="subpanel character-pet-subpanel">
            <div className="pet-header-row">
              {petState.image && (
                <img
                  src={petState.image}
                  alt={petState.name ?? "Pet"}
                  className="pet-thumbnail"
                />
              )}
              <div className="pet-header-text">
                <p className="pet-name">{petState.name ?? "Pet"}</p>
                {petState.hp != null && petState.maxHp != null && (
                  <Bar
                    label="HP"
                    tone="hp"
                    value={petState.hp}
                    max={Math.max(1, petState.maxHp)}
                    text={`${petState.hp} / ${petState.maxHp}`}
                  />
                )}
              </div>
            </div>
            <dl className="stat-grid pet-stat-grid">
              {petState.minDamage != null && petState.maxDamage != null && (
                <div><dt>Damage</dt><dd>{petState.minDamage}&ndash;{petState.maxDamage}</dd></div>
              )}
              {petState.armor != null && (
                <div><dt>Armor</dt><dd>{petState.armor}</dd></div>
              )}
            </dl>
            <div className="pet-actions">
              <button
                type="button"
                className="soft-button"
                onClick={() => onOpenSystem("pet")}
              >
                Manage Pet
              </button>
              <button
                type="button"
                className="soft-button pet-dismiss-btn"
                onClick={() => onCommand("pet dismiss")}
              >
                Dismiss
              </button>
            </div>
          </article>
        )}

        {hasCharacterProfile && (worldTime || worldWeather || worldEvents.length > 0) && (
          <article className="subpanel world-atmosphere-subpanel">
            <p className="world-atmosphere-heading">World</p>
            {worldTime && (
              <div className="world-atmosphere-row">
                <span className="world-atmosphere-icon" aria-hidden="true">{periodIcon(worldTime.period)}</span>
                <span className="world-atmosphere-label">{periodLabel(worldTime.period)}</span>
                <span className="world-atmosphere-value">{String(worldTime.hour).padStart(2, "0")}:{String(worldTime.minute).padStart(2, "0")}</span>
              </div>
            )}
            {worldWeather && worldWeather.weather !== "CLEAR" && (
              <div className="world-atmosphere-row" title={worldWeather.description}>
                <span className="world-atmosphere-icon" aria-hidden="true">{weatherIcon(worldWeather.weather)}</span>
                <span className="world-atmosphere-label">{weatherLabel(worldWeather.weather)}</span>
              </div>
            )}
            {worldEvents.length > 0 && worldEvents.map((evt) => (
              <div key={evt.id} className="world-atmosphere-row world-atmosphere-event" title={evt.description}>
                <span className="world-atmosphere-icon world-atmosphere-event-icon" aria-hidden="true">&#x2726;</span>
                <span className="world-atmosphere-label">{evt.name}</span>
              </div>
            ))}
          </article>
        )}

        {hasCharacterProfile && (
          <div className="character-logout-row">
            <button type="button" className="soft-button character-logout-btn" onClick={onLogout}>
              Log out
            </button>
          </div>
        )}
      </div>
    </section>
  );
}

function periodIcon(period: string): string {
  switch (period) {
    case "DAWN": return "☼";
    case "DAY": return "☀";
    case "DUSK": return "☽";
    case "NIGHT": return "☾";
    default: return "☀";
  }
}

function periodLabel(period: string): string {
  switch (period) {
    case "DAWN": return "Dawn";
    case "DAY": return "Day";
    case "DUSK": return "Dusk";
    case "NIGHT": return "Night";
    default: return period;
  }
}

function weatherIcon(weather: string): string {
  switch (weather) {
    case "RAIN": return "☂";
    case "STORM": return "⚡";
    case "FOG": return "☁";
    case "SNOW": return "❄";
    case "WIND": return "☴";
    default: return "";
  }
}

function weatherLabel(weather: string): string {
  switch (weather) {
    case "CLEAR": return "Clear";
    case "RAIN": return "Rain";
    case "STORM": return "Storm";
    case "FOG": return "Fog";
    case "SNOW": return "Snow";
    case "WIND": return "Wind";
    default: return weather;
  }
}
