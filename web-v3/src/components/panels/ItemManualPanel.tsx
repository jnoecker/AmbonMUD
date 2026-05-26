import { useEffect } from "react";
import type { ItemEntry } from "../../types";

interface ItemManualPanelProps {
  item: ItemEntry;
  /** Optional parchment-frame art (item_manual_bg, falling back to monster_manual_bg). */
  bg?: string;
  /** Resolved server assets, for the optional action-button icons. */
  serverAssets: Record<string, string>;
  onClose: () => void;
  onCommand: (cmd: string) => void;
  onZoomImage: (url: string) => void;
  onVideo: (url: string) => void;
}

interface ItemAction {
  key: string;
  label: string;
  /** Unicode fallback shown when the icon asset isn't registered. */
  glyph: string;
  /** Server-asset key for a custom icon. */
  assetKey: string;
  variant: "primary" | "ghost";
  run: () => void;
}

/**
 * Parchment "field manual" page for a clicked room item — the item-screen
 * analogue of the monster manual: framed (zoomable) art, name, a prominent
 * description, and the real actions (Get / Look / Cinematic).
 */
export function ItemManualPanel({
  item,
  bg,
  serverAssets,
  onClose,
  onCommand,
  onZoomImage,
  onVideo,
}: ItemManualPanelProps) {
  const skinned = !!bg;
  const { name } = item;

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const actions: ItemAction[] = [];
  if (item.takeable) actions.push({ key: "get", label: "Get", glyph: "⤓", assetKey: "action_get", variant: "primary", run: () => { onCommand(`get ${name}`); onClose(); } });
  if (item.video) actions.push({ key: "video", label: "Cinematic", glyph: "▶", assetKey: "action_cinematic", variant: "ghost", run: () => onVideo(item.video!) });

  // An action icon: custom asset when registered, else the unicode glyph.
  const actionIcon = (assetKey: string, glyph: string) => {
    const url = serverAssets[assetKey];
    if (url) return <img className="mm-action-icon" src={url} alt="" aria-hidden="true" />;
    if (glyph) return <span className="mm-action-glyph" aria-hidden="true">{glyph}</span>;
    return null;
  };

  return (
    <div
      className="mm-backdrop"
      role="dialog"
      aria-modal="true"
      aria-label={`${name} — item`}
      onClick={onClose}
    >
      <div
        className={`mm-card${skinned ? " mm-card-skinned" : ""}`}
        style={bg ? { ["--mm-bg" as string]: `url("${bg}")` } : undefined}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Framed art (left) */}
        <figure className="mm-figure">
          {item.image ? (
            <>
              <img className="mm-image" src={item.image} alt={name} />
              <button
                type="button"
                className="mm-zoom"
                title="Enlarge"
                aria-label={`Enlarge ${name}`}
                onClick={() => onZoomImage(item.image!)}
              >
                ⌖
              </button>
            </>
          ) : (
            <div className="mm-image mm-image-empty" aria-hidden="true" />
          )}
        </figure>

        {/* Manual entry (right) */}
        <div className="mm-content">
          <header className="mm-head">
            <div className="mm-titles">
              <h2 className="mm-name">{name}</h2>
            </div>
          </header>

          {item.description && <p className="mm-desc">{item.description}</p>}

          <footer className="mm-actions">
            {actions.map((a) => (
              <button
                key={a.key}
                type="button"
                className={`mm-action mm-action-${a.variant}`}
                onClick={a.run}
              >
                {actionIcon(a.assetKey, a.glyph)}
                <span className="mm-action-label">{a.label}</span>
              </button>
            ))}
          </footer>
        </div>
      </div>
    </div>
  );
}
