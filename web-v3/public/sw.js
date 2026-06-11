// AmbonMUD Service Worker — cache static assets for offline splash + faster loads.
// Bump CACHE_NAME whenever caching behavior changes: activate purges older
// versions, which also self-heals clients whose v1 cache captured error
// responses (the old fetch handler cached 404s/opaque bodies, permanently
// breaking styling for that browser).
const CACHE_NAME = "ambonmud-v2";
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

// Only complete, successful, same-origin responses may enter the cache.
// Caching anything else (404 during a server restart, an opaque error) pins
// the failure: cache-first would then serve the broken response forever.
const cacheable = (response) => Boolean(response && response.ok && response.type === "basic");

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin) return;

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
