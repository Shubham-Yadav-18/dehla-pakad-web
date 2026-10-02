// 🌟 OFFLINE CACHE & INSTANT LOAD ENGINE
const CACHE_NAME = 'dehla-pakad-v2';
const STATIC_ASSETS = [
    './',
    './index.html',
    './style.css',
    './app.js',
    './manifest.json',
    './icons/icon.svg'
];

// 1. Install Phase: Cache the core UI files immediately
self.addEventListener('install', (event) => {
    event.waitUntil(
        caches.open(CACHE_NAME).then((cache) => {
            console.log('[SW] Caching core assets');
            return cache.addAll(STATIC_ASSETS);
        })
    );
    self.skipWaiting(); // Force activation
});

// 2. Activate Phase: Clean up old caches if we update the app
self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys().then((cacheNames) => {
            return Promise.all(
                cacheNames.map((cache) => {
                    if (cache !== CACHE_NAME) {
                        console.log('[SW] Clearing old cache');
                        return caches.delete(cache);
                    }
                })
            );
        })
    );
    self.clients.claim(); // Take control of all pages immediately
});

// 3. Fetch Phase: Serve from Cache, Fallback to Network
self.addEventListener('fetch', (event) => {
    event.respondWith(
        caches.match(event.request).then((cachedResponse) => {
            // Return cached file if found (Lag-Free), otherwise fetch from Netlify
            return cachedResponse || fetch(event.request);
        })
    );
});