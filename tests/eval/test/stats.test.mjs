import test from "node:test";
import assert from "node:assert/strict";
import { percentile, mean, aggregate, aggregateByCategory, round } from "../lib/stats.mjs";

test("percentile is nearest-rank and ignores non-numbers", () => {
  const xs = [15, 20, 35, 40, 50];
  assert.equal(percentile(xs, 50), 35);
  assert.equal(percentile(xs, 95), 50);
  assert.equal(percentile(xs, 0), 15);
  assert.equal(percentile(xs, 100), 50);
  assert.equal(percentile([null, undefined, NaN, 7], 50), 7);
  assert.equal(percentile([], 50), null);
  const hundred = Array.from({ length: 100 }, (_, i) => i + 1);
  assert.equal(percentile(hundred, 95), 95);
  assert.equal(percentile(hundred, 50), 50);
});

test("percentile does not mutate its input", () => {
  const xs = [3, 1, 2];
  percentile(xs, 50);
  assert.deepEqual(xs, [3, 1, 2]);
});

test("mean and round handle empties and nulls", () => {
  assert.equal(mean([]), null);
  assert.equal(mean([1, 2, null, 3]), 2);
  assert.equal(round(null), null);
  assert.equal(round(1.23456, 2), 1.23);
});

const run = (o) => ({ taskId: "t", category: "qa", success: true, latencyMs: 100, tokens: 10, costUsd: 0.01, costKnown: true, tool: null, guardrail: null, error: null, ...o });

test("aggregate: success rate, latency, tokens, cost", () => {
  const a = aggregate([run({ latencyMs: 100 }), run({ success: false, latencyMs: 300, tokens: 30, costUsd: 0.03 }), run({ latencyMs: 200, tokens: 20, costUsd: 0.02 })]);
  assert.equal(a.runs, 3);
  assert.equal(a.successes, 2);
  assert.equal(a.successRate, 0.6667);
  assert.equal(a.latencyMs.p50, 200);
  assert.equal(a.latencyMs.p95, 300);
  assert.equal(a.tokens.total, 60);
  assert.equal(a.tokens.meanPerRun, 20);
  assert.equal(a.costUsd.total, 0.06);
  assert.equal(a.costUsd.meanPerPricedRun, 0.02);
});

test("aggregate: unreported tokens and unpriced runs stay out of the denominators (never zero)", () => {
  const a = aggregate([run({ tokens: null, costUsd: null, costKnown: false }), run({ tokens: 40, costUsd: 0.04 }), run({ tokens: 20, costUsd: 0.5, costKnown: false })]);
  assert.equal(a.tokens.reportedRuns, 2);
  assert.equal(a.tokens.meanPerRun, 30);
  assert.equal(a.costUsd.pricedRuns, 1);
  assert.equal(a.costUsd.unpricedRuns, 2);
  assert.equal(a.costUsd.total, 0.04);
});

test("aggregate: tool accuracy and guardrail only over applicable runs", () => {
  const a = aggregate([
    run({ tool: { recall: 1, precision: 1, exact: true } }),
    run({ tool: { recall: 0.5, precision: 1, exact: false } }),
    run({}),
    run({ guardrail: { pass: true } }),
    run({ guardrail: { pass: false } }),
    run({ guardrail: { pass: true } }),
  ]);
  assert.equal(a.toolAccuracy.scoredRuns, 2);
  assert.equal(a.toolAccuracy.recall, 0.75);
  assert.equal(a.toolAccuracy.precision, 1);
  assert.equal(a.toolAccuracy.exactRate, 0.5);
  assert.equal(a.guardrail.scoredRuns, 3);
  assert.equal(a.guardrail.passRate, 0.6667);
});

test("aggregate of nothing reports null, not zero", () => {
  const a = aggregate([]);
  assert.equal(a.successRate, null);
  assert.equal(a.latencyMs.p50, null);
  assert.equal(a.toolAccuracy.exactRate, null);
  assert.equal(a.guardrail.passRate, null);
});

test("aggregate counts errored runs and treats them as not successful", () => {
  const a = aggregate([run({ success: false, error: "boom" }), run()]);
  assert.equal(a.errors, 1);
  assert.equal(a.successRate, 0.5);
});

test("aggregateByCategory splits and sorts categories", () => {
  const r = aggregateByCategory([run({ category: "workflow" }), run({ category: "qa", success: false }), run({ category: "qa" })]);
  assert.deepEqual(Object.keys(r.byCategory), ["qa", "workflow"]);
  assert.equal(r.byCategory.qa.successRate, 0.5);
  assert.equal(r.byCategory.workflow.successRate, 1);
  assert.equal(r.overall.runs, 3);
});
