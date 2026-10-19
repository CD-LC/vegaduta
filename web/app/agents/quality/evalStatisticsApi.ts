"use client";

import { api } from "../../lib/api";
import type {
  CaseReliabilityResponse,
  DatasetNoiseFloorResponse,
  DatasetReliabilityResponse,
  EvalReliabilityReport,
  PairedComparisonResponse,
  ReliabilityComparisonVerdict,
  ReliabilityInterval,
  ReliabilityIntervalMethod,
  ReliabilityMetric,
  ReliabilityMetricId,
} from "../../lib/api";

/**
 * Typed client contract + interpretation layer for the HONEST EVAL
 * STATISTICS surface of core/quality — the repeat-aware layer that turns
 * every score this console shows from a point estimate into a measurement.
 *
 * WHY THIS SURFACE EXISTS AT ALL
 * ------------------------------
 * A single-run agent score is not a measurement. The quality screens used to
 * render bare numbers: `0.62`, `score 87%`, `▲ 4pt`. None of those carried
 * a repeat count, so none of them could distinguish a real regression from
 * ordinary run-to-run variance. So the product rule this module encodes,
 * and that the UI enforces:
 *
 *   1. Every score renders with its repeat count and interval — `0.62
 *      [0.48–0.75], n=5` — never the point estimate alone.
 *   2. An n=1 score is badged `unreplicated`. It is still shown (hiding it
 *      would be its own lie) but it is never allowed to look measured.
 *   3. An A/B whose paired-gap interval straddles zero, or whose gap is
 *      smaller than the tenant's measured noise floor, renders an explicit
 *      "cannot tell" state. No winner badge. The console must be able to say
 *      "this difference is not real".
 *
 * THE BACKEND CONTRACT THIS WRAPS (reconciled 2026-10-03)
 * ------------------------------------------------------
 * An earlier draft of this file declared a per-RUN contract that the real
 * backend (core/quality/.../stats, EvalReliabilityController + ReliabilityDtos)
 * deliberately did not implement. The reconciliation keeps this module as the
 * boundary the components talk to, but the wire shapes are now the backend's
 * own records transcribed in app/lib/api/types.ts, and the differences are
 * adapter-ed honestly rather than renamed:
 *
 *   - The unit of analysis is the DATASET pooled over its recent SUCCEEDED
 *     runs (each run contributes at most one rollout per case), not one run.
 *     There is no per-run statistics endpoint and none is faked here.
 *   - There is no live/online per-dimension aggregate endpoint either, so
 *     nothing in this module claims one.
 *   - The noise floor is per-dataset too: nested inside each report entry,
 *     and separately addressable via getDatasetNoiseFloor, whose `warnings`
 *     say WHY a floor is absent or suspect when it is.
 *   - The comparison names the challenger `variantRunId` and its verdict
 *     distinguishes CANNOT_TELL (interval contains zero) from
 *     INSIDE_NOISE_FLOOR (real but smaller than the tenant's measured floor).
 *     classifyComparison maps both onto the console's INDISTINGUISHABLE
 *     state with the flag that says which — see its comment.
 *
 * `resolveEvalStatsApi()` feature-detects the three wired methods on the
 * shared `api` object; while an older web bundle lacks any of them the
 * section renders its explicit "wiring pending" notice — never a fake-empty
 * state, and never a fabricated number. Each feature also records its first
 * 404 via markEvalStatsUnsupportedIf and hides itself for the page's life,
 * exactly like evalDatasetsApi.ts's hook detection.
 *
 * Dataset and run LISTING is deliberately not redeclared here: it already
 * exists as EvalDatasetApi.listEvalDatasets/listEvalRuns in
 * evalDatasetsApi.ts, and the comparison picker reuses that contract rather
 * than growing a second copy of it.
 */

// ---------------------------------------------------------------------------
// The one primitive everything else is built from
// ---------------------------------------------------------------------------

/**
 * The estimator names the backend reports — constant names of core/quality's
 * IntervalMethod enum, serialised verbatim. Kept as a re-exported alias so
 * the components say `IntervalMethod` while staying literally identical to
 * the wire values (a rename here is how two surfaces end up disagreeing
 * about which estimator ran).
 */
export type IntervalMethod = ReliabilityIntervalMethod;

/**
 * A measured quantity: point estimate plus the two things that make it a
 * measurement rather than an anecdote — how many INDEPENDENT observations
 * are behind it, and the interval around it.
 *
 * `rollouts` is the estimate's own `n` verbatim, and `method`/`confidenceLevel`
 * come straight off the backend's IntervalResponse. What `n` counts is the
 * backend's `unit` (ROLLOUT / CASE / CASE_PAIR) — the components surface the
 * unit beside it where it would otherwise be ambiguous, rather than folding
 * every n into "rollouts".
 */
export interface IntervalEstimate {
  /** Point estimate, 0..1 for scores and rates. Null = genuinely no data. */
  value: number | null;
  /** Lower/upper interval bound, 0..1. Both null = no interval available. */
  ciLow: number | null;
  ciHigh: number | null;
  /** Independent observations behind the estimate. 0 = no data. */
  rollouts: number;
  method: IntervalMethod | null;
  /** Nominal coverage, e.g. 0.95. Null = the server did not state one, and
   *  the UI then does not claim one either. */
  confidenceLevel: number | null;
}

// ---------------------------------------------------------------------------
// The contract app/lib/api satisfies (wired in lib/api/quality.ts)
// ---------------------------------------------------------------------------

export interface EvalStatisticsApi {
  /** GET /api/agents/{agentId}/quality/reliability[?maxRuns=] —
   *  one entry per dataset with at least one SUCCEEDED run. */
  getEvalReliability(agentId: string, maxRuns?: number): Promise<EvalReliabilityReport | null>;
  /** GET /api/agents/{agentId}/quality/reliability/noise-floor?datasetId= —
   *  the floor for ONE dataset, with warnings explaining absence. */
  getDatasetNoiseFloor(agentId: string, datasetId: string): Promise<DatasetNoiseFloorResponse | null>;
  /** GET /api/agents/{agentId}/quality/reliability/compare
   *      ?baselineRunId=&variantRunId= */
  compareEvalRunsPaired(
    agentId: string,
    baselineRunId: string,
    variantRunId: string
  ): Promise<PairedComparisonResponse | null>;
}

const REQUIRED_METHODS: (keyof EvalStatisticsApi)[] = [
  "getEvalReliability",
  "getDatasetNoiseFloor",
  "compareEvalRunsPaired",
];

/**
 * Feature-detects the contract on the shared client. Returns null while an
 * older web bundle lacks any of the three methods — the UI renders an
 * explicit pending-wiring notice for that case, never a silent empty state.
 */
export function resolveEvalStatsApi(): EvalStatisticsApi | null {
  const candidate = api as unknown as Record<string, unknown>;
  const complete = REQUIRED_METHODS.every((method) => typeof candidate[method] === "function");
  return complete ? (api as unknown as EvalStatisticsApi) : null;
}

/**
 * Second gate, same pattern as evalDatasetsApi.ts's hook detection: the
 * client method can exist while the CORE route does not (an older core
 * answers 404). Each feature records its first 404/501 here and stays hidden
 * for the rest of the page's life — the route is not going to appear between
 * two clicks, and re-probing every render would spam a backend whose answer
 * we already know.
 */
export type EvalStatsFeature = keyof EvalStatisticsApi;

const unsupportedFeatures = new Set<EvalStatsFeature>();

export function isEvalStatsFeatureUnsupported(feature: EvalStatsFeature): boolean {
  return unsupportedFeatures.has(feature);
}

/**
 * Call from a catch block. Records a 404 (route not mounted on this core) or
 * 501 (mounted but not implemented) as "unsupported" and returns true when it
 * did, so the caller can hide/explain instead of surfacing an error.
 *
 * 403 is deliberately NOT treated as unsupported: on this surface it means
 * the PREMIUM+ plan gate (EvalReliabilityController#assertPremiumPlan), which
 * is a real, recoverable state with its own inline upgrade card. Folding it
 * in here would hide an upsell behind a "feature missing" message.
 */
export function markEvalStatsUnsupportedIf(feature: EvalStatsFeature, error: unknown): boolean {
  const status = (error as { status?: number } | null)?.status;
  if (status === 404 || status === 501) {
    unsupportedFeatures.add(feature);
    return true;
  }
  return false;
}

/** True when the error is the PREMIUM+ plan gate rather than a real fault. */
export function isPlanGate(error: unknown): boolean {
  return (error as { status?: number } | null)?.status === 403;
}

// ---------------------------------------------------------------------------
// Backend record -> IntervalEstimate adapters
//
// These exist so every component renders backend numbers through the one
// honest primitive. The mapping is field-for-field: a null interval stays a
// null interval (never a zero-width one), `n` stays the backend's own sample
// size, and the estimator name crosses verbatim. Nothing here renames one
// measurement into another.
// ---------------------------------------------------------------------------

/** A bare interval + sample size as an IntervalEstimate (no point estimate).
 *  Used for the noise floor's disagreement band, where the backend ships a
 *  standalone interval rather than a metric row. */
export function intervalToEstimate(
  value: number | null | undefined,
  interval: ReliabilityInterval | null | undefined,
  n: number | null | undefined
): IntervalEstimate {
  return {
    value: value ?? null,
    ciLow: interval?.low ?? null,
    ciHigh: interval?.high ?? null,
    rollouts: n ?? 0,
    method: interval?.method ?? null,
    confidenceLevel: interval?.confidenceLevel ?? null,
  };
}

/** One metric row (PASS_AT_1 / PASS_AT_K / PASS_HAT_K / MEAN_SCORE) as an
 *  IntervalEstimate. A metric the backend did not compute returns the empty
 *  estimate — which renders "not measured", never zero. */
export function metricEstimate(
  dataset: DatasetReliabilityResponse | null | undefined,
  id: ReliabilityMetricId
): IntervalEstimate {
  const m = metric(dataset, id);
  if (!m) return orEmptyEstimate(null);
  return intervalToEstimate(m.value, m.interval, m.n);
}

/** The metric row itself, for its label/question/unit/unreplicated fields. */
export function metric(
  dataset: DatasetReliabilityResponse | null | undefined,
  id: ReliabilityMetricId
): ReliabilityMetric | undefined {
  return dataset?.metrics?.find((m) => m.metric === id);
}

/** A case's pass rate with its Wilson interval. `n` is rollouts. */
export function casePassEstimate(c: CaseReliabilityResponse): IntervalEstimate {
  return intervalToEstimate(c.passRate, c.passRateInterval, c.rollouts);
}

/** A case's mean judge score over its SCORED rollouts. No interval — the
 *  backend does not compute one per case — so the estimate carries the
 *  rollout count and an explicit "no interval", never a fabricated bound. */
export function caseMeanScoreEstimate(c: CaseReliabilityResponse): IntervalEstimate {
  return intervalToEstimate(c.meanScore, null, c.scoredRollouts);
}

/**
 * True when the case's rollouts disagreed with each other (0 < successes <
 * rollouts). Flakiness is the single most actionable signal on this screen:
 * it is a case whose verdict depends on the coin, not on the agent.
 */
export function isCaseFlaky(c: CaseReliabilityResponse): boolean {
  return c.successes > 0 && c.successes < c.rollouts;
}

/**
 * Rollouts that produced no judge score — an ERRORed (or UNAVAILABLE) replay
 * counts as a failed rollout for the pass rate but contributes no score.
 * Missing data, not a clean failure: the table labels them separately.
 */
export function caseUnscoredRollouts(c: CaseReliabilityResponse): number {
  return Math.max(0, (c.rollouts || 0) - (c.scoredRollouts || 0));
}

/** The paired gap of a comparison as an IntervalEstimate — signed, so the
 *  interval can legitimately contain zero, which is exactly what the
 *  "cannot tell" verdict is made of. `n` is the paired-case count. */
export function gapEstimate(comparison: PairedComparisonResponse | null | undefined): IntervalEstimate {
  if (!comparison) return orEmptyEstimate(null);
  return intervalToEstimate(comparison.meanDelta, comparison.deltaInterval, comparison.pairedCases);
}

// ---------------------------------------------------------------------------
// Pure presentation + interpretation helpers
//
// These live next to the wire types on purpose: they are the rules that keep
// the screen honest, they are the part most likely to be reused by whoever
// later updates AgentQualityPanel, and they are pure functions that a unit
// test can pin without mounting React.
// ---------------------------------------------------------------------------

const EMPTY_ESTIMATE: IntervalEstimate = {
  value: null,
  ciLow: null,
  ciHigh: null,
  rollouts: 0,
  method: null,
  confidenceLevel: null,
};

/** Null-safe accessor so callers can render an absent estimate without a
 *  cascade of `?.` and without inventing zeros. */
export function orEmptyEstimate(estimate: IntervalEstimate | null | undefined): IntervalEstimate {
  return estimate || EMPTY_ESTIMATE;
}

/** A score/rate as a bare 2dp decimal. Em dash for "no data" — never 0.00,
 *  which would read as a measured zero. */
export function formatValue(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(Number(value))) return "—";
  return Number(value).toFixed(2);
}

/** A signed 2dp decimal, for gaps where direction is the whole point. */
export function formatSigned(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(Number(value))) return "—";
  const n = Number(value);
  // U+2212 MINUS SIGN rather than a hyphen: these sit in tabular-nums columns
  // next to "+" and a hyphen is visually half the width.
  return `${n >= 0 ? "+" : "−"}${Math.abs(n).toFixed(2)}`;
}

/** True when there is an interval to show at all. */
export function hasInterval(estimate: IntervalEstimate | null | undefined): boolean {
  const e = orEmptyEstimate(estimate);
  return e.ciLow !== null && e.ciLow !== undefined && e.ciHigh !== null && e.ciHigh !== undefined;
}

/**
 * The n=1 test. An estimate with one observation (or none) is not replicated
 * and must never be presented as a measurement — `rollouts <= 1` is the whole
 * rule, deliberately independent of whether an interval happens to be
 * present, because a "Wilson interval on one trial" is still one trial.
 */
export function isUnreplicated(estimate: IntervalEstimate | null | undefined): boolean {
  const e = orEmptyEstimate(estimate);
  return e.value !== null && e.value !== undefined && (e.rollouts || 0) <= 1;
}

/** The canonical string form: `0.62 [0.48–0.75], n=5`. Degrades in exactly
 *  two documented ways — no interval drops the bracket, no n drops the tail —
 *  so a partially populated estimate never renders a fabricated bound. */
export function formatEstimate(estimate: IntervalEstimate | null | undefined): string {
  const e = orEmptyEstimate(estimate);
  const head = formatValue(e.value);
  if (head === "—") return "—";
  const interval = hasInterval(e) ? ` [${formatValue(e.ciLow)}–${formatValue(e.ciHigh)}]` : "";
  const n = (e.rollouts || 0) > 0 ? `, n=${e.rollouts}` : "";
  return `${head}${interval}${n}`;
}

/** The interval's width, or null when there is no interval. Used to rank
 *  "which of these numbers do I trust least". */
export function intervalWidth(estimate: IntervalEstimate | null | undefined): number | null {
  const e = orEmptyEstimate(estimate);
  if (!hasInterval(e)) return null;
  return Number(e.ciHigh) - Number(e.ciLow);
}

/** True when a signed estimate's interval contains zero — i.e. the data is
 *  consistent with no difference at all. */
export function straddlesZero(estimate: IntervalEstimate | null | undefined): boolean {
  const e = orEmptyEstimate(estimate);
  if (!hasInterval(e)) return false;
  return Number(e.ciLow) <= 0 && Number(e.ciHigh) >= 0;
}

// ---------------------------------------------------------------------------
// Paired-comparison adjudication
// ---------------------------------------------------------------------------

/**
 * Why a comparison is being shown the way it is. The console's own verdict
 * vocabulary has four states; the backend's ComparisonVerdict has five —
 * CANNOT_TELL and INSIDE_NOISE_FLOOR both map onto INDISTINGUISHABLE, with
 * `straddlesZero` / `withinNoiseFloor` on the reading saying which reason
 * applies. INSUFFICIENT_DATA and INDISTINGUISHABLE are separate states on
 * purpose: the first means "run more", the second means "stop — this
 * difference is not real".
 */
export type PairedVerdict =
  | "CANDIDATE_BETTER"
  | "BASELINE_BETTER"
  | "INDISTINGUISHABLE"
  | "INSUFFICIENT_DATA";

export type ComparisonState = PairedVerdict;

export interface ComparisonReading {
  state: ComparisonState;
  /** One sentence naming the actual reason, for the UI to print verbatim.
   *  When the server verdict is adopted this is the backend's own
   *  `explanation`; when a client guard overrode it, the console composes
   *  its own so the downgrade is legible. */
  reason: string;
  /** True when the backend claimed a directional winner and this function
   *  refused it. Surfaced so the UI can label the downgrade rather than
   *  silently contradicting the API. */
  downgradedFromServerVerdict: boolean;
  /** Which guard fired, when one did. */
  straddlesZero: boolean;
  withinNoiseFloor: boolean;
}

/** The console vocabulary each backend verdict lands on. The two non-
 *  directional backend verdicts are kept distinct below by the flags, which
 *  the backend's own numbers recompute anyway. */
const SERVER_VERDICT_STATE: Record<ReliabilityComparisonVerdict, ComparisonState> = {
  VARIANT_BETTER: "CANDIDATE_BETTER",
  BASELINE_BETTER: "BASELINE_BETTER",
  CANNOT_TELL: "INDISTINGUISHABLE",
  INSIDE_NOISE_FLOOR: "INDISTINGUISHABLE",
  INSUFFICIENT_DATA: "INSUFFICIENT_DATA",
};

/**
 * Decides what a paired comparison is allowed to claim.
 *
 * The backend computes its own verdict and its own one-sentence
 * `explanation`, and this trusts both — EXCEPT that two client-side guards
 * can only ever weaken a directional claim, never create one:
 *
 *   - the gap's interval straddles zero, or
 *   - |meanDelta| is at or below the noise floor the gap was tested against.
 *
 * Either of those downgrades VARIANT_BETTER/BASELINE_BETTER to
 * INDISTINGUISHABLE. This is a deliberate safety rail rather than duplicated
 * business logic: the console is the thing a customer reads, and a backend
 * that forgets the check must not be able to make this screen declare a
 * winner that the numbers on the same screen contradict. The guards never
 * promote, so they cannot invent a result either — a backend CANNOT_TELL can
 * never be talked into a winner here.
 */
export function classifyComparison(
  comparison: PairedComparisonResponse | null | undefined
): ComparisonReading {
  if (!comparison) {
    return {
      state: "INSUFFICIENT_DATA",
      reason: "No comparison has been computed yet.",
      downgradedFromServerVerdict: false,
      straddlesZero: false,
      withinNoiseFloor: false,
    };
  }

  const gap = gapEstimate(comparison);
  const zero = straddlesZero(gap);
  const floor = comparison.noiseFloorTested;
  const withinFloor =
    floor !== null &&
    floor !== undefined &&
    Number.isFinite(Number(floor)) &&
    gap.value !== null &&
    gap.value !== undefined &&
    Math.abs(Number(gap.value)) <= Number(floor);

  const serverClaimedWinner =
    comparison.verdict === "VARIANT_BETTER" || comparison.verdict === "BASELINE_BETTER";

  const reading = (
    state: ComparisonState,
    reason: string,
    downgraded: boolean
  ): ComparisonReading => ({
    state,
    reason,
    downgradedFromServerVerdict: downgraded,
    straddlesZero: zero,
    withinNoiseFloor: withinFloor,
  });

  // Nothing to classify: no paired cases, or no point estimate at all.
  if (!comparison.pairedCases || gap.value === null || gap.value === undefined) {
    return reading(
      "INSUFFICIENT_DATA",
      comparison.explanation ||
        (comparison.pairedCases === 0
          ? "These two runs share no cases, so there is nothing to pair."
          : "The backend has no paired gap for these runs yet."),
      false
    );
  }

  // The backend's own `unreplicated` flag: a single rollout per case cannot
  // support a difference claim at all — with n=1 the "gap" is one coin flip
  // minus another coin flip.
  if (comparison.unreplicated) {
    return reading(
      "INSUFFICIENT_DATA",
      comparison.explanation ||
        "At least one case had a single rollout, so run-to-run variance is unmeasured — " +
          "this difference cannot be distinguished from noise at n=1.",
      serverClaimedWinner
    );
  }

  if (zero || withinFloor) {
    const parts: string[] = [];
    if (zero) {
      parts.push(
        `the paired gap interval [${formatSigned(gap.ciLow)} to ${formatSigned(gap.ciHigh)}] contains zero`
      );
    }
    if (withinFloor) {
      parts.push(
        `the gap of ${formatSigned(gap.value)} is inside this tenant's measured noise floor of ${formatValue(floor)}`
      );
    }
    const reason = serverClaimedWinner
      ? `Cannot tell which is better: ${parts.join(", and ")}.`
      : comparison.explanation || `Cannot tell which is better: ${parts.join(", and ")}.`;
    return reading("INDISTINGUISHABLE", reason, serverClaimedWinner);
  }

  // No guard fired. Adopt the server's verdict when it gave one, and print
  // its own explanation verbatim — the server generated that sentence so the
  // UI, the API and the logs cannot tell three different stories about the
  // same delta.
  if (comparison.verdict) {
    return reading(
      SERVER_VERDICT_STATE[comparison.verdict] || "INSUFFICIENT_DATA",
      comparison.explanation || reasonForDirection(comparison, gap),
      false
    );
  }

  return reading(
    Number(gap.value) > 0 ? "CANDIDATE_BETTER" : "BASELINE_BETTER",
    reasonForDirection(comparison, gap),
    false
  );
}

function reasonForDirection(comparison: PairedComparisonResponse, gap: IntervalEstimate): string {
  const direction = Number(gap.value) > 0 ? "candidate scores higher" : "baseline scores higher";
  const interval = hasInterval(gap)
    ? `interval [${formatSigned(gap.ciLow)} to ${formatSigned(gap.ciHigh)}] excludes zero`
    : "no interval was returned, so treat the direction as provisional";
  const floorClause =
    comparison.noiseFloorTested === null || comparison.noiseFloorTested === undefined
      ? "; note this tenant has no measured noise floor yet, so the gap has not been checked against one"
      : ` and the gap exceeds the measured noise floor of ${formatValue(comparison.noiseFloorTested)}`;
  return `The ${direction} over ${comparison.pairedCases} paired case${
    comparison.pairedCases === 1 ? "" : "s"
  }: ${interval}${floorClause}.`;
}
