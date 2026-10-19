import { test, expect } from "@playwright/test";
import { writeFileSync, mkdirSync } from "node:fs";

/**
 * On-device FAQ eval — collects the report produced by /eval/edge-faq,
 * which runs the canonical core/quality edge-faq dataset through the real
 * routeTurn() inside the browser (the only place WebLLM / Chrome
 * built-in / WASM tiers exist).
 *
 * Honesty contract (mirrors web/app/lib/edge/faqEval.ts):
 *   * status "edge-unavailable" = the build/device cannot route on-device
 *     (e.g. NEXT_PUBLIC_EDGE_AGENTS unset). The spec PASSES — it is a
 *     measured skip, recorded in the report + a test annotation — it must
 *     NOT fail CI and must NOT be read as a model-quality result.
 *   * status "completed" = at least one turn was genuinely answered
 *     on-device; every case still carries its rubric for offline judging.
 *   * The report is always written to test-results/edge-faq-eval.json.
 *
 * Run:  npx playwright test e2e/edge-faq-eval.spec.ts
 * (Edge attempt requires a build with NEXT_PUBLIC_EDGE_AGENTS=1.)
 */

test.describe("edge FAQ eval", () => {
  // A cold WebLLM model download + 15 sequential turns can take minutes.
  test.setTimeout(15 * 60 * 1000);

  test("runs the FAQ dataset through routeTurn and emits a report", async ({ page }) => {
    await page.goto("/eval/edge-faq?autorun=1");

    await page.waitForFunction(() => window.__FAQ_EVAL_RESULT__ !== undefined, null, {
      timeout: 14 * 60 * 1000,
      polling: 2000,
    });

    const report = await page.evaluate(() => window.__FAQ_EVAL_RESULT__);

    mkdirSync("test-results", { recursive: true });
    writeFileSync("test-results/edge-faq-eval.json", JSON.stringify(report, null, 2));

    // Shape assertions — hold regardless of device capability.
    expect(report.datasetCaseCount).toBeGreaterThan(0);
    expect(report.results).toHaveLength(report.datasetCaseCount);
    for (const r of report.results) {
      expect(["handled", "fallback", "error"]).toContain(r.outcome);
      expect(typeof r.latencyMs).toBe("number");
      if (r.outcome === "handled") {
        expect([0, 1, 2]).toContain(r.tier);
        expect(typeof r.text).toBe("string");
      }
      if (r.outcome === "fallback") {
        expect(typeof r.reason).toBe("string");
      }
    }

    if (report.status === "edge-unavailable") {
      test.info().annotations.push({
        type: "edge-unavailable",
        description:
          "No on-device tier served any case (edge build flag off, or device lacks a local model). Measured skip — not a pass, not a model failure.",
      });
      return;
    }

    expect(["completed", "aborted"]).toContain(report.status);
    if (report.status === "completed") {
      // At least one turn really ran on-device — this is the only state
      // that may be read as "the edge tier served FAQ traffic".
      expect(report.summary.handled).toBeGreaterThan(0);
    }
  });
});
