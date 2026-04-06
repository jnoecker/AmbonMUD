import type { ReactNode } from "react";

export function LayoutShell({ children }: { children: ReactNode }) {
  return (
    <div className="layout-shell">
      {children}
    </div>
  );
}
