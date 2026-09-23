const CACHE_NAME = 'dream-hub-v4';
const ASSETS = [
  './index.html',
  './manifest.json',
  './logo.png',
  './logo-cropped.png',
  './logo-d.png',
  './icon-192.png',
  './icon-512.png'
];

self.addEventListener('install', (e) => {
  self.skipWaiting();
  e.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(ASSETS))
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k))
      );
    }).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);

  if (url.pathname.includes('.mp4') || url.pathname.includes('.m3u8') || url.hostname.includes('dramabox-stream') || url.hostname.includes('moviebox')) {
    return;
  }

  // Network first for index.html so updates are always seen immediately
  if (url.pathname.endsWith('index.html') || url.pathname.endsWith('/') || url.pathname.endsWith('dream-hub/')) {
    e.respondWith(
      fetch(e.request)
        .then((res) => {
          if (res.status === 200) {
            const clone = res.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(e.request, clone));
          }
          return res;
        })
        .catch(() => caches.match(e.request))
    );
    return;
  }

  e.respondWith(
    caches.match(e.request).then((cached) => cached || fetch(e.request))
  );
});
