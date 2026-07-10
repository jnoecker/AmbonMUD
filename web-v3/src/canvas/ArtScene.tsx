import type { ReactNode } from "react";

/**
 * Shared painted-login-scene scaffolding: a fixed full-viewport root with the
 * scene art as a blurred cover underlay (cinematic letterbox), plus an
 * aspect-locked stage the callers seat live controls into with percentage
 * insets (see the login-art block in styles.css and docs/ART_CONTRACT.md).
 *
 * The art is painted by real <img> elements, not a CSS `background-image`.
 * useArtImageState gates this scene on a probe Image loading, but a hard
 * refresh bypasses the service worker for the markup preload/subresources, so
 * the CSS-background fetch could diverge from that probe — leaving the probe
 * "ready" (controls revealed) while the background painted black. Painting
 * through an <img> uses the exact loading path the probe already proved works,
 * so the revealed scene and its pixels can no longer come apart.
 */
export function ArtScene({ url, stageClass, label, children }: {
  url: string;
  stageClass: string;
  label: string;
  children: ReactNode;
}) {
  return (
    <div className="login-art-root" role="dialog" aria-modal="true" aria-label={label}>
      <img
        className="login-art-underlay"
        src={url}
        alt=""
        aria-hidden="true"
        draggable={false}
        decoding="async"
        fetchPriority="low"
      />
      <main className={`login-art-stage ${stageClass}`}>
        <img
          className="login-art-stage-img"
          src={url}
          alt=""
          aria-hidden="true"
          draggable={false}
          decoding="async"
          fetchPriority="high"
        />
        {children}
      </main>
    </div>
  );
}
