// Dev-only fetch interceptor for /ui-kit/dashboard-preview and /ui-kit/console-preview/*.
//
// The real console (AppShell + DashboardHome) talks to core through plain
// fetch() calls built as `${API_BASE}${path}` (app/lib/api/core.ts#doFetch,
// plus the two bare fetches in telemetry.ts#getSystemHealthSilent). This
// module wraps window.fetch ONCE and answers every request under API_BASE
// with realistic sample JSON, so a designer can look at the dashboard with
// neither Keycloak nor core running. Everything not under API_BASE (Next
// chunks, open-meteo, ipapi, /api/client-log) passes straight through.
//
// Nothing here fakes auth tokens: auth.getToken() still resolves null on a
// public route, and the mock simply never checks the Authorization header.
import { API_BASE } from "../../lib/api";
import type { JsonBody } from "./mock-sample-data";
import { patternRoutes, routes } from "./mock-routes";

const INSTALLED_FLAG = "__vdDashboardPreviewMockApi";

// Anything unmatched: a path whose last static segment looks plural (agents,
// runs, servers...) is most likely a list; everything else a single object.
function fallbackBody(pathname: string): JsonBody {
  const segments = pathname.split("/").filter(Boolean);
  const last = segments[segments.length - 1] || "";
  const looksLikeId = /^[0-9a-f-]{8,}$|^\d+$/i.test(last);
  const base = looksLikeId ? segments[segments.length - 2] || "" : last;
  return /s$/i.test(base) && !looksLikeId ? [] : {};
}

function jsonResponse(body: JsonBody, status = 200, url = ""): Response {
  const text = body === null || body === undefined ? "" : JSON.stringify(body);
  const res = new Response(status === 204 ? null : text, {
    status,
    statusText: status === 200 ? "OK" : "",
    headers: { "Content-Type": "application/json" },
  });
  // Response.url is read-only; toJson's error path reports res.url, so try to
  // expose the requested URL without failing if the runtime refuses.
  try {
    Object.defineProperty(res, "url", { value: url });
  } catch {
    /* ignore */
  }
  return res;
}

function requestUrl(input: RequestInfo | URL): string {
  if (typeof input === "string") return input;
  if (input instanceof URL) return input.href;
  return input.url;
}

function requestMethod(input: RequestInfo | URL, init?: RequestInit): string {
  if (init?.method) return init.method.toUpperCase();
  if (typeof Request !== "undefined" && input instanceof Request) return input.method.toUpperCase();
  return "GET";
}

/**
 * Wraps window.fetch so every request under API_BASE is answered locally.
 * Idempotent (guarded by a window flag) and dev-only (no-op in production
 * builds). Returns true when the mock is active after the call.
 */
export function installMockApi(): boolean {
  if (process.env.NODE_ENV === "production") return false;
  if (typeof window === "undefined" || typeof window.fetch !== "function") return false;
  const w = window as unknown as Record<string, unknown>;
  if (w[INSTALLED_FLAG]) return true;

  const realFetch = window.fetch.bind(window);
  const base = API_BASE.replace(/\/+$/, "");

  window.fetch = ((input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const href = requestUrl(input);
    if (!href.startsWith(base + "/") && href !== base) {
      return realFetch(input, init);
    }
    let url: URL;
    try {
      url = new URL(href);
    } catch {
      return realFetch(input, init);
    }
    const method = requestMethod(input, init);
    const handler = routes[url.pathname];
    if (handler) {
      const result = handler(url, init);
      if (result) {
        return Promise.resolve(jsonResponse(result.body, result.status ?? 200, href));
      }
    }
    for (const [pattern, patternHandler] of patternRoutes) {
      const match = url.pathname.match(pattern);
      if (!match) continue;
      const result = patternHandler(match, url, init);
      if (result) {
        return Promise.resolve(jsonResponse(result.body, result.status ?? 200, href));
      }
    }
    const body = method === "GET" ? fallbackBody(url.pathname) : {};
     
    console.debug(`[dashboard-preview mock] unmatched ${method} ${url.pathname}${url.search} → ${JSON.stringify(body)}`);
    return Promise.resolve(jsonResponse(body, 200, href));
  }) as typeof window.fetch;

  w[INSTALLED_FLAG] = true;
   
  console.debug(`[dashboard-preview mock] intercepting ${base}/* with sample data`);
  return true;
}

/** The pathnames this mock answers explicitly (for docs / the preview pill). */
export const MOCKED_PATHS = Object.keys(routes);
