// Pure model for WorkflowHealthStrip: turns GET /workflows/{id}/eval-summary
// (core WorkflowInsightsService) into what the strip draws. No React, no DOM.
//
// Honesty rules this file owns:
//   - nothing is recomputed from a run list; every rate/median/mean is the
//     server's own number, passed through. A null stays null (rendered "-").
//   - `sampled` is the server saying the window held more runs than it
//     scanned; the strip must say so, and `runs` is then the scanned count.
//   - a node only ranks as "slow" if it has a median, and as "failing" only
//     if its error rate is above zero. A node with no data is not ranked last,
//     it is left out.
import type { WorkflowEvalNodeStats, WorkflowEvalSummary } from "../../lib/api/workflows";
import type { ChartState } from "../charts/chartMath";

export const HEALTH_PERIODS = [7, 30, 90] as const;
export type HealthPeriod = (typeof HEALTH_PERIODS)[number];

/** The run statuses core can report (WorkflowRunStatus); each has a `healthStatus_*` label. */
export const KNOWN_RUN_STATUSES = [
  "RUNNING",
  "COMPLETED",
  "FAILED",
  "AWAITING_APPROVAL",
  "CANCELLED",
  "PAUSED",
  "AWAITING_INPUT",
  "AWAITING_VOICE_INPUT",
] as const;

export type HealthNodeRow = {
  id: string;
  label: string;
  runs: number;
  visits: number;
  successRate: number | null;
  errorRate: number | null;
  p50DurationMs: number | null;
  avgCostUsd: number | null;
};

export type WorkflowHealthModel = {
  runs: number;
  /** The window the server actually used (it clamps to 1..90). */
  days: number;
  sampled: boolean;
  successRate: number | null;
  p50DurationMs: number | null;
  avgCostUsd: number | null;
  lastRunAt: string | null;
  lastRunStatus: string | null;
  /** Every node that was visited at least once in the window. */
  nodes: HealthNodeRow[];
  slowest: HealthNodeRow[];
  mostFailing: HealthNodeRow[];
};

function finite(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function count(value: unknown): number {
  const n = finite(value);
  return n !== null && n > 0 ? Math.floor(n) : 0;
}

/** A 0..1 rate, or null. Anything outside 0..1 is not a rate we understand, so it is null rather than clamped. */
function unitRate(value: unknown): number | null {
  const n = finite(value);
  return n !== null && n >= 0 && n <= 1 ? n : null;
}

function nonNegative(value: unknown): number | null {
  const n = finite(value);
  return n !== null && n >= 0 ? n : null;
}

/** Node id -> display name from a workflow definition's graph ({nodes:[{id, data:{label}}]}). */
export function nodeLabelsFromGraph(graph: unknown): Record<string, string> {
  const labels: Record<string, string> = {};
  const nodes = (graph as { nodes?: unknown } | null | undefined)?.nodes;
  if (!Array.isArray(nodes)) return labels;
  for (const node of nodes) {
    const id = (node as { id?: unknown } | null)?.id;
    const label = (node as { data?: { label?: unknown } } | null)?.data?.label;
    if (typeof id === "string" && typeof label === "string" && label.trim()) labels[id] = label.trim();
  }
  return labels;
}

export function buildWorkflowHealth(
  summary: WorkflowEvalSummary | null | undefined,
  nodeLabels: Record<string, string> = {},
  requestedDays: number = 30,
  top = 3
): WorkflowHealthModel {
  const wf = summary?.workflow ?? null;
  const rawNodes = summary?.nodes && typeof summary.nodes === "object" ? summary.nodes : {};

  const nodes: HealthNodeRow[] = [];
  for (const [id, raw] of Object.entries(rawNodes as Record<string, WorkflowEvalNodeStats | null>)) {
    if (!raw || typeof raw !== "object") continue;
    const runs = count(raw.runs);
    // Older cores sent no `visits`; a node seen in N runs was visited at least N times.
    const visits = Math.max(count(raw.visits), runs);
    if (visits <= 0) continue; // e.g. an agent node that only carries an eval score
    nodes.push({
      id,
      label: nodeLabels[id] || id,
      runs,
      visits,
      successRate: unitRate(raw.successRate),
      errorRate: unitRate(raw.errorRate),
      p50DurationMs: nonNegative(raw.p50DurationMs),
      avgCostUsd: nonNegative(raw.avgCostUsd),
    });
  }

  const slowest = nodes
    .filter((n) => n.p50DurationMs !== null && n.p50DurationMs > 0)
    .sort((a, b) => (b.p50DurationMs as number) - (a.p50DurationMs as number))
    .slice(0, top);
  const mostFailing = nodes
    .filter((n) => n.errorRate !== null && n.errorRate > 0)
    .sort((a, b) => (b.errorRate as number) - (a.errorRate as number) || b.visits - a.visits)
    .slice(0, top);

  const serverDays = count(wf?.days);
  return {
    runs: count(wf?.runs),
    days: serverDays > 0 ? serverDays : requestedDays,
    // Core sends a boolean; the first client type said number, so accept a truthy number too.
    sampled: wf?.sampled === true || (typeof wf?.sampled === "number" && wf.sampled > 0),
    successRate: unitRate(wf?.successRate),
    p50DurationMs: nonNegative(wf?.p50DurationMs),
    avgCostUsd: nonNegative(wf?.avgCostUsd),
    lastRunAt: typeof wf?.lastRunAt === "string" && wf.lastRunAt ? wf.lastRunAt : null,
    lastRunStatus: typeof wf?.lastRunStatus === "string" && wf.lastRunStatus ? wf.lastRunStatus : null,
    nodes,
    slowest,
    mostFailing,
  };
}

/** Ring colour + the word that goes with it. Null rate is "none": no colour, no verdict. */
export function healthBand(rate: number | null): { state: ChartState; key: "healthy" | "attention" | "failing" | "none" } {
  if (rate === null) return { state: "idle", key: "none" };
  if (rate >= 0.95) return { state: "success", key: "healthy" };
  if (rate >= 0.8) return { state: "warning", key: "attention" };
  return { state: "error", key: "failing" };
}

/** Maps a run status onto the chart-kit state colours. */
export function runStatusState(status: string | null | undefined): ChartState {
  switch ((status || "").toUpperCase()) {
    case "COMPLETED":
      return "success";
    case "FAILED":
      return "error";
    case "RUNNING":
      return "running";
    case "AWAITING_APPROVAL":
    case "AWAITING_INPUT":
    case "AWAITING_VOICE_INPUT":
    case "PAUSED":
      return "warning";
    default:
      return "idle";
  }
}

const RELATIVE_STEPS: Array<[Intl.RelativeTimeFormatUnit, number]> = [
  ["year", 365 * 24 * 3600],
  ["month", 30 * 24 * 3600],
  ["day", 24 * 3600],
  ["hour", 3600],
  ["minute", 60],
];

/**
 * "3 hours ago" for `iso` measured against `nowMs`. Null when either side is
 * not a real time. A time slightly in the future (clock skew) reads as "now".
 */
export function relativeTime(iso: string | null | undefined, nowMs: number | null | undefined, locale?: string): string | null {
  if (!iso || typeof nowMs !== "number" || !Number.isFinite(nowMs)) return null;
  const then = Date.parse(iso);
  if (!Number.isFinite(then)) return null;
  const seconds = Math.max(0, Math.round((nowMs - then) / 1000));
  let format: Intl.RelativeTimeFormat;
  try {
    format = new Intl.RelativeTimeFormat(locale, { numeric: "auto" });
  } catch {
    format = new Intl.RelativeTimeFormat("en", { numeric: "auto" });
  }
  for (const [unit, size] of RELATIVE_STEPS) {
    if (seconds >= size) return format.format(-Math.floor(seconds / size), unit);
  }
  return format.format(seconds < 10 ? 0 : -seconds, "second");
}
