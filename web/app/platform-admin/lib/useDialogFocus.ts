"use client";

import { useEffect, useRef, type RefObject } from "react";

// Shared focus-management helper for this app's hand-rolled portaled dialogs
// (DeleteTenantDialog, TemplateReasonDialog, MasterKeyPrompt, ...) - none of
// them moved focus in, trapped Tab, or restored focus on close, despite all
// marking themselves aria-modal. This is the one small piece every one of
// them needs, applied consistently instead of re-solved per component:
//   - on open: remember whatever had focus, then focus the dialog's first
//     focusable element (or the dialog container itself as a fallback)
//   - while open: Tab/Shift+Tab wrap within the dialog instead of escaping
//     into the page behind it
//   - on close: restore focus to whatever opened the dialog
const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

export function useDialogFocus(open: boolean, containerRef: RefObject<HTMLElement | null>) {
  const openerRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!open) return undefined;
    openerRef.current = (document.activeElement as HTMLElement) || null;

    const container = containerRef.current;
    const focusables = () =>
      container ? Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)) : [];

    // Deferred a frame so the dialog's own enter animation/mount has landed
    // in the DOM before we try to move focus into it.
    const raf = requestAnimationFrame(() => {
      const first = focusables()[0];
      (first || container)?.focus();
    });

    function onKeyDown(e: KeyboardEvent) {
      if (e.key !== "Tab" || !container) return;
      const items = focusables();
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    }
    document.addEventListener("keydown", onKeyDown);

    return () => {
      cancelAnimationFrame(raf);
      document.removeEventListener("keydown", onKeyDown);
      openerRef.current?.focus?.();
    };
  }, [open, containerRef]);
}
