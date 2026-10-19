import { isEditableTarget } from "../canvasHelpers";

export type CanvasShortcutActions = {
  undo: () => void;
  redo: () => void;
  copySelected: () => void;
  pasteCopied: () => void;
  duplicateSelected: () => void;
  tidyLayout: () => void;
  fitToScreen: () => void;
};

/** Pure key dispatcher (testable). Returns true when it handled the event. */
export function handleCanvasKey(e: KeyboardEvent, a: CanvasShortcutActions, observing: boolean): boolean {
  if (e.defaultPrevented || isEditableTarget(e.target)) return false;
  // Observe mode is read-only: only fit-to-screen still applies.
  if (observing && !(e.shiftKey && e.code === "Digit1")) return false;
  // Modals (shortcuts sheet, palette, drawer) must not edit the graph behind them.
  if (document.querySelector('[role="dialog"][aria-modal="true"], [aria-modal="true"]')) return false;
  if (e.target instanceof Element && e.target.closest('[role="dialog"]')) return false;
  const key = e.key.toLowerCase();
  if (e.ctrlKey || e.metaKey) {
    if (key === "z" || key === "y") {
      e.preventDefault();
      if (key === "z" && !e.shiftKey) a.undo();
      else a.redo();
      return true;
    }
    if (e.altKey || e.shiftKey) return false;
    if (key === "c") {
      // Leave native copy alone when the user has highlighted text.
      if (window.getSelection()?.toString()) return false;
      a.copySelected();
      return true;
    }
    if (key === "v") {
      a.pasteCopied();
      return true;
    }
    if (key === "d") {
      e.preventDefault(); // otherwise the browser bookmarks the page
      a.duplicateSelected();
      return true;
    }
    return false;
  }
  if (e.altKey || !e.shiftKey) return false;
  if (key === "l") {
    e.preventDefault();
    a.tidyLayout();
    return true;
  }
  if (e.code === "Digit1") {
    e.preventDefault();
    a.fitToScreen();
    return true;
  }
  return false;
}
