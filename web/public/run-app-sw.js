// Offline support for /run-app.html (the Phone app studio runner) and
// nothing else: registered with scope "/run-app.html", so it never controls
// the console. The runner is one self-contained file and every shared app
// lives in the phone's own storage, so caching this page is all "works
// offline" needs. Network-first so a deploy is picked up on the next visit.
const CACHE = "vd-run-app-v1";
const PAGE = "/run-app.html";

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll([PAGE, "/run-app.webmanifest"])).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith("vd-run-app-") && k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== self.location.origin) return;
  if (url.pathname !== PAGE && url.pathname !== "/run-app.webmanifest") return;
  e.respondWith(
    fetch(e.request)
      .then((res) => {
        if (res.ok) {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(url.pathname, copy));
        }
        return res;
      })
      .catch(() => caches.match(url.pathname))
  );
});
