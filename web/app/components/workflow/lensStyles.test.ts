import { describe, expect, it } from "vitest";
import {
  EMPTY_LENS_STATS,
  buildLensStats,
  criticalPath,
  edgeKey,
  heatIntensity,
  latestRuns,
  legend,
  lensEdgeStyle,
  lensNodeStyle,
  type LensEdgeRef,
  type LensRun,
} from "./lensStyles";

// a -> b -> d, a -> c -> d, plus a dead branch a -> x.
const edges: LensEdgeRef[] = [
  { id: "e1", source: "a", target: "b" },
  { id: "e2", source: "a", target: "c" },
  { id: "e3", source: "b", target: "d" },
  { id: "e4", source: "c", target: "d" },
  { id: "e5", source: "a", target: "x" },
];

const t = (s: number) => new Date(Date.UTC(2026, 8, 29, 10, 0, s)).toISOString();

// Fan-out: b is slow (5s), c is quick (1s), so d waits on b.
const run1: LensRun = {
  id: "r1",
  startedAt: t(0),
  trace: [
    { nodeId: "a", label: "Start", status: "OK", startedAt: t(0), completedAt: t(1) },
    { nodeId: "b", label: "Call LLM", status: "OK", startedAt: t(1), completedAt: t(6) },
    { nodeId: "c", label: "Lookup", status: "OK", startedAt: t(1), completedAt: t(2) },
    { nodeId: "d", label: "Reply", status: "OK", startedAt: t(6), completedAt: t(7) },
    { nodeId: "x", status: "SKIPPED" },
  ],
};

const run2: LensRun = {
  id: "r2",
  startedAt: t(30),
  trace: [
    { nodeId: "a", status: "OK", startedAt: t(30), completedAt: t(31) },
    { nodeId: "c", status: "ERROR", startedAt: t(31), completedAt: t(32) },
  ],
};

describe("criticalPath", () => {
  it("follows the predecessor that finished last", () => {
    const cp = criticalPath(run1, edges);
    expect(cp.nodeIds).toEqual(["a", "b", "d"]);
    expect(cp.edgeKeys).toEqual([edgeKey("a", "b"), edgeKey("b", "d")]);
    expect(cp.durationMs).toBe(7000);
  });

  it("keeps repeated visits of a looping node", () => {
    const loop: LensRun = {
      id: "l",
      trace: [
        { nodeId: "a", status: "OK", startedAt: t(0), completedAt: t(1) },
        { nodeId: "b", status: "OK", startedAt: t(1), completedAt: t(2) },
        { nodeId: "a", status: "OK", startedAt: t(2), completedAt: t(3) },
        { nodeId: "b", status: "OK", startedAt: t(3), completedAt: t(4) },
      ],
    };
    const cp = criticalPath(loop, [{ source: "a", target: "b" }, { source: "b", target: "a" }]);
    expect(cp.nodeIds).toEqual(["a", "b", "a", "b"]);
  });

  it("falls back to trace order and never loops without timestamps", () => {
    const bare: LensRun = { id: "z", trace: [{ nodeId: "a" }, { nodeId: "b" }, { nodeId: "d" }] };
    expect(criticalPath(bare, edges).nodeIds).toEqual(["a", "b", "d"]);
    expect(criticalPath(null, edges).nodeIds).toEqual([]);
  });
});

describe("buildLensStats", () => {
  const stats = buildLensStats([run1, run2], edges, run1);

  it("counts executed runs, failures and visits per node", () => {
    expect(stats.windowRuns).toBe(2);
    expect(stats.nodes.a).toMatchObject({ runsExecuted: 2, runsFailed: 0, visits: 2, avgDurationMs: 1000 });
    expect(stats.nodes.c).toMatchObject({ runsExecuted: 2, runsFailed: 1 });
    expect(stats.nodes.x).toBeUndefined(); // SKIPPED is not an execution
    expect(stats.maxAvgDurationMs).toBe(5000);
  });

  it("prefers durationMs over timestamps when core sends it", () => {
    const s = buildLensStats([{ id: "q", trace: [{ nodeId: "a", status: "OK", durationMs: 42, startedAt: t(0), completedAt: t(9) }] }], []);
    expect(s.nodes.a.avgDurationMs).toBe(42);
  });

  it("tracks edge traversals", () => {
    expect(stats.edgeTraversals[edgeKey("a", "c")]).toBe(2);
    expect(stats.edgeTraversals[edgeKey("a", "b")]).toBe(1);
    expect(stats.edgeTraversals[edgeKey("a", "x")]).toBeUndefined();
  });

  describe("lenses", () => {
    it("none is neutral", () => {
      expect(lensNodeStyle("none", "a", stats)).toEqual({ className: "", dim: false, intensity: 0 });
    });

    it("heat: slowest node is hottest and badged", () => {
      const b = lensNodeStyle("heat", "b", stats, "en-US");
      expect(b.intensity).toBe(1);
      expect(b.className).toContain("wfx-lens-band-high");
      expect(b.badge).toBe("5s");
      expect(lensNodeStyle("heat", "a", stats).intensity).toBeLessThan(1);
      expect(lensNodeStyle("heat", "x", stats).dim).toBe(true);
    });

    it("failures: rate as fraction badge, clean nodes recede", () => {
      const c = lensNodeStyle("failures", "c", stats);
      expect(c.badge).toBe("1/2");
      expect(c.intensity).toBe(0.5);
      expect(lensNodeStyle("failures", "a", stats).dim).toBe(true);
    });

    it("critical: path glows, the rest dims", () => {
      expect(lensNodeStyle("critical", "b", stats)).toMatchObject({ className: "wfx-lens-critical", badge: "2", dim: false });
      expect(lensNodeStyle("critical", "c", stats).dim).toBe(true);
      expect(lensEdgeStyle("critical", edges[2], stats).className).toContain("wfx-lens-critical");
      expect(lensEdgeStyle("critical", edges[1], stats).dim).toBe(true);
    });

    it("coverage: never-run nodes and edges are ghosted", () => {
      expect(lensNodeStyle("coverage", "x", stats)).toMatchObject({ className: "wfx-lens-ghost", dim: true, badge: "never ran" });
      expect(lensNodeStyle("coverage", "d", stats).badge).toBe("1/2");
      expect(lensEdgeStyle("coverage", edges[4], stats).className).toContain("wfx-lens-ghost");
    });

    it("no data never ghosts the whole canvas", () => {
      const s = lensNodeStyle("coverage", "a", EMPTY_LENS_STATS);
      expect(s.dim).toBe(false);
      expect(s.className).toBe("wfx-lens-nodata");
    });
  });

  it("legend headlines summarise the window", () => {
    expect(legend("heat", stats, { locale: "en-US" }).headline).toBe("Slowest: Call LLM · 5s avg");
    expect(legend("failures", stats, { locale: "en-US" }).headline).toBe("Most fragile: Lookup · 50%");
    expect(legend("coverage", stats, { nodeIds: ["a", "b", "c", "d", "x"] }).headline).toBe("1 never-run node in 2 runs");
    expect(legend("critical", stats, { locale: "en-US" }).headline).toBe("3 steps · 7s");
    expect(legend("none").kind).toBe("none");
    expect(legend("heat").headline).toBeUndefined();
  });
});

describe("helpers", () => {
  it("heat is log-scaled and bounded", () => {
    expect(heatIntensity(null, 100)).toBe(0);
    expect(heatIntensity(100, 0)).toBe(0);
    expect(heatIntensity(10, 10_000)).toBeGreaterThan(0.2); // linear would give 0.001
    expect(heatIntensity(99_999, 10)).toBe(1);
  });

  it("latestRuns sorts newest first and caps at N", () => {
    const got = latestRuns([run1, run2, { id: "old", startedAt: t(-100) }, { id: "nots" }], 3);
    expect(got.map((r) => r.id)).toEqual(["r2", "r1", "old"]);
  });
});
