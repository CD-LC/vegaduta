import { describe, expect, it } from "vitest";
import {
  clamp,
  formatCompact,
  formatPercent,
  isFiniteNumber,
  niceCeil,
  niceTicks,
  rate,
  safeMax,
  safeMin,
  scaleLinear,
  shares,
  sparklinePoints,
  stateColor,
  sum,
  toMagnitude,
} from "./chartMath";

const BAD = [NaN, Infinity, -Infinity];

describe("isFiniteNumber / toMagnitude", () => {
  it("accepts only real finite numbers", () => {
    expect(isFiniteNumber(0)).toBe(true);
    expect(isFiniteNumber(-3.5)).toBe(true);
    for (const bad of BAD) expect(isFiniteNumber(bad)).toBe(false);
    expect(isFiniteNumber("3")).toBe(false);
    expect(isFiniteNumber(null)).toBe(false);
    expect(isFiniteNumber(undefined)).toBe(false);
  });

  it("turns NaN, Infinity and negatives into 0", () => {
    expect(toMagnitude(5)).toBe(5);
    expect(toMagnitude(-5)).toBe(0);
    for (const bad of BAD) expect(toMagnitude(bad)).toBe(0);
    expect(toMagnitude(undefined)).toBe(0);
  });
});

describe("clamp", () => {
  it("clamps, tolerates swapped bounds, and never returns NaN", () => {
    expect(clamp(5, 0, 10)).toBe(5);
    expect(clamp(-1, 0, 10)).toBe(0);
    expect(clamp(11, 0, 10)).toBe(10);
    expect(clamp(5, 10, 0)).toBe(5);
    expect(clamp(NaN, 0, 10)).toBe(0);
    expect(clamp(Infinity, 0, 10)).toBe(0);
  });
});

describe("sum", () => {
  it("handles empty, null and single inputs", () => {
    expect(sum([])).toBe(0);
    expect(sum(null)).toBe(0);
    expect(sum(undefined)).toBe(0);
    expect(sum([7])).toBe(7);
  });

  it("skips NaN / Infinity / null and keeps negatives", () => {
    expect(sum([1, NaN, 2, Infinity, null, undefined, -Infinity])).toBe(3);
    expect(sum([5, -2])).toBe(3);
    expect(sum([0, 0, 0])).toBe(0);
  });
});

describe("safeMax / safeMin", () => {
  it("returns the fallback for empty or all-invalid input instead of +/-Infinity", () => {
    expect(safeMax([])).toBe(0);
    expect(safeMax(null)).toBe(0);
    expect(safeMax([NaN, Infinity], 1)).toBe(1);
    expect(safeMin([])).toBe(0);
    expect(safeMin([NaN], 9)).toBe(9);
  });

  it("handles single, all-zero and negative series", () => {
    expect(safeMax([4])).toBe(4);
    expect(safeMax([0, 0, 0])).toBe(0);
    expect(safeMax([-5, -2, -9])).toBe(-2);
    expect(safeMin([-5, -2, -9])).toBe(-9);
    expect(safeMax([1, NaN, 3, Infinity])).toBe(3);
  });

  it("does not overflow the stack on a huge array, and copes with huge values", () => {
    const huge = Array.from({ length: 300_000 }, (_, i) => i);
    expect(safeMax(huge)).toBe(299_999);
    expect(safeMin(huge)).toBe(0);
    expect(safeMax([1e300, Number.MAX_VALUE])).toBe(Number.MAX_VALUE);
  });
});

describe("niceCeil / niceTicks", () => {
  it("rounds up to 1 / 2 / 2.5 / 5 / 10 steps", () => {
    expect(niceCeil(1)).toBe(1);
    expect(niceCeil(1.2)).toBe(2);
    expect(niceCeil(23)).toBe(25);
    expect(niceCeil(41)).toBe(50);
    expect(niceCeil(87)).toBe(100);
    expect(niceCeil(100)).toBe(100);
    expect(niceCeil(0.03)).toBeCloseTo(0.05, 10);
  });

  it("returns 0 for zero, negative and non-finite input", () => {
    expect(niceCeil(0)).toBe(0);
    expect(niceCeil(-4)).toBe(0);
    for (const bad of BAD) expect(niceCeil(bad)).toBe(0);
  });

  it("stays finite for huge values", () => {
    expect(Number.isFinite(niceCeil(9.9e307))).toBe(true);
    expect(Number.isFinite(niceCeil(Number.MAX_VALUE))).toBe(true);
    expect(niceCeil(Number.MAX_VALUE)).toBeGreaterThanOrEqual(Number.MAX_VALUE);
  });

  it("builds evenly spaced ticks from zero", () => {
    expect(niceTicks(87, 4)).toEqual([0, 25, 50, 75, 100]);
    expect(niceTicks(0.3, 3)).toEqual([0, 0.1667, 0.3333, 0.5].map((v, i) => (i === 0 || i === 3 ? v : expect.closeTo(v, 3))));
    expect(niceTicks(3, 0)).toEqual([0, 5]);
    expect(niceTicks(3, NaN)).toHaveLength(5);
  });

  it("returns no ticks when there is nothing to scale - no empty axis", () => {
    expect(niceTicks(0)).toEqual([]);
    expect(niceTicks(-1)).toEqual([]);
    expect(niceTicks(NaN)).toEqual([]);
  });
});

describe("scaleLinear", () => {
  it("maps into the range and clamps", () => {
    expect(scaleLinear(5, 10, 100)).toBe(50);
    expect(scaleLinear(20, 10, 100)).toBe(100);
    expect(scaleLinear(-5, 10, 100)).toBe(0);
  });

  it("returns 0 instead of dividing by zero or propagating NaN", () => {
    expect(scaleLinear(5, 0, 100)).toBe(0);
    expect(scaleLinear(5, -1, 100)).toBe(0);
    expect(scaleLinear(NaN, 10, 100)).toBe(0);
    expect(scaleLinear(5, NaN, 100)).toBe(0);
    expect(scaleLinear(5, 10, Infinity)).toBe(0);
  });
});

describe("shares", () => {
  it("returns fractions that sum to 1", () => {
    expect(shares([1, 1, 2])).toEqual([0.25, 0.25, 0.5]);
    expect(shares([9])).toEqual([1]);
  });

  it("returns zeros, never NaN, for empty / all-zero / invalid input", () => {
    expect(shares([])).toEqual([]);
    expect(shares(null)).toEqual([]);
    expect(shares([0, 0])).toEqual([0, 0]);
    expect(shares([NaN, -3, Infinity])).toEqual([0, 0, 0]);
    expect(shares([-1, 1])).toEqual([0, 1]);
  });
});

describe("rate", () => {
  it("is part / whole, clamped to 0..1", () => {
    expect(rate(1, 4)).toBe(0.25);
    expect(rate(0, 4)).toBe(0);
    expect(rate(9, 4)).toBe(1);
    expect(rate(-1, 4)).toBe(0);
  });

  it("is null - not 0, not 1, not NaN - when there is no denominator", () => {
    expect(rate(0, 0)).toBeNull();
    expect(rate(3, 0)).toBeNull();
    expect(rate(3, -2)).toBeNull();
    expect(rate(null, 5)).toBeNull();
    expect(rate(5, undefined)).toBeNull();
    expect(rate(NaN, 5)).toBeNull();
    expect(rate(5, Infinity)).toBeNull();
  });
});

describe("formatPercent", () => {
  it("formats a rate", () => {
    expect(formatPercent(0.874)).toBe("87%");
    expect(formatPercent(0.875, 1)).toBe("87.5%");
    expect(formatPercent(0)).toBe("0%");
    expect(formatPercent(1)).toBe("100%");
    expect(formatPercent(1.7)).toBe("100%");
    expect(formatPercent(-0.2)).toBe("0%");
  });

  it("returns null for a missing rate so the caller picks the placeholder", () => {
    expect(formatPercent(null)).toBeNull();
    expect(formatPercent(undefined)).toBeNull();
    for (const bad of BAD) expect(formatPercent(bad)).toBeNull();
    expect(formatPercent(rate(0, 0)) ?? "-").toBe("-");
  });

  it("ignores a nonsense digits argument", () => {
    expect(formatPercent(0.5, NaN)).toBe("50%");
    expect(formatPercent(0.5, -3)).toBe("50%");
  });
});

describe("formatCompact", () => {
  it("abbreviates thousands, millions and beyond", () => {
    expect(formatCompact(0)).toBe("0");
    expect(formatCompact(950)).toBe("950");
    expect(formatCompact(1000)).toBe("1k");
    expect(formatCompact(1234)).toBe("1.2k");
    expect(formatCompact(250_000)).toBe("250k");
    expect(formatCompact(2_500_000)).toBe("2.5M");
    expect(formatCompact(3e9)).toBe("3B");
    expect(formatCompact(-1500)).toBe("-1.5k");
    expect(formatCompact(2.25)).toBe("2.3");
  });

  it("returns null for non-finite input and stays a string for huge input", () => {
    for (const bad of BAD) expect(formatCompact(bad)).toBeNull();
    expect(formatCompact(null)).toBeNull();
    expect(formatCompact(1e300)).toMatch(/T$/);
  });
});

describe("sparklinePoints", () => {
  it("returns an empty string when nothing is drawable", () => {
    expect(sparklinePoints([], 120, 26)).toBe("");
    expect(sparklinePoints(null, 120, 26)).toBe("");
    expect(sparklinePoints([NaN, Infinity], 120, 26)).toBe("");
    expect(sparklinePoints([1, 2], 0, 26)).toBe("");
  });

  it("draws a flat line for a single point", () => {
    expect(sparklinePoints([5], 120, 26)).toBe("0.0,13.0 120.0,13.0");
  });

  it("keeps the original normalisation for two or more points", () => {
    expect(sparklinePoints([0, 10], 120, 26)).toBe("0.0,25.0 120.0,3.0");
    expect(sparklinePoints([1, 2, 3], 120, 26)).toBe("0.0,25.0 60.0,14.0 120.0,3.0");
  });

  it("never emits NaN for all-equal, all-zero, negative or dirty series", () => {
    for (const series of [[0, 0, 0], [4, 4], [-3, -1, -2], [1, NaN, 3], [1e300, -1e300]]) {
      const points = sparklinePoints(series, 120, 26);
      expect(points).not.toMatch(/NaN|Infinity/);
      expect(points.length).toBeGreaterThan(0);
    }
    expect(sparklinePoints([0, 0, 0], 120, 26)).toBe("0.0,25.0 60.0,25.0 120.0,25.0");
  });
});

describe("stateColor", () => {
  it("maps states to their tokens and unknowns to idle", () => {
    expect(stateColor("success")).toBe("var(--state-success)");
    expect(stateColor("queued")).toBe("var(--state-queued)");
    expect(stateColor("idle")).toBe("var(--state-idle)");
    expect(stateColor(undefined)).toBe("var(--state-idle)");
    expect(stateColor("bogus" as never)).toBe("var(--state-idle)");
  });
});
