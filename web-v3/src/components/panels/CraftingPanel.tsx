import { useEffect, useMemo, useRef, useState } from "react";
import type { CraftingRecipe, CraftingSkill, ItemSummary, UiFeedbackEntry } from "../../types";

interface CraftingPanelProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  recipes: CraftingRecipe[];
  skills: CraftingSkill[];
  inventory: ItemSummary[];
  uiFeedbackFeed: UiFeedbackEntry[];
  onCraft: (recipeName: string) => void;
  onRequestRecipes: () => void;
  onLoadSkills: () => void;
}

type Category = "all" | "smithing" | "alchemy" | "misc";

const TABS: { key: Category; label: string }[] = [
  { key: "all", label: "All" },
  { key: "smithing", label: "Smithing" },
  { key: "alchemy", label: "Alchemy" },
  { key: "misc", label: "Misc" },
];

function categoryOf(skill: string): Category {
  const s = skill.toLowerCase();
  if (s === "smithing") return "smithing";
  if (s === "alchemy") return "alchemy";
  return "misc";
}

function glyph(skill: string): string {
  switch (categoryOf(skill)) {
    case "smithing": return "⚒";
    case "alchemy": return "⚗";
    default: return "▣";
  }
}

/**
 * Crafting Recipes — reskinned onto the painted `crafting_bg` forge frame. The
 * left page lists known recipes (filterable by profession); the right page
 * shows the selected recipe with its required ingredients (have / need) and a
 * CRAFT control overlaid on the painted button.
 */
export function CraftingPanel({
  connected,
  hasCharacterProfile,
  recipes,
  skills,
  inventory,
  uiFeedbackFeed,
  onCraft,
  onRequestRecipes,
  onLoadSkills,
}: CraftingPanelProps) {
  const ready = connected && hasCharacterProfile;
  const [tab, setTab] = useState<Category>("all");
  const [selectedId, setSelectedId] = useState<string>("");
  const recipesRequested = useRef(false);
  const skillsRequested = useRef(false);

  useEffect(() => {
    if (!ready) return;
    if (recipes.length === 0 && !recipesRequested.current) {
      recipesRequested.current = true;
      onRequestRecipes();
    }
    if (skills.length === 0 && !skillsRequested.current) {
      skillsRequested.current = true;
      onLoadSkills();
    }
  }, [ready, recipes.length, skills.length, onRequestRecipes, onLoadSkills]);

  const feedback = useMemo(
    () => uiFeedbackFeed.filter((e) => e.scope === "crafting").slice(-1)[0] ?? null,
    [uiFeedbackFeed],
  );

  // Inventory counts by item name, for ingredient have / need.
  const counts = useMemo(() => {
    const m = new Map<string, number>();
    for (const it of inventory) {
      const k = it.name.toLowerCase();
      m.set(k, (m.get(k) ?? 0) + 1);
    }
    return m;
  }, [inventory]);
  const have = (name: string) => counts.get(name.toLowerCase()) ?? 0;

  const skillLevels = useMemo(() => new Map(skills.map((s) => [s.name.toLowerCase(), s.level])), [skills]);

  const filtered = useMemo(
    () => (tab === "all" ? recipes : recipes.filter((r) => categoryOf(r.skill) === tab)),
    [recipes, tab],
  );

  const selected = filtered.find((r) => r.id === selectedId) ?? filtered[0] ?? null;

  const recipeStatus = (r: CraftingRecipe) => {
    const levelOk = (skillLevels.get(r.skill.toLowerCase()) ?? 0) >= r.skillRequired;
    const materialsOk = r.materials.every((m) => have(m.name) >= m.quantity);
    return { levelOk, materialsOk, canCraft: levelOk && materialsOk };
  };

  if (!connected) return <div className="cr-board"><p className="cr-empty">Connect to view crafting.</p></div>;
  if (!hasCharacterProfile) return <div className="cr-board"><p className="cr-empty">Log in to view crafting.</p></div>;

  return (
    <div className="cr-board">
      {feedback && (
        <p key={feedback.id} className={`cr-toast cr-toast-${feedback.type}`} role="status" aria-live="polite">
          {feedback.message}
        </p>
      )}

      {/* ───────── Recipe list (left page) ───────── */}
      <div className="cr-recipes">
        <div className="cr-tabs" role="tablist" aria-label="Recipe categories">
          {TABS.map((t) => (
            <button
              key={t.key}
              type="button"
              role="tab"
              aria-selected={tab === t.key}
              aria-controls="cr-recipe-list"
              className={`cr-tab${tab === t.key ? " cr-tab-active" : ""}`}
              onClick={() => { setTab(t.key); setSelectedId(""); }}
            >
              {t.label}
            </button>
          ))}
        </div>
        <div id="cr-recipe-list" className="cr-recipe-list">
          {filtered.length === 0 ? (
            <p className="cr-empty">No recipes known in this craft.</p>
          ) : (
            filtered.map((r) => (
              <button
                key={r.id}
                type="button"
                className={`cr-recipe${selected?.id === r.id ? " cr-recipe-active" : ""}`}
                onClick={() => setSelectedId(r.id)}
              >
                <span className="cr-recipe-icon" aria-hidden="true">{glyph(r.skill)}</span>
                <span className="cr-recipe-text">
                  <span className="cr-recipe-name">{r.name}</span>
                  <span className="cr-recipe-skill">{r.skill}</span>
                </span>
                <span className="cr-recipe-mark" aria-hidden="true">{glyph(r.skill)}</span>
              </button>
            ))
          )}
        </div>
      </div>

      {/* ───────── Selected recipe (right page) ───────── */}
      <div className="cr-detail">
        {selected ? (
          <>
            <div className="cr-detail-head">
              <div className="cr-detail-icon" aria-hidden="true">{glyph(selected.skill)}</div>
              <div className="cr-detail-titles">
                <h3 className="cr-detail-name">{selected.name}</h3>
                <p className="cr-detail-skill"><span className="cr-detail-skill-mark" aria-hidden="true">{glyph(selected.skill)}</span>{selected.skill}</p>
                <p className="cr-detail-desc">
                  Crafts {selected.outputName}
                  {selected.outputQuantity > 1 ? ` ×${selected.outputQuantity}` : ""}
                  {selected.skillRequired > 1 ? ` — requires ${selected.skill} Lv ${selected.skillRequired}` : ""}.
                </p>
              </div>
            </div>

            <div className="cr-ingredients">
              <div className="cr-ingredients-title">Required Ingredients</div>
              <div className="cr-ingredient-list">
                {selected.materials.map((m, i) => {
                  const has = have(m.name);
                  const ok = has >= m.quantity;
                  return (
                    <div key={i} className="cr-ingredient">
                      <span className="cr-ing-icon" aria-hidden="true">◈</span>
                      <span className="cr-ing-name">{m.name}</span>
                      <span className={`cr-ing-count${ok ? " cr-ing-ok" : " cr-ing-short"}`}>
                        {has} / {m.quantity}
                      </span>
                      <span className={`cr-ing-mark${ok ? " cr-ing-ok" : " cr-ing-short"}`} aria-hidden="true">
                        {ok ? "✓" : "✗"}
                      </span>
                    </div>
                  );
                })}
              </div>
            </div>
          </>
        ) : (
          <p className="cr-empty cr-detail-empty">Select a recipe to see its details.</p>
        )}
      </div>

      {/* Hotspot over the painted CRAFT button (board-level so it lands on the art). */}
      {selected && (
        <button
          type="button"
          className={`cr-craft-hotspot${recipeStatus(selected).canCraft ? "" : " cr-craft-disabled"}`}
          disabled={!recipeStatus(selected).canCraft}
          title={
            !recipeStatus(selected).levelOk
              ? `Requires ${selected.skill} Lv ${selected.skillRequired}`
              : !recipeStatus(selected).materialsOk
                ? "You are missing ingredients."
                : `Craft ${selected.name}`
          }
          onClick={() => onCraft(selected.name)}
        >
          <span className="sr-only">Craft {selected.name}</span>
        </button>
      )}
    </div>
  );
}
