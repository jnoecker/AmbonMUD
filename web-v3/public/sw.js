// AmbonMUD Service Worker — cache static assets for offline splash + faster loads.
// Bump CACHE_NAME whenever caching behavior changes: activate purges older
// versions, which also self-heals clients whose v1 cache captured error
// responses (the old fetch handler cached 404s/opaque bodies, permanently
// breaking styling for that browser).
const CACHE_NAME = "ambonmud-v4";
const STATIC_ASSETS = ["/", "/icons/icon.svg"];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(STATIC_ASSETS))
  );
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    )
  );
  self.clients.claim();
});

// Only complete, successful responses we can inspect may enter the cache.
// Same-origin fetches are "basic"; cross-origin art fetched with CORS is
// "cors" (its status is readable). Opaque no-cors responses are excluded on
// purpose: their status is unreadable (always 0), so caching one would pin a
// 404/error and serve the broken response forever.
const cacheable = (response) =>
  Boolean(response && response.ok && (response.type === "basic" || response.type === "cors"));

// Painted art (panel skins, keepsakes, minimap textures) served either locally
// from /images/ or cross-origin from the R2 CDN. Production R2 names are
// content-hashed; bundled local fallbacks retain stable paths.
const ART_EXT = /\.(?:png|jpe?g|webp|avif|gif|svg)(?:\?.*)?$/i;
const isArt = (request, url) => request.destination === "image" || ART_EXT.test(url.pathname);
// Arcanum's production contract publishes art under content-hashed filenames.
// Those URLs are immutable, so revalidating them wastes a request on every
// visit. Stable legacy URLs retain stale-while-revalidate below.
const HASHED_ART = /\/[a-f0-9]{32,}\.(?:png|jpe?g|webp|avif|gif|svg)$/i;

const fetchCrossOriginArt = (request, url, cache) =>
  fetch(new Request(url.href, { mode: "cors", credentials: "omit" }))
    .then((response) => {
      if (cacheable(response)) cache.put(request, response.clone());
      return response;
    })
    .catch(() => fetch(request)); // no CORS available — opaque, uncached

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);

  if (url.origin !== self.location.origin) {
    // Cross-origin painted art (R2 CDN). Content-hashed production URLs are
    // cache-first; stable legacy URLs retain stale-while-revalidate so a cached
    // copy survives a flaky network. The
    // SW can't read an opaque no-cors response's status, so revalidate with a
    // CORS request we *can* inspect and cache; if the origin sends no CORS
    // headers that fetch rejects, fall back to a plain (opaque, uncached) fetch
    // so art still loads. Always end in a real network fetch — never return
    // undefined, which would break the image.
    if (event.request.method === "GET" && isArt(event.request, url)) {
      event.respondWith(
        caches.open(CACHE_NAME).then((cache) =>
          cache.match(event.request, { ignoreVary: true }).then((cached) => {
            const fetchAndCache = () => fetchCrossOriginArt(event.request, url, cache);
            if (HASHED_ART.test(url.pathname)) return cached || fetchAndCache();
            const revalidate = fetchAndCache();
            return cached || revalidate;
          })
        )
      );
    }
    return;
  }

  // Network-first for HTML, and re-cache the shell on every successful
  // navigation. A shell frozen at install time references hashed bundles that
  // stop existing after a redeploy, so a stale offline fallback renders an
  // unstyled page.
  if (event.request.mode === "navigate") {
    event.respondWith(
      fetch(event.request)
        .then((response) => {
          if (cacheable(response)) {
            const clone = response.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put("/", clone));
          }
          return response;
        })
        .catch(() => caches.match("/"))
    );
    return;
  }

  // Content-hashed bundles are immutable: cache-first.
  if (url.pathname.startsWith("/assets/")) {
    event.respondWith(
      caches.match(event.request).then(
        (cached) =>
          cached ||
          fetch(event.request).then((response) => {
            if (cacheable(response)) {
              const clone = response.clone();
              caches.open(CACHE_NAME).then((cache) => cache.put(event.request, clone));
            }
            return response;
          })
      )
    );
    return;
  }

  // Art and icons are mutable (R2 art can change under a stable URL):
  // stale-while-revalidate paints warm from cache and refreshes in the
  // background so updated art lands on the next load.
  if (url.pathname.startsWith("/icons/") || url.pathname.startsWith("/images/")) {
    event.respondWith(
      caches.open(CACHE_NAME).then((cache) =>
        cache.match(event.request).then((cached) => {
          const refresh = fetch(event.request)
            .then((response) => {
              if (cacheable(response)) cache.put(event.request, response.clone());
              return response;
            })
            .catch(() => cached);
          return cached || refresh;
        })
      )
    );
  }
});
