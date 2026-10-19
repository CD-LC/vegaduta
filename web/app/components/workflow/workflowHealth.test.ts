import { describe, expect, it } from "vitest";
import type { WorkflowEvalSummary } from "../../lib/api/workflows";
import { buildWorkflowHealth, healthBand, nodeLabelsFromGraph, relativeTime, runStatusState } from "./workflowHealth";

const node = (over: Record<string, unknown> = {}): Record<string, unknown> => ({
  runs: 4,
  visits: 4,
  successRate: 1,
  errorRate: 0,
  p50DurationMs: 100,
  avgCostUsd: null,
  lastStatus: "OK",
  ...over,
});

function summary(workflow: Record<string, unknown> | null, nodes: Record<string, unknown> = {}): WorkflowEvalSummary {
  return { workflow, nodes, generatedAt: "2026-10-05T12:00:00Z" } as unknown as WorkflowEvalSummary;
}

describe("buildWorkflowHealth", () => {
  it("passes the server's numbers through and never invents a rate", () => {
    const h = buildWorkflowHealth(
      summary({ runs: 3, days: 7, sampled: false, successRate: null, p50DurationMs: null, avgCostUsd: null, lastRunAt: null, lastRunStatus: null }),
      {},
      7
    );
    expect(h.runs).toBe(3);
    expect(h.days).toBe(7);
    expect(h.successRate).toBeNull();
    expect(h.p50DurationMs).toBeNull();
    expect(h.avgCostUsd).toBeNull();
    expect(h.sampled).toBe(false);
  });

  it("is all zeros / nulls for a missing or empty summary", () => {
    for (const input of [null, undefined, summary(null)]) {
      const h = buildWorkflowHealth(input, {}, 30);
      expect(h).toMatchObject({ runs: 0, days: 30, sampled: false, successRate: null, nodes: [], slowest: [], mostFailing: [] });
    }
  });

  it("treats a rate outside 0..1, NaN or a negative as unknown rather than clamping it", () => {
    const h = buildWorkflowHealth(summary({ runs: 2, successRate: 92, p50DurationMs: -5, avgCostUsd: Number.NaN }));
    expect(h.successRate).toBeNull();
    expect(h.p50DurationMs).toBeNull();
    expect(h.avgCostUsd).toBeNull();
  });

  it("reads `sampled` as the boolean core sends", () => {
    expect(buildWorkflowHealth(summary({ runs: 500, sampled: true })).sampled).toBe(true);
    expect(buildWorkflowHealth(summary({ runs: 500 })).sampled).toBe(false);
  });

  it("ranks the three slowest and three most-failing nodes, resolving names", () => {
    const h = buildWorkflowHealth(
      summary(
        { runs: 10, days: 30 },
        {
          a: node({ p50DurationMs: 100, errorRate: 0 }),
          b: node({ p50DurationMs: 900, errorRate: 0.5 }),
          c: node({ p50DurationMs: 300, errorRate: 0.1 }),
          d: node({ p50DurationMs: 2000, errorRate: null }),
          e: node({ p50DurationMs: null, errorRate: 0.25 }),
        }
      ),
      { b: "Draft reply", d: "Fetch order" }
    );
    expect(h.slowest.map((n) => n.label)).toEqual(["Fetch order", "Draft reply", "c"]);
    expect(h.mostFailing.map((n) => n.id)).toEqual(["b", "e", "c"]);
  });

  it("leaves out nodes with no failures / no timing instead of ranking them last", () => {
    const h = buildWorkflowHealth(summary({ runs: 1 }, { a: node({ p50DurationMs: null, errorRate: 0 }), b: node({ p50DurationMs: 0, errorRate: null }) }));
    expect(h.slowest).toEqual([]);
    expect(h.mostFailing).toEqual([]);
    expect(h.nodes).toHaveLength(2);
  });

  it("skips nodes that were never visited (eval-score-only agent nodes)", () => {
    const h = buildWorkflowHealth(summary({ runs: 1 }, { scored: node({ runs: 0, visits: 0, evalScore: 0.9 }), ran: node(), junk: null }));
    expect(h.nodes.map((n) => n.id)).toEqual(["ran"]);
  });
});

describe("healthBand", () => {
  it("has no verdict for a null rate", () => {
    expect(healthBand(null)).toEqual({ state: "idle", key: "none" });
  });
  it("bands a real rate, including a real 0", () => {
    expect(healthBand(1).key).toBe("healthy");
    expect(healthBand(0.95).key).toBe("healthy");
    expect(healthBand(0.8).key).toBe("attention");
    expect(healthBand(0).key).toBe("failing");
  });
});

describe("runStatusState", () => {
  it("maps core's run statuses", () => {
    expect(runStatusState("COMPLETED")).toBe("success");
    expect(runStatusState("failed")).toBe("error");
    expect(runStatusState("RUNNING")).toBe("running");
    expect(runStatusState("AWAITING_APPROVAL")).toBe("warning");
    expect(runStatusState("CANCELLED")).toBe("idle");
    expect(runStatusState(null)).toBe("idle");
  });
});

describe("nodeLabelsFromGraph", () => {
  it("reads data.label by node id and tolerates junk", () => {
    expect(nodeLabelsFromGraph({ nodes: [{ id: "n1", data: { label: " Send email " } }, { id: "n2", data: {} }, null, { data: { label: "x" } }] })).toEqual({
      n1: "Send email",
    });
    expect(nodeLabelsFromGraph(null)).toEqual({});
    expect(nodeLabelsFromGraph({ nodes: "nope" })).toEqual({});
  });
});

describe("relativeTime", () => {
  const now = Date.parse("2026-10-05T12:00:00Z");
  it("formats against the given clock", () => {
    expect(relativeTime("2026-10-05T09:00:00Z", now, "en")).toBe("3 hours ago");
    expect(relativeTime("2026-10-03T12:00:00Z", now, "en")).toBe("2 days ago");
    expect(relativeTime("2026-10-05T11:59:58Z", now, "en")).toBe("now");
  });
  it("reads a slightly-future time as now and junk as null", () => {
    expect(relativeTime("2026-10-05T12:00:30Z", now, "en")).toBe("now");
    expect(relativeTime("not a date", now, "en")).toBeNull();
    expect(relativeTime(null, now, "en")).toBeNull();
    expect(relativeTime("2026-10-05T09:00:00Z", null, "en")).toBeNull();
  });
});
