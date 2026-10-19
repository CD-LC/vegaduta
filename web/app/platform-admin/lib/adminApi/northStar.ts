import { apiFetch, toJson } from "../api";

// North-star metrics for the platform owner (NorthStarController,
// GET /api/platform/metrics/north-star, platform-admin only). Aggregates only:
// a cell with fewer than 5 contributing users arrives as null with the
// enclosing point's `suppressed` flag set - hidden, never zero. The server
// caches the whole response for an hour, so `generatedAt` can be up to an
// hour old.

export type RunKind = "INTERACTIVE" | "SCHEDULED" | "TRIGGERED" | "CHANNEL";

export interface WeekPoint {
  /** Monday of the ISO week, YYYY-MM-DD (UTC). */
  weekStart: string;
  /** True for the still-running current week. */
  partial: boolean;
  wau: number | null;
  runs: number | null;
  /** Share of runs that were scheduled, event-triggered or channel-delivered. */
  scheduledShare: number | null;
  successRate: number | null;
  runsByKind: Record<string, number> | null;
  suppressed: boolean;
}

export interface CohortPoint {
  weekStart: string;
  signups: number | null;
  activated: number | null;
  medianMinutes: number | null;
  suppressed: boolean;
}

export interface ChannelPoint {
  channel: string;
  runs: number | null;
  successRate: number | null;
  suppressed: boolean;
}

export interface LanguagePoint {
  language: string;
  conversations: number | null;
  suppressed: boolean;
}

export interface PassKGroup {
  k: number;
  cases: number | null;
  passPowK: number | null;
  suppressed: boolean;
}

export interface PassK {
  average: number | null;
  cases: number | null;
  byK: PassKGroup[];
  suppressed: boolean;
}

export interface NorthStarCoverage {
  tenantsTotal: number;
  tenantsScanned: number;
  tenantsSkipped: number;
  truncated: boolean;
}

export interface NorthStarResponse {
  generatedAt: string;
  weeks: WeekPoint[];
  timeToFirstRunMedianMinutes: number | null;
  timeToFirstRunCohorts: CohortPoint[];
  languages: LanguagePoint[];
  channels: ChannelPoint[];
  passK: PassK | null;
  coverage: NorthStarCoverage;
  /** Plain sentences: every unavailable, partial or suppressed metric explains itself here. */
  notes: string[];
}

export const northStarApi = {
  get: (weeks = 12): Promise<NorthStarResponse> =>
    apiFetch(`/api/platform/metrics/north-star?weeks=${weeks}`).then(toJson),
};
