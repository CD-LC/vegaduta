"use client";

// Hub-rail flyout state: open section, close grace timer, keyboard disclosure. Lifted out of AppShell.tsx unchanged.

import { useCallback, useEffect, useRef, useState } from "react";


export function useHubFlyout(pathname: string, hubMode: boolean) {
  // Flyout open state: which section + where its glyph is on screen (the
  // panel is a sibling of <nav>, NOT a child - the nav is overflow-x-hidden
  // and transformed, so a fixed-position child would still be clipped to the
  // rail's 64px lane). 140ms close grace bridges the pointer's travel across
  // the 4px gap between rail edge and panel.
  const [flyout, setFlyout] = useState<{ key: string; top: number } | null>(null);
  const flyoutCloseTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Which glyph opened the current flyout - Escape returns focus to it, and
  // the ArrowRight/Enter disclosure below uses it to hand focus into the
  // panel (a11y/controls audit follow-up, see the glyph button's onKeyDown).
  const lastGlyphRef = useRef<HTMLElement | null>(null);
  const flyoutPanelRef = useRef<HTMLDivElement | null>(null);
  const cancelFlyoutClose = useCallback(() => {
    if (flyoutCloseTimer.current) {
      clearTimeout(flyoutCloseTimer.current);
      flyoutCloseTimer.current = null;
    }
  }, []);
  const scheduleFlyoutClose = useCallback(() => {
    cancelFlyoutClose();
    flyoutCloseTimer.current = setTimeout(() => setFlyout(null), 140);
  }, [cancelFlyoutClose]);
  const openFlyout = useCallback(
    (key: string, el: HTMLElement) => {
      cancelFlyoutClose();
      lastGlyphRef.current = el;
      const rect = el.getBoundingClientRect();
      // Keep the panel on-screen for tall groups near the viewport bottom:
      // estimate the panel height (header + ~36px rows) and clamp.
      setFlyout({ key, top: Math.max(8, Math.min(rect.top, window.innerHeight - 340)) });
    },
    [cancelFlyoutClose]
  );
  // Disclosure control (a11y/controls audit follow-up): the panel is a DOM
  // sibling of the whole rail, rendered well after every glyph in source
  // order, so plain Tab from a glyph never actually reaches its own panel -
  // it lands on the NEXT glyph first, whose blur then closes this one. On
  // Enter/ArrowRight, open the flyout (if needed) and jump focus straight
  // into its first link instead of relying on Tab order.
  const openFlyoutAndFocusFirst = useCallback(
    (key: string, el: HTMLElement) => {
      openFlyout(key, el);
      requestAnimationFrame(() => {
        flyoutPanelRef.current?.querySelector<HTMLElement>("a[href]")?.focus();
      });
    },
    [openFlyout]
  );
  // Route change or a collapsed-window resize closes any open flyout; Escape too.
  useEffect(() => {
    setFlyout(null);
  }, [pathname]);
  useEffect(() => {
    if (!flyout) return undefined;
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") {
        setFlyout(null);
        lastGlyphRef.current?.focus();
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [flyout]);
  useEffect(() => {
    if (!hubMode) setFlyout(null);
  }, [hubMode]);
  return { flyout, setFlyout, flyoutPanelRef, cancelFlyoutClose, scheduleFlyoutClose, openFlyout, openFlyoutAndFocusFirst };
}
