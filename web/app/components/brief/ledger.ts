// Pure view-model for the proof ledger. The one rule this file exists to hold:
// a change is shown as a change ONLY when core marked it significant AND the
// delta actually clears the noise band. Anything inside the band reads as
// "no measurable change". A LOCKED ledger yields no numbers at all.

import type { BriefLedger, LedgerRun, LedgerState, LedgerThumbs } from "./types";

/** Rated replies needed before a thumbs ratio is quoted (matches the promotion evidence rule). */
export const MIN_RATED_TURNS = 10;

export type RunVerdict = "first" | "improved" | "regressed" | "noChange" | "unmeasured";

export interface RunView {
  version: number;
  score: number | null;
  verdict: RunVerdict;
  /** Signed delta in score units; only set for improved / regressed. */
  delta: number | null;
  /** +/- band in score units, shown next to every comparison when known. */
  band: number | null;
  at?: string;
}

export function runVerdict(run: LedgerRun, index: number): RunVerdict {
  if (run.deltaVsPrev == null) return index === 0 ? "first" : "unmeasured";
  const inBand = run.noiseFloor != null && Math.abs(run.deltaVsPrev) <= run.noiseFloor;
  // core sets significant only with a measured floor and a delta larger than it; hold it to that here too.
  if (!run.significant || inBand || run.noiseFloor == null) return "noChange";
  return run.deltaVsPrev > 0 ? "improved" : "regressed";
}

export function runViews(runs: readonly LedgerRun[]): RunView[] {
  return runs.map((r, i) => {
    const verdict = runVerdict(r, i);
    const shown = verdict === "improved" || verdict === "regressed";
    return {
      version: r.version,
      score: r.score,
      verdict,
      delta: shown ? r.deltaVsPrev : null,
      band: verdict === "first" || verdict === "unmeasured" ? null : r.noiseFloor,
      at: r.at,
    };
  });
}

export type LedgerView =
  /** LOCKED / NOT_APPLIED / NO_EVAL_SET: the reason and no numbers. */
  | { kind: "locked"; state: LedgerState; reason: string | null }
  /** COLLECTING (or READY with nothing scored): a dataset exists, no finished run yet. */
  | { kind: "empty"; reason: string | null }
  | { kind: "ready"; baseline: number | null; runs: RunView[] };

export function ledgerView(ledger: BriefLedger): LedgerView {
  const reason = ledger.reason?.trim() || null;
  if (ledger.state === "LOCKED" || ledger.state === "NOT_APPLIED" || ledger.state === "NO_EVAL_SET") {
    return { kind: "locked", state: ledger.state, reason };
  }
  if (ledger.runs.length === 0) return { kind: "empty", reason };
  return { kind: "ready", baseline: ledger.baseline?.score ?? null, runs: runViews(ledger.runs) };
}

export type ThumbsView = { kind: "none" } | { kind: "few"; total: number } | { kind: "ratio"; total: number; up: number; ratio: number };

export function thumbsView(t: LedgerThumbs | null | undefined): ThumbsView {
  const total = t ? t.rated : 0;
  if (!t || total === 0 || t.ratio == null) return { kind: "none" };
  if (total < MIN_RATED_TURNS) return { kind: "few", total };
  return { kind: "ratio", total, up: t.up, ratio: t.ratio };
}

/**
 * Polyline points for a tiny sparkline over the scored runs. The y domain is
 * the data range padded a little, clamped to 0..1, so a flat history stays
 * flat instead of being stretched into a dramatic slope.
 */
export function sparklinePoints(scores: readonly (number | null)[], w: number, h: number): { points: string; coords: { x: number; y: number }[] } {
  const scored = scores.map((s, i) => ({ s, i })).filter((p): p is { s: number; i: number } => p.s != null);
  if (scored.length === 0) return { points: "", coords: [] };
  const vals = scored.map((p) => p.s);
  const pad = 0.05;
  const lo = Math.max(0, Math.min(...vals) - pad);
  const hi = Math.min(1, Math.max(...vals) + pad);
  const span = hi - lo || 1;
  const last = Math.max(1, scores.length - 1);
  const coords = scored.map((p) => ({
    x: scored.length === 1 ? w / 2 : (p.i / last) * w,
    y: h - ((p.s - lo) / span) * h,
  }));
  return { points: coords.map((c) => `${c.x.toFixed(1)},${c.y.toFixed(1)}`).join(" "), coords };
}

export const pct = (v: number): string => `${Math.round(v * 100)}%`;
/** Score-point delta, e.g. "+8 pts" number part; sign always shown. */
export const points = (v: number): string => `${v > 0 ? "+" : v < 0 ? "-" : ""}${Math.abs(Math.round(v * 100))}`;
