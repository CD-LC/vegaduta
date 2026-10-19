"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type MutableRefObject } from "react";
import { api } from "../../../lib/api";
import type { WorkflowEvalSummary } from "../../../lib/api/workflows";
import type { EvalNodeStats } from "../EvalBadge";
import { usePinsController } from "../PinsContext";
import { pinnedTraceNodeIds, toPinMeta, type PinMeta } from "../pinModel";
import type { RawWorkflowRun } from "../runModel";
import { runTraceEntries } from "../runOverlay";
import type { StepTestContextValue } from "../StepTestSlot";

/**
 * Per-node knowledge accumulated from past runs and pins: eval badges
 * (Phase 4), "Run this step" prefills, and data pins (spec: workflow
 * expressions + pinning, section 5).
 */
export function useRunInsights(opts: {
  workflowId: any;
  nodes: any[];
  edges: any[];
  isDirty: boolean;
  nodeLabel: (id: string) => string;
  runResult: any;
  runLive: boolean;
  obs: any;
  observing: boolean;
  liveFinished: unknown;
  streamTarget: string | null;
  ownRunIdRef: MutableRefObject<string | null>;
}) {
  const { workflowId, nodes, edges, isDirty, nodeLabel, runResult, runLive, obs, observing, liveFinished, streamTarget, ownRunIdRef } =
    opts;

  // --- Eval badges: once on load and after each run settles; hidden for the
  // session when the endpoint is missing (older core).
  const [evalSummary, setEvalSummary] = useState<WorkflowEvalSummary | null>(null);
  const evalSupportedRef = useRef(true);
  const loadEvalSummary = useCallback(() => {
    if (!evalSupportedRef.current) return;
    void api.getWorkflowEvalSummary(workflowId, 30).then((r) => {
      if (r.ok) {
        const d = r.data;
        setEvalSummary(d && typeof d === "object" && d.nodes && typeof d.nodes === "object" ? d : null);
      } else if (r.unsupported) {
        evalSupportedRef.current = false;
        setEvalSummary(null);
      }
    });
  }, [workflowId]);
  useEffect(() => {
    evalSupportedRef.current = true;
    loadEvalSummary();
  }, [loadEvalSummary]);
  const wasRunLiveRef = useRef(false);
  useEffect(() => {
    if (wasRunLiveRef.current && !runLive) loadEvalSummary();
    wasRunLiveRef.current = runLive;
  }, [runLive, loadEvalSummary]);
  // A streamed run someone else started finishing on screen also moves the numbers.
  useEffect(() => {
    if (liveFinished && streamTarget !== ownRunIdRef.current) loadEvalSummary();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only on the finish edge
  }, [liveFinished]);
  const evalNodes = evalSummary?.nodes;
  const wfEval = evalSummary?.workflow ?? null;
  const workflowEvalStats: EvalNodeStats | null = useMemo(() => {
    if (!wfEval) return null;
    const runs = typeof wfEval.runs30d === "number" ? wfEval.runs30d : wfEval.runs;
    if (!runs || runs <= 0) return null;
    return {
      runs,
      successRate: wfEval.successRate30d ?? wfEval.successRate ?? null,
      p50DurationMs: wfEval.p50DurationMs ?? null,
      avgCostUsd: wfEval.avgCostUsd ?? null,
      lastStatus: wfEval.lastRunStatus ?? null,
      evalScore: wfEval.passRate ?? null,
    };
  }, [wfEval]);

  // --- "Run this step": prefills come from this canvas's last run, else the
  // newest run in the flight log (loaded once observe has been opened).
  const [stepTestAvailable, setStepTestAvailable] = useState(true);
  const ioRun: RawWorkflowRun | null = runResult ?? obs.runs[0] ?? null;
  const lastIO = useMemo(() => {
    const out: Record<string, { input?: string; output?: string }> = {};
    for (const e of runTraceEntries(ioRun)) {
      if (!e || typeof e.nodeId !== "string") continue;
      out[e.nodeId] = {
        input: typeof e.inputJson === "string" ? e.inputJson : undefined,
        output: typeof e.outputJson === "string" ? e.outputJson : undefined,
      };
    }
    return out;
  }, [ioRun]);
  const markStepTestUnavailable = useCallback(() => setStepTestAvailable(false), []);
  const stepTestCtx: StepTestContextValue = useMemo(
    () => ({
      workflowId: String(workflowId),
      dirty: isDirty,
      nodes,
      edges,
      lastIO,
      labelOf: nodeLabel,
      available: stepTestAvailable,
      onUnavailable: markStepTestUnavailable,
    }),
    [workflowId, isDirty, nodes, edges, lastIO, nodeLabel, stepTestAvailable, markStepTestUnavailable]
  );

  // --- Data pins: node cards get pin METADATA only; the pinned text stays in the store.
  const pinsCtl = usePinsController(String(workflowId), nodeLabel);
  const pinsById = pinsCtl.ctx?.pins;
  const pinMetaById = useMemo(() => {
    const out: Record<string, PinMeta> = {};
    if (pinsById) for (const id of Object.keys(pinsById)) out[id] = toPinMeta(pinsById[id]);
    return out;
  }, [pinsById]);
  const pinCount = pinsCtl.ctx ? Object.keys(pinMetaById).length : 0;
  // Nodes a pin answered for in the run on screen (the replayed run when observing, else this canvas's last run).
  const obsModel = obs.model;
  const pinnedRunCount = useMemo(() => {
    if (observing) {
      const ids = new Set<string>();
      for (const v of obsModel?.visits ?? []) if (v.pinned) ids.add(v.nodeId);
      return ids.size;
    }
    return pinnedTraceNodeIds(runResult?.trace).length;
  }, [observing, obsModel, runResult]);

  return { evalNodes, workflowEvalStats, stepTestCtx, pinsCtl, pinMetaById, pinCount, pinnedRunCount };
}
