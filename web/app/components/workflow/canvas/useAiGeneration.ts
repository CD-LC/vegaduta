"use client";

import { useEffect, useRef, useState, type Dispatch, type MutableRefObject, type SetStateAction } from "react";
import { api } from "../../../lib/api";
import { errorMessage } from "../../../lib/errors";
import { toast } from "../../../lib/toastBus";
import { validationErrorsOf } from "../canvasHelpers";
import { takeDescribeHandoff } from "../describeHandoff";
import { fromWireGraph, toWireGraph } from "../workflowCanvasHelpers";
import {
  isPlanEmpty,
  normalizePlan,
  presetFromCron,
  shouldSendCurrentGraph,
  type DescribeMode,
  type WorkflowPlan,
} from "../workflowPlan";

type Setter = Dispatch<SetStateAction<any[]>>;

/**
 * AI workflow generator (#392): one instruction in, a full graph back. The
 * backend already ran it through the same OrchestratorService.validate() gate
 * as Save/Activate/Run, so a rejected generation surfaces through the same
 * `.errors` path as a failed Validate click.
 */
export function useAiGeneration(opts: {
  workflowId: any;
  workflow: any;
  nodes: any[];
  edges: any[];
  setNodes: Setter;
  setEdges: Setter;
  commitHistory: (coalesceMs?: number) => void;
  observingRef: MutableRefObject<boolean>;
  exitObserve: () => void;
  setValidationErrors: (e: string[] | null) => void;
  schedule: any;
  setScheduleFrequency: (v: string) => void;
  setScheduleTime: (v: string) => void;
  setScheduleDay: (v: string) => void;
  t: (key: string, values?: Record<string, unknown>) => string;
}) {
  const {
    workflowId,
    workflow,
    nodes,
    edges,
    setNodes,
    setEdges,
    commitHistory,
    observingRef,
    exitObserve,
    setValidationErrors,
    schedule,
    setScheduleFrequency,
    setScheduleTime,
    setScheduleDay,
    t,
  } = opts;

  const [aiPrompt, setAiPrompt] = useState("");
  const [aiGenerating, setAiGenerating] = useState(false);
  // A freshly-generated {nodes, edges, notes} the user hasn't applied yet - only
  // populated when the canvas already has real content worth confirming an
  // overwrite of; a graph generated onto an untouched starter canvas applies at once.
  const [aiPendingGraph, setAiPendingGraph] = useState<any>(null);
  // aiMode: "edit" sends the current graph so the builder changes it, "replace"
  // starts over. aiPlan is the last applied generation's explanation /
  // requirements / schedule (workflowPlan.ts); session-only, never persisted.
  const [aiMode, setAiMode] = useState<DescribeMode>("edit");
  const [aiPlan, setAiPlan] = useState<WorkflowPlan | null>(null);
  const [aiPlanOpen, setAiPlanOpen] = useState(false);
  const describeHandoffRef = useRef<string | null>(null);

  // The applied generation's plan stays on screen under the canvas. A suggested
  // schedule only PREFILLS the trigger strip's picker (and only when no schedule
  // is enabled yet) - nothing is switched on until the user asks for it.
  function adoptAiPlan(plan: WorkflowPlan) {
    setAiPlan(plan);
    setAiPlanOpen(!isPlanEmpty(plan));
    const preset = plan.schedule ? presetFromCron(plan.schedule.cron) : null;
    if (preset && !schedule?.enabled) {
      setScheduleFrequency(preset.frequency);
      if (preset.time) setScheduleTime(preset.time);
      if (preset.day !== undefined) setScheduleDay(preset.day);
    }
  }

  async function generateWithAi(promptOverride?: string) {
    const instruction = (typeof promptOverride === "string" ? promptOverride : aiPrompt).trim();
    if (!instruction || aiGenerating) return;
    setAiGenerating(true);
    setValidationErrors(null);
    try {
      // "Change this workflow" sends the graph on screen; core answers with the
      // complete edited graph. An untouched starter canvas or "Start over" sends none.
      const currentGraph = shouldSendCurrentGraph(aiMode, nodes, edges) ? toWireGraph(nodes, edges) : undefined;
      const result = await api.generateWorkflowGraph(workflowId, instruction, currentGraph);
      const { nodes: n, edges: e } = fromWireGraph(result.graph);
      const plan = normalizePlan(result);
      // A fresh workflow's starter graph is just one Trigger node and no edges,
      // so generating straight onto that applies immediately.
      const hasRealContent = nodes.length > 1 || edges.length > 0;
      // A generation that lands while a run is on screen must not swap the graph
      // under the replay - but parking it with the confirm dialog hidden (observe
      // suppresses it) made "Describe it, automate it" look dead. Leave the replay
      // back to the editable graph; the held graph then surfaces its confirm dialog.
      const wasObserving = observingRef.current;
      if (wasObserving) exitObserve();
      if (hasRealContent || wasObserving) {
        setAiPendingGraph({ nodes: n, edges: e, notes: result.notes || [], plan });
      } else {
        commitHistory();
        setNodes(n);
        setEdges(e);
        adoptAiPlan(plan);
        toast.success(t("aiGenerateSuccess"));
        setAiPrompt("");
      }
    } catch (err) {
      const rejected = validationErrorsOf(err);
      if (rejected) {
        setValidationErrors(rejected);
      } else {
        toast.error(errorMessage(err, t("aiGenerateError")));
      }
    } finally {
      setAiGenerating(false);
    }
  }

  function applyAiGraph() {
    if (!aiPendingGraph || observingRef.current) return;
    commitHistory();
    setNodes(aiPendingGraph.nodes);
    setEdges(aiPendingGraph.edges);
    adoptAiPlan(aiPendingGraph.plan ?? normalizePlan({ notes: aiPendingGraph.notes }));
    toast.success(t("aiGenerateSuccess"));
    setAiPendingGraph(null);
    setAiPrompt("");
  }

  // "Describe it, automate it" handoff (W3 -> W4): the Workflows page stashed
  // the sentence for this workflow id in sessionStorage. Build it once, as soon
  // as the workflow has loaded. takeDescribeHandoff clears the entry, so a
  // reload or back/forward visit never rebuilds over the user's edits.
  useEffect(() => {
    if (!workflow) return;
    const id = String(workflowId);
    if (describeHandoffRef.current === id) return;
    describeHandoffRef.current = id;
    const prompt = takeDescribeHandoff(id);
    if (!prompt || workflow.editable === false) return;
    setAiPrompt(prompt);
    void generateWithAi(prompt);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once per loaded workflow
  }, [workflow, workflowId]);

  return {
    aiPrompt,
    setAiPrompt,
    aiGenerating,
    aiPendingGraph,
    setAiPendingGraph,
    aiMode,
    setAiMode,
    aiPlan,
    aiPlanOpen,
    setAiPlanOpen,
    generateWithAi,
    applyAiGraph,
  };
}
