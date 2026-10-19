"use client";

import { useEffect, useRef, useState } from "react";
import { usePathname } from "next/navigation";
import { apiActivity } from "../lib/api/core";

// Global route-change feedback — the awwwards/Codrops touch, done the
// performant way: a single glowing gradient progress bar that sweeps on every
// in-app navigation. Everything animates via CSS transform/opacity
// (GPU-composited) — no layout thrash, negligible cost.
//
// No router-events API exists in the App Router, so we START on same-origin
// link clicks + back/forward, and FINISH when `usePathname` actually changes.
//
// The full-viewport LOADING SPINNER that used to live here was moved to
// RouteTransition.tsx, which covers the page opaquely and — unlike this bar,
// which finishes the instant the route mounts — holds until the page's data
// has actually loaded. This component is now purely the thin top-edge cue that
// runs on top of that cover.
export default function NavigationProgress() {
  const pathname = usePathname();
  const [visible, setVisible] = useState(false);
  const [progress, setProgress] = useState(0);
  const trickle = useRef<any>(null);
  const fade = useRef<any>(null);
  const safety = useRef<any>(null);
  const first = useRef(true);

  function clearTimers() {
    clearInterval(trickle.current);
    clearTimeout(fade.current);
    clearTimeout(safety.current);
  }

  function start() {
    clearTimers();
    setVisible(true);
    setProgress(10);
    // Ease toward ~90% while we wait for the route to settle.
    trickle.current = setInterval(() => {
      setProgress((p) => (p >= 90 ? p : p + Math.max(0.5, (90 - p) * 0.09)));
    }, 180);
    // Never let the bar hang if a nav never resolves to a pathname change.
    safety.current = setTimeout(finish, 8000);
  }

  function finish() {
    clearTimers();
    setProgress(100);
    fade.current = setTimeout(() => {
      setVisible(false);
      setProgress(0);
    }, 320);
  }

  useEffect(() => {
    function onClick(e: MouseEvent) {
      if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      const a = (e.target as HTMLElement)?.closest?.("a");
      if (!a) return;
      const targetAttr = a.getAttribute("target");
      if ((targetAttr && targetAttr !== "_self") || a.hasAttribute("download")) return;
      const href = a.getAttribute("href");
      if (!href || href.startsWith("#") || href.startsWith("mailto:") || href.startsWith("tel:")) return;
      let url: URL;
      try {
        url = new URL((a as HTMLAnchorElement).href, window.location.href);
      } catch {
        return;
      }
      if (url.origin !== window.location.origin) return;
      // Same page (or hash-only) — nothing to load.
      if (url.pathname === window.location.pathname && url.search === window.location.search) return;
      start();
    }
    document.addEventListener("click", onClick, true);
    window.addEventListener("popstate", start);
    return () => {
      document.removeEventListener("click", onClick, true);
      window.removeEventListener("popstate", start);
      clearTimers();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Route settled → complete.
  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    finish();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pathname]);

  // Data activity (prod latency pass, 2026-09-24): show the same bar while
  // backend calls are outstanding, so a page fetching after the route settled
  // still visibly loads. A 150 ms grace stops instant calls flickering it.
  useEffect(() => {
    let grace: any = null;
    let shownForActivity = false;
    const unsubscribe = apiActivity.subscribe((inflight) => {
      if (inflight > 0) {
        if (grace || shownForActivity) return;
        grace = setTimeout(() => {
          grace = null;
          shownForActivity = true;
          start();
        }, 150);
      } else {
        if (grace) {
          clearTimeout(grace);
          grace = null;
        }
        if (shownForActivity) {
          shownForActivity = false;
          finish();
        }
      }
    });
    return () => {
      unsubscribe();
      if (grace) clearTimeout(grace);
    };
    // start/finish only touch refs and setState; deliberately mount-once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <>
      {/* Top progress bar */}
      <div
        aria-hidden="true"
        style={{
          position: "fixed",
          top: 0,
          left: 0,
          height: 2.5,
          width: `${progress}%`,
          zIndex: 9999,
          opacity: visible ? 1 : 0,
          // Middle "shine" stop derived from the live accent/theme instead of
          // a fixed pale cyan (which nearly vanished on the light page and
          // ignored whatever accent a tenant had chosen).
          background:
            "linear-gradient(90deg, var(--primary), color-mix(in srgb, var(--primary) 45%, white) 55%, var(--primary))",
          boxShadow: "0 0 12px var(--primary), 0 0 4px var(--primary)",
          borderRadius: "0 3px 3px 0",
          transition: "width .2s ease, opacity .32s ease .12s",
          pointerEvents: "none",
        }}
      />
    </>
  );
}
