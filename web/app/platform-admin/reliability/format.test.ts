import { describe, it, expect } from "vitest";
import type { DependencyEntry } from "../lib/adminApi/reliability";
import {
  REFRESH_MS,
  budgetText,
  categoryLabel,
  countStatuses,
  fmtBurn,
  fmtCount,
  fmtDuration,
  fmtLatency,
  fmtPct,
  groupByCategory,
  needsCatchUpRefresh,
  overallSummary,
  shouldPoll,
  statusMeta,
  toneClasses,
  verdictMeta,
  windowLabel,
} from "./format";

function entry(name: string, category: string, status: DependencyEntry["status"]): DependencyEntry {
  return { name, category, status, latencyMs: 5, lastCheckedAt: "2026-10-04T10:00:00Z", detail: "d" };
}

describe("statusMeta", () => {
  it("pairs every status with a label and an icon, not just a colour", () => {
    expect(statusMeta("UP")).toEqual({ label: "Up", tone: "ok", icon: "check" });
    expect(statusMeta("DEGRADED")).toEqual({ label: "Degraded", tone: "warn", icon: "alert" });
    expect(statusMeta("DOWN")).toEqual({ label: "Down", tone: "bad", icon: "x" });
    expect(statusMeta("UNKNOWN")).toEqual({ label: "Unknown", tone: "muted", icon: "help" });
  });

  it("never reads an unrecognised or missing status as healthy", () => {
    expect(statusMeta("OUT_OF_SERVICE").tone).toBe("muted");
    expect(statusMeta(null).label).toBe("Unknown");
    expect(statusMeta(undefined).icon).toBe("help");
    expect(statusMeta("").tone).toBe("muted");
  });

  it("maps every tone to tile classes", () => {
    expect(toneClasses("ok")).toContain("success");
    expect(toneClasses("warn")).toContain("warning");
    expect(toneClasses("bad")).toContain("destructive");
    expect(toneClasses("muted")).toContain("text-muted-foreground");
  });
});

describe("verdictMeta", () => {
  it("words each verdict and returns nothing for a missing one", () => {
    expect(verdictMeta("OK")?.label).toBe("Within budget");
    expect(verdictMeta("WARN")?.tone).toBe("warn");
    expect(verdictMeta("BREACH")?.tone).toBe("bad");
    expect(verdictMeta("INSUFFICIENT_DATA")?.tone).toBe("muted");
    expect(verdictMeta(null)).toBeUndefined();
    expect(verdictMeta(undefined)).toBeUndefined();
  });

  it("treats an unrecognised verdict as insufficient data, not as OK", () => {
    expect(verdictMeta("SOMETHING_NEW")?.tone).toBe("muted");
  });
});

describe("number formatting", () => {
  it("shows latency in the unit a person reads and n/a for no measurement", () => {
    expect(fmtLatency(12)).toBe("12 ms");
    expect(fmtLatency(0)).toBe("0 ms");
    expect(fmtLatency(999.6)).toBe("1000 ms");
    expect(fmtLatency(1250)).toBe("1.25 s");
    expect(fmtLatency(12500)).toBe("12.5 s");
    expect(fmtLatency(null)).toBe("n/a");
    expect(fmtLatency(undefined)).toBe("n/a");
    expect(fmtLatency(Number.NaN)).toBe("n/a");
  });

  it("formats percentages, burn and counts, with n/a for gaps", () => {
    expect(fmtPct(0.99512)).toBe("99.51%");
    expect(fmtPct(0.5, 0)).toBe("50%");
    expect(fmtPct(null)).toBe("n/a");
    expect(fmtBurn(0.8)).toBe("0.80x");
    expect(fmtBurn(null)).toBe("n/a");
    expect(fmtCount(1234567)).toBe("1,234,567");
    expect(fmtCount(null)).toBe("n/a");
  });

  it("formats a duration in seconds, minutes or hours", () => {
    expect(fmtDuration(45)).toBe("45 s");
    expect(fmtDuration(600)).toBe("10 min");
    expect(fmtDuration(12600)).toBe("3.5 h");
    expect(fmtDuration(null)).toBe("n/a");
    expect(fmtDuration(-1)).toBe("n/a");
  });

  it("words the error budget, including overspend and absence", () => {
    expect(budgetText(0.2)).toBe("20% of the error budget left");
    expect(budgetText(0)).toBe("0% of the error budget left");
    expect(budgetText(-7)).toBe("Error budget overspent (700% over)");
    expect(budgetText(null)).toBe("Error budget not computed");
  });
});

describe("dependency grouping", () => {
  it("orders categories fixed, keeps server order inside, and puts unknown categories last", () => {
    const groups = groupByCategory([
      entry("e2b-credential", "credential", "UP"),
      entry("redis", "cache", "UP"),
      entry("postgres", "database", "UP"),
      entry("llm-pool: a", "ai", "UP"),
      entry("sidecar", "ai", "DOWN"),
      entry("future", "zzz", "UNKNOWN"),
    ]);
    expect(groups.map((g) => g.category)).toEqual(["database", "cache", "ai", "credential", "zzz"]);
    expect(groups[2].items.map((i) => i.name)).toEqual(["llm-pool: a", "sidecar"]);
    expect(groups[0].label).toBe("Database");
    expect(groups[4].label).toBe("zzz");
  });

  it("returns nothing for no entries", () => {
    expect(groupByCategory([])).toEqual([]);
  });

  it("labels known categories and echoes unknown ones", () => {
    expect(categoryLabel("credential")).toContain("presence only");
    expect(categoryLabel("novel")).toBe("novel");
  });
});

describe("status counting and summary", () => {
  const entries = [
    entry("a", "database", "UP"),
    entry("b", "cache", "UP"),
    entry("c", "ai", "DEGRADED"),
    entry("d", "ai", "DOWN"),
    entry("e", "messaging", "UNKNOWN"),
  ];

  it("counts each status, folding unrecognised ones into unknown", () => {
    expect(countStatuses(entries)).toEqual({ UP: 2, DEGRADED: 1, DOWN: 1, UNKNOWN: 1 });
    const odd = [{ ...entry("x", "ai", "UP"), status: "WEIRD" as any }];
    expect(countStatuses(odd).UNKNOWN).toBe(1);
  });

  it("writes a one-line summary that names problems first and explains unknown", () => {
    expect(overallSummary(entries)).toBe("1 down · 1 degraded · 2 up · 1 unknown (not configured or not checkable)");
    expect(overallSummary([entry("a", "database", "UP")])).toBe("1 up");
    expect(overallSummary([])).toBe("No dependency readings are available.");
  });
});

describe("refresh policy", () => {
  it("polls only while the tab is visible", () => {
    expect(shouldPoll("visible")).toBe(true);
    expect(shouldPoll("hidden")).toBe(false);
    expect(shouldPoll(undefined)).toBe(true);
  });

  it("catches up on return only when the data is a full interval old", () => {
    const now = 1_000_000;
    expect(needsCatchUpRefresh(null, now)).toBe(true);
    expect(needsCatchUpRefresh(now - REFRESH_MS, now)).toBe(true);
    expect(needsCatchUpRefresh(now - REFRESH_MS + 1, now)).toBe(false);
  });

  it("labels the window choices", () => {
    expect(windowLabel(24)).toBe("24 h");
  });
});
