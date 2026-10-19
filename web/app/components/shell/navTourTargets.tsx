"use client";

// Product-tour anchors for the nav rail, moved out of AppShell.tsx unchanged.
// Kept as .tsx on purpose: steps.test.tsx finds data-tour anchors by scanning app/**/*.tsx.




// PRODUCT-TOUR ANCHORS — do not strip these in a nav refactor.
// The guided walkthrough (TourProvider.tsx's TOUR_STEPS -> TourOverlay's
// spotlight) finds each step's target with
// document.querySelector('[data-tour="..."]'), so these attributes are the
// only thing tying a tour step to a real nav row. Keyed by href rather than
// written as inline ternaries on the <Link> so that moving a row between
// sections, or reordering NAV_SECTIONS, cannot silently orphan a step.
// If you rename a route here, rename it in TOUR_STEPS too.
// Note "/admin/channels" (not "/channels") and "/help" -> "help-link", whose
// name predates this map and is kept because it is the pre-existing marker.
export const NAV_TOUR_TARGETS: Record<string, string> = {
  // Build Studio is the signed-in home; the admin row anchors the tour's
  // "you are the admin" and "add teammates" steps.
  "/build": "nav-build",
  // The member dashboard (/home, the live "cognition" panel). Members get a
  // step pointing at this row; it is adminHidden, so the same marker simply
  // never exists for an admin and rule 4 drops the step for them.
  "/home": "nav-home",
  "/admin": "nav-admin",
  "/agents": "nav-agents",
  "/workflows": "nav-workflows",
  "/knowledge": "nav-knowledge",
  "/admin/channels": "nav-channels",
  "/tools": "nav-tools",
  "/playground": "nav-playground",
  // First-login tour's Edge AI step (2026-09-19). tour/targets.ts keeps a
  // fallback selector for this row, so the step survives if this entry goes.
  "/edge": "nav-edge",
  // Audience-aware tour (2026-09-12): the member tour has no admin surfaces
  // to walk through, so it points at the collaboration row instead. The href
  // is "/shared-with-me" (BUILD group, see NAV_SECTIONS above), not
  // "/shared" - keep them in step.
  "/shared-with-me": "nav-shared",
  "/help": "help-link",
};

// The same idea one level up: a marker per rail GROUP, so the Court map
// (tour/CourtMap.tsx) can light "the Build region" on the real rail while the
// visitor hovers it on the map. "nav-monitor" predates this map and is the
// tour's own step anchor - keep its spelling.
export const NAV_GROUP_TOUR_TARGETS: Record<string, string> = {
  navGroupBuild: "nav-group-build",
  navGroupConnect: "nav-group-connect",
  navGroupConfig: "nav-group-config",
  navGroupLibrary: "nav-group-library",
  navGroupAI: "nav-group-ai",
  navGroupMonitor: "nav-monitor",
  navGroupPlans: "nav-group-plans",
};
