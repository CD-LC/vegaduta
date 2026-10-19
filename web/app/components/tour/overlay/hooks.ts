import { useEffect, useMemo, useRef, useState } from "react";
import { FOCUSABLE_SELECTOR, visibleRect, accessibleText, type Rect } from "./geometry";
import { queryTourTarget } from "../targets";

export type TourTarget = { rect: Rect | null; label: string };

/**
 * Finds a step's target element, keeps its rect current, and reports the
 * element's own text so the dialog can name what the light is on.
 *
 * Retry-poll is load-bearing (kept from v1): a step whose target lives on a
 * route the user just navigated to may not have mounted yet - route
 * transitions plus data fetches beat this effect on a cold nav. On top of
 * that this version scrolls the target into view before measuring, keeps
 * re-measuring while a smooth scroll is still travelling, and re-measures on
 * scroll/resize and whenever the target itself resizes (the /tools row grows
 * an MCP attention badge, the rail expands on hover).
 */
export function useTourTarget(selector: string | null, reduced: boolean): TourTarget {
  const [rect, setRect] = useState<Rect | null>(null);
  const [label, setLabel] = useState("");

  useEffect(() => {
    setRect(null);
    setLabel("");
    if (!selector) return undefined;

    let cancelled = false;
    let raf = 0;
    let pollTimer = 0;
    let settleTimer = 0;
    let observer: ResizeObserver | null = null;
    let el: Element | null = null;
    let attempts = 0;
    const MAX_ATTEMPTS = 40; // ~6s at 150ms

    const measure = () => {
      if (cancelled || !el) return;
      setRect(visibleRect(el));
    };

    const onFound = (found: Element) => {
      el = found;
      setLabel(accessibleText(found));
      try {
        found.scrollIntoView({
          block: "center",
          inline: "nearest",
          behavior: reduced ? "auto" : "smooth",
        });
      } catch {
        // jsdom (and very old browsers) may not implement scrollIntoView, or
        // may reject the options object - measuring in place is fine there.
      }
      measure();
      // A smooth scroll keeps travelling for a few hundred ms after the call
      // returns; the rect is only final when it stops. Scroll events cover
      // most of it, but a scroll that no-ops (target already in view) fires
      // nothing at all, so poll briefly rather than trusting the event.
      const settleUntil = Date.now() + 800;
      const settle = () => {
        if (cancelled) return;
        measure();
        if (Date.now() < settleUntil) settleTimer = window.setTimeout(settle, 60);
      };
      settle();
      if (typeof ResizeObserver !== "undefined") {
        observer = new ResizeObserver(() => measure());
        observer.observe(found);
      }
    };

    const poll = () => {
      if (cancelled) return;
      // queryTourTarget (2026-09-19): marker first, then the target's fallback
      // selector if it has one (./targets.ts). Never throws, so a selector a
      // browser rejects ends as "not found" -> centred card, not an exception
      // inside this effect.
      const found = queryTourTarget(selector);
      if (found) {
        onFound(found);
        return;
      }
      attempts += 1;
      if (attempts >= MAX_ATTEMPTS) return;
      pollTimer = window.setTimeout(poll, 150);
    };
    poll();

    const onScrollOrResize = () => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(measure);
    };
    window.addEventListener("scroll", onScrollOrResize, true);
    window.addEventListener("resize", onScrollOrResize);

    return () => {
      cancelled = true;
      window.removeEventListener("scroll", onScrollOrResize, true);
      window.removeEventListener("resize", onScrollOrResize);
      cancelAnimationFrame(raf);
      window.clearTimeout(pollTimer);
      window.clearTimeout(settleTimer);
      observer?.disconnect();
    };
  }, [selector, reduced]);

  return useMemo(() => ({ rect, label }), [rect, label]);
}

/** Live viewport size; 0x0 until the client measures it (SSR renders nothing). */
export function useViewport() {
  const [size, setSize] = useState(() =>
    typeof window === "undefined" ? { w: 0, h: 0 } : { w: window.innerWidth, h: window.innerHeight }
  );
  useEffect(() => {
    const onResize = () => setSize({ w: window.innerWidth, h: window.innerHeight });
    onResize();
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);
  return size;
}

/* ------------------------------------------------------------------ */
/* Focus handling                                                      */
/* ------------------------------------------------------------------ */

/**
 * Move focus in, trap Tab, hand focus back on close - the pattern
 * LanguageChoicePrompt/UpgradePrompt/UpgradeCelebration already use, with a
 * `refocusKey` so focus also moves on every step change, not just on open.
 */
export function useDialogFocus(open: boolean, ref: React.RefObject<HTMLDivElement | null>, refocusKey: string | number) {
  const previousFocusRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!open) return undefined;
    previousFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    return () => {
      const prev = previousFocusRef.current;
      if (prev && typeof prev.focus === "function") prev.focus();
    };
  }, [open]);

  useEffect(() => {
    if (!open) return;
    // preventScroll: the step's own scrollIntoView owns scroll position here.
    ref.current?.focus({ preventScroll: true });
  }, [open, refocusKey, ref]);

  useEffect(() => {
    if (!open) return undefined;
    function onKey(e: KeyboardEvent) {
      if (e.key !== "Tab") return;
      const node = ref.current;
      if (!node) return;
      const focusable = node.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR);
      if (focusable.length === 0) {
        e.preventDefault();
        node.focus({ preventScroll: true });
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;
      if (!node.contains(active)) {
        e.preventDefault();
        (e.shiftKey ? last : first).focus();
        return;
      }
      if (e.shiftKey && active === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && active === last) {
        e.preventDefault();
        first.focus();
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, ref]);
}

