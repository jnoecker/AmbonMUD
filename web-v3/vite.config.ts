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
          // All drawer panels are React.lazy'd from App; bundle them as one
          // on-demand chunk (single fetch on first drawer open), with the
          // staff-only AdminPanel split out so players never download it.
          if (path.includes("/src/components/panels/")) {
            return path.includes("AdminPanel") ? "panel-admin" : "panels";
          }
          // Modules shared by the entry AND the panels must not be colocated
          // into "panels" (rollup's default would do that, which turns the
          // panels chunk into a static dependency of the entry and defeats
          // the lazy split — every panel would execute at startup again).
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
