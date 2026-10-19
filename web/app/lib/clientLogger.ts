// Lightweight client-side error reporter — NOT a general console.error
// replacement. Call this only for browser-side errors worth keeping
// server-side and searchable in Grafana/Loki (auth failures, failed API
// calls). It stays deliberately low-volume and opt-in per call site rather
// than mirroring every console.error in the app.
//
// Posts to /api/client-log (this app's own route handler, which
// forwards into the same pino/stdout pipeline Promtail already tails
// for every other service — see app/lib/logger.ts).
//
// Not yet wired into any existing call site (api.ts's fetch error
// handling, auth.ts's Keycloak failure paths) — those files were mid-edit
// by another session as of 2026-07-18, deferred to avoid a collision.
// Natural next step once that settles: call reportClientError("api_call_failed", ...)
// from api.ts's error path and reportClientError("auth_failure", ...) from
// auth.ts's Keycloak error paths.

export type ClientLogType = "auth_failure" | "api_call_failed" | "client_error" | "api_backend_unreachable";

interface ClientLogContext {
  [key: string]: unknown;
}

export function reportClientError(type: ClientLogType, message: string, context?: ClientLogContext): void {
  // Always keep the browser console output — this is additive, not a
  // replacement for local debugging.
  console.error(`[${type}]`, message, context ?? "");

  if (typeof window === "undefined") return; // no-op during SSR/build

  try {
    // The receiving route rejects bodies over 8 KB (413). A message that
    // embeds a URL - e.g. a 20 KB model-pricing query string - used to make
    // every report bounce, so clip here rather than lose the report.
    const clip = <T>(v: T, max: number): T | string =>
      typeof v === "string" && v.length > max ? `${v.slice(0, max)}…[+${v.length - max}]` : v;
    const safeContext = context
      ? Object.fromEntries(
          Object.entries(context)
            .slice(0, 12)
            .map(([k, v]) => [k, clip(v, 300)])
        )
      : undefined;
    const payload = JSON.stringify({ type, message: clip(message, 1000), context: safeContext });
    // keepalive lets the request survive a page unload (e.g. a redirect
    // right after an auth failure) — the main reason to prefer this over
    // a plain fetch for error reporting.
    void fetch("/api/client-log", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: payload,
      keepalive: true,
    }).catch(() => {
      // Never let logging itself surface a new error to the user.
    });
  } catch {
    // fetch unavailable/blocked — swallow, this is best-effort.
  }
}
