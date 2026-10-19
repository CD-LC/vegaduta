"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useLiveRun } from "../useLiveRun";
import { useRunObserver, useStableGraph } from "../useRunObserver";
import { useObserveActions } from "../RunObserver";
import { nodeDef, nodeLabelKey } from "../nodeDefs";
import type { RunAwaiting } from "../ApprovalPauseCard";
import type { RawWorkflowRun } from "../runModel";
import { toAwaitingStatus } from "./constants";

/**
 * Flight recorder ("observe" mode) plus the live SSE stream of the run on
 * screen. The graph facts the recorder needs are signature-memoized so drags
 * don't rebuild them.
 */
export function useRunObservation(opts: {
  workflowId: any;
  nodes: any[];
  edges: any[];
  tNodeDefs: (key: string) => string;
  runResult: any;
  runLive: boolean;
  setSelectedNodeId: (id: any) => void;
}) {
  const { workflowId, nodes, edges, tNodeDefs, runResult, runLive, setSelectedNodeId } = opts;

  const labelOf = useCallback(
    (n: { id: string; type?: string; data?: { label?: string } }) =>
      n.data?.label || tNodeDefs(nodeLabelKey(nodeDef(n.type ?? "").type)),
    [tNodeDefs]
  );
  const graph = useStableGraph(nodes, edges, labelOf);
  const nodeLabel = useCallback((id: string) => graph.labels.get(id) ?? "", [graph]);

  // Live SSE stream (Phase 3): the run it follows. Sticky on purpose - it is
  // NOT cleared when that run finishes, so the final state stays on screen and
  // a finished stream is never reopened in a loop. On an older core the hook
  // reports supported=false and the 1.2s poll carries on as before.
  const [streamTarget, setStreamTarget] = useState<string | null>(null);
  // The run this canvas started (or resumed); its stream also drives runResult.
  const ownRunIdRef = useRef<string | null>(null);
  const live = useLiveRun({ workflowId, runId: streamTarget, enabled: !!streamTarget });
  const liveIsTarget = !!streamTarget && live.state.runId === streamTarget;
  const streamOpen = liveIsTarget && live.connected;
  const obs = useRunObserver({
    workflowId,
    nodeIds: graph.nodeIds,
    runEdges: graph.runEdges,
    liveRun: runResult,
    runLive,
    streamRun: liveIsTarget ? live.state.run : null,
    streamRunId: streamTarget,
    streaming: streamOpen,
  });
  // Read by the async poll loop, which must see the stream as it is NOW.
  const streamRef = useRef<{ runId: string | null; open: boolean; finished: boolean; run: RawWorkflowRun | null }>({
    runId: null,
    open: false,
    finished: false,
    run: null,
  });
  useEffect(() => {
    streamRef.current = {
      runId: streamTarget,
      open: streamOpen,
      finished: liveIsTarget && live.outcome === "finished",
      run: liveIsTarget ? live.state.run : null,
    };
  }, [streamTarget, streamOpen, liveIsTarget, live.outcome, live.state.run]);
  const observing = obs.mode === "observe";
  const observingRef = useRef(false);
  useEffect(() => {
    observingRef.current = observing;
  }, [observing]);

  const observeActions = useObserveActions(obs);
  // Every way into observe goes through here: the config drawer (and its
  // delete button) must not stay mounted on a graph that is being replayed.
  const enterObserve = useCallback(
    (id?: string) => {
      setSelectedNodeId(null);
      obs.enterObserve(id);
    },
    [obs, setSelectedNodeId]
  );

  // Observing a run that is still live (started elsewhere, or our own parked
  // run) streams it too. While our own run executes, its stream is not moved.
  const { selectedRunId: obsRunId, selectedLive: obsRunLive } = obs;
  useEffect(() => {
    if (runLive) return;
    const own = ownRunIdRef.current;
    if (!observing) {
      if (streamTarget && streamTarget !== own) setStreamTarget(null);
      return;
    }
    if (obsRunId && obsRunLive) {
      if (obsRunId !== streamTarget) setStreamTarget(obsRunId);
    } else if (streamTarget && streamTarget !== obsRunId && streamTarget !== own) {
      setStreamTarget(null);
    }
  }, [runLive, observing, obsRunId, obsRunLive, streamTarget]);

  // Node cards open the step inspector through context; the ref keeps the
  // context value stable while openNode's identity churns with the live clock.
  const openNodeRef = useRef(observeActions.openNode);
  useEffect(() => {
    openNodeRef.current = observeActions.openNode;
  });
  const openLiveNode = useCallback((nodeId: string) => {
    openNodeRef.current(nodeId);
  }, []);

  // --- Parked run -> decision card (Phase 3) ---------------------------------
  // The open stream's run.awaiting is authoritative (it carries requestId);
  // without a stream the polled status + the last parked visit stand in.
  const liveAwaiting = liveIsTarget ? live.state.awaiting : null;
  const awaiting: RunAwaiting | null = useMemo(() => {
    if (!observing || !obs.selectedRunId) return null;
    if (streamOpen && streamTarget === obs.selectedRunId && liveAwaiting?.nodeId) {
      const st = toAwaitingStatus(liveAwaiting.status);
      return st
        ? {
            status: st,
            nodeId: liveAwaiting.nodeId,
            label: liveAwaiting.label,
            summary: liveAwaiting.summary,
            requestId: liveAwaiting.requestId,
          }
        : null;
    }
    const st = toAwaitingStatus(obs.selectedRaw?.status);
    const visits = obs.model?.visits ?? [];
    if (!st || !visits.length) return null;
    let parked = visits[visits.length - 1];
    for (let i = visits.length - 1; i >= 0; i--) {
      if (visits[i].status === "paused") {
        parked = visits[i];
        break;
      }
    }
    return {
      status: st,
      nodeId: parked.nodeId,
      label: parked.label || null,
      summary: parked.summary || null,
      requestId: null,
    };
  }, [observing, obs.selectedRunId, obs.selectedRaw, obs.model, streamOpen, streamTarget, liveAwaiting]);

  return {
    graph,
    nodeLabel,
    streamTarget,
    setStreamTarget,
    ownRunIdRef,
    live,
    liveIsTarget,
    streamOpen,
    streamRef,
    obs,
    observing,
    observingRef,
    observeActions,
    enterObserve,
    openLiveNode,
    awaiting,
  };
}
