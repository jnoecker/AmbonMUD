import type { CSSProperties, ReactNode } from "react";

/**
 * Shared painted-login-scene scaffolding: a fixed full-viewport root with the
 * scene art as a blurred cover underlay (cinematic letterbox), plus an
 * aspect-locked stage the callers seat live controls into with percentage
 * insets (see the login-art block in styles.css and docs/ART_CONTRACT.md).
 */
export function ArtScene({ url, stageClass, label, children }: {
  url: string;
  stageClass: string;
  label: string;
  children: ReactNode;
}) {
  return (
    <div
      className="login-art-root"
      role="dialog"
      aria-modal="true"
      aria-label={label}
      style={{ "--login-art": `url("${url}")` } as CSSProperties}
    >
      <div className="login-art-underlay" aria-hidden="true" />
      <main className={`login-art-stage ${stageClass}`}>{children}</main>
    </div>
  );
}
