"use client";

import { useEffect, useState } from "react";
import PaginationControls from "../PaginationControls";
import { pageForSizeChange } from "../../lib/usePagination";
import { listTaskRunsPage } from "./runsPaged";
import { useTranslations } from "../../lib/i18n";
import {
  RUNNING_POLL_MAX_TICKS,
  RUNNING_POLL_MS,
  formatCost,
  formatRunTime,
  isRunInFlight,
  statusTone,
  type ScheduledTaskRun,
} from "../../lib/scheduledTasks";
import { RunStrip } from "../charts";
import { Badge } from "../ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../ui/table";
import { runSample } from "./scheduledTaskStats";

/**
 * The run history of one task: when each run started, what triggered it, how it ended, what it cost and where its
 * result went. Loads when mounted (the card mounts it on expand) and again whenever `refreshKey` changes (a run-now
 * or a pause/resume bumps it), so an open history never goes stale after an action.
 *
 * Above the table sits a strip of the same runs (oldest to newest) with a sentence and the cost of THOSE runs. The
 * endpoint returns only the newest runs (this client asks for 20), so nothing here is presented as a lifetime
 * figure: the strip is titled "Last N runs", the cost is "of the runs shown" and is the model's estimate, and the
 * one lifetime number - `runCount`, from the task itself - is labelled as such.
 *
 * A run finishes on the server, not in this tab. While any listed run is still in flight the history re-reads
 * itself every few seconds, and it also re-reads when the task's own last status changes (the badge settling), so a
 * finished run never keeps reading "Running" until a reload (prod re-verification 2026-10-05, R5). The polling
 * stops when no listed run is in flight, on a failed read, and after RUNNING_POLL_MAX_TICKS (5 minutes).
 */
export default function ScheduledTaskRuns({
  taskId,
  timezone,
  refreshKey,
  runCount,
  taskStatus,
}: {
  taskId: string;
  timezone: string;
  refreshKey: number;
  /** ScheduledTask.runCount: every run since the task was created (automatic retries are not counted). */
  runCount?: number;
  /** ScheduledTask.lastStatus: a change means a run just settled, so the history is re-read. */
  taskStatus?: string | null;
}) {
  const t = useTranslations("scheduledTasks");
  const tr = useTranslations("scheduledRuns");
  const [runs, setRuns] = useState<ScheduledTaskRun[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  // X-Total-Count / X-Has-More of the run history; both are absent on a core that cannot page it yet.
  const [total, setTotal] = useState<number | undefined>(undefined);
  const [hasMore, setHasMore] = useState(false);
  const offset = (page - 1) * pageSize;

  useEffect(() => {
    let live = true;
    listTaskRunsPage(taskId, pageSize, offset)
      .then((res) => {
        if (!live) return;
        setRuns(res.rows);
        setTotal(res.total);
        setHasMore(res.hasMore);
        setFailed(false);
      })
      .catch(() => {
        if (live) setFailed(true);
      });
    return () => {
      live = false;
    };
  }, [taskId, refreshKey, taskStatus, pageSize, offset]);

  // Poll while a listed run is in flight. Keyed on the boolean, so the interval is not torn down by each refresh.
  const anyInFlight = runs !== null && runs.some((r) => isRunInFlight(r.status));
  useEffect(() => {
    if (!anyInFlight) return undefined;
    let live = true;
    let ticks = 0;
    const id = window.setInterval(() => {
      if (++ticks > RUNNING_POLL_MAX_TICKS) {
        window.clearInterval(id);
        return;
      }
      listTaskRunsPage(taskId, pageSize, offset)
        .then((res) => {
          if (!live) return;
          setRuns(res.rows);
          setTotal(res.total);
          setHasMore(res.hasMore);
        })
        .catch(() => window.clearInterval(id));
    }, RUNNING_POLL_MS);
    return () => {
      live = false;
      window.clearInterval(id);
    };
  }, [taskId, anyInFlight, pageSize, offset]);

  if (failed) return <p className="text-xs text-destructive">{t("historyFailed")}</p>;
  if (runs === null) return <p className="text-xs text-muted-foreground">{t("historyLoading")}</p>;
  if (runs.length === 0 && page === 1) return <p className="text-xs text-muted-foreground">{t("historyEmpty")}</p>;

  const sample = runSample(runs);
  const cost = sample.costUsd === null ? "-" : formatCost(sample.costUsd);
  // The endpoint answers newest first; the strip reads oldest to newest, left to right.
  const strip = [...runs].reverse().map((r) => ({
    id: r.id,
    status: statusTone(r.status),
    title: `${formatRunTime(r.startedAt, timezone)}: ${t(`runStatus_${r.status}`)}`,
  }));

  return (
    <div className="flex min-w-0 flex-col gap-3">
      {page === 1 && runs.length > 0 && (
      <div className="min-w-0 space-y-1.5 pt-1" data-testid="scheduled-task-run-sample">
        <RunStrip runs={strip} label={t(sample.count === 1 ? "runStripLabelOne" : "runStripLabel", { count: sample.count })} />
        <p className="text-xs text-muted-foreground">
          {t("runStripSummary", { count: sample.count, succeeded: sample.succeeded, failed: sample.failed, other: sample.other })}
        </p>
        <p className="text-xs text-muted-foreground">
          {sample.costedRuns > 0 && sample.costedRuns < sample.count
            ? t("runSampleCostPartial", { cost, costed: sample.costedRuns, count: sample.count })
            : t("runSampleCost", { cost })}
        </p>
        {typeof runCount === "number" && <p className="text-xs text-muted-foreground">{t("lifetimeRuns", { count: runCount })}</p>}
      </div>
      )}
      <div data-testid="scheduled-task-runs">
        <Table className="min-w-[34rem] text-xs">
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead className="h-8 px-2">{t("historyStarted")}</TableHead>
              <TableHead className="h-8 px-2">{t("historyTrigger")}</TableHead>
              <TableHead className="h-8 px-2">{t("historyStatus")}</TableHead>
              <TableHead className="h-8 px-2">{t("historyCost")}</TableHead>
              <TableHead className="h-8 px-2">{t("historyDelivery")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {runs.map((r) => (
              <TableRow key={r.id} className="align-top">
                <TableCell className="px-2 py-1.5 whitespace-normal text-foreground">{formatRunTime(r.startedAt, timezone)}</TableCell>
                <TableCell className="px-2 py-1.5 whitespace-normal text-muted-foreground">{t(`trigger_${r.trigger}`)}</TableCell>
                <TableCell className="px-2 py-1.5 whitespace-normal">
                  <Badge variant="state" state={`var(--state-${statusTone(r.status)})`}>
                    {t(`runStatus_${r.status}`)}
                  </Badge>
                  {r.error && <p className="mt-1 max-w-xs break-words text-muted-foreground">{r.error}</p>}
                </TableCell>
                <TableCell className="px-2 py-1.5 whitespace-normal text-muted-foreground">{formatCost(r.costUsd)}</TableCell>
                <TableCell className="px-2 py-1.5 whitespace-normal text-muted-foreground">{r.deliveryStatus ? t(`runDelivery_${r.deliveryStatus}`) : ""}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {runs.length === 0 && <p className="px-2 py-3 text-xs text-muted-foreground">{tr("pageEmpty")}</p>}
        {(page > 1 || hasMore || (total ?? 0) > 10) && (
          <PaginationControls
            page={page}
            pageSize={pageSize}
            {...(total !== undefined ? { totalItems: total } : { hasMore })}
            pageItemCount={runs.length}
            pageSizeOptions={[10, 25, 50]}
            itemLabel={tr("itemLabel")}
            onPageChange={setPage}
            onPageSizeChange={(size) => {
              setPage(pageForSizeChange(page, pageSize, size));
              setPageSize(size);
            }}
          />
        )}
      </div>
    </div>
  );
}
