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

  return (
    <div className="crafting-panel">
      <div className="crafting-tab-bar">
        <button
          className={`crafting-tab ${activeTab === "skills" ? "crafting-tab-active" : ""}`}
          onClick={() => setActiveTab("skills")}
        >
          Professions
        </button>
        <button
          className={`crafting-tab ${activeTab === "recipes" ? "crafting-tab-active" : ""}`}
          onClick={() => { setActiveTab("recipes"); if (recipes.length === 0) onRequestRecipes(); }}
        >
          Recipes
        </button>
        {nodes.length > 0 && (
          <button
            className={`crafting-tab ${activeTab === "nodes" ? "crafting-tab-active" : ""}`}
            onClick={() => setActiveTab("nodes")}
          >
            Nodes
            <span className="crafting-tab-badge">{nodes.length}</span>
          </button>
        )}
      </div>

      {activeTab === "skills" && (
        <div className="crafting-section">
          {skills.length === 0 ? (
            <p className="empty-note">No crafting skills yet. Type <code>craftskills</code> to load.</p>
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
                    <div className="crafting-xp-bar-track">
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
        <div className="crafting-section">
          {recipes.length === 0 ? (
            <p className="empty-note">No recipes loaded. Type <code>recipes</code> to browse.</p>
          ) : (
            <ul className="crafting-recipe-list">
              {recipes.map((r) => (
                <li key={r.id} className="crafting-recipe-item">
                  <div className="crafting-recipe-header">
                    <span className="crafting-recipe-name">{r.name}</span>
                    <button className="crafting-craft-button" onClick={() => onCraft(r.name)}>
                      Craft
                    </button>
                  </div>
                  <div className="crafting-recipe-meta">
                    <span>{r.skill} Lv {r.skillRequired}</span>
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
              ))}
            </ul>
          )}
        </div>
      )}

      {activeTab === "nodes" && (
        <div className="crafting-section">
          {nodes.length === 0 ? (
            <p className="empty-note">No gathering nodes here.</p>
          ) : (
            <ul className="crafting-node-list">
              {nodes.map((n) => (
                <li key={n.id} className="crafting-node-item">
                  <div className="crafting-node-info">
                    <span className="crafting-node-name">{n.name}</span>
                    <span className="crafting-node-skill">{n.skill} Lv {n.skillRequired}</span>
                  </div>
                  <button className="crafting-gather-button" onClick={() => onGather(n.name)}>
                    Gather
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
