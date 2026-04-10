import { useMemo, useState } from "react";
import type { ContainerContents, FeaturePopoutFocus, RoomFeature, RoomFeatureType } from "../types";
import { DirectionIcon } from "./Icons";

interface WorldFeaturesPopoutProps {
  roomTitle: string;
  roomFeatures: RoomFeature[];
  containerContents: ContainerContents | null;
  preferredType: FeaturePopoutFocus;
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
      actions.push({ label: "Search", command: `search ${feature.keyword}` });
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

      <div className="feature-popout-grid">
        {visibleFeatures.map((feature) => {
          const contents = feature.type === "container" && containerContents?.featureId === feature.id
            ? containerContents
            : null;
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

              {feature.type === "sign" && feature.text && (
                <p className="feature-card-sign-text">{feature.text}</p>
              )}

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

              {feature.type === "container" && contents && (
                <div className="feature-card-contents">
                  <div className="feature-card-contents-header">
                    <span>Inside {contents.name}</span>
                    <span>{contents.items.length} item{contents.items.length === 1 ? "" : "s"}</span>
                  </div>
                  {contents.items.length === 0 ? (
                    <p className="feature-card-empty">Nothing is inside right now.</p>
                  ) : (
                    <ul className="feature-card-item-list" role="list">
                      {contents.items.map((item, index) => (
                        <li key={`${item.keyword}-${index}`} className="feature-card-item">
                          <span>{item.name}</span>
                          <button
                            type="button"
                            className="feature-card-inline-action"
                            onClick={() => onCommand(`get ${item.keyword} from ${contents.keyword}`)}
                          >
                            Take
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              )}
            </article>
          );
        })}
      </div>
    </div>
  );
}
