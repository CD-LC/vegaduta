"use client";

import { useEffect, useState } from "react";

// SSR-safe prefers-reduced-motion hook for the /hire landing. Starts false
// (server render can't know), corrects on mount, and tracks live changes.
// Every scripted animation on the page (rotator, interview typing, smooth
// scrolls) consults this; the pure-CSS animations carry their own
// @media (prefers-reduced-motion: reduce) overrides in hire-landing.css.
export function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(false);
  useEffect(() => {
    if (typeof window === "undefined" || !window.matchMedia) return undefined;
    const mq = window.matchMedia("(prefers-reduced-motion: reduce)");
    setReduced(mq.matches);
    const onChange = (e: MediaQueryListEvent) => setReduced(e.matches);
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, []);
  return reduced;
}
