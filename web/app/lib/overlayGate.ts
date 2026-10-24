// When the first-run overlays (the "Build the agent" welcome modal, the product
// tour, the two-factor catch-up card) may appear, and which layer owns Escape.
//
// Stage E2E finding SE-18: those overlays arrived a few seconds after load on
// whatever the person was doing and swallowed their clicks. The rules here are
// pure functions of the DOM and the pathname so they can be unit tested; the
// TourProvider arming effect asks `overlayHold` before it commits.
//
// Per-user dismissal keys (written by TourProvider, readable by tests that want
// to pre-seed them): `vd_tour_status:<sub>` = "done" | "skipped" and
// `vd_tour_seen_security:<sub>` = "1" (the two-factor catch-up). `<sub>` is the
// Keycloak subject. When the subject is not known up front, set the bare,
// user-independent `vd_tour_off` = "1" instead: it suppresses all three.

export const TOUR_OFF_KEY = "vd_tour_off";

/** Consoles where the person is composing: opening an overlay there steals the work in progress. */
const EDITING_SURFACES: RegExp[] = [/^\/workflows\/[^/]+/, /^\/build\/[^/]+/, /^\/playground(\/|$)/, /^\/agents(\/|$)/];

/** "none": free to arm; "busy": retry shortly; "surface": do not arm on this page load. */
export type OverlayHold = "none" | "busy" | "surface";

export function isEditingSurface(pathname: string): boolean {
  return EDITING_SURFACES.some((re) => re.test(pathname));
}

/** True for a text field, select or contenteditable region (the person is typing or about to). */
export function isEditableElement(el: Element | null | undefined): boolean {
  if (!el) return false;
  const tag = el.tagName;
  if (tag === "TEXTAREA" || tag === "SELECT") return true;
  if (tag === "INPUT") {
    const type = (el as HTMLInputElement).type;
    return !["button", "submit", "reset", "checkbox", "radio", "range", "color", "file", "image"].includes(type);
  }
  return (el as HTMLElement).isContentEditable === true;
}

/**
 * Typing in a field, or an agent turn in flight. A chat surface marks a running
 * turn with `data-turn-in-flight="true"` on any element (aria-busy is not used:
 * skeletons keep it set indefinitely and would hold the overlays back forever).
 */
export function userIsBusy(doc: Document = document): boolean {
  if (isEditableElement(doc.activeElement)) return true;
  return doc.querySelector('[data-turn-in-flight="true"]') !== null;
}

export function tourSuppressed(): boolean {
  try {
    return window.localStorage.getItem(TOUR_OFF_KEY) === "1";
  } catch {
    return false;
  }
}

/** Attribute a conversation surface sets to say whether first-run overlays may cover it (see conversationHold). */
export const ONBOARDING_HOLD_ATTR = "data-onboarding-hold";

/**
 * A chat surface (the Assistant) knows something the tour cannot: whether the person already has conversations.
 * It marks itself `data-onboarding-hold="returning"` when they do, or when a conversation is open on screen -
 * they are not new here, and a modal would land on their work - and "pending" while it is still finding out.
 * Returning: no first-run overlay on this page load. Pending: wait, like a turn in flight.
 */
export function conversationHold(doc: Document = document): OverlayHold {
  const value = doc.querySelector(`[${ONBOARDING_HOLD_ATTR}]`)?.getAttribute(ONBOARDING_HOLD_ATTR);
  if (value === "returning") return "surface";
  if (value === "pending") return "busy";
  return "none";
}

export function overlayHold(pathname: string, doc: Document = document): OverlayHold {
  if (tourSuppressed() || isEditingSurface(pathname)) return "surface";
  const conversation = conversationHold(doc);
  if (conversation !== "none") return conversation;
  return userIsBusy(doc) ? "busy" : "none";
}

/** A modal layer above the tour (the command palette) is open: it owns Escape. */
export function higherEscapeLayerOpen(doc: Document = document): boolean {
  return doc.querySelector('[data-escape-layer="command-palette"]') !== null;
}
