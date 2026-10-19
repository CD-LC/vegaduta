// Time-indexed views of a RunModel: which visit handed off to which along an
// edge (linkVisits), the whole canvas at one moment (frameAt) and the event
// offsets a scrubber steps through. Re-exported through ./runModel.
import type { EdgeRunState } from "./WorkflowEdge";
import type {
  EdgeFrame,
  EdgeFrameState,
  NodeFrame,
  NodeFrameStatus,
  RunEdge,
  RunFrame,
  RunModel,
  RunStatus,
  TraceVisit,
  VisitLink,
} from "./runModelTypes";

// ---------------------------------------------------------------------------
// Visit linking - which visit of `source` handed off to which visit of `target`
// ---------------------------------------------------------------------------

// Tolerance for a target stamped a hair before its source's completedAt
// (separate Instant.now() calls, Temporal activity scheduling).
export const LINK_SLOP_MS = 50;
export const ERROR_HANDLE = "error";

export function handleOf(e: RunEdge): string | null {
  return e.sourceHandle ? String(e.sourceHandle) : null;
}

function visitCanFollow(edge: RunEdge, from: TraceVisit): boolean {
  const h = handleOf(edge);
  if (h != null && h.toLowerCase() === ERROR_HANDLE) return from.status === "error";
  if (from.status === "error" || from.status === "running" || from.status === "paused") return false;
  if (from.branch != null && h != null && h.toLowerCase() !== from.branch) return false;
  return true;
}

const linkCache = new WeakMap<RunModel, WeakMap<readonly RunEdge[], VisitLink[]>>();

/**
 * Pairs each target visit with the most recent eligible source visit that
 * finished before it started, consuming each source visit at most once per
 * edge - so a loop's back-edge counts every pass while the entry edge counts
 * only the first. Memoized per (model, edges array).
 */
export function linkVisits(model: RunModel, edges: readonly RunEdge[]): VisitLink[] {
  let perModel = linkCache.get(model);
  const cached = perModel?.get(edges);
  if (cached) return cached;
  const links: VisitLink[] = [];
  for (const edge of edges) {
    const sources = (model.byNode[edge.source] ?? [])
      .filter((s) => s.endMs != null && visitCanFollow(edge, s))
      .sort((a, b) => (a.endMs as number) - (b.endMs as number) || a.seq - b.seq);
    const targets = model.byNode[edge.target] ?? [];
    const used = new Set<number>();
    for (const to of targets) {
      let best: TraceVisit | null = null;
      for (const s of sources) {
        if (s.seq === to.seq) continue;
        if ((s.endMs as number) <= to.startMs + LINK_SLOP_MS && s.startMs <= to.startMs) best = s;
      }
      if (best && !used.has(best.seq)) {
        used.add(best.seq);
        links.push({ edgeId: edge.id, from: best, to });
      }
    }
  }
  links.sort((a, b) => a.to.startMs - b.to.startMs || a.to.seq - b.to.seq);
  if (!perModel) {
    perModel = new WeakMap();
    linkCache.set(model, perModel);
  }
  perModel.set(edges, links);
  return links;
}

// ---------------------------------------------------------------------------
// frameAt
// ---------------------------------------------------------------------------

function visitOpenAt(v: TraceVisit, at: number): boolean {
  return v.endMs == null || v.endMs > at;
}

function frameStatusOf(v: TraceVisit): NodeFrameStatus {
  switch (v.status) {
    case "ok":
      return "ok";
    case "error":
      return "error";
    case "paused":
      return "paused";
    case "running":
      return "running";
    case "skipped":
    case "cancelled":
      return "skipped";
  }
}

function danglingStatus(s: RunStatus): NodeFrameStatus {
  if (s === "failed") return "error";
  if (s === "completed") return "ok";
  return "skipped";
}

/**
 * The whole canvas at one moment. `tMs` is milliseconds since `model.t0`
 * (the scrubber's natural unit); it is clamped, so pass `Infinity` for the
 * end state - which is also what prefers-reduced-motion should jump to.
 * Nodes that never ran are absent from `nodes` (treat as pending).
 */
export function frameAt(model: RunModel, tMs: number, edges: readonly RunEdge[] = []): RunFrame {
  const rel = Number.isNaN(tMs) ? 0 : Math.min(Math.max(tMs, 0), model.totalMs);
  const at = model.t0 + rel;
  const atEnd = rel >= model.totalMs;
  const nodes: Record<string, NodeFrame> = {};

  for (const [nodeId, all] of Object.entries(model.byNode)) {
    const started = all.filter((v) => v.startMs <= at);
    if (!started.length) continue;
    // Latest open visit wins, so parallel fan-out of one node reads "running"
    // until its last copy lands.
    const open = started.filter((v) => visitOpenAt(v, at));
    const running = open.length ? open[open.length - 1] : null;
    const active = running ?? started[started.length - 1];
    let status: NodeFrameStatus = running ? "running" : frameStatusOf(active);
    // An entry left open in a run that has already ended was never closed
    // by the engine (crash, cancel mid-node): show the run's verdict, not a
    // spinner that will never stop.
    if (running && running.endMs == null && !model.live) status = danglingStatus(model.run.status);
    const endForElapsed = active.endMs == null || active.endMs > at ? at : active.endMs;
    nodes[nodeId] = {
      status,
      visits: started,
      activeVisit: active,
      elapsedMs: Math.max(0, endForElapsed - active.startMs),
      errorCount: started.filter((v) => v.status === "error").length,
      cancelled: active.status === "cancelled",
    };
  }

  const edgeFrames: Record<string, EdgeFrame> = {};
  if (edges.length) {
    const links = linkVisits(model, edges).filter((l) => l.to.startMs <= at);
    const byEdge: Record<string, VisitLink[]> = {};
    for (const l of links) (byEdge[l.edgeId] ??= []).push(l);
    const bySource: Record<string, RunEdge[]> = {};
    for (const e of edges) (bySource[e.source] ??= []).push(e);
    const runOver = atEnd && !model.live;

    for (const edge of edges) {
      const mine = byEdge[edge.id] ?? [];
      if (mine.length) {
        const last = mine[mine.length - 1];
        const flowing = visitOpenAt(last.to, at) && nodes[edge.target]?.status === "running";
        edgeFrames[edge.id] = { state: flowing ? "flowing" : "taken", traversals: mine.length };
        continue;
      }
      edgeFrames[edge.id] = { state: untakenEdgeState(edge, nodes[edge.source], bySource[edge.source] ?? [], byEdge, runOver), traversals: 0 };
    }
  }

  return { tMs: rel, nodes, edges: edgeFrames };
}

function untakenEdgeState(
  edge: RunEdge,
  src: NodeFrame | undefined,
  siblings: RunEdge[],
  byEdge: Record<string, VisitLink[]>,
  runOver: boolean,
): EdgeFrameState {
  if (!src || src.status === "pending" || src.status === "running" || !src.activeVisit) return "pending";
  const latest = src.activeVisit;
  const isErrorPort = (handleOf(edge) ?? "").toLowerCase() === ERROR_HANDLE;
  // A sibling edge carried the source's latest visit onward: this one lost.
  const siblingTookLatest = siblings.some(
    (s) => s.id !== edge.id && (byEdge[s.id] ?? []).some((l) => l.from.seq === latest.seq),
  );
  if (src.status === "paused") return "awaiting-approval";
  if (src.status === "error") {
    if (isErrorPort) return runOver ? "failed" : "pending";
    return siblingTookLatest ? "skipped" : "failed";
  }
  if (siblingTookLatest) return "skipped";
  if (!visitCanFollow(edge, latest)) return "skipped";
  return runOver ? "skipped" : "pending";
}

/** Map the model's edge vocabulary onto WorkflowEdge's `runState` prop. */
export function toEdgeRunState(state: EdgeFrameState): EdgeRunState {
  return state === "taken" ? "done" : state;
}

/**
 * Sorted, de-duplicated timeline offsets (ms since t0) where anything starts
 * or ends - lets a keyboard user step the scrubber event by event.
 */
export function keyframes(model: RunModel): number[] {
  const set = new Set<number>([0, model.totalMs]);
  for (const v of model.visits) {
    set.add(v.startMs - model.t0);
    if (v.endMs != null) set.add(v.endMs - model.t0);
  }
  return [...set].filter((n) => n >= 0 && n <= model.totalMs).sort((a, b) => a - b);
}
