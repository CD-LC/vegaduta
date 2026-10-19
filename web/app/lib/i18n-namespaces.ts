// Route-aware pruning of the translation catalog (prod latency pass,
// 2026-09-24). Pure functions, safe on the server and in tests.
//
// The root layout used to hand I18nProvider the ENTIRE locale bundle on every
// request - 480 KB (en) to 1.4 MB (ta) of JSON serialized into each page's
// HTML. A page can only reach the namespaces in its static import closure, so
// scripts/i18n-route-namespaces.mjs computes that closure per route and the
// layout ships just those. Whatever a route might still need at runtime (a
// client-side navigation to another route, a namespace this analysis cannot
// see) is filled in by I18nProvider's lazy completion fetch of the full
// bundle from /api/i18n/<locale> - so a wrong prediction here degrades to a
// brief key-name flash, never a permanent gap.
import { LAYOUT_NAMESPACES, ROUTE_NAMESPACES } from "./i18n-route-namespaces.generated";

export type MessageBundle = Record<string, unknown>;

// Cookie AuthProvider sets once a Keycloak session has been seen on this
// browser (the localStorage "vd_authed" hint, mirrored so the server can read
// it). It is a hint only - the server never trusts it for anything but which
// namespaces to inline.
export const AUTHED_HINT_COOKIE = "vd_authed";

// The anonymous marketing landing (GenesisExperience) is rendered by
// AuthProvider on "/" for any visitor without a session - and during SSR on
// "/" regardless, since auth is client-side. It is by far the largest
// namespace (115 KB of the 448 KB English catalog), so a signed-in visitor on
// any other route must not pay for it.
// 2026-10-11 landing speed pass: the landing's nav also mounts the language
// switcher and the theme picker. Their namespaces were never in the "/"
// route map (app/page.tsx's own closure is the dashboard shim), so the very
// first t() on the landing missed and I18nProvider fetched the FULL catalog
// (228 KB) on every anonymous visit - the pruning bought nothing on the one
// route it mattered most. They ride with "landing" now.
const LANDING_ONLY_NAMESPACES = new Set(["landing", "languageSwitcher", "themePicker"]);

const routeMatchers: Array<{ route: string; segments: string[] }> = Object.keys(ROUTE_NAMESPACES).map((route) => ({
  route,
  segments: route === "/" ? [] : route.slice(1).split("/"),
}));

/** Map a request pathname ("/workflows/abc/runs") to its app route ("/workflows/[id]/runs"), or null. */
export function matchRoute(pathname: string | null | undefined): string | null {
  if (!pathname) return null;
  const parts = pathname.replace(/\/+$/, "").split("/").filter(Boolean);
  let best: string | null = null;
  let bestScore = -1;
  for (const { route, segments } of routeMatchers) {
    if (segments.length !== parts.length) continue;
    let score = 0;
    let ok = true;
    for (let i = 0; i < segments.length; i++) {
      const s = segments[i];
      if (s === parts[i]) score += 2;
      else if (s.startsWith("[") && s.endsWith("]")) score += 1;
      else {
        ok = false;
        break;
      }
    }
    if (ok && score > bestScore) {
      best = route;
      bestScore = score;
    }
  }
  return best;
}

/**
 * Which namespaces to inline for this request. `null` means "ship the full
 * bundle" - the safe answer for a pathname this analysis does not know.
 */
export function namespacesForRequest(pathname: string | null | undefined, authedHint: boolean): Set<string> | null {
  const route = matchRoute(pathname);
  if (!route) return null;
  const routeNames = ROUTE_NAMESPACES[route] || [];
  const names = new Set<string>([...LAYOUT_NAMESPACES, ...routeNames]);
  if (authedHint && route !== "/") {
    // A signed-in visitor off the root never sees the landing: drop its
    // namespaces unless the route's own closure needs one of them.
    for (const ns of LANDING_ONLY_NAMESPACES) if (!routeNames.includes(ns)) names.delete(ns);
  } else {
    for (const ns of LANDING_ONLY_NAMESPACES) names.add(ns);
  }
  return names;
}

/** Keep only the listed top-level namespaces of a bundle (non-namespace scalars are kept). */
export function pruneBundle(bundle: MessageBundle, names: Set<string> | null): MessageBundle {
  if (!names) return bundle;
  const out: MessageBundle = {};
  for (const [k, v] of Object.entries(bundle)) {
    if (names.has(k) || typeof v !== "object" || v === null) out[k] = v;
  }
  return out;
}

/** Parse the authed hint out of a raw Cookie header value. */
export function hasAuthedHint(cookieValue: string | null | undefined): boolean {
  return cookieValue === "1";
}
