/* Canvas lenses: pure mapping from run statistics to per-node / per-edge
 * visual state. A lens never changes the graph, it only recolours the SAME
 * canvas to answer one question ("where is time spent?", "what breaks?",
 * "what made this run slow?", "what never runs?").
 *
 * Nothing here touches the DOM or React, so the integrator can compute
 * styles inside a node renderer or a useMemo and unit tests stay trivial.
 *
 * NOTE for integrator: runModel.ts did not exist when this was written, so
 * the minimal run/trace shapes below are declared locally and structurally
 * match WorkflowRunResponse. Once runModel.ts lands, swap LensRun /
 * LensTraceEntry for its types (they are assignable as-is). */

export type CanvasLens = "none" | "heat" | "failures" | "critical" | "coverage";

export const CANVAS_LENSES: readonly CanvasLens[] = ["none", "heat", "failures", "critical", "coverage"];

export type RunsWindow = 5 | 20 | 50;
export const RUNS_WINDOWS: readonly RunsWindow[] = [5, 20, 50];

/* ---------- input shapes (defensive: every field optional but nodeId) ---------- */

export interface LensTraceEntry {
  nodeId: string;
  label?: string | null;
  status?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  /** Future core field; preferred over timestamp arithmetic when present. */
  durationMs?: number | null;
}

export interface LensRun {
  id: string;
  status?: string | null;
  startedAt?: string | null;
  trace?: LensTraceEntry[] | null;
}

export interface LensEdgeRef {
  id?: string;
  source: string;
  target: string;
}

/* ---------- aggregated stats ---------- */

export interface LensNodeStats {
  /** Runs (in the window) in which the node actually executed at least once. */
  runsExecuted: number;
  /** Runs in which at least one visit of this node ended in ERROR. */
  runsFailed: number;
  /** Every executed visit, loops and fan-out included. */
  visits: number;
  /** Mean duration across visits that reported one; null when none did. */
  avgDurationMs: number | null;
  label?: string;
}

export interface LensStats {
  /** How many runs the window actually holds (may be < the requested N). */
  windowRuns: number;
  nodes: Record<string, LensNodeStats>;
  /** Runs in which an edge was plausibly traversed, keyed by edgeKey(). */
  edgeTraversals: Record<string, number>;
  maxAvgDurationMs: number;
  /** Critical path of the selected run, in execution order. */
  criticalNodeIds: string[];
  /** edgeKey()s along the critical path. */
  criticalEdgeKeys: string[];
  criticalDurationMs: number | null;
}

export const EMPTY_LENS_STATS: LensStats = {
  windowRuns: 0,
  nodes: {},
  edgeTraversals: {},
  maxAvgDurationMs: 0,
  criticalNodeIds: [],
  criticalEdgeKeys: [],
  criticalDurationMs: null,
};

export function edgeKey(source: string, target: string): string {
  return `${source}->${target}`;
}

/* ---------- helpers ---------- */

function norm(status: string | null | undefined): string {
  return (status ?? "").toUpperCase();
}

/** A visit counts as "executed" unless core explicitly skipped it. Unknown
 * statuses count as executed so a new status never ghosts a live node. */
export function visitExecuted(e: LensTraceEntry): boolean {
  return norm(e.status) !== "SKIPPED";
}

function ts(v: string | null | undefined): number | null {
  if (!v) return null;
  const n = Date.parse(v);
  return Number.isFinite(n) ? n : null;
}

export function visitDurationMs(e: LensTraceEntry): number | null {
  if (typeof e.durationMs === "number" && Number.isFinite(e.durationMs) && e.durationMs >= 0) {
    return e.durationMs;
  }
  const a = ts(e.startedAt);
  const b = ts(e.completedAt);
  if (a === null || b === null || b < a) return null;
  return b - a;
}

function clamp01(n: number): number {
  if (!Number.isFinite(n)) return 0;
  return n < 0 ? 0 : n > 1 ? 1 : n;
}

/** Most recent first; runs without a timestamp sink to the end but keep
 * their relative order (the list endpoint already returns newest first). */
export function latestRuns<R extends LensRun>(runs: readonly R[], n: number): R[] {
  const indexed = runs.map((r, i) => ({ r, i, t: ts(r.startedAt) }));
  indexed.sort((x, y) => {
    if (x.t !== null && y.t !== null && x.t !== y.t) return y.t - x.t;
    if (x.t === null && y.t !== null) return 1;
    if (y.t === null && x.t !== null) return -1;
    return x.i - y.i;
  });
  return indexed.slice(0, Math.max(0, n)).map((x) => x.r);
}

/* ---------- critical path ---------- */

/** Walks backwards from the visit that finished last, each step hopping to
 * the predecessor visit (via a real canvas edge) that completed latest
 * before the current one started. That chain is what actually gated the
 * run's end time. Visits are kept by index, so loops and fan-out work: a
 * node visited three times can appear on the path more than once. */
export function criticalPath(
  run: LensRun | null | undefined,
  edges: readonly LensEdgeRef[],
): { nodeIds: string[]; edgeKeys: string[]; durationMs: number | null } {
  const trace = (run?.trace ?? []).filter(visitExecuted);
  if (trace.length === 0) return { nodeIds: [], edgeKeys: [], durationMs: null };

  const preds = new Map<string, Set<string>>();
  for (const e of edges) {
    const set = preds.get(e.target) ?? new Set<string>();
    set.add(e.source);
    preds.set(e.target, set);
  }

  const end = (e: LensTraceEntry, i: number): number => ts(e.completedAt) ?? ts(e.startedAt) ?? i;
  const start = (e: LensTraceEntry, i: number): number => ts(e.startedAt) ?? end(e, i);

  let cur = 0;
  for (let i = 1; i < trace.length; i++) {
    if (end(trace[i], i) >= end(trace[cur], cur)) cur = i;
  }

  const pathIdx: number[] = [cur];
  const seen = new Set<number>([cur]);
  // Bounded by trace length, and `seen` stops cycles when timestamps are missing.
  for (let guard = 0; guard < trace.length; guard++) {
    const node = trace[cur];
    const from = preds.get(node.nodeId);
    if (!from || from.size === 0) break;
    const curStart = start(node, cur);
    let best = -1;
    for (let i = 0; i < trace.length; i++) {
      if (seen.has(i) || !from.has(trace[i].nodeId)) continue;
      // Without timestamps, trace order is the only causality signal we have.
      const before = ts(trace[i].completedAt) !== null && ts(node.startedAt) !== null
        ? end(trace[i], i) <= curStart
        : i < cur;
      if (!before) continue;
      if (best === -1 || end(trace[i], i) >= end(trace[best], best)) best = i;
    }
    if (best === -1) break;
    pathIdx.push(best);
    seen.add(best);
    cur = best;
  }
  pathIdx.reverse();

  const nodeIds = pathIdx.map((i) => trace[i].nodeId);
  const edgeKeys: string[] = [];
  for (let i = 1; i < nodeIds.length; i++) edgeKeys.push(edgeKey(nodeIds[i - 1], nodeIds[i]));

  const first = trace[pathIdx[0]];
  const last = trace[pathIdx[pathIdx.length - 1]];
  const a = ts(first.startedAt);
  const b = ts(last.completedAt);
  let durationMs: number | null = a !== null && b !== null && b >= a ? b - a : null;
  if (durationMs === null) {
    const parts = pathIdx.map((i) => visitDurationMs(trace[i]));
    durationMs = parts.every((p) => p !== null) ? parts.reduce<number>((s, p) => s + (p ?? 0), 0) : null;
  }
  return { nodeIds, edgeKeys, durationMs };
}

/* ---------- aggregation ---------- */

/** `runs` should already be the window (see latestRuns) and carry traces. */
export function buildLensStats(
  runs: readonly LensRun[],
  edges: readonly LensEdgeRef[],
  selectedRun?: LensRun | null,
): LensStats {
  const nodes: Record<string, LensNodeStats> = {};
  const durSum: Record<string, number> = {};
  const durCount: Record<string, number> = {};
  const edgeTraversals: Record<string, number> = {};

  for (const run of runs) {
    const trace = (run.trace ?? []).filter(visitExecuted);
    const ranHere = new Set<string>();
    const failedHere = new Set<string>();
    const firstStart = new Map<string, number>();

    trace.forEach((e, i) => {
      const s = nodes[e.nodeId] ?? (nodes[e.nodeId] = { runsExecuted: 0, runsFailed: 0, visits: 0, avgDurationMs: null });
      s.visits += 1;
      if (e.label && !s.label) s.label = e.label;
      ranHere.add(e.nodeId);
      if (norm(e.status) === "ERROR") failedHere.add(e.nodeId);
      const d = visitDurationMs(e);
      if (d !== null) {
        durSum[e.nodeId] = (durSum[e.nodeId] ?? 0) + d;
        durCount[e.nodeId] = (durCount[e.nodeId] ?? 0) + 1;
      }
      if (!firstStart.has(e.nodeId)) firstStart.set(e.nodeId, ts(e.startedAt) ?? i);
    });

    ranHere.forEach((id) => { nodes[id].runsExecuted += 1; });
    failedHere.forEach((id) => { nodes[id].runsFailed += 1; });

    // Trace has no edge ids; an edge counts as taken when both ends ran and
    // the target started no earlier than the source.
    for (const e of edges) {
      const a = firstStart.get(e.source);
      const b = firstStart.get(e.target);
      if (a !== undefined && b !== undefined && b >= a) {
        const k = edgeKey(e.source, e.target);
        edgeTraversals[k] = (edgeTraversals[k] ?? 0) + 1;
      }
    }
  }

  let maxAvgDurationMs = 0;
  for (const id of Object.keys(nodes)) {
    const c = durCount[id] ?? 0;
    const avg = c > 0 ? durSum[id] / c : null;
    nodes[id].avgDurationMs = avg;
    if (avg !== null && avg > maxAvgDurationMs) maxAvgDurationMs = avg;
  }

  const cp = criticalPath(selectedRun ?? runs[0] ?? null, edges);
  return {
    windowRuns: runs.length,
    nodes,
    edgeTraversals,
    maxAvgDurationMs,
    criticalNodeIds: cp.nodeIds,
    criticalEdgeKeys: cp.edgeKeys,
    criticalDurationMs: cp.durationMs,
  };
}

/* ---------- formatting (Intl only, locale-aware) ---------- */

export function formatDurationMs(ms: number, locale?: string): string {
  if (ms < 1000) {
    return new Intl.NumberFormat(locale, { style: "unit", unit: "millisecond", unitDisplay: "narrow", maximumFractionDigits: 0 }).format(ms);
  }
  if (ms < 60_000) {
    return new Intl.NumberFormat(locale, { style: "unit", unit: "second", unitDisplay: "narrow", maximumFractionDigits: 1 }).format(ms / 1000);
  }
  return new Intl.NumberFormat(locale, { style: "unit", unit: "minute", unitDisplay: "narrow", maximumFractionDigits: 1 }).format(ms / 60_000);
}

export function formatPercent(ratio: number, locale?: string): string {
  return new Intl.NumberFormat(locale, { style: "percent", maximumFractionDigits: 0 }).format(clamp01(ratio));
}

/* ---------- per-element styles ---------- */

export interface LensElementStyle {
  /** Space-separated classes from canvas-lens.css; "" for no lens. */
  className: string;
  /** Short text chip so state is never colour-only. */
  badge?: string;
  /** Accessible sentence for aria-label / title. */
  description?: string;
  dim: boolean;
  /** 0..1, feed into the --wfx-lens-i custom property (see lensCssVars). */
  intensity: number;
}

const NEUTRAL: LensElementStyle = { className: "", dim: false, intensity: 0 };

/** Duration heat is log-scaled: agent runs are dominated by one or two LLM
 * calls, and a linear ramp would paint every other node identically cold. */
export function heatIntensity(avgMs: number | null, maxMs: number): number {
  if (avgMs === null || maxMs <= 0) return 0;
  return clamp01(Math.log1p(avgMs) / Math.log1p(maxMs));
}

/** Discrete band so text/badges can say more than a colour does. */
export function intensityBand(i: number): "low" | "mid" | "high" {
  return i >= 0.8 ? "high" : i >= 0.45 ? "mid" : "low";
}

export function lensNodeStyle(lens: CanvasLens, nodeId: string, stats: LensStats, locale?: string): LensElementStyle {
  if (lens === "none") return NEUTRAL;
  if (stats.windowRuns === 0) return { className: "wfx-lens-nodata", dim: false, intensity: 0 };
  const s = stats.nodes[nodeId];

  switch (lens) {
    case "heat": {
      if (!s || s.avgDurationMs === null) {
        return { className: "wfx-lens-heat wfx-lens-dim", dim: true, intensity: 0, description: "No timing data" };
      }
      const i = heatIntensity(s.avgDurationMs, stats.maxAvgDurationMs);
      const avg = formatDurationMs(s.avgDurationMs, locale);
      return {
        className: `wfx-lens-heat wfx-lens-band-${intensityBand(i)}`,
        badge: avg,
        description: `Average ${avg} over ${s.visits} ${s.visits === 1 ? "visit" : "visits"}`,
        dim: false,
        intensity: i,
      };
    }
    case "failures": {
      if (!s || s.runsExecuted === 0) {
        return { className: "wfx-lens-fail wfx-lens-dim", dim: true, intensity: 0, description: "Not executed" };
      }
      const rate = s.runsFailed / s.runsExecuted;
      if (s.runsFailed === 0) {
        return { className: "wfx-lens-fail wfx-lens-fail-clean", badge: "0", dim: true, intensity: 0, description: `No failures in ${s.runsExecuted} runs` };
      }
      return {
        className: `wfx-lens-fail wfx-lens-band-${intensityBand(rate)}`,
        badge: `${s.runsFailed}/${s.runsExecuted}`,
        description: `Failed in ${s.runsFailed} of ${s.runsExecuted} runs (${formatPercent(rate, locale)})`,
        dim: false,
        intensity: clamp01(rate),
      };
    }
    case "critical": {
      const idx = stats.criticalNodeIds.indexOf(nodeId);
      if (stats.criticalNodeIds.length === 0) return { className: "wfx-lens-nodata", dim: false, intensity: 0 };
      if (idx === -1) return { className: "wfx-lens-dim", dim: true, intensity: 0, description: "Off the critical path" };
      const avg = s?.avgDurationMs ?? null;
      return {
        className: "wfx-lens-critical",
        badge: `${idx + 1}`,
        description: `Critical path step ${idx + 1} of ${stats.criticalNodeIds.length}`,
        dim: false,
        // Brighter glow for heavier steps, but never invisible.
        intensity: Math.max(0.35, heatIntensity(avg, stats.maxAvgDurationMs)),
      };
    }
    case "coverage": {
      if (s && s.runsExecuted > 0) {
        const ratio = s.runsExecuted / stats.windowRuns;
        return {
          className: "wfx-lens-covered",
          badge: `${s.runsExecuted}/${stats.windowRuns}`,
          description: `Ran in ${s.runsExecuted} of the last ${stats.windowRuns} runs`,
          dim: false,
          intensity: clamp01(ratio),
        };
      }
      return {
        className: "wfx-lens-ghost",
        badge: "never ran",
        description: `Never executed in the last ${stats.windowRuns} runs - possible dead branch`,
        dim: true,
        intensity: 0,
      };
    }
  }
}

export function lensEdgeStyle(lens: CanvasLens, edge: LensEdgeRef, stats: LensStats, locale?: string): LensElementStyle {
  if (lens === "none" || stats.windowRuns === 0) return NEUTRAL;
  const k = edgeKey(edge.source, edge.target);

  switch (lens) {
    case "heat": {
      // The edge carries the heat of the node it feeds: "latency flows in here".
      const target = lensNodeStyle("heat", edge.target, stats, locale);
      return { className: target.dim ? "wfx-lens-edge wfx-lens-dim" : "wfx-lens-edge wfx-lens-heat", dim: target.dim, intensity: target.intensity };
    }
    case "failures": {
      const target = lensNodeStyle("failures", edge.target, stats, locale);
      return { className: target.dim ? "wfx-lens-edge wfx-lens-dim" : "wfx-lens-edge wfx-lens-fail", dim: target.dim, intensity: target.intensity };
    }
    case "critical": {
      if (stats.criticalEdgeKeys.length === 0) return NEUTRAL;
      return stats.criticalEdgeKeys.includes(k)
        ? { className: "wfx-lens-edge wfx-lens-critical", dim: false, intensity: 1, description: "On the critical path" }
        : { className: "wfx-lens-edge wfx-lens-dim", dim: true, intensity: 0 };
    }
    case "coverage": {
      const n = stats.edgeTraversals[k] ?? 0;
      return n > 0
        ? { className: "wfx-lens-edge wfx-lens-covered", dim: false, intensity: clamp01(n / stats.windowRuns), description: `Taken in ${n} of ${stats.windowRuns} runs` }
        : { className: "wfx-lens-edge wfx-lens-ghost", dim: true, intensity: 0, description: "Never taken" };
    }
  }
}

/** Inline style for the element carrying the lens class. */
export function lensCssVars(style: LensElementStyle): Record<string, string> {
  return { "--wfx-lens-i": style.intensity.toFixed(3) };
}

/* ---------- legend ---------- */

export interface LensLegendStop {
  label: string;
  /** Class from canvas-lens.css that paints the swatch. */
  swatchClass: string;
}

export interface LensLegend {
  lens: CanvasLens;
  title: string;
  question: string;
  kind: "none" | "ramp" | "swatches";
  stops: LensLegendStop[];
  /** One computed insight, e.g. the slowest node. Absent without data. */
  headline?: string;
}

function topNode(stats: LensStats, score: (s: LensNodeStats) => number | null): [string, LensNodeStats] | null {
  let best: [string, LensNodeStats] | null = null;
  let bestScore = -Infinity;
  for (const [id, s] of Object.entries(stats.nodes)) {
    const v = score(s);
    if (v !== null && v > bestScore) { bestScore = v; best = [id, s]; }
  }
  return best;
}

/** `nodeIds` (all canvas nodes) lets the coverage headline count nodes that
 * never appear in any trace at all; without it only traced nodes are known. */
export function legend(lens: CanvasLens, stats?: LensStats, opts?: { nodeIds?: readonly string[]; locale?: string }): LensLegend {
  const locale = opts?.locale;
  const has = !!stats && stats.windowRuns > 0;
  switch (lens) {
    case "none":
      return { lens, title: "No lens", question: "The canvas as designed", kind: "none", stops: [] };
    case "heat": {
      const top = has ? topNode(stats, (s) => s.avgDurationMs) : null;
      return {
        lens, title: "Heat", question: "Where does the time go?", kind: "ramp",
        stops: [
          { label: "fast", swatchClass: "wfx-lens-swatch-cool" },
          { label: "slow", swatchClass: "wfx-lens-swatch-hot" },
        ],
        headline: top && top[1].avgDurationMs !== null
          ? `Slowest: ${top[1].label ?? top[0]} · ${formatDurationMs(top[1].avgDurationMs, locale)} avg`
          : undefined,
      };
    }
    case "failures": {
      const top = has ? topNode(stats, (s) => (s.runsExecuted > 0 && s.runsFailed > 0 ? s.runsFailed / s.runsExecuted : null)) : null;
      return {
        lens, title: "Failures", question: "What breaks, and how often?", kind: "ramp",
        stops: [
          { label: "never", swatchClass: "wfx-lens-swatch-ok" },
          { label: "always", swatchClass: "wfx-lens-swatch-err" },
        ],
        headline: has
          ? top ? `Most fragile: ${top[1].label ?? top[0]} · ${formatPercent(top[1].runsFailed / top[1].runsExecuted, locale)}` : "No node failed in this window"
          : undefined,
      };
    }
    case "critical":
      return {
        lens, title: "Critical path", question: "What made this run take as long as it did?", kind: "swatches",
        stops: [
          { label: "on path", swatchClass: "wfx-lens-swatch-critical" },
          { label: "off path", swatchClass: "wfx-lens-swatch-dim" },
        ],
        headline: stats && stats.criticalNodeIds.length > 0
          ? `${stats.criticalNodeIds.length} ${stats.criticalNodeIds.length === 1 ? "step" : "steps"}${stats.criticalDurationMs !== null ? ` · ${formatDurationMs(stats.criticalDurationMs, locale)}` : ""}`
          : undefined,
      };
    case "coverage": {
      let headline: string | undefined;
      if (has) {
        const ids = opts?.nodeIds ?? Object.keys(stats.nodes);
        const dead = ids.filter((id) => !(stats.nodes[id]?.runsExecuted > 0)).length;
        headline = dead === 0 ? "Every node ran at least once" : `${dead} never-run ${dead === 1 ? "node" : "nodes"} in ${stats.windowRuns} runs`;
      }
      return {
        lens, title: "Coverage", question: "Which branches never run?", kind: "swatches",
        stops: [
          { label: "ran", swatchClass: "wfx-lens-swatch-covered" },
          { label: "never ran", swatchClass: "wfx-lens-swatch-ghost" },
        ],
        headline,
      };
    }
  }
}
