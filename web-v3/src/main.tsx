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
