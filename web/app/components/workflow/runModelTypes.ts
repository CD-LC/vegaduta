// Wire and model types of the run "flight recorder" (see runModel.ts for the
// wire facts they lean on). Re-exported through ./runModel.

// ---------------------------------------------------------------------------
// Wire shapes (read defensively - every field may be missing on old runs)
// ---------------------------------------------------------------------------

export type RawTraceEntry = {
  nodeId?: string | null;
  nodeType?: string | null;
  label?: string | null;
  status?: string | null;
  summary?: string | null;
  startedAt?: string | number | null;
  completedAt?: string | number | null;
  logLines?: unknown;
  durationMs?: number | string | null;
  inputJson?: string | null;
  outputJson?: string | null;
  inputTruncated?: boolean | null;
  outputTruncated?: boolean | null;
  tokensIn?: number | string | null;
  tokensOut?: number | string | null;
  costUsd?: number | string | null;
};

export type RawWorkflowRun = {
  id?: string | number | null;
  workflowId?: string | number | null;
  workflowName?: string | null;
  status?: string | null;
  requestedBy?: string | null;
  errorMessage?: string | null;
  // Normally an array; tolerated as a JSON string (traceJson) too.
  trace?: RawTraceEntry[] | string | null;
  startedAt?: string | number | null;
  completedAt?: string | number | null;
  outputFileName?: string | null;
  workflowVersion?: number | string | null;
};

/** Minimal edge shape the model needs; React Flow edges satisfy it as-is. */
export type RunEdge = {
  id: string;
  source: string;
  target: string;
  sourceHandle?: string | null;
};

// ---------------------------------------------------------------------------
// Model types
// ---------------------------------------------------------------------------

export type RunStatus = "running" | "completed" | "failed" | "cancelled" | "paused" | "awaiting" | "unknown";

export type VisitStatus = "ok" | "error" | "skipped" | "cancelled" | "paused" | "running";

/** Why a paused visit is waiting - lets the UI say "awaiting approval" vs "operator paused". */
export type AwaitReason = "approval" | "input" | "voice" | "operator" | "other";

/**
 * How trustworthy a visit's placement on the timeline is: `exact` = both
 * timestamps; `derived` = one timestamp plus durationMs; `estimated` = placed
 * by trace order because timestamps were missing (draw it hatched, not solid).
 */
export type VisitTiming = "exact" | "derived" | "estimated";

export type RunSummary = {
  id: string;
  workflowId: string;
  workflowName: string;
  status: RunStatus;
  rawStatus: string;
  requestedBy: string | null;
  errorMessage: string | null;
  startMs: number | null;
  endMs: number | null;
  outputFileName: string | null;
  workflowVersion: number | null;
};

export type TraceVisit = {
  /** Stable key: `${nodeId}#${visitIndex}`. */
  key: string;
  /** Position in the start-sorted visit list. */
  seq: number;
  /** Position in the raw trace array (execution-append order). */
  traceIndex: number;
  nodeId: string;
  nodeType: string;
  label: string;
  status: VisitStatus;
  rawStatus: string;
  awaiting: AwaitReason | null;
  /** Engine returned a pinned output instead of running the node (wire status PINNED; `status` is "ok"). */
  pinned?: boolean;
  summary: string;
  logLines: string[];
  /** 0-based index of this visit among the node's visits, chronological. */
  visitIndex: number;
  /** Total visits of this node in the run. */
  visitCount: number;
  startMs: number;
  /** null while the visit is still open (running). */
  endMs: number | null;
  /** null when unknown - never guessed as 0. */
  durationMs: number | null;
  timing: VisitTiming;
  /** Resolved branch when the trace states it (Condition "-> true"). */
  branch: string | null;
  input: string | null;
  output: string | null;
  inputTruncated: boolean;
  outputTruncated: boolean;
  tokensIn: number | null;
  tokensOut: number | null;
  costUsd: number | null;
};

export type RunTotals = {
  tokensIn: number | null;
  tokensOut: number | null;
  costUsd: number | null;
};

export type RunModel = {
  run: RunSummary;
  visits: TraceVisit[];
  byNode: Record<string, TraceVisit[]>;
  /** Absolute epoch ms of timeline position 0. */
  t0: number;
  /** Absolute epoch ms of the timeline's end. */
  t1: number;
  totalMs: number;
  /** True while the run can still grow (RUNNING / paused / awaiting). */
  live: boolean;
  totals: RunTotals;
};

export type NodeFrameStatus = "pending" | "running" | "ok" | "error" | "skipped" | "paused";

export type NodeFrame = {
  status: NodeFrameStatus;
  /** Visits started at or before the frame time, chronological. */
  visits: TraceVisit[];
  /** The running visit if any, else the most recent one; null while pending. */
  activeVisit: TraceVisit | null;
  /** Time the active visit has spent so far at this frame; null when pending. */
  elapsedMs: number | null;
  errorCount: number;
  /** The active visit was CANCELLED (rendered as skipped, but worth saying). */
  cancelled: boolean;
};

export type EdgeFrameState = "pending" | "taken" | "flowing" | "failed" | "skipped" | "awaiting-approval";

export type EdgeFrame = {
  state: EdgeFrameState;
  /** Times this edge has been traversed up to the frame (loops count each pass). */
  traversals: number;
};

export type RunFrame = {
  /** Frame position relative to t0, clamped to [0, totalMs]. */
  tMs: number;
  nodes: Record<string, NodeFrame>;
  edges: Record<string, EdgeFrame>;
};

export type VisitLink = { edgeId: string; from: TraceVisit; to: TraceVisit };
