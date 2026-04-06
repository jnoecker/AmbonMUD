import { forwardRef, useImperativeHandle } from "react";
import type { ReactNode } from "react";
import { useBottomSheet } from "../hooks/useBottomSheet";
import type { SnapPoint } from "../hooks/useBottomSheet";
import { TabBar } from "./TabBar";

export interface BottomSheetProps {
  activePanel: string;
  onPanelChange: (panel: string) => void;
  children: ReactNode;
}

export interface BottomSheetHandle {
  collapse: () => void;
  snapTo: (snap: SnapPoint) => void;
}

export const BottomSheet = forwardRef<BottomSheetHandle, BottomSheetProps>(
  function BottomSheet({ activePanel, onPanelChange, children }, ref) {
    const { sheetRef, handleRef, currentSnap, isDragging, snapTo, collapse } = useBottomSheet("peek");

    useImperativeHandle(ref, () => ({
      collapse,
      snapTo,
    }), [collapse, snapTo]);

    const handlePanelChange = (panel: string) => {
      onPanelChange(panel);
      // Snap to half when a tab is tapped (if currently at peek)
      if (currentSnap === "peek") {
        snapTo("half");
      }
    };

    return (
      <div
        ref={sheetRef}
        className={`bottom-sheet ${isDragging ? "bottom-sheet--dragging" : ""}`}
        style={{ "--sheet-translate-y": `${currentSnap === "peek" ? 85 : currentSnap === "half" ? 50 : 10}%` } as React.CSSProperties}
        data-snap={currentSnap}
      >
        <div
          ref={handleRef}
          className="bottom-sheet__drag-handle"
          style={{ touchAction: "none" }}
          aria-label="Drag to resize panel"
          role="separator"
          aria-orientation="horizontal"
        />

        <TabBar
          activePanel={activePanel}
          onPanelChange={handlePanelChange}
        />

        <div className="bottom-sheet__content">
          {children}
        </div>
      </div>
    );
  },
);
