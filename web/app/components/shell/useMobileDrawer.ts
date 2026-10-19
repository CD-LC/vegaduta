"use client";

// Off-canvas drawer state, focus management and the product tour's auto-open machine. Lifted out of AppShell.tsx unchanged.

import { useCallback, useEffect, useRef, useState } from "react";
import { useTour } from "../TourProvider";


export function useMobileDrawer(pathname: string, isDesktop: boolean) {
  // Mobile responsiveness: the left rail below is `position:fixed` and
  // always-visible on desktop widths (see globals.css's `.ax-rail`) - there
  // was previously no way to reach it at all on a phone-sized viewport (an
  // earlier comment in globals.css explicitly called this console
  // "desktop-only... a dedicated mobile app is planned"). This turns the
  // rail into an off-canvas drawer below the `.ax-rail`/`.ax-topbar`
  // mobile breakpoint (900px, see globals.css) - closed by default, opened
  // via the hamburger button in the topbar (rendered only at that
  // breakpoint via CSS, not conditional JS, so no hydration mismatch), and
  // closed again on outside-click (backdrop), Escape, or navigating to a
  // new route.
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const navRef = useRef<HTMLElement>(null);
  const mobileMenuButtonRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    setMobileNavOpen(false);
  }, [pathname]);
  useEffect(() => {
    if (!mobileNavOpen) return undefined;
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") setMobileNavOpen(false);
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [mobileNavOpen]);
  const mobileNavWasOpen = useRef(false);

  // ---------------------------------------------------------------- TOUR
  // Below lg the rail is parked off-screen (`-translate-x-full` on the <nav>
  // below) unless mobileNavOpen is true. There is no single "the tour" any
  // more - TourProvider tags each catalogue step by audience and experience
  // mode, so what a visitor actually walks is one of six selections (member /
  // tenant-admin / platform-admin, each in SIMPLE or ADVANCED), and the old
  // fixed nine-step walk is just the tenant-admin ADVANCED case. What did NOT
  // change is the thing this block depends on: EVERY anchored step in the
  // catalogue points at a data-tour element inside this rail (the nav rows,
  // help-link, and the rail itself for the platform-admin walk); the only
  // unanchored steps are the two centred heroes. So in every selection all but
  // a step or two are rail-anchored, and TourOverlay's visibleRect() correctly
  // rejects an off-screen rect - with the drawer closed the spotlight silently
  // renders nothing and the walk degrades into a stack of bottom sheets
  // floating over an undimmed, still-tappable dashboard. The longest walk
  // degrades the worst, but none of them escape it. That is the tour's most
  // common first-login
  // device class, so: when the tour goes "active" on a sub-lg viewport, open
  // the drawer for it.
  //
  // Deliberately conservative, because an auto-opening drawer is exactly the
  // kind of thing that ends up fighting the user. `tourRailRef` is a five-state
  // machine; each state exists to guard one specific transition:
  //
  //   "none"        no decision on record for this tour run (also the state
  //                 after every remount - see the note below).
  //   "left-alone"  the tour started at >= lg, or the user already had the
  //                 drawer open. Guards against us ever touching a drawer we
  //                 did not open. Terminal for the run.
  //   "opened"      WE opened it and it is open. Guards the close-on-tour-end
  //                 below: we only undo our own open.
  //   "reopen"      a NAVIGATION is in flight that we know closes the drawer
  //                 (a nav-row tap, or a pathname change). Guards against
  //                 mistaking that close for a dismissal: the next time the
  //                 effect sees the drawer shut it puts it back.
  //   "user-closed" the drawer went shut while "opened" with no navigation on
  //                 record - i.e. the backdrop, Escape or the hamburger. This
  //                 is the one real dismissal signal, and it is terminal: we
  //                 never reopen for the rest of the run.
  //
  // Transitions: none -> left-alone | opened; opened -> reopen (navigation
  // marked) | user-closed (drawer shut, unmarked); reopen -> opened (we put it
  // back); anything -> none when the tour leaves "active".
  //
  // ROUND 3 (this is the fix): the old machine had no "reopen". Any close was
  // read as a dismissal, so pressing "Show me" on an early step - or tapping
  // the row the spotlight was on - retired the auto-open for the whole rest of
  // the tour and every remaining rail-anchored step spotlit an off-screen rail
  // (which, for every audience, is almost all of them). A route change the tour's
  // own button caused is not the user closing the drawer; that is the entire
  // distinction the "reopen" state encodes. A close we did NOT mark still wins
  // and is still terminal, so a user who genuinely dismisses the drawer is
  // never argued with.
  //
  // Worth knowing while reading this: AppShell remounts on every navigation
  // (see the persistent-shell note further down, and layout.tsx's comments on
  // AppChromeProvider), so a cross-route "Show me" usually lands on a fresh
  // instance whose ref is already "none" and which re-decides from scratch.
  // The state machine matters for the closes that happen on THIS instance -
  // the nav-row onClick below, and any navigation that does not swap the page
  // component (same-route "Show me", query-only pushes) - and it is also what
  // keeps this correct if AppShell is ever hoisted into a shared layout.
  //
  //  - We only close on tour end if WE opened it. A drawer the user had open
  //    before the tour started stays open.
  //  - "(min-width: 64rem)" is Tailwind v4's `lg` verbatim (rem, not px, so a
  //    non-16px root font-size can't desync JS from CSS). This must stay in
  //    step with the `lg:translate-x-0` on the <nav> below. TourOverlay's
  //    DOCK_BREAKPOINT (1024) is the SAME breakpoint for the SAME reason -
  //    "is the rail on screen?" and "is the tour card a bottom sheet?" are one
  //    question, and its own comment says so. They are written differently on
  //    purpose: this one is a rem media query (immune to a non-16px root font
  //    size), that one compares a px viewport width. If the rail's breakpoint
  //    ever moves, all three must move together - the `lg:` classes on the
  //    <nav> below, this query, and DOCK_BREAKPOINT.
  //
  // useTour() returns a no-op shape outside a provider, but AppShell is
  // genuinely inside one: layout.tsx mounts <TourProvider> around {children},
  // and every page wraps its own content in <AppShell>, so the provider is
  // always an ancestor here and `tour.status` is the real value.
  const tour = useTour();
  const tourRailRef = useRef<"none" | "opened" | "reopen" | "left-alone" | "user-closed">("none");

  // Marks a navigation as OURS, so the close it causes is not read as a
  // dismissal. Only "opened" is upgraded: a drawer the user already shut
  // ("user-closed") or one that was never ours ("left-alone") must stay that
  // way, whatever navigating happens afterwards.
  const markTourNavigation = useCallback(() => {
    if (tour.status === "active" && tourRailRef.current === "opened") {
      tourRailRef.current = "reopen";
    }
  }, [tour.status]);

  // Declared BEFORE the effect below on purpose. On the commit where the tour
  // first goes "active" both run; this one must see the pre-decision state
  // ("none") and no-op, rather than seeing the "opened" the other one just
  // wrote and marking a navigation that never happened.
  useEffect(() => {
    markTourNavigation();
  }, [pathname, markTourNavigation]);

  useEffect(() => {
    if (tour.status !== "active") {
      // Tour over. Undo our own open - "reopen" counts as ours, it is just
      // "opened" mid-navigation.
      if (tourRailRef.current === "opened" || tourRailRef.current === "reopen") {
        setMobileNavOpen(false);
      }
      tourRailRef.current = "none";
      return;
    }
    if (tourRailRef.current === "reopen") {
      if (!mobileNavOpen) setMobileNavOpen(true);
      tourRailRef.current = "opened";
      return;
    }
    if (tourRailRef.current === "opened") {
      // Open drawer, ours, now shut, and nothing marked it: the user did it.
      if (!mobileNavOpen) tourRailRef.current = "user-closed";
      return;
    }
    // "left-alone" and "user-closed" are terminal for this run.
    if (tourRailRef.current !== "none") return;
    if (window.matchMedia("(min-width: 64rem)").matches || mobileNavOpen) {
      tourRailRef.current = "left-alone";
      return;
    }
    tourRailRef.current = "opened";
    setMobileNavOpen(true);
  }, [tour.status, mobileNavOpen]);

  // Focus management for the off-canvas drawer (a11y audit follow-up): move
  // focus into it on open (below lg it covers the whole viewport, so nothing
  // else is usable until it's dealt with) and back to the hamburger that
  // opened it once it closes. Skipped at desktop widths, where the nav is
  // always in flow via the `lg:` breakpoint, not a drawer, and this
  // ref-focus would fight normal tabbing.
  useEffect(() => {
    if (isDesktop) return;
    if (mobileNavOpen && !mobileNavWasOpen.current) {
      navRef.current?.querySelector<HTMLElement>("a[href], button:not([disabled])")?.focus();
    } else if (!mobileNavOpen && mobileNavWasOpen.current) {
      mobileMenuButtonRef.current?.focus();
    }
    mobileNavWasOpen.current = mobileNavOpen;
  }, [mobileNavOpen, isDesktop]);
  return { mobileNavOpen, setMobileNavOpen, navRef, mobileMenuButtonRef, markTourNavigation, tourActive: tour.status === "active" };
}
