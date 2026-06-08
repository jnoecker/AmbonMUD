import { useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import type { ContainerContents, FeaturePopoutFocus, RoomFeature, RoomFeatureType } from "../types";
import { DirectionIcon } from "./Icons";

interface WorldFeaturesPopoutProps {
  roomTitle: string;
  roomFeatures: RoomFeature[];
  containerContents: ContainerContents | null;
  preferredType: FeaturePopoutFocus;
  /** Resolved server assets — supplies the global default lever plate/handle art. */
  serverAssets: Record<string, string>;
  onCommand: (command: string) => void;
}

const FEATURE_ORDER: RoomFeatureType[] = ["door", "container", "lever", "sign"];

const FEATURE_LABELS: Record<RoomFeatureType, { singular: string; plural: string }> = {
  door: { singular: "Door", plural: "Doors" },
  container: { singular: "Container", plural: "Containers" },
  lever: { singular: "Lever", plural: "Levers" },
  sign: { singular: "Sign", plural: "Signs" },
};

function titleCase(value: string | null | undefined): string {
  if (!value) return "";
  return value
    .split("_")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function stateLabel(feature: RoomFeature): string | null {
  if (!feature.state) return null;
  if (feature.type === "lever") {
    return feature.state === "up" ? "Ready" : "Pulled";
  }
  return titleCase(feature.state);
}

function LeverWidget({
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

  // Custom art (authored in Arcanum): a static plate plus a handle sprite that
  // rotates around its pivot between the up/down angles. Per-lever art wins;
  // otherwise the server-wide default plate/handle; otherwise the hand-drawn
  // vector lever.
  const px = feature.leverPivot?.x ?? 0.5;
  const py = feature.leverPivot?.y ?? 0.85;
  const angle = isUp ? (feature.upAngle ?? -28) : (feature.downAngle ?? 28);
  const handleSrc = feature.handleImage ?? serverAssets["lever_handle"] ?? null;
  const plateSrc = feature.plateImage ?? serverAssets["lever_plate"] ?? null;
  // Optional backdrop "box" the lever sits inside; the plate + handle ride on
  // top. Per-feature art wins, then the server-wide default, then a CSS frame.
  const boxSrc = feature.backgroundImage ?? serverAssets["lever_bg"] ?? null;

  return (
    <div
      className={`lever-widget${boxSrc ? " lever-widget-boxed" : ""}`}
      onClick={() => onCommand(`pull ${feature.keyword}`)}
    >
      {boxSrc && (
        <img className="lever-widget-box" src={boxSrc} alt="" aria-hidden="true" draggable={false} />
      )}
      {handleSrc ? (
        <div className="lever-widget-art" aria-label={`${feature.name}: ${label}`} role="img">
          {plateSrc && (
            <img className="lever-widget-art-plate" src={plateSrc} alt="" aria-hidden="true" draggable={false} />
          )}
          <img
            className="lever-widget-art-handle"
            src={handleSrc}
            alt=""
            aria-hidden="true"
            draggable={false}
            style={{
              transformOrigin: `${px * 100}% ${py * 100}%`,
              transform: `translate(${(0.5 - px) * 100}%, ${(0.5 - py) * 100}%) rotate(${angle}deg)`,
            }}
          />
        </div>
      ) : (
        <div className="lever-widget-plate">
          <svg
            className="lever-widget-svg"
            viewBox="0 0 64 96"
            aria-label={`${feature.name}: ${label}`}
          >
            {/* Base plate arc */}
            <path
              d="M12 78 Q32 86 52 78"
              fill="none"
              stroke="rgb(180 150 210 / 40%)"
              strokeWidth="2"
              strokeLinecap="round"
            />
            {/* Pivot point */}
            <circle cx="32" cy="72" r="6" className="lever-widget-pivot" />
            {/* Handle shaft */}
            <line
              x1="32"
              y1="72"
              x2={isUp ? "32" : "46"}
              y2={isUp ? "22" : "32"}
              className="lever-widget-shaft"
              strokeWidth="4"
              strokeLinecap="round"
            />
            {/* Handle grip */}
            <circle
              cx={isUp ? "32" : "46"}
              cy={isUp ? "18" : "28"}
              r="7"
              className="lever-widget-grip"
            />
            {/* Glow on grip */}
            <circle
              cx={isUp ? "32" : "46"}
              cy={isUp ? "18" : "28"}
              r="4"
              className="lever-widget-glow"
            />
          </svg>
        </div>
      )}
      <div className="lever-widget-info">
        <span className="lever-widget-name">{feature.name}</span>
        <span className={`lever-widget-state lever-widget-state-${isUp ? "up" : "down"}`}>
          {label}
        </span>
      </div>
      <button
        type="button"
        className="lever-widget-pull"
        onClick={(e) => {
          e.stopPropagation();
          onCommand(`pull ${feature.keyword}`);
        }}
      >
        Pull
      </button>
    </div>
  );
}

/** Stylized open-chest used as the container backdrop when no art is shipped. */
function ChestGlyph() {
  return (
    <svg className="fcard-glyph fcard-glyph-chest" viewBox="0 0 160 120" aria-hidden="true">
      {/* open lid, tilted back */}
      <path
        d="M30 52 Q80 6 130 52 L130 60 Q80 22 30 60 Z"
        fill="currentColor"
        opacity="0.22"
      />
      <path
        d="M30 52 Q80 6 130 52"
        fill="none"
        stroke="currentColor"
        strokeWidth="3"
        strokeLinecap="round"
        opacity="0.5"
      />
      {/* chest body */}
      <path d="M26 58 H134 V104 a8 8 0 0 1-8 8 H34 a8 8 0 0 1-8-8 Z" fill="currentColor" opacity="0.16" />
      <path
        d="M26 58 H134 V104 a8 8 0 0 1-8 8 H34 a8 8 0 0 1-8-8 Z"
        fill="none"
        stroke="currentColor"
        strokeWidth="3"
        opacity="0.45"
      />
      {/* horizontal band + lock */}
      <line x1="26" y1="78" x2="134" y2="78" stroke="currentColor" strokeWidth="3" opacity="0.4" />
      <rect x="72" y="72" width="16" height="16" rx="3" fill="currentColor" opacity="0.55" />
      <circle cx="80" cy="80" r="2.4" fill="rgb(20 22 34)" opacity="0.7" />
    </svg>
  );
}

/** Stylized hanging placard used as the sign backdrop when no art is shipped. */
function SignGlyph() {
  return (
    <svg className="fcard-glyph fcard-glyph-sign" viewBox="0 0 160 120" aria-hidden="true">
      {/* top rail + ropes */}
      <line x1="40" y1="16" x2="120" y2="16" stroke="currentColor" strokeWidth="3" opacity="0.4" />
      <line x1="52" y1="16" x2="46" y2="34" stroke="currentColor" strokeWidth="2.5" opacity="0.4" />
      <line x1="108" y1="16" x2="114" y2="34" stroke="currentColor" strokeWidth="2.5" opacity="0.4" />
      {/* board */}
      <rect x="30" y="32" width="100" height="60" rx="9" fill="currentColor" opacity="0.16" />
      <rect
        x="30"
        y="32"
        width="100"
        height="60"
        rx="9"
        fill="none"
        stroke="currentColor"
        strokeWidth="3"
        opacity="0.45"
      />
      {/* inked lines */}
      <line x1="46" y1="52" x2="114" y2="52" stroke="currentColor" strokeWidth="3" opacity="0.32" />
      <line x1="46" y1="64" x2="114" y2="64" stroke="currentColor" strokeWidth="3" opacity="0.32" />
      <line x1="46" y1="76" x2="94" y2="76" stroke="currentColor" strokeWidth="3" opacity="0.32" />
    </svg>
  );
}

function ContainerCard({
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
  const bgSrc = feature.backgroundImage ?? serverAssets["container_bg"] ?? null;
  const isOpen = feature.state === "open";
  const stateClass = isOpen ? "is-open" : feature.state === "locked" ? "is-locked" : "is-closed";
  const actions = featureActions(feature);
  const items = isOpen && contents?.featureId === feature.id ? contents.items : null;

  return (
    <article
      className={`fcard fcard-container ${stateClass}${bgSrc ? " has-art" : ""}`}
      style={bgSrc ? { ["--fcard-art" as string]: `url("${bgSrc}")` } : undefined}
    >
      <div className="fcard-bg" aria-hidden="true">{!bgSrc && <ChestGlyph />}</div>
      <div className="fcard-body">
        <header className="fcard-head">
          <span className="fcard-eyebrow fcard-eyebrow-container">Container</span>
          <h4 className="fcard-title">{feature.name}</h4>
          <p className="fcard-sub">
            {feature.state === "locked"
              ? feature.keyRequired
                ? "Locked — a key is required."
                : "Locked tight."
              : isOpen
                ? "Open"
                : "Closed"}
          </p>
        </header>

        <div className="fcard-foot">
          {isOpen && items !== null && (
            items.length === 0 ? (
              <p className="fcard-empty">Nothing inside.</p>
            ) : (
              <ul className="fcard-loot" role="list">
                {items.map((item, index) => (
                  <li key={`${item.keyword}-${index}`} className="fcard-loot-row">
                    <span className="fcard-loot-name">{item.name}</span>
                    <button
                      type="button"
                      className="fcard-take"
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
            <div className="fcard-actions">
              {actions.map((action) => (
                <button
                  key={`${feature.id}-${action.command}`}
                  type="button"
                  className={`fcard-btn${action.label === "Open" || action.label === "Unlock" ? " fcard-btn-primary" : ""}`}
                  onClick={() => onCommand(action.command)}
                >
                  {action.label}
                </button>
              ))}
            </div>
          )}
        </div>
      </div>
    </article>
  );
}

function SignCard({
  feature,
  serverAssets,
  onCommand,
}: {
  feature: RoomFeature;
  serverAssets: Record<string, string>;
  onCommand: (cmd: string) => void;
}) {
  const bgSrc = feature.backgroundImage ?? serverAssets["sign_bg"] ?? null;

  return (
    <article
      className={`fcard fcard-sign${bgSrc ? " has-art" : ""}`}
      style={bgSrc ? { ["--fcard-art" as string]: `url("${bgSrc}")` } : undefined}
    >
      <div className="fcard-bg" aria-hidden="true">{!bgSrc && <SignGlyph />}</div>
      <div className="fcard-body fcard-sign-body">
        <span className="fcard-eyebrow fcard-eyebrow-sign">{feature.name}</span>
        {feature.text && <p className="fcard-sign-text">{feature.text}</p>}
        <button
          type="button"
          className="fcard-sign-read"
          onClick={() => onCommand(`read ${feature.keyword}`)}
        >
          Read aloud
        </button>
      </div>
    </article>
  );
}

function LockedFeatureHero({
  feature,
  kindLabel,
  kindClass,
  heroVariantClass,
  subtitle,
  extraContent,
  onCommand,
}: {
  feature: RoomFeature;
  kindLabel: string;
  kindClass: string;
  heroVariantClass: string;
  subtitle: string;
  extraContent?: ReactNode;
  onCommand: (cmd: string) => void;
}) {
  return (
    <article
      className={`feature-card feature-card-${feature.type} feature-card-hero-locked ${heroVariantClass}`}
    >
      <div className="feature-card-hero-lock" aria-hidden="true">
        <svg viewBox="0 0 64 80" className="feature-card-hero-lock-svg">
          <path
            d="M20 34 V24 a12 12 0 0 1 24 0 V34"
            fill="none"
            stroke="currentColor"
            strokeWidth="4"
            strokeLinecap="round"
          />
          <rect
            x="12"
            y="34"
            width="40"
            height="34"
            rx="5"
            fill="currentColor"
            opacity="0.18"
            stroke="currentColor"
            strokeWidth="2.5"
          />
          <circle cx="32" cy="48" r="4" fill="currentColor" />
          <path
            d="M32 52 V58"
            stroke="currentColor"
            strokeWidth="3"
            strokeLinecap="round"
          />
        </svg>
      </div>
      <div className="feature-card-hero-copy">
        <span className={`feature-card-kind ${kindClass}`}>{kindLabel}</span>
        <h4>{feature.name}</h4>
        <p className="feature-card-hero-subtitle">{subtitle}</p>
        {extraContent}
      </div>
      <div className="feature-card-hero-actions">
        <button
          type="button"
          className="feature-card-action feature-card-action-primary"
          onClick={() => onCommand(`unlock ${feature.keyword}`)}
        >
          Unlock
        </button>
      </div>
    </article>
  );
}

function LockedContainerHero({
  feature,
  onCommand,
}: {
  feature: RoomFeature;
  onCommand: (cmd: string) => void;
}) {
  return (
    <LockedFeatureHero
      feature={feature}
      kindLabel="Container"
      kindClass="feature-card-kind-container"
      heroVariantClass="feature-card-hero-locked-container"
      subtitle={feature.keyRequired ? "A key is required to unlock this." : "Locked tight."}
      onCommand={onCommand}
    />
  );
}

function LockedDoorHero({
  feature,
  onCommand,
}: {
  feature: RoomFeature;
  onCommand: (cmd: string) => void;
}) {
  const direction = feature.direction ? titleCase(feature.direction) : null;
  return (
    <LockedFeatureHero
      feature={feature}
      kindLabel="Door"
      kindClass="feature-card-kind-door"
      heroVariantClass="feature-card-hero-locked-door"
      subtitle={
        feature.keyRequired
          ? "A key is required to pass through."
          : "The way is barred."
      }
      extraContent={
        direction ? (
          <span className="feature-card-hero-direction">
            <DirectionIcon
              direction={feature.direction as "north" | "east" | "south" | "west" | "up" | "down"}
              className="feature-card-direction-icon"
            />
            Leads {direction}
          </span>
        ) : null
      }
      onCommand={onCommand}
    />
  );
}

function featureSummary(features: RoomFeature[], type: RoomFeatureType): string {
  const count = features.filter((feature) => feature.type === type).length;
  if (count === 0) return `No ${FEATURE_LABELS[type].plural.toLowerCase()}`;
  if (count === 1) return `1 ${FEATURE_LABELS[type].singular.toLowerCase()}`;
  return `${count} ${FEATURE_LABELS[type].plural.toLowerCase()}`;
}

function featureActions(feature: RoomFeature): Array<{ label: string; command: string }> {
  if (feature.type === "door") {
    const actions: Array<{ label: string; command: string }> = [];
    if (feature.state === "open") {
      if (feature.direction) {
        actions.push({ label: `Go ${feature.direction}`, command: feature.direction });
      }
      actions.push({ label: "Close", command: `close ${feature.keyword}` });
    } else if (feature.state === "closed") {
      actions.push({ label: "Open", command: `open ${feature.keyword}` });
      if (feature.locked === false) {
        actions.push({ label: "Lock", command: `lock ${feature.keyword}` });
      }
    } else if (feature.state === "locked") {
      actions.push({ label: "Unlock", command: `unlock ${feature.keyword}` });
    }
    return actions;
  }

  if (feature.type === "container") {
    const actions: Array<{ label: string; command: string }> = [];
    if (feature.state === "open") {
      actions.push({ label: "Close", command: `close ${feature.keyword}` });
    } else if (feature.state === "closed") {
      actions.push({ label: "Open", command: `open ${feature.keyword}` });
      if (feature.locked === false) {
        actions.push({ label: "Lock", command: `lock ${feature.keyword}` });
      }
    } else if (feature.state === "locked") {
      actions.push({ label: "Unlock", command: `unlock ${feature.keyword}` });
    }
    return actions;
  }

  if (feature.type === "lever") {
    return [{ label: "Pull", command: `pull ${feature.keyword}` }];
  }

  if (feature.type === "sign") {
    return [{ label: "Read", command: `read ${feature.keyword}` }];
  }

  return [];
}

export function WorldFeaturesPopout({
  roomTitle,
  roomFeatures,
  containerContents,
  preferredType,
  serverAssets,
  onCommand,
}: WorldFeaturesPopoutProps) {
  const groupedFeatures = useMemo(
    () => ({
      door: roomFeatures.filter((feature) => feature.type === "door"),
      container: roomFeatures.filter((feature) => feature.type === "container"),
      lever: roomFeatures.filter((feature) => feature.type === "lever"),
      sign: roomFeatures.filter((feature) => feature.type === "sign"),
    }),
    [roomFeatures],
  );

  const availableTabs = useMemo(
    () => FEATURE_ORDER.filter((type) => groupedFeatures[type].length > 0),
    [groupedFeatures],
  );

  const [manualTab, setManualTab] = useState<RoomFeatureType | null>(null);

  // Auto-search containers once they become open so their contents render
  // without an extra click. A ref tracks which feature ids we've already
  // requested, scoped to the current "open" session — if a container closes
  // or disappears, its id is dropped so reopening triggers a fresh search.
  const autoSearchedRef = useRef<Set<string>>(new Set());
  useEffect(() => {
    const openIds = new Set(
      roomFeatures
        .filter((f) => f.type === "container" && f.state === "open")
        .map((f) => f.id),
    );
    for (const id of Array.from(autoSearchedRef.current)) {
      if (!openIds.has(id)) autoSearchedRef.current.delete(id);
    }
    for (const feature of roomFeatures) {
      if (
        feature.type === "container" &&
        feature.state === "open" &&
        !autoSearchedRef.current.has(feature.id) &&
        containerContents?.featureId !== feature.id
      ) {
        autoSearchedRef.current.add(feature.id);
        onCommand(`search ${feature.keyword}`);
      }
    }
  }, [roomFeatures, containerContents, onCommand]);

  if (roomFeatures.length === 0) {
    return (
      <div className="feature-popout feature-popout-empty">
        <div className="feature-popout-hero feature-popout-hero-empty">
          <p className="feature-popout-eyebrow">Room Features</p>
          <h3>{roomTitle}</h3>
          <p>
            This room does not currently expose any doors, containers, levers, or signs to the web client.
            When Arcanum authors them into the world, they will appear here automatically.
          </p>
        </div>
      </div>
    );
  }

  const activeTab =
    (preferredType && groupedFeatures[preferredType].length > 0 ? preferredType : null) ??
    (manualTab && availableTabs.includes(manualTab) ? manualTab : null) ??
    availableTabs[0] ??
    "door";

  const visibleFeatures = groupedFeatures[activeTab];

  return (
    <div className="feature-popout">
      <header className="feature-popout-hero">
        <div className="feature-popout-hero-copy">
          <h3>{roomTitle}</h3>
        </div>
        <div className="feature-popout-summary" aria-label="Feature summary">
          <span className="feature-popout-summary-pill feature-popout-summary-door">
            {featureSummary(roomFeatures, "door")}
          </span>
          <span className="feature-popout-summary-pill feature-popout-summary-container">
            {featureSummary(roomFeatures, "container")}
          </span>
          <span className="feature-popout-summary-pill feature-popout-summary-lever">
            {featureSummary(roomFeatures, "lever")}
          </span>
        </div>
      </header>

      <div className="feature-popout-tabs" role="tablist" aria-label="Feature categories">
        {availableTabs.map((type) => (
          <button
            key={type}
            type="button"
            role="tab"
            className={`feature-popout-tab feature-popout-tab-${type}${activeTab === type ? " feature-popout-tab-active" : ""}`}
            aria-selected={activeTab === type}
            onClick={() => setManualTab(type)}
          >
            {FEATURE_LABELS[type].plural}
            <span className="feature-popout-tab-count">{groupedFeatures[type].length}</span>
          </button>
        ))}
      </div>

      <div className={`feature-popout-grid${activeTab === "lever" ? " feature-popout-grid-levers" : ""}`}>
        {visibleFeatures.map((feature) => {
          if (feature.type === "lever") {
            return <LeverWidget key={feature.id} feature={feature} serverAssets={serverAssets} onCommand={onCommand} />;
          }

          if (feature.type === "container") {
            if (feature.state === "locked" && visibleFeatures.length === 1) {
              return <LockedContainerHero key={feature.id} feature={feature} onCommand={onCommand} />;
            }
            const contents = containerContents?.featureId === feature.id ? containerContents : null;
            return (
              <ContainerCard
                key={feature.id}
                feature={feature}
                contents={contents}
                serverAssets={serverAssets}
                onCommand={onCommand}
              />
            );
          }

          if (feature.type === "sign") {
            return <SignCard key={feature.id} feature={feature} serverAssets={serverAssets} onCommand={onCommand} />;
          }

          // Doors — generic card.
          if (feature.state === "locked" && visibleFeatures.length === 1) {
            return <LockedDoorHero key={feature.id} feature={feature} onCommand={onCommand} />;
          }

          const actions = featureActions(feature);
          const label = stateLabel(feature);

          return (
            <article key={feature.id} className={`feature-card feature-card-${feature.type}`}>
              <header className="feature-card-header">
                <div className="feature-card-heading">
                  <span className={`feature-card-kind feature-card-kind-${feature.type}`}>
                    {FEATURE_LABELS[feature.type].singular}
                  </span>
                  <h4>{feature.name}</h4>
                </div>
                <div className="feature-card-meta">
                  {label && <span className="feature-card-state">{label}</span>}
                  {feature.direction && (
                    <span className="feature-card-direction">
                      <DirectionIcon direction={feature.direction as "north" | "east" | "south" | "west" | "up" | "down"} className="feature-card-direction-icon" />
                      {titleCase(feature.direction)}
                    </span>
                  )}
                </div>
              </header>

              <div className="feature-card-details">
                {feature.locked === true && (
                  <span className="feature-card-detail-chip">Locked</span>
                )}
                {feature.keyRequired && (
                  <span className="feature-card-detail-chip">Key required</span>
                )}
                <span className="feature-card-detail-chip">Keyword: {feature.keyword}</span>
              </div>

              {actions.length > 0 && (
                <div className="feature-card-actions">
                  {actions.map((action) => (
                    <button
                      key={`${feature.id}-${action.command}`}
                      type="button"
                      className="feature-card-action"
                      onClick={() => onCommand(action.command)}
                    >
                      {action.label}
                    </button>
                  ))}
                </div>
              )}
            </article>
          );
        })}
      </div>
    </div>
  );
}
