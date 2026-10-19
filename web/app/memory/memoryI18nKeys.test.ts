// Every t("...") the Memory overview reads must exist in messages/en.json's "memoryPage" namespace: t() returns the
// dotted path for a missing key (it never falls back), so a typo would render "memoryPage.xyz" on screen.

import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";
import en from "../../messages/en.json";

const ns = (en as any).memoryPage as Record<string, string>;

const FILES = ["./MemoryOverview.tsx"];

function literalKeys(source: string): string[] {
  const keys = new Set<string>();
  for (const m of source.matchAll(/\bt\(\s*"([A-Za-z0-9_]+)"/g)) keys.add(m[1]);
  return [...keys];
}

describe("memoryPage i18n (overview)", () => {
  it("has the namespace", () => {
    expect(ns).toBeTruthy();
  });

  for (const file of FILES) {
    it(`defines every literal key used by ${file}`, () => {
      const source = readFileSync(path.resolve(__dirname, file), "utf8");
      const keys = literalKeys(source);
      expect(keys.length).toBeGreaterThan(0);
      expect(keys.filter((k) => !(k in ns))).toEqual([]);
    });
  }

  it("defines every key built at runtime (singular / plural and scope picks)", () => {
    const dynamic = [
      "overviewScopeSummary",
      "overviewScopeSummaryOne",
      "overviewScopeAgent",
      "overviewScopeShared",
      "overviewUsedSummary",
      "overviewUsedNone",
      "usageOnce",
      "usageMany",
    ];
    expect(dynamic.filter((k) => !(k in ns))).toEqual([]);
  });

  it("uses every overview key it defines", () => {
    const source = readFileSync(path.resolve(__dirname, "./MemoryOverview.tsx"), "utf8");
    const unused = Object.keys(ns).filter((k) => k.startsWith("overview") && !source.includes(`"${k}"`));
    expect(unused).toEqual([]);
  });

  it("keeps the placeholders the code passes values for", () => {
    for (const key of ["overviewScopeSummary", "overviewScopeSummaryOne"]) {
      expect(ns[key]).toContain("{agent}");
      expect(ns[key]).toContain("{shared}");
    }
    expect(ns.overviewScopeSummary).toContain("{total}");
    for (const slot of ["{count}", "{total}"]) expect(ns.overviewNeverUsedValue).toContain(slot);
    for (const slot of ["{used}", "{total}", "{top}"]) expect(ns.overviewUsedSummary).toContain(slot);
    for (const slot of ["{shown}", "{used}"]) expect(ns.overviewUsedCaption).toContain(slot);
    expect(ns.usageMany).toContain("{count}");
  });

  it("adds no em dash in the overview strings", () => {
    for (const [key, value] of Object.entries(ns)) {
      if (key.startsWith("overview")) expect(value, key).not.toMatch(/—/);
    }
  });
});
