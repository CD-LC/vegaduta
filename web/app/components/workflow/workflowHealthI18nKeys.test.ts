// Every t("...") the workflow health strip and the run-history page read must
// exist in messages/en.json's "workflowRunsPage" namespace: t() returns the
// dotted path for a missing key (it never falls back), so a typo would render
// "workflowRunsPage.xyz" on screen. Keys built at runtime are listed below.

import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";
import en from "../../../messages/en.json";
import { KNOWN_RUN_STATUSES } from "./workflowHealth";

const ns = (en as unknown as Record<string, Record<string, string>>).workflowRunsPage;

const FILES = ["./WorkflowHealthStrip.tsx", "../../workflows/[id]/runs/page.tsx"];

function literalKeys(source: string): string[] {
  const keys = new Set<string>();
  for (const m of source.matchAll(/\bt\(\s*"([A-Za-z0-9_]+)"/g)) keys.add(m[1]);
  return [...keys];
}

describe("workflowRunsPage i18n (health strip)", () => {
  for (const file of FILES) {
    it(`defines every literal key used by ${file}`, () => {
      const source = readFileSync(path.resolve(__dirname, file), "utf8");
      expect([...source.matchAll(/useTranslations\(\s*"([\w.-]+)"/g)].map((m) => m[1])).toEqual(
        // The run-history page also reads its filter/retry strings from the studio namespace (tu(...)).
        file.includes("runs/page") ? ["workflowRunsPage", "workflowStudioUi"] : ["workflowRunsPage"]
      );
      const keys = literalKeys(source);
      expect(keys.length).toBeGreaterThan(0);
      expect(keys.filter((k) => !(k in ns))).toEqual([]);
    });
  }

  it("defines every dynamically built key", () => {
    const dynamic = [
      ...KNOWN_RUN_STATUSES.map((s) => `healthStatus_${s}`),
      ...["healthy", "attention", "failing", "none"].map((b) => `healthBand_${b}`),
    ];
    expect(dynamic.filter((k) => !(k in ns))).toEqual([]);
  });

  it("uses every health* key it defines", () => {
    const source = readFileSync(path.resolve(__dirname, FILES[0]), "utf8");
    const used = new Set(literalKeys(source));
    const unused = Object.keys(ns).filter(
      (k) => k.startsWith("health") && !used.has(k) && !k.startsWith("healthStatus_") && !k.startsWith("healthBand_")
    );
    expect(unused).toEqual([]);
  });

  it("keeps the placeholders the code passes values for", () => {
    for (const key of ["healthSummary", "healthSummaryNoRate"]) {
      expect(ns[key]).toContain("{days}");
      expect(ns[key]).toContain("{runs}");
    }
    expect(ns.healthSummary).toContain("{rate}");
    expect(ns.healthSampledNote).toContain("{count}");
    expect(ns.healthFailedShare).toContain("{percent}");
    for (const key of ["healthPeriodOption", "healthPeriodOptionAria", "healthRunsHint", "healthEmptyTitle"]) {
      expect(ns[key]).toContain("{days}");
    }
  });

  it("calls cost an estimate and contains no em dash in the new strings", () => {
    expect(ns.healthCostLabel.toLowerCase()).toContain("estimated");
    expect(ns.healthColCost.toLowerCase()).toContain("estimated");
    for (const [key, value] of Object.entries(ns)) {
      if (key.startsWith("health")) expect(value).not.toMatch(/—/);
    }
  });
});
