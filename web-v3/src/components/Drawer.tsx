import { useCallback, useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";

interface DrawerProps {
  open: boolean;
  title: string;
  onClose: () => void;
  children: ReactNode;
  /** Visual skin for the sheet chrome: warm leather (satchel), a dim
   *  dressing-chamber (equipment), a merchant cart (shop), a chalkboard
   *  (trainer), a parchment journal (combat log), or a cork quest board. */
  variant?: "default" | "satchel" | "equipment" | "shop" | "trainer" | "journal" | "questboard" | "grimoire" | "mail" | "feature" | "tome" | "vault" | "cabinet" | "board" | "starframe" | "archive" | "stainedglass" | "codex" | "boudoir" | "estate" | "fortune" | "dicetable" | "auctionhouse" | "forge" | "professions";
  /** Optional background art for a skinned variant (server asset). */
  skinBg?: string;
  /** Height (as a fraction of the viewport) the drawer snaps to when it opens. */
  initialHeight?: number;
}

const SNAP_HALF = 0.6;
const SNAP_FULL = 0.95;
const DISMISS_THRESHOLD = 0.3;
const DRAG_VELOCITY_DISMISS = 800; // px/s
const ANIMATION_MS = 300;

export function Drawer({ open, title, onClose, children, variant = "default", skinBg, initialHeight = SNAP_HALF }: DrawerProps) {
  const [height, setHeight] = useState(initialHeight);
  const [dragging, setDragging] = useState(false);
  const [renderPhase, setRenderPhase] = useState<"hidden" | "animating" | "open">(open ? "open" : "hidden");
  const [prevOpen, setPrevOpen] = useState(open);
  const dragStartRef = useRef<{ y: number; height: number; time: number } | null>(null);
  const sheetRef = useRef<HTMLDivElement | null>(null);

  // Detect open/close transitions during render and schedule animation phase
  if (open !== prevOpen) {
    setPrevOpen(open);
    if (open) {
      // Drawer is opening — mount it and snap to its initial height
      setHeight(initialHeight);
      setRenderPhase("open");
    } else {
      // Drawer is closing — collapse height, animation phase will unmount when done
      setHeight(0);
      setRenderPhase("animating");
    }
  }

  // After height collapses, unmount once the transition has played out
  useEffect(() => {
    if (renderPhase !== "animating") return;
    const timer = window.setTimeout(() => setRenderPhase("hidden"), ANIMATION_MS);
    return () => window.clearTimeout(timer);
  }, [renderPhase]);

  const handleDragStart = useCallback((clientY: number) => {
    setDragging(true);
    dragStartRef.current = { y: clientY, height, time: Date.now() };
  }, [height]);

  const handleDragMove = useCallback((clientY: number) => {
    const start = dragStartRef.current;
    if (!start) return;
    const vh = window.innerHeight;
    const dy = start.y - clientY;
    const newHeight = Math.max(0.1, Math.min(SNAP_FULL, start.height + dy / vh));
    setHeight(newHeight);
  }, []);

  const handleDragEnd = useCallback((clientY: number) => {
    const start = dragStartRef.current;
    setDragging(false);
    dragStartRef.current = null;

    if (!start) return;

    const elapsed = (Date.now() - start.time) / 1000;
    const velocity = (start.y - clientY) / elapsed;

    // Fast flick down = dismiss
    if (velocity < -DRAG_VELOCITY_DISMISS) {
      onClose();
      return;
    }
    // Fast flick up = full screen
    if (velocity > DRAG_VELOCITY_DISMISS) {
      setHeight(SNAP_FULL);
      return;
    }

    // Snap to nearest position
    if (height < DISMISS_THRESHOLD) {
      onClose();
    } else if (height < (SNAP_HALF + SNAP_FULL) / 2) {
      setHeight(SNAP_HALF);
    } else {
      setHeight(SNAP_FULL);
    }
  }, [height, onClose]);

  // Touch handlers
  const onTouchStart = useCallback((e: React.TouchEvent) => {
    handleDragStart(e.touches[0].clientY);
  }, [handleDragStart]);

  const onTouchMove = useCallback((e: React.TouchEvent) => {
    handleDragMove(e.touches[0].clientY);
  }, [handleDragMove]);

  const onTouchEnd = useCallback((e: React.TouchEvent) => {
    handleDragEnd(e.changedTouches[0].clientY);
  }, [handleDragEnd]);

  // Mouse handlers (for desktop testing)
  const onMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    handleDragStart(e.clientY);
  }, [handleDragStart]);

  useEffect(() => {
    if (!dragging) return;
    const onMove = (e: MouseEvent) => handleDragMove(e.clientY);
    const onUp = (e: MouseEvent) => handleDragEnd(e.clientY);
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
    return () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
  }, [dragging, handleDragMove, handleDragEnd]);

  // Escape to close
  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [open, onClose]);

  if (renderPhase === "hidden") return null;

  const transition = dragging ? "none" : `height ${ANIMATION_MS}ms var(--ease-out-soft)`;

  return (
    <>
      <div
        className={`drawer-backdrop${open ? " drawer-backdrop-visible" : ""}`}
        onClick={onClose}
        aria-hidden="true"
      />
      <div
        ref={sheetRef}
        className={`drawer-sheet${variant !== "default" ? ` drawer-sheet-${variant}` : ""}`}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        style={{
          ["--drawer-height" as string]: `${height * 100}vh`,
          ["--drawer-transition" as string]: transition,
          ...(skinBg ? { ["--skin-bg" as string]: `url("${skinBg}")` } : {}),
        }}
      >
        <div
          className="drawer-handle-zone"
          onTouchStart={onTouchStart}
          onTouchMove={onTouchMove}
          onTouchEnd={onTouchEnd}
          onMouseDown={onMouseDown}
        >
          <div className="drawer-handle" aria-hidden="true" />
        </div>
        <header className="drawer-header">
          <h2 className="drawer-title">{title}</h2>
          <button
            type="button"
            className="drawer-close"
            onClick={onClose}
            aria-label="Close"
          >
            &#x2715;
          </button>
        </header>
        <div className="drawer-body">
          {children}
        </div>
      </div>
    </>
  );
}
