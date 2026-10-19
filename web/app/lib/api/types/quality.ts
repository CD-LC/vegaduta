// Agent Quality / evals dashboard (QualityController, core/quality) - PREMIUM+
// only. Response shapes mirror the backend records exactly (see
// QualitySummaryResponse/QualityHistoryResponse/QualityStatisticsResponse in
// core/quality/src/main/java/.../dto and QualityController's own
// QualityConfigResponse record) - see getAgentQuality* below for the field
// naming quirk between summary and history's scoresByType keys.
export interface QualitySummaryResponse {
  agentId: string;
  overallScore: number;
  // Keyed by EvaluationType.name() (e.g. "HELPFULNESS", "SAFETY") - see
  // AgentQualityRecord's own javadoc. NOT the display name.
  scoresByType: Record<string, number>;
  feedbackByType: Record<string, string>;
  turnNumber: number;
  evaluatedAt: string;
}

export interface QualityHistoryResponse {
  agentId: string;
  timestamps: string[];
  overallScores: number[];
  // Unlike QualitySummaryResponse.scoresByType, this is keyed by
  // EvaluationType.getDisplayName() (e.g. "Helpfulness") - QualityController#
  // getHistory builds this map differently than the summary/record path.
  scoresByType: Record<string, number[]>;
  recordCount: number;
}

export interface QualityStatisticsResponse {
  agentId: string;
  totalEvaluations: number;
  // null when the agent has no evaluation records yet - render "no data",
  // never a fabricated 0/100 (see the backend record's own javadoc).
  averageQualityScore: number | null;
  averageScoresByType: Record<string, number>;
  generatedAt: string;
}

export type QualityEvaluationType = "HELPFULNESS" | "ACCURACY" | "TONE" | "LENGTH" | "RELEVANCE" | "SAFETY";

export interface QualityConfigResponse {
  agentId: string;
  enabledEvaluations: QualityEvaluationType[];
  planTier: string;
}

// Eval RELIABILITY statistics (EvalReliabilityController + ReliabilityDtos,
// core/quality/src/main/java/.../quality/stats) - the same judge output as the
// four QualityController endpoints above, but reported as a measurement:
// every number arrives with the sample size behind it, what that sample size
// COUNTS, its interval (or an explicit null), which estimator produced the
// interval, and an `unreplicated` flag when it rests on single observations.
//
// The types below are transcribed field-for-field from those Java records
// rather than designed here, deliberately: the backend's javadoc is where the
// statistical contract is argued, and a web-side rename would make the two
// impossible to diff. Two rules from it that matter to every caller:
//   - A null `interval` means "not computable from this data" (one case, one
//     rollout). It NEVER means a zero-width interval, and a console that
//     renders it as one is claiming infinite precision.
//   - A null `noiseFloor` means the floor has not been measured for that
//     dataset, which is NOT a floor of zero. "Unmeasured" must not be
//     rendered as "the bar is zero, so any gap clears it".
// Unit of analysis is per-DATASET, pooled over that dataset's recent
// SUCCEEDED runs (each run contributes at most one rollout per case) - not
// per-run. See getEvalReliability below.

/** Interval method names - constants of core/quality's IntervalMethod enum. */
export type ReliabilityIntervalMethod =
  "WILSON_SCORE" | "PERCENTILE_BOOTSTRAP" | "HIERARCHICAL_BOOTSTRAP" | "PAIRED_BOOTSTRAP";

/** Metric ids - constants of core/quality's ReliabilityMetricId enum. */
export type ReliabilityMetricId = "PASS_AT_1" | "PASS_AT_K" | "PASS_HAT_K" | "MEAN_SCORE";

/** What a reported `n` counts - constants of core/quality's SampleUnit enum. */
export type ReliabilitySampleUnit = "ROLLOUT" | "CASE" | "CASE_PAIR";

/** Verdict constants of core/quality's ComparisonVerdict enum. Only the first
 *  two may be rendered as a winner - the controller's javadoc is explicit that
 *  CANNOT_TELL / INSIDE_NOISE_FLOOR / INSUFFICIENT_DATA are answers, not
 *  failures, and must not be dressed up as a direction. */
export type ReliabilityComparisonVerdict =
  "VARIANT_BETTER" | "BASELINE_BETTER" | "CANNOT_TELL" | "INSIDE_NOISE_FLOOR" | "INSUFFICIENT_DATA";

export interface ReliabilityInterval {
  low: number;
  high: number;
  confidenceLevel: number;
  method: ReliabilityIntervalMethod;
}

export interface ReliabilityMetric {
  metric: ReliabilityMetricId;
  /** Short column label, server-supplied (e.g. "pass^k"). */
  label: string;
  /** The plain-English question this metric answers - shipped from the server
   *  so every surface tells the same story about what the number means. */
  question: string;
  /** null when the metric could not be measured at all. */
  value: number | null;
  /** null when no interval is computable from this data. Not a zero width. */
  interval: ReliabilityInterval | null;
  n: number;
  unit: ReliabilitySampleUnit;
  /** null for metrics where an attempt count is meaningless. */
  k: number | null;
  unreplicated: boolean;
}

export interface CaseReliabilityResponse {
  /** eval_dataset_case id when the result row carried one, else "pos:<n>". */
  caseKey: string;
  userMessage: string;
  rollouts: number;
  successes: number;
  passRate: number;
  passRateInterval: ReliabilityInterval | null;
  allPassed: boolean;
  unreplicated: boolean;
  /** null when no rollout of this case produced a judge score. */
  meanScore: number | null;
  /** Can be lower than `rollouts`: an ERRORed replay is a failed rollout that
   *  produces no score. */
  scoredRollouts: number;
}

export interface NoiseFloorResponse {
  firstRunId: string;
  secondRunId: string;
  secondRunFinishedAt: string;
  pairedCases: number;
  meanAbsoluteDelta: number;
  maxAbsoluteDelta: number;
  /** The bar an A/B effect must exceed. */
  floor: number;
  meanAbsoluteDeltaInterval: ReliabilityInterval | null;
  signedMeanDelta: number;
  signedMeanDeltaInterval: ReliabilityInterval | null;
  /** True when the signed interval excludes zero: two supposedly identical
   *  reruns differ systematically, so this is drift evidence rather than a
   *  clean floor and the console must say so. */
  configurationSuspect: boolean;
}

export interface DatasetReliabilityResponse {
  datasetId: string;
  datasetName: string;
  /** Contributing runs, newest first. */
  runIds: string[];
  runsConsidered: number;
  caseCount: number;
  minRolloutsPerCase: number;
  maxRolloutsPerCase: number;
  /** The attempt count pass@k / pass^k were computed at - the MINIMUM rollout
   *  count across cases, the largest k every case can honestly support. */
  k: number;
  unreplicated: boolean;
  /** pass@1 - pass^k: how much of the headline rate does not survive the
   *  requirement that it hold k times running. */
  reliabilityGap: number;
  metrics: ReliabilityMetric[];
  cases: CaseReliabilityResponse[];
  /** null when fewer than two runs exist to measure a floor from. */
  noiseFloor: NoiseFloorResponse | null;
  /** Machine-generated caveats that MATERIALLY limit the numbers above them
   *  (ragged replication, judge-model drift between runs, changed pass
   *  threshold). Never decorative - if one is present it must be shown. */
  warnings: string[];
}

/**
 * The whole eval-reliability report for one agent. Named EvalReliabilityReport
 * rather than after the Java record (AgentReliabilityResponse) purely to avoid
 * a collision with AgentWorkbench's AgentReliabilitySummary, which is the
 * UNRELATED run-health summary behind GET /api/agents/{id}/reliability
 * (totalCalls / failedCalls / avgResponseTimeMs). Two different endpoints, two
 * different meanings of "reliability" - one is uptime, this one is statistics.
 */
export interface EvalReliabilityReport {
  agentId: string;
  generatedAt: string;
  confidenceLevel: number;
  maxRunsPerDataset: number;
  /** One entry per dataset with at least one SUCCEEDED run, newest-first by
   *  most recent run. Empty (with `warnings` populated) is a 200, not a 404:
   *  "nothing is replicated yet" is a state to render, not an error. */
  datasets: DatasetReliabilityResponse[];
  warnings: string[];
}

/**
 * The noise floor for ONE dataset, addressable on its own
 * (DatasetNoiseFloorResponse on the backend) - exists so a surface printing
 * "this suite's floor" beside an A/B does not have to fetch and discard a
 * whole reliability report.
 */
export interface DatasetNoiseFloorResponse {
  datasetId: string;
  datasetName: string;
  /** null when the floor could not be measured (fewer than two SUCCEEDED
   *  runs, or no case scorable in both). NOT a floor of zero. */
  noiseFloor: NoiseFloorResponse | null;
  /** Why the floor is absent or suspect, when it is. */
  warnings: string[];
}

export interface PairedComparisonResponse {
  datasetId: string;
  baselineRunId: string;
  variantRunId: string;
  pairedCases: number;
  baselineMean: number;
  variantMean: number;
  /** Mean per-case variant - baseline; positive favours the variant. */
  meanDelta: number;
  /** null with fewer than two paired cases. */
  deltaInterval: ReliabilityInterval | null;
  verdict: ReliabilityComparisonVerdict;
  /** The floor the effect was tested against. null = no independent floor was
   *  available, and null is NOT a pass. */
  noiseFloorTested: number | null;
  casesVariantBetter: number;
  casesBaselineBetter: number;
  casesTied: number;
  /** Exact two-sided sign test over discordant cases; null when there are none. */
  signTestPValue: number | null;
  unreplicated: boolean;
  /** One server-generated sentence stating the verdict and its reason, so the
   *  UI, the API and the logs cannot tell three different stories about the
   *  same delta. Render it verbatim rather than paraphrasing it. */
  explanation: string;
  warnings: string[];
}
