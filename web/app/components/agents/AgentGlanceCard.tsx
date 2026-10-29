"use client";

// "At a glance": the one card near the top of the agent workbench that answers "is this agent doing OK?".
//
// Data, and what each number honestly is:
//   - `reliability` is the workbench's own GET /api/agents/{id}/reliability result, passed in (no second
//     fetch). Its counts are ALL-TIME totals over the agent's model-call and tool-call logs, so the card
//     captions them with firstCallAt..lastCallAt. `recentFailures` is a short, truncated list: it is shown as
//     plain text only and no rate is ever derived from it.
//   - GET /api/agent-jobs/counts?agentId= gives background-job counts. Terminal counts (succeeded / failed /
//     cancelled) cover the window the SERVER echoes back; queued / running / awaiting approval are "right now".
//     The two are never drawn in one bar. If the call fails the jobs block is simply absent.
//
// A rate with no denominator is "-" (SuccessRing's null state), never 0% or 100%. A brand-new agent gets a
// friendly empty state instead of a wall of zeros.
import { useEffect, useState } from "react";
import { Activity } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { ChartFrame, StackedStatusBar, SuccessRing, formatPercent, isFiniteNumber, rate, toMagnitude } from "../charts";
import type { ChartState, ChartTableData } from "../charts";
import type { AgentReliabilitySummary } from "./AgentWorkbench/types";

export type GlanceReliability = undefined | "loading" | "error" | AgentReliabilitySummary;

/** The fields of AgentJobCountsResponse this card reads. */
export interface GlanceJobCounts {
  queued: number;
  running: number;
  awaitingApproval: number;
  succeededInWindow: number;
  failedInWindow: number;
  cancelledInWindow: number;
  /** Whole hours between the echoed windowStart and asOf; null when the server did not echo a usable window. */
  windowHours: number | null;
}

const JOB_WINDOW = "P7D";
const MAX_FAILURES = 3;
const MAX_REASON_CHARS = 200;

type Translate = (key: string, values?: Record<string, string | number>) => string;

function count(value: unknown): number {
  return Math.round(toMagnitude(value));
}

/** Accepts only a well-formed counts payload; anything else is "no jobs data". */
export function normalizeJobCounts(raw: unknown): GlanceJobCounts | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  if (!isFiniteNumber(r.succeededInWindow) || !isFiniteNumber(r.failedInWindow)) return null;
  const start = typeof r.windowStart === "string" ? Date.parse(r.windowStart) : NaN;
  const end = typeof r.asOf === "string" ? Date.parse(r.asOf) : NaN;
  const hours =
    Number.isFinite(start) && Number.isFinite(end) && end > start ? Math.round((end - start) / 3_600_000) : null;
  return {
    queued: count(r.queued),
    running: count(r.running),
    awaitingApproval: count(r.awaitingApproval),
    succeededInWindow: count(r.succeededInWindow),
    failedInWindow: count(r.failedInWindow),
    cancelledInWindow: count(r.cancelledInWindow),
    windowHours: hours && hours > 0 ? hours : null,
  };
}

/** "the last 7 days" / "the last 36 hours" from the echoed window; a generic phrase when it was not echoed. */
export function windowPhrase(hours: number | null, t: Translate): string {
  if (!hours) return t("windowUnknown");
  if (hours % 24 === 0) {
    const days = hours / 24;
    return days === 1 ? t("windowOneDay") : t("windowDays", { count: days });
  }
  return hours === 1 ? t("windowOneHour") : t("windowHours", { count: hours });
}

function formatDuration(ms: number | null | undefined, t: Translate): string {
  if (!isFiniteNumber(ms) || ms < 0) return "-";
  if (ms < 1000) return t("milliseconds", { value: Math.round(ms) });
  return t("seconds", { value: (ms / 1000).toFixed(ms < 10_000 ? 1 : 0) });
}

function formatDay(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const time = Date.parse(iso);
  if (!Number.isFinite(time)) return null;
  return new Date(time).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}

function ringState(value: number | null): ChartState {
  if (value == null) return "idle";
  if (value >= 0.95) return "success";
  if (value >= 0.8) return "warning";
  return "error";
}

function failureReason(failure: { errorMessage: string | null; exceptionType: string | null }): string | null {
  const text = (failure.errorMessage || failure.exceptionType || "").replace(/\s+/g, " ").trim();
  if (!text) return null;
  return text.length > MAX_REASON_CHARS ? `${text.slice(0, MAX_REASON_CHARS - 1)}…` : text;
}

export default function AgentGlanceCard({ agentId, reliability }: { agentId: string; reliability: GlanceReliability }) {
  const t = useTranslations("agentGlance") as Translate;
  // Keyed by the agent it was fetched for, so switching agents never shows the previous agent's jobs.
  const [jobsFor, setJobsFor] = useState<{ agentId: string; counts: GlanceJobCounts | null } | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .getAgentJobCounts(agentId, JOB_WINDOW)
      .then((raw: unknown) => {
        if (!cancelled) setJobsFor({ agentId, counts: normalizeJobCounts(raw) });
      })
      .catch(() => {
        // Older core (no route), no access, or a transient failure: the jobs block is left out.
        if (!cancelled) setJobsFor({ agentId, counts: null });
      });
    return () => {
      cancelled = true;
    };
  }, [agentId]);

  const jobsSettled = jobsFor !== null && jobsFor.agentId === agentId;
  const jobs = jobsSettled ? jobsFor.counts : null;
  const loading = reliability === undefined || reliability === "loading" || !jobsSettled;
  const failed = reliability === "error";
  const data = typeof reliability === "object" ? reliability : null;

  const totalCalls = count(data?.totalCalls);
  const okCalls = Math.max(0, totalCalls - count(data?.failedCalls));
  const callRate = rate(okCalls, totalCalls);
  const toolCalls = count(data?.toolCallCount);
  const toolFailed = Math.min(toolCalls, count(data?.toolCallFailureCount));
  const toolOk = toolCalls - toolFailed;
  const toolRate = rate(toolOk, toolCalls);
  const fallbacks = count(data?.fallbackTriggeredCount);

  const jobsFinished = jobs ? jobs.succeededInWindow + jobs.failedInWindow + jobs.cancelledInWindow : 0;
  const jobsLive = jobs ? jobs.queued + jobs.running + jobs.awaitingApproval : 0;
  const hasActivity = totalCalls > 0 || toolCalls > 0 || fallbacks > 0 || jobsFinished > 0 || jobsLive > 0;

  const firstDay = formatDay(data?.firstCallAt);
  const lastDay = formatDay(data?.lastCallAt);
  const range =
    firstDay && lastDay
      ? firstDay === lastDay
        ? t("rangeOneDay", { day: firstDay })
        : t("range", { from: firstDay, to: lastDay })
      : t("rangeUnknown");

  const percent = formatPercent(callRate) ?? "-";
  const toolPercent = formatPercent(toolRate) ?? "-";
  const summary =
    totalCalls > 0 ? t("summary", { ok: okCalls, total: totalCalls, percent, range }) : t("summaryNoCalls");
  const windowText = windowPhrase(jobs ? jobs.windowHours : null, t);
  const jobsSummary = jobs
    ? jobsFinished > 0
      ? t("jobsSummary", {
          window: windowText,
          succeeded: jobs.succeededInWindow,
          failed: jobs.failedInWindow,
          cancelled: jobs.cancelledInWindow,
        })
      : t("jobsNone", { window: windowText })
    : "";
  const reasons = (data?.recentFailures ?? [])
    .map(failureReason)
    .filter((x): x is string => !!x)
    .slice(0, MAX_FAILURES);

  const table: ChartTableData = {
    columns: [
      { key: "metric", header: t("tableMetric") },
      { key: "value", header: t("tableValue"), align: "right" },
    ],
    rows: [
      {
        id: "calls",
        metric: t("rowCalls"),
        value: totalCalls > 0 ? t("okOfTotal", { ok: okCalls, total: totalCalls }) : "-",
      },
      { id: "rate", metric: t("rowCallRate"), value: percent },
      { id: "latency", metric: t("rowLatency"), value: formatDuration(data?.avgResponseTimeMs, t) },
      {
        id: "tools",
        metric: t("rowTools"),
        value: toolCalls > 0 ? t("okOfTotal", { ok: toolOk, total: toolCalls }) : "-",
      },
      { id: "toolRate", metric: t("rowToolRate"), value: toolPercent },
      { id: "fallbacks", metric: t("rowFallbacks"), value: fallbacks },
      ...(jobs
        ? [
            { id: "jobsOk", metric: t("rowJobsSucceeded", { window: windowText }), value: jobs.succeededInWindow },
            { id: "jobsFailed", metric: t("rowJobsFailed", { window: windowText }), value: jobs.failedInWindow },
            {
              id: "jobsCancelled",
              metric: t("rowJobsCancelled", { window: windowText }),
              value: jobs.cancelledInWindow,
            },
            { id: "jobsQueued", metric: t("rowJobsQueued"), value: jobs.queued },
            { id: "jobsRunning", metric: t("rowJobsRunning"), value: jobs.running },
            { id: "jobsAwaiting", metric: t("rowJobsAwaiting"), value: jobs.awaitingApproval },
          ]
        : []),
    ],
    caption: t("tableCaption", { range }),
  };

  return (
    <div className="min-w-0 rounded-xl border border-border bg-card p-4" data-testid="agent-glance">
      <ChartFrame
        title={t("title")}
        summary={summary}
        table={table}
        loading={loading && !failed}
        error={failed ? t("loadError") : undefined}
        empty={!loading && !failed && !hasActivity}
        emptyIcon={Activity}
        emptyTitle={t("emptyTitle")}
        emptyDescription={t("emptyDescription")}
        footer={t("footer", { range })}
      >
        <div className="grid min-w-0 grid-cols-1 gap-4 sm:grid-cols-2">
          <SuccessRing
            rate={callRate}
            label={t("ringLabel")}
            state={ringState(callRate)}
            statusLabel={totalCalls > 0 ? t("ringStatus", { ok: okCalls, total: totalCalls }) : t("ringNoCalls")}
            ariaLabel={summary}
          />
          <dl className="m-0 grid min-w-0 grid-cols-2 gap-3">
            <div className="min-w-0" data-testid="agent-glance-latency">
              <dt className="text-xs text-muted-foreground">{t("latencyLabel")}</dt>
              <dd className="m-0 text-base font-semibold tabular-nums text-foreground">
                {formatDuration(data?.avgResponseTimeMs, t)}
              </dd>
            </div>
            <div className="min-w-0" data-testid="agent-glance-fallbacks">
              <dt className="text-xs text-muted-foreground">{t("fallbackLabel")}</dt>
              <dd className="m-0 text-base font-semibold tabular-nums text-foreground">{fallbacks}</dd>
            </div>
          </dl>
        </div>

        <div className="mt-4 min-w-0" data-testid="agent-glance-tools">
          <p className="m-0 mb-1.5 text-xs font-medium text-foreground">{t("toolsTitle")}</p>
          {toolCalls > 0 ? (
            <>
              <p className="m-0 mb-1.5 text-xs text-muted-foreground">
                {t("toolsSummary", { ok: toolOk, total: toolCalls, percent: toolPercent })}
              </p>
              <StackedStatusBar
                ariaLabel={t("toolsSummary", { ok: toolOk, total: toolCalls, percent: toolPercent })}
                segments={[
                  { key: "ok", label: t("segmentSucceeded"), count: toolOk, state: "success" },
                  { key: "failed", label: t("segmentFailed"), count: toolFailed, state: "error" },
                ]}
              />
            </>
          ) : (
            <p className="m-0 text-xs text-muted-foreground">{t("toolsNone")}</p>
          )}
        </div>

        {jobs && (
          <div className="mt-4 min-w-0" data-testid="agent-glance-jobs">
            <p className="m-0 mb-1.5 text-xs font-medium text-foreground">{t("jobsTitle")}</p>
            <p className="m-0 mb-1.5 text-xs text-muted-foreground">{jobsSummary}</p>
            {jobsFinished > 0 && (
              <StackedStatusBar
                ariaLabel={jobsSummary}
                segments={[
                  { key: "succeeded", label: t("segmentSucceeded"), count: jobs.succeededInWindow, state: "success" },
                  { key: "failed", label: t("segmentFailed"), count: jobs.failedInWindow, state: "error" },
                  { key: "cancelled", label: t("segmentCancelled"), count: jobs.cancelledInWindow, state: "idle" },
                ]}
              />
            )}
            <p className="m-0 mt-1.5 text-xs text-muted-foreground" data-testid="agent-glance-jobs-live">
              {jobsLive > 0
                ? t("jobsLive", { queued: jobs.queued, running: jobs.running, awaiting: jobs.awaitingApproval })
                : t("jobsLiveNone")}
            </p>
          </div>
        )}

        {reasons.length > 0 && (
          <div className="mt-4 min-w-0" data-testid="agent-glance-failures">
            <p className="m-0 mb-1 text-xs font-medium text-foreground">{t("failuresTitle")}</p>
            <ul className="m-0 list-disc space-y-1 ps-4">
              {reasons.map((reason, index) => (
                <li key={index} className="break-words text-xs text-muted-foreground [overflow-wrap:anywhere]">
                  {reason}
                </li>
              ))}
            </ul>
          </div>
        )}
      </ChartFrame>
    </div>
  );
}
