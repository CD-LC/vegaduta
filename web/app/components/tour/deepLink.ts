// `?tour=field:<data-tour-id>` deep links (Earned Autonomy, 2026-09-26).
//
// Anything in the console can link to "this exact control on that page":
// readiness rows ("add TALLY_API_KEY"), coach cards, and the command palette's
// ask-the-court. TourProvider's FieldSpotlight consumes the parameter, lights
// the element for one ephemeral step, and strips it from the URL.
//
// Pure: no React, no DOM beyond the one injected root - unit-tested in
// deepLink.test.ts. The id grammar is deliberately the SAME one targets.ts
// holds every tour target to, so a value that cannot be a data-tour marker is
// rejected here rather than reaching a querySelector.

import { queryTourTarget } from "./targets";

const PREFIX = "field:";
export const TOUR_PARAM = "tour";
const SAFE_ID = /^[a-zA-Z0-9_-]+$/;

/** The data-tour id a `tour` query value names, or null (absent, other tour
 * mode, or not a usable id). `field:secret-add` -> `secret-add`. */
export function parseFieldParam(value: string | null | undefined): string | null {
  if (typeof value !== "string") return null;
  const v = value.trim();
  if (!v.startsWith(PREFIX)) return null;
  const id = v.slice(PREFIX.length);
  return SAFE_ID.test(id) ? id : null;
}

/** Same, reading a raw `?a=b&tour=field:x` search string. */
export function fieldFromSearch(search: string | null | undefined): string | null {
  if (typeof search !== "string" || search === "") return null;
  try {
    return parseFieldParam(new URLSearchParams(search).get(TOUR_PARAM));
  } catch {
    return null;
  }
}

/** `path?…&tour=field:x#hash` with ONLY the tour parameter removed; every other
 * query key and the hash survive. Returns the input unchanged when the
 * parameter is not a field deep link (a future `tour=court` is not ours). */
export function stripFieldParam(url: string): string {
  const hashAt = url.indexOf("#");
  const hash = hashAt >= 0 ? url.slice(hashAt) : "";
  const noHash = hashAt >= 0 ? url.slice(0, hashAt) : url;
  const qAt = noHash.indexOf("?");
  if (qAt < 0) return url;
  const path = noHash.slice(0, qAt);
  const params = new URLSearchParams(noHash.slice(qAt + 1));
  if (parseFieldParam(params.get(TOUR_PARAM)) === null) return url;
  params.delete(TOUR_PARAM);
  const rest = params.toString();
  return `${path}${rest ? `?${rest}` : ""}${hash}`;
}

/** The element to light, or null if it is not on screen (yet). Resolution is
 * targets.ts', so a fallback selector registered there also serves deep links. */
export function resolveFieldTarget(id: string, root?: Pick<ParentNode, "querySelector"> | null): Element | null {
  return queryTourTarget(id, root);
}

/** `/route?tour=field:<id>`, keeping any query or hash already on the route.
 * The writer's half of parseFieldParam. */
export function fieldHref(route: string, id: string): string {
  const hashAt = route.indexOf("#");
  const hash = hashAt >= 0 ? route.slice(hashAt) : "";
  const base = hashAt >= 0 ? route.slice(0, hashAt) : route;
  return `${base}${base.includes("?") ? "&" : "?"}${TOUR_PARAM}=${PREFIX}${encodeURIComponent(id)}${hash}`;
}
