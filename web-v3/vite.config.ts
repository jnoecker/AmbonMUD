import { resolve } from "node:path";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// https://vite.dev/config/
export default defineConfig({
  base: "/v3/",
  publicDir: false,
  plugins: [react()],
  build: {
    outDir: resolve(__dirname, "../src/main/resources/web-v3"),
    emptyOutDir: true,
  },
});
