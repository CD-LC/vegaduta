"use client";

// The "is this workflow healthy?" strip at the top of a workflow's run history.
//
// Data: ONE call to GET /api/workflows/{id}/eval-summary?days=N (core
// WorkflowInsightsService, cached 60 s server-side). It deliberately does NOT
// read /workflows/{id}/runs - that list is unbounded and the server already
// did the counting. Every number here is the server's own; nothing is
// recomputed from a list (see workflowHealth.ts for the rules).
//
// States: loading skeleton, error with retry, an empty state with a link to
// the canvas, and - when core predates the endpoint - nothing at all.
import { useEffect, useMemo, useState, type ReactNode } from "react";
import Link from "next/link";
import { PERIOD_PILL_FOCUS_CLASS } from "../charts/periodPill";
import { Activity, AlertTriangle, CheckCircle2, CircleSlash } from "lucide-react";
import { api } from "../../lib/api";
import type { FeatureResult, WorkflowEvalSummary } from "../../lib/api/workflows";
import { useLocale, useTranslations } from "../../lib/i18n";
import { ChartFrame, RankedBars, SuccessRing, formatPercent, stateColor } from "../charts";
import type { ChartTableData } from "../charts";
import { Button } from "../ui/button";
import { formatCostUsd, formatDuration } from "./runModel";
import {
  HEALTH_PERIODS,
  KNOWN_RUN_STATUSES,
  buildWorkflowHealth,
  healthBand,
  relativeTime,
  runStatusState,
  type HealthPeriod,
} from "./workflowHealth";

const DASH = "-";

type Loaded = {
  key: string;
  result: FeatureResult<WorkflowEvalSummary>;
  fetchedAt: number;
};

export interface WorkflowHealthStripProps {
  workflowId: string;
  /** Node id -> display name, from the workflow definition. Ids are shown when a name is missing. */
  nodeLabels?: Record<string, string>;
  /** Change this to make the strip re-fetch (e.g. when a live run finishes). */
  refreshKey?: string | number;
  defaultDays?: HealthPeriod;
  className?: string;
}

export default function WorkflowHealthStrip({
  workflowId,
  nodeLabels,
  refreshKey = 0,
  defaultDays = 30,
  className,
}: WorkflowHealthStripProps) {
  const t = useTranslations("workflowRunsPage");
  const locale = useLocale();
  const [days, setDays] = useState<HealthPeriod>(defaultDays);
  const [attempt, setAttempt] = useState(0);
  const [loaded, setLoaded] = useState<Loaded | null>(null);

  // `viewKey` decides what is on screen (a new workflow, period or retry shows
  // the skeleton); `refreshKey` only re-fetches, so a background refresh keeps
  // the current numbers visible until the new ones arrive.
  const viewKey = `${workflowId}|${days}|${attempt}`;
  useEffect(() => {
    if (!workflowId) return undefined;
    let cancelled = false;
    void api.getWorkflowEvalSummary(workflowId, days).then((result: FeatureResult<WorkflowEvalSummary>) => {
      if (!cancelled) setLoaded({ key: viewKey, result, fetchedAt: Date.now() });
    });
    return () => {
      cancelled = true;
    };
  }, [workflowId, days, viewKey, refreshKey]);

  const current = loaded && loaded.key === viewKey ? loaded : null;
  const summary = current?.result.ok ? current.result.data : null;
  const health = useMemo(() => buildWorkflowHealth(summary, nodeLabels, days), [summary, nodeLabels, days]);

  // Core older than the endpoint: hide the strip rather than show a permanent error.
  if (current && !current.result.ok && current.result.unsupported) return null;

  const loading = !current;
  const failed = !!current && !current.result.ok;
  const empty = !loading && !failed && health.runs === 0;

  const percent = (rate: number | null) => formatPercent(rate) ?? DASH;
  const duration = (ms: number | null) => (ms === null ? DASH : formatDuration(ms));
  const cost = (usd: number | null) => (usd === null ? DASH : formatCostUsd(usd, locale));

  const band = healthBand(health.successRate);
  const BandIcon = band.key === "healthy" ? CheckCircle2 : band.key === "none" ? CircleSlash : AlertTriangle;

  const generatedAt = summary?.generatedAt ? Date.parse(summary.generatedAt) : NaN;
  const now = Number.isFinite(generatedAt) ? generatedAt : (current?.fetchedAt ?? null);
  const lastRunAgo = relativeTime(health.lastRunAt, now, locale);
  const lastStatus = (health.lastRunStatus || "").toUpperCase();
  const lastStatusLabel = !health.lastRunStatus
    ? DASH
    : (KNOWN_RUN_STATUSES as readonly string[]).includes(lastStatus)
      ? t(`healthStatus_${lastStatus}`)
      : health.lastRunStatus;

  const summaryText =
    health.successRate === null
      ? t("healthSummaryNoRate", { days: health.days, runs: health.runs })
      : t("healthSummary", { rate: percent(health.successRate), days: health.days, runs: health.runs });

  const table: ChartTableData = {
    columns: [
      { key: "step", header: t("healthColStep") },
      { key: "runs", header: t("healthColRuns"), align: "right" },
      { key: "visits", header: t("healthColVisits"), align: "right" },
      { key: "success", header: t("healthColSuccess"), align: "right" },
      { key: "errors", header: t("healthColErrors"), align: "right" },
      { key: "duration", header: t("healthColDuration"), align: "right" },
      { key: "cost", header: t("healthColCost"), align: "right" },
    ],
    rows: [
      {
        id: "__workflow__",
        step: t("healthWholeWorkflow"),
        runs: health.runs,
        visits: DASH,
        success: percent(health.successRate),
        errors: DASH,
        duration: duration(health.p50DurationMs),
        cost: cost(health.avgCostUsd),
      },
      ...health.nodes.map((node) => ({
        id: node.id,
        step: node.label,
        runs: node.runs,
        visits: node.visits,
        success: percent(node.successRate),
        errors: percent(node.errorRate),
        duration: duration(node.p50DurationMs),
        cost: cost(node.avgCostUsd),
      })),
    ],
    caption: health.sampled ? `${t("healthTableCaption")} ${t("healthSampledNote", { count: health.runs })}` : t("healthTableCaption"),
  };

  const periodSwitch = (
    <div role="group" aria-label={t("healthPeriodLabel")} className="flex items-center gap-1">
      {HEALTH_PERIODS.map((option) => (
        <Button
          key={option}
          type="button"
          size="sm"
          variant={option === days ? "default" : "outline"}
          aria-pressed={option === days}
          aria-label={t("healthPeriodOptionAria", { days: option })}
          data-health-period={option}
          className={PERIOD_PILL_FOCUS_CLASS}
          onClick={() => setDays(option)}
        >
          {t("healthPeriodOption", { days: option })}
        </Button>
      ))}
    </div>
  );

  return (
    <div data-testid="workflow-health-strip" className={className}>
      <ChartFrame
        title={t("healthTitle")}
        summary={summaryText}
        periodSwitch={periodSwitch}
        table={table}
        loading={loading}
        error={
          failed ? (
            <span className="flex flex-wrap items-center gap-3">
              <span>{t("healthLoadError")}</span>
              <Button type="button" size="sm" variant="outline" onClick={() => setAttempt((n) => n + 1)}>
                {t("healthRetry")}
              </Button>
            </span>
          ) : undefined
        }
        empty={empty}
        emptyIcon={Activity}
        emptyTitle={t("healthEmptyTitle", { days: health.days })}
        emptyDescription={t("healthEmptyDescription")}
        emptyAction={
          <Button asChild size="sm">
            <Link href={`/workflows/${workflowId}`}>{t("healthEmptyAction")}</Link>
          </Button>
        }
        className="rounded-xl border border-border bg-card/40 p-4"
      >
        <div className="min-w-0 space-y-4">
          <div className="flex min-w-0 flex-wrap items-center gap-x-6 gap-y-4">
            <SuccessRing
              rate={health.successRate}
              label={t("healthSuccessLabel")}
              state={band.state}
              statusLabel={t(`healthBand_${band.key}`)}
              icon={<BandIcon aria-hidden="true" size={12} />}
              nullText={DASH}
            />
            <dl className="m-0 grid min-w-0 flex-1 basis-64 grid-cols-2 gap-x-4 gap-y-3 sm:grid-cols-4">
              <Stat label={t("healthRunsLabel")} value={String(health.runs)} hint={t("healthRunsHint", { days: health.days })} />
              <Stat label={t("healthDurationLabel")} value={duration(health.p50DurationMs)} hint={t("healthDurationHint")} />
              <Stat label={t("healthCostLabel")} value={cost(health.avgCostUsd)} hint={t("healthCostHint")} />
              <Stat
                label={t("healthLastRunLabel")}
                value={
                  <span className="inline-flex min-w-0 items-center gap-1.5">
                    {health.lastRunStatus && (
                      <span
                        aria-hidden="true"
                        className="inline-block size-2 shrink-0 rounded-full"
                        style={{ background: stateColor(runStatusState(health.lastRunStatus)) }}
                      />
                    )}
                    <span className="min-w-0 break-words">{lastStatusLabel}</span>
                  </span>
                }
                hint={lastRunAgo ?? undefined}
              />
            </dl>
          </div>

          {health.sampled && (
            <p data-health-sampled="" role="note" className="m-0 text-xs text-muted-foreground">
              {t("healthSampledNote", { count: health.runs })}
            </p>
          )}

          <div className="grid min-w-0 gap-x-6 gap-y-4 md:grid-cols-2">
            <RankedList
              title={t("healthSlowestTitle")}
              hint={t("healthSlowestHint")}
              emptyText={t("healthSlowestEmpty")}
              testId="health-slowest"
            >
              {health.slowest.length > 0 && (
                <RankedBars
                  sort={false}
                  ariaLabel={t("healthSlowestTitle")}
                  color={stateColor("running")}
                  items={health.slowest.map((node) => ({
                    key: node.id,
                    label: node.label,
                    value: node.p50DurationMs ?? 0,
                    valueText: duration(node.p50DurationMs),
                  }))}
                />
              )}
            </RankedList>
            <RankedList
              title={t("healthFailingTitle")}
              hint={t("healthFailingHint")}
              emptyText={t("healthFailingEmpty")}
              testId="health-failing"
            >
              {health.mostFailing.length > 0 && (
                <RankedBars
                  sort={false}
                  scaleMax={1}
                  ariaLabel={t("healthFailingTitle")}
                  color={stateColor("error")}
                  items={health.mostFailing.map((node) => ({
                    key: node.id,
                    label: node.label,
                    value: node.errorRate ?? 0,
                    valueText: t("healthFailedShare", { percent: percent(node.errorRate) }),
                  }))}
                />
              )}
            </RankedList>
          </div>
        </div>
      </ChartFrame>
    </div>
  );
}

function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: string }) {
  return (
    <div className="min-w-0">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="m-0 text-base font-semibold tabular-nums text-foreground">{value}</dd>
      {hint && <dd className="m-0 text-xs text-muted-foreground">{hint}</dd>}
    </div>
  );
}

function RankedList({
  title,
  hint,
  emptyText,
  testId,
  children,
}: {
  title: string;
  hint: string;
  emptyText: string;
  testId: string;
  children: ReactNode;
}) {
  return (
    <div className="min-w-0 space-y-2" data-testid={testId}>
      <div>
        <h4 className="m-0 text-sm font-medium text-foreground">{title}</h4>
        <p className="m-0 text-xs text-muted-foreground">{hint}</p>
      </div>
      {children || <p className="m-0 text-sm text-muted-foreground">{emptyText}</p>}
    </div>
  );
}
