import { describe, it, expect } from "vitest";
import type { NorthStarResponse, WeekPoint } from "../lib/adminApi/northStar";
import {
  HIDDEN_LABEL,
  cellText,
  chartGeometry,
  fmtCount,
  fmtMinutes,
  fmtPct,
  hasAnyActivity,
  latestCompleteWeek,
  linePath,
  sumKnown,
  weekBefore,
  weekLabel,
} from "./format";

function week(weekStart: string, over: Partial<WeekPoint> = {}): WeekPoint {
  return {
    weekStart,
    partial: false,
    wau: 5,
    runs: 10,
    scheduledShare: 0.2,
    successRate: 0.9,
    runsByKind: null,
    suppressed: false,
    ...over,
  };
}

function response(weeks: WeekPoint[]): NorthStarResponse {
  return {
    generatedAt: "2026-10-03T12:00:00Z",
    weeks,
    timeToFirstRunMedianMinutes: null,
    timeToFirstRunCohorts: [],
    languages: [],
    channels: [],
    passK: null,
    coverage: { tenantsTotal: 1, tenantsScanned: 1, tenantsSkipped: 0, truncated: false },
    notes: [],
  };
}

describe("formatters", () => {
  it("renders null and non-finite as n/a, never 0", () => {
    expect(fmtCount(null)).toBe("n/a");
    expect(fmtCount(undefined)).toBe("n/a");
    expect(fmtCount(Number.NaN)).toBe("n/a");
    expect(fmtPct(null)).toBe("n/a");
    expect(fmtMinutes(null)).toBe("n/a");
  });

  it("formats counts, percentages and durations", () => {
    expect(fmtCount(1234567)).toBe("1,234,567");
    expect(fmtCount(0)).toBe("0");
    expect(fmtPct(0.8734)).toBe("87.3%");
    expect(fmtPct(0)).toBe("0.0%");
    expect(fmtMinutes(12.5)).toBe("12.5 min");
    expect(fmtMinutes(90)).toBe("1.5 h");
    expect(fmtMinutes(60 * 24 * 2.1)).toBe("2.1 d");
  });
});

describe("cellText", () => {
  it("separates a hidden cell from an undefined one", () => {
    expect(cellText(null, true)).toBe(HIDDEN_LABEL);
    expect(cellText(null, false)).toBe("n/a");
  });

  it("shows a published zero as 0", () => {
    expect(cellText(0, false)).toBe("0");
    expect(cellText(0, true)).toBe("0");
  });

  it("applies the supplied formatter", () => {
    expect(cellText(0.5, false, fmtPct)).toBe("50.0%");
  });
});

describe("weekLabel", () => {
  it("formats an ISO date without timezone drift", () => {
    expect(weekLabel("2026-09-28")).toBe("28 Sep");
    expect(weekLabel("2026-01-05")).toBe("5 Jan");
  });

  it("passes an unparseable value through", () => {
    expect(weekLabel("soon")).toBe("soon");
  });
});

describe("latestCompleteWeek / weekBefore", () => {
  const w1 = week("2026-09-14");
  const w2 = week("2026-09-21");
  const w3 = week("2026-09-28", { partial: true });

  it("skips the partial current week", () => {
    expect(latestCompleteWeek([w1, w2, w3])).toBe(w2);
    expect(weekBefore([w1, w2, w3], w2)).toBe(w1);
  });

  it("falls back to the only week when it is partial", () => {
    expect(latestCompleteWeek([w3])).toBe(w3);
  });

  it("handles empty input", () => {
    expect(latestCompleteWeek([])).toBeNull();
    expect(weekBefore([w1], w1)).toBeNull();
    expect(weekBefore([w1], null)).toBeNull();
  });
});

describe("sumKnown", () => {
  it("totals published values and counts the hidden ones", () => {
    expect(sumKnown([10, null, 5, undefined])).toEqual({ total: 15, known: 2, hidden: 2 });
    expect(sumKnown([])).toEqual({ total: 0, known: 0, hidden: 0 });
  });
});

describe("hasAnyActivity", () => {
  it("is false for null and an all-zero window", () => {
    expect(hasAnyActivity(null)).toBe(false);
    expect(hasAnyActivity(response([week("2026-09-14", { wau: 0, runs: 0, suppressed: false })]))).toBe(false);
  });

  it("counts a suppressed week as activity", () => {
    expect(hasAnyActivity(response([week("2026-09-14", { wau: null, runs: null, suppressed: true })]))).toBe(true);
  });

  it("is true when any week has runs", () => {
    expect(hasAnyActivity(response([week("2026-09-14", { wau: 0, runs: 3 })]))).toBe(true);
  });
});

describe("chartGeometry", () => {
  const opts = { width: 300, height: 100, padTop: 10, padBottom: 20 };

  it("scales bars against the largest published value and centres them in their slot", () => {
    const g = chartGeometry([10, 5, 0], opts);
    expect(g.max).toBe(10);
    expect(g.slot).toBe(100);
    expect(g.points[0].x).toBe(50);
    expect(g.points[0].h).toBe(70); // plot height = 100 - 10 - 20
    expect(g.points[1].h).toBe(35);
    expect(g.points[2].h).toBe(0);
    expect(g.points[0].y).toBe(10);
  });

  it("keeps hidden values as null points, not zero-height bars", () => {
    const g = chartGeometry([4, null], opts);
    expect(g.points[1].value).toBeNull();
    expect(g.points[1].h).toBe(0);
    expect(g.points[0].value).toBe(4);
  });

  it("honours a fixed axis for rates and never lets the axis fall below 1", () => {
    expect(chartGeometry([0.5], { ...opts, fixedMax: 1 }).points[0].h).toBe(35);
    expect(chartGeometry([0, null], opts).max).toBe(1);
  });

  it("clamps a value above a fixed axis", () => {
    expect(chartGeometry([2], { ...opts, fixedMax: 1 }).points[0].h).toBe(70);
  });

  it("copes with no data", () => {
    expect(chartGeometry([], opts).points).toEqual([]);
  });
});

describe("linePath", () => {
  const opts = { width: 400, height: 100, padTop: 0, padBottom: 0, fixedMax: 1 };

  it("draws one connected segment for published points", () => {
    const d = linePath(chartGeometry([1, 0.5, 0], opts).points);
    expect(d.startsWith("M")).toBe(true);
    expect(d.match(/M/g)?.length).toBe(1);
    expect(d.match(/L/g)?.length).toBe(2);
  });

  it("breaks the line at a hidden point instead of interpolating across it", () => {
    const d = linePath(chartGeometry([1, null, 0.5, 0.25], opts).points);
    expect(d.match(/M/g)?.length).toBe(2);
    expect(d.match(/L/g)?.length).toBe(1);
  });

  it("is empty when nothing is published", () => {
    expect(linePath(chartGeometry([null, null], opts).points)).toBe("");
  });
});
