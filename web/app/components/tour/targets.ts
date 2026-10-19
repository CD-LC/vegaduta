// Where a tour step's spotlight lands - and what it falls back to when the
// element it was written for carries no data-tour marker (2026-09-19).
//
// Until now a step's `target` resolved through exactly one query,
// `[data-tour="<target>"]`, and the only elements carrying that attribute are
// the eight nav rows AppShell's NAV_TOUR_TARGETS names. That made every new
// step a two-file change, and the second file (AppShell.tsx) belongs to a
// different owner. The Edge AI step is the first to need it: the /edge nav row
// has existed since 2026-08-05 but was never given a marker, so the first-login
// tour could not point at "where on-device lives" at all - and rule 4 in
// ./audience.ts would have DELETED the step for every visitor, silently,
// because its anchor "was not on screen".
//
// The fallback is a plain CSS selector for an element that already exists,
// scoped under the nav rail so a body link to the same route can never win.
// The data-tour marker still comes FIRST in the selector list: the day AppShell
// grows `"/edge": "nav-edge"` the fallback stops mattering and can be deleted
// without touching a step.
//
// Pure apart from the one querySelector, and that is try/caught: a selector
// that a browser rejects must degrade to "no anchor" (the overlay then centres
// the card over a scrim), never to an exception inside a mount effect of a
// component mounted at the root layout.

/** Targets that may resolve through an existing element when no data-tour
 * marker is present. Keep this SHORT - every entry is a marker somebody should
 * eventually add, and is listed as such in the step's own comment. */
export const TARGET_FALLBACK_SELECTORS: Readonly<Record<string, string>> = {
  // AppShell renders every nav row as <Link href={item.href}> inside the
  // <nav data-tour="nav-rail">; "/edge" is NAV_SECTIONS' Edge AI row.
  "nav-edge": '[data-tour="nav-rail"] a[href="/edge"]',
};

/** A target is an identifier we interpolate into a selector, so it is held to
 * identifier characters. Anything else is treated as "no target" rather than
 * escaped: no step has ever needed more, and a quote or bracket arriving here
 * would be a bug in the step table, not something to make work. */
const SAFE_TARGET = /^[a-zA-Z0-9_-]+$/;

/** The selector list for a target, marker first, or null for an unusable one. */
export function tourTargetSelector(target: string | null | undefined): string | null {
  if (typeof target !== "string") return null;
  const trimmed = target.trim();
  if (!SAFE_TARGET.test(trimmed)) return null;
  const marker = `[data-tour="${trimmed}"]`;
  const fallback = TARGET_FALLBACK_SELECTORS[trimmed];
  return fallback ? `${marker}, ${fallback}` : marker;
}

/**
 * The element a step points at, or null. The marker wins over the fallback
 * even when the fallback comes earlier in document order - a selector LIST
 * returns the first match in the DOM, not the first selector that matched, so
 * the two are queried separately.
 */
export function queryTourTarget(
  target: string | null | undefined,
  root?: Pick<ParentNode, "querySelector"> | null,
): Element | null {
  const scope = root ?? (typeof document === "undefined" ? null : document);
  if (!scope) return null;
  if (typeof target !== "string" || !SAFE_TARGET.test(target.trim())) return null;
  const trimmed = target.trim();
  try {
    const marked = scope.querySelector(`[data-tour="${trimmed}"]`);
    if (marked) return marked;
    const fallback = TARGET_FALLBACK_SELECTORS[trimmed];
    return fallback ? scope.querySelector(fallback) : null;
  } catch {
    return null;
  }
}
