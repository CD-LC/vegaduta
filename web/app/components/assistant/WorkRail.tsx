"use client";

import Link from "next/link";
import { CheckCircle2, CircleDot, ExternalLink, Hourglass, Loader2, MessageSquareText, OctagonX, ShieldQuestion, Square, XCircle } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import type { AgentJobSummaryResponse } from "../../lib/api/types";
import { Button } from "../ui/button";
import { isLivePhase, jobPhase, type JobPhase } from "./backgroundWork";
import type { WorkCounts } from "./useWorkCounts";
import LearnedSection from "./LearnedSection";
import type { Learned } from "./useLearned";

const PHASE_ICON: Record<JobPhase, typeof CircleDot> = {
  queued: Hourglass,
  running: Loader2,
  waiting: ShieldQuestion,
  done: CheckCircle2,
  failed: XCircle,
  stopped: OctagonX,
};

function ago(iso: string | null | undefined): string {
  const then = iso ? Date.parse(iso) : NaN;
  if (Number.isNaN(then)) return "";
  const secs = Math.round((then - Date.now()) / 1000);
  const fmt = new Intl.RelativeTimeFormat(undefined, { numeric: "auto", style: "short" });
  const abs = Math.abs(secs);
  if (abs < 60) return fmt.format(Math.round(secs / 10) * 10 || 0, "second");
  if (abs < 3600) return fmt.format(Math.round(secs / 60), "minute");
  if (abs < 86400) return fmt.format(Math.round(secs / 3600), "hour");
  return fmt.format(Math.round(secs / 86400), "day");
}

/** One background task: what it is, how far it got, and what can be done with it. */
function TaskRow({
  job,
  onStop,
  onShow,
  stopping,
}: {
  job: AgentJobSummaryResponse;
  onStop: (job: AgentJobSummaryResponse) => void;
  onShow: (job: AgentJobSummaryResponse) => void;
  stopping: boolean;
}) {
  const t = useTranslations("assistant");
  const phase = jobPhase(job.status);
  const Icon = PHASE_ICON[phase];
  const live = isLivePhase(phase);
  const tone =
    phase === "failed" ? "var(--state-error)" : phase === "waiting" ? "var(--state-warning)" : phase === "done" ? "var(--state-success, currentColor)" : undefined;
  return (
    <li className="asst-card rounded-lg border p-2.5 text-sm" data-testid={`task-${job.id}`} data-phase={phase}>
      <div className="flex items-start gap-2">
        <Icon
          size={15}
          className={cn("mt-0.5 shrink-0", phase === "running" && "animate-spin text-primary", !tone && phase !== "running" && "text-muted-foreground")}
          style={tone ? { color: tone } : undefined}
          aria-hidden="true"
        />
        <div className="min-w-0 flex-1">
          <p className="line-clamp-2 break-words font-medium text-foreground" title={job.goalPreview ?? undefined}>
            {job.goalPreview || t("taskUntitled")}
          </p>
          <p className="mt-0.5 text-xs text-muted-foreground">
            {t(`phase_${phase}`)}
            {ago(job.finishedAt ?? job.startedAt ?? job.createdAt) && ` · ${ago(job.finishedAt ?? job.startedAt ?? job.createdAt)}`}
          </p>
          {live && job.progressNote && <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">{job.progressNote}</p>}
          {phase === "waiting" && <p className="mt-1 text-xs text-muted-foreground">{t("taskWaitingHint")}</p>}
        </div>
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-1.5">
        {(phase === "done" || phase === "failed" || phase === "stopped") && (
          <Button type="button" size="sm" variant="outline" className="h-7 gap-1 px-2 text-xs" onClick={() => onShow(job)}>
            <MessageSquareText size={12} aria-hidden="true" />
            {t("taskShow")}
          </Button>
        )}
        {(phase === "queued" || phase === "running") && (
          <Button type="button" size="sm" variant="outline" className="h-7 gap-1 px-2 text-xs" onClick={() => onStop(job)} disabled={stopping}>
            <Square size={11} aria-hidden="true" />
            {stopping ? t("taskStopping") : t("taskStop")}
          </Button>
        )}
        {phase === "waiting" && (
          <Button asChild size="sm" variant="outline" className="h-7 px-2 text-xs">
            <Link href="/admin/approvals">{t("taskReview")}</Link>
          </Button>
        )}
        <Link
          href={`/jobs/${job.agentId}/${job.id}`}
          className="inline-flex items-center gap-1 text-xs text-muted-foreground underline-offset-2 hover:text-foreground hover:underline"
        >
          <ExternalLink size={11} aria-hidden="true" />
          {t("taskOpen")}
        </Link>
      </div>
    </li>
  );
}

/**
 * The right rail: everything that outlives a turn. Phase 1 shows two counts - approvals waiting on a
 * person and jobs running or parked - each linking to the console page that already manages them.
 * Phase 2 adds the live lists (jobs with their trace, routines, finished today) and notifications.
 */
export default function WorkRail({
  counts,
  onStopJob,
  onShowJob,
  stoppingId,
  learned,
}: {
  counts: WorkCounts;
  onStopJob: (job: AgentJobSummaryResponse) => void;
  onShowJob: (job: AgentJobSummaryResponse) => void;
  stoppingId: string | null;
  /** What the assistant has learned; absent where the surface is shown without the learning reads. */
  learned?: Learned;
}) {
  const t = useTranslations("assistant");
  const needsYou = counts.pendingApprovals + counts.parkedJobs;
  const quiet = counts.reachable !== null && needsYou === 0 && counts.runningJobs === 0;
  return (
    <aside aria-label={t("workLabel")} className="flex h-full min-h-0 flex-col">
      <div className="px-3 py-3">
        <h2 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{t("workLabel")}</h2>
      </div>
      <div className="min-h-0 flex-1 space-y-2 overflow-y-auto px-3 pb-3">
        {counts.reachable === null && (
          <p className="flex items-center gap-2 text-xs text-muted-foreground">
            <Loader2 size={14} className="animate-spin" aria-hidden="true" />
            {t("workLoading")}
          </p>
        )}
        {counts.reachable === false && <p className="text-xs text-muted-foreground">{t("workUnavailable")}</p>}
        <Link
          href="/admin/approvals"
          className="flex items-start gap-2 rounded-lg border border-border px-3 py-2 text-sm hover:bg-secondary/60"
          data-testid="work-needs-you"
        >
          <ShieldQuestion size={16} className="mt-0.5 shrink-0" style={{ color: "var(--state-warning)" }} aria-hidden="true" />
          <span className="min-w-0">
            <span className="block font-medium text-foreground">{t("needsYou", { n: needsYou })}</span>
            <span className="block text-xs text-muted-foreground">
              {counts.parkedJobs > 0 ? t("needsYouDetailParked", { n: counts.parkedJobs }) : t("needsYouDetail")}
            </span>
          </span>
        </Link>
        <Link
          href="/jobs/all"
          className="flex items-start gap-2 rounded-lg border border-border px-3 py-2 text-sm hover:bg-secondary/60"
          data-testid="work-running"
        >
          <CircleDot
            size={16}
            className={counts.runningJobs > 0 ? "mt-0.5 shrink-0 animate-pulse text-primary" : "mt-0.5 shrink-0 text-muted-foreground"}
            aria-hidden="true"
          />
          <span className="min-w-0">
            <span className="block font-medium text-foreground">{t("running", { n: counts.runningJobs })}</span>
            <span className="block text-xs text-muted-foreground">{t("runningDetail")}</span>
          </span>
        </Link>
        <h3 className="px-1 pt-3 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">{t("tasksTitle")}</h3>
        {counts.jobs.length === 0 ? (
          <p className="px-1 text-xs text-muted-foreground">{quiet ? t("nothingRunning") : t("tasksEmpty")}</p>
        ) : (
          <ul className="space-y-2" aria-label={t("tasksTitle")}>
            {counts.jobs.map((job) => (
              <TaskRow key={job.id} job={job} onStop={onStopJob} onShow={onShowJob} stopping={stoppingId === job.id} />
            ))}
          </ul>
        )}
        <RoutinesSection routines={counts.routines} />
        {learned && <LearnedSection learned={learned} />}
      </div>
    </aside>
  );
}

/** The old Jobs page's second tab, in the rail: each routine with its next run, and the two pages that manage them. */
function RoutinesSection({ routines }: { routines: WorkCounts["routines"] }) {
  const t = useTranslations("assistant");
  return (
    <section data-testid="work-routines">
      <h3 className="px-1 pt-3 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">{t("scheduledTitle")}</h3>
      {routines.length === 0 ? (
        <p className="px-1 text-xs text-muted-foreground">{t("scheduledEmpty")}</p>
      ) : (
        <ul className="space-y-1.5" aria-label={t("scheduledTitle")}>
          {routines.slice(0, 8).map((r) => (
            <li key={r.id} className="rounded-lg border border-border px-2.5 py-2 text-xs" data-testid={`routine-${r.id}`}>
              <p className="truncate font-medium text-foreground" title={r.prompt}>
                {r.name}
              </p>
              <p className="mt-0.5 text-muted-foreground">
                {!r.enabled ? t("scheduledPaused") : r.nextRunAt ? t("scheduledNext", { when: ago(r.nextRunAt) }) : r.scheduleDescription}
              </p>
            </li>
          ))}
        </ul>
      )}
      <p className="mt-1.5 flex flex-wrap gap-x-3 px-1 text-xs">
        <Link href="/scheduled" className="text-muted-foreground underline-offset-2 hover:text-foreground hover:underline">
          {t("scheduledOpen")}
        </Link>
        <Link href="/jobs/schedules" className="text-muted-foreground underline-offset-2 hover:text-foreground hover:underline">
          {t("agentSchedulesOpen")}
        </Link>
      </p>
    </section>
  );
}
