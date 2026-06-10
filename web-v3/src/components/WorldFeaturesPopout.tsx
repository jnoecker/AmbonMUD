import { useEffect, useRef, useState } from "react";
import type { ContainerContents, FeaturePopoutFocus, RoomFeature } from "../types";
import { featureArt, pickFocusedFeature } from "./worldFeatures";

interface WorldFeaturesPopoutProps {
  roomFeatures: RoomFeature[];
  containerContents: ContainerContents | null;
  preferredType: FeaturePopoutFocus;
  /** Resolved server assets — supplies the global default lever plate/handle art. */
  serverAssets: Record<string, string>;
  onCommand: (command: string) => void;
}

function titleCase(value: string | null | undefined): string {
  if (!value) return "";
  return value
    .split("_")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function featureActions(feature: RoomFeature): Array<{ label: string; command: string; primary?: boolean }> {
  if (feature.type === "door") {
    const actions: Array<{ label: string; command: string; primary?: boolean }> = [];
    if (feature.state === "open") {
      if (feature.direction) {
        actions.push({ label: `Go ${titleCase(feature.direction)}`, command: feature.direction, primary: true });
      }
      actions.push({ label: "Close", command: `close ${feature.keyword}` });
    } else if (feature.state === "closed") {
      actions.push({ label: "Open", command: `open ${feature.keyword}`, primary: true });
      if (feature.locked === false) {
        actions.push({ label: "Lock", command: `lock ${feature.keyword}` });
      }
    } else if (feature.state === "locked") {
      actions.push({ label: "Unlock", command: `unlock ${feature.keyword}`, primary: true });
    }
    return actions;
  }

  if (feature.type === "container") {
    const actions: Array<{ label: string; command: string; primary?: boolean }> = [];
    if (feature.state === "open") {
      actions.push({ label: "Close", command: `close ${feature.keyword}` });
    } else if (feature.state === "closed") {
      actions.push({ label: "Open", command: `open ${feature.keyword}`, primary: true });
      if (feature.locked === false) {
        actions.push({ label: "Lock", command: `lock ${feature.keyword}` });
      }
    } else if (feature.state === "locked") {
      actions.push({ label: "Unlock", command: `unlock ${feature.keyword}`, primary: true });
    }
    return actions;
  }

  return [];
}

/** Stylized open-chest, shown faintly behind a container with no art yet. */
function ChestGlyph() {
  return (
    <svg className="feat-glyph feat-glyph-chest" viewBox="0 0 160 120" aria-hidden="true">
      <path d="M30 52 Q80 6 130 52 L130 60 Q80 22 30 60 Z" fill="currentColor" opacity="0.22" />
      <path d="M30 52 Q80 6 130 52" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" opacity="0.5" />
      <path d="M26 58 H134 V104 a8 8 0 0 1-8 8 H34 a8 8 0 0 1-8-8 Z" fill="currentColor" opacity="0.16" />
      <path d="M26 58 H134 V104 a8 8 0 0 1-8 8 H34 a8 8 0 0 1-8-8 Z" fill="none" stroke="currentColor" strokeWidth="3" opacity="0.45" />
      <line x1="26" y1="78" x2="134" y2="78" stroke="currentColor" strokeWidth="3" opacity="0.4" />
      <rect x="72" y="72" width="16" height="16" rx="3" fill="currentColor" opacity="0.55" />
      <circle cx="80" cy="80" r="2.4" fill="rgb(20 22 34)" opacity="0.7" />
    </svg>
  );
}

/** Stylized placard, shown faintly behind a sign with no art yet. */
function SignGlyph() {
  return (
    <svg className="feat-glyph feat-glyph-sign" viewBox="0 0 160 120" aria-hidden="true">
      <line x1="40" y1="16" x2="120" y2="16" stroke="currentColor" strokeWidth="3" opacity="0.4" />
      <line x1="52" y1="16" x2="46" y2="34" stroke="currentColor" strokeWidth="2.5" opacity="0.4" />
      <line x1="108" y1="16" x2="114" y2="34" stroke="currentColor" strokeWidth="2.5" opacity="0.4" />
      <rect x="30" y="32" width="100" height="60" rx="9" fill="currentColor" opacity="0.16" />
      <rect x="30" y="32" width="100" height="60" rx="9" fill="none" stroke="currentColor" strokeWidth="3" opacity="0.45" />
      <line x1="46" y1="52" x2="114" y2="52" stroke="currentColor" strokeWidth="3" opacity="0.32" />
      <line x1="46" y1="64" x2="114" y2="64" stroke="currentColor" strokeWidth="3" opacity="0.32" />
      <line x1="46" y1="76" x2="94" y2="76" stroke="currentColor" strokeWidth="3" opacity="0.32" />
    </svg>
  );
}

function SignPanel({
  feature,
  serverAssets,
}: {
  feature: RoomFeature;
  serverAssets: Record<string, string>;
}) {
  const hasArt = featureArt(feature, serverAssets) !== null;
  return (
    <div className={`feat-panel feat-sign${hasArt ? " has-art" : ""}`}>
      {!hasArt && <div className="feat-fallback feat-fallback-sign" aria-hidden="true"><SignGlyph /></div>}
      {feature.text && <p className="feat-sign-text">{feature.text}</p>}
    </div>
  );
}

function ContainerPanel({
  feature,
  contents,
  serverAssets,
  onCommand,
}: {
  feature: RoomFeature;
  contents: ContainerContents | null;
  serverAssets: Record<string, string>;
  onCommand: (cmd: string) => void;
}) {
  const hasArt = featureArt(feature, serverAssets) !== null;
  const isOpen = feature.state === "open";
  const items = isOpen && contents?.featureId === feature.id ? contents.items : null;
  const actions = featureActions(feature);

  return (
    <div className={`feat-panel feat-container${hasArt ? " has-art" : ""}`}>
      {!hasArt && <div className="feat-fallback feat-fallback-container" aria-hidden="true"><ChestGlyph /></div>}
      <div className="feat-container-inner">
        {feature.state === "locked" && (
          <p className="feat-container-note">
            {feature.keyRequired ? "Locked — a key is required." : "Locked tight."}
          </p>
        )}
        {isOpen && items !== null && (
          items.length === 0 ? (
            <p className="feat-empty">Nothing inside.</p>
          ) : (
            <ul className="feat-loot" role="list">
              {items.map((item, index) => (
                <li key={`${item.keyword}-${index}`} className="feat-loot-row">
                  <span className="feat-loot-name">{item.name}</span>
                  <button
                    type="button"
                    className="feat-take"
                    aria-label={`Take ${item.name}`}
                    onClick={() => onCommand(`get ${item.keyword} from ${feature.keyword}`)}
                  >
                    Take
                  </button>
                </li>
              ))}
            </ul>
          )
        )}
        {actions.length > 0 && (
          <div className="feat-actions">
            {actions.map((action) => (
              <button
                key={`${feature.id}-${action.command}`}
                type="button"
                className={`feat-btn${action.primary ? " feat-btn-primary" : ""}`}
                onClick={() => onCommand(action.command)}
              >
                {action.label}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function LeverPanel({
  feature,
  serverAssets,
  onCommand,
}: {
  feature: RoomFeature;
  serverAssets: Record<string, string>;
  onCommand: (cmd: string) => void;
}) {
  const isUp = feature.state === "up";
  const label = isUp ? "Ready" : "Pulled";
  const px = feature.leverPivot?.x ?? 0.5;
  const py = feature.leverPivot?.y ?? 0.85;
  const angle = isUp ? (feature.upAngle ?? -28) : (feature.downAngle ?? 28);
  const handleSrc = feature.handleImage ?? serverAssets["lever_handle"] ?? null;
  const plateSrc = feature.plateImage ?? serverAssets["lever_plate"] ?? null;
  const boxSrc = feature.backgroundImage ?? serverAssets["lever_bg"] ?? null;

  return (
    <div className="feat-panel feat-lever">
      <button
        type="button"
        className={`feat-lever-stage${boxSrc ? " has-box" : ""}`}
        onClick={() => onCommand(`pull ${feature.keyword}`)}
        aria-label={`Pull ${feature.name} (${label})`}
        title={`Pull ${feature.name}`}
      >
        {boxSrc && (
          <img className="feat-lever-box" src={boxSrc} alt="" aria-hidden="true" draggable={false} />
        )}
        {handleSrc ? (
          <span className="feat-lever-lever">
            {plateSrc && (
              <img className="feat-lever-plate" src={plateSrc} alt="" aria-hidden="true" draggable={false} />
            )}
            <img
              className="feat-lever-handle"
              src={handleSrc}
              alt=""
              aria-hidden="true"
              draggable={false}
              style={{
                transformOrigin: `${px * 100}% ${py * 100}%`,
                transform: `translate(${(0.5 - px) * 100}%, ${(0.5 - py) * 100}%) rotate(${angle}deg)`,
              }}
            />
          </span>
        ) : (
          <span className="feat-lever-lever">
            <svg className="feat-lever-svg" viewBox="0 0 64 96" aria-hidden="true">
              <path d="M12 78 Q32 86 52 78" fill="none" stroke="rgb(180 150 210 / 40%)" strokeWidth="2" strokeLinecap="round" />
              <circle cx="32" cy="72" r="6" className="feat-lever-pivot" />
              <line x1="32" y1="72" x2={isUp ? "32" : "46"} y2={isUp ? "22" : "32"} className="feat-lever-shaft" strokeWidth="4" strokeLinecap="round" />
              <circle cx={isUp ? "32" : "46"} cy={isUp ? "18" : "28"} r="7" className="feat-lever-grip" />
              <circle cx={isUp ? "32" : "46"} cy={isUp ? "18" : "28"} r="4" className="feat-lever-glow" />
            </svg>
          </span>
        )}
      </button>
    </div>
  );
}

function DoorPanel({
  feature,
  serverAssets,
  onCommand,
}: {
  feature: RoomFeature;
  serverAssets: Record<string, string>;
  onCommand: (cmd: string) => void;
}) {
  const isOpen = feature.state === "open";
  const isLocked = feature.state === "locked";
  const dir = feature.direction as "north" | "east" | "south" | "west" | "up" | "down" | null;
  const frameSrc = feature.frameImage ?? serverAssets["door_frame"] ?? null;
  const leafSrc = feature.leafImage ?? serverAssets["door_leaf"] ?? null;
  const lockSrc = serverAssets["door_lock"] ?? null;
  const portalSrc = serverAssets["door_portal"] ?? null;
  const hinge = feature.hinge === "left" ? "left" : "right";
  const openAngle = feature.openAngle ?? 60;
  // Door opens toward the viewer; swing direction comes from the hinge, not the
  // angle's sign (left hinge → right edge swings out = negative rotateY).
  const leafAngle = isOpen ? (hinge === "right" ? openAngle : -openAngle) : 0;
  // Fit the leaf inside the frame's opening: leafScale (fraction of the box) +
  // leafOffsetY (vertical nudge, + = down). Pivot on the hinge edge. Defaults
  // match the bundled default door art.
  const leafScale = feature.leafScale ?? 0.76;
  const leafOffsetY = feature.leafOffsetY ?? 0.09;
  const leafInset = (1 - leafScale) / 2;
  const leafStyle = {
    left: `${leafInset * 100}%`,
    top: `${(leafInset + leafOffsetY) * 100}%`,
    width: `${leafScale * 100}%`,
    height: `${leafScale * 100}%`,
    transformOrigin: hinge === "right" ? "right center" : "left center",
    transform: `rotateY(${leafAngle}deg)`,
  };
  // The portal sits behind the leaf, revealed when it swings open. Keep it a bit
  // smaller than the leaf box and centred on it, so the (arch-shaped, transparent-
  // edged) leaf fully covers it when closed instead of bleeding past the edges.
  // PORTAL_SHRINK is the single tuning dial.
  const PORTAL_SHRINK = 0.62;
  const portalW = leafScale * PORTAL_SHRINK;
  const leafCx = leafInset + leafScale / 2;
  const leafCy = leafInset + leafOffsetY + leafScale / 2;
  const portalStyle = {
    left: `${(leafCx - portalW / 2) * 100}%`,
    top: `${(leafCy - portalW / 2) * 100}%`,
    width: `${portalW * 100}%`,
    height: `${portalW * 100}%`,
  };
  const actions = featureActions(feature);

  // Track the door's state so the warded seal can shatter the instant it unlocks.
  const [seenState, setSeenState] = useState<string | null>(feature.state);
  const [shatter, setShatter] = useState(false);
  if (feature.state !== seenState) {
    const wasLocked = seenState === "locked";
    setSeenState(feature.state);
    if (wasLocked && feature.state !== "locked") setShatter(true);
  }
  useEffect(() => {
    if (!shatter) return;
    const timer = setTimeout(() => setShatter(false), 850);
    return () => clearTimeout(timer);
  }, [shatter]);

  const showLock = isLocked || shatter;
  const showKey = Boolean(feature.keyRequired && feature.keyImage && (isLocked || shatter));

  return (
    <div className="feat-panel feat-door">
      <div className={`feat-door-stage hinge-${hinge}${isOpen ? " is-open" : ""}`}>
        <div className="feat-door-portal" style={portalStyle} aria-hidden="true">
          {portalSrc ? (
            <img className="feat-door-portal-art" src={portalSrc} alt="" draggable={false} />
          ) : (
            <span className="feat-door-portal-css" />
          )}
        </div>
        {frameSrc ? (
          <img className="feat-door-frame" src={frameSrc} alt="" aria-hidden="true" draggable={false} />
        ) : (
          <div className="feat-door-frame feat-door-frame-css" aria-hidden="true" />
        )}
        {leafSrc ? (
          <img className="feat-door-leaf" src={leafSrc} alt="" aria-hidden="true" draggable={false} style={leafStyle} />
        ) : (
          <div className="feat-door-leaf feat-door-leaf-css" style={leafStyle} aria-hidden="true" />
        )}
        {feature.keyRequired && feature.state === "closed" && (
          <span className="feat-door-keyhole" aria-hidden="true" />
        )}
        {showLock && (
          <div className={`feat-door-seal${isLocked ? " is-locked" : ""}${shatter ? " is-shatter" : ""}`} aria-hidden="true">
            {lockSrc ? (
              <img className="feat-door-seal-img" src={lockSrc} alt="" draggable={false} />
            ) : (
              <span className="feat-door-seal-css" />
            )}
          </div>
        )}
      </div>

      <div className="feat-door-info">
        <p className="feat-door-line">
          {dir ? <>Leads <strong>{titleCase(dir)}</strong></> : "A doorway."}
          {isLocked && (feature.keyRequired ? " · sealed" : " · barred")}
          {isOpen && " · open"}
        </p>
        {showKey && (
          <div className={`feat-door-key${shatter ? " is-using" : ""}`}>
            <img className="feat-door-key-img" src={feature.keyImage ?? ""} alt={feature.keyName ?? "key"} draggable={false} />
            <span className="feat-door-key-name">{feature.keyName ?? "a key"}</span>
          </div>
        )}
        {actions.length > 0 && (
          <div className="feat-actions">
            {actions.map((action) => (
              <button
                key={`${feature.id}-${action.command}`}
                type="button"
                className={`feat-btn${action.primary ? " feat-btn-primary" : ""}`}
                onClick={() => onCommand(action.command)}
              >
                {action.label}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

export function WorldFeaturesPopout({
  roomFeatures,
  containerContents,
  preferredType,
  serverAssets,
  onCommand,
}: WorldFeaturesPopoutProps) {
  const feature = pickFocusedFeature(roomFeatures, preferredType);

  // Auto-search the focused container once it's open so its contents render
  // without an extra click. Track the id we've requested for the current "open"
  // session so a close/reopen triggers a fresh search.
  const autoSearchedRef = useRef<string | null>(null);
  useEffect(() => {
    if (!feature || feature.type !== "container") {
      autoSearchedRef.current = null;
      return;
    }
    if (feature.state !== "open") {
      autoSearchedRef.current = null;
      return;
    }
    if (autoSearchedRef.current !== feature.id && containerContents?.featureId !== feature.id) {
      autoSearchedRef.current = feature.id;
      onCommand(`search ${feature.keyword}`);
    }
  }, [feature, containerContents, onCommand]);

  if (!feature) {
    return (
      <div className="feat-panel feat-empty-panel">
        <p>This room exposes no interactive features to the web client yet.</p>
      </div>
    );
  }

  switch (feature.type) {
    case "sign":
      return <SignPanel feature={feature} serverAssets={serverAssets} />;
    case "lever":
      return <LeverPanel feature={feature} serverAssets={serverAssets} onCommand={onCommand} />;
    case "container":
      return (
        <ContainerPanel
          feature={feature}
          contents={containerContents?.featureId === feature.id ? containerContents : null}
          serverAssets={serverAssets}
          onCommand={onCommand}
        />
      );
    default:
      return <DoorPanel feature={feature} serverAssets={serverAssets} onCommand={onCommand} />;
  }
}
