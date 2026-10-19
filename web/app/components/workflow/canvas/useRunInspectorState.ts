"use client";

import { useEffect, useMemo, useState } from "react";
import { api } from "../../../lib/api";
import { errorMessage } from "../../../lib/errors";
import { toast } from "../../../lib/toastBus";
import type { WorkflowRunSummary } from "../workflowCanvasHelpers";
import { saveBlobAs } from "./useDiagramExport";

/**
 * State behind the right-hand run inspector: the three tabs (steps / raw /
 * history), the paged run history, expanded trace rows, the output download,
 * the log export and the live elapsed-time readout.
 */
export function useRunInspectorState(opts: {
  workflowId: any;
  runResult: any;
  runLive: boolean;
  t: (key: string, values?: Record<string, unknown>) => string;
}) {
  const { workflowId, runResult, runLive, t } = opts;
  // "steps" mirrors the run console; "raw" is a pretty-printed dump of the
  // exact runResult in hand; "history" lazy-loads real past runs on first visit.
  const [inspectorTab, setInspectorTab] = useState("steps");
  // Which trace rows have their log lines expanded (index into runResult.trace).
  const [expandedTrace, setExpandedTrace] = useState<Record<number, boolean>>({});
  const [runHistory, setRunHistory] = useState<WorkflowRunSummary[] | null>(null); // null = not fetched yet
  const [runHistoryLoading, setRunHistoryLoading] = useState(false);
  // History is server-capped (newest first); older runs are paged in with offset.
  const [runHistoryMeta, setRunHistoryMeta] = useState<{
    total: number | null;
    hasMore: boolean;
    loadingMore: boolean;
  }>({
    total: null,
    hasMore: false,
    loadingMore: false,
  });
  const [downloading, setDownloading] = useState(false);
  // Holds the actual current-time reading (updated while a run is live) so
  // elapsedSeconds stays a pure derivation of (nowMs, runResult.startedAt).
  const [nowMs, setNowMs] = useState(() => Date.now());

  async function loadRunHistory() {
    setRunHistoryLoading(true);
    try {
      const page = await api.listWorkflowRunsPage(workflowId, 0, 100);
      setRunHistory(page.rows);
      setRunHistoryMeta({ total: page.total, hasMore: page.hasMore, loadingMore: false });
    } catch (err) {
      toast.error(errorMessage(err, t("loadRunHistoryError")));
      setRunHistory([]);
      setRunHistoryMeta({ total: null, hasMore: false, loadingMore: false });
    } finally {
      setRunHistoryLoading(false);
    }
  }

  async function loadMoreRunHistory() {
    if (!runHistory || runHistoryMeta.loadingMore) return;
    setRunHistoryMeta((m) => ({ ...m, loadingMore: true }));
    try {
      const page = await api.listWorkflowRunsPage(workflowId, runHistory.length, 100);
      setRunHistory((prev) => {
        const seen = new Set((prev || []).map((r) => r.id));
        return [...(prev || []), ...page.rows.filter((r: any) => !seen.has(r.id))];
      });
      setRunHistoryMeta({ total: page.total, hasMore: page.hasMore, loadingMore: false });
    } catch (err) {
      toast.error((err as Error).message || t("loadRunHistoryError"));
      setRunHistoryMeta((m) => ({ ...m, loadingMore: false }));
    }
  }

  // Output node "local file" destination (#152): streams the saved output
  // through the authenticated api path, then triggers the browser download
  // (a plain <a href> can't carry the bearer token).
  async function downloadRunOutput() {
    if (!runResult) return;
    setDownloading(true);
    try {
      const { blob, filename } = await api.downloadWorkflowRunOutput(workflowId, runResult.id);
      saveBlobAs(blob, filename);
    } catch (err) {
      toast.error(errorMessage(err, t("downloadOutputError")));
    } finally {
      setDownloading(false);
    }
  }

  // The backend has no log-export endpoint, so this serializes the runResult
  // already in hand (what the Steps/Raw tabs render from) client-side.
  function exportRunLogs() {
    if (!runResult) return;
    saveBlobAs(
      new Blob([JSON.stringify(runResult, null, 2)], { type: "application/json" }),
      `workflow-run-${runResult.id}.json`
    );
  }

  // Ticks only while a run is in flight; always recomputed from the run's real startedAt.
  useEffect(() => {
    if (!runLive) return undefined;
    const timer = setInterval(() => setNowMs(Date.now()), 500);
    return () => clearInterval(timer);
  }, [runLive]);

  const elapsedSeconds = useMemo(() => {
    if (!runResult?.startedAt) return null;
    const started = new Date(runResult.startedAt).getTime();
    if (Number.isNaN(started)) return null;
    return Math.max(0, (nowMs - started) / 1000);
  }, [runResult?.startedAt, nowMs]);

  return {
    inspectorTab,
    setInspectorTab,
    expandedTrace,
    setExpandedTrace,
    runHistory,
    setRunHistory,
    runHistoryLoading,
    runHistoryMeta,
    loadRunHistory,
    loadMoreRunHistory,
    downloading,
    downloadRunOutput,
    exportRunLogs,
    elapsedSeconds,
  };
}
