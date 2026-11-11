// Result document + markdown table. Pure functions.
import { aggregateByCategory } from "./stats.mjs";

const fmt = (v, d = 2) => (v === null || v === undefined ? "n/a" : Number(v).toFixed(d));
const pct = (v) => (v === null || v === undefined ? "n/a" : `${(v * 100).toFixed(1)}%`);
const ms = (v) => (v === null || v === undefined ? "n/a" : `${Math.round(v)}`);

/**
 * Build the machine-readable results document.
 * `meta` is provenance (system under test, suite version, timestamps, repeats...). It never contains
 * credentials; the runner passes only non-secret values.
 */
export function buildResults(meta, runs) {
  const { overall, byCategory } = aggregateByCategory(runs);
  return {
    schema: "vegaduta-eval-results/1",
    meta,
    overall,
    byCategory,
    runs: runs.map((r) => ({
      taskId: r.taskId,
      category: r.category,
      success: r.success,
      latencyMs: r.latencyMs,
      tokens: r.tokens,
      costUsd: r.costUsd,
      costKnown: r.costKnown,
      tool: r.tool,
      guardrail: r.guardrail,
      error: r.error,
      failures: r.failures,
    })),
  };
}

function row(label, a) {
  const cost = a.costUsd.pricedRuns === 0 ? "n/a" : `$${fmt(a.costUsd.meanPerPricedRun, 5)}${a.costUsd.unpricedRuns ? ` (${a.costUsd.unpricedRuns} unpriced)` : ""}`;
  return `| ${label} | ${a.runs} | ${pct(a.successRate)} | ${pct(a.toolAccuracy.exactRate)} | ${fmt(a.toolAccuracy.recall)} / ${fmt(a.toolAccuracy.precision)} | ${pct(a.guardrail.passRate)} | ${ms(a.latencyMs.p50)} | ${ms(a.latencyMs.p95)} | ${fmt(a.tokens.meanPerRun, 0)} | ${cost} |`;
}

/** Markdown summary table plus the per-task failures, with the mandatory honesty header. */
export function renderMarkdown(results) {
  const m = results.meta;
  const lines = [];
  lines.push(`# Evaluation results: ${m.system}`);
  lines.push("");
  lines.push(`- Suite: \`${m.suite.name}\` v${m.suite.version} (${m.suite.taskCount} tasks, ${m.repeats} repeat(s) each)`);
  lines.push(`- Started: ${m.startedAt}; finished: ${m.finishedAt}`);
  lines.push(`- Target: ${m.target}`);
  lines.push(`- Model/provider under test: ${m.model ?? "not recorded"}`);
  lines.push(`- Harness commit: ${m.harnessCommit ?? "not recorded"}`);
  lines.push("");
  lines.push("Success is decided by programmatic checks, not an LLM judge. Guardrail refusal checks are regex heuristics. n/a means not applicable or not reported by the system, never zero.");
  lines.push("");
  lines.push("| Scope | Runs | Success | Tool exact | Tool recall / precision | Guardrail | p50 ms | p95 ms | Tokens/run | Cost/run |");
  lines.push("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|");
  lines.push(row("**overall**", results.overall));
  for (const [c, a] of Object.entries(results.byCategory)) lines.push(row(c, a));
  const failed = results.runs.filter((r) => !r.success);
  lines.push("");
  if (failed.length === 0) lines.push("No failing runs.");
  else {
    lines.push(`## Failing runs (${failed.length})`);
    lines.push("");
    for (const r of failed) lines.push(`- \`${r.taskId}\`: ${(r.failures ?? []).join("; ") || "failed"}`);
  }
  lines.push("");
  return lines.join("\n");
}
