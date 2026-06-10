import { memo, useEffect, useRef } from "react";
import { Application } from "pixi.js";
import { SceneManager } from "./SceneManager";

// memo: props-less component under GameShell, which re-renders on every
// vitals/room/combat update — the canvas itself never needs to.
export const PixiCanvas = memo(function PixiCanvas() {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const appRef = useRef<Application | null>(null);
  const sceneRef = useRef<SceneManager | null>(null);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    let destroyed = false;
    const app = new Application();
    appRef.current = app;

    const init = async () => {
      await app.init({
        background: 0x2f3446,
        resizeTo: container,
        antialias: true,
        autoDensity: true,
        resolution: window.devicePixelRatio || 1,
      });

      if (destroyed) {
        app.destroy(true);
        return;
      }

      const canvasEl = app.canvas as HTMLCanvasElement;
      // The host div carries role="img" + label; the raw canvas adds nothing
      // for assistive tech and should not be probed separately.
      canvasEl.setAttribute("aria-hidden", "true");
      container.appendChild(canvasEl);
      const scene = new SceneManager(app);
      sceneRef.current = scene;

      app.ticker.add((ticker) => {
        scene.update(ticker.deltaMS);
      });

      const ro = new ResizeObserver(() => {
        if (destroyed) return;
        app.resize();
        scene.resize(app.screen.width, app.screen.height);
      });
      ro.observe(container);

      // Store for cleanup
      (container as unknown as Record<string, unknown>).__pixiRO = ro;
    };

    init();

    return () => {
      destroyed = true;
      const ro = (container as unknown as Record<string, unknown>).__pixiRO as ResizeObserver | undefined;
      ro?.disconnect();
      sceneRef.current?.destroy();
      sceneRef.current = null;
      if (appRef.current) {
        appRef.current.destroy(true);
        appRef.current = null;
      }
    };
  }, []);

  return (
    <div
      ref={containerRef}
      className="pixi-canvas-host"
      role="img"
      aria-label="Game world canvas — room visuals, mobs, and combat rendered here. Use the terminal for accessible text output."
      style={{ width: "100%", height: "100%", minHeight: 200 }}
    />
  );
});
