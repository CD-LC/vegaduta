"use client";

import { ReactFlowProvider } from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { WorkflowEdgeDeleteContext } from "./DeletableEdge";
import WorkflowLintChip from "./WorkflowLintChip";
import { canRunDraft } from "./canvasRun";
import { NodeLiveOpenContext } from "./runOverlay";
import { ExpressionLintContext } from "./ExpressionField";
import EvalBadge from "./EvalBadge";
import { StepTestContext } from "./StepTestSlot";
import { PinsContext } from "./PinsContext";
import { DraftRunChip } from "./PinRunSummary";
import { ObserveBanner, ObserveDock, ObserveOverlay, ObserveRail } from "./RunObserver";
import StudioPanel from "./StudioPanel";
import { StudioPaletteRail } from "./StudioRails";
import { LEFT_LIMITS, RIGHT_LIMITS } from "./useStudioLayout";
import "./workflowStudioStyles";
import WorkflowTopbar from "./WorkflowTopbar";
import WorkflowTriggerStrip from "./WorkflowTriggerStrip";
import WorkflowPalette from "./WorkflowPalette";
import WorkflowCanvasZone from "./WorkflowCanvasZone";
import WorkflowInspector from "./WorkflowInspector";
import WorkflowValidationDialog from "./WorkflowValidationDialog";
import WorkflowAiPendingDialog from "./WorkflowAiPendingDialog";
import WorkflowPlanPanel from "./WorkflowPlanPanel";
import { countSimulatedSends, DRY_RUN_MODE } from "./workflowPlan";
import WorkflowRunModal from "./WorkflowRunModal";
import WorkflowDiagramModal from "./WorkflowDiagramModal";
import WorkflowIntelligenceModal from "./WorkflowIntelligenceModal";
import WorkflowShareModal from "./WorkflowShareModal";
import WorkflowVersionsDrawer from "./WorkflowVersionsDrawer";
import WorkflowCommentsHost from "./WorkflowCommentsHost";

import { useWorkflowCanvasController } from "./canvas/useWorkflowCanvasController";
import DescribeBar from "./canvas/DescribeBar";
import EditLockNotice from "./canvas/EditLockNotice";
import "./canvas/canvas-diff.css";

// Run decoration (interned overlays, edge labels) lives in runOverlay.ts and
// ./canvas/useCanvasDisplay.ts; module constants in ./canvas/constants.ts.
// Each concern of this screen is a hook in ./canvas/, wired together by
// ./canvas/useWorkflowCanvasController.ts; this file only lays the studio out.

/**
 * The Interactive Workflow Designer canvas - drag nodes in from the palette,
 * wire them together, then Validate/Activate/Run against the real backend
 * (WorkflowController.java). Every save round-trips the exact @xyflow/react
 * {nodes, edges} shape with the backend's WorkflowGraphDto, so there is no
 * client/server schema translation layer to keep in sync.
 */
function CanvasInner({
  workflowId,
  agents,
  collections,
  devices,
  onBack,
  focusMode = false,
}: {
  workflowId: any;
  agents: any;
  collections: any;
  devices: any;
  onBack: any;
  focusMode?: any;
}) {
  const c = useWorkflowCanvasController({ workflowId, focusMode });
  const {
    workflow, setWorkflow, error, busy, t, tAw, tDescribe, tNodeDefs, canManageWorkflows, reactFlowWrapper,
    paletteOpen, setPaletteOpen, inspectorOpen, setInspectorOpen, showShareModal, setShowShareModal,
    historyOpen, setHistoryOpen, commentsOpen, setCommentsOpen, zoomPct, setZoomPct, showShortcuts, toggleShortcuts,
    decideRequest, setDecideRequest, cmdOpen, setCmdOpen, validationErrors, setValidationErrors,
    alerts, showAlertsPopover, setShowAlertsPopover, alertsPopoverRef,
  } = c;
  const {
    nodes, edges, selectedNode, triggerNodeCount, setSelectedNodeId, focusNodeFromLint, historyRef, undo, redo,
    onNodesChange, onEdgesChange, onNodeDragStart, addNodeByClick, onDragOver, onDrop, updateNode, deleteNode,
    deleteEdge, onConnect, onConnectEnd, clearPaletteMode, handlePalettePick, snapToGrid, toggleSnap, copySelected,
    pasteCopied, duplicateSelected, tidyLayout, fitView, zoomIn, zoomOut, isDirty, autosaveState, save, validate,
    handleDuplicate,
  } = c;
  const {
    graph, nodeLabel, live, streamOpen, obs, observing, observeActions, enterObserve, openLiveNode, awaiting,
    runResult, runLive, runIsStoppable, trace, cancelBusy, pauseBusy, runWithRuntimeInputs, stopRun, pauseRun,
    resumeRun, onAwaitingResolved, runModal,
  } = c;
  const {
    schedule, scheduleFrequency, setScheduleFrequency, scheduleTime, setScheduleTime, scheduleDay, setScheduleDay,
    scheduleBusy, diagram, intelligence, ai, aiPlan, aiPlanOpen, setAiPlanOpen, suggestedScheduleOn,
    stripShowsSuggestion, turnOnSuggestedSchedule, canvasHasContent,
  } = c;
  const {
    allLint, lintLoading, lintServerAvailable, exprLint, applyLintFix, workflowEvalStats, stepTestCtx, pinsCtl,
    pinCount, pinnedRunCount, displayNodes, displayEdges, runningNodeIds, streamOnScreen, setDiffDeco,
    diffDrawerProps, tourOpen, tourSteps, focusTourNode, startTour, exitTour, studio, onToggleInspector,
  } = c;
  const {
    elapsedSeconds,
    downloadRunOutput,
    downloading,
    expandedTrace,
    setExpandedTrace,
    exportRunLogs,
    inspectorTab,
    setInspectorTab,
    loadRunHistory,
    loadMoreRunHistory,
    runHistory,
    runHistoryLoading,
    runHistoryMeta,
  } = c.inspectorState;
  const { openRunModal, openTestRun } = runModal;
  const { simulateSends } = runModal;
  const { generateWithAi, applyAiGraph, aiPrompt, aiGenerating, aiMode, aiPendingGraph, setAiPendingGraph } = ai;
  const { applySchedule, turnOffSchedule } = c.sched;
  const { openDiagramModal } = diagram;
  const { openIntelligenceModal } = intelligence;

  if (!workflow) {
    return error ? (
      <div className="m-4 rounded-xl border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
        {error}
      </div>
    ) : (
      <p className="p-4 text-sm text-muted-foreground">{t("loadingWorkflow")}</p>
    );
  }

  return (
    <PinsContext.Provider value={pinsCtl.ctx}>
      <div
        className={`ax-wf-shell${focusMode ? " ax-wf-shell-immersive" : " ax-wf-studio"}${studio.zen ? " ax-wf-zen" : ""}`}
        data-tour="canvas"
        data-left={studio.leftMode}
        data-right={studio.rightMode}
        data-free={studio.freeSize}
        style={focusMode ? undefined : studio.cssVars}
      >
        <WorkflowTopbar
          alerts={alerts}
          alertsPopoverRef={alertsPopoverRef}
          canComment={workflow.editable !== false && !observing} commentsOpen={commentsOpen} historyOpen={historyOpen}
          onToggleComments={() => setCommentsOpen((v) => !v)} onToggleHistory={() => setHistoryOpen((v) => !v)}
          autosaveState={autosaveState}
          busy={busy}
          exitTour={exitTour}
          focusMode={focusMode}
          handleDuplicate={handleDuplicate}
          historyRef={historyRef}
          inspectorOpen={inspectorOpen}
          isDirty={isDirty}
          nodes={nodes}
          onBack={onBack}
          openDiagramModal={openDiagramModal}
          openIntelligenceModal={openIntelligenceModal}
          openRunModal={openRunModal}
          paletteOpen={paletteOpen}
          studio={focusMode ? null : { ...studio, toggleRight: onToggleInspector }}
          readOnly={observing}
          redo={redo}
          save={save}
          setInspectorOpen={setInspectorOpen}
          setPaletteOpen={setPaletteOpen}
          setShowAlertsPopover={setShowAlertsPopover}
          setShowShareModal={setShowShareModal}
          showAlertsPopover={showAlertsPopover}
          startTour={startTour}
          t={t}
          tourOpen={tourOpen}
          undo={undo}
          validate={validate}
          workflow={workflow}
          workflowId={workflowId}
        />

        <div className="ax-wf-notices">
          {workflow.editable === false && <EditLockNotice workflowId={workflowId} setWorkflow={setWorkflow} t={t} />}

          <WorkflowTriggerStrip
            applySchedule={applySchedule}
            canManageWorkflows={canManageWorkflows}
            schedule={schedule}
            scheduleBusy={scheduleBusy}
            scheduleDay={scheduleDay}
            scheduleFrequency={scheduleFrequency}
            scheduleTime={scheduleTime}
            setScheduleDay={setScheduleDay}
            setScheduleFrequency={setScheduleFrequency}
            setScheduleTime={setScheduleTime}
            scheduleSuggestion={
              aiPlan?.schedule && stripShowsSuggestion
                ? tDescribe("planScheduleStripHint", { label: aiPlan.schedule.label })
                : null
            }
            t={t}
            turnOffSchedule={turnOffSchedule}
            workflow={workflow}
          />
        </div>

        <div className="ax-wf-main">
          {/* Mobile drawer backdrops (see the paletteOpen/inspectorOpen state
           * comment in ./canvas/useWorkflowCanvasController.ts) - only mounted while the corresponding drawer is
           * open, same "costs nothing when closed" pattern as AppShell.js's
           * .ax-rail-backdrop. CSS-hidden entirely above the 900px breakpoint
           * (.ax-wf-drawer-backdrop's own display:none default), so these
           * never dim anything on desktop even while `paletteOpen`/
           * `inspectorOpen` happen to be true from a since-resized window. */}
          {paletteOpen && (
            <div className="ax-wf-drawer-backdrop" onClick={() => setPaletteOpen(false)} aria-hidden="true" />
          )}
          {inspectorOpen && (!observing || runIsStoppable) && (
            <div className="ax-wf-drawer-backdrop" onClick={() => setInspectorOpen(false)} aria-hidden="true" />
          )}
          {/* Observe mode is read-only, so the node palette yields its column to the flight log. */}
          {!observing && (
            <StudioPanel
              side="left"
              mode={studio.leftMode}
              width={studio.leftWidth}
              minWidth={LEFT_LIMITS.min}
              maxWidth={LEFT_LIMITS.max}
              defaultWidth={LEFT_LIMITS.initial}
              onResize={studio.setLeftWidth}
              onToggle={studio.toggleLeft}
              label={t("studioPaletteLabel")}
              collapseLabel={t("studioCollapsePalette")}
              expandLabel={t("studioExpandPalette")}
              resizeLabel={t("studioResizePalette")}
              header={
                <span className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                  {t("studioPaletteTitle")}
                </span>
              }
              rail={<StudioPaletteRail addNodeByClick={addNodeByClick} tNodeDefs={tNodeDefs} />}
            >
              <WorkflowPalette
                addNodeByClick={addNodeByClick}
                paletteOpen={paletteOpen}
                setPaletteOpen={setPaletteOpen}
                tNodeDefs={tNodeDefs}
              />
            </StudioPanel>
          )}

          {/* Canvas + describe-bar stack in their own column; the AI bar is a normal row below the graph. */}
          <div className="ax-wf-canvas-col" style={focusMode ? { position: "relative" } : undefined}>
            {observing ? (
              <ObserveBanner
                obs={obs}
                onBackToEdit={obs.exitObserve}
                streaming={streamOnScreen && streamOpen}
                liveCostUsd={streamOnScreen ? (live.state.runTotals?.costUsd ?? null) : null}
                onDecide={awaiting ? () => setDecideRequest((n) => n + 1) : undefined}
              />
            ) : (
              <WorkflowLintChip
                issues={allLint}
                onSelectNode={focusNodeFromLint}
                nodeLabel={nodeLabel}
                onApplyFix={applyLintFix}
                loading={lintLoading}
                serverAvailable={lintServerAvailable}
              />
            )}
            {/* DeletableEdge's x button reaches back up here through context
             * rather than prop-drilling through the ReactFlow config - the
             * provider only needs to wrap the zone the edges render inside. */}
            <div className="rob-stage">
              {/* A green draft run that used pins is not a real end-to-end run: say so, persistently. */}
              {pinnedRunCount > 0 && (
                <div className="wfx-pin-runbar">
                  <DraftRunChip count={pinnedRunCount} />
                </div>
              )}
              <WorkflowEdgeDeleteContext.Provider value={deleteEdge}>
                <NodeLiveOpenContext.Provider value={observing ? openLiveNode : null}>
                  <WorkflowCanvasZone
                    readOnly={observing}
                    onObserveNodeClick={observeActions.openNode}
                    onObservePaneClick={() => obs.setSelectedVisit(null)}
                    onToggleRuns={observing ? obs.exitObserve : () => enterObserve()}
                    runsActive={observing}
                    overlay={
                      observing ? (
                        <ObserveOverlay
                          obs={obs}
                          workflowId={workflowId}
                          nodeLabel={nodeLabel}
                          awaiting={awaiting}
                          onAwaitingResolved={onAwaitingResolved}
                          decideRequest={decideRequest}
                        />
                      ) : null
                    }
                    titleExtra={
                      workflowEvalStats ? (
                        <span
                          style={{
                            display: "inline-flex",
                            verticalAlign: "middle",
                            marginLeft: 8,
                            pointerEvents: "auto",
                            letterSpacing: "normal",
                          }}
                        >
                          <span className="sr-only">Last 30 days: </span>
                          <EvalBadge stats={workflowEvalStats} compact />
                        </span>
                      ) : null
                    }
                    cmdOpen={cmdOpen}
                    displayEdges={displayEdges}
                    displayNodes={displayNodes}
                    edges={edges}
                    exitTour={exitTour}
                    fitView={fitView}
                    fitPadding={focusMode ? undefined : studio.fitPadding}
                    focusTourNode={focusTourNode}
                    handlePalettePick={handlePalettePick}
                    nodes={nodes}
                    onConnect={onConnect}
                    onConnectEnd={onConnectEnd}
                    onDragOver={onDragOver}
                    onDrop={onDrop}
                    onEdgesChange={onEdgesChange}
                    onNodeDragStart={onNodeDragStart}
                    onNodesChange={onNodesChange}
                    reactFlowWrapper={reactFlowWrapper}
                    runLive={runLive}
                    runResult={runResult}
                    setCmdOpen={setCmdOpen}
                    setInspectorOpen={setInspectorOpen}
                    setPendingConnection={clearPaletteMode}
                    onAutoLayout={tidyLayout}
                    snapToGrid={snapToGrid}
                    onToggleSnap={toggleSnap}
                    onCopy={copySelected}
                    onPaste={pasteCopied}
                    onDuplicate={duplicateSelected}
                    showShortcuts={showShortcuts}
                    onToggleShortcuts={toggleShortcuts}
                    setSelectedNodeId={setSelectedNodeId}
                    setZoomPct={setZoomPct}
                    t={t}
                    tourOpen={tourOpen}
                    tourSteps={tourSteps}
                    zoomIn={zoomIn}
                    zoomOut={zoomOut}
                    zoomPct={zoomPct}
                  />
                </NodeLiveOpenContext.Provider>
              </WorkflowEdgeDeleteContext.Provider>
              <WorkflowCommentsHost workflowId={workflowId} open={commentsOpen && !observing} onClose={() => setCommentsOpen(false)} nodeLabel={nodeLabel} onFocusNode={focusNodeFromLint} />
              {observing && (
                <ObserveRail
                  obs={obs}
                  nodeLabel={nodeLabel}
                  runEdges={graph.runEdges}
                  onRunNow={canRunDraft(workflow) ? openRunModal : undefined}
                />
              )}
            </div>

            {/* AI workflow generator (#392 - direct feedback: "customer should
             * be able to type what he wants and automatically it should get
             * [built] with the help of AI using available tools, mcp,
             * condition, output, Human in loop approval everything"). Relocated
             * below the flow graph, out of the canvas itself - see the comment
             * above .ax-wf-canvas-col for why. In observe mode the flight
             * recorder dock takes this slot. */}
            {observing ? (
              <ObserveDock obs={obs} nodeLabel={nodeLabel} runEdges={graph.runEdges} onClose={obs.exitObserve} />
            ) : (
              <>
                {aiPlan && aiPlanOpen && (
                  <WorkflowPlanPanel
                    plan={aiPlan}
                    onFocusNode={focusNodeFromLint}
                    nodeExists={(id) => nodes.some((n) => n.id === id)}
                    onClose={() => setAiPlanOpen(false)}
                    workflowActive={workflow.status === "ACTIVE"}
                    canManageSchedule={canManageWorkflows}
                    scheduleBusy={scheduleBusy}
                    scheduleOn={suggestedScheduleOn}
                    onTurnOnSchedule={turnOnSuggestedSchedule}
                    onTestRun={canRunDraft(workflow) && aiPlan.testRunSupported ? openTestRun : undefined}
                    testRunBusy={busy}
                    dryRunSends={runResult?.mode === DRY_RUN_MODE ? countSimulatedSends(runResult.trace) : null}
                  />
                )}
                <DescribeBar
                  aiPrompt={aiPrompt}
                  setAiPrompt={ai.setAiPrompt}
                  aiGenerating={aiGenerating}
                  aiMode={aiMode}
                  setAiMode={ai.setAiMode}
                  aiPlan={aiPlan}
                  aiPlanOpen={aiPlanOpen}
                  setAiPlanOpen={setAiPlanOpen}
                  canvasHasContent={canvasHasContent}
                  generateWithAi={generateWithAi}
                  t={t}
                  tDescribe={tDescribe}
                />
              </>
            )}
          </div>

          {/* While observing a finished run the flight log + inspector card
           * replace this panel; it stays for a live run (Stop / Pause). */}
          {(!observing || runIsStoppable) && (
            <StudioPanel
              side="right"
              mode={studio.rightMode}
              width={studio.rightWidth}
              minWidth={RIGHT_LIMITS.min}
              maxWidth={studio.rightMax}
              defaultWidth={RIGHT_LIMITS.initial}
              onResize={studio.setRightWidth}
              onToggle={onToggleInspector}
              label={t("studioInspectorLabel")}
              collapseLabel={t("studioCollapseInspector")}
              expandLabel={t("studioExpandInspector")}
              resizeLabel={t("studioResizeInspector")}
              header={
                <span className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                  {selectedNode && !observing ? t("studioNodeSettings") : t("studioRunTitle")}
                </span>
              }
            >
              <StepTestContext.Provider value={stepTestCtx}>
                <ExpressionLintContext.Provider value={exprLint.state}>
                  <WorkflowInspector
                    agents={agents}
                    cancelBusy={cancelBusy}
                    collections={collections}
                    deleteNode={deleteNode}
                    devices={devices}
                    downloadRunOutput={downloadRunOutput}
                    downloading={downloading}
                    elapsedSeconds={elapsedSeconds}
                    expandedTrace={expandedTrace}
                    exportRunLogs={exportRunLogs}
                    inspectorOpen={inspectorOpen}
                    inspectorTab={inspectorTab}
                    loadRunHistory={loadRunHistory}
                    nodes={nodes}
                    onOpenRun={enterObserve}
                    pauseBusy={pauseBusy}
                    pauseRun={pauseRun}
                    resumeRun={resumeRun}
                    runHistory={runHistory}
                    runHistoryLoading={runHistoryLoading}
                    runHistoryMeta={runHistoryMeta}
                    loadMoreRunHistory={loadMoreRunHistory}
                    runIsStoppable={runIsStoppable}
                    runLive={runLive}
                    runResult={runResult}
                    runningNodeIds={runningNodeIds}
                    selectedNode={observing ? null : selectedNode}
                    setExpandedTrace={setExpandedTrace}
                    setInspectorOpen={setInspectorOpen}
                    setInspectorTab={setInspectorTab}
                    setSelectedNodeId={setSelectedNodeId}
                    stopRun={stopRun}
                    t={t}
                    tNodeDefs={tNodeDefs}
                    trace={trace}
                    triggerNodeCount={triggerNodeCount}
                    updateNode={updateNode}
                    workflowId={workflowId}
                  />
                </ExpressionLintContext.Provider>
              </StepTestContext.Provider>
            </StudioPanel>
          )}
        </div>

        <WorkflowValidationDialog setValidationErrors={setValidationErrors} t={t} validationErrors={validationErrors} />

        <WorkflowAiPendingDialog
          aiPendingGraph={observing ? null : aiPendingGraph}
          applyAiGraph={applyAiGraph}
          setAiPendingGraph={setAiPendingGraph}
          t={t}
        />

        <WorkflowRunModal
          {...runModal.modalProps}
          busy={busy}
          runWithRuntimeInputs={runWithRuntimeInputs}
          pinCount={pinCount}
          dryRunNote={simulateSends ? tDescribe("planTestRunModalNote") : null}
          t={t}
        />

        <WorkflowDiagramModal {...diagram.modalProps} t={t} />

        <WorkflowIntelligenceModal {...intelligence.modalProps} t={t} tAw={tAw} workflow={workflow} />

        {historyOpen && (
          <WorkflowVersionsDrawer workflowId={workflowId} canvasDirty={isDirty} canWrite={workflow.editable !== false && !observing} onFocusNode={focusNodeFromLint} {...diffDrawerProps} onClose={() => { setDiffDeco(null); setHistoryOpen(false); }} />
        )}

        <WorkflowShareModal
          setShowShareModal={setShowShareModal}
          showShareModal={showShareModal}
          t={t}
          workflow={workflow}
        />
      </div>
      {pinsCtl.editor}
    </PinsContext.Provider>
  );
}

export default function WorkflowCanvas(props: any) {
  return (
    <ReactFlowProvider>
      <CanvasInner {...props} />
    </ReactFlowProvider>
  );
}
