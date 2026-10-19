// "Is the personal tutor switched on for this deployment?" - asked once per
// page life and shared by every entry point (the card on /learn, the jump
// palette row), so a deployment with the feature off shows no link to it.
//
// The probe is GET /api/tutor/status. With the server flag off no /api/tutor
// route is mapped, so it answers 404; any failure reads as "not available".
// Only a definitive answer (on, or 404 = off) is remembered: a passing failure
// (a restart, a network blip) is asked again by the next entry point instead
// of hiding the tutor until a full reload. The probe call is quiet, so pages
// where nobody asked for the tutor never show a toast about it.

import { useEffect, useState } from "react";
import type { LucideIcon } from "lucide-react";

import { api } from "../../lib/api";

export const TUTOR_ROUTE = "/learn/tutor";

let probe: Promise<boolean> | null = null;

export function tutorAvailable(): Promise<boolean> {
  if (probe === null) {
    const started: Promise<boolean> = Promise.resolve()
      .then(() => api.probeTutorStatus())
      .then((status: any) => Boolean(status) && status.enabled !== false)
      .catch((err: any) => {
        if (err?.status !== 404 && probe === started) probe = null;
        return false;
      });
    probe = started;
  }
  return probe;
}

/** Test seam: forget the remembered answer. */
export function resetTutorAvailability(): void {
  probe = null;
}

/**
 * true once the probe says the tutor is on. `active` delays the request until
 * the caller actually needs the answer (the palette only asks when opened).
 */
export function useTutorAvailable(active: boolean = true): boolean {
  const [available, setAvailable] = useState(false);
  useEffect(() => {
    if (!active) return undefined;
    let cancelled = false;
    void tutorAvailable().then((on) => {
      if (!cancelled) setAvailable(on);
    });
    return () => {
      cancelled = true;
    };
  }, [active]);
  return available;
}

export type PaletteRow = { href: string; icon: LucideIcon; label: string; groupLabel: string };

/**
 * Adds the tutor as a jump-palette destination right after the Learn row. A
 * palette without a Learn row (the rail hides it) gets nothing added, so the
 * palette never offers a place the rail would not.
 */
export function withTutorRow(rows: PaletteRow[], available: boolean, icon: LucideIcon, label: string): PaletteRow[] {
  if (!available) return rows;
  const at = rows.findIndex((row) => row.href === "/learn");
  if (at < 0 || rows.some((row) => row.href === TUTOR_ROUTE)) return rows;
  const row: PaletteRow = { href: TUTOR_ROUTE, icon, label, groupLabel: rows[at].groupLabel };
  return [...rows.slice(0, at + 1), row, ...rows.slice(at + 1)];
}
