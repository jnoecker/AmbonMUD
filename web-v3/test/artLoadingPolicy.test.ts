import { describe, expect, test } from "bun:test";
import { loadCriticalArtImage } from "../src/canvas/loginArtFit";

describe("painted art loading policy", () => {
  test("gives the exact resolved scene URL high priority", () => {
    const image = { decoding: "auto", fetchPriority: "auto", src: "" } as Pick<
      HTMLImageElement,
      "decoding" | "fetchPriority" | "src"
    >;

    loadCriticalArtImage(image, "https://art.example/content-hash.png");

    expect(image).toEqual({
      decoding: "async",
      fetchPriority: "high",
      src: "https://art.example/content-hash.png",
    });
  });

  test("startup source does not preload a guessed login path or the full asset catalog", async () => {
    const [mainSource, appSource, serviceWorkerSource, viteSource] = await Promise.all([
      Bun.file(new URL("../src/main.tsx", import.meta.url)).text(),
      Bun.file(new URL("../src/App.tsx", import.meta.url)).text(),
      Bun.file(new URL("../public/sw.js", import.meta.url)).text(),
      Bun.file(new URL("../vite.config.ts", import.meta.url)).text(),
    ]);

    expect(mainSource).not.toContain("/images/global_assets/login_");
    expect(appSource).not.toContain("Object.values(state.serverAssets)");
    expect(appSource).not.toContain("preloadedArt");
    expect(serviceWorkerSource).toContain("HASHED_ART");
    expect(serviceWorkerSource).toContain("return cached || fetchAndCache()");
    expect(viteSource).not.toContain('return path.includes("AdminPanel") ? "panel-admin" : "panels"');
  });
});
