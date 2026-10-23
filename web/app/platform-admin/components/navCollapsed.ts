import { NAV_SECTIONS } from "./adminNav";

// Which rail groups the operator collapsed, remembered per browser (localStorage).
//
// The stored value is picked from the labels of the constant NAV_SECTIONS, never built from the rail the
// shell renders (that one is filtered by edition and billing). So the persisted text is always a subset of
// fixed, public labels, and a label that is not a rail group is dropped instead of stored.

export const NAV_COLLAPSED_KEY = "vd-admin-nav-collapsed";

const NAV_GROUP_LABELS: readonly string[] = NAV_SECTIONS.flatMap((s) => (s.label ? [s.label] : []));

/**
 * Reads the stored value. Accepts both formats: the current one, a JSON array of collapsed labels, and the
 * first one, an object `{ "<label>": true }`. Throws on text that is not JSON (the caller treats that as
 * "nothing stored").
 */
export function parseCollapsed(raw: string): Record<string, boolean> {
  const parsed: unknown = JSON.parse(raw);
  const out: Record<string, boolean> = {};
  if (Array.isArray(parsed)) {
    for (const label of parsed) if (typeof label === "string") out[label] = true;
  } else if (parsed && typeof parsed === "object") {
    for (const [label, closed] of Object.entries(parsed)) if (closed === true) out[label] = true;
  }
  return out;
}

/** The text to store for this state: the collapsed groups, in rail order, as a JSON array of labels. */
export function serializeCollapsed(state: Record<string, boolean>): string {
  return JSON.stringify(NAV_GROUP_LABELS.filter((label) => state[label] === true));
}
