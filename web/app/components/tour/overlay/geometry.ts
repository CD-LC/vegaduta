import type { TourStep } from "../types";

export const CARD_W = 360;
export const MARGIN = 12; // min gap between the card and the viewport edge
export const GAP = 16; // gap between the spotlight hole and the card
export const HOLE_PAD = 8; // how far the cut-out extends past the target's box
// Must stay equal to the `lg` breakpoint the nav rail itself uses. It was 768
// (Tailwind `md`), which left the 768-1023px band with no rail on screen AND
// no docking: the card fell through to the centred translate(-50%,-50%)
// branch, un-anchored and (before the fallback scrim below) un-dimmed. The
// rail is `-translate-x-full lg:translate-x-0` in AppShell, so 1024 is the
// only value that makes "is the rail on screen?" and "is the card docked?"
// the same question.
export const DOCK_BREAKPOINT = 1024; // below this the card becomes a bottom sheet
/** At or below this many selected steps the welcome hero promises the short
 * walk (`durationShort`) instead of the full one. Not an audience test: a
 * SIMPLE-mode member's tour is short for a different reason than a
 * platform-admin's, and both deserve the honest number. */
export const SHORT_TOUR_STEPS = 5;

/** Matches LanguageChoicePrompt/UpgradePrompt - the house focus-trap selector. */
export const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

export type Rect = { top: number; left: number; width: number; height: number };
export type Side = "top" | "bottom" | "left" | "right";

export function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), Math.max(min, max));
}

/**
 * A target only counts when it has real, on-screen geometry. Below the `lg`
 * breakpoint the whole nav rail is `-translate-x-full` (present in the DOM,
 * parked off the left edge), and a collapsed rail can hand back a zero-size
 * box for a label. Returning null for those cases routes the step into the
 * centred no-spotlight fallback (card + plain scrim, see the overlay below)
 * instead of pointing the light off-screen.
 *
 * Known gap, deliberately not papered over here: this rejects zero-size and
 * off-screen rects, NOT `opacity: 0`. The rail's section labels are
 * `lg:opacity-0` on collapsible-rail pages, so a target parked on one of those
 * still measures as a perfectly good rect. The fix belongs on the attribute
 * (AppShell), not in this predicate - an opacity walk up the ancestor chain on
 * every scroll frame is not worth it.
 */
export function visibleRect(el: Element): Rect | null {
  const r = el.getBoundingClientRect();
  if (r.width <= 0 || r.height <= 0) return null;
  if (r.right <= 0 || r.bottom <= 0 || r.left >= window.innerWidth || r.top >= window.innerHeight) {
    return null;
  }
  return { top: r.top, left: r.left, width: r.width, height: r.height };
}

/**
 * The spotlit element's own visible text, used as the step's textual referent
 * (see `TourTarget.label`). Read off the live DOM rather than minted as tour
 * copy on purpose: the nav row is already translated by the app, so this
 * cannot drift out of sync with what is on screen the way a duplicated
 * en.json string would.
 */
export function accessibleText(el: Element): string {
  const aria = el.getAttribute("aria-label");
  if (aria && aria.trim()) return aria.trim();
  const raw = (el as HTMLElement).innerText ?? el.textContent ?? "";
  return raw.replace(/\s+/g, " ").trim().slice(0, 80);
}

/* ------------------------------------------------------------------ */
/* Placement                                                           */
/* ------------------------------------------------------------------ */

export const FLIP_ORDER: Record<Side, Side[]> = {
  right: ["right", "left", "bottom", "top"],
  left: ["left", "right", "bottom", "top"],
  bottom: ["bottom", "top", "right", "left"],
  top: ["top", "bottom", "right", "left"],
};

export function positionForSide(side: Side, hole: Rect, cardW: number, cardH: number, vw: number, vh: number) {
  if (side === "right" || side === "left") {
    const left = side === "right" ? hole.left + hole.width + GAP : hole.left - GAP - cardW;
    const top = clamp(hole.top + hole.height / 2 - cardH / 2, MARGIN, vh - cardH - MARGIN);
    return { left, top };
  }
  const top = side === "bottom" ? hole.top + hole.height + GAP : hole.top - GAP - cardH;
  const left = clamp(hole.left, MARGIN, vw - cardW - MARGIN);
  return { left, top };
}

export function sideFits(side: Side, hole: Rect, cardW: number, cardH: number, vw: number, vh: number) {
  switch (side) {
    case "right":
      return hole.left + hole.width + GAP + cardW <= vw - MARGIN;
    case "left":
      return hole.left - GAP - cardW >= MARGIN;
    case "bottom":
      return hole.top + hole.height + GAP + cardH <= vh - MARGIN;
    case "top":
      return hole.top - GAP - cardH >= MARGIN;
  }
}

export function overlaps(a: { left: number; top: number; w: number; h: number }, hole: Rect) {
  return (
    a.left < hole.left + hole.width &&
    a.left + a.w > hole.left &&
    a.top < hole.top + hole.height &&
    a.top + a.h > hole.top
  );
}

/**
 * Card position for a spotlit step, with collision handling: try the step's
 * preferred side, then its flip, then the perpendicular pair; clamp to a 12px
 * viewport margin. If nothing fits (a tall card against a short viewport) the
 * card is parked in the viewport half the target is NOT in, so it never
 * covers its own cut-out - a card sitting on top of the thing it is
 * describing is worse than a card that is further away than ideal.
 */
export function computePosition(
  hole: Rect,
  preferred: TourStep["placement"],
  cardW: number,
  cardH: number,
  vw: number,
  vh: number
) {
  const side: Side = preferred === "center" ? "bottom" : preferred;
  for (const candidate of FLIP_ORDER[side]) {
    if (!sideFits(candidate, hole, cardW, cardH, vw, vh)) continue;
    const pos = positionForSide(candidate, hole, cardW, cardH, vw, vh);
    if (!overlaps({ ...pos, w: cardW, h: cardH }, hole)) return pos;
  }
  const targetInTopHalf = hole.top + hole.height / 2 < vh / 2;
  return {
    left: clamp(hole.left, MARGIN, vw - cardW - MARGIN),
    top: targetInTopHalf ? Math.max(MARGIN, vh - cardH - MARGIN) : MARGIN,
  };
}

