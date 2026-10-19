// Every key the Insights screens read must exist in messages/en.json's "insights" namespace: t() returns the dotted
// path for a missing key (it never falls back), so a typo would render "insights.xyz" on screen. Keys chosen at
// runtime (the model's { key, values } sentences, the period buttons) are listed explicitly below. The reverse is
// checked too: a key in en.json that nothing reads is dead weight in 30 locale files.

import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";
import en from "../../messages/en.json";
import { MODEL_KEYS, PERIODS } from "./insightsModel";

const ns = (en as unknown as Record<string, Record<string, string>>).insights;
const nav = (en as unknown as Record<string, Record<string, string>>).nav;

const FILES = ["./page.tsx", "./InsightsTeaser.tsx"];
const DYNAMIC = [...MODEL_KEYS, ...PERIODS.map((p) => `period${p}`)];

function literalKeys(source: string): string[] {
  const keys = new Set<string>();
  for (const m of source.matchAll(/\bt\(\s*"([A-Za-z0-9_]+)"/g)) keys.add(m[1]);
  // t(cond ? "a" : "b", ...) - both branches are keys.
  for (const m of source.matchAll(/\bt\(\s*[^"`)]+\?\s*"([A-Za-z0-9_]+)"\s*:\s*"([A-Za-z0-9_]+)"/g)) {
    keys.add(m[1]);
    keys.add(m[2]);
  }
  return [...keys];
}

const sources = FILES.map((file) => ({ file, source: readFileSync(path.resolve(__dirname, file), "utf8") }));

describe("insights i18n", () => {
  it("has the namespace and the sidebar label", () => {
    expect(ns).toBeTypeOf("object");
    expect(nav.railInsights).toBe("Insights");
  });

  for (const { file, source } of sources) {
    it(`defines every literal key used by ${file}`, () => {
      const keys = literalKeys(source);
      expect(keys.length).toBeGreaterThan(0);
      expect(keys.filter((k) => !(k in ns))).toEqual([]);
    });

    it(`${file} reads only the insights namespace`, () => {
      const namespaces = [...source.matchAll(/useTranslations\(\s*"([\w.-]+)"/g)].map((m) => m[1]);
      expect(namespaces).toEqual(["insights"]);
    });
  }

  it("defines every key chosen at runtime", () => {
    expect(DYNAMIC.filter((k) => !(k in ns))).toEqual([]);
  });

  it("carries no key that nothing reads", () => {
    const used = new Set<string>([...DYNAMIC, ...sources.flatMap(({ source }) => literalKeys(source))]);
    expect(Object.keys(ns).filter((k) => !used.has(k))).toEqual([]);
  });

  it("keeps the placeholders the code passes values for", () => {
    const expectPlaceholders = (key: string, names: string[]) => {
      for (const name of names) expect(ns[key], `insights.${key}`).toContain(`{${name}}`);
    };
    for (const key of ["rangeNote", "rangeNoteLive"]) expectPlaceholders(key, ["from", "to"]);
    for (const key of ["callsSummary", "callsSummaryLive"]) expectPlaceholders(key, ["succeeded", "total", "from", "to"]);
    for (const key of ["costSummary", "costSummaryLive"]) expectPlaceholders(key, ["cost", "from", "to"]);
    for (const key of ["teaserSummary", "teaserSummaryLive"]) expectPlaceholders(key, ["succeeded", "total", "days"]);
    expectPlaceholders("topSummary", ["name", "cost", "total", "count"]);
    expectPlaceholders("topSummaryOne", ["name", "cost"]);
    expectPlaceholders("jobsSummary", ["succeeded", "failed", "live", "window"]);
    expectPlaceholders("jobsSummaryNoneFinished", ["live", "window"]);
    for (const key of ["jobsWindowDays", "jobsWindowHours", "jobsWindowMinutes"]) expectPlaceholders(key, ["count"]);
    expectPlaceholders("jobsFooter", ["window"]);
    expectPlaceholders("jobsEmptyBody", ["window"]);
    expectPlaceholders("jobsCancelled", ["count"]);
    expectPlaceholders("kpiCallsSub", ["failed"]);
    expectPlaceholders("kpiSuccessSub", ["succeeded", "total"]);
    expectPlaceholders("kpiLatencySub", ["p95"]);
    expectPlaceholders("callsValue", ["count"]);
    expectPlaceholders("callsValueOne", ["count"]);
    expectPlaceholders("rankValueOne", ["cost", "count"]);
    expectPlaceholders("costValue", ["cost"]);
    expectPlaceholders("rankValue", ["cost", "count"]);
    expectPlaceholders("unknownAgent", ["id"]);
  });

  it("always calls cost an estimate", () => {
    for (const key of ["kpiCost", "costChartTitle", "costSummary", "costSummaryLive", "colCost", "topAgentsTitle", "topModelsTitle", "topSummary", "topSummaryOne", "costValue", "rankValue", "rankValueOne", "noCostTitle"]) {
      expect(ns[key].toLowerCase(), `insights.${key}`).toMatch(/estimate/);
    }
  });

  it("contains no em dash", () => {
    for (const value of Object.values(ns)) expect(value).not.toMatch(/—/);
  });
});
