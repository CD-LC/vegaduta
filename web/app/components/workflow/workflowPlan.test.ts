// The pure half of the plan panel: parsing an untrusted generate-graph
// response, deciding when the current graph is sent, mapping a suggested cron
// onto the trigger strip's picker, and the "insert-approval" lint fix.

import { describe, expect, it } from "vitest";
import {
  countSimulatedSends,
  hasRealContent,
  existingApprovalBefore,
  insertApprovalBefore,
  isPlanEmpty,
  normalizeCron,
  normalizePlan,
  pickerMatchesPreset,
  presetFromCron,
  sameSchedule,
  safePlanHref,
  shouldSendCurrentGraph,
} from "./workflowPlan";

describe("normalizePlan", () => {
  it("degrades to notes only against an older core", () => {
    const plan = normalizePlan({ graph: { nodes: [], edges: [] }, notes: ["No agents exist yet."] });
    expect(plan).toEqual({
      mode: "create",
      steps: [],
      notes: ["No agents exist yet."],
      requirements: [],
      insertedApprovals: [],
      schedule: null,
      testRunSupported: false,
    });
    expect(isPlanEmpty(plan)).toBe(false);
  });

  it("is empty for a response with nothing to show, and for junk", () => {
    expect(isPlanEmpty(normalizePlan({ graph: {} }))).toBe(true);
    expect(isPlanEmpty(normalizePlan(null))).toBe(true);
    expect(isPlanEmpty(normalizePlan("nope"))).toBe(true);
    expect(isPlanEmpty(null)).toBe(true);
  });

  it("reads the full contract", () => {
    const plan = normalizePlan({
      notes: ["  Added an approval step.  ", ""],
      mode: "edit",
      explanation: [
        { nodeId: "t1", label: "Start", type: "TRIGGER", sentence: "Starts when you run it." },
        { nodeId: "o1", label: "Send", type: "output", sentence: "Sends a WhatsApp message." },
      ],
      suggestedSchedule: { cron: " 0 9 * * 1 ", label: "Every Monday 09:00", timezone: "UTC" },
      requirements: {
        secrets: ["CRM_KEY"],
        knowledge: [],
        items: [
          { kind: "channel", message: "Connect WhatsApp.", href: "/admin/channels", nodeId: "o1" },
          { kind: "secret", message: "Add CRM_KEY.", href: "/secrets", nodeId: null },
        ],
      },
      insertedApprovals: [{ approvalNodeId: "auto-approval-o1", beforeNodeId: "o1", sendNodeId: "o1" }],
    });
    expect(plan.mode).toBe("edit");
    expect(plan.steps.map((s) => s.type)).toEqual(["trigger", "output"]);
    expect(plan.notes).toEqual(["Added an approval step."]);
    expect(plan.schedule).toEqual({ cron: "0 9 * * 1", label: "Every Monday 09:00", timezone: "UTC" });
    expect(plan.requirements).toEqual([
      { kind: "channel", message: "Connect WhatsApp.", href: "/admin/channels", nodeId: "o1" },
      { kind: "secret", message: "Add CRM_KEY.", href: "/secrets", nodeId: null },
    ]);
    expect(plan.insertedApprovals).toEqual([{ approvalNodeId: "auto-approval-o1", beforeNodeId: "o1", sendNodeId: "o1" }]);
  });

  it("drops malformed entries instead of throwing", () => {
    const plan = normalizePlan({
      notes: "not a list",
      explanation: [null, { nodeId: "a" }, { sentence: "   " }, { sentence: "Kept." }],
      suggestedSchedule: { cron: "every monday", label: "x" },
      requirements: { items: [{ kind: "weird", message: "Do a thing.", href: "https://evil.example/x" }, { kind: "secret" }] },
      insertedApprovals: [{ beforeNodeId: "x" }, "nope"],
    });
    expect(plan.steps).toEqual([{ nodeId: "", label: "", type: "", sentence: "Kept." }]);
    expect(plan.notes).toEqual([]);
    expect(plan.schedule).toBeNull();
    expect(plan.requirements).toEqual([{ kind: "other", message: "Do a thing.", href: null, nodeId: null }]);
    expect(plan.insertedApprovals).toEqual([]);
  });

  it("falls back to the cron as label and UTC as zone", () => {
    expect(normalizePlan({ suggestedSchedule: { cron: "30 8 * * *" } }).schedule).toEqual({
      cron: "30 8 * * *",
      label: "30 8 * * *",
      timezone: "UTC",
    });
  });
});

describe("safePlanHref", () => {
  it("keeps console paths", () => {
    expect(safePlanHref("/secrets")).toBe("/secrets");
    expect(safePlanHref(" /admin/channels ")).toBe("/admin/channels");
  });

  it("rejects anything that could leave the console", () => {
    for (const bad of ["https://x.example", "//x.example", "/\\x.example", "javascript:alert(1)", "secrets", "", "/a b", null, 7]) {
      expect(safePlanHref(bad)).toBeNull();
    }
  });
});

describe("normalizeCron / presetFromCron", () => {
  it("accepts only five fields", () => {
    expect(normalizeCron("0  9 * * 1")).toBe("0 9 * * 1");
    expect(normalizeCron("0 0 9 * * 1")).toBeNull();
    expect(normalizeCron("0 9 * * mon; rm")).toBeNull();
    expect(normalizeCron(undefined)).toBeNull();
  });

  it("maps the crons the picker can show", () => {
    expect(presetFromCron("0 * * * *")).toEqual({ frequency: "hourly" });
    expect(presetFromCron("5 9 * * *")).toEqual({ frequency: "daily", time: "09:05" });
    expect(presetFromCron("0 9 * * 1")).toEqual({ frequency: "weekly", time: "09:00", day: "1" });
    expect(presetFromCron("0 9 * * 7")).toEqual({ frequency: "weekly", time: "09:00", day: "0" });
  });

  it("returns null for a cron the picker would misrepresent", () => {
    for (const cron of ["0 9 1 * *", "0 9 * * 1-5", "0 9 * * MON", "*/15 * * * *", "0 9,17 * * *", "61 9 * * *", "0 24 * * *", "junk"]) {
      expect(presetFromCron(cron)).toBeNull();
    }
  });
});

describe("edit mode", () => {
  const starter = [{ id: "t" }];
  const real = [{ id: "t" }, { id: "a" }];

  it("treats the lone starter trigger as an empty canvas", () => {
    expect(hasRealContent(starter, [])).toBe(false);
    expect(hasRealContent(real, [])).toBe(true);
    expect(hasRealContent(starter, [{ id: "e" }])).toBe(true);
  });

  it("sends the current graph only when changing a real workflow", () => {
    expect(shouldSendCurrentGraph("edit", real, [])).toBe(true);
    expect(shouldSendCurrentGraph("replace", real, [])).toBe(false);
    expect(shouldSendCurrentGraph("edit", starter, [])).toBe(false);
  });
});

describe("countSimulatedSends", () => {
  it("counts only entries flagged dryRun === true", () => {
    expect(
      countSimulatedSends([
        { nodeId: "a", status: "OK" },
        { nodeId: "b", status: "OK", dryRun: true },
        { nodeId: "c", status: "OK", dryRun: "true" },
        { nodeId: "d", status: "OK", dryRun: true },
        null,
      ]),
    ).toBe(2);
    expect(countSimulatedSends(undefined)).toBe(0);
  });
});

describe("insertApprovalBefore", () => {
  const opts = { label: "Approve before sending", message: "Check this." };
  const node = (id: string, type: string, x = 0, label = id) => ({ id, type, position: { x, y: 0 }, data: { label, config: {} } });

  it("routes the send's incoming edges through a new approval, keeping edge id and source handle", () => {
    const nodes = [node("t", "trigger"), node("c", "condition", 300), node("o", "output", 600)];
    const edges = [
      { id: "e1", source: "t", target: "c" },
      { id: "e2", source: "c", target: "o", sourceHandle: "true", targetHandle: "in" },
    ];
    const res = insertApprovalBefore(nodes, edges, ["o"], opts);
    expect(res.changed).toBe(true);
    expect(res.approvalNodeIds).toEqual(["auto-approval-o"]);
    const approval = res.nodes.find((n) => n.id === "auto-approval-o");
    expect(approval).toMatchObject({
      type: "approval",
      data: { label: "Approve before sending", config: { message: "Check this.", autoInserted: true } },
    });
    expect(res.edges.find((e) => e.id === "e2")).toMatchObject({ source: "c", target: "auto-approval-o", sourceHandle: "true" });
    expect(res.edges.find((e) => e.id === "e2")?.targetHandle).toBeUndefined();
    expect(res.edges.find((e) => e.source === "auto-approval-o")).toMatchObject({ id: "e-auto-approval-o", target: "o" });
    // Inputs are not mutated.
    expect(nodes).toHaveLength(3);
    expect(edges[1].target).toBe("o");
  });

  it("leaves a container's body edge alone and reports nothing to do", () => {
    const nodes = [node("t", "trigger"), node("l", "loop", 300), node("o", "output", 600)];
    const edges = [
      { id: "e1", source: "t", target: "l" },
      { id: "e2", source: "l", target: "o" },
    ];
    const res = insertApprovalBefore(nodes, edges, ["o"], opts);
    expect(res.changed).toBe(false);
    expect(res.nodes).toBe(nodes);
    expect(res.edges).toBe(edges);
  });

  it("gates in front of the container when the server says so", () => {
    const nodes = [node("t", "trigger"), node("l", "loop", 300), node("o", "output", 600)];
    const edges = [
      { id: "e1", source: "t", target: "l" },
      { id: "e2", source: "l", target: "o" },
    ];
    const res = insertApprovalBefore(nodes, edges, ["l"], opts);
    expect(res.edges.find((e) => e.id === "e1")?.target).toBe("auto-approval-l");
    expect(res.edges.find((e) => e.id === "e2")?.target).toBe("o");
  });

  it("keeps ids and labels unique and applies the canvas edge defaults", () => {
    const nodes = [node("t", "trigger"), node("o", "output", 600), node("auto-approval-o", "approval", 900, "Approve before sending")];
    const edges = [{ id: "e1", source: "t", target: "o" }, { id: "e-auto-approval-o-2", source: "t", target: "auto-approval-o" }];
    const res = insertApprovalBefore(nodes, edges, ["o", "o", "missing"], { ...opts, edgeDefaults: { type: "smoothstep" } });
    expect(res.approvalNodeIds).toEqual(["auto-approval-o-2"]);
    const added = res.nodes.find((n) => n.id === "auto-approval-o-2");
    expect(added?.data?.label).toBe("Approve before sending-2");
    expect(res.edges.find((e) => e.source === "auto-approval-o-2")).toMatchObject({ id: "e-auto-approval-o-2-2", type: "smoothstep" });
  });
});

describe("review round - capability, picker match, already gated", () => {
  it("marks test run as supported only when a current-core field is present", () => {
    expect(normalizePlan({ graph: { nodes: [], edges: [] }, notes: ["n"] }).testRunSupported).toBe(false);
    expect(normalizePlan(null).testRunSupported).toBe(false);
    expect(normalizePlan({ explanation: [] }).testRunSupported).toBe(true);
    expect(normalizePlan({ insertedApprovals: [] }).testRunSupported).toBe(true);
    expect(normalizePlan({ requirements: { items: [] } }).testRunSupported).toBe(true);
    expect(normalizePlan({ mode: "create" }).testRunSupported).toBe(true);
  });

  it("matches the picker only while it still holds the suggested preset", () => {
    const weekly = presetFromCron("0 9 * * 1");
    expect(pickerMatchesPreset(weekly, "weekly", "09:00", "1")).toBe(true);
    expect(pickerMatchesPreset(weekly, "weekly", "10:00", "1")).toBe(false);
    expect(pickerMatchesPreset(weekly, "weekly", "09:00", "2")).toBe(false);
    expect(pickerMatchesPreset(weekly, "off", "09:00", "1")).toBe(false);
    expect(pickerMatchesPreset(presetFromCron("0 * * * *"), "hourly", "13:37", "4")).toBe(true);
    // A cron the picker cannot express never matches, whatever the picker holds.
    expect(pickerMatchesPreset(presetFromCron("0 9 1 * *"), "daily", "09:00", "1")).toBe(false);
    expect(pickerMatchesPreset(presetFromCron("*/15 * * * *"), "hourly", "09:00", "1")).toBe(false);
    expect(pickerMatchesPreset(presetFromCron("0 9 * * MON"), "weekly", "09:00", "1")).toBe(false);
  });

  it("treats equal crons and equal presets as the same schedule", () => {
    expect(sameSchedule("0 9 * * 1", " 0 9 * * 1 ")).toBe(true);
    expect(sameSchedule("0 9 * * 7", "00 09 * * 0")).toBe(true);
    expect(sameSchedule("0 9 * * 1", "0 9 * * 2")).toBe(false);
    expect(sameSchedule("0 9 1 * *", "0 9 * * *")).toBe(false);
    expect(sameSchedule(null, "0 9 * * *")).toBe(false);
  });

  it("finds the approval already in front of a step, and a second insert is a no-op", () => {
    const node = (id: string, type: string, x = 0) => ({ id, type, position: { x, y: 0 }, data: { label: id, config: {} } });
    const nodes = [node("t", "trigger"), node("o", "output", 600)];
    const edges = [{ id: "e1", source: "t", target: "o" }];
    expect(existingApprovalBefore(nodes, edges, ["o"])).toBeNull();
    const first = insertApprovalBefore(nodes, edges, ["o"], { label: "Approve", message: "Check." });
    expect(first.changed).toBe(true);
    expect(existingApprovalBefore(first.nodes, first.edges, ["o"])).toBe("auto-approval-o");
    const second = insertApprovalBefore(first.nodes, first.edges, ["o"], { label: "Approve", message: "Check." });
    expect(second.changed).toBe(false);
  });

  it("does not drop the new approval on top of an existing node", () => {
    const at = (id: string, type: string, x: number, y: number) => ({ id, type, position: { x, y }, data: { label: id, config: {} } });
    // The midpoint + 80 of t(0,0) -> o(600,0) is (300,80); a node already sits there.
    const nodes = [at("t", "trigger", 0, 0), at("x", "agent", 300, 80), at("o", "output", 600, 0)];
    const edges = [{ id: "e1", source: "t", target: "o" }];
    const res = insertApprovalBefore(nodes, edges, ["o"], { label: "Approve", message: "Check." });
    const added = res.nodes.find((n) => n.id === "auto-approval-o");
    expect(added?.position).toEqual({ x: 300, y: 200 });
  });
});
