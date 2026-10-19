// Pure run model for the canvas "flight recorder": turns a WorkflowRunResponse
// (GET /api/workflows/{id}/runs/{runId}) into a time-indexed model that the
// replay scrubber, edge/node overlays, heat lens and run narrative all read.
// No React, no fetching, no Date.now() - every function here is deterministic
// so the same run always replays, narrates and tests the same way.
//
// Wire facts this leans on (core/workflow NodeTraceEntry + WorkflowRunStatus):
// - trace[] is appended in execution order; a node appears once PER VISIT
//   (loops, map fan-out, approval resume), so visits are never keyed by
//   nodeId alone.
// - node status: OK | ERROR | SKIPPED | CANCELLED | PAUSED | AWAITING_* and,
//   defensively, RUNNING / anything unknown.
// - a Condition node's summary ends "-> true" / "-> false"; that resolved
//   branch is also the sourceHandle of the edge it followed. "error" is every
//   node's failure port.
// - durationMs, inputJson/outputJson, *Truncated, tokensIn/Out, costUsd are
//   newer, nullable fields; null means UNKNOWN (never zero) and is kept null.
//
// Types live in runModelTypes.ts, the time-indexed views (linkVisits, frameAt,
// keyframes) in runTimeline.ts and the multi-visit analyses (criticalPath,
// aggregateRuns) in runAnalysis.ts; this module normalizes the wire run and
// re-exports all of them so every import path stays stable.

import type {
  AwaitReason,
  RawTraceEntry,
  RawWorkflowRun,
  RunModel,
  RunStatus,
  RunSummary,
  RunTotals,
  TraceVisit,
  VisitStatus,
  VisitTiming,
} from "./runModelTypes";

export * from "./runModelTypes";
export { ERROR_HANDLE, frameAt, handleOf, keyframes, linkVisits, toEdgeRunState } from "./runTimeline";
export * from "./runAnalysis";

// ---------------------------------------------------------------------------
// Small parsing helpers
// ---------------------------------------------------------------------------

function toMs(v: unknown): number | null {
  if (v == null || v === "") return null;
  if (typeof v === "number") return Number.isFinite(v) ? v : null;
  if (typeof v === "string") {
    const n = Date.parse(v);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

function toNum(v: unknown): number | null {
  if (v == null || v === "") return null;
  const n = typeof v === "number" ? v : typeof v === "string" ? Number(v) : NaN;
  return Number.isFinite(n) ? n : null;
}

function str(v: unknown): string {
  return typeof v === "string" ? v : v == null ? "" : String(v);
}

function parseVisitStatus(raw: string): { status: VisitStatus; awaiting: AwaitReason | null } {
  const s = raw.trim().toUpperCase();
  switch (s) {
    case "OK":
    case "SUCCESS":
    case "COMPLETED":
    case "PINNED": // a pin answered for the node: success-like, flagged separately via TraceVisit.pinned
      return { status: "ok", awaiting: null };
    case "ERROR":
    case "FAILED":
      return { status: "error", awaiting: null };
    case "SKIPPED":
      return { status: "skipped", awaiting: null };
    case "CANCELLED":
    case "CANCELED":
      return { status: "cancelled", awaiting: null };
    case "RUNNING":
    case "":
      return { status: "running", awaiting: null };
    case "PAUSED":
      return { status: "paused", awaiting: "operator" };
    case "AWAITING_APPROVAL":
      return { status: "paused", awaiting: "approval" };
    case "AWAITING_INPUT":
      return { status: "paused", awaiting: "input" };
    case "AWAITING_VOICE_MENU":
    case "AWAITING_DTMF":
      return { status: "paused", awaiting: "voice" };
    default:
      if (s.startsWith("AWAITING")) return { status: "paused", awaiting: "other" };
      // Unknown vocabulary from a newer core: treat as done rather than
      // leaving a node spinning forever in the replay.
      return { status: "ok", awaiting: null };
  }
}

export function parseRunStatus(raw: unknown): RunStatus {
  const s = str(raw).trim().toUpperCase();
  if (s === "RUNNING" || s === "QUEUED" || s === "PENDING") return "running";
  if (s === "COMPLETED" || s === "SUCCEEDED" || s === "SUCCESS") return "completed";
  if (s === "FAILED" || s === "ERROR") return "failed";
  if (s === "CANCELLED" || s === "CANCELED") return "cancelled";
  if (s === "PAUSED") return "paused";
  if (s.startsWith("AWAITING")) return "awaiting";
  return "unknown";
}

/** Run statuses that can still produce more trace. */
export function isLiveRunStatus(s: RunStatus): boolean {
  return s === "running" || s === "paused" || s === "awaiting";
}

// Condition's summary is "Condition '<needle>' -> true|false" (WorkflowNodeExecutor).
function parseBranch(nodeType: string, summary: string): string | null {
  if (nodeType !== "condition") return null;
  const m = /->\s*(true|false)\s*$/i.exec(summary);
  return m ? m[1].toLowerCase() : null;
}

function readTrace(raw: RawWorkflowRun["trace"]): RawTraceEntry[] {
  if (Array.isArray(raw)) return raw.filter((e): e is RawTraceEntry => !!e && typeof e === "object");
  if (typeof raw === "string" && raw.trim()) {
    try {
      const parsed: unknown = JSON.parse(raw);
      return Array.isArray(parsed)
        ? parsed.filter((e): e is RawTraceEntry => !!e && typeof e === "object")
        : [];
    } catch {
      return [];
    }
  }
  return [];
}

// ---------------------------------------------------------------------------
// normalizeRun
// ---------------------------------------------------------------------------

export type NormalizeOptions = {
  /**
   * "Now" for a live run, so the timeline extends to the present and running
   * visits get an elapsed time. Omit for a frozen snapshot (pure/replay use).
   */
  nowMs?: number;
};

export function normalizeRun(response: RawWorkflowRun | null | undefined, opts: NormalizeOptions = {}): RunModel {
  const r: RawWorkflowRun = response ?? {};
  const runStatus = parseRunStatus(r.status);
  const version = toNum(r.workflowVersion);
  const run: RunSummary = {
    id: str(r.id),
    workflowId: str(r.workflowId),
    workflowName: str(r.workflowName),
    status: runStatus,
    rawStatus: str(r.status),
    requestedBy: r.requestedBy ? str(r.requestedBy) : null,
    errorMessage: r.errorMessage ? str(r.errorMessage) : null,
    startMs: toMs(r.startedAt),
    endMs: toMs(r.completedAt),
    outputFileName: r.outputFileName ? str(r.outputFileName) : null,
    workflowVersion: version,
  };
  const live = isLiveRunStatus(runStatus);

  type Draft = Omit<TraceVisit, "key" | "seq" | "visitIndex" | "visitCount">;
  const drafts: Draft[] = [];
  // Placement cursor for entries with no timestamps: trace order IS execution
  // order, so an untimed entry sits right after whatever came before it.
  let cursor: number | null = run.startMs;

  readTrace(r.trace).forEach((e, traceIndex) => {
    const rawStatus = str(e.status);
    const { status, awaiting } = parseVisitStatus(rawStatus);
    const nodeId = str(e.nodeId) || `unknown-${traceIndex}`;
    const nodeType = str(e.nodeType);
    const summary = str(e.summary);
    let start = toMs(e.startedAt);
    let end = toMs(e.completedAt);
    const dur = toNum(e.durationMs);
    const knownDur = dur != null && dur >= 0 ? dur : null;
    const closed = status !== "running";
    let timing: VisitTiming = "exact";

    if (start == null && end != null && knownDur != null) {
      start = end - knownDur;
      timing = "derived";
    } else if (start != null && end == null && knownDur != null) {
      end = start + knownDur;
      timing = "derived";
    }
    if (start == null) {
      timing = "estimated";
      start = end != null ? (cursor != null && cursor <= end ? cursor : end) : (cursor ?? 0);
    }
    if (end == null && closed) {
      // Closed but no completion time: zero-width marker, duration unknown.
      end = start;
      if (timing === "exact") timing = "estimated";
    }
    if (end != null && end < start) end = start;

    const durationMs = knownDur ?? (end != null && timing !== "estimated" ? end - start : null);
    cursor = Math.max(cursor ?? start, end ?? start);

    drafts.push({
      traceIndex,
      nodeId,
      nodeType,
      label: str(e.label),
      status,
      rawStatus,
      awaiting,
      pinned: rawStatus.trim().toUpperCase() === "PINNED" || undefined,
      summary,
      logLines: Array.isArray(e.logLines) ? e.logLines.map(str) : [],
      startMs: start,
      endMs: end,
      durationMs,
      timing,
      branch: parseBranch(nodeType, summary),
      input: typeof e.inputJson === "string" ? e.inputJson : null,
      output: typeof e.outputJson === "string" ? e.outputJson : null,
      inputTruncated: e.inputTruncated === true,
      outputTruncated: e.outputTruncated === true,
      tokensIn: toNum(e.tokensIn),
      tokensOut: toNum(e.tokensOut),
      costUsd: toNum(e.costUsd),
    });
  });

  drafts.sort((a, b) => a.startMs - b.startMs || a.traceIndex - b.traceIndex);

  const counts: Record<string, number> = {};
  for (const d of drafts) counts[d.nodeId] = (counts[d.nodeId] ?? 0) + 1;
  const seen: Record<string, number> = {};
  const byNode: Record<string, TraceVisit[]> = {};
  const visits: TraceVisit[] = drafts.map((d, seq) => {
    const visitIndex = seen[d.nodeId] ?? 0;
    seen[d.nodeId] = visitIndex + 1;
    const v: TraceVisit = { ...d, key: `${d.nodeId}#${visitIndex}`, seq, visitIndex, visitCount: counts[d.nodeId] };
    (byNode[d.nodeId] ??= []).push(v);
    return v;
  });

  const starts = visits.map((v) => v.startMs);
  const ends = visits.flatMap((v) => (v.endMs != null ? [v.endMs] : []));
  const lowCandidates = [...starts, ...(run.startMs != null ? [run.startMs] : [])];
  const t0 = lowCandidates.length ? Math.min(...lowCandidates) : (run.endMs ?? 0);
  const highCandidates = [t0, ...starts, ...ends, ...(run.endMs != null ? [run.endMs] : [])];
  if (live && opts.nowMs != null && Number.isFinite(opts.nowMs)) highCandidates.push(opts.nowMs);
  const t1 = Math.max(...highCandidates);

  return { run, visits, byNode, t0, t1, totalMs: t1 - t0, live, totals: sumTotals(visits) };
}

function sumTotals(visits: TraceVisit[]): RunTotals {
  const sum = (pick: (v: TraceVisit) => number | null): number | null => {
    let total: number | null = null;
    for (const v of visits) {
      const n = pick(v);
      if (n != null) total = (total ?? 0) + n;
    }
    return total;
  };
  return { tokensIn: sum((v) => v.tokensIn), tokensOut: sum((v) => v.tokensOut), costUsd: sum((v) => v.costUsd) };
}
// ---------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------

/** "340ms", "8.2s", "42s", "3m 05s", "1h 02m"; "—" when unknown. */
export function formatDuration(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms)) return "—";
  const v = Math.max(0, ms);
  if (v < 1000) return `${Math.round(v)}ms`;
  if (v < 9950) return `${(v / 1000).toFixed(1)}s`;
  const totalSec = Math.round(v / 1000);
  if (totalSec < 60) return `${totalSec}s`;
  if (totalSec < 3600) {
    const m = Math.floor(totalSec / 60);
    return `${m}m ${String(totalSec % 60).padStart(2, "0")}s`;
  }
  const totalMin = Math.round(totalSec / 60);
  return `${Math.floor(totalMin / 60)}h ${String(totalMin % 60).padStart(2, "0")}m`;
}

/** USD via Intl (never a literal symbol); tiny LLM costs keep 2 significant digits. */
export function formatCostUsd(usd: number | null | undefined, locale?: string): string {
  if (usd == null || !Number.isFinite(usd)) return "—";
  const opts: Intl.NumberFormatOptions =
    usd !== 0 && Math.abs(usd) < 0.01
      ? { style: "currency", currency: "USD", maximumSignificantDigits: 2 }
      : { style: "currency", currency: "USD", minimumFractionDigits: 2, maximumFractionDigits: 2 };
  return new Intl.NumberFormat(locale, opts).format(usd);
}

// Narration lives in runNarration.ts; re-exported to keep every import path stable.
export * from "./runNarration";
