// Artifacts pane - width bookkeeping for the resizable side panel (md+ only;
// below md the pane is a full-screen sheet and width does not apply).

export const PANE_MIN_WIDTH = 320;
export const PANE_MAX_WIDTH = 960;
export const PANE_DEFAULT_WIDTH = 520;
/** Never let the pane squeeze the chat narrower than this (px). */
export const CHAT_MIN_WIDTH = 360;
export const PANE_KEY_STEP = 24;

/** Clamp a requested pane width to [min, min(max, viewport - chat minimum)]. Non-finite input falls back to the default. */
export function clampPaneWidth(requested: number, viewportWidth: number): number {
  const want = Number.isFinite(requested) ? requested : PANE_DEFAULT_WIDTH;
  const vp = Number.isFinite(viewportWidth) && viewportWidth > 0 ? viewportWidth : PANE_MAX_WIDTH + CHAT_MIN_WIDTH;
  const ceiling = Math.max(PANE_MIN_WIDTH, Math.min(PANE_MAX_WIDTH, vp - CHAT_MIN_WIDTH));
  return Math.round(Math.min(ceiling, Math.max(PANE_MIN_WIDTH, want)));
}

/** New width after dragging the left-edge handle from startX by clientX (dragging LEFT widens the right-hand pane). */
export function widthAfterDrag(startWidth: number, startX: number, clientX: number, viewportWidth: number): number {
  return clampPaneWidth(startWidth + (startX - clientX), viewportWidth);
}

/** Keyboard resize on the separator: ArrowLeft widens, ArrowRight narrows, Home/End jump to the limits. Null = key not handled. */
export function widthAfterKey(key: string, width: number, viewportWidth: number): number | null {
  switch (key) {
    case "ArrowLeft":
      return clampPaneWidth(width + PANE_KEY_STEP, viewportWidth);
    case "ArrowRight":
      return clampPaneWidth(width - PANE_KEY_STEP, viewportWidth);
    case "Home":
      return clampPaneWidth(PANE_MIN_WIDTH, viewportWidth);
    case "End":
      return clampPaneWidth(PANE_MAX_WIDTH, viewportWidth);
    default:
      return null;
  }
}
