"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useNodesInitialized, useReactFlow } from "@xyflow/react";
import { useAuth } from "../../AuthProvider";
import { claimPaletteShortcut } from "../../../lib/paletteShortcut";
import { useNodeDefTranslations } from "../useNodeDefTranslations";
import { useStudioLayout, type StudioFitPadding } from "../useStudioLayout";
import { useTranslations } from "../../../lib/i18n";
import {
  hasRealContent as graphHasRealContent,
  pickerMatchesPreset,
  presetFromCron,
  sameSchedule,
} from "../workflowPlan";
import { useLatestRef } from "../../../lib/hooks/useLatestRef";
import { useRememberedInspectorOpen } from "../canvasValidation";

import { useCanvasHistory } from "./useCanvasHistory";
import { useClipboard } from "./useClipboard";
import { useViewport } from "./useViewport";
import { useCanvasShortcuts } from "./useCanvasShortcuts";
import { useSnapToGrid } from "./useSnapToGrid";
import { useSelection } from "./useSelection";
import { useCanvasEditing } from "./useCanvasEditing";
import { useConnections } from "./useConnections";
import { useGraphPersistence } from "./useGraphPersistence";
import { useWorkflowSchedule } from "./useWorkflowSchedule";
import { useDiagramExport } from "./useDiagramExport";
import { useIntelligenceSettings } from "./useIntelligenceSettings";
import { useRunModal } from "./useRunModal";
import { useRunInspectorState } from "./useRunInspectorState";
import { useRunObservation } from "./useRunObservation";
import { useRunExecution } from "./useRunExecution";
import { useWorkflowLint } from "./useWorkflowLint";
import { useLintFixes } from "./useLintFixes";
import { useAiGeneration } from "./useAiGeneration";
import { useCanvasDisplay } from "./useCanvasDisplay";
import { useRunInsights } from "./useRunInsights";
import { useCanvasAlerts } from "./useCanvasAlerts";

/**
 * Every piece of state and behaviour behind the workflow canvas, wired
 * together: each concern is its own hook in this folder, and this one only
 * connects them (shared graph refs, run state, studio layout) and hands the
 * result to WorkflowCanvas.tsx, which lays the studio out. Must run inside a
 * ReactFlowProvider.
 */
export function useWorkflowCanvasController({ workflowId, focusMode }: { workflowId: string; focusMode: boolean }) {
  const router = useRouter();
  const push = useCallback((href: string) => router.push(href), [router]);
  const reactFlowWrapper = useRef(null);
  const { screenToFlowPosition, zoomIn, zoomOut, fitView, setCenter } = useReactFlow();
  const { hasPermission } = useAuth();
  const canManageWorkflows = hasPermission("MANAGE_WORKFLOWS_ADMIN");
  const tNodeDefs = useNodeDefTranslations();
  const t = useTranslations("workflowCanvas");
  // The graph-load effect must not re-run when the translator changes (a late
  // catalog would reload the graph and wipe unsaved edits), so it reads t here.
  const tRef = useLatestRef(t);
  // The cost-routing pickers reuse AgentWorkbench's catalog-picker strings.
  const tAw = useTranslations("agentWorkbench");
  const tDescribe = useTranslations("workflowDescribe");

  const [workflow, setWorkflow] = useState(null);
  const [busy, setBusy] = useState(false);
  // Only the initial "could not load this workflow" failure; every other
  // action's success/failure surfaces as a toast (#346: no layout shifts).
  const [error, setError] = useState(null);
  const [nodes, setNodes] = useState<any[]>([]);
  const [edges, setEdges] = useState<any[]>([]);
  // Latest graph for callbacks/listeners that must not re-create (or go stale)
  // on every drag frame. Synced after each commit.
  const nodesRef = useRef<any[]>([]);
  const edgesRef = useRef<any[]>([]);
  useEffect(() => {
    nodesRef.current = nodes;
    edgesRef.current = edges;
  }, [nodes, edges]);
  // Per-side fitView padding from the studio layout; a ref because the fit
  // helpers are plain functions declared before the layout hook runs.
  const fitPaddingRef = useRef<StudioFitPadding | number>(0.3);
  // Plain messages off a rejected save/validate response's `.errors`, and the
  // nodes a failed Validate named (SE-26); cleared on the next validate/run/save.
  const [validationErrors, setValidationErrors] = useState<string[] | null>(null);
  const [invalidNodeIds, setInvalidNodeIds] = useState<ReadonlySet<string>>(new Set());
  // The run on screen. runLive is true from Run being dispatched until the run
  // reaches a terminal status.
  const [runResult, setRunResult] = useState(null);
  const [runLive, setRunLive] = useState(false);
  // Mobile: palette and inspector are off-canvas drawers below 900px (see the
  // .ax-wf-palette-open / .ax-wf-inspector-open rules). Desktop is unaffected.
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [inspectorOpen, setInspectorOpen] = useRememberedInspectorOpen(workflowId);
  const [showShareModal, setShowShareModal] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [commentsOpen, setCommentsOpen] = useState(false);
  const [zoomPct, setZoomPct] = useState(100);
  const [showShortcuts, setShowShortcuts] = useState(false);
  const toggleShortcuts = useCallback(() => setShowShortcuts((v) => !v), []);
  // Banner "Decide" -> ObserveOverlay opens and focuses the decision card.
  const [decideRequest, setDecideRequest] = useState(0);
  // Server-only lint checks (agent refs, provider keys) re-run when this bumps.
  const [lintNonce, setLintNonce] = useState(0);
  const bumpLint = useCallback(() => setLintNonce((n) => n + 1), []);
  // ⌘K / Ctrl+K node command palette (WorkflowCommandPalette): add a node from
  // anywhere on the canvas without reaching for the left rail.
  const [cmdOpen, setCmdOpen] = useState(false);

  // --- Concerns, one hook each (./canvas/) ---------------------------------
  const { selectedNodeId, setSelectedNodeId, selectedNode, triggerNodeCount, focusNodeFromLint, clearSelection } =
    useSelection({ nodes, nodesRef, setNodes, setCenter, setInspectorOpen });
  const {
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
  } = useRunObservation({ workflowId, nodes, edges, tNodeDefs, runResult, runLive, setSelectedNodeId });
  // The canvas CLAIMS the chord (capture phase, see lib/paletteShortcut.ts) so
  // AppShell's global "Jump to..." palette does not open alongside this one.
  // Observe mode is read-only: it stands down and the global palette opens.
  useEffect(
    () =>
      claimPaletteShortcut(
        () => setCmdOpen((v) => !v),
        () => !observingRef.current
      ),
    [observingRef]
  );
  // Undo/redo (Command pattern, see ./canvas/commands.ts)
  const { historyRef, commitHistory, undo, redo, resetHistory } = useCanvasHistory({
    nodesRef,
    edgesRef,
    setNodes,
    setEdges,
    observingRef,
  });

  const { onNodesChange, onEdgesChange, onNodeDragStart, addNode, addNodeByClick, onDragOver, onDrop, updateNode, deleteNode, deleteEdge } =
    useCanvasEditing({ nodesRef, setNodes, setEdges, setSelectedNodeId, commitHistory, tNodeDefs, screenToFlowPosition });
  const { onConnect, onConnectEnd, onInsertOnEdge, clearPaletteMode, handlePalettePick, setPendingConnection, setInsertEdgeId } =
    useConnections({
      nodesRef,
      edgesRef,
      setNodes,
      setEdges,
      setSelectedNodeId,
      setCmdOpen,
      commitHistory,
      addNode,
      addNodeByClick,
      tNodeDefs,
      screenToFlowPosition,
    });
  const { snapToGrid, toggleSnap } = useSnapToGrid();
  const { copySelected, pasteCopied, duplicateSelected } = useClipboard({
    nodesRef,
    edgesRef,
    setNodes,
    setEdges,
    setSelectedNodeId,
    commitHistory,
  });
  const { fitToScreen, tidyLayout } = useViewport({ nodesRef, edgesRef, setNodes, commitHistory, fitPaddingRef });
  useCanvasShortcuts({ undo, redo, copySelected, pasteCopied, duplicateSelected, tidyLayout, fitToScreen }, observingRef);

  const { graphJson, lintIssues, isDirty, autosaveState, persistDraftUnlessClean, save, validate, handleDuplicate } =
    useGraphPersistence({
      workflowId,
      workflow,
      setWorkflow,
      nodes,
      edges,
      setNodes,
      setEdges,
      nodesRef,
      edgesRef,
      resetHistory,
      observing,
      busy,
      setBusy,
      setError,
      runLive,
      setValidationErrors,
      setInvalidNodeIds,
      bumpLint,
      t,
      tRef,
      push,
    });
  const sched = useWorkflowSchedule({ workflowId, t });
  const { schedule, scheduleFrequency, setScheduleFrequency, scheduleTime, setScheduleTime, scheduleDay, setScheduleDay, scheduleBusy } = sched;
  const diagram = useDiagramExport({ workflowId, workflow, t });
  const intelligence = useIntelligenceSettings({ workflowId, workflow, setWorkflow, t });

  // --- Running ---------------------------------------------------------------
  const runModal = useRunModal({ t });
  const inspectorState = useRunInspectorState({ workflowId, runResult, runLive, t });
  const liveRunObj = liveIsTarget ? live.state.run : null;
  const { trace, cancelBusy, pauseBusy, runWithRuntimeInputs, stopRun, pauseRun, resumeRun, onAwaitingResolved } =
    useRunExecution({
      workflowId,
      t,
      tDescribe,
      runResult,
      setRunResult,
      runLive,
      setRunLive,
      setBusy,
      setError,
      setValidationErrors,
      setInvalidNodeIds,
      persistDraftUnlessClean,
      simulateSends: runModal.simulateSends,
      buildRunInputPayload: runModal.buildRunInputPayload,
      closeRunModal: runModal.closeRunModal,
      setExpandedTrace: inspectorState.setExpandedTrace,
      setInspectorTab: inspectorState.setInspectorTab,
      setRunHistory: inspectorState.setRunHistory,
      ownRunIdRef,
      streamRef,
      streamTarget,
      setStreamTarget,
      streamOpen,
      liveRunObj,
      enterObserve,
      refreshSelected: obs.refreshSelected,
      obsRunId: obs.selectedRunId,
    });

  // --- AI generator -----------------------------------------------------------
  const ai = useAiGeneration({
    workflowId,
    workflow,
    nodes,
    edges,
    setNodes,
    setEdges,
    commitHistory,
    observingRef,
    exitObserve: obs.exitObserve,
    setValidationErrors,
    schedule,
    setScheduleFrequency,
    setScheduleTime,
    setScheduleDay,
    t,
  });
  const { aiPlan, aiPlanOpen, setAiPlanOpen } = ai;

  // --- Lint, insights, display ---------------------------------------------------
  const { allLint, lintLoading, lintServerAvailable, exprLint } = useWorkflowLint({
    workflowId,
    nodes,
    nodesRef,
    graphJson,
    lintIssues,
    observing,
    lintNonce,
  });
  const applyLintFix = useLintFixes({
    nodesRef,
    edgesRef,
    observingRef,
    setNodes,
    setEdges,
    commitHistory,
    deleteEdge,
    addNode,
    focusNodeFromLint,
    setInsertEdgeId,
    setPendingConnection,
    setCmdOpen,
    bumpLint,
    push,
    tDescribe,
  });
  const { evalNodes, workflowEvalStats, stepTestCtx, pinsCtl, pinMetaById, pinCount, pinnedRunCount } = useRunInsights({
    workflowId,
    nodes,
    edges,
    isDirty,
    nodeLabel,
    runResult,
    runLive,
    obs,
    observing,
    liveFinished: liveIsTarget ? live.state.finished : null,
    streamTarget,
    ownRunIdRef,
  });
  const { displayNodes, displayEdges, runningNodeIds, streamOnScreen, setDiffDeco, tourOpen, tourSteps, focusTourNode, startTour, exitTour } =
    useCanvasDisplay({
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
    });
  // The drawer forwards this to VersionDiffPanel's onDecorations once it declares the prop.
  const diffDrawerProps = { onDecorations: setDiffDeco };

  // Stop Run is offered exactly while a run is executing. `runLive` alone is
  // not enough: on the Temporal engine the POST can return early on a timeout
  // while the run keeps executing durably, so RUNNING covers that gap. PAUSED
  // counts too: a parked run is still cancellable, so Stop stays offered rather
  // than stranding it with no way out but Resume.
  const runIsStoppable = runLive || runResult?.status === "RUNNING" || runResult?.status === "PAUSED";

  // Studio layout: full-bleed canvas with floating, resizable, collapsible glass
  // panels - see useStudioLayout. Called before the early return below so hook
  // order never changes. The inspector appears only when it has something to
  // show: a selected node, a run that can still be stopped, or a run result the
  // user hasn't closed yet. Closing it deselects / dismisses that run.
  const [dismissedRunId, setDismissedRunId] = useState<string | null>(null);
  const inspectorNeeded =
    (!observing && selectedNodeId != null) ||
    runIsStoppable ||
    (!observing && runResult != null && runResult.id !== dismissedRunId);
  const studio = useStudioLayout({
    leftHidden: observing,
    rightHidden: observing && !runIsStoppable,
    selectedNodeId: observing ? null : selectedNodeId,
    rightNeeded: inspectorNeeded,
  });
  const onToggleInspector = () => {
    if (studio.rightMode === "open") {
      clearSelection();
      if (runResult && !runIsStoppable) setDismissedRunId(runResult.id);
      studio.unpinRight();
    } else {
      studio.expandRight();
    }
  };
  studio.rightToggleOverride.current = onToggleInspector;
  fitPaddingRef.current = studio.fitPadding;
  // One fit once the saved panel layout is known, so the first view frames
  // the graph between the panels (React Flow's own mount-time fit runs
  // before localStorage is read). Later panel toggles never move the camera.
  // Gated on useNodesInitialized: a fit before React Flow has measured the
  // nodes has no bounds to frame and silently does nothing.
  const nodesMeasured = useNodesInitialized();
  const didStudioFit = useRef(false);
  useEffect(() => {
    if (focusMode || didStudioFit.current || !studio.ready || !nodesMeasured || nodes.length === 0) return;
    didStudioFit.current = true;
    void fitView({ maxZoom: 1, padding: studio.fitPadding, duration: 0 });
  }, [focusMode, studio.ready, studio.fitPadding, nodesMeasured, nodes.length, fitView]);

  const { alerts, showAlertsPopover, setShowAlertsPopover, alertsPopoverRef } = useCanvasAlerts({
    workflow,
    schedule,
    canManageWorkflows,
    t,
  });

  // nlflow W4 render-time derivations for the describe bar and plan panel.
  const canvasHasContent = graphHasRealContent(nodes, edges);
  const suggestedScheduleOn =
    !!aiPlan?.schedule && !!schedule?.enabled && sameSchedule(schedule?.cron, aiPlan.schedule.cron);
  // The strip's "save the schedule" hint is only shown while its picker still holds the suggested preset;
  // a cron the picker cannot express is switched on from the plan panel, which sends the exact cron.
  const stripShowsSuggestion =
    !!aiPlan?.schedule &&
    !suggestedScheduleOn &&
    pickerMatchesPreset(presetFromCron(aiPlan.schedule.cron), scheduleFrequency, scheduleTime, scheduleDay);
  const turnOnSuggestedSchedule = () => {
    void sched.turnOnSuggestedSchedule(aiPlan?.schedule?.cron);
  };

  return {
    // workflow + chrome state
    workflow, setWorkflow, error, busy, t, tAw, tDescribe, tNodeDefs, canManageWorkflows, reactFlowWrapper,
    paletteOpen, setPaletteOpen, inspectorOpen, setInspectorOpen, showShareModal, setShowShareModal,
    historyOpen, setHistoryOpen, commentsOpen, setCommentsOpen, zoomPct, setZoomPct, showShortcuts, toggleShortcuts,
    decideRequest, setDecideRequest, cmdOpen, setCmdOpen, validationErrors, setValidationErrors,
    alerts, showAlertsPopover, setShowAlertsPopover, alertsPopoverRef,
    // graph editing
    nodes, edges, selectedNodeId, setSelectedNodeId, selectedNode, triggerNodeCount, focusNodeFromLint,
    historyRef, undo, redo, onNodesChange, onEdgesChange, onNodeDragStart, addNodeByClick, onDragOver, onDrop,
    updateNode, deleteNode, deleteEdge, onConnect, onConnectEnd, clearPaletteMode, handlePalettePick,
    snapToGrid, toggleSnap, copySelected, pasteCopied, duplicateSelected, tidyLayout, fitView, zoomIn, zoomOut,
    isDirty, autosaveState, save, validate, handleDuplicate,
    // observe + run
    graph, nodeLabel, live, streamOpen, obs, observing, observeActions, enterObserve, openLiveNode, awaiting,
    runResult, runLive, runIsStoppable, trace, cancelBusy, pauseBusy, runWithRuntimeInputs, stopRun, pauseRun,
    resumeRun, onAwaitingResolved, runModal, inspectorState,
    // schedule, diagram, intelligence, AI
    sched, schedule, scheduleFrequency, setScheduleFrequency, scheduleTime, setScheduleTime, scheduleDay,
    setScheduleDay, scheduleBusy, diagram, intelligence, ai, aiPlan, aiPlanOpen, setAiPlanOpen,
    canvasHasContent, suggestedScheduleOn, stripShowsSuggestion, turnOnSuggestedSchedule,
    // lint, insights, display
    allLint, lintLoading, lintServerAvailable, exprLint, applyLintFix, workflowEvalStats, stepTestCtx, pinsCtl,
    pinCount, pinnedRunCount, displayNodes, displayEdges, runningNodeIds, streamOnScreen, setDiffDeco,
    diffDrawerProps, tourOpen, tourSteps, focusTourNode, startTour, exitTour,
    // studio layout
    studio, onToggleInspector,
  };
}

export type WorkflowCanvasController = ReturnType<typeof useWorkflowCanvasController>;
