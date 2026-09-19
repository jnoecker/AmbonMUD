import { describe, expect, test } from "bun:test";
import { renderToStaticMarkup } from "react-dom/server";
import { SkillIcon } from "../src/components/SkillIcon";
import { isSlottable, mergeSlotIds } from "../src/hooks/useQuickbar";
import type { SkillSummary } from "../src/types";

function skill(id: string, extra: Partial<SkillSummary> = {}): SkillSummary {
  return {
    id,
    name: id,
    description: "",
    manaCost: 5,
    cooldownMs: 0,
    cooldownRemainingMs: 0,
    levelRequired: 1,
    targetType: "ENEMY",
    effectType: "DIRECT_DAMAGE",
    classRestriction: "MAGE",
    image: null,
    visual: null,
    receivedAt: 0,
    source: "self",
    ...extra,
  };
}

const racial = skill("racial:stoneform", { source: "racial", passive: true, targetType: "SELF", effectType: "PASSIVE" });

describe("quickbar slots", () => {
  test("racial passives are not slottable", () => {
    expect(isSlottable(skill("fireball"))).toBe(true);
    expect(isSlottable(racial)).toBe(false);
    expect(isSlottable(skill("aura", { passive: true }))).toBe(false);
  });

  test("a racial never auto-fills an empty slot", () => {
    const merged = mergeSlotIds(new Array(9).fill(null), [skill("fireball"), racial, skill("frost")]);
    expect(merged.slice(0, 3)).toEqual(["fireball", "frost", null]);
    expect(merged).not.toContain("racial:stoneform");
  });

  test("a racial left in a stored slot from before is pruned", () => {
    const stored = ["fireball", "racial:stoneform", null, null, null, null, null, null, null];
    const merged = mergeSlotIds(stored, [skill("fireball"), racial]);
    expect(merged[1]).toBeNull();
  });
});

describe("SkillIcon", () => {
  test("renders the sprite when the skill has an image", () => {
    const html = renderToStaticMarkup(
      <SkillIcon skill={skill("fireball", { image: "/images/fireball.png" })} imgClassName="img" iconClassName="icon" />,
    );
    expect(html).toContain('src="/images/fireball.png"');
  });

  test("renders the drawn glyph when the skill has no image", () => {
    const html = renderToStaticMarkup(<SkillIcon skill={skill("fireball")} imgClassName="img" iconClassName="icon" />);
    expect(html).not.toContain("<img");
    expect(html).toContain("<svg");
  });
});
