"use client";

import { useEffect, useRef, type ReactNode } from "react";
import { sendSectionReachOnce, type LandingSection } from "./landingBeacon";

// Section-reach instrumentation for the landing page (2026-09-11 funnel gap:
// every beacon on this page except landing_view fired on a CLICK, so a low
// click count on a band was indistinguishable from a band nobody ever
// scrolled to). Wrapping a band in <SectionReach> records, once per browser
// session, that the visitor actually got to it — turning the funnel from a
// list of click counts into a drop-off curve.
//
// WHY THE DWELL QUALIFIER (and not reach-on-first-pixel): a visitor who flings
// the page to the footer technically intersects every band on the way past. If
// that counted as reach, every band would report ~100% and the curve would be
// flat, flattering, and completely false — it would say "they saw everything
// and still didn't sign up", which is the one conclusion that would send the
// next redesign in the wrong direction. Requiring the band to hold the
// visibility bar for a continuous second is the cheapest honest definition of
// "looked at" that needs no extra event and no per-visitor state.
//
// Privacy posture is inherited unchanged from landingBeacon.ts: this sends an
// event NAME to a Redis counter. No scroll positions, no timings, no ordering,
// no session id, nothing that could reconstruct one visitor's path. The
// sessionStorage key is a local dedupe flag and never leaves the browser.

/**
 * Fraction of the band that must be visible. Also reused as the
 * fraction-of-VIEWPORT bar for bands taller than the window — see below.
 */
const VISIBLE_FRACTION = 0.5;

/** How long the visibility bar must hold, continuously, before it counts. */
const DWELL_MS = 1000;

/**
 * Several landing bands are TALLER than the viewport (ChannelWall,
 * AudienceShowcase, the live studio). Such a band can never be 50%
 * visible, so a naive `threshold: 0.5` observer would never fire for exactly
 * the biggest, most content-heavy sections on the page — and they would read
 * as 0% reach, the most alarming possible artefact. So the bar is EITHER:
 *   - half the band is on screen (short band), OR
 *   - the band is covering half the viewport (tall band).
 * The threshold list exists only to get callbacks at several ratios on the way
 * past; a band 3x the viewport height tops out around ratio 0.33 and is caught
 * by the second condition at the 0.25 step.
 */
const THRESHOLDS = [0, 0.1, 0.25, 0.5, 0.75, 1];

export default function SectionReach({
  section,
  children,
}: {
  section: LandingSection;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const el = ref.current;
    // No IntersectionObserver (very old browser, or a JSDOM test that hasn't
    // stubbed it) means no reach data for that visitor — never an error on a
    // marketing page. Same fail-quiet contract as the beacon itself.
    if (!el || typeof IntersectionObserver === "undefined") return undefined;

    let dwellTimer: ReturnType<typeof setTimeout> | null = null;
    const clearDwell = () => {
      if (dwellTimer !== null) {
        clearTimeout(dwellTimer);
        dwellTimer = null;
      }
    };

    const observer = new IntersectionObserver(
      (entries) => {
        const entry = entries[entries.length - 1];
        if (!entry) return;

        const viewport = window.innerHeight || 0;
        const meetsBar =
          entry.isIntersecting &&
          (entry.intersectionRatio >= VISIBLE_FRACTION ||
            entry.intersectionRect.height >= viewport * VISIBLE_FRACTION);

        if (!meetsBar) {
          clearDwell(); // scrolled away before the second was up — not a read
          return;
        }
        if (dwellTimer !== null) return; // already counting down, don't restart

        dwellTimer = setTimeout(() => {
          dwellTimer = null;
          sendSectionReachOnce(section);
          observer.disconnect(); // once per session; nothing left to watch
        }, DWELL_MS);
      },
      { threshold: THRESHOLDS }
    );

    observer.observe(el);
    return () => {
      clearDwell();
      observer.disconnect();
    };
  }, [section]);

  // A bare block-level div, deliberately unstyled: every band below is a
  // full-width child of the landing's flow container, and DemoLanding already
  // wraps two of them (#live-studio, the senses prompt target) in exactly this
  // way. Nothing here participates in layout.
  return <div ref={ref}>{children}</div>;
}
