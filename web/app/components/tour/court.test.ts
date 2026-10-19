// The Court's region table against the REAL tour catalogue: a region can only
// walk steps that exist, and no step may be claimed by two regions.

import { describe, expect, it } from "vitest";
import { TOUR_STEPS } from "../TourProvider";
import { COURT_DOCK_BREAKPOINT, COURT_REGIONS, courtIsDocked, courtRegion, regionSteps } from "./court";

describe("COURT_REGIONS", () => {
  it("names five regions, each with a unique rail marker", () => {
    expect(COURT_REGIONS.map((r) => r.id)).toEqual(["build", "connect", "config", "monitor", "plans"]);
    expect(new Set(COURT_REGIONS.map((r) => r.railTarget)).size).toBe(5);
  });

  it("maps the launch steps to Build and the setup rows to Config", () => {
    // Region assignment follows where the row lives in the rail: the launch
    // story's app/agent/workflow/knowledge stops sit under Build, and Config
    // keeps its actual setup rows. The admin step belongs to no region - the
    // Court has no Admin region and nav-admin lives under navGroupAdmin.
    expect(courtRegion("build")?.stepIds).toEqual([
      "build",
      "agents",
      "workflows",
      "knowledge",
      "shared-with-me",
    ]);
    expect(courtRegion("config")?.stepIds).toEqual(["channels", "tools", "edge"]);
    for (const r of COURT_REGIONS) expect(r.stepIds).not.toContain("admin");
  });

  it("only refers to steps that exist, and never twice", () => {
    const known = new Set(TOUR_STEPS.map((s) => s.id));
    const seen = new Set<string>();
    for (const r of COURT_REGIONS) {
      for (const id of r.stepIds) {
        expect(known.has(id), `${r.id}: unknown step ${id}`).toBe(true);
        expect(seen.has(id), `${id} claimed twice`).toBe(false);
        seen.add(id);
      }
    }
  });

  it("gives a region with no steps somewhere to send the click", () => {
    for (const r of COURT_REGIONS) if (r.stepIds.length === 0) expect(r.href).toMatch(/^\//);
  });
});

describe("regionSteps", () => {
  it("keeps tour order and drops steps the visitor does not walk", () => {
    const build = courtRegion("build")!;
    // A member's list: no channels/tools, but agents and knowledge.
    const list = [{ id: "start" }, { id: "agents" }, { id: "knowledge" }, { id: "help" }];
    expect(regionSteps(build, list).map((s) => s.id)).toEqual(["agents", "knowledge"]);
    expect(regionSteps(courtRegion("plans")!, list)).toEqual([]);
  });
});

describe("courtRegion / docking", () => {
  it("looks a region up and rejects a stranger", () => {
    expect(courtRegion("config")?.id).toBe("config");
    expect(courtRegion("nope")).toBeNull();
    expect(courtRegion(null)).toBeNull();
  });

  it("docks below the tour card's own breakpoint only", () => {
    expect(courtIsDocked(COURT_DOCK_BREAKPOINT - 1)).toBe(true);
    expect(courtIsDocked(COURT_DOCK_BREAKPOINT)).toBe(false);
    expect(courtIsDocked(0)).toBe(false);
  });
});
