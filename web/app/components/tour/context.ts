"use client";

import { createContext, useContext } from "react";
import type { CourtRegionId } from "./court";
import type { TourAccess, TourAudience } from "./audience";
import type { TourStep } from "./types";
import { UNKNOWN_ACCESS } from "./storage";

// The tour's context contract: what TourProvider publishes and what useTour()
// hands every consumer (with a safe no-op shape outside the provider).

export type TourStatus = "idle" | "welcome" | "active" | "completed";

export interface TourContextValue {
  status: TourStatus;
  stepIndex: number;
  currentStep: TourStep | null;
  /** The steps THIS visitor walks — a stable subsequence of TOUR_STEPS, not
   * the catalogue. Every index-bearing member of this context (stepIndex,
   * totalSteps, nextStep/prevStep/goToStep, the resume key) is an offset
   * into this array. */
  steps: TourStep[];
  totalSteps: number;
  /** Who the tour thinks this visitor is, and what their tenant is entitled
   * to. Read-only: the provider resolves it, nothing else may set it.
   * Exposed so the overlay can render copy that matches the entitlement
   * (a plan-conditional line must not hardcode one plan's claim) and so the
   * tests can assert the audience split without reaching into the DOM. */
  audience: TourAudience;
  access: TourAccess;
  startTour: () => void;
  dismissWelcome: () => void;
  nextStep: () => void;
  prevStep: () => void;
  skipTour: () => void;
  finishTour: () => void;
  /** Jump straight to a step — the overlay's progress dots / step list, and
   * the way back in from the completion screen. Clamps into range and puts
   * the tour in "active". */
  goToStep: (index: number) => void;
  /** Manually re-open the tour from scratch — the Help page's "Replay" entry
   * point and the topbar "?" panel. Returns whether a tour actually started,
   * so a caller can say something instead of doing nothing when it did not
   * (2026-09-19: a silent no-op on the one control whose job is "show me the
   * tour again" was indistinguishable from a broken button). */
  replayTour: () => boolean;
  /** Does the server route this tenant to the platform-hosted model pool?
   * `null` until GET /api/providers/availability has answered, and `null`
   * forever if it fails - the tour then simply does not make the "no API key
   * needed" claim. See ./tour/consumerGuide.ts#hostedModelClaimAllowed. */
  hostedModelAvailable: boolean | null;
  /** True for a few seconds after the visitor bails out (skip, or dismissing
   * the welcome), while TourOverlay shows where the tour can be reopened. */
  reopenHintVisible: boolean;
  dismissReopenHint: () => void;
  /** The Court (tour mode "court", tour/CourtMap.tsx): a full-screen map of the
   * console's regions. `walkCourtRegion` runs that region's EXISTING steps for
   * this visitor and returns false when it has none (the map then opens the
   * region's first page instead). A region walk never records first-run tour
   * progress, and ending or skipping it returns to the map. */
  courtOpen: boolean;
  openCourt: () => void;
  closeCourt: () => void;
  walkCourtRegion: (id: CourtRegionId) => boolean;
  courtStopCount: (id: CourtRegionId) => number;
  /** True while the active walk is a catch-up of steps added after this
   * visitor finished or skipped the tour (today: just "security"). The
   * overlay may use it to drop first-run chrome (counter, rail, "Skip") that
   * reads oddly on a one-step walk. Its exits never touch vd_tour_status. */
  catchUp: boolean;
}

export const TourContext = createContext<TourContextValue | null>(null);

/** Falls back to a safe no-op shape outside the provider — the tour is a
 * soft onboarding affordance, never worth crashing a render over.
 *
 * `steps` is empty and `totalSteps` 0 here, not TOUR_STEPS.length: outside
 * the provider no audience has been resolved, so the honest answer to "how
 * many steps does this visitor walk" is none. currentStep is null, so
 * nothing renders a counter against it either way. */
export function useTour(): TourContextValue {
  const ctx = useContext(TourContext);
  if (!ctx) {
    return {
      status: "idle",
      stepIndex: 0,
      currentStep: null,
      steps: [],
      totalSteps: 0,
      audience: UNKNOWN_ACCESS.audience,
      access: UNKNOWN_ACCESS,
      startTour: () => {},
      dismissWelcome: () => {},
      nextStep: () => {},
      prevStep: () => {},
      skipTour: () => {},
      finishTour: () => {},
      goToStep: () => {},
      replayTour: () => false,
      hostedModelAvailable: null,
      reopenHintVisible: false,
      dismissReopenHint: () => {},
      courtOpen: false,
      openCourt: () => {},
      closeCourt: () => {},
      walkCourtRegion: () => false,
      courtStopCount: () => 0,
      catchUp: false,
    };
  }
  return ctx;
}
