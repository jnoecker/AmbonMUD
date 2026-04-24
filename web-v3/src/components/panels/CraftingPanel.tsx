import { useEffect, useRef, useState } from "react";
import type { CraftingSkill, CraftingRecipe, CraftingNode, UiFeedbackEntry } from "../../types";

interface CraftingPanelProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  skills: CraftingSkill[];
  recipes: CraftingRecipe[];
  nodes: CraftingNode[];
  gatherCooldownUntilMs: number;
  uiFeedbackFeed: UiFeedbackEntry[];
  onGather: (keyword: string) => void;
  onCraft: (recipeKeyword: string) => void;
  onRequestRecipes: () => void;
  onLoadSkills: () => void;
}

export function CraftingPanel({
  connected,
  hasCharacterProfile,
  skills,
  recipes,
  nodes,
  gatherCooldownUntilMs,
  uiFeedbackFeed,
  onGather,
  onCraft,
  onRequestRecipes,
  onLoadSkills,
}: CraftingPanelProps) {
  const [activeTab, setActiveTab] = useState<"skills" | "recipes" | "nodes">("skills");
  const skillsRequestedRef = useRef(false);
  const recipesRequestedRef = useRef(false);
  const ready = connected && hasCharacterProfile;

  const [now, setNow] = useState(() => Date.now());
  const gatherRemainingMs = Math.max(0, gatherCooldownUntilMs - now);
  const gatherOnCooldown = gatherRemainingMs > 0;

  useEffect(() => {
    if (!gatherOnCooldown) return;
    const id = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(id);
  }, [gatherOnCooldown]);

  const gatherCountdownLabel = gatherOnCooldown
    ? `Ready in ${Math.ceil(gatherRemainingMs / 1000)}s`
    : "Gather";

  const latestCraftingFeedback = uiFeedbackFeed
    .filter((entry) => entry.scope === "crafting")
    .slice(-1)[0];

  useEffect(() => {
    if (!ready) return;
    if (skills.length === 0 && !skillsRequestedRef.current) {
      skillsRequestedRef.current = true;
      onLoadSkills();
    }
  }, [ready, skills.length, onLoadSkills]);

  useEffect(() => {
    if (!ready || activeTab !== "recipes") return;
    if (recipes.length === 0 && !recipesRequestedRef.current) {
      recipesRequestedRef.current = true;
      onRequestRecipes();
    }
  }, [ready, activeTab, recipes.length, onRequestRecipes]);

  if (!connected) return <p className="empty-note">Connect to view crafting.</p>;
  if (!hasCharacterProfile) return <p className="empty-note">Log in to view crafting.</p>;

  const skillLevels = new Map(skills.map((s) => [s.name, s.level]));
  const skillTypesByName = new Map(skills.map((s) => [s.name, s.type]));

  return (
    <div className="crafting-panel">
      {latestCraftingFeedback && (
        <div
          key={latestCraftingFeedback.id}
          className={`crafting-feedback crafting-feedback-${latestCraftingFeedback.type}`}
          role="status"
          aria-live="polite"
        >
          {latestCraftingFeedback.message}
        </div>
      )}
      <div className="crafting-tab-bar" role="tablist">
        <button
          type="button"
          role="tab"
          className={`crafting-tab ${activeTab === "skills" ? "crafting-tab-active" : ""}`}
          aria-selected={activeTab === "skills"}
          onClick={() => setActiveTab("skills")}
        >
          Professions
        </button>
        <button
          type="button"
          role="tab"
          className={`crafting-tab ${activeTab === "recipes" ? "crafting-tab-active" : ""}`}
          aria-selected={activeTab === "recipes"}
          onClick={() => setActiveTab("recipes")}
        >
          Recipes
        </button>
        {nodes.length > 0 && (
          <button
            type="button"
            role="tab"
            className={`crafting-tab ${activeTab === "nodes" ? "crafting-tab-active" : ""}`}
            aria-selected={activeTab === "nodes"}
            onClick={() => setActiveTab("nodes")}
          >
            Nodes
            <span className="crafting-tab-badge">{nodes.length}</span>
          </button>
        )}
      </div>

      {activeTab === "skills" && (
        <div className="crafting-section" role="tabpanel" aria-label="Professions">
          {skills.length === 0 ? (
            <div className="crafting-empty-state">
              <span className="crafting-empty-icon">{"\u2692"}</span>
              <p className="empty-note">The workshop is quiet&hellip;</p>
            </div>
          ) : (
            <ul className="crafting-skill-list">
              {skills.map((s) => {
                const isMax = s.level >= s.maxLevel;
                const pct = isMax ? 100 : s.xpToNext > 0 ? Math.round((s.xp / s.xpToNext) * 100) : 0;
                const typeClass = `crafting-skill-item-${s.type}`;
                const maxClass = isMax ? "crafting-skill-item-max" : "";
                return (
                  <li key={s.id} className={`crafting-skill-item ${typeClass} ${maxClass}`.trim()}>
                    <div className="crafting-skill-header">
                      <span className="crafting-skill-name">{s.name}</span>
                      <span className={`crafting-skill-chip crafting-skill-chip-${s.type}`}>
                        {s.type === "gathering" ? "Gathering" : "Crafting"}
                      </span>
                    </div>
                    <div className="crafting-skill-level">
                      <span className="crafting-skill-level-label">Level</span>
                      <span className="crafting-skill-level-value">
                        {s.level}
                        <span className="crafting-skill-level-max"> / {s.maxLevel}</span>
                      </span>
                      {isMax && <span className="crafting-skill-max-badge">Mastered</span>}
                    </div>
                    <div
                      className={`crafting-xp-bar-track crafting-xp-bar-track-${s.type}`}
                      role="progressbar"
                      aria-valuenow={pct}
                      aria-valuemin={0}
                      aria-valuemax={100}
                      aria-label={`${s.name} XP progress`}
                    >
                      <div className={`crafting-xp-bar-fill crafting-xp-bar-fill-${s.type}`} style={{ width: `${pct}%` }} />
                    </div>
                    <div className="crafting-xp-label">
                      {isMax ? "\u2726 Mastered" : `${s.xp.toLocaleString()} / ${s.xpToNext.toLocaleString()} XP`}
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      )}

      {activeTab === "recipes" && (
        <div className="crafting-section" role="tabpanel" aria-label="Recipes">
          {recipes.length === 0 ? (
            <div className="crafting-empty-state">
              <span className="crafting-empty-icon">{"\u2726"}</span>
              <p className="empty-note">No recipes known yet.</p>
            </div>
          ) : (
            <ul className="crafting-recipe-list">
              {recipes.map((r) => {
                const playerLevel = skillLevels.get(r.skill) ?? 0;
                const canCraft = playerLevel >= r.skillRequired;
                const recipeType = skillTypesByName.get(r.skill) ?? "crafting";
                const typeClass = `crafting-recipe-item-${recipeType}`;
                const lockedClass = !canCraft ? "crafting-recipe-item-locked" : "";
                return (
                  <li key={r.id} className={`crafting-recipe-item ${typeClass} ${lockedClass}`.trim()}>
                    <div className="crafting-recipe-header">
                      <span className="crafting-recipe-name">{r.name}</span>
                      <button
                        type="button"
                        className="crafting-craft-button"
                        disabled={!canCraft}
                        onClick={() => onCraft(r.name)}
                      >
                        Craft
                      </button>
                    </div>
                    <div className="crafting-recipe-meta">
                      <span className={`crafting-recipe-req ${!canCraft ? "crafting-req-unmet" : ""}`}>
                        {r.skill} Lv {r.skillRequired}
                      </span>
                      {r.levelRequired > 1 && (
                        <span className="crafting-recipe-req">Char Lv {r.levelRequired}</span>
                      )}
                    </div>
                    <div className="crafting-recipe-materials">
                      {r.materials.map((m, i) => (
                        <span key={i} className="crafting-material">
                          <span className="crafting-material-name">{m.name}</span>
                          <span className="crafting-material-qty">&times;{m.quantity}</span>
                        </span>
                      ))}
                      <span className="crafting-recipe-arrow" aria-hidden="true">{"\u276f"}</span>
                      <span className="crafting-recipe-output">
                        {r.outputName}
                        {r.outputQuantity > 1 && (
                          <span className="crafting-recipe-output-qty">&times;{r.outputQuantity}</span>
                        )}
                      </span>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      )}

      {activeTab === "nodes" && (
        <div className="crafting-section" role="tabpanel" aria-label="Gathering Nodes">
          {nodes.length === 0 ? (
            <div className="crafting-empty-state">
              <span className="crafting-empty-icon">{"\u2698"}</span>
              <p className="empty-note">Nothing to gather here.</p>
            </div>
          ) : (
            <ul className="crafting-node-list">
              {nodes.map((n) => {
                const playerLevel = skillLevels.get(n.skill) ?? 0;
                const canGather = playerLevel >= n.skillRequired;
                const lockedClass = !canGather ? "crafting-node-item-locked" : "";
                return (
                  <li key={n.id} className={`crafting-node-item ${lockedClass}`.trim()}>
                    <div className="crafting-node-info">
                      <span className="crafting-node-name">{n.name}</span>
                      <span className={`crafting-node-skill ${!canGather ? "crafting-req-unmet" : ""}`}>
                        {n.skill} Lv {n.skillRequired}
                      </span>
                    </div>
                    <button
                      type="button"
                      className={`crafting-gather-button${gatherOnCooldown ? " crafting-gather-button-cooldown" : ""}`}
                      disabled={!canGather || gatherOnCooldown}
                      onClick={() => onGather(n.name)}
                      title={gatherOnCooldown ? gatherCountdownLabel : undefined}
                    >
                      {gatherOnCooldown ? gatherCountdownLabel : "Gather"}
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
