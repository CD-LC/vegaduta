"use client";

import { useEffect, type RefObject } from "react";

const FOCUSABLE =
  'a[href],button:not([disabled]),input:not([disabled]):not([type="hidden"]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

/** Modal focus handling: on mount focus the first control inside `ref`, keep
 * Tab / Shift+Tab cycling within it, close on Escape, and on unmount return
 * focus to whatever had it before the dialog opened. */
export function useFocusTrap(ref: RefObject<HTMLElement | null>, onEscape?: () => void): void {
  useEffect(() => {
    const box = ref.current;
    const before = typeof document !== "undefined" ? (document.activeElement as HTMLElement | null) : null;
    if (box) {
      const first = box.querySelector<HTMLElement>("[autofocus],[data-autofocus]") || box.querySelector<HTMLElement>(FOCUSABLE);
      (first || box).focus?.();
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && onEscape) {
        e.stopPropagation();
        onEscape();
        return;
      }
      if (e.key !== "Tab" || !box) return;
      const items = Array.from(box.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((el) => el.offsetParent !== null || el === document.activeElement);
      if (items.length === 0) {
        e.preventDefault();
        return;
      }
      const first = items[0];
      const last = items[items.length - 1];
      const active = document.activeElement as HTMLElement | null;
      if (e.shiftKey && (active === first || !box.contains(active))) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && (active === last || !box.contains(active))) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKey, true);
    return () => {
      document.removeEventListener("keydown", onKey, true);
      if (before && document.contains(before)) before.focus?.();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
}
