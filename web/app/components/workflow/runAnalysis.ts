// Analyses over run models: the longest dependency chain of one run
// (criticalPath) and the heat lens over many runs (aggregateRuns).
// Re-exported through ./runModel.
import type { RunEdge, RunModel, TraceVisit } from "./runModelTypes";
import { LINK_SLOP_MS, linkVisits } from "./runTimeline";

// ---------------------------------------------------------------------------
// criticalPath
// ---------------------------------------------------------------------------

export type CriticalPath = {
  /** Unique node ids along the chain, first-seen order. */
  nodeIds: string[];
  /** Every visit on the chain in order (a loop contributes each pass). */
  visits: TraceVisit[];
  /** Edges the chain travels (empty when computed without edges). */
  edgeIds: string[];
  /** Sum of the chain's visit durations. */
  totalMs: number;
};

function effectiveDuration(model: RunModel, v: TraceVisit): number {
  if (v.durationMs != null) return v.durationMs;
  if (v.endMs == null) return Math.max(0, model.t1 - v.startMs);
  return 0;
}

/**
 * Longest-duration dependency chain. With edges, dependencies are the actual
 * visit-to-visit hand-offs (so loops unroll instead of cycling). Without
 * edges, any visit that finished before another started counts as a
 * dependency - the longest strictly sequential chain of steps.
 */
export function criticalPathDetail(model: RunModel, edges: readonly RunEdge[] = []): CriticalPath {
  const n = model.visits.length;
  if (!n) return { nodeIds: [], visits: [], edgeIds: [], totalMs: 0 };
  type Inc = { from: number; edgeId: string | null };
  const incoming: Inc[][] = model.visits.map((): Inc[] => []);
  if (edges.length) {
    for (const l of linkVisits(model, edges)) {
      if (l.from.seq < l.to.seq) incoming[l.to.seq].push({ from: l.from.seq, edgeId: l.edgeId });
    }
  } else {
    for (const v of model.visits) {
      for (const u of model.visits) {
        if (u.seq < v.seq && u.endMs != null && u.endMs <= v.startMs + LINK_SLOP_MS && u.startMs < v.startMs) {
          incoming[v.seq].push({ from: u.seq, edgeId: null });
        }
      }
      // Two untimed visits with identical placement still ran in trace order.
      for (const u of model.visits) {
        if (u.seq === v.seq - 1 && u.startMs === v.startMs && u.endMs === u.startMs) {
          incoming[v.seq].push({ from: u.seq, edgeId: null });
        }
      }
    }
  }
  const best = new Array<number>(n).fill(0);
  const prev = new Array<{ from: number; edgeId: string | null } | null>(n).fill(null);
  for (let i = 0; i < n; i++) {
    let bestIn = -1;
    for (const inc of incoming[i]) {
      if (best[inc.from] > bestIn) {
        bestIn = best[inc.from];
        prev[i] = inc;
      }
    }
    best[i] = Math.max(bestIn, 0) + effectiveDuration(model, model.visits[i]);
  }
  // Ties go to the later visit so zero-length tail steps stay on the chain.
  let end = 0;
  for (let i = 1; i < n; i++) if (best[i] >= best[end]) end = i;
  const chain: TraceVisit[] = [];
  const edgeIds: string[] = [];
  for (let i: number | null = end; i != null; ) {
    chain.unshift(model.visits[i]);
    const p: { from: number; edgeId: string | null } | null = prev[i];
    if (p?.edgeId) edgeIds.unshift(p.edgeId);
    i = p ? p.from : null;
  }
  const nodeIds: string[] = [];
  for (const v of chain) if (!nodeIds.includes(v.nodeId)) nodeIds.push(v.nodeId);
  return { nodeIds, visits: chain, edgeIds, totalMs: best[end] };
}

export function criticalPath(model: RunModel, edges: readonly RunEdge[] = []): string[] {
  return criticalPathDetail(model, edges).nodeIds;
}

// ---------------------------------------------------------------------------
// aggregateRuns - the heat lens over many runs
// ---------------------------------------------------------------------------

export type NodeAggregate = {
  nodeId: string;
  /** Runs in which the node appeared at least once. */
  runs: number;
  visits: number;
  /** Runs with at least one ERROR visit of this node. */
  failures: number;
  /** failures / runs, 0..1. */
  failRate: number;
  skips: number;
  p50Ms: number | null;
  p95Ms: number | null;
  avgMs: number | null;
  maxMs: number | null;
  /** avgMs relative to the slowest node's avgMs, 0..1 (0 when unknown). */
  heat: number;
};

export type EdgeAggregate = {
  /** Total traversals across all runs (loops count every pass). */
  traversals: number;
  /** Runs in which the edge was traversed at least once. */
  runs: number;
};

export type RunsAggregate = {
  runCount: number;
  nodes: Record<string, NodeAggregate>;
  edges: Record<string, EdgeAggregate>;
  maxAvgMs: number | null;
};

/** Nearest-rank percentile over an ascending array; null when empty. */
export function percentile(sortedAsc: readonly number[], p: number): number | null {
  if (!sortedAsc.length) return null;
  const rank = Math.ceil((Math.min(Math.max(p, 0), 100) / 100) * sortedAsc.length);
  return sortedAsc[Math.min(sortedAsc.length - 1, Math.max(0, rank - 1))];
}

export function aggregateRuns(models: readonly RunModel[], edges: readonly RunEdge[] = []): RunsAggregate {
  const durations: Record<string, number[]> = {};
  const nodes: Record<string, NodeAggregate> = {};
  const edgeAgg: Record<string, EdgeAggregate> = {};
  for (const e of edges) edgeAgg[e.id] = { traversals: 0, runs: 0 };

  for (const m of models) {
    for (const [nodeId, vs] of Object.entries(m.byNode)) {
      const a = (nodes[nodeId] ??= {
        nodeId, runs: 0, visits: 0, failures: 0, failRate: 0, skips: 0,
        p50Ms: null, p95Ms: null, avgMs: null, maxMs: null, heat: 0,
      });
      a.runs += 1;
      a.visits += vs.length;
      if (vs.some((v) => v.status === "error")) a.failures += 1;
      a.skips += vs.filter((v) => v.status === "skipped").length;
      for (const v of vs) if (v.durationMs != null) (durations[nodeId] ??= []).push(v.durationMs);
    }
    if (edges.length) {
      const counts: Record<string, number> = {};
      for (const l of linkVisits(m, edges)) counts[l.edgeId] = (counts[l.edgeId] ?? 0) + 1;
      for (const [id, c] of Object.entries(counts)) {
        edgeAgg[id].traversals += c;
        edgeAgg[id].runs += 1;
      }
    }
  }

  let maxAvgMs: number | null = null;
  for (const a of Object.values(nodes)) {
    a.failRate = a.runs ? a.failures / a.runs : 0;
    const ds = (durations[a.nodeId] ?? []).slice().sort((x, y) => x - y);
    if (ds.length) {
      a.p50Ms = percentile(ds, 50);
      a.p95Ms = percentile(ds, 95);
      a.avgMs = ds.reduce((s, d) => s + d, 0) / ds.length;
      a.maxMs = ds[ds.length - 1];
      maxAvgMs = Math.max(maxAvgMs ?? 0, a.avgMs);
    }
  }
  for (const a of Object.values(nodes)) {
    a.heat = a.avgMs != null && maxAvgMs ? a.avgMs / maxAvgMs : 0;
  }
  return { runCount: models.length, nodes, edges: edgeAgg, maxAvgMs };
}
