// Pure aggregation helpers. No I/O, no clock: everything here is unit-tested offline.

/** Nearest-rank percentile (p in 0..100) of a numeric array; null for an empty array. */
export function percentile(values, p) {
  const xs = values.filter((v) => typeof v === "number" && Number.isFinite(v)).sort((a, b) => a - b);
  if (xs.length === 0) return null;
  if (p <= 0) return xs[0];
  if (p >= 100) return xs[xs.length - 1];
  const rank = Math.ceil((p / 100) * xs.length); // 1-based nearest rank
  return xs[rank - 1];
}

export function mean(values) {
  const xs = values.filter((v) => typeof v === "number" && Number.isFinite(v));
  return xs.length === 0 ? null : xs.reduce((a, b) => a + b, 0) / xs.length;
}

export function sum(values) {
  return values.reduce((a, b) => a + (typeof b === "number" && Number.isFinite(b) ? b : 0), 0);
}

/** Round to n decimals, passing null through. */
export function round(v, n = 4) {
  if (v === null || v === undefined) return null;
  const f = 10 ** n;
  return Math.round(v * f) / f;
}

/**
 * Aggregate scored runs. A "run" is one execution of one task:
 *   { taskId, category, success, latencyMs, tokens, costUsd, costKnown,
 *     tool: null | {recall, precision, exact}, guardrail: null | {pass},
 *     error }
 * `null` fields mean "not applicable / not reported" and are excluded from the
 * relevant denominator, never counted as zero or as a pass.
 */
export function aggregate(runs) {
  const n = runs.length;
  const ok = runs.filter((r) => r.success === true).length;
  const errored = runs.filter((r) => r.error).length;
  const toolRuns = runs.filter((r) => r.tool);
  const guardRuns = runs.filter((r) => r.guardrail);
  const lat = runs.map((r) => r.latencyMs);
  const tokenRuns = runs.filter((r) => typeof r.tokens === "number");
  const costRuns = runs.filter((r) => r.costKnown === true && typeof r.costUsd === "number");
  return {
    runs: n,
    successes: ok,
    successRate: n === 0 ? null : round(ok / n),
    errors: errored,
    latencyMs: { p50: percentile(lat, 50), p95: percentile(lat, 95), mean: round(mean(lat), 1) },
    tokens: {
      reportedRuns: tokenRuns.length,
      total: sum(tokenRuns.map((r) => r.tokens)),
      meanPerRun: round(mean(tokenRuns.map((r) => r.tokens)), 1),
    },
    costUsd: {
      pricedRuns: costRuns.length,
      unpricedRuns: n - costRuns.length,
      total: round(sum(costRuns.map((r) => r.costUsd)), 6),
      meanPerPricedRun: round(mean(costRuns.map((r) => r.costUsd)), 6),
    },
    toolAccuracy: {
      scoredRuns: toolRuns.length,
      recall: round(mean(toolRuns.map((r) => r.tool.recall))),
      precision: round(mean(toolRuns.map((r) => r.tool.precision))),
      exactRate: toolRuns.length === 0 ? null : round(toolRuns.filter((r) => r.tool.exact).length / toolRuns.length),
    },
    guardrail: {
      scoredRuns: guardRuns.length,
      passRate: guardRuns.length === 0 ? null : round(guardRuns.filter((r) => r.guardrail.pass).length / guardRuns.length),
    },
  };
}

/** Overall plus a per-category breakdown, categories sorted for stable output. */
export function aggregateByCategory(runs) {
  const cats = [...new Set(runs.map((r) => r.category))].sort();
  const byCategory = {};
  for (const c of cats) byCategory[c] = aggregate(runs.filter((r) => r.category === c));
  return { overall: aggregate(runs), byCategory };
}
