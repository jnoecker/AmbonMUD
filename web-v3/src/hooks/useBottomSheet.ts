import { useCallback, useEffect, useRef, useState } from "react";

export type SnapPoint = "peek" | "half" | "full";

/** Snap positions as fractions of window.innerHeight (translateY from top). */
const SNAP_POSITIONS: Record<SnapPoint, number> = {
  peek: 0.85,
  half: 0.50,
  full: 0.10,
};

/** Mid-points between snap positions for nearest-snap calculation. */
function nearestSnap(fraction: number): SnapPoint {
  // Thresholds between snap positions:
  // full(0.10) --- mid(0.30) --- half(0.50) --- mid(0.675) --- peek(0.85)
  if (fraction < 0.30) return "full";
  if (fraction < 0.675) return "half";
  return "peek";
}

export interface UseBottomSheetReturn {
  sheetRef: React.RefObject<HTMLDivElement | null>;
  handleRef: React.RefObject<HTMLDivElement | null>;
  currentSnap: SnapPoint;
  isDragging: boolean;
  snapTo: (snap: SnapPoint) => void;
  collapse: () => void;
}

export function useBottomSheet(initialSnap: SnapPoint = "peek"): UseBottomSheetReturn {
  const sheetRef = useRef<HTMLDivElement | null>(null);
  const handleRef = useRef<HTMLDivElement | null>(null);
  const [currentSnap, setCurrentSnap] = useState<SnapPoint>(initialSnap);
  const [isDragging, setIsDragging] = useState(false);

  // Refs for drag state (no React re-renders during drag per Research anti-pattern)
  const dragStartY = useRef(0);
  const dragStartTranslate = useRef(0);
  const draggingRef = useRef(false);

  const snapTo = useCallback((snap: SnapPoint) => {
    setCurrentSnap(snap);
    if (sheetRef.current) {
      sheetRef.current.style.transform = "";
      sheetRef.current.style.setProperty("--sheet-translate-y", `${SNAP_POSITIONS[snap] * 100}%`);
    }
  }, []);

  const collapse = useCallback(() => {
    snapTo("peek");
  }, [snapTo]);

  // Pointer event handlers on the drag handle
  useEffect(() => {
    const handle = handleRef.current;
    if (!handle) return;

    const onPointerDown = (e: PointerEvent) => {
      // Only primary pointer (left click / first touch)
      if (e.button !== 0) return;
      handle.setPointerCapture(e.pointerId);
      dragStartY.current = e.clientY;
      dragStartTranslate.current = SNAP_POSITIONS[currentSnap] * window.innerHeight;
      draggingRef.current = true;
      setIsDragging(true);
    };

    const onPointerMove = (e: PointerEvent) => {
      if (!draggingRef.current) return;
      const delta = e.clientY - dragStartY.current;
      const newY = dragStartTranslate.current + delta;
      const vh = window.innerHeight;
      // Clamp between full and peek positions
      const minY = SNAP_POSITIONS.full * vh;
      const maxY = SNAP_POSITIONS.peek * vh;
      const clamped = Math.max(minY, Math.min(maxY, newY));

      if (sheetRef.current) {
        sheetRef.current.style.transform = `translateY(${clamped}px)`;
      }
    };

    const onPointerUp = (e: PointerEvent) => {
      if (!draggingRef.current) return;
      draggingRef.current = false;
      setIsDragging(false);

      try {
        handle.releasePointerCapture(e.pointerId);
      } catch {
        // Pointer capture may already be released
      }

      // Determine current position
      const vh = window.innerHeight;
      let currentY = SNAP_POSITIONS[currentSnap] * vh;

      if (sheetRef.current) {
        const transform = sheetRef.current.style.transform;
        const match = /translateY\(([^)]+)px\)/.exec(transform);
        if (match) {
          currentY = Number.parseFloat(match[1]);
        }
      }

      // Find nearest snap
      const fraction = currentY / vh;
      const snap = nearestSnap(fraction);
      snapTo(snap);
    };

    const onPointerCancel = () => {
      if (!draggingRef.current) return;
      draggingRef.current = false;
      setIsDragging(false);
      // Return to current snap
      snapTo(currentSnap);
    };

    handle.addEventListener("pointerdown", onPointerDown);
    handle.addEventListener("pointermove", onPointerMove);
    handle.addEventListener("pointerup", onPointerUp);
    handle.addEventListener("pointercancel", onPointerCancel);

    return () => {
      handle.removeEventListener("pointerdown", onPointerDown);
      handle.removeEventListener("pointermove", onPointerMove);
      handle.removeEventListener("pointerup", onPointerUp);
      handle.removeEventListener("pointercancel", onPointerCancel);
    };
  }, [currentSnap, snapTo]);

  return {
    sheetRef,
    handleRef,
    currentSnap,
    isDragging,
    snapTo,
    collapse,
  };
}
