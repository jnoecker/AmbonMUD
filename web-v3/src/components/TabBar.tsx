import { useState } from "react";

interface TabDef {
  id: string;
  label: string;
  icon: React.ReactNode;
}

const PINNED_TABS: TabDef[] = [
  {
    id: "character",
    label: "Character",
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
        <circle cx="12" cy="7" r="4" />
      </svg>
    ),
  },
  {
    id: "inventory",
    label: "Inventory",
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M6 2 3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4z" />
        <line x1="3" y1="6" x2="21" y2="6" />
        <path d="M16 10a4 4 0 0 1-8 0" />
      </svg>
    ),
  },
  {
    id: "combat",
    label: "Combat",
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="m14.5 2-7 7L4 5.5 2 12l6.5 1.5L5 17l2.5 2.5 3.5-3.5L12 22l4.5-2-3.5-3.5 7-7L14.5 2z" />
      </svg>
    ),
  },
  {
    id: "quests",
    label: "Quest",
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z" />
        <polyline points="14 2 14 8 20 8" />
        <line x1="16" y1="13" x2="8" y2="13" />
        <line x1="16" y1="17" x2="8" y2="17" />
        <polyline points="10 9 9 9 8 9" />
      </svg>
    ),
  },
  {
    id: "chat",
    label: "Chat",
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
      </svg>
    ),
  },
];

const OVERFLOW_TABS: TabDef[] = [
  {
    id: "equipment",
    label: "Equipment",
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
      </svg>
    ),
  },
  {
    id: "spellbook",
    label: "Spellbook",
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
        <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
        <path d="m9 10 2 2 4-4" />
      </svg>
    ),
  },
  {
    id: "crafting",
    label: "Crafting",
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />
      </svg>
    ),
  },
  {
    id: "bank",
    label: "Bank",
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <rect x="1" y="4" width="22" height="16" rx="2" ry="2" />
        <line x1="1" y1="10" x2="23" y2="10" />
      </svg>
    ),
  },
  {
    id: "auction",
    label: "Auction",
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="10" />
        <line x1="12" y1="8" x2="12" y2="12" />
        <line x1="12" y1="16" x2="12.01" y2="16" />
      </svg>
    ),
  },
  {
    id: "mail",
    label: "Mail",
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z" />
        <polyline points="22,6 12,13 2,6" />
      </svg>
    ),
  },
  {
    id: "housing",
    label: "Housing",
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
        <polyline points="9 22 9 12 15 12 15 22" />
      </svg>
    ),
  },
  {
    id: "leaderboard",
    label: "Leaderboard",
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <line x1="18" y1="20" x2="18" y2="10" />
        <line x1="12" y1="20" x2="12" y2="4" />
        <line x1="6" y1="20" x2="6" y2="14" />
      </svg>
    ),
  },
];

export interface TabBarProps {
  activePanel: string;
  onPanelChange: (panel: string) => void;
}

export function TabBar({ activePanel, onPanelChange }: TabBarProps) {
  const [overflowOpen, setOverflowOpen] = useState(false);

  const handleTabClick = (tabId: string) => {
    onPanelChange(tabId);
    setOverflowOpen(false);
  };

  return (
    <nav className="tab-bar" role="tablist" aria-label="Game panels">
      {PINNED_TABS.map((tab) => (
        <button
          key={tab.id}
          type="button"
          role="tab"
          className={`tab-bar__tab ${activePanel === tab.id ? "tab-bar__tab--active" : ""}`}
          aria-selected={activePanel === tab.id}
          aria-label={tab.label}
          onClick={() => handleTabClick(tab.id)}
        >
          {tab.icon}
          <span>{tab.label}</span>
        </button>
      ))}

      <div className="tab-bar__overflow-container">
        <button
          type="button"
          className={`tab-bar__tab ${overflowOpen ? "tab-bar__tab--active" : ""}`}
          aria-label="More panels"
          aria-expanded={overflowOpen}
          aria-haspopup="true"
          onClick={() => setOverflowOpen(!overflowOpen)}
        >
          <svg viewBox="0 0 24 24" fill="currentColor">
            <circle cx="5" cy="12" r="2" />
            <circle cx="12" cy="12" r="2" />
            <circle cx="19" cy="12" r="2" />
          </svg>
          <span>More</span>
        </button>

        {overflowOpen && (
          <>
            {/* Invisible backdrop to close overflow on outside click */}
            <div
              className="tab-bar__overflow-backdrop"
              onClick={() => setOverflowOpen(false)}
              aria-hidden="true"
            />
            <div className="tab-bar__overflow" role="menu">
              {OVERFLOW_TABS.map((tab) => (
                <button
                  key={tab.id}
                  type="button"
                  role="menuitem"
                  className={`tab-bar__overflow-item ${activePanel === tab.id ? "tab-bar__tab--active" : ""}`}
                  onClick={() => handleTabClick(tab.id)}
                >
                  {tab.icon}
                  <span>{tab.label}</span>
                </button>
              ))}
            </div>
          </>
        )}
      </div>
    </nav>
  );
}
