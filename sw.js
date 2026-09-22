const CACHE_NAME = 'dream-hub-v1';
const ASSETS = [
  './index.html',
  './manifest.json',
  './icon-192.png',
  './icon-512.png'
];

// Install: Cache core UI assets
self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(ASSETS))
  );
  self.skipWaiting();
});

// Activate: Clean old caches
self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k))
      );
    })
  );
  self.clients.claim();
});

// Fetch: Network first for HTML/data, cache fallback
self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);

  // Video streams should not be intercepted by service worker cache
  if (url.pathname.includes('.mp4') || url.hostname.includes('dramabox-stream')) {
    return;
  }

  e.respondWith(
    fetch(e.request)
      .then((res) => {
        // Cache response for shell files
        if (res.status === 200 && (url.pathname.endsWith('.html') || url.pathname.endsWith('.json') || url.pathname.endsWith('.png'))) {
          const clone = res.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(e.request, clone));
        }
        return res;
      })
      .catch(() => caches.match(e.request))
  );
});
