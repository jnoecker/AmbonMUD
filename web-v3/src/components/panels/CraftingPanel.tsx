import { useState } from "react";
import type { CraftingSkill, CraftingRecipe, CraftingNode } from "../../types";

interface CraftingPanelProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  skills: CraftingSkill[];
  recipes: CraftingRecipe[];
  nodes: CraftingNode[];
  onGather: (keyword: string) => void;
  onCraft: (recipeKeyword: string) => void;
  onRequestRecipes: () => void;
}

export function CraftingPanel({
  connected,
  hasCharacterProfile,
  skills,
  recipes,
  nodes,
  onGather,
  onCraft,
  onRequestRecipes,
}: CraftingPanelProps) {
  const [activeTab, setActiveTab] = useState<"skills" | "recipes" | "nodes">("skills");

  if (!connected) return <p className="empty-note">Connect to view crafting.</p>;
  if (!hasCharacterProfile) return <p className="empty-note">Log in to view crafting.</p>;

  const skillLevels = new Map(skills.map((s) => [s.name, s.level]));

  return (
    <div className="crafting-panel">
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
          onClick={() => { setActiveTab("recipes"); if (recipes.length === 0) onRequestRecipes(); }}
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
              <p className="empty-note">No crafting skills yet. Type <code>craftskills</code> to load.</p>
            </div>
          ) : (
            <ul className="crafting-skill-list">
              {skills.map((s) => {
                const pct = s.level >= s.maxLevel ? 100 : s.xpToNext > 0 ? Math.round((s.xp / s.xpToNext) * 100) : 0;
                return (
                  <li key={s.id} className="crafting-skill-item">
                    <div className="crafting-skill-header">
                      <span className="crafting-skill-name">{s.name}</span>
                      <span className="crafting-skill-type">{s.type === "gathering" ? "Gathering" : "Crafting"}</span>
                    </div>
                    <div className="crafting-skill-level">
                      Lv {s.level} / {s.maxLevel}
                    </div>
                    <div
                      className="crafting-xp-bar-track"
                      role="progressbar"
                      aria-valuenow={pct}
                      aria-valuemin={0}
                      aria-valuemax={100}
                      aria-label={`${s.name} XP progress`}
                    >
                      <div className="crafting-xp-bar-fill" style={{ width: `${pct}%` }} />
                    </div>
                    <div className="crafting-xp-label">
                      {s.level >= s.maxLevel ? "MAX" : `${s.xp} / ${s.xpToNext} XP`}
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
              <p className="empty-note">No recipes loaded. Type <code>recipes</code> to browse.</p>
            </div>
          ) : (
            <ul className="crafting-recipe-list">
              {recipes.map((r) => {
                const playerLevel = skillLevels.get(r.skill) ?? 0;
                const canCraft = playerLevel >= r.skillRequired;
                return (
                  <li key={r.id} className="crafting-recipe-item">
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
                      <span className={!canCraft ? "crafting-req-unmet" : ""}>{r.skill} Lv {r.skillRequired}</span>
                      {r.levelRequired > 1 && <span>Char Lv {r.levelRequired}</span>}
                    </div>
                    <div className="crafting-recipe-materials">
                      {r.materials.map((m, i) => (
                        <span key={i} className="crafting-material">{m.name} x{m.quantity}</span>
                      ))}
                      <span className="crafting-recipe-arrow">&rarr;</span>
                      <span className="crafting-recipe-output">
                        {r.outputName}{r.outputQuantity > 1 ? ` x${r.outputQuantity}` : ""}
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
            <p className="empty-note">No gathering nodes here.</p>
          ) : (
            <ul className="crafting-node-list">
              {nodes.map((n) => {
                const playerLevel = skillLevels.get(n.skill) ?? 0;
                const canGather = playerLevel >= n.skillRequired;
                return (
                  <li key={n.id} className="crafting-node-item">
                    <div className="crafting-node-info">
                      <span className="crafting-node-name">{n.name}</span>
                      <span className={`crafting-node-skill ${!canGather ? "crafting-req-unmet" : ""}`}>
                        {n.skill} Lv {n.skillRequired}
                      </span>
                    </div>
                    <button
                      type="button"
                      className="crafting-gather-button"
                      disabled={!canGather}
                      onClick={() => onGather(n.name)}
                    >
                      Gather
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
