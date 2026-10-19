import { describe, expect, it } from "vitest";
import {
  aggregateRuns,
  criticalPath,
  criticalPathDetail,
  formatCostUsd,
  formatDuration,
  frameAt,
  keyframes,
  linkVisits,
  narrate,
  narrateLines,
  normalizeRun,
  percentile,
  toEdgeRunState,
  type RawTraceEntry,
  type RawWorkflowRun,
  type RunEdge,
} from "./runModel";

// All fixtures sit on one fixed UTC instant so clock text is deterministic.
const BASE = Date.parse("2026-09-29T14:02:00.000Z");
const iso = (offsetMs: number) => new Date(BASE + offsetMs).toISOString();

function step(
  nodeId: string,
  start: number | null,
  end: number | null,
  status = "OK",
  extra: Partial<RawTraceEntry> = {},
): RawTraceEntry {
  return {
    nodeId,
    nodeType: extra.nodeType ?? (nodeId === "trigger" ? "trigger" : "agent"),
    label: extra.label ?? nodeId,
    status,
    summary: extra.summary ?? "",
    startedAt: start == null ? null : iso(start),
    completedAt: end == null ? null : iso(end),
    logLines: [],
    ...extra,
  };
}

function run(trace: RawTraceEntry[], status = "COMPLETED", extra: Partial<RawWorkflowRun> = {}): RawWorkflowRun {
  return {
    id: "run-1",
    workflowId: "wf-1",
    workflowName: "Test flow",
    status,
    requestedBy: "alice",
    errorMessage: null,
    trace,
    startedAt: iso(0),
    completedAt: status === "RUNNING" ? null : iso(Math.max(0, ...trace.map((t) => (t.completedAt ? Date.parse(String(t.completedAt)) - BASE : 0)))),
    outputFileName: null,
    workflowVersion: 3,
    ...extra,
  };
}

const edge = (source: string, target: string, sourceHandle?: string): RunEdge => ({
  id: `${source}->${target}${sourceHandle ? `:${sourceHandle}` : ""}`,
  source,
  target,
  sourceHandle: sourceHandle ?? null,
});

const label = (id: string) => ({ research: "Research Agent", email: "Send Email", cond: "Is urgent?" } as Record<string, string>)[id];
const narrateOpts = { locale: "en-GB", timeZone: "UTC" };

// ---------------------------------------------------------------------------

describe("formatDuration", () => {
  it("scales units", () => {
    expect(formatDuration(0)).toBe("0ms");
    expect(formatDuration(340)).toBe("340ms");
    expect(formatDuration(8200)).toBe("8.2s");
    expect(formatDuration(9960)).toBe("10s");
    expect(formatDuration(42_000)).toBe("42s");
    expect(formatDuration(185_000)).toBe("3m 05s");
    expect(formatDuration(3_720_000)).toBe("1h 02m");
  });
  it("marks unknown and clamps negatives", () => {
    expect(formatDuration(null)).toBe("—");
    expect(formatDuration(undefined)).toBe("—");
    expect(formatDuration(Number.NaN)).toBe("—");
    expect(formatDuration(-5)).toBe("0ms");
  });
});

describe("formatCostUsd", () => {
  it("uses Intl currency and keeps tiny LLM costs readable", () => {
    const tiny = formatCostUsd(0.0042, "en-US");
    expect(tiny).toContain("0.0042");
    expect(formatCostUsd(1.5, "en-US")).toContain("1.50");
    expect(formatCostUsd(null)).toBe("—");
  });
});

describe("percentile", () => {
  it("uses nearest rank", () => {
    const xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
    expect(percentile(xs, 50)).toBe(5);
    expect(percentile(xs, 95)).toBe(10);
    expect(percentile([], 50)).toBeNull();
  });
});

describe("normalizeRun", () => {
  it("builds a sorted, time-indexed model", () => {
    const m = normalizeRun(run([step("trigger", 0, 10), step("research", 20, 8220), step("email", 8230, 8500)]));
    expect(m.run.status).toBe("completed");
    expect(m.run.workflowVersion).toBe(3);
    expect(m.visits.map((v) => v.nodeId)).toEqual(["trigger", "research", "email"]);
    expect(m.t0).toBe(BASE);
    expect(m.totalMs).toBe(8500);
    expect(m.visits[1].durationMs).toBe(8200);
    expect(m.visits[1].timing).toBe("exact");
    expect(m.live).toBe(false);
  });

  it("keeps every visit of a looping node with per-node visit indexes", () => {
    const m = normalizeRun(
      run([step("a", 0, 10), step("b", 20, 30), step("a", 40, 60), step("b", 70, 90), step("a", 100, 130)]),
    );
    expect(m.visits).toHaveLength(5);
    expect(m.byNode.a.map((v) => v.visitIndex)).toEqual([0, 1, 2]);
    expect(m.byNode.a.every((v) => v.visitCount === 3)).toBe(true);
    expect(m.byNode.a.map((v) => v.key)).toEqual(["a#0", "a#1", "a#2"]);
    expect(new Set(m.visits.map((v) => v.key)).size).toBe(5);
  });

  it("sorts by start even when the trace was appended out of order", () => {
    const m = normalizeRun(run([step("slow", 0, 500), step("fast", 100, 200)]));
    expect(m.visits.map((v) => v.nodeId)).toEqual(["slow", "fast"]);
    const m2 = normalizeRun(run([step("fast", 100, 200), step("slow", 0, 500)]));
    expect(m2.visits.map((v) => v.nodeId)).toEqual(["slow", "fast"]);
    expect(m2.visits.map((v) => v.traceIndex)).toEqual([1, 0]);
  });

  it("derives timing from durationMs and estimates missing timestamps", () => {
    const m = normalizeRun(
      run([
        step("a", 0, 100),
        step("b", null, 400, "OK", { durationMs: 250 }),
        step("c", 500, null, "OK", { durationMs: 40 }),
        step("d", null, null, "OK"),
      ]),
    );
    const [a, b, c, d] = m.visits;
    expect(a.nodeId).toBe("a");
    expect(b.startMs - BASE).toBe(150);
    expect(b.timing).toBe("derived");
    expect(c.endMs! - BASE).toBe(540);
    expect(c.durationMs).toBe(40);
    expect(d.timing).toBe("estimated");
    expect(d.durationMs).toBeNull();
    expect(d.startMs - BASE).toBe(540); // placed right after its predecessor
  });

  it("survives a run with no timestamps at all", () => {
    const m = normalizeRun({
      status: "COMPLETED",
      trace: [
        { nodeId: "a", status: "OK" },
        { nodeId: "b", status: "OK" },
      ],
    });
    expect(m.visits.map((v) => v.nodeId)).toEqual(["a", "b"]);
    expect(m.totalMs).toBe(0);
    expect(m.visits.every((v) => v.durationMs === null)).toBe(true);
    expect(narrate(m, () => null)).toContain("Finished successfully across 2 steps");
  });

  it("accepts a JSON-string trace and garbage input", () => {
    const m = normalizeRun({ status: "COMPLETED", trace: JSON.stringify([{ nodeId: "a", status: "OK" }]) });
    expect(m.visits).toHaveLength(1);
    expect(normalizeRun({ trace: "not json" }).visits).toHaveLength(0);
    expect(normalizeRun(null).run.status).toBe("unknown");
  });

  it("reads optional per-step data defensively", () => {
    const m = normalizeRun(
      run([
        step("research", 0, 100, "OK", {
          inputJson: "{\"q\":1}",
          outputJson: "hi",
          outputTruncated: true,
          tokensIn: 100,
          tokensOut: "20",
          costUsd: "0.0031",
        }),
        step("email", 100, 120),
      ]),
    );
    const v = m.visits[0];
    expect(v.input).toBe("{\"q\":1}");
    expect(v.outputTruncated).toBe(true);
    expect(v.inputTruncated).toBe(false);
    expect(v.tokensOut).toBe(20);
    expect(v.costUsd).toBeCloseTo(0.0031);
    expect(m.visits[1].tokensIn).toBeNull();
    expect(m.totals).toEqual({ tokensIn: 100, tokensOut: 20, costUsd: 0.0031 });
    expect(normalizeRun(run([step("a", 0, 1)])).totals.costUsd).toBeNull();
  });

  it("extends a live run's timeline to now and leaves the open visit open", () => {
    const m = normalizeRun(run([step("trigger", 0, 10), step("research", 20, null, "RUNNING")], "RUNNING"), {
      nowMs: BASE + 5000,
    });
    expect(m.live).toBe(true);
    expect(m.totalMs).toBe(5000);
    expect(m.visits[1].endMs).toBeNull();
    expect(m.visits[1].durationMs).toBeNull();
  });

  it("maps node and run status vocabularies", () => {
    const m = normalizeRun(
      run(
        [
          step("a", 0, 1, "SKIPPED"),
          step("b", 1, 2, "AWAITING_APPROVAL"),
          step("c", 2, 3, "PAUSED"),
          step("d", 3, 4, "CANCELLED"),
          step("e", 4, 5, "SOMETHING_NEW"),
        ],
        "AWAITING_APPROVAL",
      ),
    );
    expect(m.visits.map((v) => v.status)).toEqual(["skipped", "paused", "paused", "cancelled", "ok"]);
    expect(m.visits[1].awaiting).toBe("approval");
    expect(m.visits[2].awaiting).toBe("operator");
    expect(m.run.status).toBe("awaiting");
    expect(m.live).toBe(true);
  });

  it("parses the condition branch from its summary", () => {
    const m = normalizeRun(run([step("cond", 0, 5, "OK", { nodeType: "condition", summary: "Condition 'urgent' -> true" })]));
    expect(m.visits[0].branch).toBe("true");
  });
});

// ---------------------------------------------------------------------------

describe("frameAt", () => {
  const linear = () =>
    normalizeRun(run([step("trigger", 0, 10), step("research", 20, 8220), step("email", 8230, 8500)]));
  const linearEdges = [edge("trigger", "research"), edge("research", "email")];

  it("is all pending at time zero except what started at t0", () => {
    const f = frameAt(linear(), 0, linearEdges);
    expect(f.nodes.trigger.status).toBe("running");
    expect(f.nodes.research).toBeUndefined();
    expect(f.edges["trigger->research"].state).toBe("pending");
  });

  it("shows a node running and its inbound edge flowing mid-step", () => {
    const f = frameAt(linear(), 4000, linearEdges);
    expect(f.nodes.trigger.status).toBe("ok");
    expect(f.nodes.research.status).toBe("running");
    expect(f.nodes.research.elapsedMs).toBe(3980);
    expect(f.edges["trigger->research"].state).toBe("flowing");
    expect(f.edges["research->email"].state).toBe("pending");
  });

  it("clamps and reaches the end state with Infinity", () => {
    const f = frameAt(linear(), Infinity, linearEdges);
    expect(f.tMs).toBe(8500);
    expect(Object.values(f.nodes).every((n) => n.status === "ok")).toBe(true);
    expect(f.edges["research->email"]).toEqual({ state: "taken", traversals: 1 });
    expect(frameAt(linear(), -50).tMs).toBe(0);
    expect(frameAt(linear(), Number.NaN).tMs).toBe(0);
  });

  it("marks the untaken condition branch skipped as soon as the other branch starts", () => {
    const m = normalizeRun(
      run([
        step("trigger", 0, 10),
        step("cond", 10, 20, "OK", { nodeType: "condition", summary: "Condition 'x' -> false" }),
        step("no", 30, 100),
      ]),
    );
    const es = [edge("trigger", "cond"), edge("cond", "yes", "true"), edge("cond", "no", "false")];
    const mid = frameAt(m, 25, es);
    // Branch already resolved in the trace, so the TRUE port is dead before FALSE's target even starts.
    expect(mid.edges["cond->yes:true"].state).toBe("skipped");
    expect(mid.edges["cond->no:false"].state).toBe("pending");
    const end = frameAt(m, Infinity, es);
    expect(end.edges["cond->no:false"].state).toBe("taken");
    expect(end.edges["cond->yes:true"].state).toBe("skipped");
    expect(end.nodes.yes).toBeUndefined();
  });

  it("infers a supervisor-style route from which target actually ran", () => {
    const m = normalizeRun(run([step("sup", 0, 10), step("billing", 20, 40)]));
    const es = [edge("sup", "billing", "billing"), edge("sup", "tech", "tech")];
    const f = frameAt(m, 30, es);
    expect(f.edges["sup->billing:billing"].state).toBe("flowing");
    expect(f.edges["sup->tech:tech"].state).toBe("skipped");
  });

  it("handles parallel branches and a join", () => {
    const m = normalizeRun(
      run([step("a", 0, 10), step("b", 15, 100), step("c", 15, 60), step("d", 110, 130)]),
    );
    const es = [edge("a", "b"), edge("a", "c"), edge("b", "d"), edge("c", "d")];
    const f = frameAt(m, 70, es);
    expect(f.nodes.b.status).toBe("running");
    expect(f.nodes.c.status).toBe("ok");
    expect(f.edges["a->b"].state).toBe("flowing");
    expect(f.edges["a->c"].state).toBe("taken");
    expect(f.edges["c->d"].state).toBe("pending");
    const end = frameAt(m, Infinity, es);
    expect(end.edges["b->d"].state).toBe("taken");
    expect(end.edges["c->d"].state).toBe("taken");
  });

  it("counts every pass of a loop without double counting the entry edge", () => {
    const m = normalizeRun(
      run([
        step("trigger", 0, 5),
        step("work", 10, 20),
        step("check", 25, 30, "OK", { nodeType: "condition", summary: "Condition 'done' -> false" }),
        step("work", 35, 45),
        step("check", 50, 55, "OK", { nodeType: "condition", summary: "Condition 'done' -> false" }),
        step("work", 60, 70),
        step("check", 75, 80, "OK", { nodeType: "condition", summary: "Condition 'done' -> true" }),
        step("exit", 85, 90),
      ]),
    );
    const es = [edge("trigger", "work"), edge("work", "check"), edge("check", "work", "false"), edge("check", "exit", "true")];
    const f = frameAt(m, Infinity, es);
    expect(f.edges["trigger->work"].traversals).toBe(1);
    expect(f.edges["work->check"].traversals).toBe(3);
    expect(f.edges["check->work:false"].traversals).toBe(2);
    expect(f.edges["check->exit:true"].traversals).toBe(1);
    expect(f.nodes.work.visits).toHaveLength(3);
    expect(f.nodes.work.activeVisit?.visitIndex).toBe(2);
    // Mid-loop: second pass of `work` is running.
    const mid = frameAt(m, 40, es);
    expect(mid.nodes.work.status).toBe("running");
    expect(mid.nodes.work.activeVisit?.visitIndex).toBe(1);
    expect(mid.edges["check->work:false"].state).toBe("flowing");
    expect(mid.edges["check->exit:true"].state).toBe("skipped");
  });

  it("shows a failed run: error node, failed outbound edge, untouched downstream", () => {
    const m = normalizeRun(
      run([step("trigger", 0, 5), step("email", 10, 30, "ERROR", { summary: "SMTP 550 mailbox unavailable" }), ], "FAILED", {
        errorMessage: "SMTP 550 mailbox unavailable",
      }),
    );
    const es = [edge("trigger", "email"), edge("email", "log")];
    const f = frameAt(m, Infinity, es);
    expect(f.nodes.email.status).toBe("error");
    expect(f.nodes.email.errorCount).toBe(1);
    expect(f.edges["email->log"].state).toBe("failed");
    expect(f.nodes.log).toBeUndefined();
  });

  it("routes a failure down the error port", () => {
    const m = normalizeRun(run([step("email", 0, 10, "ERROR"), step("fallback", 15, 20)]));
    const es = [edge("email", "next"), edge("email", "fallback", "error")];
    const f = frameAt(m, Infinity, es);
    expect(f.edges["email->fallback:error"].state).toBe("taken");
    expect(f.edges["email->next"].state).toBe("skipped");
  });

  it("marks an unused error port skipped only once the run is over", () => {
    const m = normalizeRun(run([step("a", 0, 10), step("b", 20, 30)]));
    const es = [edge("a", "b"), edge("a", "handler", "error")];
    expect(frameAt(m, 25, es).edges["a->handler:error"].state).toBe("skipped");
    expect(frameAt(m, Infinity, es).edges["a->handler:error"].state).toBe("skipped");
  });

  it("holds edges out of a paused approval node as awaiting-approval", () => {
    const m = normalizeRun(run([step("trigger", 0, 5), step("approve", 10, 10, "AWAITING_APPROVAL")], "AWAITING_APPROVAL"));
    const f = frameAt(m, Infinity, [edge("trigger", "approve"), edge("approve", "send")]);
    expect(f.nodes.approve.status).toBe("paused");
    expect(f.edges["approve->send"].state).toBe("awaiting-approval");
    expect(toEdgeRunState("taken")).toBe("done");
    expect(toEdgeRunState("awaiting-approval")).toBe("awaiting-approval");
  });

  it("keeps a still-running run's open step running at the live edge", () => {
    const m = normalizeRun(run([step("trigger", 0, 10), step("research", 20, null, "RUNNING")], "RUNNING"), {
      nowMs: BASE + 3000,
    });
    const f = frameAt(m, Infinity, [edge("trigger", "research"), edge("research", "email")]);
    expect(f.nodes.research.status).toBe("running");
    expect(f.nodes.research.elapsedMs).toBe(2980);
    expect(f.edges["trigger->research"].state).toBe("flowing");
    expect(f.edges["research->email"].state).toBe("pending");
  });

  it("does not leave a dangling open step spinning in a finished run", () => {
    const m = normalizeRun(run([step("a", 0, 10), step("b", 20, null, "RUNNING")], "FAILED"));
    expect(frameAt(m, Infinity).nodes.b.status).toBe("error");
  });

  it("shows cancelled nodes as skipped but flags them", () => {
    const m = normalizeRun(run([step("a", 0, 10, "CANCELLED")], "CANCELLED"));
    const n = frameAt(m, Infinity).nodes.a;
    expect(n.status).toBe("skipped");
    expect(n.cancelled).toBe(true);
  });
});

describe("keyframes", () => {
  it("lists unique event offsets for keyboard stepping", () => {
    const m = normalizeRun(run([step("a", 0, 10), step("b", 10, 30)]));
    expect(keyframes(m)).toEqual([0, 10, 30]);
  });
});

// ---------------------------------------------------------------------------

describe("criticalPath", () => {
  it("follows the slow branch through a diamond", () => {
    const m = normalizeRun(
      run([step("a", 0, 10), step("b", 15, 100), step("c", 15, 60), step("d", 110, 130)]),
    );
    const es = [edge("a", "b"), edge("a", "c"), edge("b", "d"), edge("c", "d")];
    const cp = criticalPathDetail(m, es);
    expect(cp.nodeIds).toEqual(["a", "b", "d"]);
    expect(cp.edgeIds).toEqual(["a->b", "b->d"]);
    expect(cp.totalMs).toBe(10 + 85 + 20);
    expect(criticalPath(m, es)).toEqual(["a", "b", "d"]);
  });

  it("unrolls loops instead of cycling", () => {
    const m = normalizeRun(run([step("a", 0, 10), step("b", 10, 20), step("a", 20, 30), step("b", 30, 40)]));
    const es = [edge("a", "b"), edge("b", "a")];
    const cp = criticalPathDetail(m, es);
    expect(cp.visits.map((v) => v.key)).toEqual(["a#0", "b#0", "a#1", "b#1"]);
    expect(cp.nodeIds).toEqual(["a", "b"]);
    expect(cp.totalMs).toBe(40);
  });

  it("without edges picks the longest sequential chain", () => {
    const m = normalizeRun(
      run([step("a", 0, 10), step("b", 15, 100), step("c", 15, 60), step("d", 110, 130)]),
    );
    expect(criticalPath(m)).toEqual(["a", "b", "d"]);
  });

  it("counts a still-running step up to the live edge", () => {
    const m = normalizeRun(run([step("a", 0, 10), step("b", 15, 20), step("c", 15, null, "RUNNING")], "RUNNING"), {
      nowMs: BASE + 500,
    });
    expect(criticalPath(m, [edge("a", "b"), edge("a", "c")])).toEqual(["a", "c"]);
  });

  it("is empty for an empty run", () => {
    expect(criticalPathDetail(normalizeRun({ status: "RUNNING", trace: [] }))).toEqual({
      nodeIds: [], visits: [], edgeIds: [], totalMs: 0,
    });
  });
});

// ---------------------------------------------------------------------------

describe("aggregateRuns", () => {
  it("computes fail rate, percentiles and edge traversals across runs", () => {
    const es = [edge("a", "b"), edge("b", "a")];
    const runs = [
      normalizeRun(run([step("a", 0, 100), step("b", 100, 200)])),
      normalizeRun(run([step("a", 0, 300), step("b", 300, 310, "ERROR")], "FAILED")),
      normalizeRun(run([step("a", 0, 200), step("b", 200, 220), step("a", 220, 240), step("b", 240, 260)])),
    ];
    const agg = aggregateRuns(runs, es);
    expect(agg.runCount).toBe(3);
    expect(agg.nodes.a.runs).toBe(3);
    expect(agg.nodes.a.visits).toBe(4);
    expect(agg.nodes.a.failRate).toBe(0);
    expect(agg.nodes.b.failures).toBe(1);
    expect(agg.nodes.b.failRate).toBeCloseTo(1 / 3);
    // a durations: 100, 300, 200, 20 -> sorted 20,100,200,300
    expect(agg.nodes.a.p50Ms).toBe(100);
    expect(agg.nodes.a.p95Ms).toBe(300);
    expect(agg.nodes.a.avgMs).toBe(155);
    expect(agg.nodes.a.heat).toBe(1);
    expect(agg.nodes.b.heat).toBeGreaterThan(0);
    expect(agg.nodes.b.heat).toBeLessThan(1);
    expect(agg.edges["a->b"]).toEqual({ traversals: 4, runs: 3 });
    expect(agg.edges["b->a"]).toEqual({ traversals: 1, runs: 1 });
  });

  it("reports null timing stats when durations are unknown", () => {
    const agg = aggregateRuns([normalizeRun({ status: "COMPLETED", trace: [{ nodeId: "x", status: "OK" }] })]);
    expect(agg.nodes.x.p50Ms).toBeNull();
    expect(agg.nodes.x.heat).toBe(0);
    expect(agg.maxAvgMs).toBeNull();
  });
});

// ---------------------------------------------------------------------------

describe("narrate", () => {
  it("tells the story of a successful run", () => {
    const m = normalizeRun(
      run([
        step("trigger", 0, 10, "OK", { label: "Webhook" }),
        step("research", 20, 8220),
        step("email", 8230, 8500),
      ]),
    );
    const s = narrate(m, label, narrateOpts);
    expect(s[0]).toBe("Triggered by Webhook at 14:02");
    expect(s).toContain("Research Agent took 8.2s - the slowest step (96% of the run)");
    expect(s[s.length - 1]).toBe("Finished successfully in 8.5s across 3 steps");
  });

  it("does not say 'Triggered by Trigger'", () => {
    const m = normalizeRun(run([step("trigger", 0, 10, "OK", { label: "Trigger" })]));
    expect(narrate(m, () => null, narrateOpts)[0]).toBe("Triggered at 14:02");
  });

  it("names branches, loops and parallel work", () => {
    const m = normalizeRun(
      run([
        step("trigger", 0, 5),
        step("cond", 5, 10, "OK", { nodeType: "condition", summary: "Condition 'urgent' -> true" }),
        step("summ", 20, 60, "OK", { label: "Summarize" }),
        step("tran", 25, 70, "OK", { label: "Translate" }),
        step("research", 80, 90),
        step("research", 95, 105),
      ]),
    );
    const s = narrate(m, label, narrateOpts);
    expect(s).toContain("Is urgent? took the TRUE path");
    expect(s).toContain("Summarize and Translate ran in parallel");
    expect(s).toContain("Research Agent ran 2 times (about 10ms each)");
  });

  it("says where a run failed and why", () => {
    const m = normalizeRun(
      run([step("trigger", 0, 5), step("email", 10, 30, "ERROR", { summary: "SMTP 550   mailbox\nunavailable" })], "FAILED"),
    );
    const lines = narrateLines(m, label, narrateOpts);
    const last = lines[lines.length - 1];
    expect(last.text).toBe("Failed at Send Email: SMTP 550 mailbox unavailable");
    expect(last.tone).toBe("bad");
    expect(last.nodeId).toBe("email");
    expect(last.atMs).toBe(10);
  });

  it("falls back to the run error when no node failed", () => {
    const m = normalizeRun(run([step("a", 0, 5)], "FAILED", { errorMessage: "Worker lost" }));
    const s = narrate(m, () => null, narrateOpts);
    expect(s[s.length - 1]).toBe("Failed: Worker lost");
  });

  it("clips long summaries", () => {
    const long = "x".repeat(400);
    const m = normalizeRun(run([step("email", 0, 5, "ERROR", { summary: long })], "FAILED"));
    const s = narrate(m, label, narrateOpts);
    expect(s[s.length - 1].length).toBeLessThan(170);
    expect(s[s.length - 1].endsWith("…")).toBe(true);
  });

  it("narrates a still-running run", () => {
    const m = normalizeRun(run([step("trigger", 0, 10), step("research", 20, null, "RUNNING")], "RUNNING"), {
      nowMs: BASE + 4020,
    });
    const s = narrate(m, label, narrateOpts);
    expect(s[s.length - 1]).toBe("Still running - Research Agent is working (4.0s so far)");
  });

  it("narrates approval waits, skips, recovered errors and usage", () => {
    const m = normalizeRun(
      run(
        [
          step("trigger", 0, 5),
          step("kb", 5, 6, "SKIPPED", { nodeType: "knowledge", label: "Docs", summary: "knowledge profile off" }),
          step("email", 10, 20, "ERROR", { summary: "timeout" }),
          step("fallback", 25, 30, "OK", { label: "Fallback", tokensIn: 1200, tokensOut: 300, costUsd: 0.0042 }),
          step("approve", 40, 40, "AWAITING_APPROVAL", { label: "Manager sign-off" }),
        ],
        "AWAITING_APPROVAL",
      ),
    );
    const s = narrate(m, label, { ...narrateOpts, edges: [edge("email", "fallback", "error")] });
    expect(s).toContain("Docs was skipped: knowledge profile off");
    expect(s).toContain("Send Email failed and recovered through its error path to Fallback");
    expect(s.some((x) => x.startsWith("Used 1,500 model tokens, about "))).toBe(true);
    expect(s[s.length - 1]).toBe("Waiting for approval at Manager sign-off");
  });

  it("narrates a route chosen by a multi-output node", () => {
    const m = normalizeRun(run([step("sup", 0, 10, "OK", { label: "Router" }), step("billing", 20, 40, "OK", { label: "Billing" })]));
    const s = narrate(m, () => null, { ...narrateOpts, edges: [edge("sup", "billing", "billing"), edge("sup", "tech", "tech")] });
    expect(s).toContain("Router routed to Billing");
  });

  it("is deterministic", () => {
    const mk = () => normalizeRun(run([step("trigger", 0, 10), step("research", 20, 8220)]));
    expect(narrate(mk(), label, narrateOpts)).toEqual(narrate(mk(), label, narrateOpts));
  });
});

describe("linkVisits", () => {
  it("is memoized per model and edges array", () => {
    const m = normalizeRun(run([step("a", 0, 10), step("b", 10, 20)]));
    const es = [edge("a", "b")];
    expect(linkVisits(m, es)).toBe(linkVisits(m, es));
    expect(linkVisits(m, es)).toHaveLength(1);
  });
});
