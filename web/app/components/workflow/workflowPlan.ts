// "What this workflow does" - the pure half of the plan panel (nlflow W4).
//
// POST /api/workflows/{id}/generate-graph answers with the graph plus, on a
// current core, a deterministic explanation, a list of what still has to be
// connected, the approval steps the server added in front of sends, and a
// schedule the sentence implied. An older core returns only {graph, notes}.
// Everything here treats each of those fields as optional and as untrusted
// JSON: a wrong shape is dropped, never thrown on, so the canvas degrades to
// "graph + notes" instead of breaking.
//
// Pure module: no React, no runtime imports.

export type PlanStep = { nodeId: string; label: string; type: string; sentence: string };

export type PlanRequirementKind = "secret" | "knowledge" | "agent" | "channel" | "approval-policy" | "other";

export type PlanRequirement = {
  kind: PlanRequirementKind;
  message: string;
  /** Console path that resolves it; null when the server sent none or an unsafe one. */
  href: string | null;
  nodeId: string | null;
};

export type PlanInsertedApproval = { approvalNodeId: string; beforeNodeId: string; sendNodeId: string };

export type PlanSchedule = { cron: string; label: string; timezone: string };

export type WorkflowPlan = {
  mode: "create" | "edit";
  steps: PlanStep[];
  notes: string[];
  requirements: PlanRequirement[];
  insertedApprovals: PlanInsertedApproval[];
  schedule: PlanSchedule | null;
  /**
   * True when the response carried at least one of the fields only a current
   * core sends (explanation / requirements / insertedApprovals / mode). The
   * same core release is the one that understands simulateSends on a draft
   * run, so the canvas offers "Test run" only then: an older core would
   * ignore the unknown property and really send.
   */
  testRunSupported?: boolean;
};

const isStr = (v: unknown): v is string => typeof v === "string";
const isObj = (v: unknown): v is Record<string, unknown> => !!v && typeof v === "object" && !Array.isArray(v);
const nonBlank = (v: unknown): v is string => isStr(v) && v.trim() !== "";

const REQUIREMENT_KINDS: readonly string[] = ["secret", "knowledge", "agent", "channel", "approval-policy"];

/**
 * A requirement link is followed with the user's session, so only a
 * same-origin console path is kept: one leading slash, no scheme, no
 * protocol-relative "//host", no backslash tricks.
 */
export function safePlanHref(raw: unknown): string | null {
  if (!isStr(raw)) return null;
  const href = raw.trim();
  if (!/^\/(?![/\\])[^\s\\]*$/.test(href)) return null;
  return href;
}

/** Trims a five-field cron; null for anything else (the server already validated it, this guards the wire). */
export function normalizeCron(raw: unknown): string | null {
  if (!isStr(raw)) return null;
  const parts = raw.trim().split(/\s+/);
  if (parts.length !== 5 || parts.some((p) => !/^[0-9A-Za-z*,/\-?#]+$/.test(p))) return null;
  return parts.join(" ");
}

/** Defensive parse of the generate-graph response into what the panel shows. */
export function normalizePlan(raw: unknown): WorkflowPlan {
  const r = isObj(raw) ? raw : {};

  const steps: PlanStep[] = [];
  if (Array.isArray(r.explanation)) {
    for (const item of r.explanation) {
      if (!isObj(item) || !nonBlank(item.sentence)) continue;
      steps.push({
        nodeId: isStr(item.nodeId) ? item.nodeId : "",
        label: isStr(item.label) ? item.label : "",
        type: isStr(item.type) ? item.type.toLowerCase() : "",
        sentence: item.sentence.trim(),
      });
    }
  }

  const notes = Array.isArray(r.notes) ? r.notes.filter(nonBlank).map((n) => n.trim()) : [];

  const requirements: PlanRequirement[] = [];
  const req = isObj(r.requirements) ? r.requirements : {};
  if (Array.isArray(req.items)) {
    for (const item of req.items) {
      if (!isObj(item) || !nonBlank(item.message)) continue;
      requirements.push({
        kind: isStr(item.kind) && REQUIREMENT_KINDS.includes(item.kind) ? (item.kind as PlanRequirementKind) : "other",
        message: item.message.trim(),
        href: safePlanHref(item.href),
        nodeId: nonBlank(item.nodeId) ? item.nodeId : null,
      });
    }
  }

  const insertedApprovals: PlanInsertedApproval[] = [];
  if (Array.isArray(r.insertedApprovals)) {
    for (const item of r.insertedApprovals) {
      if (!isObj(item) || !nonBlank(item.approvalNodeId)) continue;
      insertedApprovals.push({
        approvalNodeId: item.approvalNodeId,
        beforeNodeId: isStr(item.beforeNodeId) ? item.beforeNodeId : "",
        sendNodeId: isStr(item.sendNodeId) ? item.sendNodeId : "",
      });
    }
  }

  let schedule: PlanSchedule | null = null;
  const sched = r.suggestedSchedule;
  if (isObj(sched)) {
    const cron = normalizeCron(sched.cron);
    if (cron) {
      schedule = {
        cron,
        label: nonBlank(sched.label) ? sched.label.trim() : cron,
        timezone: nonBlank(sched.timezone) ? sched.timezone.trim() : "UTC",
      };
    }
  }

  const testRunSupported =
    Array.isArray(r.explanation) || Array.isArray(r.insertedApprovals) || isObj(r.requirements) || isStr(r.mode);

  return {
    mode: r.mode === "edit" ? "edit" : "create",
    steps,
    notes,
    requirements,
    insertedApprovals,
    schedule,
    testRunSupported,
  };
}

/** True when the panel would have nothing to show (old core that sent no notes either). */
export function isPlanEmpty(plan: WorkflowPlan | null | undefined): boolean {
  return (
    !plan ||
    (plan.steps.length === 0 &&
      plan.notes.length === 0 &&
      plan.requirements.length === 0 &&
      plan.insertedApprovals.length === 0 &&
      !plan.schedule)
  );
}

export type SchedulePreset = { frequency: "hourly" | "daily" | "weekly"; time?: string; day?: string };

/**
 * The trigger strip's picker only knows hourly / daily at HH:MM / weekly on
 * one day at HH:MM. Returns that preset when the suggested cron is exactly
 * one of those, else null - the panel then switches the schedule on with the
 * cron itself instead of showing a picker value that means something else.
 */
export function presetFromCron(raw: unknown): SchedulePreset | null {
  const cron = normalizeCron(raw);
  if (!cron) return null;
  const [min, hour, dom, month, dow] = cron.split(" ");
  if (dom !== "*" || month !== "*") return null;
  if (min === "0" && hour === "*" && dow === "*") return { frequency: "hourly" };
  if (!/^\d{1,2}$/.test(min) || !/^\d{1,2}$/.test(hour)) return null;
  if (Number(min) > 59 || Number(hour) > 23) return null;
  const time = `${hour.padStart(2, "0")}:${min.padStart(2, "0")}`;
  if (dow === "*") return { frequency: "daily", time };
  if (!/^[0-7]$/.test(dow)) return null;
  return { frequency: "weekly", time, day: dow === "7" ? "0" : dow };
}

/**
 * True while the trigger strip's picker still holds exactly the preset that was
 * prefilled from the suggested cron. The strip's "save the schedule" hint is
 * only honest then: Save stores whatever the picker holds.
 */
export function pickerMatchesPreset(
  preset: SchedulePreset | null,
  frequency: string,
  time: string,
  day: string,
): boolean {
  if (!preset) return false;
  if (frequency !== preset.frequency) return false;
  if (preset.time !== undefined && time !== preset.time) return false;
  if (preset.day !== undefined && day !== preset.day) return false;
  return true;
}

/**
 * Whether two crons are the same schedule: equal once trimmed, or the same
 * picker preset ("0 9 * * 7" and "00 09 * * 0" are both weekly Sunday 09:00).
 */
export function sameSchedule(a: unknown, b: unknown): boolean {
  const ca = normalizeCron(a);
  const cb = normalizeCron(b);
  if (!ca || !cb) return false;
  if (ca === cb) return true;
  const pa = presetFromCron(ca);
  const pb = presetFromCron(cb);
  return !!pa && !!pb && pa.frequency === pb.frequency && pa.time === pb.time && pa.day === pb.day;
}

/** The starter canvas is one Trigger and no edges; anything more is worth keeping. */
export function hasRealContent(nodes: readonly unknown[], edges: readonly unknown[]): boolean {
  return nodes.length > 1 || edges.length > 0;
}

export type DescribeMode = "edit" | "replace";

/** Edit mode sends the current graph; "Start over" and an empty canvas do not. */
export function shouldSendCurrentGraph(mode: DescribeMode, nodes: readonly unknown[], edges: readonly unknown[]): boolean {
  return mode === "edit" && hasRealContent(nodes, edges);
}

/** How many sends a dry run simulated: trace entries flagged dryRun === true (absent on every other entry). */
export function countSimulatedSends(trace: unknown): number {
  if (!Array.isArray(trace)) return 0;
  return trace.filter((e) => isObj(e) && e.dryRun === true).length;
}

/** Run mode the server reports for a draft run that simulated its sends. */
export const DRY_RUN_MODE = "draft_dry_run";

// ------------------------------------------------------------ insert approval

type PlanNode = {
  id: string;
  type?: string;
  position?: { x: number; y: number };
  data?: { label?: string; config?: Record<string, unknown> };
};
type PlanEdge = { id: string; source: string; target: string; sourceHandle?: string | null; targetHandle?: string | null };

/** Mirrors core WorkflowSendApprovalGuard: a send inside one of these is gated in front of the container. */
const CONTAINER_TYPES: readonly string[] = ["loop", "fan_out", "parallel"];

export const AUTO_APPROVAL_ID_PREFIX = "auto-approval-";

function unique(base: string, used: Set<string>): string {
  let candidate = base;
  for (let n = 2; used.has(candidate); n++) candidate = `${base}-${n}`;
  used.add(candidate);
  return candidate;
}

/** Rough node card footprint; only used to keep a new node from landing on top of another. */
const CLEAR_X = 200;
const CLEAR_Y = 100;

/** Moves a proposed position down until no existing node sits under it (bounded, so it always returns). */
function clearOf(nodes: readonly PlanNode[], wanted: { x: number; y: number }): { x: number; y: number } {
  const pos = { ...wanted };
  for (let tries = 0; tries < 50; tries++) {
    const hit = nodes.some(
      (n) => n.position && Math.abs(n.position.x - pos.x) < CLEAR_X && Math.abs(n.position.y - pos.y) < CLEAR_Y,
    );
    if (!hit) break;
    pos.y += CLEAR_Y + 20;
  }
  return pos;
}

/**
 * The approval node already feeding one of the given nodes, or null. Lets the
 * "Add approval step" fix say "already gated" (and show the step) when it is
 * clicked again before the server lint has caught up.
 */
export function existingApprovalBefore(
  nodes: readonly PlanNode[],
  edges: readonly PlanEdge[],
  insertBeforeNodeIds: readonly string[],
): string | null {
  const byId = new Map(nodes.map((n) => [n.id, n]));
  for (const targetId of insertBeforeNodeIds) {
    for (const e of edges) {
      if (e.target === targetId && byId.get(e.source)?.type === "approval") return e.source;
    }
  }
  return null;
}

export type InsertApprovalOptions = {
  /** Label for the first approval added. */
  label: string;
  /** Text the approver sees. */
  message: string;
  /** Spread onto each new edge (the canvas passes its edge presentation defaults). */
  edgeDefaults?: Record<string, unknown>;
};

/**
 * Applies the lint fix "insert-approval": for every id in insertBeforeNodeIds
 * an approval node is put in front of that node and the node's incoming edges
 * are routed through it, keeping each edge's id and source handle (so a
 * condition's "true" port still leads the same way). Edges coming from a
 * loop / fan_out / parallel are left alone - those are the container's own
 * body wiring. Same shape the server guard produces for a generated graph.
 *
 * Returns the same arrays, changed: false, when there was nothing to reroute.
 */
export function insertApprovalBefore<N extends PlanNode, E extends PlanEdge>(
  nodes: readonly N[],
  edges: readonly E[],
  insertBeforeNodeIds: readonly string[],
  opts: InsertApprovalOptions,
): { nodes: N[]; edges: E[]; changed: boolean; approvalNodeIds: string[] } {
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const usedNodeIds = new Set(nodes.map((n) => n.id));
  const usedEdgeIds = new Set(edges.map((e) => e.id));
  const usedLabels = new Set(nodes.map((n) => n.data?.label ?? ""));

  const outNodes: N[] = [...nodes];
  let outEdges: E[] = [...edges];
  const approvalNodeIds: string[] = [];

  for (const targetId of new Set(insertBeforeNodeIds)) {
    const target = byId.get(targetId);
    if (!target) continue;
    const reroute = outEdges.filter((e) => {
      if (e.target !== targetId) return false;
      const source = byId.get(e.source);
      return !!source && !CONTAINER_TYPES.includes(source.type ?? "") && source.type !== "approval";
    });
    if (reroute.length === 0) continue;

    const approvalId = unique(`${AUTO_APPROVAL_ID_PREFIX}${targetId}`, usedNodeIds);
    const label = unique(opts.label, usedLabels);
    const from = byId.get(reroute[0].source)?.position;
    const to = target.position ?? { x: 0, y: 0 };
    const position = clearOf(
      outNodes,
      from ? { x: (from.x + to.x) / 2, y: (from.y + to.y) / 2 + 80 } : { x: to.x - 280, y: to.y },
    );

    const approval = {
      id: approvalId,
      type: "approval",
      position,
      data: { label, config: { message: opts.message, adminOnly: false, autoInserted: true } },
    } as unknown as N;
    outNodes.push(approval);
    byId.set(approvalId, approval);

    const rerouteIds = new Set(reroute.map((e) => e.id));
    outEdges = outEdges.map((e) => (rerouteIds.has(e.id) ? { ...e, target: approvalId, targetHandle: undefined } : e));
    outEdges.push({
      ...(opts.edgeDefaults ?? {}),
      id: unique(`e-${approvalId}`, usedEdgeIds),
      source: approvalId,
      target: targetId,
      sourceHandle: undefined,
      targetHandle: undefined,
    } as unknown as E);
    approvalNodeIds.push(approvalId);
  }

  if (approvalNodeIds.length === 0) return { nodes: nodes as N[], edges: edges as E[], changed: false, approvalNodeIds };
  return { nodes: outNodes, edges: outEdges, changed: true, approvalNodeIds };
}
