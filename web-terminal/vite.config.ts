import { resolve } from "node:path";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  base: "/terminal/",
  publicDir: false,
  plugins: [react()],
  resolve: {
    alias: {
      "@v3": resolve(__dirname, "../web-v3/src"),
    },
  },
  build: {
    outDir: resolve(__dirname, "../src/main/resources/web-terminal"),
    emptyOutDir: true,
  },
});
