import { describe, expect, it } from "vitest";
import { normalizeRun, type RawTraceEntry, type RawWorkflowRun, type RunEdge } from "./runModel";
import { computeRunDisplay, settleFinishedTrace } from "./runOverlay";

const BASE = Date.parse("2026-09-29T14:02:00.000Z");
const iso = (offsetMs: number) => new Date(BASE + offsetMs).toISOString();

function step(nodeId: string, nodeType: string, start: number, end: number, summary = ""): RawTraceEntry {
  return {
    nodeId,
    nodeType,
    label: nodeId,
    status: "OK",
    summary,
    startedAt: iso(start),
    completedAt: iso(end),
    logLines: [],
  };
}

function liveRun(trace: RawTraceEntry[]): RawWorkflowRun {
  return {
    id: "run-1",
    workflowId: "wf-1",
    workflowName: "Supervisor flow",
    status: "RUNNING",
    requestedBy: "alice",
    errorMessage: null,
    trace,
    startedAt: iso(0),
    completedAt: null,
    outputFileName: null,
    workflowVersion: 1,
  };
}

// trigger -> sup; sup fans out to three agents on route-1/2/3, plus an error port.
const NODE_IDS = ["trigger", "sup", "a1", "a2", "a3", "oops"];
const EDGES: RunEdge[] = [
  { id: "e0", source: "trigger", target: "sup", sourceHandle: null },
  { id: "r1", source: "sup", target: "a1", sourceHandle: "route-1" },
  { id: "r2", source: "sup", target: "a2", sourceHandle: "route-2" },
  { id: "r3", source: "sup", target: "a3", sourceHandle: "route-3" },
  { id: "err", source: "sup", target: "oops", sourceHandle: "error" },
];

function display(supSummary: string) {
  const model = normalizeRun(liveRun([step("trigger", "trigger", 0, 10), step("sup", "supervisor", 20, 400, supSummary)]), {
    nowMs: BASE + 600,
  });
  return computeRunDisplay(model, Infinity, EDGES, NODE_IDS);
}

describe("computeRunDisplay - supervisor fan-out at the live edge", () => {
  it("lights only the route the supervisor chose", () => {
    const d = display("Supervisor routed to 'route-2'.");
    expect([...d.running]).toEqual(["a2"]);
    expect(d.edges.r2.state).toBe("flowing");
    expect(d.edges.r1.state).not.toBe("flowing");
    expect(d.edges.r3.state).not.toBe("flowing");
    expect(d.edges.err.state).not.toBe("flowing");
    expect(d.deciding.size).toBe(0);
  });

  it("follows the fallback route when the routing call failed", () => {
    const d = display("Supervisor defaulted to 'route-1' (routing call failed).");
    expect([...d.running]).toEqual(["a1"]);
    expect(d.edges.r1.state).toBe("flowing");
  });

  it("leaves every route pending and marks the source deciding when the choice is unreadable", () => {
    const d = display("Supervisor routed to 'route-");
    expect(d.running.size).toBe(0);
    for (const id of ["r1", "r2", "r3"]) expect(d.edges[id].state).toBe("pending");
    expect([...d.deciding]).toEqual(["sup"]);
  });

  it("still fans out along plain (unnamed) edges", () => {
    const edges: RunEdge[] = [
      { id: "e0", source: "trigger", target: "sup", sourceHandle: null },
      { id: "p1", source: "sup", target: "a1", sourceHandle: null },
      { id: "p2", source: "sup", target: "a2", sourceHandle: null },
    ];
    const model = normalizeRun(liveRun([step("trigger", "trigger", 0, 10), step("sup", "agent", 20, 400)]), { nowMs: BASE + 600 });
    const d = computeRunDisplay(model, Infinity, edges, ["trigger", "sup", "a1", "a2"]);
    expect([...d.running].sort()).toEqual(["a1", "a2"]);
    expect(d.edges.p1.state).toBe("flowing");
    expect(d.edges.p2.state).toBe("flowing");
  });
});

describe("settleFinishedTrace", () => {
  it("turns a stale AWAITING_* visit into OK once the run has ended", () => {
    const parked = { status: "AWAITING_WAIT", summary: "Waiting 1h", durationMs: 0 };
    expect(settleFinishedTrace(parked, true)?.status).toBe("OK");
    expect(settleFinishedTrace({ status: "PAUSED" }, true)?.status).toBe("OK");
  });

  it("leaves parked visits alone while the run is still live or parked", () => {
    const parked = { status: "AWAITING_APPROVAL" };
    expect(settleFinishedTrace(parked, false)).toBe(parked);
  });

  it("keeps real terminal statuses and undefined untouched", () => {
    const err = { status: "ERROR" };
    expect(settleFinishedTrace(err, true)).toBe(err);
    expect(settleFinishedTrace(undefined, true)).toBeUndefined();
  });
});
