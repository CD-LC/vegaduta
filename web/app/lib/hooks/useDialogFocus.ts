"use client";

import { useEffect, useRef, type RefObject } from "react";

export const FOCUSABLE_SELECTOR =
  'a[href],button:not([disabled]),select:not([disabled]),input:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

export interface DialogFocusOptions {
  /** Whether the dialog is open. Defaults to true (the hook's owner mounts only while open). */
  enabled?: boolean;
  /** Keep Tab / Shift+Tab inside the container. Default true. Turn off for non-modal popovers. */
  trap?: boolean;
  /** Return focus to the element that was focused when the dialog opened. Default true. */
  restoreFocus?: boolean;
  /** Move focus into the dialog on open. Default true. */
  autoFocus?: boolean;
}

/**
 * Modal focus handling: focus moves in on open, Tab / Shift+Tab stay inside the container, Escape calls
 * `onClose` (when one is given), and focus goes back to whatever was focused when the dialog opened.
 * Pair with role="dialog" aria-modal="true" and a backdrop.
 *
 * - `initial`: element to focus on open. When omitted (or its ref is empty) the first focusable element in the
 *   container is focused, falling back to the container itself (give it tabIndex={-1}).
 * - `onClose`: omit / pass null when the component already handles Escape itself, so it doesn't fire twice.
 * - `options.enabled`: for components that stay mounted and toggle an `open` prop; the effect runs on each
 *   false -> true transition and restores focus on true -> false.
 */
export function useDialogFocus(
  container: RefObject<HTMLElement | null>,
  initial?: RefObject<HTMLElement | null> | null,
  onClose?: (() => void) | null,
  options: DialogFocusOptions = {},
): void {
  const { enabled = true, trap = true, restoreFocus = true, autoFocus = true } = options;
  const closeRef = useRef(onClose);
  closeRef.current = onClose;

  useEffect(() => {
    if (!enabled) return undefined;
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    if (autoFocus) {
      const target =
        initial?.current ?? container.current?.querySelector<HTMLElement>(FOCUSABLE_SELECTOR) ?? container.current;
      target?.focus();
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        // An inner widget (combobox, menu) that already consumed Escape keeps the dialog open.
        if (closeRef.current && !e.defaultPrevented) closeRef.current();
        return;
      }
      if (!trap || e.key !== "Tab") return;
      const root = container.current;
      if (!root) return;
      const items = Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR));
      const active = document.activeElement;
      if (items.length === 0) {
        e.preventDefault();
        root.focus();
        return;
      }
      const first = items[0];
      const last = items[items.length - 1];
      if (!(active instanceof Node) || !root.contains(active)) {
        e.preventDefault();
        (e.shiftKey ? last : first).focus();
      } else if (e.shiftKey && (active === first || active === root)) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && active === last) {
        e.preventDefault();
        first.focus();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("keydown", onKey);
      if (!restoreFocus) return;
      try {
        if (opener && opener.isConnected) opener.focus();
      } catch {
        // The opener may be gone; focus simply falls to the page.
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- refs are stable; onClose is read through closeRef
  }, [enabled, trap, restoreFocus, autoFocus]);
}
