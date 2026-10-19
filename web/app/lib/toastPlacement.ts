// Where the toast stack may sit (prod signed-in pass 2026-10-05).
//
// The stack was pinned to the top-right corner, which is exactly where every
// page keeps its primary actions: the app header's bell, and on the workflow
// canvas the Activate / Run now / Share row. Error toasts stay until
// dismissed, so a click meant for "Activate" landed on a toast instead.
//
// Toolbars opt out by carrying TOAST_AVOID_ATTR. The stack is then placed
// just below the lowest such bar that it would otherwise overlap, so a toast
// can never sit on top of one. Pure geometry here; ToastHost does the DOM.

export const TOAST_AVOID_ATTR = "data-toast-avoid";

/** The stack's resting offset from the top/right edge (1.1rem). */
export const TOAST_EDGE_PX = 18;
/** Breathing room between a toolbar's bottom edge and the first toast. */
export const TOAST_TOOLBAR_GAP_PX = 10;

export interface RectLike {
  top: number;
  bottom: number;
  left: number;
  right: number;
}

/**
 * Top offset (px) for the toast stack.
 *
 * `avoid` are the viewport rects of every on-screen element marked
 * TOAST_AVOID_ATTR. Only bars that are (a) in the top part of the viewport and
 * (b) horizontally under the stack's column count - a bar scrolled away, or
 * one that ends left of the stack, pushes nothing. The result is capped at
 * half the viewport so a tall wrapped toolbar on a phone cannot push toasts
 * off-screen.
 */
export function toastTopOffset(
  avoid: RectLike[],
  viewport: { width: number; height: number },
  stackWidth: number
): number {
  const columnLeft = viewport.width - TOAST_EDGE_PX - stackWidth;
  let top = TOAST_EDGE_PX;
  for (const r of avoid) {
    if (r.bottom <= 0 || r.top >= viewport.height * 0.4) continue; // off-screen or not a top bar
    if (r.right <= columnLeft || r.left >= viewport.width) continue; // not under the stack
    if (r.bottom - r.top <= 0) continue; // collapsed / display:none
    top = Math.max(top, r.bottom + TOAST_TOOLBAR_GAP_PX);
  }
  return Math.round(Math.min(top, Math.max(TOAST_EDGE_PX, viewport.height * 0.5)));
}
