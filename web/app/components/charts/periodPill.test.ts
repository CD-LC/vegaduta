import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { PERIOD_PILL_FOCUS_CLASS } from "./periodPill";

// Local QA: the chart period pills (7d / 30d / 90d) showed almost no keyboard focus ring (Button's default is a 50%-opacity ring).
describe("period pill focus ring", () => {
  it("is a solid, offset ring, not the faint default", () => {
    expect(PERIOD_PILL_FOCUS_CLASS).toContain("focus-visible:ring-2");
    expect(PERIOD_PILL_FOCUS_CLASS).toContain("focus-visible:ring-ring ");
    expect(PERIOD_PILL_FOCUS_CLASS).toContain("focus-visible:ring-offset-2");
    expect(PERIOD_PILL_FOCUS_CLASS).not.toMatch(/ring-ring\/\d+/);
  });
  it("is applied to every period switch", () => {
    for (const f of ["../../insights/page.tsx", "../workflow/WorkflowHealthStrip.tsx"]) {
      expect(readFileSync(join(__dirname, f), "utf8")).toContain("PERIOD_PILL_FOCUS_CLASS}");
    }
  });
});
