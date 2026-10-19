"use client";

// First-run routing (Earned Autonomy, 2026-09-26): a tenant with no agents and
// no envoys is sent from Home to the Build Center once, where the three-door
// hero is the whole page. Everyone else keeps the Home they had.
//
// It must never fight the product tour, whose arming path (TourProvider) reads
// the DOM once, ~900ms after the nav rail first appears, and treats "no anchor
// found" as "run no tour at all" - a route change inside that window would cost
// a first-time visitor their welcome for the session. So while the tour has not
// yet been offered or dealt with, the redirect waits: it goes as soon as the
// welcome modal is up (that modal is mounted at the root and survives the
// route change), or after TOUR_SETTLE_MS as a backstop. A tour already
// handled in this browser, or no tour at all, redirects immediately.
//
// The pure decision is exported and unit-tested (firstRun.test.ts).

import { useContext, useEffect } from "react";
import { useRouter } from "next/navigation";
import { api } from "../../lib/api";
import { AuthContext } from "../AuthProvider";
import { useTour, userScoped } from "../TourProvider";

export const FIRST_RUN_KEY = "vd_firstrun_build";
// TourProvider's STORAGE_KEY (not exported): set once the tour was done/skipped.
const TOUR_STATUS_KEY = "vd_tour_status";
export const TOUR_SETTLE_MS = 4000;
export const BUILD_ROUTE = "/build";

export type FirstRunInput = {
  agentCount: number | null | undefined;
  envoyCount: number | null | undefined;
  alreadyRouted: boolean;
  isPlatformAdmin: boolean;
  tourStatus: "idle" | "welcome" | "active" | "completed";
  tourHandled: boolean;
};

/** null = do not redirect; otherwise the delay in ms before doing it. */
export function firstRunDelay(i: FirstRunInput): number | null {
  if (i.isPlatformAdmin || i.alreadyRouted) return null;
  // null/undefined = not loaded or the fetch failed: never redirect on a guess.
  if (i.agentCount !== 0 || i.envoyCount !== 0) return null;
  if (i.tourStatus === "active" || i.tourStatus === "completed") return null;
  if (i.tourHandled || i.tourStatus === "welcome") return 0;
  return TOUR_SETTLE_MS;
}

function readFlag(key: string): boolean {
  try {
    return !!window.localStorage.getItem(key);
  } catch {
    return false;
  }
}

/** Call from a Home surface with its own agent count (null until loaded). */
export function useFirstRunBuildRoute(agentCount: number | null | undefined): void {
  const router = useRouter();
  const tour = useTour();
  const auth = useContext(AuthContext) as { isPlatformAdmin?: boolean } | null;
  const isPlatformAdmin = !!auth?.isPlatformAdmin;

  useEffect(() => {
    if (agentCount !== 0 || isPlatformAdmin || readFlag(FIRST_RUN_KEY)) return undefined;
    let cancelled = false;
    let timer: number | undefined;
    // Envoys are the other half of "empty": someone who applied a brief has
    // agents, but a failed apply may leave zero agents and one envoy.
    api
      .listBriefs()
      .then((list: unknown[]) => {
        if (cancelled) return;
        const delay = firstRunDelay({
          agentCount,
          envoyCount: Array.isArray(list) ? list.length : null,
          alreadyRouted: readFlag(FIRST_RUN_KEY),
          isPlatformAdmin,
          tourStatus: tour.status,
          tourHandled: readFlag(userScoped(TOUR_STATUS_KEY)),
        });
        if (delay === null) return;
        timer = window.setTimeout(() => {
          try {
            window.localStorage.setItem(FIRST_RUN_KEY, "1");
          } catch {
            // Private mode: worst case the redirect repeats on the next load.
          }
          router.replace(BUILD_ROUTE);
        }, delay);
      })
      .catch(() => {
        // Core without the brief service, or a transient failure: stay on Home.
      });
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [agentCount, isPlatformAdmin, tour.status, router]);
}
