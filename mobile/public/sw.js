/*
 * Service worker мобильного клиента.
 *
 * Кэш нужен для установки на домашний экран и мгновенного старта: приложение
 * открывается даже раньше, чем телефон установит связь с ПК. Сеть при этом
 * всегда в приоритете — свежая сборка агента подхватывается сразу, а кэш
 * выручает только при недоступной сети.
 */
const CACHE = 'netscan-shell-v1';

self.addEventListener('install', (event) => {
  self.skipWaiting();
  event.waitUntil(
    caches.open(CACHE).then((cache) => cache.addAll(['./', './manifest.webmanifest', './icon-192.png'])).catch(() => undefined),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  // Запросы к API и WebSocket никогда не кэшируются: сканы должны идти вживую.
  if (request.method !== 'GET' || new URL(request.url).pathname.startsWith('/api/')) return;

  event.respondWith(
    fetch(request)
      .then((response) => {
        const copy = response.clone();
        caches.open(CACHE).then((cache) => cache.put(request, copy)).catch(() => undefined);
        return response;
      })
      .catch(() => caches.match(request).then((cached) => cached || caches.match('./'))),
  );
});
