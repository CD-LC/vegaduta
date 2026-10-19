import { describe, expect, it } from "vitest";
import sample from "./fixtures/brief.sample.json";
import type { ApplyJob, Brief, ReadinessRow } from "./types";
import {
  approvalGuard,
  buildEnvoyGraph,
  deepLink,
  editorTargetFor,
  guardTotals,
  kindOfRef,
  nextRung,
  parseSse,
  sortReadiness,
  summariseApply,
  summariseReadiness,
} from "./logic";

const brief = sample as unknown as Brief;

const row = (state: ReadinessRow["state"], ref = "s1"): ReadinessRow => ({ kind: "SECRET", ref, label: ref, state, reason: "r" });

describe("deepLink", () => {
  it("builds a tour deep-link", () => {
    expect(deepLink("/secrets", "secret-add")).toBe("/secrets?tour=field:secret-add");
  });
  it("appends to an existing query and keeps the hash", () => {
    expect(deepLink("/agents?agent=abc", "workbench-system-prompt#top")).toBe("/agents?agent=abc&tour=field:workbench-system-prompt%23top");
    expect(deepLink("/tools#mcp", "mcp-connect")).toBe("/tools?tour=field:mcp-connect#mcp");
  });
});

describe("summariseReadiness", () => {
  it("counts every state and never drops a row", () => {
    const rows = [row("READY"), row("MISSING"), row("MISSING"), row("WAITING_ON_ADMIN"), row("GATED"), row("SIMULATED")];
    const s = summariseReadiness(rows);
    expect(s).toMatchObject({ ready: 1, missing: 2, waitingOnAdmin: 1, gated: 1, simulated: 1, total: 6, needsAttention: 4 });
    expect(sortReadiness(rows)).toHaveLength(6);
  });
  it("sorts actionable rows first and keeps input order within a state", () => {
    const sorted = sortReadiness([row("READY", "a"), row("MISSING", "b"), row("GATED", "c"), row("MISSING", "d")]);
    expect(sorted.map((r) => r.ref)).toEqual(["b", "d", "c", "a"]);
  });
  it("handles an empty list", () => {
    expect(summariseReadiness([])).toMatchObject({ total: 0, needsAttention: 0 });
  });
});

describe("approvalGuard", () => {
  const trust = { rung: "UNDER_WATCH" as const, irreversible: ["w1.n4"], humanMustDecide: ["w1.n4"] };
  it("guards every side-effecting node type at UNDER_WATCH", () => {
    expect(approvalGuard("UNDER_WATCH", "http", "w1.n2", trust)).toMatchObject({ kind: "approval", disappearsAtNext: true, next: "TRUSTED" });
    expect(approvalGuard("UNDER_WATCH", "agent", "w1.n3", trust)).toBeNull();
  });
  it("keeps the irreversible node guarded at the next rung", () => {
    expect(approvalGuard("UNDER_WATCH", "output", "w1.n4", trust)).toMatchObject({ irreversible: true, disappearsAtNext: false });
  });
  it("guards only irreversible nodes at TRUSTED", () => {
    expect(approvalGuard("TRUSTED", "http", "w1.n2", trust)).toBeNull();
    expect(approvalGuard("TRUSTED", "output", "w1.n4", trust)).toMatchObject({ next: "AUTONOMOUS", disappearsAtNext: false });
    expect(approvalGuard("TRUSTED", "output", "w1.n4", { ...trust, humanMustDecide: [] })).toMatchObject({ disappearsAtNext: true });
  });
  it("leaves only human-must-decide nodes at AUTONOMOUS", () => {
    expect(approvalGuard("AUTONOMOUS", "output", "w1.n4", trust)).toMatchObject({ kind: "human", next: null });
    expect(approvalGuard("AUTONOMOUS", "http", "w1.n2", trust)).toBeNull();
  });
  it("walks the ladder", () => {
    expect(nextRung("UNDER_WATCH")).toBe("TRUSTED");
    expect(nextRung("TRUSTED")).toBe("AUTONOMOUS");
    expect(nextRung("AUTONOMOUS")).toBeNull();
  });
});

describe("kindOfRef and editorTargetFor", () => {
  it("maps refs and node paths to artifact kinds", () => {
    expect(kindOfRef("a1")).toBe("agent");
    expect(kindOfRef("w1.n4")).toBe("workflow");
    expect(kindOfRef("zzz")).toBeNull();
  });
  it("is null for entity-backed kinds until applied, and always available for secrets", () => {
    expect(editorTargetFor("a1", null)).toBeNull();
    expect(editorTargetFor("s1", null)?.href).toContain("tour=field:secret-add");
    const detail = { bindings: [{ ref: "a1", kind: "AGENT", entityId: "ag-9" }, { ref: "w1", kind: "WORKFLOW", entityId: "wf-3" }] };
    expect(editorTargetFor("a1", detail)?.href).toBe("/agents?agentId=ag-9&tour=field:workbench-system-prompt");
    expect(editorTargetFor("w1.n4", detail)?.href).toBe("/workflows/wf-3?tour=field:canvas");
  });
});

describe("parseSse", () => {
  it("returns complete events and keeps the partial tail", () => {
    const buf = 'event: thinking\ndata: {"step":"one"}\n\nevent: section\ndata: {"name":"agents","data":[1]}\n\nevent: thinking\ndata: {"te';
    const { events, rest } = parseSse(buf);
    expect(events).toEqual([
      { type: "thinking", text: "one" },
      { type: "section", name: "agents", data: [1] },
    ]);
    expect(rest).toBe('event: thinking\ndata: {"te');
  });
  it("accepts plain-text thinking lines and a done payload", () => {
    const { events } = parseSse('event: thinking\ndata: reading\n\nevent: done\ndata: {"brief":{},"readiness":[],"warnings":[]}\n\n');
    expect(events[0]).toEqual({ type: "thinking", text: "reading" });
    expect(events[1].type).toBe("done");
  });
  it("turns an error event into the sentence core wrote", () => {
    const { events } = parseSse('event: error\ndata: {"status":422,"error":"The goal is too vague.","section":"agents"}\n\n');
    expect(events).toEqual([{ type: "error", message: "The goal is too vague." }]);
  });
});

describe("summariseApply", () => {
  const step = (seq: number, status: ApplyJob["steps"][number]["status"]) => ({ seq, kind: "AGENT", ref: `a${seq}`, status });
  it("reports progress while running", () => {
    const p = summariseApply({ status: "RUNNING", steps: [step(1, "DONE"), step(2, "PENDING")] });
    expect(p).toMatchObject({ total: 2, done: 1, terminal: false, failed: false });
  });
  it("names the offending step and says nothing was kept only when everything was compensated", () => {
    const failed = summariseApply({ status: "FAILED", steps: [step(1, "COMPENSATED"), { ...step(2, "FAILED"), error: "boom" }] });
    expect(failed.failedStep?.ref).toBe("a2");
    expect(failed.nothingKept).toBe(true);
    const partial = summariseApply({ status: "FAILED", steps: [step(1, "DONE"), step(2, "FAILED")] });
    expect(partial.nothingKept).toBe(false);
    // an orphan (a created entity that could not be removed) is kept even when every step reads COMPENSATED
    const orphaned = summariseApply({ status: "FAILED", steps: [step(1, "COMPENSATED")], orphans: [{ ...step(1, "DONE") }] });
    expect(orphaned.nothingKept).toBe(false);
  });
  it("is terminal on SUCCEEDED and takes core's failedStep", () => {
    expect(summariseApply({ status: "SUCCEEDED", steps: [step(1, "DONE")] }).terminal).toBe(true);
    const p = summariseApply({ status: "FAILED", steps: [], failedStep: { ...step(3, "FAILED"), error: "no plan" } });
    expect(p.failedStep?.error).toBe("no plan");
  });
  it("treats a missing job as not terminal", () => {
    expect(summariseApply(null)).toMatchObject({ terminal: false, total: 0 });
  });
});

describe("buildEnvoyGraph", () => {
  it("places every artifact and links dependencies", () => {
    const g = buildEnvoyGraph(brief, { rung: "UNDER_WATCH" });
    const ids = g.nodes.map((n) => n.id).sort();
    expect(ids).toEqual(["a1", "a2", "c1", "k1", "m1", "p1", "s1", "s2", "w1"]);
    const has = (s: string, t: string) => g.edges.some((e) => e.source === s && e.target === t);
    expect(has("c1", "a2")).toBe(true);
    expect(has("a1", "k1")).toBe(true);
    expect(has("a1", "p1")).toBe(true);
    expect(has("a2", "m1")).toBe(true);
    expect(has("m1", "s2")).toBe(true);
    expect(has("w1", "a1")).toBe(true);
    expect(has("w1", "s1")).toBe(true); // {{secret.TALLY_API_KEY}} on the http node
  });
  it("hides detail kinds in simple mode", () => {
    const g = buildEnvoyGraph(brief, { rung: "UNDER_WATCH", mode: "simple", expanded: new Set(["w1"]) });
    expect(g.nodes.map((n) => n.data.kind).sort()).toEqual(["agent", "agent", "channel", "workflow"]);
  });
  it("expands a workflow inline with shields on the guarded edges", () => {
    const g = buildEnvoyGraph(brief, { rung: "UNDER_WATCH", expanded: new Set(["w1"]) });
    expect(g.nodes.find((n) => n.id === "w1")?.type).toBe("frame");
    const steps = g.nodes.filter((n) => n.parentId === "w1");
    expect(steps.map((n) => n.id)).toEqual(["w1.n1", "w1.n2", "w1.n3", "w1.n4"]);
    // http (n2) and output (n4) are guarded at UNDER_WATCH; the agent step is not.
    const shielded = g.edges.filter((e) => e.type === "shield").map((e) => e.target).sort();
    expect(shielded).toEqual(["w1.n2", "w1.n4"]);
    // the frame is emitted before its children
    expect(g.nodes.findIndex((n) => n.id === "w1")).toBeLessThan(g.nodes.findIndex((n) => n.id === "w1.n1"));
  });
  it("drops the non-irreversible shield at TRUSTED and all shields at AUTONOMOUS", () => {
    const opts = { expanded: new Set(["w1"]) };
    const trusted = buildEnvoyGraph(brief, { ...opts, rung: "TRUSTED" }).edges.filter((e) => e.type === "shield");
    expect(trusted.map((e) => e.target)).toEqual(["w1.n4"]);
    const auto = buildEnvoyGraph(brief, { ...opts, rung: "AUTONOMOUS" }).edges.filter((e) => e.type === "shield");
    expect(auto).toHaveLength(0);
  });
  it("carries readiness state on secret nodes and a guard count on the collapsed workflow", () => {
    const g = buildEnvoyGraph(brief, { rung: "UNDER_WATCH", readinessByRef: { s1: "MISSING" } });
    expect(g.nodes.find((n) => n.id === "s1")?.data.state).toBe("MISSING");
    expect(g.nodes.find((n) => n.id === "w1")?.data.guardCount).toBe(2);
  });
  it("is deterministic", () => {
    const a = buildEnvoyGraph(brief, { rung: "TRUSTED", expanded: new Set(["w1"]) });
    const b = buildEnvoyGraph(brief, { rung: "TRUSTED", expanded: new Set(["w1"]) });
    expect(b).toEqual(a);
  });
});

describe("guardTotals", () => {
  it("counts shields and how many disappear at the next rung", () => {
    expect(guardTotals(brief, "UNDER_WATCH")).toEqual({ guarded: 2, disappearAtNext: 1, next: "TRUSTED" });
    expect(guardTotals(brief, "AUTONOMOUS")).toEqual({ guarded: 0, disappearAtNext: 0, next: null });
  });
});

describe("fixture", () => {
  it("has a narrative entry for every artifact and workflow node", () => {
    const refs = [
      ...brief.agents.map((a) => a.ref),
      ...(brief.procedures ?? []).map((p) => p.ref),
      ...brief.workflows.map((w) => w.ref),
      ...brief.workflows.flatMap((w) => w.graph.nodes.map((n) => `${w.ref}.${n.id}`)),
      ...(brief.mcpServers ?? []).map((m) => m.ref),
      ...(brief.knowledge ?? []).map((k) => k.ref),
      ...(brief.secrets ?? []).map((s) => s.ref),
      ...(brief.channels ?? []).map((c) => c.ref),
    ];
    for (const r of refs) expect(brief.narrative.perNode[r], r).toBeTruthy();
  });
});
