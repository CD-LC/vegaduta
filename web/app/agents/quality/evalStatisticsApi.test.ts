// Tests for the honesty rules behind /agents/quality's statistics section.
//
// These are not decoration. The rules this file pins are the entire reason
// EvalStatisticsSection exists, and every one of them is a rule about what
// the console is FORBIDDEN to say:
//
//   1. a score renders with its repeat count and interval, and degrades in
//      exactly two documented ways (no interval -> no bracket; no n -> no
//      tail). It never invents a bound and never prints 0.00 for "unknown".
//   2. n<=1 is `unreplicated`, independent of whether an interval happens to
//      have been returned alongside it.
//   3. classifyComparison's two guards can only ever WEAKEN a directional
//      claim, never create one — so a backend that forgets its own check
//      cannot make the console declare a winner, and a backend that returns
//      CANNOT_TELL can never be talked into a winner by the client.
//   4. the adapters carry the backend's records field-for-field — a null
//      interval stays null (never a zero-width one), n stays the server's
//      own sample size, the estimator name crosses verbatim.
//
// Pure functions, so no React, no jsdom work and no mocking: the module is
// imported directly. (It does import app/lib/api.ts for the feature-detection
// helpers, which is why the fixtures below are plain data and no test here
// touches resolveEvalStatsApi.)

import { describe, expect, it } from "vitest";
import type { PairedComparisonResponse, ReliabilityInterval } from "../../lib/api";
import {
  casePassEstimate,
  caseUnscoredRollouts,
  classifyComparison,
  formatEstimate,
  formatSigned,
  formatValue,
  gapEstimate,
  hasInterval,
  intervalToEstimate,
  intervalWidth,
  isCaseFlaky,
  isUnreplicated,
  metricEstimate,
  orEmptyEstimate,
  straddlesZero,
  type IntervalEstimate,
} from "./evalStatisticsApi";

function estimate(partial: Partial<IntervalEstimate>): IntervalEstimate {
  return {
    value: null,
    ciLow: null,
    ciHigh: null,
    rollouts: 0,
    method: null,
    confidenceLevel: null,
    ...partial,
  };
}

function interval(low: number, high: number): ReliabilityInterval {
  return { low, high, confidenceLevel: 0.95, method: "PAIRED_BOOTSTRAP" };
}

function comparison(partial: Partial<PairedComparisonResponse>): PairedComparisonResponse {
  return {
    datasetId: "ds-1",
    baselineRunId: "run-a",
    variantRunId: "run-b",
    pairedCases: 20,
    baselineMean: 0.6,
    variantMean: 0.63,
    meanDelta: 0,
    deltaInterval: null,
    verdict: "CANNOT_TELL",
    noiseFloorTested: null,
    casesVariantBetter: 0,
    casesBaselineBetter: 0,
    casesTied: 20,
    signTestPValue: null,
    unreplicated: false,
    explanation: "",
    warnings: [],
    ...partial,
  };
}

describe("formatEstimate — the canonical score form", () => {
  it("renders point estimate, interval and repeat count", () => {
    // The exact form the build agenda specifies.
    expect(
      formatEstimate(estimate({ value: 0.62, ciLow: 0.48, ciHigh: 0.75, rollouts: 5 }))
    ).toBe("0.62 [0.48–0.75], n=5");
  });

  it("drops the bracket rather than inventing a zero-width interval", () => {
    expect(formatEstimate(estimate({ value: 0.62, rollouts: 5 }))).toBe("0.62, n=5");
  });

  it("drops the repeat tail when the backend sent no rollout count", () => {
    expect(formatEstimate(estimate({ value: 0.62, ciLow: 0.5, ciHigh: 0.7 }))).toBe("0.62 [0.50–0.70]");
  });

  it("renders an em dash for absent data, never 0.00", () => {
    expect(formatEstimate(estimate({}))).toBe("—");
    expect(formatEstimate(null)).toBe("—");
    expect(formatValue(null)).toBe("—");
    // A real measured zero still prints as a number — the point is that
    // "unknown" and "zero" are different strings.
    expect(formatValue(0)).toBe("0.00");
  });

  it("signs gaps with a true minus sign so columns align", () => {
    expect(formatSigned(0.04)).toBe("+0.04");
    expect(formatSigned(-0.04)).toBe("−0.04");
    expect(formatSigned(null)).toBe("—");
  });
});

describe("the n=1 rule", () => {
  it("flags a single-rollout estimate as unreplicated even with an interval", () => {
    // A Wilson interval computed on one trial is still one trial.
    expect(isUnreplicated(estimate({ value: 0.8, ciLow: 0.3, ciHigh: 1, rollouts: 1 }))).toBe(true);
  });

  it("does not flag a replicated estimate", () => {
    expect(isUnreplicated(estimate({ value: 0.8, rollouts: 5 }))).toBe(false);
  });

  it("does not flag an absent estimate — there is no score to badge", () => {
    expect(isUnreplicated(estimate({ rollouts: 0 }))).toBe(false);
    expect(isUnreplicated(null)).toBe(false);
  });
});

describe("interval predicates", () => {
  it("detects presence and width", () => {
    expect(hasInterval(estimate({ value: 0.5 }))).toBe(false);
    expect(hasInterval(estimate({ value: 0.5, ciLow: 0.4, ciHigh: 0.6 }))).toBe(true);
    expect(intervalWidth(estimate({ value: 0.5, ciLow: 0.4, ciHigh: 0.6 }))).toBeCloseTo(0.2, 10);
    expect(intervalWidth(estimate({ value: 0.5 }))).toBeNull();
  });

  it("detects an interval that contains zero", () => {
    expect(straddlesZero(estimate({ value: 0.03, ciLow: -0.02, ciHigh: 0.08 }))).toBe(true);
    expect(straddlesZero(estimate({ value: 0.09, ciLow: 0.04, ciHigh: 0.14 }))).toBe(false);
    // No interval is not the same as "contains zero" — it is "unknown", and
    // the caller handles it separately.
    expect(straddlesZero(estimate({ value: 0.09 }))).toBe(false);
  });

  it("normalises a missing estimate instead of throwing", () => {
    expect(orEmptyEstimate(null).rollouts).toBe(0);
    expect(orEmptyEstimate(undefined).value).toBeNull();
  });
});

describe("backend adapters — field-for-field, never renamed", () => {
  it("gapEstimate carries meanDelta, its interval, and the paired-case n", () => {
    const e = gapEstimate(
      comparison({ meanDelta: 0.04, deltaInterval: interval(0.01, 0.07), pairedCases: 12 })
    );
    expect(e.value).toBe(0.04);
    expect(e.ciLow).toBe(0.01);
    expect(e.ciHigh).toBe(0.07);
    expect(e.rollouts).toBe(12);
    expect(e.method).toBe("PAIRED_BOOTSTRAP");
  });

  it("a null backend interval stays a null interval — not a zero-width one", () => {
    const e = gapEstimate(comparison({ meanDelta: 0.04, deltaInterval: null }));
    expect(e.value).toBe(0.04);
    expect(hasInterval(e)).toBe(false);
    expect(e.method).toBeNull();
  });

  it("metricEstimate maps the metric row's value/interval/n verbatim", () => {
    const dataset = {
      metrics: [
        {
          metric: "MEAN_SCORE" as const,
          label: "mean score",
          question: "How good are the responses on the judge's own scale?",
          value: 0.62,
          interval: { low: 0.48, high: 0.75, confidenceLevel: 0.95, method: "HIERARCHICAL_BOOTSTRAP" as const },
          n: 3,
          unit: "CASE" as const,
          k: null as number | null,
          unreplicated: false,
        },
      ],
    };
    const e = metricEstimate(dataset as never, "MEAN_SCORE");
    expect(e.value).toBe(0.62);
    expect(e.ciLow).toBe(0.48);
    expect(e.ciHigh).toBe(0.75);
    expect(e.rollouts).toBe(3);
    // A metric the backend did not compute is the empty estimate —
    // "not measured", never a fabricated zero.
    expect(metricEstimate(dataset as never, "PASS_AT_1").value).toBeNull();
  });

  it("case helpers separate flaky, unscored and the pass-rate estimate", () => {
    const c = {
      caseKey: "c1",
      userMessage: "hi",
      rollouts: 3,
      successes: 1,
      passRate: 1 / 3,
      passRateInterval: { low: 0.06, high: 0.79, confidenceLevel: 0.95, method: "WILSON_SCORE" as const },
      allPassed: false,
      unreplicated: false,
      meanScore: 0.6,
      scoredRollouts: 2,
    };
    // 1 of 3 passed: the rollouts disagreed — flaky.
    expect(isCaseFlaky(c)).toBe(true);
    // 3 rollouts, 2 scored: one produced no judge score — missing data.
    expect(caseUnscoredRollouts(c)).toBe(1);
    const e = casePassEstimate(c);
    expect(e.rollouts).toBe(3);
    expect(e.method).toBe("WILSON_SCORE");
    // A unanimous case is not flaky.
    expect(isCaseFlaky({ ...c, successes: 0, passRate: 0 })).toBe(false);
    expect(isCaseFlaky({ ...c, successes: 3, allPassed: true })).toBe(false);
  });

  it("intervalToEstimate tolerates missing pieces without inventing them", () => {
    const e = intervalToEstimate(0.5, null, 0);
    expect(e.value).toBe(0.5);
    expect(e.ciLow).toBeNull();
    expect(e.rollouts).toBe(0);
  });
});

describe("classifyComparison — the console's refusal to call a winner", () => {
  it("refuses when the gap interval contains zero", () => {
    const reading = classifyComparison(
      comparison({ meanDelta: 0.03, deltaInterval: interval(-0.02, 0.08), verdict: "CANNOT_TELL" })
    );
    expect(reading.state).toBe("INDISTINGUISHABLE");
    expect(reading.straddlesZero).toBe(true);
    expect(reading.reason).toContain("contains zero");
  });

  it("refuses when the gap sits inside the dataset's measured noise floor", () => {
    const reading = classifyComparison(
      comparison({
        meanDelta: 0.04,
        deltaInterval: interval(0.01, 0.07),
        noiseFloorTested: 0.05,
        verdict: "INSIDE_NOISE_FLOOR",
      })
    );
    expect(reading.state).toBe("INDISTINGUISHABLE");
    expect(reading.withinNoiseFloor).toBe(true);
    expect(reading.straddlesZero).toBe(false);
  });

  it("overrides a backend that claimed a winner inside the noise floor, and says it did", () => {
    const reading = classifyComparison(
      comparison({
        meanDelta: 0.04,
        deltaInterval: interval(0.01, 0.07),
        noiseFloorTested: 0.05,
        verdict: "VARIANT_BETTER",
      })
    );
    expect(reading.state).toBe("INDISTINGUISHABLE");
    expect(reading.downgradedFromServerVerdict).toBe(true);
    expect(reading.reason).toContain("noise floor");
  });

  it("never promotes: a backend verdict of CANNOT_TELL survives a clean interval", () => {
    // The guards are one-directional on purpose. A clean, floor-clearing
    // interval does NOT let the client upgrade the backend's own refusal.
    const reading = classifyComparison(
      comparison({
        meanDelta: 0.2,
        deltaInterval: interval(0.15, 0.25),
        noiseFloorTested: 0.05,
        verdict: "CANNOT_TELL",
        explanation: "the paired interval contains zero",
      })
    );
    expect(reading.state).toBe("INDISTINGUISHABLE");
    expect(reading.downgradedFromServerVerdict).toBe(false);
  });

  it("calls a winner and prints the backend's own explanation verbatim", () => {
    const explanation = "the variant scores higher: paired gap +0.20 [+0.15, +0.25] over 20 cases clears the floor of 0.05";
    const reading = classifyComparison(
      comparison({
        meanDelta: 0.2,
        deltaInterval: interval(0.15, 0.25),
        noiseFloorTested: 0.05,
        verdict: "VARIANT_BETTER",
        casesVariantBetter: 14,
        casesTied: 4,
        casesBaselineBetter: 2,
        explanation,
      })
    );
    expect(reading.state).toBe("CANDIDATE_BETTER");
    expect(reading.reason).toBe(explanation);
  });

  it("warns, in a derived reason, when a winner was called with no measured floor", () => {
    const reading = classifyComparison(
      comparison({
        meanDelta: 0.2,
        deltaInterval: interval(0.15, 0.25),
        noiseFloorTested: null,
        verdict: "VARIANT_BETTER",
      })
    );
    expect(reading.state).toBe("CANDIDATE_BETTER");
    expect(reading.reason).toContain("no measured noise floor");
  });

  it("refuses when the backend flags the comparison as unreplicated, whatever it said", () => {
    const reading = classifyComparison(
      comparison({
        unreplicated: true,
        meanDelta: 0.2,
        deltaInterval: interval(0.15, 0.25),
        verdict: "VARIANT_BETTER",
      })
    );
    expect(reading.state).toBe("INSUFFICIENT_DATA");
    expect(reading.downgradedFromServerVerdict).toBe(true);
  });

  it("refuses when the two runs share no cases", () => {
    const reading = classifyComparison(comparison({ pairedCases: 0, casesTied: 0 }));
    expect(reading.state).toBe("INSUFFICIENT_DATA");
    expect(reading.reason).toContain("share no cases");
  });

  it("refuses when there is no comparison at all", () => {
    expect(classifyComparison(null).state).toBe("INSUFFICIENT_DATA");
  });
});
