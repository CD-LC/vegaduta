"use client";

import { visibleInterval } from "../../../lib/visibleInterval";
import { useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { motion } from "motion/react";
import { ArrowLeft, ChevronRight, ScrollText } from "lucide-react";
import { api } from "../../../lib/api";
import AppShell from "../../../components/AppShell";
import PageHeader from "../../../components/ax/PageHeader";
import { Section, EmptyState } from "../../../components/ax/page-kit";
import { Button } from "../../../components/ui/button";
import { Badge } from "../../../components/ui/badge";
import { Card } from "../../../components/ui/card";
import { Skeleton } from "../../../components/ui/skeleton";
import { Input } from "../../../components/ui/input";
import PaginationControls from "../../../components/PaginationControls";
import LoadMoreBar from "../../../components/LoadMoreBar";
import { usePagination } from "../../../lib/usePagination";
import { useTranslations } from "../../../lib/i18n";
import WorkflowHealthStrip from "../../../components/workflow/WorkflowHealthStrip";
import { nodeLabelsFromGraph } from "../../../components/workflow/workflowHealth";

// Run history - "after running the workflow, the logs should get printed in
// a separate page... everything should be transparent to consumer." This is
// the list half of that: every past run of one workflow, each one a link
// into runs/[runId] (full step-by-step trace - what every agent/tool/code
// node actually did), so a run's transparency record outlives the canvas
// session it happened in instead of only being visible in the "Last run"
// panel until the next run overwrites it.
//
// Premium redesign - the bespoke .ax-int-card/.ax-wf-history-* run-history
// chrome is replaced with the shared PageHeader + page-kit Section + a
// clean token-driven Card list (one divided row per run). Status maps to a
// shadcn Badge; each row stays a Link into the full run trace.

// Maps a run status to a shadcn Badge variant, so the run list reads with the
// same status language as the rest of the console (never a fabricated field -
// just a visual mapping of the real WorkflowRun.status the backend returns).
function statusVariant(status: string | undefined): "default" | "secondary" | "outline" | "destructive" {
  const s = (status || "").toUpperCase();
  if (s === "COMPLETED" || s === "OK") return "default";
  if (s === "FAILED" || s === "ERROR") return "destructive";
  if (s === "RUNNING" || s === "AWAITING_APPROVAL") return "secondary";
  // Stop Run - explicit, not just falling through to the same "outline"
  // default every unrecognized status would also land on - documents that
  // CANCELLED was actually considered, not merely uncaught.
  if (s === "CANCELLED") return "outline";
  return "outline";
}

function isLiveStatus(status: string | undefined): boolean {
  const s = (status || "").toUpperCase();
  return s === "RUNNING" || s === "AWAITING_APPROVAL";
}

// One row of api.listWorkflowRuns, same "only what's consumed here" shape as
// WorkflowCanvas.tsx's local WorkflowRunSummary (not reused directly - this
// list also renders triggeredBy, which that trace-tab type doesn't need).
interface WorkflowRunListItem {
  id: string;
  status?: string;
  startedAt?: string;
  triggeredBy?: string;
}

export default function WorkflowRunsPage() {
  const t = useTranslations("workflowRunsPage");
  const tu = useTranslations("workflowStudioUi");
  const params = useParams<{ id: string }>();
  const workflowId = params.id;
  const [workflow, setWorkflow] = useState<any>(null);
  const [runs, setRuns] = useState<any[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Run history is server-capped (newest first); older runs are paged in with offset.
  const [runMeta, setRunMeta] = useState<{ total: number | null; hasMore: boolean }>({ total: null, hasMore: false });
  const [loadingMore, setLoadingMore] = useState(false);
  const [statusFilter, setStatusFilter] = useState("");
  const [search, setSearch] = useState("");
  const [reloadKey, setReloadKey] = useState(0);
  const loadedRef = useRef(0);
  loadedRef.current = runs ? runs.length : 0;

  const loadOlderRuns = async () => {
    if (!runs || loadingMore) return;
    setLoadingMore(true);
    try {
      const page = await api.listWorkflowRunsPage(workflowId, runs.length, 100);
      setRuns((prev) => {
        const seen = new Set((prev || []).map((r: any) => r.id));
        return [...(prev || []), ...page.rows.filter((r: any) => !seen.has(r.id))];
      });
      setRunMeta({ total: page.total, hasMore: page.hasMore });
    } catch (err: any) {
      setError(err?.message || t("couldNotLoad"));
    } finally {
      setLoadingMore(false);
    }
  };

  useEffect(() => {
    let cancelled = false;
    setError(null);
    setRuns(null);
    api
      .getWorkflow(workflowId)
      .then((wf) => !cancelled && setWorkflow(wf))
      .catch(() => {});
    api
      .listWorkflowRunsPage(workflowId, 0, 100)
      .then((page) => {
        if (cancelled) return;
        setRuns(page.rows);
        setRunMeta({ total: page.total, hasMore: page.hasMore });
      })
      .catch((err) => !cancelled && setError(err.message || t("couldNotLoad")));
    return () => {
      cancelled = true;
    };
  }, [workflowId, t, reloadKey]);

  // Monitoring hardening (same fix as runs/[runId]/page.tsx, see its comment
  // for the full rationale): this list previously froze at whatever
  // snapshot the initial fetch caught, so a run that was still RUNNING (or
  // moved to AWAITING_APPROVAL) when the page loaded never updated its
  // status badge without a manual reload. Re-fetches the whole list on the
  // 3s cadence (paused in hidden tabs), but only while at least one row is actually live -
  // a finished run history sits still, exactly as before.
  const hasLiveRun = (runs || []).some((r) => isLiveStatus(r.status));
  useEffect(() => {
    if (!hasLiveRun) return undefined;
    let cancelled = false;
    // 3s (was 1.2s) and paused in hidden tabs: this re-lists every run for the
    // workflow, so cost grows with run history x open tabs.
    const poll = async () => {
      if (cancelled) return;
      // Re-read the window already loaded (not just the newest page) so polling does not
      // collapse older pages the user loaded; the server hard max is 1000.
      try {
        const page = await api.listWorkflowRunsPage(workflowId, 0, Math.min(1000, Math.max(100, loadedRef.current)));
        if (cancelled) return;
        setRuns(page.rows);
        setRunMeta({ total: page.total, hasMore: page.hasMore });
      } catch {
        // Transient poll failure - next tick retries.
      }
    };
    // 3s (was 1.2s) and paused in hidden tabs: this re-lists every run for the
    // workflow, so cost grows with run history x open tabs.
    const stop = visibleInterval(() => {
      void poll();
    }, 3000);
    return () => {
      cancelled = true;
      stop();
    };
  }, [workflowId, hasLiveRun]);

  // Node display names for the health strip, from the definition already loaded above.
  const nodeLabels = useMemo(() => nodeLabelsFromGraph(workflow?.graph), [workflow]);

  const statuses = useMemo(() => {
    const counts = new Map<string, number>();
    for (const r of runs || []) counts.set(r.status || "UNKNOWN", (counts.get(r.status || "UNKNOWN") || 0) + 1);
    return [...counts.entries()].sort((a, b) => b[1] - a[1]);
  }, [runs]);
  const filteredRuns = useMemo(() => {
    const q = search.trim().toLowerCase();
    return (runs || []).filter(
      (r) =>
        (!statusFilter || (r.status || "UNKNOWN") === statusFilter) &&
        (!q || `${r.id} ${r.triggeredBy || ""}`.toLowerCase().includes(q))
    );
  }, [runs, statusFilter, search]);
  const { page, pageSize, pageItems, totalPages, totalItems, setPage, setPageSize } = usePagination(filteredRuns);

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader
        title={workflow?.name ? `${workflow.name} — ${t("runHistoryTitle")}` : t("runHistoryTitle")}
        subtitle={t("description")}
        actions={
          <Button asChild variant="outline" size="sm">
            <Link href={`/workflows/${workflowId}`}>
              <ArrowLeft size={14} /> {t("backToCanvas")}
            </Link>
          </Button>
        }
      />

      {/* Health at a glance: one bounded eval-summary call, never derived from the run list below. */}
      {typeof workflowId === "string" && (
        <WorkflowHealthStrip
          workflowId={workflowId}
          nodeLabels={nodeLabels}
          refreshKey={hasLiveRun ? "live" : "idle"}
          className="mb-6"
        />
      )}

      <Section
        title={
          <span className="inline-flex items-center gap-2">
            <ScrollText size={15} className="text-muted-foreground" />
            {t("sectionLabel")}
          </span>
        }
      >
        {error && (
          <div
            role="alert"
            className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive"
          >
            <span className="min-w-0">{error}</span>
            <Button type="button" variant="outline" size="sm" onClick={() => setReloadKey((k) => k + 1)}>
              {tu("runsRetry")}
            </Button>
          </div>
        )}

        {!error && runs === null && (
          <Card className="gap-0 divide-y divide-border p-0">
            {[0, 1, 2].map((i) => (
              <div key={i} className="flex items-center gap-4 px-5 py-4">
                <Skeleton className="h-4 w-20" />
                <Skeleton className="h-5 w-16 rounded-full" />
                <Skeleton className="ml-auto h-4 w-48" />
              </div>
            ))}
          </Card>
        )}

        {!error && runs && runs.length === 0 && <EmptyState icon={ScrollText} title={t("emptyState")} />}

        {!error && runs && runs.length > 0 && (
          <div className="space-y-2">
            <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
              <Input
                type="search"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder={tu("runsSearch")}
                aria-label={tu("runsSearch")}
                className="sm:max-w-xs"
              />
              <div role="group" aria-label={tu("runsStatusFilter")} className="flex flex-wrap gap-1.5">
                <Button type="button" size="sm" variant={statusFilter === "" ? "default" : "outline"} aria-pressed={statusFilter === ""} onClick={() => setStatusFilter("")}>
                  {tu("runsStatusAll")}
                </Button>
                {statuses.map(([status, count]) => (
                  <Button key={status} type="button" size="sm" variant={statusFilter === status ? "default" : "outline"} aria-pressed={statusFilter === status} onClick={() => setStatusFilter(status)}>
                    {status.toLowerCase()} <span className="tabular-nums">{count}</span>
                  </Button>
                ))}
              </div>
            </div>
            {runMeta.hasMore && (statusFilter || search) && (
              <p className="text-xs text-muted-foreground">{tu("runsLoadedNote", { count: runs.length })}</p>
            )}
          </div>
        )}

        {!error && runs && runs.length > 0 && filteredRuns.length === 0 && (
          <p role="status" className="rounded-lg border border-dashed border-border px-4 py-6 text-center text-sm text-muted-foreground">
            {tu("runsNoMatches")}
          </p>
        )}

        {/* Animate Run History "as per name" - a history is a sequence
         * unfolding over time, so each row reveals in order (staggered by
         * index). A plain keyed motion.div reveal (no AnimatePresence mode
         * swap), so scrolling back through this workflow's own timeline
         * reads as one. */}
        {!error && runs && filteredRuns.length > 0 && (
          <Card className="gap-0 divide-y divide-border p-0">
            {pageItems.map((run: WorkflowRunListItem, i: number) => (
              <motion.div
                key={run.id}
                initial={{ opacity: 0, x: -20 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ duration: 0.4, delay: Math.min(i, 14) * 0.05, ease: [0.16, 1, 0.3, 1] }}
              >
                <Link
                  href={`/workflows/${workflowId}/runs/${run.id}`}
                  className="group flex flex-wrap items-center gap-x-4 gap-y-1 px-5 py-4 transition-colors hover:bg-accent"
                >
                  <span className="font-mono text-sm font-medium tabular-nums text-foreground">
                    #{run.id.slice(0, 8)}
                  </span>
                  <Badge variant={statusVariant(run.status)}>{run.status}</Badge>
                  <span className="ml-auto min-w-0 text-right text-sm text-muted-foreground">
                    {run.startedAt ? new Date(run.startedAt).toLocaleString() : t("unknownDate")}
                    {run.triggeredBy ? ` · ${run.triggeredBy}` : ""}
                  </span>
                  <ChevronRight
                    size={16}
                    aria-hidden="true"
                    className="shrink-0 text-muted-foreground transition-colors group-hover:text-foreground"
                  />
                </Link>
              </motion.div>
            ))}
          </Card>
        )}

        {!error && runs && runs.length > 0 && (
          <>
            <LoadMoreBar
              loaded={(runs || []).length}
              total={runMeta.total}
              hasMore={runMeta.hasMore}
              loading={loadingMore}
              onLoadMore={() => void loadOlderRuns()}
              itemLabel={t("itemLabelRuns")}
            />
            <PaginationControls
              page={page}
              totalPages={totalPages}
              totalItems={totalItems}
              pageSize={pageSize}
              onPageChange={setPage}
              onPageSizeChange={setPageSize}
              itemLabel={t("itemLabelRuns")}
            />
          </>
        )}
      </Section>
    </AppShell>
  );
}
