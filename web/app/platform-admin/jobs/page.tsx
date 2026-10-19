"use client";

import { Fragment, useCallback, useEffect, useMemo, useState } from "react";
import { Timer, RefreshCw, Play, Search, KeyRound } from "lucide-react";
import AdminShell from "../components/AdminShell";
import { PageHint, SectionCard } from "../components/sections/common";
import MasterKeyPrompt from "../components/tenants/MasterKeyPrompt";
import { ToneChip } from "../components/ops/OpsWidgets";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Skeleton } from "../components/ui/skeleton";
import { opsApi, type ScheduledJob } from "../lib/adminApi/operations";
import PaginationControls from "../components/PaginationControls";
import { usePagination } from "../lib/usePagination";
import { toast } from "../lib/toastBus";
import { UNAVAILABLE, formatExact, formatRelative } from "../lib/platformFormat";
import { failedJobCount, filterJobs, formatDurationMs, jobStatusMeta, sortJobs } from "../lib/opsFormat";
import { useConfirm } from "../components/admin/ConfirmDialog";
import { AdminEmptyState, AdminErrorState, AdminTableWrap } from "../components/admin/AdminKit";
import { Checkbox } from "../../components/ui/checkbox";

// Every @Scheduled job core runs (GET /api/platform/jobs), with its last run as
// recorded by core. Failed jobs sort first. "Run now" is offered only where
// core says runNowAllowed (idempotent jobs), needs the master key, and asks
// for confirmation - it starts the job on the replica that answers.

export default function JobsPage() {
  const askConfirm = useConfirm();
  const [jobs, setJobs] = useState<ScheduledJob[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [q, setQ] = useState("");
  const [onlyFailed, setOnlyFailed] = useState(false);
  const [running, setRunning] = useState<string | null>(null);
  const [masterKey, setMasterKey] = useState<string | null>(null);
  const [keyPromptFor, setKeyPromptFor] = useState<ScheduledJob | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const rows = await opsApi.jobs();
      setJobs(Array.isArray(rows) ? rows : []);
      setError(null);
    } catch (e: any) {
      setError(e?.message || "Could not load scheduled jobs.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const shown = useMemo(() => {
    const base = sortJobs(filterJobs(jobs || [], q));
    return onlyFailed ? base.filter((j) => j.lastStatus === "FAILED") : base;
  }, [jobs, q, onlyFailed]);
  const failed = failedJobCount(jobs);
  const pg = usePagination(shown, 25);

  async function run(job: ScheduledJob, key: string) {
    setRunning(job.id);
    try {
      const res = await opsApi.runJob(job.id, key);
      toast.success(res?.started ? `${job.bean}.${job.method} started.` : `${job.bean}.${job.method} did not start.`);
      await load();
    } catch (err: any) {
      if (err?.status === 401 || err?.status === 403) setMasterKey(null);
      // toJson already toasted the reason.
    } finally {
      setRunning(null);
    }
  }

  async function requestRun(job: ScheduledJob) {
    if (!job.runNowAllowed) return;
    if (
      !(await askConfirm(
        `Run ${job.bean}.${job.method} now? It runs immediately on one replica, outside its schedule (${job.schedule}).`
      ))
    ) {
      return;
    }
    if (!masterKey) {
      setKeyPromptFor(job);
      return;
    }
    void run(job, masterKey);
  }

  return (
    <AdminShell
      title="Scheduled jobs"
      actions={
        <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
          <RefreshCw className={loading ? "animate-spin" : undefined} /> Refresh
        </Button>
      }
    >
      <PageHint>
        Every scheduled job in core with its last run. Failed jobs are listed first. Run now is only offered for jobs
        core marks as safe to trigger by hand, and needs the master key.
      </PageHint>

      <SectionCard
        title="Jobs"
        icon={Timer}
        subtitle={
          jobs
            ? `${jobs.length} job${jobs.length === 1 ? "" : "s"}${failed ? ` · ${failed} failed on last run` : " · none failed on last run"}`
            : undefined
        }
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <div className="relative">
              <Search
                size={14}
                className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-muted-foreground"
              />
              <Input
                aria-label="Search jobs"
                className="h-9 w-60 pl-8"
                placeholder="Bean, method, schedule, error"
                value={q}
                onChange={(e) => setQ(e.target.value)}
              />
            </div>
            <div className="flex items-center gap-1.5 text-xs">
              <Checkbox checked={onlyFailed} onCheckedChange={(v) => setOnlyFailed(v)} id="jobs-failed-only" />
              <label htmlFor="jobs-failed-only">Failed only</label>
            </div>
          </div>
        }
      >
        {error && <AdminErrorState message={error} onRetry={() => void load()} />}
        {loading && !jobs && <Skeleton className="h-40 w-full" />}
        {jobs && shown.length === 0 && (
          <AdminEmptyState title={jobs.length === 0 ? "Core reported no scheduled jobs" : "No jobs match"} />
        )}
        {shown.length > 0 && (
          <AdminTableWrap>
            <table className="w-full min-w-[760px] text-left text-sm">
              <thead className="text-xs text-muted-foreground">
                <tr className="border-b border-border">
                  <th className="py-2 pr-3 font-medium">Job</th>
                  <th className="py-2 pr-3 font-medium">Schedule</th>
                  <th className="py-2 pr-3 font-medium">Last status</th>
                  <th className="py-2 pr-3 font-medium">Last started</th>
                  <th className="py-2 pr-3 font-medium">Duration</th>
                  <th className="py-2 font-medium">
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {pg.pageItems.map((j) => {
                  const meta = jobStatusMeta(j.lastStatus);
                  const open = expanded === j.id;
                  return (
                    <Fragment key={j.id}>
                      <tr
                        className="border-b border-border/60 align-top"
                        data-testid="job-row"
                        data-status={j.lastStatus || "NONE"}
                      >
                        <td className="py-2 pr-3">
                          <div className="font-medium">{j.method}</div>
                          <div className="font-mono text-[11px] text-muted-foreground">{j.bean}</div>
                        </td>
                        <td className="py-2 pr-3 font-mono text-xs">{j.schedule || UNAVAILABLE}</td>
                        <td className="py-2 pr-3">
                          <ToneChip tone={meta.tone} label={meta.label} />
                          {j.lastError && (
                            <button
                              type="button"
                              className="ml-2 border-0 bg-transparent p-0 text-xs text-destructive underline underline-offset-2"
                              onClick={() => setExpanded(open ? null : j.id)}
                              aria-expanded={open}
                            >
                              {open ? "Hide error" : "Show error"}
                            </button>
                          )}
                        </td>
                        <td className="py-2 pr-3 text-xs" title={formatExact(j.lastStartedAt)}>
                          {j.lastStartedAt ? formatRelative(j.lastStartedAt) : UNAVAILABLE}
                        </td>
                        <td className="py-2 pr-3 font-mono text-xs">{formatDurationMs(j.lastDurationMs)}</td>
                        <td className="py-2 text-right">
                          {j.runNowAllowed ? (
                            <Button
                              size="sm"
                              variant="outline"
                              disabled={running != null || j.lastStatus === "RUNNING"}
                              onClick={() => requestRun(j)}
                            >
                              <Play /> {running === j.id ? "Starting..." : "Run now"}
                            </Button>
                          ) : (
                            <span
                              className="text-[11px] text-muted-foreground"
                              title="Core does not allow this job to be triggered by hand."
                            >
                              schedule only
                            </span>
                          )}
                        </td>
                      </tr>
                      {open && j.lastError && (
                        <tr className="border-b border-border/60">
                          <td colSpan={6} className="pb-3">
                            <pre className="max-h-48 overflow-auto whitespace-pre-wrap rounded-md bg-destructive/5 p-2 font-mono text-[11px] text-destructive">
                              {j.lastError}
                            </pre>
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </AdminTableWrap>
        )}
        <PaginationControls
          page={pg.page}
          totalPages={pg.totalPages}
          totalItems={pg.totalItems}
          pageSize={pg.pageSize}
          onPageChange={pg.setPage}
          onPageSizeChange={pg.setPageSize}
          itemLabel="jobs"
        />
        <p className="flex items-center gap-1 pt-1 text-[11px] text-muted-foreground">
          <KeyRound size={12} />{" "}
          {masterKey ? "Master key unlocked for this session." : "Run now asks for the master key once per session."}
        </p>
      </SectionCard>

      <MasterKeyPrompt
        open={keyPromptFor != null}
        submitting={running != null}
        description="Unlocks running scheduled jobs by hand for the rest of this browser session. It is re-checked on every run."
        onCancel={() => setKeyPromptFor(null)}
        onSubmit={(key) => {
          const job = keyPromptFor;
          setMasterKey(key);
          setKeyPromptFor(null);
          if (job) void run(job, key);
        }}
      />
    </AdminShell>
  );
}
