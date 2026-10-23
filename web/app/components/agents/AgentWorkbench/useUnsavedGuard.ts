"use client";

import { useCallback, useEffect, useRef, useState, type RefObject } from "react";
import { useRouter } from "next/navigation";

/**
 * Whether any settings field inside `ref` holds an unsaved edit. The settings panel marks a changed field with
 * data-dirty, so this watches those marks instead of owning the draft state; it reports every change once.
 */
export function useDirtyWatch(ref: RefObject<HTMLElement | null>, onChange?: (dirty: boolean) => void): boolean {
  const [dirty, setDirty] = useState(false);
  const cb = useRef(onChange);
  cb.current = onChange;
  useEffect(() => {
    const root = ref.current;
    if (!root || typeof MutationObserver === "undefined") return undefined;
    let last = false;
    const check = () => {
      const now = root.querySelector('[data-dirty="true"]') !== null;
      if (now !== last) {
        last = now;
        setDirty(now);
        cb.current?.(now);
      }
    };
    check();
    const observer = new MutationObserver(check);
    observer.observe(root, { subtree: true, childList: true, attributes: true, attributeFilter: ["data-dirty"] });
    return () => {
      observer.disconnect();
      // The tab went away (another agent, or the page): nothing is pending any more.
      if (last) cb.current?.(false);
    };
  }, [ref]);
  return dirty;
}

/**
 * While `dirty`, a click on an in-app link is held back so the person can confirm leaving. The browser's own
 * prompt for closing the tab is the settings panel's job (beforeunload). Returns the held link, if any.
 */
export function useLinkGuard(dirty: boolean): { pendingHref: string | null; stay: () => void; leave: () => void } {
  const router = useRouter();
  const [pendingHref, setPendingHref] = useState<string | null>(null);
  useEffect(() => {
    if (!dirty) {
      setPendingHref(null);
      return undefined;
    }
    const onClick = (e: MouseEvent) => {
      if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      const anchor = (e.target as Element | null)?.closest?.("a[href]") as HTMLAnchorElement | null;
      if (!anchor || anchor.target === "_blank" || anchor.hasAttribute("download")) return;
      const href = anchor.getAttribute("href") ?? "";
      if (!href.startsWith("/") || href.startsWith("//")) return; // in-app navigation only
      e.preventDefault();
      e.stopPropagation();
      setPendingHref(href);
    };
    document.addEventListener("click", onClick, true);
    return () => document.removeEventListener("click", onClick, true);
  }, [dirty]);
  const stay = useCallback(() => setPendingHref(null), []);
  const leave = useCallback(() => {
    const href = pendingHref;
    setPendingHref(null);
    if (href) router.push(href);
  }, [pendingHref, router]);
  return { pendingHref, stay, leave };
}
