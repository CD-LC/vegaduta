// One Cmd/Ctrl+K, one palette (prod signed-in pass 2026-10-05, bug 6).
//
// AppShell's global "Jump to..." palette and the workflow canvas's "Add a
// node" palette each registered their own bubble-phase window keydown
// listener for the same chord. On a canvas route both fired, both palettes
// opened, Escape closed only the one holding focus, and the next click landed
// on the node list that was still open and added a node.
//
// Rule: a surface that owns the chord CLAIMS it in the capture phase and
// stops the event there; the global listener runs in the bubble phase and
// additionally ignores any event somebody already handled. The most specific
// surface on screen wins, and exactly one palette opens.

type KeyLike = Pick<KeyboardEvent, "key" | "metaKey" | "ctrlKey">;

export function isPaletteShortcut(e: KeyLike): boolean {
  return (e.metaKey || e.ctrlKey) && typeof e.key === "string" && e.key.toLowerCase() === "k";
}

/**
 * Take over Cmd/Ctrl+K for a surface (e.g. the workflow canvas). While the
 * claim is registered, the chord runs `onTrigger` and never reaches the
 * global palette. `enabled` lets the surface stand down without unmounting
 * (a read-only canvas has no node palette, so the global one should open).
 * Returns the release function.
 */
export function claimPaletteShortcut(onTrigger: () => void, enabled?: () => boolean): () => void {
  if (typeof window === "undefined") return () => {};
  const onKeyDown = (e: KeyboardEvent) => {
    if (!isPaletteShortcut(e)) return;
    if (enabled && !enabled()) return;
    e.preventDefault();
    e.stopPropagation();
    onTrigger();
  };
  window.addEventListener("keydown", onKeyDown, true);
  return () => window.removeEventListener("keydown", onKeyDown, true);
}

/**
 * The app-wide Cmd/Ctrl+K listener. Yields to any claim above (the event
 * never arrives) and to anything else that already handled the keystroke.
 * Returns the release function.
 */
export function listenGlobalPaletteShortcut(onTrigger: () => void): () => void {
  if (typeof window === "undefined") return () => {};
  const onKeyDown = (e: KeyboardEvent) => {
    if (!isPaletteShortcut(e) || e.defaultPrevented) return;
    e.preventDefault();
    onTrigger();
  };
  window.addEventListener("keydown", onKeyDown);
  return () => window.removeEventListener("keydown", onKeyDown);
}
