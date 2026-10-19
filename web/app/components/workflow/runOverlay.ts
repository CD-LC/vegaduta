// Canvas-side glue between the pure run model (runModel.ts) and what React
// Flow renders: per-node / per-edge overlays derived from a RunFrame, interned
// so an unchanged node or edge keeps the SAME decorated object between
// renders. That identity is what lets the memo'd WorkflowNode / WorkflowEdge
// skip work while the replay playhead moves 60 times a second - only the
// items whose state actually changed at this frame re-render.

import { createContext } from "react";
import { MarkerType } from "@xyflow/react";
import type { WorkflowNodeRunTrace } from "./WorkflowNode";
import type { EdgeRunState } from "./WorkflowEdge";
import type { InspectorToolCall, InspectorVisit } from "./RunStepInspector";
import type { LensElementStyle, LensRun, LensTraceEntry } from "./lensStyles";
import type { NodeLiveToolCall } from "./NodeLivePanel";
import type { LiveNodeBuffer } from "./liveRunStream";
import type { EvalNodeStats } from "./EvalBadge";
import type { PinMeta } from "./pinModel";
import { classifierEdgeLabel, type AiClassDef } from "./aiNodes";
import {
  frameAt,
  toEdgeRunState,
  ERROR_HANDLE,
  type NodeFrame,
  type RawTraceEntry,
  type RawWorkflowRun,
  type RunEdge,
  type RunFrame,
  type RunModel,
  type TraceVisit,
} from "./runModel";

// ---------------------------------------------------------------------------
// Edge look (marker colour per run state). Module constants so an edge whose
// state did not change keeps referentially-equal style/marker objects.
// ---------------------------------------------------------------------------

const EDGE_ARROW = (color: string, size = 16) => ({ type: MarkerType.ArrowClosed, color, width: size, height: size });
const EDGE_LOOK: Record<EdgeRunState, { animated: boolean; style: Record<string, unknown>; markerEnd: ReturnType<typeof EDGE_ARROW> }> = {
  done: { animated: false, style: { stroke: "var(--hud-cyan)", strokeWidth: 2 }, markerEnd: EDGE_ARROW("var(--hud-cyan)") },
  flowing: {
    animated: true,
    style: { stroke: "var(--hud-violet)", strokeWidth: 2.4, strokeDasharray: "6 6" },
    markerEnd: EDGE_ARROW("var(--hud-violet)"),
  },
  pending: {
    animated: false,
    style: { stroke: "var(--hud-text-dim)", strokeWidth: 2, strokeDasharray: "5 7" },
    markerEnd: EDGE_ARROW("var(--hud-text-dim)", 14),
  },
  skipped: {
    animated: false,
    style: { stroke: "var(--hud-text-dim)", strokeWidth: 2, strokeDasharray: "5 7" },
    markerEnd: EDGE_ARROW("var(--hud-text-dim)", 14),
  },
  failed: { animated: false, style: { stroke: "var(--ax-red)", strokeWidth: 2 }, markerEnd: EDGE_ARROW("var(--ax-red)") },
  "awaiting-approval": {
    animated: false,
    style: { stroke: "var(--ax-amber)", strokeWidth: 2, strokeDasharray: "4 4" },
    markerEnd: EDGE_ARROW("var(--ax-amber)"),
  },
};

// ---------------------------------------------------------------------------
// Interned overlays
// ---------------------------------------------------------------------------

/** "ahead" = replay has not reached it yet; "unreached" = the run ended without it. */
export type ReplayMark = "ahead" | "unreached";

export type NodeOverlay = {
  trace: WorkflowNodeRunTrace | undefined;
  running: boolean;
  tourFocus: boolean;
  visitCount: number | undefined;
  replay: ReplayMark | undefined;
  inspected: boolean;
  /** A failed Validate named this node (SE-26). */
  invalid?: boolean;
  lens: LensElementStyle | undefined;
};

export type EdgeOverlay = {
  state: EdgeRunState | undefined;
  label: string | undefined;
  passes: number | undefined;
  lens: LensElementStyle | undefined;
  readOnly: boolean;
};

const INTERN_CAP = 4000;
const nodeOverlays = new Map<string, NodeOverlay>();
const edgeOverlays = new Map<string, EdgeOverlay>();

function lensKey(l: LensElementStyle | undefined): string {
  return l ? `${l.className}|${l.badge ?? ""}|${l.description ?? ""}|${l.intensity.toFixed(3)}|${l.dim}` : "";
}

function intern<T>(cache: Map<string, T>, key: string, make: () => T): T {
  const hit = cache.get(key);
  if (hit) return hit;
  if (cache.size > INTERN_CAP) cache.clear();
  const v = make();
  cache.set(key, v);
  return v;
}

export function internNodeOverlay(o: NodeOverlay): NodeOverlay {
  const t = o.trace;
  const key = [
    t ? `${t.status}|${t.summary ?? ""}|${t.durationMs ?? ""}|${t.tokens ?? ""}|${t.costUsd ?? ""}` : "-",
    o.running ? 1 : 0,
    o.tourFocus ? 1 : 0,
    o.visitCount ?? "",
    o.replay ?? "",
    o.inspected ? 1 : 0,
    o.invalid ? 1 : 0,
    lensKey(o.lens),
  ].join("\u0001");
  return intern(nodeOverlays, key, () => o);
}

export function internEdgeOverlay(o: EdgeOverlay): EdgeOverlay {
  const key = [o.state ?? "", o.label ?? "", o.passes ?? "", lensKey(o.lens), o.readOnly ? 1 : 0].join("\u0001");
  return intern(edgeOverlays, key, () => o);
}

// Per-object memo: a node/edge whose inputs did not change keeps the SAME
// decorated object, so React Flow re-renders only what moved. WeakMap so
// removed items are collected with no manual cleanup.
const nodeDisplayCache = new WeakMap<
  object,
  { overlay: NodeOverlay; live: NodeLiveSlice | undefined; evalStats: EvalNodeStats | undefined; pin: PinMeta | undefined; out: object }
>();
const edgeDisplayCache = new WeakMap<object, { overlay: EdgeOverlay; onInsert: unknown; out: object }>();

/**
 * `live` / `evalStats` / `pin` are compared by reference, not interned: callers pass
 * the SAME object until that node's stream buffer / eval row changes (see
 * liveSliceFromBuffer / liveSliceFromEntry), so a token for one agent
 * re-renders that agent's card only.
 */
export function decorateNode<N extends { data?: object }>(
  n: N,
  overlay: NodeOverlay,
  live?: NodeLiveSlice,
  evalStats?: EvalNodeStats,
  pin?: PinMeta,
): N {
  const hit = nodeDisplayCache.get(n);
  if (hit && hit.overlay === overlay && hit.live === live && hit.evalStats === evalStats && hit.pin === pin) return hit.out as N;
  const out = {
    ...n,
    data: {
      ...(n.data || {}),
      trace: overlay.trace,
      running: overlay.running,
      tourFocus: overlay.tourFocus,
      visitCount: overlay.visitCount,
      replay: overlay.replay,
      inspected: overlay.inspected,
      invalid: overlay.invalid,
      lens: overlay.lens,
      live,
      evalStats,
      pin,
    },
  };
  nodeDisplayCache.set(n, { overlay, live, evalStats, pin, out });
  return out;
}

// ---------------------------------------------------------------------------
// Live agent slice (NodeLivePanel props) - stream buffer or finished trace entry
// ---------------------------------------------------------------------------

export type NodeLiveSlice = {
  text?: string;
  streaming: boolean;
  capped?: boolean;
  toolCalls?: NodeLiveToolCall[];
};

/** Opens the step inspector for a node from inside its card (observe mode). */
export const NodeLiveOpenContext = createContext<((nodeId: string) => void) | null>(null);

const bufferSlices = new WeakMap<LiveNodeBuffer, { streaming: boolean; out: NodeLiveSlice }>();

export function liveSliceFromBuffer(b: LiveNodeBuffer, streaming: boolean): NodeLiveSlice {
  const hit = bufferSlices.get(b);
  if (hit && hit.streaming === streaming) return hit.out;
  const out: NodeLiveSlice = {
    text: b.text || undefined,
    streaming,
    capped: b.capped || b.textTruncated || undefined,
    toolCalls: b.toolCalls.length
      ? b.toolCalls.map((c) => ({
          id: c.id,
          tool: c.tool,
          args: c.args ?? undefined,
          preview: c.preview ?? undefined,
          error: c.error ?? undefined,
          ms: c.ms ?? undefined,
          status: c.status,
        }))
      : undefined,
  };
  bufferSlices.set(b, { streaming, out });
  return out;
}

type WireToolCall = {
  toolName?: unknown;
  input?: unknown;
  output?: unknown;
  error?: unknown;
  startedAt?: unknown;
  durationMs?: unknown;
};

const optStr = (v: unknown): string | undefined => (typeof v === "string" && v !== "" ? v : undefined);
const optNum = (v: unknown): number | undefined => {
  const n = typeof v === "number" ? v : typeof v === "string" && v.trim() !== "" ? Number(v) : NaN;
  return Number.isFinite(n) ? n : undefined;
};

/** NodeTraceEntry.toolCalls (newer core; absent or null on older runs) -> inspector rows. */
export function entryToolCalls(entry: object | null | undefined): InspectorToolCall[] {
  const raw = (entry as { toolCalls?: unknown } | null | undefined)?.toolCalls;
  if (!Array.isArray(raw)) return [];
  const out: InspectorToolCall[] = [];
  for (const c of raw as WireToolCall[]) {
    if (!c || typeof c !== "object") continue;
    out.push({
      tool: optStr(c.toolName) ?? "tool",
      input: optStr(c.input) ?? null,
      output: optStr(c.output) ?? null,
      error: optStr(c.error) ?? null,
      startedAt: optStr(c.startedAt) ?? null,
      durationMs: optNum(c.durationMs) ?? null,
    });
  }
  return out;
}

const entrySlices = new WeakMap<object, NodeLiveSlice | null>();

/** Finished visit's tool calls as a (non-streaming) panel slice; undefined when it made none. */
export function liveSliceFromEntry(entry: object | null | undefined): NodeLiveSlice | undefined {
  if (!entry) return undefined;
  const hit = entrySlices.get(entry);
  if (hit !== undefined) return hit ?? undefined;
  const calls = entryToolCalls(entry);
  const out: NodeLiveSlice | null = calls.length
    ? {
        streaming: false,
        toolCalls: calls.map((c, i) => ({
          id: String(i),
          tool: c.tool,
          args: c.input ?? undefined,
          preview: c.output ?? undefined,
          error: c.error ?? undefined,
          ms: c.durationMs ?? undefined,
          status: c.error ? "error" : "ok",
        })),
      }
    : null;
  entrySlices.set(entry, out);
  return out ?? undefined;
}

/** Raw trace entries of a run, parsed once per run object. */
const traceCache = new WeakMap<object, RawTraceEntry[]>();
export function runTraceEntries(raw: RawWorkflowRun | null | undefined): RawTraceEntry[] {
  if (!raw) return [];
  const hit = traceCache.get(raw);
  if (hit) return hit;
  const out = rawTrace(raw);
  traceCache.set(raw, out);
  return out;
}

export function decorateEdge<E extends { data?: object }>(e: E, overlay: EdgeOverlay, onInsert: unknown): E {
  const hit = edgeDisplayCache.get(e);
  if (hit && hit.overlay === overlay && hit.onInsert === onInsert) return hit.out as E;
  const look = overlay.state ? EDGE_LOOK[overlay.state] : null;
  const out = {
    ...e,
    ...(look ?? {}),
    data: {
      ...(e.data || {}),
      runState: overlay.state,
      label: overlay.label,
      passes: overlay.passes,
      lens: overlay.lens,
      readOnly: overlay.readOnly,
      onInsert: overlay.readOnly ? undefined : onInsert,
    },
  };
  edgeDisplayCache.set(e, { overlay, onInsert, out });
  return out;
}

type RouteConfig = { handle?: string; label?: string };
type EdgeLike = { sourceHandle?: string | null };
type SourceLike = { type?: string; data?: { config?: { routes?: RouteConfig[]; classes?: AiClassDef[] } } } | undefined;

// Branch/route name shown on an edge's pill, derived from the source node's
// handle (condition true/false, supervisor route label, the shared error port).
export function edgeLabelFor(edge: EdgeLike, source: SourceLike): string | undefined {
  const h = edge.sourceHandle;
  if (!h) return undefined;
  if (h === "error") return "error";
  if (source?.type === "condition") return h === "true" || h === "false" ? h : undefined;
  if (source?.type === "supervisor") {
    const route = (source.data?.config?.routes || []).find((r) => r.handle === h);
    return route ? route.label || route.handle : undefined;
  }
  // Switch: the case name IS the handle (dataNodes.switchPorts), and "default" is the fixed no-match port.
  if (source?.type === "switch") return h;
  if (source?.type === "question_classifier") return classifierEdgeLabel(source.data?.config, h);
  return undefined;
}

// ---------------------------------------------------------------------------
// Frame -> node trace
// ---------------------------------------------------------------------------

function pausedWireStatus(v: TraceVisit | null): string {
  if (v?.awaiting === "approval") return "AWAITING_APPROVAL";
  if (v?.awaiting === "input") return "AWAITING_INPUT";
  return "PAUSED";
}

/** The node card's chip data at a frame; undefined while pending/running. */
export function frameTrace(nf: NodeFrame | undefined): WorkflowNodeRunTrace | undefined {
  if (!nf || nf.status === "running" || nf.status === "pending") return undefined;
  const v = nf.activeVisit;
  const status =
    nf.status === "ok" ? (v?.pinned ? "PINNED" : "OK") : nf.status === "error" ? "ERROR" : nf.status === "skipped" ? "SKIPPED" : pausedWireStatus(v);
  const tokens = v && (v.tokensIn != null || v.tokensOut != null) ? (v.tokensIn ?? 0) + (v.tokensOut ?? 0) : undefined;
  return {
    status,
    summary: v?.summary || undefined,
    durationMs: v?.durationMs ?? undefined,
    tokens,
    costUsd: v?.costUsd ?? undefined,
  };
}

/**
 * A node that parked (Wait / approval / input) records an AWAITING_* visit, and
 * core does not rewrite it when the run later resumes. Once the run has ended
 * (completed, failed or cancelled) that state is history - the run moved past
 * it - so the card must not keep saying "Waiting" on a finished run. Still-live
 * and still-parked runs are left alone.
 */
export function settleFinishedTrace(
  trace: WorkflowNodeRunTrace | undefined,
  runFinished: boolean
): WorkflowNodeRunTrace | undefined {
  if (!trace || !runFinished) return trace;
  const s = String(trace.status ?? "").toUpperCase();
  if (s === "PAUSED" || s.startsWith("AWAITING")) return { ...trace, status: "OK" };
  return trace;
}

// ---------------------------------------------------------------------------
// Run display: frame + live frontier
// ---------------------------------------------------------------------------

export type EdgeDisplay = { state: EdgeRunState; passes: number };

export type RunDisplay = {
  frame: RunFrame;
  running: Set<string>;
  edges: Record<string, EdgeDisplay>;
  atEnd: boolean;
  /**
   * Live-edge nodes that finished with several named routes out and no
   * readable choice yet. Their route targets stay pending rather than all
   * glowing as if every route ran; the canvas may show "deciding…" on them.
   */
  deciding: Set<string>;
};

const PARKED = new Set(["paused", "awaiting"]);
const LINK_SLOP_MS = 50;

// Supervisor / voice-menu summaries name the chosen handle ("Supervisor
// routed to 'route-2'.", "... defaulted to 'route-1' ...", WorkflowNodeExecutor).
// runModel.parseBranch only reads Condition, so the route is recovered here.
// A name may contain an apostrophe ("it's"), so the closing quote is the first one followed by the
// summary's own punctuation, not simply the next quote.
const ROUTE_RE = /(?:routed|defaulted) to '(.+?)'(?=\.|\s|$)/i;

// A skipped visit that still names a branch (a Switch that logged a warning) can be followed.
function canFollow(status: string, v: TraceVisit): boolean {
  return status === "ok" || (status === "skipped" && !!v.branch);
}

/** The handle a finished visit chose, lower-cased; null when unknown. */
export function chosenHandle(v: TraceVisit): string | null {
  if (v.branch) return v.branch.toLowerCase();
  const m = ROUTE_RE.exec(v.summary || "");
  return m ? m[1].toLowerCase() : null;
}

type Frontier = { targets: Set<string>; deciding: Set<string> };

// The engine appends a trace entry only when a node FINISHES, so the node
// executing right now has no visit yet. At the live edge of a running run we
// infer it: a pending edge whose source just finished (and whose handle that
// visit could actually follow) points at the node that must be executing.
// A named (non-error) handle is a CHOICE, not a fan-out: the engine follows
// exactly one, so without a readable choice none of them is lit.
function inferFrontier(model: RunModel, frame: RunFrame, edges: readonly RunEdge[], nodeIds: readonly string[]): Frontier {
  const out: Frontier = { targets: new Set<string>(), deciding: new Set<string>() };
  if (!model.live || PARKED.has(model.run.status)) return out;
  if (Object.values(frame.nodes).some((n) => n.status === "running")) return out;
  if (model.visits.length === 0) {
    const targets = new Set(edges.map((e) => e.target));
    for (const id of nodeIds) if (!targets.has(id)) out.targets.add(id);
    return out;
  }
  // Distinct named route handles per source: >1 means the source had a choice to make.
  const routesOf = new Map<string, Set<string>>();
  for (const e of edges) {
    const h = (e.sourceHandle ?? "").toLowerCase();
    if (!h || h === ERROR_HANDLE) continue;
    const s = routesOf.get(e.source) ?? new Set<string>();
    s.add(h);
    routesOf.set(e.source, s);
  }
  for (const e of edges) {
    if (frame.edges[e.id]?.state !== "pending") continue;
    const src = frame.nodes[e.source];
    const v = src?.activeVisit;
    if (!src || !v || v.endMs == null) continue;
    const h = (e.sourceHandle ?? "").toLowerCase();
    if (h === ERROR_HANDLE ? src.status !== "error" : !canFollow(src.status, v)) continue;
    if (h && h !== ERROR_HANDLE) {
      const chosen = chosenHandle(v);
      if (chosen != null ? h !== chosen : (routesOf.get(e.source)?.size ?? 0) > 1) {
        if (chosen == null) out.deciding.add(e.source);
        continue;
      }
    }
    const later = frame.nodes[e.target]?.activeVisit;
    if (later && later.startMs >= v.endMs - LINK_SLOP_MS) continue;
    out.targets.add(e.target);
  }
  return out;
}

// A frontier target can be reached by several edges; only those its finished
// source could actually have taken carry the travelling pulse.
function pulseCanFollow(e: RunEdge, frame: RunFrame, deciding: ReadonlySet<string>): boolean {
  const src = frame.nodes[e.source];
  const v = src?.activeVisit;
  if (!src || !v || v.endMs == null) return false;
  const h = (e.sourceHandle ?? "").toLowerCase();
  if (h === ERROR_HANDLE) return src.status === "error";
  if (!canFollow(src.status, v)) return false;
  if (!h) return true;
  if (deciding.has(e.source)) return false;
  const chosen = chosenHandle(v);
  return chosen == null || chosen === h;
}

export function computeRunDisplay(
  model: RunModel,
  tMs: number,
  edges: readonly RunEdge[],
  nodeIds: readonly string[],
): RunDisplay {
  const frame = frameAt(model, tMs, edges);
  const atEnd = frame.tMs >= model.totalMs;
  const running = new Set<string>();
  for (const [id, n] of Object.entries(frame.nodes)) if (n.status === "running") running.add(id);
  const { targets: frontier, deciding } = atEnd
    ? inferFrontier(model, frame, edges, nodeIds)
    : { targets: new Set<string>(), deciding: new Set<string>() };
  for (const id of frontier) running.add(id);
  const out: Record<string, EdgeDisplay> = {};
  for (const e of edges) {
    const f = frame.edges[e.id];
    if (!f) continue;
    const flowingIn = f.state === "pending" && frontier.has(e.target) && pulseCanFollow(e, frame, deciding);
    out[e.id] = { state: flowingIn ? "flowing" : toEdgeRunState(f.state), passes: f.traversals };
  }
  return { frame, running, edges: out, atEnd, deciding };
}

// ---------------------------------------------------------------------------
// Adapters for the Phase 2 components
// ---------------------------------------------------------------------------

function rawTrace(raw: RawWorkflowRun): RawTraceEntry[] {
  const t = raw.trace;
  if (Array.isArray(t)) return t;
  if (typeof t === "string" && t.trim()) {
    try {
      const parsed: unknown = JSON.parse(t);
      return Array.isArray(parsed) ? (parsed as RawTraceEntry[]) : [];
    } catch {
      return [];
    }
  }
  return [];
}

function isoOrNull(v: unknown): string | null {
  if (typeof v === "string") return v;
  if (typeof v === "number" && Number.isFinite(v)) return new Date(v).toISOString();
  return null;
}

/** Wire run -> lensStyles' LensRun (entries without a nodeId are engine noise). */
export function toLensRun(raw: RawWorkflowRun): LensRun {
  const trace: LensTraceEntry[] = [];
  for (const e of rawTrace(raw)) {
    if (!e || typeof e.nodeId !== "string" || !e.nodeId) continue;
    const d = typeof e.durationMs === "number" ? e.durationMs : typeof e.durationMs === "string" ? Number(e.durationMs) : null;
    trace.push({
      nodeId: e.nodeId,
      label: e.label ?? null,
      status: e.status ?? null,
      startedAt: isoOrNull(e.startedAt),
      completedAt: isoOrNull(e.completedAt),
      durationMs: d != null && Number.isFinite(d) ? d : null,
    });
  }
  return { id: String(raw.id ?? ""), status: raw.status ?? null, startedAt: isoOrNull(raw.startedAt), trace };
}

export function hasTrace(raw: RawWorkflowRun): boolean {
  return raw.trace != null;
}

/** `canvasLabel` (the node's title on the canvas) wins over the trace's own label. */
export function toInspectorVisit(v: TraceVisit, canvasLabel?: string, toolCalls?: InspectorToolCall[]): InspectorVisit {
  return {
    toolCalls: toolCalls && toolCalls.length ? toolCalls : undefined,
    nodeId: v.nodeId,
    nodeType: v.nodeType || null,
    label: canvasLabel || v.label || null,
    status: v.status === "running" ? "RUNNING" : v.rawStatus,
    summary: v.summary || null,
    logLines: v.logLines,
    startMs: v.startMs,
    endMs: v.endMs,
    durationMs: v.durationMs,
    visitIndex: v.visitIndex,
    inputJson: v.input ?? undefined,
    outputJson: v.output ?? undefined,
    inputTruncated: v.inputTruncated,
    outputTruncated: v.outputTruncated,
    tokensIn: v.tokensIn,
    tokensOut: v.tokensOut,
    costUsd: v.costUsd,
  };
}

/** Run duration from wire timestamps; null while unknown. */
export function wireRunDurationMs(raw: RawWorkflowRun): number | null {
  const s = typeof raw.startedAt === "string" ? Date.parse(raw.startedAt) : NaN;
  const e = typeof raw.completedAt === "string" ? Date.parse(raw.completedAt) : NaN;
  return Number.isFinite(s) && Number.isFinite(e) ? Math.max(0, e - s) : null;
}
