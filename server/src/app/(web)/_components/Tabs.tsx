'use client';

import { useState, type ReactNode } from 'react';

export type Tab = { id: string; label: string; count?: number; content: ReactNode };

/** Panels behind tabs (#80); every panel is rendered, the others hidden. */
export function Tabs({ tabs, label }: { tabs: Tab[]; label: string }) {
  const [current, setCurrent] = useState(tabs[0]?.id);
  return (
    <div className="tabs">
      <div className="tab-list" role="tablist" aria-label={label}>
        {tabs.map((tab) => (
          <button
            key={tab.id}
            type="button"
            role="tab"
            id={`tab-${tab.id}`}
            aria-selected={tab.id === current}
            aria-controls={`panel-${tab.id}`}
            className="tab"
            onClick={() => setCurrent(tab.id)}
          >
            <span>{tab.label}</span>
            {tab.count !== undefined && <span className="tab-count">{tab.count}</span>}
          </button>
        ))}
      </div>
      {tabs.map((tab) => (
        <div
          key={tab.id}
          role="tabpanel"
          id={`panel-${tab.id}`}
          aria-labelledby={`tab-${tab.id}`}
          hidden={tab.id !== current}
          className="tab-panel"
        >
          {tab.content}
        </div>
      ))}
    </div>
  );
}
