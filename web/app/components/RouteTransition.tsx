"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from "react";
import { usePathname } from "next/navigation";
import Image from "next/image";

// ---------------------------------------------------------------------------
// Route transition cover — the fix for "on refresh / navigation I see errors
// flash, then they vanish once the page loads."
//
// Why this exists (and why NavigationProgress alone wasn't enough): every
// console screen is a CLIENT component that renders its shell immediately and
// then fetches its own data from `core` after mount. NavigationProgress's bar
// finishes the moment `usePathname` changes — i.e. the instant the new route
// MOUNTS — which is BEFORE any of that data has arrived. So there's a window
// where the freshly-mounted page is showing its empty/loading/error states
// (an "activate a provider first" banner, a "couldn't load" message, a bare
// skeleton) with nothing covering it. That flash is exactly what the user
// reported.
//
// This component used to cover that window with a THEME-OPAQUE full-viewport
// layer plus a centered branded spinner — which is exactly what made every
// in-app navigation look and feel like a full page reload (SPA pass,
// 2026-10-03: "page is refreshing on click"). It is now a small floating
// loading icon (transparent backdrop, pointer-events:none, top-right corner)
// so the incoming page renders immediately and only the icon announces that
// data is still arriving. The timing contract is unchanged: it does NOT hide
// the moment the route mounts — it holds until the page signals its primary
// data is ready via usePageReady(), so slow fetches still show the icon.
// Pages that don't opt in get the same brief icon (it only fades in after
// SPINNER_DELAY_MS anyway), and a hard safety cap guarantees it always
// clears.
//
// The shell registration handshake below is kept for the same reason it was
// added: AppShell is invoked per-page (every console page wraps itself in
// <AppShell>), not from a shared layout, so it remounts on every navigation.
// AppShell registers itself as "present" via registerShell()/
// unregisterShell() (called from a `useLayoutEffect`, so the flag flips
// before the browser ever paints), and this root-level viewport badge only
// renders while NO shell is registered (i.e. for pre-auth/public pages —
// login, signup, marketing, /try, /docs, etc.). Whenever a shell IS
// registered, AppShell instead renders its OWN badge (same icon, via the
// exported <RouteTransitionOverlay variant="content" />) pinned inside its
// main content region, using `holds` from this same context.
// ---------------------------------------------------------------------------

type RouteTransitionApi = {
  hold: () => void;
  release: () => void;
  holds: number;
  registerShell: () => void;
  unregisterShell: () => void;
};

const noop = () => {};
const RouteTransitionContext = createContext<RouteTransitionApi>({
  hold: noop,
  release: noop,
  holds: 0,
  registerShell: noop,
  unregisterShell: noop,
});

/**
 * Opt a page into the transition indicator: pass whether its primary data has
 * finished loading. While `ready` is false the badge stays up; once it
 * flips true (or the page unmounts) the hold is released and the badge fades.
 *
 * Usage in a page/component:
 *   const [loaded, setLoaded] = useState(false);
 *   // ...set loaded=true in your fetch's finally...
 *   usePageReady(loaded);
 *
 * A page that never calls this still gets the short default cover, so this is
 * purely additive — nothing regresses for screens that don't opt in.
 */
export function usePageReady(ready: boolean): void {
  const { hold, release } = useContext(RouteTransitionContext);
  useEffect(() => {
    if (ready) return undefined;
    hold();
    return () => release();
  }, [ready, hold, release]);
}

// Minimum time the indicator stays up even for an instant client navigation —
// long enough to read as deliberate (not a flicker), short enough to feel
// instant.
//
// SPA feel (2026-09-26): this is 0. A forced minimum made every navigation
// cost >=200ms + a fade even when the page's data was already in the client
// cache. The badge now lifts the moment usePageReady() reports ready (the
// next frame for cached data), and only fades in at all if the wait runs past
// SPINNER_DELAY_MS - i.e. only when data is genuinely slow.
export const MIN_DISPLAY_MS = 0;
export const SPINNER_DELAY_MS = 400;
const FADE_MS = 120;
// Absolute ceiling: if a page opts in but its data never resolves (hung fetch,
// bug), never leave the badge spinning forever — clear it after this.
export const MAX_WAIT_MS = 8000;

export default function RouteTransition({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const [holds, setHolds] = useState(0);
  const [active, setActive] = useState(true); // cover the very first load too
  const [minElapsed, setMinElapsed] = useState(false);
  // How many currently-mounted AppShell instances have registered themselves
  // (see the module comment above). Almost always 0 or 1 — a plain counter
  // rather than a boolean survives the brief moment during a navigation
  // between two AppShell pages where React may tear down the old one and
  // build the new one within the same commit.
  const [shellCount, setShellCount] = useState(0);

  const minTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const maxTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const hold = useCallback(() => setHolds((n) => n + 1), []);
  const release = useCallback(() => setHolds((n) => Math.max(0, n - 1)), []);
  const registerShell = useCallback(() => setShellCount((n) => n + 1), []);
  const unregisterShell = useCallback(() => setShellCount((n) => Math.max(0, n - 1)), []);

  // Open a fresh cover cycle on every route change (and on first mount). Child
  // effects (a page's usePageReady hold) run before this parent effect, so any
  // hold the incoming page registers is already counted by the time we flip
  // `active` on — no race where the cover hides before the page can claim it.
  useEffect(() => {
    setActive(true);
    setMinElapsed(false);
    if (minTimer.current) clearTimeout(minTimer.current);
    if (maxTimer.current) clearTimeout(maxTimer.current);
    minTimer.current = setTimeout(() => setMinElapsed(true), MIN_DISPLAY_MS);
    maxTimer.current = setTimeout(() => setActive(false), MAX_WAIT_MS);
    return () => {
      if (minTimer.current) clearTimeout(minTimer.current);
      if (maxTimer.current) clearTimeout(maxTimer.current);
    };
  }, [pathname]);

  // Reveal once the minimum cover time has passed AND nothing is holding the
  // page open. The extra rAF lets the now-ready content paint underneath the
  // still-opaque cover for one frame, so the fade reveals finished content
  // rather than crossfading through a mid-layout frame.
  useEffect(() => {
    if (!active || !minElapsed || holds !== 0) return undefined;
    const id = requestAnimationFrame(() => setActive(false));
    return () => cancelAnimationFrame(id);
  }, [active, minElapsed, holds]);

  return (
    <RouteTransitionContext.Provider value={{ hold, release, holds, registerShell, unregisterShell }}>
      {children}
      {/* Only render the viewport badge when no AppShell is currently
          registered — see the big comment above. Authenticated console pages
          get their own content-scoped badge from AppShell instead. */}
      {shellCount === 0 && <RouteTransitionOverlay active={active} />}
    </RouteTransitionContext.Provider>
  );
}

/**
 * For AppShell only: registers/unregisters its presence (suppressing the root
 * viewport badge above) and returns whether ITS OWN scoped, content-only
 * badge should currently be shown.
 *
 * Deliberately re-implements the same active/minElapsed/max-wait timing as
 * the root cover above, rather than reading this context's `active` directly
 * — AppShell mounts fresh on every navigation (it's rendered per-page, not
 * from a persistent layout), so driving its local cover off of ITS OWN mount
 * lifecycle (this hook's effect below runs exactly once per AppShell mount,
 * which is exactly once per navigation) avoids a race against the root
 * `active` state, which flips via a separate effect keyed off `pathname` and
 * could otherwise lag one paint behind a freshly-mounted consumer.
 *
 * `enabled` lets a caller skip registration entirely (AppShell passes
 * `coreReachable` — while core is down it renders MaintenanceScreen instead
 * of the real nav/header, so there's no shell to preserve and the root's
 * full-viewport cover should behave exactly as it did before this fix).
 */
export function useShellLoadingCover(enabled: boolean = true): boolean {
  const { holds, registerShell, unregisterShell } = useContext(RouteTransitionContext);
  const [active, setActive] = useState(true);
  const [minElapsed, setMinElapsed] = useState(false);

  // useLayoutEffect: flips shellCount in the parent context BEFORE the
  // browser paints this commit, so the root cover never gets a chance to
  // paint over an already-mounted shell (and, symmetrically, unregistering
  // on unmount never leaves a one-frame gap where the root cover could flash
  // on between two AppShell pages — see the module comment above).
  useLayoutEffect(() => {
    if (!enabled) return undefined;
    registerShell();
    return () => unregisterShell();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled]);

  // Mirrors RouteTransition's own min/max timers, but keyed to THIS mount
  // (i.e. once per navigation, since AppShell remounts each time) instead of
  // to `pathname` — see the hook's own doc comment above.
  useEffect(() => {
    const minTimer = setTimeout(() => setMinElapsed(true), MIN_DISPLAY_MS);
    const maxTimer = setTimeout(() => setActive(false), MAX_WAIT_MS);
    return () => {
      clearTimeout(minTimer);
      clearTimeout(maxTimer);
    };
     
  }, []);

  useEffect(() => {
    if (!active || !minElapsed || holds !== 0) return undefined;
    const id = requestAnimationFrame(() => setActive(false));
    return () => cancelAnimationFrame(id);
  }, [active, minElapsed, holds]);

  return active;
}

/**
 * The loading indicator — the branded ring + winged-logo mark floating in a
 * small non-blocking badge (SPA pass, 2026-10-03). This used to be a
 * theme-OPAQUE layer covering the whole viewport (or AppShell's whole
 * content region) until each page's data arrived — which is exactly what
 * made every in-app navigation look like a full page reload. It is now just
 * an icon: transparent backdrop, pointer-events:none, tucked into the
 * top-right corner, so the new page renders immediately underneath and only
 * the icon announces that data is still arriving.
 * `variant="viewport"` is used at the root for pages with no AppShell;
 * `variant="content"` pins the same badge to AppShell's <main> region so the
 * rail/topbar never show it.
 */
export function RouteTransitionOverlay({
  active,
  variant = "viewport",
}: {
  active: boolean;
  variant?: "viewport" | "content";
}) {
  // Keep the node mounted through the fade-out, then drop it.
  const [mounted, setMounted] = useState(active);
  useEffect(() => {
    if (active) {
      setMounted(true);
      return undefined;
    }
    const t = setTimeout(() => setMounted(false), FADE_MS + 20); // > fade duration
    return () => clearTimeout(t);
  }, [active]);

  if (!mounted) return null;

  const isContent = variant === "content";

  return (
    <div
      role="status"
      aria-live="polite"
      aria-label={active ? "Loading" : undefined}
      aria-hidden={active ? undefined : true}
      style={{
        position: isContent ? "absolute" : "fixed",
        top: isContent ? 12 : 18,
        right: isContent ? 16 : 20,
        width: 44,
        height: 44,
        borderRadius: "50%",
        // Translucent disc so the icon stays legible over any content
        // without covering it - follows light/dark via --surface-page.
        background: "color-mix(in srgb, var(--surface-page, #f7f8fa) 72%, transparent)",
        backdropFilter: "blur(6px)",
        boxShadow: "0 1px 8px rgb(0 0 0 / 0.08)",
        zIndex: isContent ? 20 : 10000,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        opacity: active ? 1 : 0,
        transition: `opacity ${FADE_MS}ms ease`,
        pointerEvents: "none",
      }}
    >
      <style>{`
        @keyframes rt-spin { to { transform: rotate(360deg); } }
        /* Spinner stays invisible for SPINNER_DELAY_MS so a fast (cached)
           navigation never flashes it. */
        @keyframes rt-late { from { opacity: 0; } to { opacity: 1; } }
        /* Gentler range than the old 9px dot's (.62 -> 1.08): the monogram is
           a legible mark, not an abstract blip, so it should breathe rather
           than throb, and it must never shrink below readable. */
        @keyframes rt-pulse { 0%,100% { transform: scale(.94); opacity: .82; } 50% { transform: scale(1.04); opacity: 1; } }
        @media (prefers-reduced-motion: reduce) {
          .rt-ring, .rt-core { animation-duration: 0s !important; }
        }
      `}</style>
      <div style={{ position: "relative", width: 32, height: 32, animation: `rt-late .25s ease ${SPINNER_DELAY_MS}ms both` }}>
        {/* soft glow */}
        <div
          style={{
            position: "absolute",
            inset: -12,
            borderRadius: "50%",
            background:
              "radial-gradient(circle, color-mix(in srgb, var(--primary) 30%, transparent), transparent 70%)",
          }}
        />
        {/* conic ring */}
        <div
          className="rt-ring"
          style={{
            position: "absolute",
            inset: 0,
            borderRadius: "50%",
            background: "conic-gradient(from 90deg, transparent 0deg, var(--primary) 320deg)",
            WebkitMask:
              "radial-gradient(farthest-side, transparent calc(100% - 3px), #000 calc(100% - 3px))",
            mask: "radial-gradient(farthest-side, transparent calc(100% - 3px), #000 calc(100% - 3px))",
            animation: "rt-spin .75s linear infinite",
          }}
        />
        {/* Brand monogram at the centre of the ring (2026-07-25 brand pass).
            This badge is the app's most-seen loading indicator and it used to
            carry no brand at all — just an anonymous violet dot. It now pulses
            the winged human-in-helmet emblem (/logo-mark.png), the one brand
            mark used everywhere (direct instruction, 2026-07-25: never the "V"
            monogram), in the artwork's own colors. */}
        <div
          style={{
            position: "absolute",
            inset: 0,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
          }}
        >
          <Image
            className="rt-core"
            src="/logo-mark.png"
            alt=""
            height={18}
            width={24}
            draggable={false}
            style={{
              height: 18,
              width: "auto",
              objectFit: "contain",
              display: "block",
              animation: "rt-pulse 1.6s ease-in-out infinite",
            }}
          />
        </div>
      </div>
    </div>
  );
}
