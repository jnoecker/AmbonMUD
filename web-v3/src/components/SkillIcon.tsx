import { useState } from "react";
import type { SkillSummary } from "../types";
import { SkillCastIcon } from "./Icons";

interface SkillIconProps {
  skill: Pick<SkillSummary, "id" | "image" | "classRestriction" | "targetType">;
  /** Class for the `<img>` when the sprite loads. */
  imgClassName: string;
  /** Class for the drawn fallback glyph. */
  iconClassName: string;
}

/**
 * A skill's sprite, falling back to the drawn class/target glyph when the
 * ability has no image or the image fails to load. Without the error path a
 * missing CDN asset left the quickbar slot blank — the `<img>` has no alt
 * text on purpose, so a 404 rendered as nothing at all.
 */
export function SkillIcon({ skill, imgClassName, iconClassName }: SkillIconProps) {
  // The URL that last failed. Keyed by URL rather than a boolean so a skill
  // re-sent with a different sprite gets a fresh try without an effect.
  const [failedSrc, setFailedSrc] = useState<string | null>(null);

  const src = skill.image;
  if (src && failedSrc !== src) {
    return (
      <img
        src={src}
        alt=""
        className={imgClassName}
        draggable={false}
        onError={() => setFailedSrc(src)}
      />
    );
  }
  return <SkillCastIcon className={iconClassName} classRestriction={skill.classRestriction} targetType={skill.targetType} />;
}
