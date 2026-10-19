"use client";

import { useCallback, useMemo, useState } from "react";
import {
  decorateEdge,
  decorateNode,
  edgeLabelFor,
  frameTrace,
  internEdgeOverlay,
  internNodeOverlay,
  liveSliceFromBuffer,
  liveSliceFromEntry,
  runTraceEntries,
  settleFinishedTrace,
  type NodeLiveSlice,
} from "../runOverlay";
import { nodeDef, nodeDescriptionKey, nodeLabelKey } from "../nodeDefs";
import type { DiffDecorations } from "../versionDiff";
import type { PinMeta } from "../pinModel";
import type { EvalNodeStats } from "../EvalBadge";
import type { RunAwaiting } from "../ApprovalPauseCard";
import { applyEdgeDiff, applyNodeDiff } from "./diffDecorations";
import { NO_ENTRIES, NO_LIVE_NODES, NO_RUNNING, PARKED_TRACE } from "./constants";

/**
 * What the graph SHOWS: nodes/edges decorated with run state (trace, replay
 * frame, live stream slice, eval badges, pin chips, lens, version diff), plus
 * the guided tour that walks the real graph. Decorators hand back the SAME
 * object for a node whose interned overlay did not change, so a drag - or a
 * replay frame - re-renders only the nodes whose state actually moved.
 */
export function useCanvasDisplay(opts: {
  nodes: any[];
  edges: any[];
  trace: Record<string, any>;
  obs: any;
  observing: boolean;
  live: any;
  liveIsTarget: boolean;
  streamOpen: boolean;
  streamTarget: string | null;
  awaiting: RunAwaiting | null;
  invalidNodeIds: ReadonlySet<string>;
  evalNodes: Record<string, EvalNodeStats> | undefined;
  pinMetaById: Record<string, PinMeta>;
  onInsertOnEdge: (edgeId: string) => void;
  tNodeDefs: (key: string) => string;
  setCenter: (x: number, y: number, o?: { zoom?: number; duration?: number }) => unknown;
  setSelectedNodeId: (id: any) => void;
  setInspectorOpen: (open: boolean) => void;
}) {
  const {
    nodes,
    edges,
    trace,
    obs,
    observing,
    live,
    liveIsTarget,
    streamOpen,
    streamTarget,
    awaiting,
    invalidNodeIds,
    evalNodes,
    pinMetaById,
    onInsertOnEdge,
    tNodeDefs,
    setCenter,
    setSelectedNodeId,
    setInspectorOpen,
  } = opts;

  // Guided-tour mode (user-triggered via the "Tour" button, never auto-started).
  // `tourNodeId` is the node the tour is focused on: it drives both a setCenter
  // pan/zoom and the node's highlight ring (via data.tourFocus).
  const [tourOpen, setTourOpen] = useState(false);
  const [tourNodeId, setTourNodeId] = useState<string | null>(null);

  // What the graph shows about a run comes from ONE frame of the run model
  // (runModel.frameAt via runOverlay.computeRunDisplay): in observe mode the
  // frame under the replay playhead, in edit mode the end state of the
  // canvas's own last run. The node executing right now - which has no trace
  // entry until it finishes - is inferred at the live edge.
  const display = obs.display;
  const runningNodeIds = display?.running ?? NO_RUNNING;
  const replayModel = observing ? obs.model : null;
  const inspectedNodeId = observing ? obs.selectedVisit?.nodeId : undefined;
  const lensNodes = obs.lensNodes;
  // Live stream slice for the run on screen: only at the live edge (a replay
  // scrubbed back shows that moment's finished visits instead).
  const streamOnScreen = observing && liveIsTarget && obs.selectedRunId === streamTarget;
  const atLiveEdge = !!display && display.atEnd && obs.following;
  const liveNodes = streamOnScreen && atLiveEdge ? live.state.nodes : NO_LIVE_NODES;
  const liveActive = streamOnScreen && atLiveEdge && streamOpen ? live.state.activeNodeIds : null;
  const liveRunDone = !!live.state.finished;
  const selectedEntries = observing ? runTraceEntries(obs.selectedRaw) : NO_ENTRIES;
  // A viewed run that has ended: parked (AWAITING_*) visits in it are history.
  const runFinishedOnScreen = !!replayModel && !replayModel.live && !!display?.atEnd;
  const parkedNodeId = atLiveEdge ? awaiting?.nodeId : undefined;
  const parkedTrace = awaiting ? PARKED_TRACE[awaiting.status] : undefined;
  const displayNodesBase = useMemo(
    () =>
      nodes.map((n) => {
        const nf = display?.frame.nodes[n.id];
        // With the stream open the executing set is known exactly (node.started),
        // not inferred from the edges its predecessors could follow.
        const running = liveActive ? liveActive.includes(n.id) || nf?.status === "running" : runningNodeIds.has(n.id);
        const replay =
          replayModel && !nf && !running ? (display?.atEnd && !replayModel.live ? "unreached" : "ahead") : undefined;
        let nodeTrace = display ? settleFinishedTrace(frameTrace(nf), runFinishedOnScreen) : trace[n.id];
        if (!nodeTrace && n.id === parkedNodeId) nodeTrace = parkedTrace;
        const overlay = internNodeOverlay({
          trace: nodeTrace,
          running,
          tourFocus: tourOpen && n.id === tourNodeId,
          visitCount: nf && nf.visits.length > 1 ? nf.visits.length : undefined,
          replay,
          inspected: n.id === inspectedNodeId,
          lens: lensNodes[n.id],
          invalid: invalidNodeIds.has(n.id),
        });
        let liveSlice: NodeLiveSlice | undefined;
        if (observing && (n.type === "agent" || n.type === "supervisor")) {
          const buf = liveNodes[n.id];
          if (buf) liveSlice = liveSliceFromBuffer(buf, buf.phase === "running" && streamOpen && !liveRunDone);
          else if (nf?.activeVisit && nf.status !== "running")
            liveSlice = liveSliceFromEntry(selectedEntries[nf.activeVisit.traceIndex]);
        }
        return decorateNode(n, overlay, liveSlice, evalNodes?.[n.id], pinMetaById[n.id]);
      }),
    [
      nodes,
      display,
      trace,
      runningNodeIds,
      replayModel,
      tourOpen,
      tourNodeId,
      inspectedNodeId,
      invalidNodeIds,
      lensNodes,
      liveActive,
      liveNodes,
      liveRunDone,
      streamOpen,
      observing,
      selectedEntries,
      parkedNodeId,
      parkedTrace,
      evalNodes,
      runFinishedOnScreen,
      pinMetaById,
    ]
  );
  // Version-diff "Show on canvas" overlay (VersionDiffPanel -> onDecorations).
  const [diffDeco, setDiffDeco] = useState<DiffDecorations | null>(null);
  const displayNodes = useMemo(() => applyNodeDiff(displayNodesBase, diffDeco), [displayNodesBase, diffDeco]);

  // Edge run state comes from the same frame as the node chips (taken /
  // flowing / failed / skipped / awaiting, plus loop pass counts). With no
  // run there is no state, so edges keep the default build-mode look.
  const lensEdges = obs.lensEdges;
  const displayEdgesBase = useMemo(() => {
    const byId = new Map<string, any>();
    for (const n of nodes) byId.set(n.id, n);
    return edges.map((e) => {
      const d = display?.edges[e.id];
      const overlay = internEdgeOverlay({
        state: d?.state,
        label: edgeLabelFor(e, byId.get(e.source)),
        passes: d && d.passes > 1 ? d.passes : undefined,
        lens: lensEdges[e.id],
        readOnly: observing,
      });
      return decorateEdge(e, overlay, onInsertOnEdge);
    });
  }, [edges, nodes, display, lensEdges, observing, onInsertOnEdge]);
  const displayEdges = useMemo(() => applyEdgeDiff(displayEdgesBase, diffDeco), [displayEdgesBase, diffDeco]);

  // Ordered walk-through of the REAL graph: a topological sweep from the
  // trigger(s) outward along the actual edges. Each step carries only real node
  // data (author label or type label, per-type description from nodeDefs i18n).
  // Disconnected islands are appended so the tour never silently skips a node.
  const tourSteps = useMemo(() => {
    if (nodes.length === 0) return [];
    const byId = new Map<string, any>(nodes.map((n) => [n.id, n] as [string, any]));
    const incoming = new Map<string, number>(nodes.map((n) => [n.id, 0] as [string, number]));
    const adj = new Map<string, string[]>(nodes.map((n) => [n.id, []] as [string, string[]]));
    for (const e of edges) {
      if (adj.has(e.source) && byId.has(e.target)) {
        adj.get(e.source)!.push(e.target);
        incoming.set(e.target, (incoming.get(e.target) || 0) + 1);
      }
    }
    let roots = nodes.filter((n) => (incoming.get(n.id) || 0) === 0).map((n) => n.id);
    if (roots.length === 0) roots = [nodes[0].id];
    const order = [];
    const seen = new Set();
    const queue = [...roots];
    while (queue.length) {
      const id = queue.shift();
      if (seen.has(id)) continue;
      seen.add(id);
      order.push(id);
      for (const nxt of adj.get(id) || []) if (!seen.has(nxt)) queue.push(nxt);
    }
    for (const n of nodes) if (!seen.has(n.id)) order.push(n.id);
    return order.map((id) => {
      const n = byId.get(id);
      const def = nodeDef(n.type);
      const typeLabel = tNodeDefs(nodeLabelKey(def.type));
      const title = n.data?.label && n.data.label !== typeLabel ? n.data.label : typeLabel;
      return {
        id,
        shortLabel: typeLabel,
        title,
        subtitle: typeLabel,
        description: tNodeDefs(nodeDescriptionKey(def.type)),
      };
    });
  }, [nodes, edges, tNodeDefs]);

  // Pan/zoom to (and highlight) a node during the tour. Node cards are ~168px
  // wide; the +84/+34 offset frames the card roughly centered.
  const focusTourNode = useCallback(
    (nodeId: string) => {
      setTourNodeId(nodeId);
      const n = nodes.find((x) => x.id === nodeId);
      if (n && n.position) {
        void setCenter(n.position.x + 84, n.position.y + 34, { zoom: 1.15, duration: 500 });
      }
    },
    [nodes, setCenter]
  );

  const startTour = useCallback(() => {
    if (nodes.length === 0) return;
    // A tour is about watching the graph, not editing - clear any open node
    // config/inspector so the narration bubble isn't fighting the drawer.
    setSelectedNodeId(null);
    setInspectorOpen(false);
    setTourOpen(true);
  }, [nodes.length, setSelectedNodeId, setInspectorOpen]);

  const exitTour = useCallback(() => {
    setTourOpen(false);
    setTourNodeId(null);
  }, []);

  return {
    displayNodes,
    displayEdges,
    runningNodeIds,
    streamOnScreen,
    setDiffDeco,
    tourOpen,
    tourSteps,
    focusTourNode,
    startTour,
    exitTour,
  };
}
