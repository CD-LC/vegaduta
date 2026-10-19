"use client";

import { useMemo, useState } from "react";
import { useTranslations } from "../../lib/i18n";
import { formatRunTime, type ScheduledTask } from "../../lib/scheduledTasks";
import { ChartFrame, StackedStatusBar } from "../charts";
import { taskStatusCounts, upcomingWithin, UPCOMING_PER_TASK } from "./scheduledTaskStats";

/** At most this many timeline lines; the rest is counted in an "and N more" line, never dropped silently. */
const TIMELINE_SHOWN = 8;

/**
 * The header of /scheduled: how many tasks are active / failing / paused (one bar plus a sentence that says the
 * same thing), and what is due in the next 24 hours.
 *
 * The task counts are exact - GET /api/scheduled-tasks is not paginated. The timeline is built from each task's
 * `upcomingRuns`, which holds only its next 3 runs, so a task that fires more often than that gets a note saying
 * more runs may follow. Times are shown in each task's own time zone, as on the task cards.
 */
export default function ScheduledTasksSummary({ tasks }: { tasks: ScheduledTask[] }) {
  const t = useTranslations("scheduledTasks");
  // One clock reading per mount: the window does not slide while the page sits open, and a reload refreshes it.
  const [now] = useState(() => Date.now());
  const counts = useMemo(() => taskStatusCounts(tasks), [tasks]);
  const upcoming = useMemo(() => upcomingWithin(tasks, now), [tasks, now]);

  if (counts.total === 0) return null;

  const word = (n: number) => (n === 0 ? t("summaryNone") : String(n));
  const summary = t(counts.total === 1 ? "summarySentenceOne" : "summarySentence", {
    total: counts.total,
    active: word(counts.active),
    paused: word(counts.paused),
    failing: word(counts.failing),
  });

  const rows = [
    { id: "active", state: t("summaryActive"), count: counts.active, meaning: t("summaryActiveMeaning") },
    { id: "failing", state: t("summaryFailing"), count: counts.failing, meaning: t("summaryFailingMeaning") },
    { id: "paused", state: t("summaryPaused"), count: counts.paused, meaning: t("summaryPausedMeaning") },
  ];

  const shown = upcoming.runs.slice(0, TIMELINE_SHOWN);
  const hidden = upcoming.runs.length - shown.length;

  return (
    <div className="grid min-w-0 grid-cols-1 gap-4 lg:grid-cols-2" data-testid="scheduled-tasks-summary">
      <ChartFrame
        className="min-w-0 rounded-lg border border-border bg-card p-4"
        title={t("summaryTitle")}
        summary={summary}
        table={{
          columns: [
            { key: "state", header: t("summaryColState") },
            { key: "count", header: t("summaryColTasks"), align: "right" },
            { key: "meaning", header: t("summaryColMeaning") },
          ],
          rows,
        }}
      >
        <StackedStatusBar
          segments={[
            { key: "active", label: t("summaryActive"), count: counts.active, state: "success" },
            { key: "failing", label: t("summaryFailing"), count: counts.failing, state: "error" },
            { key: "paused", label: t("summaryPaused"), count: counts.paused, state: "idle" },
          ]}
        />
      </ChartFrame>

      <section className="min-w-0 space-y-2 rounded-lg border border-border bg-card p-4" aria-labelledby="scheduled-next-24h">
        <h3 id="scheduled-next-24h" className="text-sm font-semibold tracking-tight text-foreground">
          {t("upcomingTitle")}
        </h3>
        {shown.length === 0 ? (
          <p className="text-sm text-muted-foreground" data-testid="scheduled-upcoming-empty">
            {t(counts.active + counts.failing === 0 ? "upcomingAllPaused" : "upcomingNone")}
          </p>
        ) : (
          <>
            <p className="text-sm text-muted-foreground">
              {t(upcoming.runs.length === 1 ? "upcomingSentenceOne" : "upcomingSentence", { count: upcoming.runs.length })}
            </p>
            <ol className="m-0 list-none space-y-1.5 p-0" data-testid="scheduled-upcoming-list">
              {shown.map((run) => (
                <li key={`${run.taskId}:${run.at}`} className="flex min-w-0 flex-col gap-x-3 border-l-2 border-border pl-3 text-sm sm:flex-row sm:items-baseline">
                  <time dateTime={run.at} className="shrink-0 text-xs tabular-nums text-muted-foreground">
                    {formatRunTime(run.at, run.timezone)}
                  </time>
                  <span className="min-w-0 break-words text-foreground">{run.taskName}</span>
                </li>
              ))}
            </ol>
            {hidden > 0 && <p className="text-xs text-muted-foreground">{t("upcomingMore", { count: hidden })}</p>}
            {upcoming.mayHaveMore.length > 0 && (
              <p className="text-xs text-muted-foreground" data-testid="scheduled-upcoming-partial">
                {t("upcomingPartial", { count: UPCOMING_PER_TASK })}
              </p>
            )}
          </>
        )}
      </section>
    </div>
  );
}
