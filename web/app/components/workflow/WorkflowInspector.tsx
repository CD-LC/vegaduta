"use client";

import NodeConfigDrawer from "./NodeConfigDrawer";
import { Tabs, TabsList, TabsTrigger } from "../ui/tabs";
import { Button } from "../ui/button";
import Link from "next/link";
import LoadMoreBar from "../LoadMoreBar";
import { nodeLabelKey } from "./nodeDefs";
import { runStepProgress } from "./canvasValidation";
import { LOG_LEVEL_LABEL, type WorkflowRunTraceEntry, classifyLogLine } from "./workflowCanvasHelpers";

export default function WorkflowInspector({
  agents,
  cancelBusy,
  collections,
  deleteNode,
  devices,
  downloadRunOutput,
  downloading,
  elapsedSeconds,
  expandedTrace,
  exportRunLogs,
  inspectorOpen,
  inspectorTab,
  loadRunHistory,
  nodes,
  onOpenRun,
  pauseBusy,
  pauseRun,
  resumeRun,
  runHistory,
  runHistoryLoading,
  runHistoryMeta,
  loadMoreRunHistory,
  runIsStoppable,
  runLive,
  runResult,
  runningNodeIds,
  selectedNode,
  setExpandedTrace,
  setInspectorOpen,
  setInspectorTab,
  setSelectedNodeId,
  stopRun,
  t,
  tNodeDefs,
  trace,
  triggerNodeCount,
  updateNode,
  workflowId,
}: any) {
  return (
    <>
      {/* Run inspector (mockup: 376px right panel, Steps/Raw trace/
       * History tabs). Swaps to the node config drawer the moment a node
       * is selected - editing a node's own settings takes priority over
       * watching a run, and clicking back onto empty canvas (onPaneClick
       * above) already clears the selection, returning here automatically. */}
      <div className={`ax-wf-inspector${inspectorOpen ? " ax-wf-inspector-open" : ""}`}>
        {selectedNode ? (
          <NodeConfigDrawer
            node={selectedNode}
            workflowId={workflowId}
            agents={agents}
            collections={collections}
            devices={devices}
            triggerCount={triggerNodeCount}
            onChange={updateNode}
            onDelete={deleteNode}
            onClose={() => {
              setSelectedNodeId(null);
              setInspectorOpen(false);
            }}
          />
        ) : (
          <>
            <div className="ax-wf-inspector-header">
              <div className="row" style={{ justifyContent: "space-between", alignItems: "center" }}>
                <span className="ax-wf-run-id">
                  {runResult ? t("runIdLabel", { id: runResult.id.slice(0, 8) }) : t("noRunYet")}
                </span>
                {runResult && (
                  <span
                    className={`ax-wf-run-status ax-wf-run-status-${runLive ? "running" : (runResult.status || "").toLowerCase()}`}
                  >
                    <span className="ax-wf-run-status-dot" />
                    {runLive ? t("runningStatus") : runResult.status}
                  </span>
                )}
              </div>
              {runResult && (
                <>
                  <div className="ax-wf-run-meta">
                    <span>
                      {(() => {
                        const p = runStepProgress(Object.keys(trace).length, nodes.length, runResult.status, runLive);
                        return p.branchesNotTaken > 0
                          ? t("stepProgressBranches", { completed: p.completed, total: p.total, skipped: p.branchesNotTaken })
                          : t("stepProgress", { completed: p.completed, total: p.total });
                      })()}
                    </span>
                    <span>·</span>
                    <span>
                      {elapsedSeconds != null ? t("elapsedTime", { seconds: elapsedSeconds.toFixed(1) }) : ""}
                    </span>
                  </div>
                  <div className="ax-wf-progress">
                    <div
                      className="ax-wf-progress-fill"
                      style={{
                        width: `${nodes.length ? Math.min(100, (Object.keys(trace).length / nodes.length) * 100) : 0}%`,
                      }}
                    />
                  </div>
                  <div className="row" style={{ gap: "8px", marginTop: "8px" }}>
                    <Button asChild variant="outline" size="sm">
                      <Link href={`/workflows/${workflowId}/runs/${runResult.id}`}>{t("viewFullRunLog")}</Link>
                    </Button>
                    {runResult.outputFileName && (
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        onClick={downloadRunOutput}
                        disabled={downloading}
                      >
                        {downloading
                          ? t("preparingDownload")
                          : t("downloadOutputLabel", { filename: runResult.outputFileName })}
                      </Button>
                    )}
                  </div>
                </>
              )}
            </div>

            {/* Console tab convention: shadcn Tabs (pill/segmented). Content
                still renders from inspectorTab below, so only the switcher
                changed. */}
            <Tabs
              value={inspectorTab}
              onValueChange={(v: string) => {
                setInspectorTab(v);
                if (v === "history" && runHistory === null) loadRunHistory();
              }}
              className="ax-wf-inspector-tabs gap-0 px-4 pt-3"
            >
              <TabsList>
                <TabsTrigger value="steps">{t("stepsTab")}</TabsTrigger>
                <TabsTrigger value="raw">{t("rawTraceTab")}</TabsTrigger>
                <TabsTrigger value="history">{t("historyTab")}</TabsTrigger>
              </TabsList>
            </Tabs>

            <div className="ax-wf-tab-body">
              {inspectorTab === "steps" &&
                (!runResult && !runLive ? (
                  <p className="ax-wf-empty">{t("noRunYetHint")}</p>
                ) : (
                  <div className="ax-wf-step-list">
                    {nodes.map((n: any) => {
                      const traceIdx = (runResult?.trace || []).findIndex(
                        (x: WorkflowRunTraceEntry) => x.nodeId === n.id
                      );
                      const traceEntry = traceIdx >= 0 ? runResult.trace[traceIdx] : null;
                      const isRunningNode = runningNodeIds.has(n.id);
                      const expanded = traceIdx >= 0 && !!expandedTrace[traceIdx];
                      const hasLogLines = traceEntry && (traceEntry.logLines || []).length > 0;
                      const dotState = traceEntry
                        ? traceEntry.status.toLowerCase()
                        : isRunningNode
                          ? "running"
                          : "pending";
                      return (
                        <div
                          key={n.id}
                          className={`ax-wf-step${isRunningNode ? " ax-wf-step-running" : ""}${!traceEntry && !isRunningNode ? " ax-wf-step-pending" : ""}`}
                        >
                          <div
                            className="ax-wf-step-row"
                            style={{ cursor: hasLogLines ? "pointer" : "default" }}
                            role="button"
                            tabIndex={hasLogLines ? 0 : -1}
                            aria-disabled={hasLogLines ? undefined : true}
                            aria-expanded={hasLogLines ? !!expanded : undefined}
                            onClick={() =>
                              hasLogLines && setExpandedTrace((prev: any) => ({ ...prev, [traceIdx]: !prev[traceIdx] }))
                            }
                            onKeyDown={(e) => {
                              if (!hasLogLines || (e.key !== "Enter" && e.key !== " ")) return;
                              e.preventDefault();
                              setExpandedTrace((prev: any) => ({ ...prev, [traceIdx]: !prev[traceIdx] }));
                            }}
                          >
                            <span className={`ax-wf-step-dot ax-wf-step-dot-${dotState}`}>
                              {traceEntry?.status === "OK" && "✓"}
                              {traceEntry?.status === "ERROR" && "✕"}
                              {traceEntry?.status === "SKIPPED" && "–"}
                              {traceEntry?.status === "AWAITING_APPROVAL" && "⏸"}
                              {traceEntry?.status === "CANCELLED" && "■"}
                              {traceEntry?.status === "PINNED" && "◆"}
                            </span>
                            <div className="ax-wf-step-text">
                              <div className="ax-wf-step-label">{n.data.label || tNodeDefs(nodeLabelKey(n.type))}</div>
                              <div className="ax-wf-step-sub">
                                {traceEntry
                                  ? traceEntry.summary
                                  : isRunningNode
                                    ? t("runningNodeState")
                                    : t("queuedNodeState")}
                              </div>
                            </div>
                          </div>
                          {expanded && hasLogLines && (
                            <div className="ax-wf-step-loglines">
                              {traceEntry.logLines.map((line: string, j: number) => {
                                const level = classifyLogLine(line);
                                return (
                                  <div key={j} className="wf-log-line">
                                    <span className="wf-log-lineno">{j + 1}</span>
                                    <span className={`wf-log-level wf-log-level-${level}`}>
                                      {LOG_LEVEL_LABEL[level]}
                                    </span>
                                    <span className="wf-log-message">{line}</span>
                                  </div>
                                );
                              })}
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                ))}

              {/* Raw trace tab - "should show every activity, event, and
               * operation performed by the system in real time, without
               * omitting any trace entries." A flattened, chronological
               * transcript: every real logLine from every trace entry, in
               * order, never sliced/capped - unlike the Steps tab above
               * (a human-readable collapsed summary), this is the full
               * firehose. Falls back to the entry's own summary for any
               * step that has no logLines recorded, so a step is never
               * silently missing from the transcript. Already live: this
               * re-renders on every runResult update from the same 1.2s
               * poll the Steps tab and canvas both already use. */}
              {inspectorTab === "raw" && (
                <div className="ax-wf-raw-log">
                  {!runResult && !runLive && <p className="ax-wf-empty">{t("noRunNothingToShow")}</p>}
                  {!runResult && runLive && <p className="ax-wf-empty">{t("startingRun")}</p>}
                  {runResult && (runResult.trace || []).length === 0 && !runLive && (
                    <p className="ax-wf-empty">{t("noEventsRecorded")}</p>
                  )}
                  {runResult &&
                    (runResult.trace || []).flatMap((t: WorkflowRunTraceEntry, i: number) => {
                      const lines = t.logLines && t.logLines.length > 0 ? t.logLines : [t.summary || t.status];
                      return lines.map((line, j) => {
                        const level = classifyLogLine(line);
                        return (
                          <div className="ax-wf-raw-line" key={`${t.nodeId || "node"}-${i}-${j}`}>
                            <span className="ax-wf-raw-node">{t.label || t.nodeId}</span>
                            <span className={`wf-log-level wf-log-level-${level}`}>{LOG_LEVEL_LABEL[level]}</span>
                            <span className="ax-wf-raw-msg">{line}</span>
                          </div>
                        );
                      });
                    })}
                  {runLive && (
                    <div className="ax-wf-raw-line ax-wf-raw-line-muted">
                      <span className="ax-wf-raw-msg">{t("waitingForNextEvent")}</span>
                    </div>
                  )}
                </div>
              )}

              {inspectorTab === "history" &&
                (runHistoryLoading ? (
                  <p className="ax-wf-empty">{t("loading")}</p>
                ) : !runHistory || runHistory.length === 0 ? (
                  <p className="ax-wf-empty">{t("noRunsYet")}</p>
                ) : (
                  <div className="ax-wf-history-list">
                    {runHistory.map((r: any) => {
                      const cells = (
                        <>
                          <span className="ax-wf-history-id">#{r.id.slice(0, 8)}</span>
                          <span className="ax-wf-history-status">{r.status}</span>
                          <span className="ax-wf-history-time">
                            {r.startedAt ? new Date(r.startedAt).toLocaleString() : t("unknownTime")}
                          </span>
                        </>
                      );
                      const cls = `ax-wf-history-row ax-wf-history-row-${(r.status || "").toLowerCase()}`;
                      // One run viewer: open the run in the canvas replay (its
                      // banner links on to the full run page) rather than a
                      // second, separate history surface.
                      return onOpenRun ? (
                        <button key={r.id} type="button" className={cls} onClick={() => onOpenRun(String(r.id))}>
                          {cells}
                        </button>
                      ) : (
                        <Link key={r.id} href={`/workflows/${workflowId}/runs/${r.id}`} className={cls}>
                          {cells}
                        </Link>
                      );
                    })}
                    <LoadMoreBar
                      loaded={runHistory.length}
                      total={runHistoryMeta?.total ?? null}
                      hasMore={Boolean(runHistoryMeta?.hasMore)}
                      loading={Boolean(runHistoryMeta?.loadingMore)}
                      onLoadMore={() => void loadMoreRunHistory?.()}
                      itemLabel="runs"
                    />
                  </div>
                ))}
            </div>

            {runResult && (
              <div className="ax-wf-inspector-footer">
                {/* Pause/Resume - the parked counterpart of Stop: offered
                 * exactly for the two states it applies to (pausing a
                 * RUNNING run, resuming a PAUSED one), same neutral outline
                 * styling Export uses rather than the destructive tint Stop
                 * gets - pausing isn't destructive. flex-1 keeps the row
                 * evenly divided like the old flex buttons did. */}
                {runResult.status === "RUNNING" && (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="flex-1"
                    disabled={pauseBusy || cancelBusy}
                    onClick={pauseRun}
                    title={t("pauseRunTooltip")}
                  >
                    {pauseBusy ? t("pausingRun") : t("pauseRun")}
                  </Button>
                )}
                {runResult.status === "PAUSED" && (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="flex-1"
                    disabled={pauseBusy || cancelBusy}
                    onClick={resumeRun}
                    title={t("resumeRunTooltip")}
                  >
                    {pauseBusy ? t("resumingRun") : t("resumeRun")}
                  </Button>
                )}
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="flex-1 border-destructive/50 text-destructive hover:bg-destructive/10 hover:text-destructive"
                  disabled={!runIsStoppable || cancelBusy}
                  onClick={stopRun}
                  title={runIsStoppable ? t("stopRunTooltip") : t("stopRunNotRunningTooltip")}
                >
                  {cancelBusy ? t("stoppingRun") : t("stopRun")}
                </Button>
                <Button type="button" variant="outline" size="sm" className="flex-1" onClick={exportRunLogs}>
                  {t("exportLogs")}
                </Button>
              </div>
            )}
          </>
        )}
      </div>
    </>
  );
}
