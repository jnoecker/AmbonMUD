import { resolve } from "node:path";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { visualizer } from "rollup-plugin-visualizer";

// https://vite.dev/config/
export default defineConfig({
  base: "/",
  plugins: [
    react(),
    // Bundle breakdown: ANALYZE=1 bun run build → stats.json (raw-data
    // template) with per-module sizes. Emits nothing in normal builds.
    ...(process.env.ANALYZE
      ? [visualizer({ filename: "stats.json", template: "raw-data" })]
      : []),
  ],
  build: {
    outDir: resolve(__dirname, "../src/main/resources/web-v3"),
    emptyOutDir: true,
    rollupOptions: {
      output: {
        manualChunks(id: string) {
          const path = id.replace(/\\/g, "/");
          if (path.includes("node_modules")) {
            // Pixi (and its earcut dependency) stay their own chunk.
            if (path.includes("/pixi.js/") || path.includes("/earcut/")) return "pixi";
            // Framework chunk: cache survives app-code deploys.
            if (/\/node_modules\/(react|react-dom|scheduler)\//.test(path)) return "vendor";
            // xterm is dynamically imported (useTerminal) — keep it isolated.
            if (path.includes("/@xterm/")) return "xterm";
            return undefined;
          }
          // Drawer panels are React.lazy'd from App. Let Rollup keep their
          // dynamic entry boundaries instead of forcing them into one manual
          // chunk: grouping them caused that chunk to become a static entry
          // dependency and put every panel back on the login path.
          // Modules shared by the entry and lazy panels stay in a small,
          // cache-stable shared chunk.
          if (
            /\/src\/(utils|constants|imageDefaults|types)\.ts/.test(path) ||
            path.includes("/src/canvas/GameStateBridge") ||
            path.includes("/src/components/Icons")
          ) {
            return "shared";
          }
          return undefined;
        },
      },
    },
  },
});
