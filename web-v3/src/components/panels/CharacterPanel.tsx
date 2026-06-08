import { useMemo, useState } from "react";
import { formatCompact } from "../../utils";
import type {
  AchievementData,
  CharacterInfo,
  CharStats,
  CurrencyBalance,
  FactionStanding,
  GroupInfo,
  GuildInfo,
  LeaderboardData,
  PetState,
  PrestigeInfo,
  QuestEntry,
  QuestNotification,
  SpriteEntry,
  SpriteList,
  StatusEffect,
  StatusVarLabels,
  Vitals,
} from "../../types";

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
  prestigeInfo: PrestigeInfo | null;
  leaderboard: Record<string, LeaderboardData>;
  serverAssets: Record<string, string>;
  onDismissQuestNotification: (id: string) => void;
  onAbandonQuest: (questName: string) => void;
  onOpenInventory: () => void;
  onOpenEquipment: () => void;
  onCommand: (command: string) => void;
  onLogout: () => void;
}

const GENDERS: Array<{ value: string; label: string }> = [
  { value: "male", label: "Male" },
  { value: "female", label: "Female" },
  { value: "enby", label: "Other" },
];

/** Effect type → charm accent colour. */
function effectAccent(type: string): string {
  const t = type.toLowerCase();
  if (t.includes("heal") || t.includes("regen")) return "#7bbf6a";
  if (t.includes("buff") || t.includes("bless") || t.includes("haste") || t.includes("swift")) return "#6fae9a";
  if (t.includes("shield") || t.includes("protect") || t.includes("ward")) return "#c9a24a";
  if (t.includes("dot") || t.includes("burn") || t.includes("fire") || t.includes("poison") || t.includes("debuff")) return "#a86bd0";
  return "#8fa9c8";
}

function formatDuration(ms: number): string {
  const s = Math.max(0, Math.round(ms / 1000));
  if (s >= 3600) return `${Math.floor(s / 3600)}h`;
  if (s >= 60) return `${Math.floor(s / 60)}m`;
  return `${s}s`;
}

function CharBar({
  kind,
  label,
  value,
  max,
  text,
  title,
  pct,
}: {
  kind: "hp" | "mana" | "xp";
  label: string;
  value?: number;
  max?: number;
  text?: string;
  title?: string;
  pct: number;
}) {
  const clamped = Math.max(0, Math.min(100, pct));
  const display = text ?? (value != null && max != null ? `${formatCompact(value)} / ${formatCompact(max)}` : "");
  return (
    <div className={`cc-bar cc-bar-${kind}`}>
      <span className="cc-bar-icon" aria-hidden="true" />
      <span className="cc-bar-label">{label}</span>
      <span className="cc-bar-track">
        <span className="cc-bar-fill" style={{ width: `${clamped}%` }} />
      </span>
      <span className="cc-bar-value" title={title ?? (value != null && max != null ? `${value} / ${max}` : undefined)}>{display}</span>
    </div>
  );
}

export function CharacterPanel({
  connected,
  hasCharacterProfile,
  character,
  displayRace,
  displayClassName,
  vitals,
  statusVarLabels,
  xpValue,
  xpMax,
  xpText,
  visibleEffects,
  hiddenEffectsCount,
  achievements,
  charStats,
  activeTitle,
  spriteList,
  prestigeInfo,
  serverAssets,
  onCommand,
  onLogout,
}: CharacterPanelProps) {
  const [showAchievements, setShowAchievements] = useState(false);
  const [showPrestige, setShowPrestige] = useState(false);
  const [showScribe, setShowScribe] = useState(false);
  const [scribeDraft, setScribeDraft] = useState("");
  const [wimpyDraft, setWimpyDraft] = useState<number | null>(null);

  const a = (key: string): string | null => serverAssets[key] ?? null;

  const unlockedTitles = useMemo(
    () => achievements.completed.filter((x) => x.title !== null).map((x) => x.title as string),
    [achievements.completed],
  );

  // Sprite carousel: default (auto) + the player's unlocked sprites.
  const spriteOrder = useMemo<Array<{ id: string | null; name: string; image: string | null }>>(
    () => [
      { id: null, name: "Default (Auto)", image: character.sprite },
      ...spriteList.sprites.map((s: SpriteEntry) => ({ id: s.imageId, name: s.displayName, image: s.imagePath })),
    ],
    [spriteList.sprites, character.sprite],
  );
  const activeSpriteIdx = Math.max(
    0,
    spriteOrder.findIndex((s) => s.id === spriteList.active),
  );
  const cycleSprite = (dir: 1 | -1) => {
    if (spriteOrder.length <= 1) return;
    const next = (activeSpriteIdx + dir + spriteOrder.length) % spriteOrder.length;
    const target = spriteOrder[next];
    onCommand(target.id === null ? "sprite default" : `sprite set ${target.id}`);
  };
  const currentSpriteName = spriteOrder[activeSpriteIdx]?.name ?? character.name;

  const autolootEnabled = character.autolootEnabled;
  const autopeekEnabled = character.autopeekEnabled;
  const wimpyPct = wimpyDraft ?? character.wimpyThresholdPct ?? 0;

  const commitWimpy = (n: number) => {
    const clamped = Math.max(0, Math.min(100, Math.round(n)));
    setWimpyDraft(clamped);
    onCommand(`wimpy ${clamped}`);
  };

  if (!hasCharacterProfile) {
    return (
      <div className="char-cabinet char-cabinet-empty">
        <div className="cc-empty-card">
          <p className="cc-empty-title">{connected ? "Awaiting login" : "No active character"}</p>
          <p className="cc-empty-note">
            Your character sheet appears here once you step into the world.
          </p>
          {connected && (
            <button type="button" className="cc-empty-logout" onClick={onLogout}>
              Log out
            </button>
          )}
        </div>
      </div>
    );
  }

  const hpPct = vitals.maxHp > 0 ? (vitals.hp / vitals.maxHp) * 100 : 0;
  const manaPct = vitals.maxMana > 0 ? (vitals.mana / vitals.maxMana) * 100 : 0;
  const xpPct = xpMax > 0 ? (xpValue / xpMax) * 100 : 0;

  const niche = a("character_niche");
  const achBtn = a("char_btn_achievements");
  const preBtn = a("char_btn_prestige");
  const charmArt = a("character_charm");

  return (
    <div className="char-cabinet">
      <div className="cc-grid">
        {/* ── Showcase: sprite niche + carousel ─────────────── */}
        <section className="cc-showcase">
          <div
            className={`cc-niche${niche ? " has-art" : ""}`}
            style={niche ? { ["--cc-niche" as string]: `url("${niche}")` } : undefined}
          >
            {character.sprite ? (
              <img className="cc-sprite" src={character.sprite} alt={`${character.name} sprite`} draggable={false} />
            ) : (
              <div className="cc-sprite-empty" aria-hidden="true" />
            )}
          </div>
          <div className="cc-carousel">
            <button
              type="button"
              className="cc-carousel-arrow"
              aria-label="Previous sprite"
              disabled={spriteOrder.length <= 1}
              onClick={() => cycleSprite(-1)}
            >
              ‹
            </button>
            <span className="cc-carousel-name" title={currentSpriteName}>{currentSpriteName}</span>
            <button
              type="button"
              className="cc-carousel-arrow"
              aria-label="Next sprite"
              disabled={spriteOrder.length <= 1}
              onClick={() => cycleSprite(1)}
            >
              ›
            </button>
          </div>
        </section>

        {/* ── Identity card ─────────────────────────────────── */}
        <section className="cc-frame cc-identity">
          <div className="cc-id-row">
            <span className="cc-id-label">Name</span>
            <span className="cc-id-value cc-id-name">
              {character.name}
              <button
                type="button"
                className="cc-scribe-btn"
                aria-label="Edit your description"
                title="Inscribe your tale"
                onClick={() => setShowScribe(true)}
              >
                ✒
              </button>
            </span>
          </div>
          <div className="cc-id-row cc-id-row-split">
            <span className="cc-id-label">Race</span>
            <span className="cc-id-value">{displayRace}</span>
            <span className="cc-id-label">Class</span>
            <span className="cc-id-value">{displayClassName}</span>
          </div>
          <div className="cc-id-row">
            <span className="cc-id-label">Title</span>
            <select
              className="cc-select"
              value={activeTitle ?? ""}
              onChange={(e) => onCommand(e.target.value ? `title ${e.target.value}` : "title clear")}
            >
              <option value="">None</option>
              {unlockedTitles.map((t) => (
                <option key={t} value={t}>{t}</option>
              ))}
            </select>
          </div>
          <div className="cc-id-row">
            <span className="cc-id-label">Gender</span>
            <div className="cc-segmented" role="group" aria-label="Gender">
              {GENDERS.map((g) => (
                <button
                  key={g.value}
                  type="button"
                  className={`cc-seg${character.gender === g.value ? " is-active" : ""}`}
                  aria-pressed={character.gender === g.value}
                  onClick={() => onCommand(`gender ${g.value}`)}
                >
                  {g.label}
                </button>
              ))}
            </div>
          </div>

          <div className="cc-controls">
            <button
              type="button"
              className={`cc-ctl cc-ctl-toggle${autolootEnabled ? " is-on" : ""}`}
              role="switch"
              aria-checked={autolootEnabled}
              disabled={!connected}
              title="Mob drops go straight into your pack after a kill."
              onClick={() => onCommand(autolootEnabled ? "autoloot off" : "autoloot on")}
            >
              <span className="cc-ctl-leaf" aria-hidden="true" />
              <span className="cc-ctl-name">Auto Loot</span>
              <span className="cc-switch"><span className="cc-switch-thumb" /></span>
            </button>
            <button
              type="button"
              className={`cc-ctl cc-ctl-toggle${autopeekEnabled ? " is-on" : ""}`}
              role="switch"
              aria-checked={autopeekEnabled}
              disabled={!connected}
              title="Room descriptions name what lies through each open exit."
              onClick={() => onCommand(autopeekEnabled ? "autopeek off" : "autopeek on")}
            >
              <span className="cc-ctl-leaf" aria-hidden="true" />
              <span className="cc-ctl-name">Auto Peek</span>
              <span className="cc-switch"><span className="cc-switch-thumb" /></span>
            </button>
            <div className="cc-ctl cc-ctl-wimpy" title="Auto-flee combat when HP drops to this %.">
              <span className="cc-ctl-leaf" aria-hidden="true" />
              <span className="cc-ctl-name">Wimpy</span>
              <button
                type="button"
                className="cc-step"
                aria-label="Lower wimpy threshold"
                disabled={!connected || wimpyPct <= 0}
                onClick={() => commitWimpy(wimpyPct - 5)}
              >
                −
              </button>
              <span className="cc-step-val">{wimpyPct}%</span>
              <button
                type="button"
                className="cc-step"
                aria-label="Raise wimpy threshold"
                disabled={!connected || wimpyPct >= 100}
                onClick={() => commitWimpy(wimpyPct + 5)}
              >
                +
              </button>
            </div>
          </div>
        </section>

        {/* ── Readouts: progression, bars, effects, derived ──── */}
        <section className="cc-frame cc-readouts">
          <div className="cc-topline">
            <span className="cc-topline-item"><b className="cc-chip">Level</b> {vitals.level ?? "—"}</span>
            <span className="cc-topline-item">Total XP: {vitals.xp.toLocaleString()}</span>
            <span className="cc-topline-item">Gold: {vitals.gold.toLocaleString()}</span>
          </div>

          <div className="cc-bars">
            <CharBar kind="hp" label={statusVarLabels.hp} value={vitals.hp} max={vitals.maxHp} pct={hpPct} />
            <CharBar kind="mana" label={statusVarLabels.mana} value={vitals.mana} max={vitals.maxMana} pct={manaPct} />
            <CharBar kind="xp" label={statusVarLabels.xp} text={xpText} pct={xpPct} />
          </div>

          <div className="cc-effects">
            <h4 className="cc-section-title">Active Effects</h4>
            {visibleEffects.length === 0 ? (
              <p className="cc-effects-empty">No active effects.</p>
            ) : (
              <div className="cc-charms">
                {visibleEffects.map((effect, i) => (
                  <div
                    key={`${effect.name}-${i}`}
                    className={`cc-charm${charmArt ? " has-art" : ""}`}
                    style={{
                      ["--cc-charm-accent" as string]: effectAccent(effect.type),
                      ...(charmArt ? { ["--cc-charm" as string]: `url("${charmArt}")` } : {}),
                    }}
                    title={`${effect.name}${effect.stacks > 1 ? ` ×${effect.stacks}` : ""}`}
                  >
                    <span className="cc-charm-glyph" aria-hidden="true" />
                    <span className="cc-charm-name">{effect.name}</span>
                    <span className="cc-charm-time">{formatDuration(effect.remainingMs)}</span>
                  </div>
                ))}
                {hiddenEffectsCount > 0 && <div className="cc-charm cc-charm-more">+{hiddenEffectsCount}</div>}
              </div>
            )}
          </div>

          <div className="cc-derived">
            <div className="cc-plaque cc-derived-item">
              <span className="cc-derived-label">Damage</span>
              <span
                className="cc-derived-value"
                title={charStats ? `${charStats.baseDamageMin.toLocaleString()} – ${charStats.baseDamageMax.toLocaleString()}` : undefined}
              >
                {charStats ? `${formatCompact(charStats.baseDamageMin)} – ${formatCompact(charStats.baseDamageMax)}` : "—"}
              </span>
            </div>
            <div className="cc-plaque cc-derived-item">
              <span className="cc-derived-label">Armor</span>
              <span className="cc-derived-value">{charStats ? charStats.armor : "—"}</span>
            </div>
            <div className="cc-plaque cc-derived-item">
              <span className="cc-derived-label">Dodge</span>
              <span className="cc-derived-value">{charStats ? `${charStats.dodgePercent}%` : "—"}</span>
            </div>
          </div>
        </section>

        {/* ── Stats plaque column ───────────────────────────── */}
        <aside className="cc-plaque cc-stats">
          <h4 className="cc-stats-title">Stats</h4>
          {charStats && charStats.stats.length > 0 ? (
            <ul className="cc-stats-list">
              {charStats.stats.map((s) => (
                <li key={s.id} className="cc-stat-row" title={s.name}>
                  <span className="cc-stat-abbrev">{s.abbrev}</span>
                  <span className="cc-stat-value">{s.effective}</span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="cc-stats-empty">—</p>
          )}
        </aside>
      </div>

      {/* ── Action buttons ─────────────────────────────────── */}
      <div className="cc-actions">
        <button
          type="button"
          className={`cc-gem-btn cc-gem-purple${preBtn ? " has-art" : ""}`}
          style={preBtn ? { ["--cc-btn" as string]: `url("${preBtn}")` } : undefined}
          onClick={() => setShowPrestige(true)}
        >
          <span className="cc-gem-label">Prestige</span>
        </button>
        <button
          type="button"
          className={`cc-gem-btn cc-gem-green${achBtn ? " has-art" : ""}`}
          style={achBtn ? { ["--cc-btn" as string]: `url("${achBtn}")` } : undefined}
          onClick={() => setShowAchievements(true)}
        >
          <span className="cc-gem-label">Achievements</span>
        </button>
      </div>

      {/* ── Describe pop-out ──────────────────────────────── */}
      {showScribe && (
        <div className="cc-overlay" role="dialog" aria-label="Inscribe your tale" onClick={() => setShowScribe(false)}>
          <div
            className={`cc-scribe${a("character_scribe_bg") ? " has-art" : ""}`}
            style={a("character_scribe_bg") ? { ["--cc-scribe" as string]: `url("${a("character_scribe_bg")}")` } : undefined}
            onClick={(e) => e.stopPropagation()}
          >
            <h4 className="cc-scribe-title">Inscribe Your Tale</h4>
            <textarea
              className="cc-scribe-text"
              placeholder="Describe your character…"
              value={scribeDraft}
              onChange={(e) => setScribeDraft(e.target.value)}
              autoFocus
            />
            <div className="cc-scribe-actions">
              <button type="button" className="cc-scribe-cancel" onClick={() => setShowScribe(false)}>Cancel</button>
              <button
                type="button"
                className="cc-scribe-clear"
                onClick={() => { onCommand("describe clear"); setScribeDraft(""); setShowScribe(false); }}
              >
                Clear
              </button>
              <button
                type="button"
                className="cc-scribe-save"
                disabled={!scribeDraft.trim()}
                onClick={() => { onCommand(`describe ${scribeDraft.trim()}`); setShowScribe(false); }}
              >
                Inscribe
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── Achievements modal ────────────────────────────── */}
      {showAchievements && (
        <div className="cc-overlay" role="dialog" aria-label="Achievements" onClick={() => setShowAchievements(false)}>
          <div className="cc-frame cc-modal" onClick={(e) => e.stopPropagation()}>
            <div className="cc-modal-head">
              <h4 className="cc-modal-title">Achievements</h4>
              <button type="button" className="cc-modal-close" aria-label="Close" onClick={() => setShowAchievements(false)}>✕</button>
            </div>
            <div className="cc-modal-body">
              {achievements.inProgress.length > 0 && (
                <>
                  <p className="cc-section-title">In Progress</p>
                  <ul className="cc-ach-list">
                    {achievements.inProgress.map((ach) => (
                      <li key={ach.id} className="cc-ach-row">
                        <span className="cc-ach-name">{ach.name}</span>
                        <span className="cc-ach-bar">
                          <span className="cc-ach-fill" style={{ width: `${ach.required > 0 ? Math.min(100, (ach.current / ach.required) * 100) : 0}%` }} />
                        </span>
                        <span className="cc-ach-count">{ach.current}/{ach.required}</span>
                      </li>
                    ))}
                  </ul>
                </>
              )}
              <p className="cc-section-title">Earned ({achievements.completed.length})</p>
              {achievements.completed.length === 0 ? (
                <p className="cc-effects-empty">No achievements earned yet.</p>
              ) : (
                <ul className="cc-ach-list">
                  {achievements.completed.map((ach) => (
                    <li key={ach.id} className="cc-ach-row cc-ach-done">
                      <span className="cc-ach-name">{ach.name}</span>
                      {ach.title && <span className="cc-ach-title">“{ach.title}”</span>}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        </div>
      )}

      {/* ── Prestige placeholder ──────────────────────────── */}
      {showPrestige && (
        <div className="cc-overlay" role="dialog" aria-label="Prestige" onClick={() => setShowPrestige(false)}>
          <div className="cc-frame cc-modal cc-modal-prestige" onClick={(e) => e.stopPropagation()}>
            <div className="cc-modal-head">
              <h4 className="cc-modal-title">Prestige</h4>
              <button type="button" className="cc-modal-close" aria-label="Close" onClick={() => setShowPrestige(false)}>✕</button>
            </div>
            <div className="cc-modal-body cc-prestige-tbd">
              <p className="cc-prestige-glyph" aria-hidden="true">✦</p>
              <p className="cc-prestige-soon">The path of rebirth is being charted.</p>
              {prestigeInfo && (
                <p className="cc-prestige-meta">
                  Rank {prestigeInfo.currentRank} / {prestigeInfo.maxRank} · {prestigeInfo.availableXp.toLocaleString()} prestige points
                </p>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
