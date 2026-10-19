// The Court: the five regions of the console, as data (Earned Autonomy,
// 2026-09-26).
//
// CourtMap draws these on a stylised courtyard plan; hovering a region lights
// the matching rail group (via data-tour) and clicking it walks the tour steps
// that already exist for that region. Nothing here invents a step: `stepIds`
// are ids from TourProvider's TOUR_STEPS, kept by where the ROW LIVES IN THE
// RAIL (NAV_SECTIONS), which is not always where a person would guess - the
// Channels row sits under Config, not Connect. A region with no step still
// does not get a made-up one: clicking it opens its first page. (Connect and
// Plans got their steps in the header-controls pass - the group-header
// "connect-group" and "plans-group" steps.)
//
// Pure: no React, no DOM - unit-tested in court.test.ts.

import { isBillingEnabled } from "../../lib/billing-flag";

export type CourtRegionId = "build" | "connect" | "config" | "monitor" | "plans";

export type CourtRegion = {
  id: CourtRegionId;
  /** data-tour on the rail group AppShell renders for this region. */
  railTarget: string;
  /** TOUR_STEPS ids this region walks. Empty = none exist; open `href` instead. */
  stepIds: string[];
  /** Where a click goes when the region has no tour step for this visitor. */
  href: string;
};

// Order is the plan's reading order (north hall first, clockwise).
export const COURT_REGIONS: readonly CourtRegion[] = [
  {
    id: "build",
    railTarget: "nav-group-build",
    stepIds: ["build", "agents", "workflows", "knowledge", "shared-with-me"],
    href: "/agents",
  },
  { id: "connect", railTarget: "nav-group-connect", stepIds: ["connect-group"], href: "/admin/inbox" },
  { id: "config", railTarget: "nav-group-config", stepIds: ["channels", "tools", "edge"], href: "/tools" },
  { id: "monitor", railTarget: "nav-monitor", stepIds: ["playground", "monitor"], href: "/playground" },
  { id: "plans", railTarget: "nav-group-plans", stepIds: ["plans-group"], href: "/features" },
];

/** The regions this install has: "plans" only exists with billing on (its rail group is gone otherwise). */
export function visibleCourtRegions(): readonly CourtRegion[] {
  return isBillingEnabled() ? COURT_REGIONS : COURT_REGIONS.filter((r) => r.id !== "plans");
}

export function courtRegion(id: string | null | undefined): CourtRegion | null {
  return COURT_REGIONS.find((r) => r.id === id) ?? null;
}

/** The subset of `steps` (already audience/entitlement filtered by the caller)
 * that belongs to the region, in tour order. */
export function regionSteps<T extends { id: string }>(region: CourtRegion, steps: readonly T[]): T[] {
  const wanted = new Set(region.stepIds);
  return steps.filter((s) => wanted.has(s.id));
}

/** Must equal TourOverlay's DOCK_BREAKPOINT and the rail's `lg` breakpoint: the
 * width below which the tour card is a bottom sheet, and so the Court is a
 * list. Duplicated, not imported, because TourOverlay imports TourProvider,
 * which imports the Court. */
export const COURT_DOCK_BREAKPOINT = 1024;

export function courtIsDocked(viewportWidth: number): boolean {
  return viewportWidth > 0 && viewportWidth < COURT_DOCK_BREAKPOINT;
}
