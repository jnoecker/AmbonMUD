import { TABS } from "../constants";
import type { MobileTab } from "../types";

interface MobileTabBarProps {
  activeTab: MobileTab;
  onTabChange: (tab: MobileTab) => void;
}

export function MobileTabBar({ activeTab, onTabChange }: MobileTabBarProps) {
  return (
    <nav className="mobile-tab-bar" aria-label="Mobile sections">
      {TABS.map((tab) => (
        <button
          key={tab.id}
          type="button"
          className={`mobile-tab ${activeTab === tab.id ? "mobile-tab-active" : ""}`}
          onClick={() => onTabChange(tab.id)}
          aria-pressed={activeTab === tab.id}
        >
          {tab.label}
        </button>
      ))}
    </nav>
  );
}

