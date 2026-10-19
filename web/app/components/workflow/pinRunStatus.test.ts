import { describe, expect, it } from "vitest";
import { frameAt, normalizeRun, type RawTraceEntry, type RawWorkflowRun, type RunEdge } from "./runModel";
import { frameTrace, toInspectorVisit } from "./runOverlay";

const BASE = Date.parse("2026-09-30T10:00:00.000Z");
const iso = (ms: number) => new Date(BASE + ms).toISOString();

function entry(nodeId: string, start: number, end: number, status: string, extra: Partial<RawTraceEntry> = {}): RawTraceEntry {
  return { nodeId, nodeType: "http", label: nodeId, status, summary: "", startedAt: iso(start), completedAt: iso(end), logLines: [], ...extra };
}

function run(trace: RawTraceEntry[]): RawWorkflowRun {
  return {
    id: "run-9",
    workflowId: "wf-1",
    workflowName: "Draft flow",
    status: "COMPLETED",
    requestedBy: "me",
    errorMessage: null,
    trace,
    startedAt: iso(0),
    completedAt: iso(100),
    outputFileName: null,
    workflowVersion: 1,
  };
}

const edges: RunEdge[] = [{ id: "a->b", source: "a", target: "b", sourceHandle: null }];

describe("PINNED trace status", () => {
  const model = () => normalizeRun(run([entry("a", 0, 10, "PINNED", { outputJson: '{"x":1}' }), entry("b", 20, 40, "OK")]));

  it("is success-like in the model but flagged as pinned", () => {
    const m = model();
    const a = m.visits.find((v) => v.nodeId === "a")!;
    const b = m.visits.find((v) => v.nodeId === "b")!;
    expect(a.status).toBe("ok");
    expect(a.rawStatus).toBe("PINNED");
    expect(a.pinned).toBe(true);
    expect(b.pinned).toBeFalsy();
  });

  it("lets the run flow past the pinned node (edge taken, node ok)", () => {
    const f = frameAt(model(), 100, edges);
    expect(f.nodes.a.status).toBe("ok");
    expect(f.edges["a->b"].state).toBe("taken");
  });

  it("keeps the PINNED wire status on the node card overlay and the inspector visit", () => {
    const m = model();
    const f = frameAt(m, 100, edges);
    expect(frameTrace(f.nodes.a)?.status).toBe("PINNED");
    expect(frameTrace(f.nodes.b)?.status).toBe("OK");
    const v = toInspectorVisit(m.visits.find((x) => x.nodeId === "a")!);
    expect(v.status).toBe("PINNED");
    expect(v.outputJson).toBe('{"x":1}');
  });
});
