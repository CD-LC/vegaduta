// Service Worker hosting a persistent @mlc-ai/web-llm MLCEngine, so the
// on-device tier-1 model survives a page reload within the same browser tab
// (latency audit fix #2, 2026-08-06 — see web/app/lib/edge/tier1.ts's
// "Service Worker engine host" section for the full design/rationale).
//
// Registered from web/app/lib/edge/tier1.ts via:
//   navigator.serviceWorker.register("/webllm-sw.js", { type: "module" })
//
// The library is imported same-origin from /vendored/web-llm.js, which
// scripts/vendor-webllm.mjs copies out of node_modules on every build/dev
// start (npm `prebuild`/`predev`). That keeps this worker on the exact same
// @mlc-ai/web-llm version the page-side dynamic import resolves — the
// client/worker message protocol (WorkerRequest/WorkerResponse shapes) can
// never drift — and removes the runtime dependency on a third-party CDN that
// the previous esm.run import carried (and that a same-origin script-src CSP
// would have blocked).
import * as webllm from "/vendored/web-llm.js";

const engine = new webllm.MLCEngine();
// Handles the full worker message protocol (reload/chat.completions.create/
// interruptGenerate/unload/etc.) — every existing tier1.ts call site talks
// to this transparently through ServiceWorkerMLCEngine on the client side.
new webllm.ServiceWorkerMLCEngineHandler(engine);

// Activate immediately (don't wait for all tabs to close) and take control
// of any already-open page without requiring a reload — the SW registration
// call in tier1.ts happens on the same page load that wants to use it.
self.addEventListener("install", () => {
  self.skipWaiting();
});
self.addEventListener("activate", (event) => {
  event.waitUntil(self.clients.claim());
});
