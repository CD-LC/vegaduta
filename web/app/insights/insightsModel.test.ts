import { describe, expect, it } from "vitest";
import type { AgentJobCountsDto, DayUsageDto, ModelUsageDto, SpendSummaryDto, UsageMetricsDto } from "../lib/api/insights";
import {
  MODEL_KEYS,
  PLACEHOLDER,
  buildDailySeries,
  buildJobs,
  buildKpis,
  buildTeaser,
  callsSummary,
  costSummary,
  describeJobWindow,
  formatCount,
  formatDayLabel,
  formatLatency,
  formatRate,
  formatUsd,
  jobWindowForPeriod,
  jobWindowMs,
  parseIsoDate,
  rangeForPeriod,
  rankAgents,
  rankModels,
  rankingSummary,
  toCount,
  toNumber,
} from "./insightsModel";

function metrics(over: Partial<UsageMetricsDto> = {}): UsageMetricsDto {
  return {
    totalCalls: 0,
    errorCount: 0,
    successRate: 0,
    totalTokens: 0,
    messageVolume: 0,
    distinctSessions: 0,
    estimatedCostUsd: 0,
    avgLatencyMs: null,
    latencyP50Ms: null,
    latencyP95Ms: null,
    cacheHitRate: null,
    ...over,
  };
}

function summary(over: Partial<SpendSummaryDto> = {}, m: Partial<UsageMetricsDto> = {}): SpendSummaryDto {
  return { fromDate: "2026-09-29", toDate: "2026-10-05", includesLiveToday: true, metrics: metrics(m), ...over };
}

function counts(over: Partial<AgentJobCountsDto> = {}): AgentJobCountsDto {
  return {
    queued: 0,
    running: 0,
    awaitingApproval: 0,
    live: 0,
    succeededInWindow: 0,
    failedInWindow: 0,
    cancelledInWindow: 0,
    window: "PT168H",
    windowStart: "2026-09-28T10:00:00Z",
    asOf: "2026-10-05T10:00:00Z",
    agentId: null,
    tenantWide: true,
    ...over,
  };
}

describe("dates", () => {
  it("parses strict ISO dates only", () => {
    expect(parseIsoDate("2026-10-05")).toBe(Date.UTC(2026, 9, 5));
    expect(parseIsoDate("2026-02-31")).toBeNull();
    expect(parseIsoDate("2026-10-05T00:00:00Z")).toBeNull();
    expect(parseIsoDate("")).toBeNull();
    expect(parseIsoDate(null)).toBeNull();
    expect(parseIsoDate(20261005)).toBeNull();
  });

  it("builds an inclusive UTC range ending today", () => {
    const now = new Date("2026-10-05T23:30:00Z");
    expect(rangeForPeriod(7, now)).toEqual({ from: "2026-09-29", to: "2026-10-05" });
    expect(rangeForPeriod(30, now)).toEqual({ from: "2026-09-06", to: "2026-10-05" });
    expect(rangeForPeriod(90, now)).toEqual({ from: "2026-07-08", to: "2026-10-05" });
  });

  it("uses the UTC date, not the local one, just after UTC midnight", () => {
    expect(rangeForPeriod(7, new Date("2026-10-06T00:10:00Z")).to).toBe("2026-10-06");
  });

  it("maps a period to the job window parameter", () => {
    expect(jobWindowForPeriod(7)).toBe("P7D");
    expect(jobWindowForPeriod(90)).toBe("P90D");
  });

  it("labels a day and passes an unparsable value through", () => {
    expect(formatDayLabel("2026-10-03")).toBe("3 Oct");
    expect(formatDayLabel("not-a-date")).toBe("not-a-date");
    expect(formatDayLabel("")).toBe("");
  });
});

describe("number formatting", () => {
  it("toNumber accepts numbers and numeric strings only", () => {
    expect(toNumber(1.5)).toBe(1.5);
    expect(toNumber("12.40")).toBe(12.4);
    expect(toNumber("")).toBeNull();
    expect(toNumber("abc")).toBeNull();
    expect(toNumber(NaN)).toBeNull();
    expect(toNumber(Infinity)).toBeNull();
    expect(toNumber(null)).toBeNull();
    expect(toNumber(undefined)).toBeNull();
  });

  it("toCount never goes negative or fractional", () => {
    expect(toCount(12.9)).toBe(12);
    expect(toCount(-3)).toBe(0);
    expect(toCount("7")).toBe(7);
    expect(toCount(undefined)).toBe(0);
    expect(formatCount(1234567)).toBe("1,234,567");
  });

  it("formats money, keeping zero, tiny and unknown apart", () => {
    expect(formatUsd(0)).toBe("$0.00");
    expect(formatUsd(0.004)).toBe("<$0.01");
    expect(formatUsd(12.4)).toBe("$12.40");
    expect(formatUsd(1234.567)).toBe("$1,234.57");
    expect(formatUsd(null)).toBe(PLACEHOLDER);
    expect(formatUsd(undefined)).toBe(PLACEHOLDER);
    expect(formatUsd(NaN)).toBe(PLACEHOLDER);
    expect(formatUsd(-1)).toBe(PLACEHOLDER);
  });

  it("always marks latency as approximate", () => {
    expect(formatLatency(420.4)).toBe("~420 ms");
    expect(formatLatency(1400)).toBe("~1.4 s");
    expect(formatLatency(0)).toBe("~0 ms");
    expect(formatLatency(null)).toBe(PLACEHOLDER);
    expect(formatLatency(NaN)).toBe(PLACEHOLDER);
  });

  it("renders a null rate as '-', never 0% or 100%", () => {
    expect(formatRate(null)).toBe("-");
    expect(formatRate(undefined)).toBe("-");
    expect(formatRate(NaN)).toBe("-");
  });

  it("does not round a rate with failures up to 100%, or one with successes down to 0%", () => {
    expect(formatRate(1)).toBe("100%");
    expect(formatRate(0)).toBe("0%");
    expect(formatRate(0.9)).toBe("90%");
    expect(formatRate(0.998)).toBe("99.8%");
    expect(formatRate(0.99999)).toBe(">99.9%");
    expect(formatRate(0.002)).toBe("0.2%");
    expect(formatRate(0.00001)).toBe("<0.1%");
  });
});

describe("buildKpis", () => {
  it("computes the success rate from calls and errors", () => {
    const kpis = buildKpis(summary({}, { totalCalls: 10, errorCount: 1, estimatedCostUsd: 1.5, latencyP50Ms: 420, latencyP95Ms: 2100 }));
    expect(kpis.calls).toBe(10);
    expect(kpis.failed).toBe(1);
    expect(kpis.succeeded).toBe(9);
    expect(kpis.successRate).toBeCloseTo(0.9);
    expect(kpis.successRateText).toBe("90%");
    expect(kpis.costText).toBe("$1.50");
    expect(kpis.p50Text).toBe("~420 ms");
    expect(kpis.p95Text).toBe("~2.1 s");
    expect(kpis.hasData).toBe(true);
    expect(kpis.fromDate).toBe("2026-09-29");
    expect(kpis.toDate).toBe("2026-10-05");
    expect(kpis.includesLiveToday).toBe(true);
  });

  it("zero calls: rate is '-' even though the server sends successRate 0", () => {
    const kpis = buildKpis(summary({}, { totalCalls: 0, errorCount: 0, successRate: 0 }));
    expect(kpis.successRate).toBeNull();
    expect(kpis.successRateText).toBe("-");
    expect(kpis.callsText).toBe("0");
    expect(kpis.costText).toBe("$0.00");
    expect(kpis.p50Text).toBe("-");
    expect(kpis.p95Text).toBe("-");
    expect(kpis.hasData).toBe(false);
  });

  it("ignores the server's successRate field entirely", () => {
    expect(buildKpis(summary({}, { totalCalls: 4, errorCount: 1, successRate: 1 })).successRateText).toBe("75%");
  });

  it("accepts the cost as a string and a missing latency as '-'", () => {
    const kpis = buildKpis(summary({}, { totalCalls: 3, estimatedCostUsd: "0.0031", latencyP50Ms: null, latencyP95Ms: null }));
    expect(kpis.costText).toBe("<$0.01");
    expect(kpis.p50Text).toBe("-");
    expect(kpis.p95Text).toBe("-");
  });

  it("shows an unknown cost as '-', not $0.00", () => {
    expect(buildKpis(summary({}, { totalCalls: 3, estimatedCostUsd: null })).costText).toBe("-");
  });

  it("never reports more failures than calls", () => {
    const kpis = buildKpis(summary({}, { totalCalls: 2, errorCount: 5 }));
    expect(kpis.failed).toBe(2);
    expect(kpis.succeeded).toBe(0);
    expect(kpis.successRateText).toBe("0%");
  });

  it("survives null / malformed input", () => {
    for (const input of [null, undefined, {} as SpendSummaryDto, { metrics: null } as unknown as SpendSummaryDto]) {
      const kpis = buildKpis(input);
      expect(kpis.hasData).toBe(false);
      expect(kpis.successRateText).toBe("-");
      expect(kpis.fromDate).toBe("");
      expect(kpis.includesLiveToday).toBe(false);
    }
  });
});

describe("buildDailySeries", () => {
  const rows: DayUsageDto[] = [
    { date: "2026-10-05", metrics: metrics({ totalCalls: 2, errorCount: 0, estimatedCostUsd: 0.2 }) },
    { date: "2026-09-30", metrics: metrics({ totalCalls: 8, errorCount: 1, estimatedCostUsd: "1.3" }) },
  ];

  it("zero-fills every day of the echoed range, in order", () => {
    const series = buildDailySeries(summary(), rows);
    expect(series.days.map((d) => d.date)).toEqual([
      "2026-09-29",
      "2026-09-30",
      "2026-10-01",
      "2026-10-02",
      "2026-10-03",
      "2026-10-04",
      "2026-10-05",
    ]);
    expect(series.days.map((d) => d.calls)).toEqual([0, 8, 0, 0, 0, 0, 2]);
    expect(series.days[1]).toMatchObject({ label: "30 Sep", failed: 1, costUsd: 1.3 });
    expect(series.days[6].costUsd).toBeCloseTo(0.2);
  });

  it("flags the last bar partial only when the range includes live today", () => {
    expect(buildDailySeries(summary({ includesLiveToday: true }), rows).partialLast).toBe(true);
    expect(buildDailySeries(summary({ includesLiveToday: false }), rows).partialLast).toBe(false);
  });

  it("an empty answer still yields a full zero series for the range", () => {
    const series = buildDailySeries(summary(), []);
    expect(series.days).toHaveLength(7);
    expect(series.days.every((d) => d.calls === 0 && d.costUsd === 0)).toBe(true);
  });

  it("drops rows outside the echoed range and rows with a bad date", () => {
    const series = buildDailySeries(summary(), [
      ...rows,
      { date: "2026-08-01", metrics: metrics({ totalCalls: 99 }) },
      { date: "garbage", metrics: metrics({ totalCalls: 99 }) },
    ]);
    expect(series.days).toHaveLength(7);
    expect(series.days.reduce((acc, d) => acc + d.calls, 0)).toBe(10);
  });

  it("without a usable range, returns the rows as sent, sorted", () => {
    const series = buildDailySeries(summary({ fromDate: "", toDate: "" }), rows);
    expect(series.days.map((d) => d.date)).toEqual(["2026-09-30", "2026-10-05"]);
    // toDate is unknown, so the last row can not be confirmed as "today".
    expect(series.partialLast).toBe(false);
  });

  it("without a from date, the last bar is partial only if it is the range's last day", () => {
    expect(buildDailySeries(summary({ fromDate: "" }), rows).partialLast).toBe(true);
    expect(buildDailySeries(summary({ fromDate: "", toDate: "2026-10-06" }), rows).partialLast).toBe(false);
  });

  it("refuses to fill an inverted or absurd range", () => {
    expect(buildDailySeries(summary({ fromDate: "2026-10-05", toDate: "2026-09-29" }), []).days).toEqual([]);
    expect(buildDailySeries(summary({ fromDate: "1990-01-01", toDate: "2026-10-05" }), []).days).toEqual([]);
  });

  it("handles a one-day range and null inputs", () => {
    const one = buildDailySeries(summary({ fromDate: "2026-10-05", toDate: "2026-10-05" }), rows);
    expect(one.days).toHaveLength(1);
    expect(one.days[0].calls).toBe(2);
    expect(buildDailySeries(null, null)).toEqual({ days: [], partialLast: false });
  });

  it("treats negative or missing numbers as zero", () => {
    const series = buildDailySeries(summary({ fromDate: "2026-10-05", toDate: "2026-10-05" }), [
      { date: "2026-10-05", metrics: metrics({ totalCalls: -4, errorCount: 9, estimatedCostUsd: -2 }) },
    ]);
    expect(series.days[0]).toMatchObject({ calls: 0, failed: 0, costUsd: 0 });
  });
});

describe("chart summaries", () => {
  it("says how many calls succeeded over the echoed range", () => {
    const kpis = buildKpis(summary({ includesLiveToday: false }, { totalCalls: 10, errorCount: 1 }));
    expect(callsSummary(kpis)).toEqual({
      key: "callsSummary",
      values: { succeeded: "9", total: "10", from: "29 Sep", to: "5 Oct" },
    });
  });

  it("uses the 'still counting' variants when today is live", () => {
    const kpis = buildKpis(summary({}, { totalCalls: 10, errorCount: 1, estimatedCostUsd: 2 }));
    expect(callsSummary(kpis)?.key).toBe("callsSummaryLive");
    expect(costSummary(kpis)).toEqual({ key: "costSummaryLive", values: { cost: "$2.00", from: "29 Sep", to: "5 Oct" } });
  });

  it("has no sentence for zero calls", () => {
    const kpis = buildKpis(summary());
    expect(callsSummary(kpis)).toBeNull();
    expect(costSummary(kpis)).toBeNull();
  });
});

describe("rankings", () => {
  it("ranks agents by estimated cost and keeps the full list", () => {
    const names = new Map([
      ["a1", "Support bot"],
      ["a2", "Researcher"],
    ]);
    const ranking = rankAgents(
      [
        { agentId: "a2", metrics: metrics({ totalCalls: 5, estimatedCostUsd: 0.5 }) },
        { agentId: "a1", metrics: metrics({ totalCalls: 20, estimatedCostUsd: 4.1 }) },
        { agentId: null, metrics: metrics({ totalCalls: 3, estimatedCostUsd: 0.2 }) },
        { agentId: "0123456789abcdef", metrics: metrics({ totalCalls: 1, estimatedCostUsd: 1 }) },
        { agentId: "idle", metrics: metrics({ totalCalls: 0, estimatedCostUsd: 0 }) },
      ],
      names
    );
    expect(ranking.rows.map((r) => r.key)).toEqual(["a1", "0123456789abcdef", "a2", "__none__"]);
    expect(ranking.rows[0]).toMatchObject({ label: "Support bot", kind: "named", calls: 20, costText: "$4.10" });
    expect(ranking.rows[1]).toMatchObject({ label: null, kind: "unknown", shortId: "01234567" });
    expect(ranking.rows[3]).toMatchObject({ label: null, kind: "none" });
    expect(ranking.totalCostUsd).toBeCloseTo(5.8);
    expect(ranking.hasCost).toBe(true);
  });

  it("merges the same model across modalities", () => {
    const ranking = rankModels([
      { model: "gpt-x", modality: "text", metrics: metrics({ totalCalls: 4, estimatedCostUsd: 1 }) },
      { model: "gpt-x", modality: "image", metrics: metrics({ totalCalls: 1, estimatedCostUsd: 2 }) },
      { model: "small", modality: "text", metrics: metrics({ totalCalls: 9, estimatedCostUsd: 0.5 }) },
      { model: null, modality: null, metrics: metrics({ totalCalls: 2, estimatedCostUsd: 0 }) },
      { model: "  ", modality: null, metrics: metrics({ totalCalls: 1, estimatedCostUsd: 0 }) },
    ]);
    expect(ranking.rows.map((r) => [r.key, r.calls, r.costUsd])).toEqual([
      ["gpt-x", 5, 3],
      ["small", 9, 0.5],
      ["__none__", 3, 0],
    ]);
    expect(ranking.rows[2].kind).toBe("none");
  });

  it("calls with no cost at all: nothing to rank by", () => {
    const ranking = rankModels([{ model: "free", modality: "text", metrics: metrics({ totalCalls: 5, estimatedCostUsd: 0 }) }]);
    expect(ranking.rows).toHaveLength(1);
    expect(ranking.hasCost).toBe(false);
    expect(rankingSummary(ranking)).toBeNull();
  });

  it("breaks cost ties by calls, then by key, so the order is stable", () => {
    const ranking = rankModels([
      { model: "b", modality: null, metrics: metrics({ totalCalls: 1, estimatedCostUsd: 1 }) },
      { model: "a", modality: null, metrics: metrics({ totalCalls: 1, estimatedCostUsd: 1 }) },
      { model: "c", modality: null, metrics: metrics({ totalCalls: 2, estimatedCostUsd: 1 }) },
    ]);
    expect(ranking.rows.map((r) => r.key)).toEqual(["c", "a", "b"]);
  });

  it("summarises the leader against the total of the FULL list", () => {
    const ranking = rankModels(
      Array.from({ length: 8 }, (_, i): ModelUsageDto => ({ model: `m${i}`, modality: null, metrics: metrics({ totalCalls: 1, estimatedCostUsd: i + 1 }) }))
    );
    const result = rankingSummary(ranking);
    expect(result?.top.key).toBe("m7");
    expect(result?.msg).toEqual({ key: "topSummary", values: { cost: "$8.00", total: "$36.00", count: 8 } });
  });

  it("uses the single-entry sentence for one row", () => {
    const ranking = rankModels([{ model: "only", modality: null, metrics: metrics({ totalCalls: 1, estimatedCostUsd: 2 }) }]);
    expect(rankingSummary(ranking)?.msg).toEqual({ key: "topSummaryOne", values: { cost: "$2.00" } });
  });

  it("empty and null input give an empty ranking", () => {
    for (const ranking of [rankModels([]), rankModels(null), rankAgents(undefined), rankAgents([])]) {
      expect(ranking).toEqual({ rows: [], totalCostUsd: 0, hasCost: false });
    }
  });
});

describe("job window", () => {
  it("prefers windowStart / asOf over the window field", () => {
    expect(jobWindowMs(counts({ window: "PT1H" }))).toBe(7 * 86_400_000);
  });

  it("falls back to an ISO duration or a number of seconds", () => {
    const noStamps: Pick<AgentJobCountsDto, "windowStart" | "asOf"> = { windowStart: null, asOf: null };
    expect(jobWindowMs(counts({ ...noStamps, window: "PT24H" }))).toBe(86_400_000);
    expect(jobWindowMs(counts({ ...noStamps, window: "P30D" }))).toBe(30 * 86_400_000);
    expect(jobWindowMs(counts({ ...noStamps, window: "P1DT12H30M" }))).toBe(86_400_000 + 12.5 * 3_600_000);
    expect(jobWindowMs(counts({ ...noStamps, window: 604800 }))).toBe(7 * 86_400_000);
    expect(jobWindowMs(counts({ ...noStamps, window: "P" }))).toBeNull();
    expect(jobWindowMs(counts({ ...noStamps, window: "soon" }))).toBeNull();
    expect(jobWindowMs(counts({ ...noStamps, window: null }))).toBeNull();
    expect(jobWindowMs(null)).toBeNull();
  });

  it("ignores stamps that are inverted or unparsable", () => {
    expect(jobWindowMs(counts({ windowStart: "2026-10-05T10:00:00Z", asOf: "2026-10-04T10:00:00Z", window: "PT2H" }))).toBe(7_200_000);
    expect(jobWindowMs(counts({ windowStart: "nope", asOf: "nope", window: null }))).toBeNull();
  });

  it("describes the window in the largest sensible unit", () => {
    expect(describeJobWindow(7 * 86_400_000)).toEqual({ key: "jobsWindowDays", values: { count: 7 } });
    expect(describeJobWindow(30 * 86_400_000)).toEqual({ key: "jobsWindowDays", values: { count: 30 } });
    expect(describeJobWindow(86_400_000)).toEqual({ key: "jobsWindowHours", values: { count: 24 } });
    expect(describeJobWindow(3_600_000)).toEqual({ key: "jobsWindowHour" });
    expect(describeJobWindow(15 * 60_000)).toEqual({ key: "jobsWindowMinutes", values: { count: 15 } });
    expect(describeJobWindow(60_000)).toEqual({ key: "jobsWindowMinute" });
    expect(describeJobWindow(null)).toEqual({ key: "jobsWindowUnknown" });
    expect(describeJobWindow(0)).toEqual({ key: "jobsWindowUnknown" });
  });
});

describe("buildJobs", () => {
  it("maps counts to five segments with states", () => {
    const view = buildJobs(
      counts({ queued: 2, running: 1, awaitingApproval: 1, succeededInWindow: 9, failedInWindow: 1, cancelledInWindow: 3 })
    );
    expect(view.segments.map((s) => [s.key, s.count, s.state])).toEqual([
      ["queued", 2, "queued"],
      ["running", 1, "running"],
      ["awaitingApproval", 1, "warning"],
      ["succeeded", 9, "success"],
      ["failed", 1, "error"],
    ]);
    expect(view.total).toBe(14);
    expect(view.live).toBe(4);
    expect(view.cancelled).toBe(3);
    expect(view.tenantWide).toBe(true);
    expect(view.window).toEqual({ key: "jobsWindowDays", values: { count: 7 } });
    expect(view.summary).toEqual({ key: "jobsSummary", values: { succeeded: "9", failed: "1", live: "4" } });
  });

  it("shows the window the server applied, not the one requested (90 days clamps to 30)", () => {
    const view = buildJobs(counts({ windowStart: "2026-09-05T10:00:00Z", asOf: "2026-10-05T10:00:00Z", window: "PT720H" }));
    expect(view.window).toEqual({ key: "jobsWindowDays", values: { count: 30 } });
  });

  it("only live jobs: says none finished instead of implying a rate", () => {
    const view = buildJobs(counts({ running: 2 }));
    expect(view.summary).toEqual({ key: "jobsSummaryNoneFinished", values: { live: "2" } });
  });

  it("all zero: total 0 and no summary, so the page draws the empty state", () => {
    const view = buildJobs(counts());
    expect(view.total).toBe(0);
    expect(view.summary).toBeNull();
  });

  it("cancelled jobs alone do not make the bar non-empty", () => {
    const view = buildJobs(counts({ cancelledInWindow: 4 }));
    expect(view.total).toBe(0);
    expect(view.cancelled).toBe(4);
  });

  it("tenantWide is false unless the server says true", () => {
    expect(buildJobs(counts({ tenantWide: false })).tenantWide).toBe(false);
    expect(buildJobs({ ...counts(), tenantWide: undefined } as unknown as AgentJobCountsDto).tenantWide).toBe(false);
  });

  it("recomputes live from the parts and survives null / garbage", () => {
    expect(buildJobs(counts({ queued: 1, running: 1, live: 99 })).live).toBe(2);
    const empty = buildJobs(null);
    expect(empty.total).toBe(0);
    expect(empty.window).toEqual({ key: "jobsWindowUnknown" });
    const bad = buildJobs(counts({ queued: -5, running: NaN as unknown as number }));
    expect(bad.total).toBe(0);
  });
});

describe("buildTeaser", () => {
  const rows: DayUsageDto[] = [{ date: "2026-10-04", metrics: metrics({ totalCalls: 10, errorCount: 1 }) }];

  it("is null when there were no calls", () => {
    expect(buildTeaser(summary(), [])).toBeNull();
    expect(buildTeaser(null, null)).toBeNull();
  });

  it("is null when calls exist but no day series can be drawn", () => {
    expect(buildTeaser(summary({ fromDate: "", toDate: "" }, { totalCalls: 10 }), [])).toBeNull();
  });

  it("carries the 7-day series and a plain sentence", () => {
    const teaser = buildTeaser(summary({}, { totalCalls: 10, errorCount: 1 }), rows);
    expect(teaser?.series.days).toHaveLength(7);
    expect(teaser?.series.partialLast).toBe(true);
    expect(teaser?.summary).toEqual({ key: "teaserSummaryLive", values: { succeeded: "9", total: "10", days: 7 } });
    expect(buildTeaser(summary({ includesLiveToday: false }, { totalCalls: 10, errorCount: 1 }), rows)?.summary.key).toBe("teaserSummary");
  });
});

describe("MODEL_KEYS", () => {
  it("has no duplicates", () => {
    expect(new Set(MODEL_KEYS).size).toBe(MODEL_KEYS.length);
  });
});
