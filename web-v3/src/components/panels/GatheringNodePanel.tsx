import { useEffect, useState } from "react";
import type { CraftingNode, CraftingSkill } from "../../types";

interface GatheringNodePanelProps {
  node: CraftingNode;
  /** The player's crafting/gathering skills, for the "yours vs. required" line. */
  skills: CraftingSkill[];
  /** Client-clock instant when the player may gather again (0 = ready). */
  gatherCooldownUntilMs: number;
  /** Optional parchment-frame art (node_manual_bg, falling back to the item/monster frames). */
  bg?: string;
  serverAssets: Record<string, string>;
  onClose: () => void;
  onCommand: (cmd: string) => void;
  onZoomImage: (url: string) => void;
}

function formatSeconds(ms: number): string {
  const s = Math.ceil(ms / 1000);
  if (s >= 3600) return `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`;
  if (s >= 60) return `${Math.floor(s / 60)}m ${s % 60}s`;
  return `${s}s`;
}

function quantityLabel(min: number, max: number): string {
  return min === max ? `×${min}` : `×${min}–${max}`;
}

/**
 * Parchment card for a clicked gathering node — the gathering analogue of the
 * item manual. Shows the node's art, the skill it takes against the player's
 * own level, what it yields (with rare-drop odds), and the respawn / cooldown
 * state, with Gather as the primary action. Clicking a node used to fire
 * `gather` immediately, so a player never saw what a node was for.
 */
export function GatheringNodePanel({
  node,
  skills,
  gatherCooldownUntilMs,
  bg,
  serverAssets,
  onClose,
  onCommand,
  onZoomImage,
}: GatheringNodePanelProps) {
  const skinned = !!bg;
  const skill = skills.find((s) => s.id === node.skill) ?? null;
  const skillName = skill?.name ?? node.skill;
  const yourLevel = skill?.level ?? 0;
  const skillOk = yourLevel >= node.skillRequired;

  // One-second tick while a timer is showing.
  const [now, setNow] = useState(() => Date.now());
  const respawnLeft = node.respawnAtMs != null ? Math.max(0, node.respawnAtMs - now) : 0;
  const cooldownLeft = Math.max(0, gatherCooldownUntilMs - now);
  const timing = respawnLeft > 0 || cooldownLeft > 0;
  useEffect(() => {
    if (!timing) return;
    const t = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(t);
  }, [timing]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const canGather = skillOk && respawnLeft === 0 && cooldownLeft === 0;
  const blocker = !skillOk
    ? `Needs ${skillName} ${node.skillRequired} (you have ${yourLevel})`
    : respawnLeft > 0
      ? `Depleted — regrows in ${formatSeconds(respawnLeft)}`
      : cooldownLeft > 0
        ? `You can gather again in ${formatSeconds(cooldownLeft)}`
        : null;

  const actionIcon = (assetKey: string, glyph: string) => {
    const url = serverAssets[assetKey];
    if (url) return <img className="mm-action-icon" src={url} alt="" aria-hidden="true" />;
    return <span className="mm-action-glyph" aria-hidden="true">{glyph}</span>;
  };

  return (
    <div
      className="mm-backdrop"
      role="dialog"
      aria-modal="true"
      aria-label={`${node.name} — gathering node`}
      onClick={onClose}
    >
      <div
        className={`mm-card mm-card-node${skinned ? " mm-card-skinned" : ""}`}
        style={bg ? { ["--mm-bg" as string]: `url("${bg}")` } : undefined}
        onClick={(e) => e.stopPropagation()}
      >
        <button type="button" className="mm-close-x" aria-label="Close" onClick={onClose}>✕</button>

        <figure className="mm-figure">
          {node.image ? (
            <>
              <img className="mm-image" src={node.image} alt={node.name} />
              <button
                type="button"
                className="mm-zoom"
                title="Enlarge"
                aria-label={`Enlarge ${node.name}`}
                onClick={() => onZoomImage(node.image!)}
              >
                ⌖
              </button>
            </>
          ) : (
            <div className="mm-image mm-image-empty" aria-hidden="true" />
          )}
        </figure>

        <div className="mm-content">
          <header className="mm-head">
            <div className="mm-titles">
              <h2 className="mm-name">{node.name}</h2>
              <span className={`mm-meta node-skill-line${skillOk ? "" : " node-skill-line-short"}`}>
                {skillName} {node.skillRequired}
                {" · "}yours {yourLevel}
              </span>
            </div>
          </header>

          <section className="node-yields" aria-label="Yields">
            <h3 className="node-section-title">Yields</h3>
            <ul className="node-yield-list">
              {node.yields.map((y) => (
                <li key={y.itemId} className="node-yield">
                  {y.image && <img className="node-yield-img" src={y.image} alt="" aria-hidden="true" />}
                  <span className="node-yield-name">{y.name}</span>
                  <span className="node-yield-qty">{quantityLabel(y.minQuantity, y.maxQuantity)}</span>
                </li>
              ))}
              {node.rareYields.map((y) => (
                <li key={`rare-${y.itemId}`} className="node-yield node-yield-rare">
                  {y.image && <img className="node-yield-img" src={y.image} alt="" aria-hidden="true" />}
                  <span className="node-yield-name">{y.name}</span>
                  <span className="node-yield-qty">
                    {quantityLabel(y.quantity, y.quantity)}
                    <span className="node-yield-chance"> {y.chancePct}%</span>
                  </span>
                </li>
              ))}
              {node.yields.length === 0 && node.rareYields.length === 0 && (
                <li className="node-yield node-yield-unknown">Nothing recorded</li>
              )}
            </ul>
          </section>

          <dl className="mm-stats node-stats">
            {node.xpReward > 0 && <div className="mm-stat"><dt>Skill XP</dt><dd>+{node.xpReward}</dd></div>}
            {node.respawnSeconds > 0 && <div className="mm-stat"><dt>Regrows</dt><dd>{formatSeconds(node.respawnSeconds * 1000)}</dd></div>}
            <div className="mm-stat"><dt>State</dt><dd>{respawnLeft > 0 ? "depleted" : "ready"}</dd></div>
          </dl>

          {blocker && <p className="mm-desc node-blocker" role="status">{blocker}</p>}

          <footer className="mm-actions">
            <button
              type="button"
              className="mm-action mm-action-primary"
              disabled={!canGather}
              title={blocker ?? `Gather from ${node.name}`}
              onClick={() => { onCommand(`gather ${node.name}`); onClose(); }}
            >
              {actionIcon("action_gather", "✦")}
              <span className="mm-action-label">Gather</span>
            </button>
          </footer>
        </div>
      </div>
    </div>
  );
}
