// Pure reducer of the live run stream: folds run events into what the canvas
// draws (streamed text inside a node, tool calls as child rows, the approval
// pause, running cost). Re-exported through ./liveRunStream.
import { isLiveRunStatus, parseRunStatus, type RawTraceEntry, type RawWorkflowRun } from "./runModel";
import { isRecord, numOrNull, parseTs, strOrNull, type LiveRunEvent } from "./liveRunEvents";

// ---------------------------------------------------------------------------
// Reducer
// ---------------------------------------------------------------------------

export const LIVE_TEXT_CAP = 8000;
const MAX_TOOL_CALLS = 100;
const MAX_AGENT_EVENTS = 50;

export type LiveToolCallStatus = "running" | "ok" | "error";

export type LiveToolCall = {
  id: string;
  tool: string;
  args: string | null;
  preview: string | null;
  error: string | null;
  ms: number | null;
  status: LiveToolCallStatus;
  startedAt: number | null;
};

export type LiveAgentEvent = { kind: string; ts: number | null; data: Record<string, unknown> };

export type LiveNodeBuffer = {
  nodeId: string;
  nodeType: string | null;
  label: string | null;
  attempt: number;
  startedAt: number | null;
  completedAt: number | null;
  /** "running" until node.completed; then the entry's own status is in `entryStatus`. */
  phase: "running" | "done";
  entryStatus: string | null;
  /** Streamed output, tail-capped at LIVE_TEXT_CAP. */
  text: string;
  /** True once the head was dropped to honour the cap. */
  textTruncated: boolean;
  /** The server stopped streaming tokens for this node (its own cap). */
  capped: boolean;
  toolCalls: LiveToolCall[];
  agentEvents: LiveAgentEvent[];
};

export type LiveAwaiting = {
  status: string;
  nodeId: string | null;
  label: string | null;
  summary: string | null;
  requestId: string | null;
};

export type LiveRunTotals = { tokensIn: number | null; tokensOut: number | null; costUsd: number | null };

export type LiveRunState = {
  /** The run this state belongs to; events naming another run are dropped. */
  runId: string | null;
  lastSeq: number;
  /** A WorkflowRunResponse-shaped run, fit for normalizeRun(). */
  run: RawWorkflowRun | null;
  nodes: Record<string, LiveNodeBuffer>;
  /** Nodes with a node.started not yet completed, in start order. */
  activeNodeIds: string[];
  awaiting: LiveAwaiting | null;
  runTotals: LiveRunTotals | null;
  finished: { status: string; error: string | null } | null;
  error: string | null;
  /** Count of events applied - a cheap change signal for memo keys. */
  version: number;
};

export function createLiveRunState(runId: string | null = null): LiveRunState {
  return {
    runId,
    lastSeq: 0,
    run: null,
    nodes: {},
    activeNodeIds: [],
    awaiting: null,
    runTotals: null,
    finished: null,
    error: null,
    version: 0,
  };
}

function capText(text: string): { text: string; cut: boolean } {
  return text.length > LIVE_TEXT_CAP ? { text: text.slice(-LIVE_TEXT_CAP), cut: true } : { text, cut: false };
}

function readTraceArray(raw: RawWorkflowRun["trace"]): RawTraceEntry[] {
  if (Array.isArray(raw)) return raw;
  if (typeof raw === "string" && raw.trim()) {
    try {
      const parsed: unknown = JSON.parse(raw);
      return Array.isArray(parsed) ? (parsed as RawTraceEntry[]) : [];
    } catch {
      return [];
    }
  }
  return [];
}

export function isTerminalStatus(status: unknown): boolean {
  const s = parseRunStatus(status);
  return s !== "unknown" && !isLiveRunStatus(s);
}

function isAwaitingStatus(status: unknown): boolean {
  const s = parseRunStatus(status);
  return s === "awaiting" || s === "paused";
}

/** Same visit = same node + same start (or, lacking one, same end). */
function sameVisit(a: RawTraceEntry, b: RawTraceEntry): boolean {
  if (!a.nodeId || a.nodeId !== b.nodeId) return false;
  if (a.startedAt != null && b.startedAt != null) return parseTs(a.startedAt) === parseTs(b.startedAt);
  if (a.completedAt != null && b.completedAt != null) return parseTs(a.completedAt) === parseTs(b.completedAt);
  return false;
}

function mergeTraceEntry(trace: RawTraceEntry[], entry: RawTraceEntry): RawTraceEntry[] {
  // Scan from the end: a replayed duplicate is almost always the latest visit.
  for (let i = trace.length - 1; i >= 0; i--) {
    if (sameVisit(trace[i], entry)) {
      const next = trace.slice();
      next[i] = { ...trace[i], ...entry };
      return next;
    }
  }
  return [...trace, entry];
}

function sumTrace(trace: RawTraceEntry[]): LiveRunTotals | null {
  let tIn: number | null = null;
  let tOut: number | null = null;
  let cost: number | null = null;
  for (const e of trace) {
    const a = numOrNull(e.tokensIn);
    const b = numOrNull(e.tokensOut);
    const c = numOrNull(e.costUsd);
    if (a != null) tIn = (tIn ?? 0) + a;
    if (b != null) tOut = (tOut ?? 0) + b;
    if (c != null) cost = (cost ?? 0) + c;
  }
  return tIn == null && tOut == null && cost == null ? null : { tokensIn: tIn, tokensOut: tOut, costUsd: cost };
}

function newBuffer(nodeId: string): LiveNodeBuffer {
  return {
    nodeId,
    nodeType: null,
    label: null,
    attempt: 1,
    startedAt: null,
    completedAt: null,
    phase: "running",
    entryStatus: null,
    text: "",
    textTruncated: false,
    capped: false,
    toolCalls: [],
    agentEvents: [],
  };
}

function withNode(state: LiveRunState, nodeId: string, fn: (b: LiveNodeBuffer) => LiveNodeBuffer): LiveRunState {
  const prev = state.nodes[nodeId] ?? newBuffer(nodeId);
  return { ...state, nodes: { ...state.nodes, [nodeId]: fn(prev) } };
}

function ensureRun(state: LiveRunState, ts: number | null): RawWorkflowRun {
  return (
    state.run ?? {
      id: state.runId,
      status: "RUNNING",
      trace: [],
      startedAt: ts != null ? new Date(ts).toISOString() : null,
    }
  );
}

function withRunStatus(
  state: LiveRunState,
  status: string,
  ts: number | null,
  extra: Partial<RawWorkflowRun> = {}
): LiveRunState {
  return { ...state, run: { ...ensureRun(state, ts), status, ...extra } };
}

function upsertTool(calls: LiveToolCall[], id: string, patch: Partial<LiveToolCall>, tool: string): LiveToolCall[] {
  const i = calls.findIndex((c) => c.id === id);
  if (i >= 0) {
    const next = calls.slice();
    next[i] = { ...calls[i], ...patch, tool: patch.tool || calls[i].tool };
    return next;
  }
  const row: LiveToolCall = {
    id,
    tool,
    args: null,
    preview: null,
    error: null,
    ms: null,
    status: "running",
    startedAt: null,
    ...patch,
  };
  const next = [...calls, row];
  return next.length > MAX_TOOL_CALLS ? next.slice(-MAX_TOOL_CALLS) : next;
}

function awaitingFromSnapshot(run: RawWorkflowRun): LiveAwaiting | null {
  if (!isAwaitingStatus(run.status)) return null;
  const trace = readTraceArray(run.trace);
  const last = trace[trace.length - 1];
  return {
    status: String(run.status ?? ""),
    nodeId: last?.nodeId ?? null,
    label: last?.label ?? null,
    summary: last?.summary ?? null,
    requestId: null,
  };
}

/**
 * Folds one stream event into the live state. Pure: same state + event always
 * yields the same result, and an event that changes nothing returns `state`.
 */
export function liveRunReducer(state: LiveRunState, event: LiveRunEvent): LiveRunState {
  if (event.runId && state.runId && event.runId !== state.runId) return state;
  if (event.seq != null) {
    if (event.seq <= state.lastSeq) return state;
  }
  const next = applyEvent(state, event);
  if (next === state) return state;
  return {
    ...next,
    lastSeq: event.seq != null ? Math.max(state.lastSeq, event.seq) : next.lastSeq,
    version: state.version + 1,
  };
}

function applyEvent(state: LiveRunState, ev: LiveRunEvent): LiveRunState {
  const d = ev.data;
  const nodeId = ev.nodeId;
  switch (ev.type) {
    case "snapshot": {
      if (!isRecord(d) || Object.keys(d).length === 0) return state;
      const snap = d as RawWorkflowRun;
      const trace = readTraceArray(snap.trace);
      const run: RawWorkflowRun = { ...snap, trace };
      const terminal = isTerminalStatus(snap.status);
      let awaiting = state.awaiting;
      if (isAwaitingStatus(snap.status)) awaiting = awaiting ?? awaitingFromSnapshot(run);
      else awaiting = null;
      return {
        ...state,
        run,
        runId: state.runId ?? strOrNull(snap.id),
        awaiting,
        runTotals: sumTrace(trace) ?? state.runTotals,
        finished: terminal
          ? (state.finished ?? { status: String(snap.status), error: snap.errorMessage ?? null })
          : state.finished,
        activeNodeIds: terminal ? [] : state.activeNodeIds,
      };
    }
    case "run.started":
    case "run.resumed":
      return { ...withRunStatus(state, "RUNNING", ev.ts), awaiting: null, finished: null };
    case "node.started": {
      if (!nodeId) return state;
      const s = withNode(state, nodeId, () => ({
        ...newBuffer(nodeId),
        nodeType: strOrNull(d.nodeType),
        label: strOrNull(d.label),
        attempt: numOrNull(d.attempt) ?? 1,
        startedAt: ev.ts,
      }));
      return {
        ...s,
        run: state.run && isAwaitingStatus(state.run.status) ? { ...state.run, status: "RUNNING" } : s.run,
        activeNodeIds: [...state.activeNodeIds.filter((id) => id !== nodeId), nodeId],
      };
    }
    case "node.token": {
      if (!nodeId) return state;
      if (d.capped === true) return withNode(state, nodeId, (b) => ({ ...b, capped: true }));
      if (d.reset === true) {
        const { text, cut } = capText(typeof d.text === "string" ? d.text : "");
        return withNode(state, nodeId, (b) => ({ ...b, text, textTruncated: cut }));
      }
      if (typeof d.delta !== "string" || d.delta === "") return state;
      const delta = d.delta;
      return withNode(state, nodeId, (b) => {
        const { text, cut } = capText(b.text + delta);
        return { ...b, text, textTruncated: b.textTruncated || cut };
      });
    }
    case "node.tool_call": {
      if (!nodeId) return state;
      const id = strOrNull(d.id) ?? `seq-${ev.seq ?? 0}`;
      const tool = strOrNull(d.tool) ?? "tool";
      return withNode(state, nodeId, (b) => ({
        ...b,
        toolCalls: upsertTool(
          b.toolCalls,
          id,
          { tool, args: strOrNull(d.args), startedAt: ev.ts, status: "running" },
          tool
        ),
      }));
    }
    case "node.tool_result":
    case "node.tool_error": {
      if (!nodeId) return state;
      const id = strOrNull(d.id) ?? `seq-${ev.seq ?? 0}`;
      const tool = strOrNull(d.tool) ?? "tool";
      const failed = ev.type === "node.tool_error";
      return withNode(state, nodeId, (b) => ({
        ...b,
        toolCalls: upsertTool(
          b.toolCalls,
          id,
          failed
            ? { tool, error: strOrNull(d.message) ?? "Tool failed", ms: numOrNull(d.ms), status: "error" }
            : { tool, preview: strOrNull(d.preview), ms: numOrNull(d.ms), status: "ok" },
          tool
        ),
      }));
    }
    case "node.agent_event": {
      if (!nodeId) return state;
      const kind = strOrNull(d.kind) ?? "event";
      // Liveness beats carry no information worth a row.
      if (kind === "heartbeat") return state;
      return withNode(state, nodeId, (b) => {
        const list = [...b.agentEvents, { kind, ts: ev.ts, data: d }];
        return { ...b, agentEvents: list.length > MAX_AGENT_EVENTS ? list.slice(-MAX_AGENT_EVENTS) : list };
      });
    }
    case "node.completed": {
      const entry = isRecord(d.entry) ? (d.entry as RawTraceEntry) : null;
      const id = nodeId ?? entry?.nodeId ?? null;
      if (!id) return state;
      let s = withNode(state, id, (b) => ({
        ...b,
        nodeType: b.nodeType ?? entry?.nodeType ?? null,
        label: b.label ?? entry?.label ?? null,
        phase: "done",
        entryStatus: entry?.status ?? null,
        completedAt: parseTs(entry?.completedAt) ?? ev.ts,
        // A tool still "running" when its node finished never reported back.
        toolCalls: b.toolCalls.map((c) =>
          c.status === "running" ? { ...c, status: "error" as const, error: c.error ?? "No result reported" } : c
        ),
      }));
      if (entry) {
        const run = ensureRun(s, ev.ts);
        const fullEntry: RawTraceEntry = { ...entry, nodeId: entry.nodeId ?? id };
        s = { ...s, run: { ...run, trace: mergeTraceEntry(readTraceArray(run.trace), fullEntry) } };
      }
      const totals = isRecord(d.runTotals)
        ? {
            tokensIn: numOrNull(d.runTotals.tokensIn),
            tokensOut: numOrNull(d.runTotals.tokensOut),
            costUsd: numOrNull(d.runTotals.costUsd),
          }
        : s.runTotals;
      return { ...s, runTotals: totals, activeNodeIds: s.activeNodeIds.filter((n) => n !== id) };
    }
    case "run.awaiting": {
      const status = strOrNull(d.status) ?? "AWAITING_APPROVAL";
      const s = withRunStatus(state, status, ev.ts);
      return {
        ...s,
        awaiting: {
          status,
          nodeId: strOrNull(d.nodeId) ?? nodeId,
          label: strOrNull(d.label),
          summary: strOrNull(d.summary),
          requestId: strOrNull(d.requestId),
        },
      };
    }
    case "run.finished": {
      const status = strOrNull(d.status) ?? "COMPLETED";
      const error = strOrNull(d.error);
      const s = withRunStatus(state, status, ev.ts, {
        errorMessage: error ?? state.run?.errorMessage ?? null,
        completedAt: state.run?.completedAt ?? (ev.ts != null ? new Date(ev.ts).toISOString() : null),
      });
      return { ...s, finished: { status, error }, awaiting: null, activeNodeIds: [] };
    }
    case "error":
      return { ...state, error: strOrNull(d.message) ?? "The live stream failed." };
    default:
      return state;
  }
}

/** Convenience fold for tests and batch dispatch. */
export function reduceLiveEvents(state: LiveRunState, events: readonly LiveRunEvent[]): LiveRunState {
  let s = state;
  for (const e of events) s = liveRunReducer(s, e);
  return s;
}
