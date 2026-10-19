import { MarkerType } from "@xyflow/react";
import WorkflowNode from "./WorkflowNode";
import WorkflowEdge from "./WorkflowEdge";
import { NODE_DEFS, type WorkflowGraphNode, type WorkflowGraphEdge } from "./nodeDefs";

export const nodeTypes = Object.fromEntries(NODE_DEFS.map((d) => [d.type, WorkflowNode]));

// Edge renderer override: every edge below is stamped type:"smoothstep"
// (see DEFAULT_EDGE_OPTIONS), so registering that built-in name here routes
// all of them through WorkflowEdge (state colours, label pill, insert "+",
// delete "x"). The key stays "smoothstep" so saved graphs and existing
// edge.type values keep resolving; the delete context still lives in
// DeletableEdge.tsx and WorkflowEdge consumes it.
export const edgeTypes = { smoothstep: WorkflowEdge };

// Every edge is a smooth, arrow-terminated, animated ("marching ants") line
// by default - the single biggest complaint fixed from the first pass,
// which used @xyflow/react's bare default (a thin, static, unmarked line
// that's genuinely hard to notice at a glance). Animation direction always
// reads left-to-right/top-to-bottom, matching how every node's own
// handles are laid out (target on the left, source on the right).
// Was hardcoded to the pre-AXON "aurora" indigo (#6366f1) - globals.css
// itself documents that identity as replaced app-wide (see its "AXON
// cyan/violet identity" comment), and every OTHER edge-state color a few
// hundred lines below (displayEdges) was already migrated to the
// theme-reactive --hud-* tokens for exactly this reason ("rendered wrong
// in light mode"). These were the one remaining spot - the idle/default
// edge and the live connection-drag line, i.e. what a builder sees most.
export const DEFAULT_EDGE_OPTIONS = {
  type: "smoothstep",
  animated: true,
  markerEnd: { type: MarkerType.ArrowClosed, color: "var(--hud-violet)", width: 16, height: 16 },
  style: { stroke: "var(--hud-violet)", strokeWidth: 2 },
};

export const CONNECTION_LINE_STYLE = { stroke: "var(--hud-violet)", strokeWidth: 2 };

// How far (in px, at 100% zoom) a released connection will snap to the
// nearest compatible handle - @xyflow/react defaults this to 20, which in
// practice is smaller than the visual gap most people leave between their
// cursor and a ~10px handle dot, especially once the canvas is zoomed out
// to fit a larger graph. This was the actual root cause behind "I can't
// connect the nodes": the drop just needed to land closer to the dot than
// most people naturally get. Raising it plus enlarging the handles
// themselves (see .wf-handle in globals.css) fixes it from both ends.
export const CONNECTION_RADIUS = 42;

// One entry of a run's per-node trace as WorkflowExecutionEngine returns it
// (the subset this canvas actually reads - the full run-detail page renders
// more fields, but typing only what's consumed here keeps this local type
// from silently drifting when the backend DTO grows).
export type WorkflowRunTraceEntry = {
  nodeId: string;
  label?: string;
  status: string;
  summary?: string;
  logLines?: string[];
  // Optional per-node metrics; node footer chips render only when present.
  durationMs?: number;
  tokens?: number;
  costUsd?: number;
};

// One row of api.listWorkflowRuns for the History tab (again only the fields
// the list rendering below touches).
export type WorkflowRunSummary = { id: string; status: string; startedAt?: string };

// The canvas now lives on its own dedicated page (app/workflows/[id]) with
// nothing else competing for vertical space, so it can fill essentially the
// whole viewport instead of a cramped fixed 520px. Sizing itself is handled
// entirely by CSS flexbox now (.ax-wf-shell / .ax-wf-main in globals.css),
// not a JS-computed `calc(100vh - Npx)` guess - direct feedback ("so much
// gaps and still not aligned properly... always pages should be responsive")
// on the previous magic-number version, which drifted from reality whenever
// the topbar wrapped or the trigger-strip appeared/grew. See the comment
// above .ax-wf-shell in globals.css for how the real flex-fill chain works.

// Same display heuristic as the full run-detail page (workflows/[id]/runs/
// [runId]/page.js) - classifies from real substrings the backend already
// guarantees appear in the text (WorkflowExecutionEngine never sends a
// structured level field), never a fabricated one. Kept in sync with that
// page's classifyLogLine/LOG_LEVEL_LABEL rather than shared/imported since
// this is the only other place logLines get rendered, and duplicating one
// small pure function is cheaper than adding a shared-utils module for it.
export function classifyLogLine(line: any) {
  if (/ERROR:|failed:/i.test(line)) return "error";
  if (/^Tool call:/i.test(line)) return "tool";
  if (/^(Agent:|Calling agent|Agent replied)/i.test(line)) return "agent";
  return "info";
}
export const LOG_LEVEL_LABEL = { error: "ERROR", tool: "TOOL", agent: "AGENT", info: "INFO" };

// Token-class textarea styling matching the ShadCN Input primitive (no
// ui/textarea primitive exists in the foundation) - used by the run modal's
// multi-line execution-prompt field.
export const TEXTAREA_CLASS =
  "flex w-full min-w-0 rounded-md border border-border bg-secondary/40 px-3 py-2 text-sm text-foreground shadow-sm outline-none transition-[color,box-shadow] placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/40 disabled:cursor-not-allowed disabled:opacity-50";

// Param types are the persisted-graph subset (nodeDefs.ts's WorkflowGraphNode/
// Edge): the live canvas arrays carry extra @xyflow/react runtime fields
// (selected/measured/animated etc.), but this function's whole job is to strip
// down to exactly what WorkflowGraphDto persists, so the wire shape is the
// honest contract to type against.
export function toWireGraph(nodes: WorkflowGraphNode[], edges: WorkflowGraphEdge[]) {
  return {
    nodes: nodes.map((n) => ({
      id: n.id,
      type: n.type,
      position: n.position,
      data: { label: n.data.label || "", config: n.data.config || {} },
    })),
    edges: edges.map((e) => ({
      id: e.id,
      source: e.source,
      target: e.target,
      sourceHandle: e.sourceHandle || null,
      targetHandle: e.targetHandle || null,
    })),
  };
}

// Schedule trigger (#166) - translates the simple frequency picker into a
// plain 5-field cron string (minute hour dom month dow), UTC. Nobody
// configuring a workflow ever types cron syntax themselves - this is the
// one place that syntax exists at all on the frontend.
export function cronFromPreset(frequency: any, time: any, day: any) {
  const [hour, minute] = (time || "09:00").split(":");
  if (frequency === "hourly") return "0 * * * *";
  if (frequency === "daily") return `${Number(minute)} ${Number(hour)} * * *`;
  if (frequency === "weekly") return `${Number(minute)} ${Number(hour)} * * ${day}`;
  return null;
}

// Inverse of cronFromPreset - best-effort, only recognizes cron strings this
// same picker could have produced. Anything else (e.g. a workflow whose
// schedule was set some other way) is left as null so the UI falls back to
// "Off" rather than showing a wrong preset.
export function parseCronPreset(cron: any) {
  if (!cron) return null;
  const parts = cron.trim().split(/\s+/);
  if (parts.length !== 5) return null;
  const [min, hour, dom, month, dow] = parts;
  if (dom !== "*" || month !== "*") return null;
  if (min === "0" && hour === "*" && dow === "*") return { frequency: "hourly" };
  const time = `${hour.padStart(2, "0")}:${min.padStart(2, "0")}`;
  if (dow === "*") return { frequency: "daily", time };
  return { frequency: "weekly", time, day: dow };
}

export function fromWireGraph(graph: any) {
  // `graph` itself stays untyped (it's whatever JSON the fetch handed back),
  // but each element is the WorkflowGraphDto wire shape - annotating the
  // callback params types the mapping without changing the any-typed return
  // the nodes/edges state setters currently accept.
  const nodes = (graph?.nodes || []).map((n: WorkflowGraphNode) => ({
    id: n.id,
    type: n.type,
    position: n.position || { x: 0, y: 0 },
    data: { label: n.data?.label || "", config: n.data?.config || {} },
  }));
  // Explicitly stamped with DEFAULT_EDGE_OPTIONS rather than relying solely
  // on <ReactFlow defaultEdgeOptions>: a saved graph's edges never carried
  // type/animated/style/markerEnd (WorkflowEdgeDto.java has no such fields -
  // they're purely a canvas presentation concern), so without this every
  // edge loaded from a saved workflow would render as @xyflow/react's bare,
  // unmarked default line even though a freshly-drawn connection looks right.
  const edges = (graph?.edges || []).map((e: WorkflowGraphEdge) => ({
    id: e.id,
    source: e.source,
    target: e.target,
    sourceHandle: e.sourceHandle || undefined,
    targetHandle: e.targetHandle || undefined,
    ...DEFAULT_EDGE_OPTIONS,
  }));
  return { nodes, edges };
}
