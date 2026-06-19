import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
// Self-hosted fonts, replacing the Google Fonts <link> in index.html. Bundled
// into the build and served same-origin from /assets/, so a hard refresh can't
// orphan them on a flaky cross-origin CDN (the failure mode that made painted
// text flip grey/black), and the service worker's cache-first /assets/ rule
// already covers them with no SW change. Weights match the old request exactly
// (normal style only — it had no italics); the @fontsource static packages
// register the canonical family names ("Cormorant Garamond", etc.) the CSS and
// canvas/xterm code already reference, so nothing else changes.
import "@fontsource/cormorant-garamond/500.css";
import "@fontsource/cormorant-garamond/600.css";
import "@fontsource/cormorant-garamond/700.css";
import "@fontsource/nunito-sans/400.css";
import "@fontsource/nunito-sans/600.css";
import "@fontsource/nunito-sans/700.css";
import "@fontsource/nunito-sans/800.css";
import "@fontsource/jetbrains-mono/400.css";
import "@fontsource/jetbrains-mono/500.css";
import "@fontsource/jetbrains-mono/600.css";
import App from "./App.tsx";

// Preload the painted background for whichever login screen shows first, so it
// paints warm instead of flashing its fallback. The art is a CSS background and
// only arrives via Server.Assets ~immediately before the login prompt, leaving
// no time to fetch before first paint; kicking the request off here (before
// React mounts, well ahead of the websocket connect) closes that gap. Returning
// players land on the welcome-back picker, everyone else on the name screen, and
// each scene swaps to its phone-portrait companion on a portrait viewport — so
// preload the variant the first paint will actually use. Bundled login art
// always resolves to /images/global_assets/ regardless of the world/config
// overlay (see GmcpEmitter asset resolution), so the path is fixed.
try {
  const tokens = JSON.parse(localStorage.getItem("ambonmud_auth_tokens") ?? "{}") as Record<string, string>;
  const base = Object.keys(tokens).length > 0 ? "login_picker_bg" : "login_bg";
  const portrait = window.matchMedia("(orientation: portrait)").matches;
  const key = portrait ? `${base}_portrait` : base;
  const link = document.createElement("link");
  link.rel = "preload";
  link.as = "image";
  link.href = `/images/global_assets/${key}.png`;
  document.head.appendChild(link);
} catch {
  // localStorage / matchMedia unavailable — skip the preload, the app still works.
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);

// Register service worker for PWA installability + asset caching
if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/sw.js").catch(() => {
    // SW registration failed — app still works, just no offline caching
  });
}
