import { useEffect, useRef } from "react";
import { Application } from "pixi.js";
import { SceneManager } from "./SceneManager";

export function PixiCanvas() {
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

      container.appendChild(app.canvas as HTMLCanvasElement);
      const scene = new SceneManager(app);
      sceneRef.current = scene;

      app.ticker.add(() => {
        scene.update();
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
      style={{ width: "100%", height: "100%", minHeight: 200 }}
    />
  );
}
