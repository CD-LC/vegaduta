// Pure logic for the Build Center: kept out of the components so it is
// unit-testable (logic.test.ts) and so the trust-rung rules exist in exactly
// one place on the web side. The rung rules mirror section 1 of
// docs/plans/EARNED-AUTONOMY-CONTRACT.md; core materialises the real APPROVAL
// nodes, this only decides where the UI draws the shield.

import type {
  ApplyJob,
  ApplyStep,
  Brief,
  BriefDetail,
  BriefGraphNode,
  PlanStreamEvent,
  ReadinessRow,
  ReadinessState,
  ReadinessSummary,
  Rung,
} from "./types";
import { RUNGS } from "./types";

// ---- trust rung ---------------------------------------------------------

// Workflow node types that sit behind an APPROVAL at UNDER_WATCH (contract
// section 1, first row).
export const GUARDED_NODE_TYPES: ReadonlySet<string> = new Set([
  "output",
  "tool",
  "http",
  "bulk_email",
  "crm_lead",
  "device_control",
  "media",
]);

export function nextRung(rung: Rung): Rung | null {
  const i = RUNGS.indexOf(rung);
  return i >= 0 && i < RUNGS.length - 1 ? RUNGS[i + 1] : null;
}

export interface Guard {
  /** approval = an APPROVAL node in front; human = a HUMAN_INPUT that survives AUTONOMOUS. */
  kind: "approval" | "human";
  irreversible: boolean;
  next: Rung | null;
  /** True when moving to `next` removes this check entirely. */
  disappearsAtNext: boolean;
}

/** Is a workflow node guarded at `rung`, and what happens to the guard at the next rung? */
export function approvalGuard(rung: Rung, nodeType: string, path: string, trust: Brief["trust"]): Guard | null {
  const irreversible = trust.irreversible.includes(path);
  const humanMustDecide = (trust.humanMustDecide ?? []).includes(path);
  const next = nextRung(rung);
  if (rung === "AUTONOMOUS") {
    return humanMustDecide ? { kind: "human", irreversible, next, disappearsAtNext: false } : null;
  }
  const guarded = rung === "UNDER_WATCH" ? GUARDED_NODE_TYPES.has(nodeType) || irreversible : irreversible;
  if (!guarded) return null;
  // Going to TRUSTED keeps only irreversible steps; going to AUTONOMOUS keeps
  // only the ones a human must decide.
  const stays = rung === "UNDER_WATCH" ? irreversible : humanMustDecide;
  return { kind: "approval", irreversible, next, disappearsAtNext: !stays };
}

// ---- deep link ----------------------------------------------------------

/** `/route?tour=field:<id>` - consumed by TourProvider (WEB 3). Keeps any query already on the route. */
export function deepLink(route: string, fieldId: string): string {
  const hashAt = route.indexOf("#");
  const hash = hashAt >= 0 ? route.slice(hashAt) : "";
  const base = hashAt >= 0 ? route.slice(0, hashAt) : route;
  const sep = base.includes("?") ? "&" : "?";
  return `${base}${sep}tour=field:${encodeURIComponent(fieldId)}${hash}`;
}

export type ArtifactKind = "agent" | "workflow" | "tool" | "mcp" | "knowledge" | "procedure" | "secret" | "channel";

const KIND_BY_PREFIX: Record<string, ArtifactKind> = {
  a: "agent",
  w: "workflow",
  t: "tool",
  m: "mcp",
  k: "knowledge",
  p: "procedure",
  s: "secret",
  c: "channel",
};

/** "a1" -> "agent", "w1.n4" -> "workflow" (a node path belongs to its workflow), unknown -> null. */
export function kindOfRef(ref: string): ArtifactKind | null {
  const m = /^([a-z])[0-9]{1,2}/.exec(ref);
  return m ? (KIND_BY_PREFIX[m[1]] ?? null) : null;
}

/**
 * Where "open in editor" lands for a ref, or null while the envoy is not
 * applied (no entity exists to edit yet). The field ids are the data-tour ids
 * the contract lists; WEB 3 adds them to the real controls.
 */
export function editorTargetFor(ref: string, detail: Pick<BriefDetail, "bindings"> | null): { href: string; fieldId: string } | null {
  const kind = kindOfRef(ref);
  if (!kind) return null;
  const root = ref.split(".")[0];
  const entityId = detail?.bindings?.find((b) => b.ref === root)?.entityId;
  if (kind === "secret") return { href: deepLink("/secrets", "secret-add"), fieldId: "secret-add" };
  if (kind === "mcp") return { href: deepLink("/tools", "mcp-connect"), fieldId: "mcp-connect" };
  if (!entityId) return null;
  if (kind === "agent") return { href: deepLink(`/agents?agentId=${encodeURIComponent(entityId)}`, "workbench-system-prompt"), fieldId: "workbench-system-prompt" };
  if (kind === "workflow") {
    // The canvas as a whole is the only anchor a workflow page has; individual nodes carry no data-tour id, so a
    // per-node id would silently light up nothing.
    return { href: deepLink(`/workflows/${encodeURIComponent(entityId)}`, "canvas"), fieldId: "canvas" };
  }
  if (kind === "knowledge") return { href: deepLink("/knowledge", "knowledge-toggle"), fieldId: "knowledge-toggle" };
  return null;
}

// ---- readiness ----------------------------------------------------------

export function summariseReadiness(rows: readonly ReadinessRow[]): ReadinessSummary & { total: number; needsAttention: number } {
  const out = { ready: 0, missing: 0, waitingOnAdmin: 0, gated: 0, simulated: 0, total: rows.length, needsAttention: 0 };
  for (const r of rows) {
    if (r.state === "READY") out.ready++;
    else if (r.state === "MISSING") out.missing++;
    else if (r.state === "WAITING_ON_ADMIN") out.waitingOnAdmin++;
    else if (r.state === "GATED") out.gated++;
    else if (r.state === "SIMULATED") out.simulated++;
  }
  out.needsAttention = out.missing + out.waitingOnAdmin + out.gated;
  return out;
}

/** Design-system state token per readiness state (colour is backed by a text label in the UI). */
export const READINESS_STATE_TOKEN: Record<ReadinessState, string> = {
  READY: "var(--state-success)",
  MISSING: "var(--state-error)",
  WAITING_ON_ADMIN: "var(--state-warning)",
  GATED: "var(--state-idle)",
  SIMULATED: "var(--state-queued)",
};

/** Stable display order: what the person can act on first, ready rows last. Never drops a row. */
export function sortReadiness(rows: readonly ReadinessRow[]): ReadinessRow[] {
  const order: ReadinessState[] = ["MISSING", "WAITING_ON_ADMIN", "GATED", "SIMULATED", "READY"];
  return rows
    .map((r, i) => ({ r, i }))
    .sort((a, b) => order.indexOf(a.r.state) - order.indexOf(b.r.state) || a.i - b.i)
    .map((x) => x.r);
}

// ---- apply job ----------------------------------------------------------

export interface ApplyProgress {
  total: number;
  done: number;
  terminal: boolean;
  failed: boolean;
  failedStep: ApplyStep | null;
  /** True when a failed job rolled back everything it had made. */
  nothingKept: boolean;
}

export function summariseApply(job: ApplyJob | null | undefined): ApplyProgress {
  const steps = job?.steps ?? [];
  const failedStep = job?.failedStep ?? steps.find((s) => s.status === "FAILED") ?? null;
  // A step can flip to FAILED while the job is still compensating: that is not the end, and "what was kept" is not
  // known until the job itself says FAILED.
  const failed = job?.status === "FAILED";
  const done = steps.filter((s) => s.status === "DONE").length;
  // Orphans are entities a failed apply could not remove again: they are kept, whatever the steps say.
  const kept = steps.some((s) => s.status === "DONE") || (job?.orphans?.length ?? 0) > 0;
  return {
    total: steps.length,
    done,
    terminal: job?.status === "SUCCEEDED" || failed,
    failed,
    failedStep,
    // Only claim "nothing was kept" when the ledger says so: no step is still DONE.
    nothingKept: failed && !kept,
  };
}

// ---- SSE ----------------------------------------------------------------

/**
 * Incremental text/event-stream parser for /api/briefs/plan. Feed it the whole
 * buffer so far; it returns the complete events and the unconsumed tail.
 * Events (core): thinking {step}, section {name, data}, done {brief, readiness, warnings}, error {error}.
 */
export function parseSse(buffer: string): { events: PlanStreamEvent[]; rest: string } {
  const events: PlanStreamEvent[] = [];
  const blocks = buffer.split(/\r?\n\r?\n/);
  const rest = blocks.pop() ?? "";
  for (const block of blocks) {
    let name = "message";
    const data: string[] = [];
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith("event:")) name = line.slice(6).trim();
      else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
    }
    if (data.length === 0) continue;
    const raw = data.join("\n");
    let payload: any = raw;
    try {
      payload = JSON.parse(raw);
    } catch {
      // plain-text data is legal for thinking lines
    }
    if (name === "thinking") events.push({ type: "thinking", text: typeof payload === "string" ? payload : String(payload?.step ?? payload?.text ?? "") });
    else if (name === "section") events.push({ type: "section", name: String(payload?.name ?? ""), data: payload?.data });
    else if (name === "done") events.push({ type: "done", result: payload });
    else if (name === "error") events.push({ type: "error", message: String(payload?.error ?? payload ?? "") });
  }
  return { events, rest };
}

// ---- envoy graph --------------------------------------------------------

export type GraphMode = "simple" | "guided" | "expert";

export interface EnvoyNodeData extends Record<string, unknown> {
  kind: ArtifactKind | "step";
  /** Artifact ref (a1) or workflow node path (w1.n2) - the key into narrative.perNode. */
  ref: string;
  label: string;
  sub?: string;
  /** step nodes: the workflow node type, for the icon. */
  nodeType?: string;
  state?: ReadinessState;
  guardCount?: number;
  expandable?: boolean;
  expanded?: boolean;
  guard?: Guard | null;
}

export interface EnvoyNode {
  id: string;
  type: "envoy" | "frame" | "step";
  position: { x: number; y: number };
  parentId?: string;
  extent?: "parent";
  draggable?: boolean;
  width?: number;
  height?: number;
  data: EnvoyNodeData;
}

export interface EnvoyEdge {
  id: string;
  source: string;
  target: string;
  type: "shield" | "default";
  data?: { guard: Guard | null; targetPath?: string };
}

export interface EnvoyGraphOptions {
  rung: Rung;
  mode?: GraphMode;
  /** workflow refs shown as an inline graph */
  expanded?: ReadonlySet<string>;
  /** ref -> readiness state (secrets show a state dot) */
  readinessByRef?: Readonly<Record<string, ReadinessState>>;
}

export const NODE_W = 196;
export const NODE_H = 64;
export const STEP_W = 168;
export const STEP_H = 52;
const COL_GAP = 84;
const ROW_GAP = 22;
const FRAME_PAD = 16;
const FRAME_HEAD = 34;

const REF_RE = /^[a-z][0-9]{1,2}$/;

/** Every string in a node config that is an artifact ref, plus secrets named as {{secret.NAME}}. */
function configRefs(config: unknown, known: ReadonlySet<string>, secretByName: ReadonlyMap<string, string>): Set<string> {
  const found = new Set<string>();
  const walk = (v: unknown) => {
    if (typeof v === "string") {
      if (REF_RE.test(v) && known.has(v)) found.add(v);
      for (const m of v.matchAll(/\{\{\s*secret\.([A-Z][A-Z0-9_]*)\s*\}\}/g)) {
        const ref = secretByName.get(m[1]);
        if (ref) found.add(ref);
      }
    } else if (Array.isArray(v)) v.forEach(walk);
    else if (v && typeof v === "object") Object.values(v as object).forEach(walk);
  };
  walk(config);
  return found;
}

function refsOfToolAllowList(list: readonly string[] | undefined, known: ReadonlySet<string>): string[] {
  const out: string[] = [];
  for (const entry of list ?? []) {
    if (known.has(entry)) out.push(entry);
    const mcp = /^mcp:([a-z][0-9]{1,2})\//.exec(entry);
    if (mcp && known.has(mcp[1])) out.push(mcp[1]);
  }
  return out;
}

const COLUMN_OF: Record<ArtifactKind, number> = {
  channel: 0,
  workflow: 1,
  agent: 2,
  tool: 3,
  mcp: 3,
  knowledge: 4,
  procedure: 4,
  secret: 5,
};

const SIMPLE_KINDS: ReadonlySet<ArtifactKind> = new Set(["agent", "workflow", "channel"]);

function nodeLabel(n: BriefGraphNode): string {
  return n.data?.label || n.type;
}

interface Placed {
  id: string;
  kind: ArtifactKind;
  ref: string;
  label: string;
  sub?: string;
  width: number;
  height: number;
  frame?: { nodes: EnvoyNode[]; edges: EnvoyEdge[] };
  guardCount?: number;
  expandable?: boolean;
  expanded?: boolean;
}

/** Turn a Brief into positioned @xyflow/react nodes and edges. Pure: same Brief in, same graph out. */
export function buildEnvoyGraph(brief: Brief, opts: EnvoyGraphOptions): { nodes: EnvoyNode[]; edges: EnvoyEdge[] } {
  const mode = opts.mode ?? "guided";
  const expanded = opts.expanded ?? new Set<string>();
  const stateOf = opts.readinessByRef ?? {};

  const all: { kind: ArtifactKind; ref: string; label: string; sub?: string }[] = [
    ...(brief.channels ?? []).map((c) => ({ kind: "channel" as const, ref: c.ref, label: c.kind, sub: c.optional === false ? undefined : "optional" })),
    ...brief.workflows.map((w) => ({ kind: "workflow" as const, ref: w.ref, label: w.name })),
    ...brief.agents.map((a) => ({ kind: "agent" as const, ref: a.ref, label: a.name, sub: a.reasoningDepth })),
    ...(brief.tools ?? []).map((t) => ({ kind: "tool" as const, ref: t.ref, label: t.name || t.ref, sub: t.kind })),
    ...(brief.mcpServers ?? []).map((m) => ({ kind: "mcp" as const, ref: m.ref, label: m.name || m.registryId })),
    ...(brief.knowledge ?? []).map((k) => ({ kind: "knowledge" as const, ref: k.ref, label: k.name })),
    ...(brief.procedures ?? []).map((p) => ({ kind: "procedure" as const, ref: p.ref, label: p.name, sub: `${p.steps.length}` })),
    ...(brief.secrets ?? []).map((s) => ({ kind: "secret" as const, ref: s.ref, label: s.name, sub: s.scope })),
  ];
  const visible = all.filter((a) => mode !== "simple" || SIMPLE_KINDS.has(a.kind));
  const visibleRefs = new Set(visible.map((a) => a.ref));
  const allRefs = new Set(all.map((a) => a.ref));
  const secretByName = new Map((brief.secrets ?? []).map((s) => [s.name, s.ref] as const));

  // Workflow inner graphs first: the frame size decides the column geometry.
  const placed = new Map<string, Placed>();
  for (const a of visible) placed.set(a.ref, { ...a, id: a.ref, width: NODE_W, height: NODE_H });

  const edges: EnvoyEdge[] = [];
  const seen = new Set<string>();
  const addEdge = (source: string, target: string, guard: Guard | null = null, targetPath?: string) => {
    const id = `${source}->${target}`;
    if (seen.has(id) || source === target) return;
    seen.add(id);
    edges.push({ id, source, target, type: guard ? "shield" : "default", data: guard ? { guard, targetPath } : undefined });
  };

  for (const wf of brief.workflows) {
    const p = placed.get(wf.ref);
    if (!p) continue;
    const guards = new Map<string, Guard | null>();
    for (const n of wf.graph.nodes) {
      const path = `${wf.ref}.${n.id}`;
      guards.set(n.id, approvalGuard(opts.rung, n.type, path, brief.trust));
    }
    p.guardCount = [...guards.values()].filter(Boolean).length;
    p.expandable = mode !== "simple";
    const isOpen = mode !== "simple" && expanded.has(wf.ref);
    p.expanded = isOpen;

    // Cross-artifact edges: from the step when open, else from the workflow node.
    for (const n of wf.graph.nodes) {
      const from = isOpen ? `${wf.ref}.${n.id}` : wf.ref;
      for (const ref of configRefs(n.data?.config, allRefs, secretByName)) {
        if (visibleRefs.has(ref)) addEdge(from, ref);
      }
    }

    if (isOpen) {
      const xs = wf.graph.nodes.map((n, i) => n.position?.x ?? i * (STEP_W + 40));
      const ys = wf.graph.nodes.map((n) => n.position?.y ?? 0);
      const minX = Math.min(...xs);
      const minY = Math.min(...ys);
      const innerNodes: EnvoyNode[] = wf.graph.nodes.map((n, i) => ({
        id: `${wf.ref}.${n.id}`,
        type: "step",
        parentId: wf.ref,
        extent: "parent",
        draggable: false,
        width: STEP_W,
        height: STEP_H,
        position: { x: FRAME_PAD + xs[i] - minX, y: FRAME_HEAD + FRAME_PAD + ys[i] - minY },
        data: { kind: "step", ref: `${wf.ref}.${n.id}`, label: nodeLabel(n), nodeType: n.type, guard: guards.get(n.id) ?? null },
      }));
      const innerEdges: EnvoyEdge[] = [];
      for (const e of wf.graph.edges) {
        const guard = guards.get(e.target) ?? null;
        const id = `${wf.ref}:${e.id ?? `${e.source}-${e.target}`}`;
        innerEdges.push({
          id,
          source: `${wf.ref}.${e.source}`,
          target: `${wf.ref}.${e.target}`,
          type: guard ? "shield" : "default",
          data: guard ? { guard, targetPath: `${wf.ref}.${e.target}` } : undefined,
        });
      }
      const right = Math.max(...innerNodes.map((n) => n.position.x + STEP_W));
      const bottom = Math.max(...innerNodes.map((n) => n.position.y + STEP_H));
      p.width = right + FRAME_PAD;
      p.height = bottom + FRAME_PAD;
      p.frame = { nodes: innerNodes, edges: innerEdges };
    }
  }

  for (const a of brief.agents) {
    if (!placed.has(a.ref)) continue;
    const deps = [
      ...(a.knowledgeRefs ?? []),
      ...(a.procedureRefs ?? []),
      ...refsOfToolAllowList(a.toolAllowList, allRefs),
    ];
    for (const d of deps) if (visibleRefs.has(d)) addEdge(a.ref, d);
  }
  for (const t of brief.tools ?? []) {
    if (t.auth?.secretRef && visibleRefs.has(t.ref) && visibleRefs.has(t.auth.secretRef)) addEdge(t.ref, t.auth.secretRef);
  }
  for (const m of brief.mcpServers ?? []) {
    for (const env of Object.values(m.env ?? {})) {
      if (visibleRefs.has(m.ref) && visibleRefs.has(env.secretRef)) addEdge(m.ref, env.secretRef);
    }
  }
  for (const c of brief.channels ?? []) {
    if (visibleRefs.has(c.ref) && visibleRefs.has(c.bindTo)) addEdge(c.ref, c.bindTo);
  }
  for (const w of brief.workflows) {
    for (const trg of w.triggers ?? []) {
      if (trg.channelRef && visibleRefs.has(trg.channelRef) && visibleRefs.has(w.ref)) addEdge(trg.channelRef, w.ref);
    }
  }

  // Column layout: left to right by dependency depth, stacked top to bottom.
  const columns = new Map<number, Placed[]>();
  for (const p of placed.values()) {
    const col = COLUMN_OF[p.kind];
    columns.set(col, [...(columns.get(col) ?? []), p]);
  }
  const nodes: EnvoyNode[] = [];
  let x = 0;
  for (const col of [...columns.keys()].sort((a, b) => a - b)) {
    const items = columns.get(col)!;
    let y = 0;
    for (const p of items) {
      const isFrame = !!p.frame;
      nodes.push({
        id: p.id,
        type: isFrame ? "frame" : "envoy",
        position: { x, y },
        width: p.width,
        height: isFrame ? p.height : p.height,
        data: {
          kind: p.kind,
          ref: p.ref,
          label: p.label,
          sub: p.sub,
          state: p.kind === "secret" ? stateOf[p.ref] : undefined,
          guardCount: p.guardCount,
          expandable: p.expandable,
          expanded: p.expanded,
        },
      });
      if (p.frame) nodes.push(...p.frame.nodes);
      y += p.height + ROW_GAP;
    }
    x += Math.max(...items.map((p) => p.width)) + COL_GAP;
  }
  for (const p of placed.values()) if (p.frame) edges.push(...p.frame.edges);
  return { nodes, edges };
}

/** How many shields are drawn at `rung`, and how many of them go away at the next rung. */
export function guardTotals(brief: Brief, rung: Rung): { guarded: number; disappearAtNext: number; next: Rung | null } {
  let guarded = 0;
  let disappearAtNext = 0;
  for (const wf of brief.workflows) {
    for (const n of wf.graph.nodes) {
      const g = approvalGuard(rung, n.type, `${wf.ref}.${n.id}`, brief.trust);
      if (!g) continue;
      guarded++;
      if (g.disappearsAtNext) disappearAtNext++;
    }
  }
  return { guarded, disappearAtNext, next: nextRung(rung) };
}

/** Human label for an artifact ref or a workflow node path, or the ref itself when unknown. */
export function labelOfRef(brief: Brief, ref: string): string {
  const [root, node] = ref.split(".");
  const base =
    brief.agents.find((a) => a.ref === root)?.name ??
    brief.workflows.find((w) => w.ref === root)?.name ??
    brief.tools?.find((t) => t.ref === root)?.name ??
    brief.mcpServers?.find((m) => m.ref === root)?.name ??
    brief.knowledge?.find((k) => k.ref === root)?.name ??
    brief.procedures?.find((p) => p.ref === root)?.name ??
    brief.secrets?.find((s) => s.ref === root)?.name ??
    brief.channels?.find((c) => c.ref === root)?.kind;
  if (!base) return ref;
  if (!node) return base;
  const step = brief.workflows.find((w) => w.ref === root)?.graph.nodes.find((n) => n.id === node);
  return step?.data?.label ? `${base} / ${step.data.label}` : `${base} / ${node}`;
}
