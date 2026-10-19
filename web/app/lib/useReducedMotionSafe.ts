"use client";

import { useEffect, useState } from "react";

// SSR-safe replacement for motion/react's `useReducedMotion()`.
//
// THE BUG: framer-motion's own useReducedMotion() initializes its state
// synchronously from `window.matchMedia("(prefers-reduced-motion: reduce)")`
// during the *first render* (see node_modules/framer-motion/dist/es/utils/
// reduced-motion/use-reduced-motion.mjs — `useState(prefersReducedMotion.current)`,
// no effect involved). The server has no window, so SSR always renders the
// "false" (motion-on) branch. A client whose OS is set to reduced-motion reads
// the real value immediately on its very first render, i.e. during hydration
// itself — so the tree hydration compares against differs from what the
// server sent, and React throws error #418 on every load for those users.
//
// THE FIX: mirror useRegion()'s established pattern in this codebase (see
// ./useRegion.ts) — default to `false` on both the server and the client's
// first render (so hydration always matches SSR byte-for-byte), then read the
// real matchMedia value inside a useEffect (client-only, runs strictly after
// hydration commits) and subscribe to live changes.
//
// TRADE-OFF (deliberate, and the correct one): a visitor with reduced-motion
// enabled will see the first animation frame(s) — e.g. an entrance fade/slide,
// or a decorative ping dot — for one paint before this hook flips to `true`
// and the component re-renders into its static variant. That flicker is the
// standard, accepted cost of SSR + prefers-reduced-motion done correctly; it
// is far cheaper than the alternative:
//   - `suppressHydrationWarning` only silences the console warning, it does
//     NOT fix the actual DOM mismatch — React still reconciles the wrong
//     subtree and downstream bugs (lost event handlers, wrong refs) remain.
//   - A two-pass keyed remount (render a `null`/skeleton until mounted, then
//     remount the real tree with a changed `key`) avoids the flash entirely,
//     but forces a full extra paint + layout pass on every single page load
//     for every visitor (not just reduced-motion ones) just to dodge a
//     one-frame flicker that only affects a minority who have explicitly
//     opted into "reduce motion" — not "eliminate motion", so a first frame
//     is an acceptable, spec-compliant outcome for that preference.
// The one-frame-then-flip approach is what this hook implements.
export function useReducedMotionSafe(): boolean {
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
