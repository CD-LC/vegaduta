"use client";

// Insights: one screen answering "how are my AI workers doing?" in plain words.
//
// Data (both existing core endpoints, see lib/api/insights.ts):
//   - /api/admin/spend-reports/{summary,by-day,by-model,by-agent} for the
//     workspace's AI calls, success rate, estimated cost and response time.
//     Needs the VIEW_USAGE permission; a 403 is an expected state, not an
//     error: the page then shows the job section only, with a short note.
//   - /api/agent-jobs/counts for the "Jobs right now" bar, open to everyone.
//     `tenantWide` in the answer decides the "Your agents" label.
//
// Every number rule (no rate from zero calls, "~" on percentiles, cost always
// "estimated", partial today, zero-filled days, the window the server applied)
// lives in insightsModel.ts. This file only fetches, lays out and translates.
import { useEffect, useMemo, useState, type ReactNode } from "react";
import Link from "next/link";
import { SeeCostPerAgentLink } from "../roi/RoiFamily";
import { PERIOD_PILL_FOCUS_CLASS } from "../components/charts/periodPill";
import { BarChart3, Coins, ListChecks, Timer, TrendingUp, Zap } from "lucide-react";
import AppShell from "../components/AppShell";
import DemoWatermark from "../roi/DemoWatermark";
import { useAuth } from "../components/AuthProvider";
import { usePageReady } from "../components/RouteTransition";
import PageHeader from "../components/ax/PageHeader";
import { EmptyState, Section, StatCard } from "../components/ax/page-kit";
import { ChartFrame, MiniBars, RankedBars, StackedStatusBar } from "../components/charts";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Card } from "../components/ui/card";
import { Skeleton } from "../components/ui/skeleton";
import { api } from "../lib/api";
import {
  insightsApi,
  isForbidden,
  type AgentJobCountsDto,
  type AgentUsageDto,
  type DayUsageDto,
  type ModelUsageDto,
  type SpendSummaryDto,
} from "../lib/api/insights";
import { useTranslations } from "../lib/i18n";
import {
  PERIODS,
  buildDailySeries,
  buildJobs,
  buildKpis,
  callsSummary,
  costSummary,
  formatCount,
  formatDayLabel,
  formatUsd,
  jobWindowForPeriod,
  rangeForPeriod,
  rankAgents,
  rankModels,
  rankingSummary,
  type Msg,
  type PeriodDays,
  type RankedRow,
  type Ranking,
} from "./insightsModel";

interface SpendData {
  summary: SpendSummaryDto;
  byDay: DayUsageDto[];
  byModel: ModelUsageDto[];
  byAgent: AgentUsageDto[];
}

// A result remembers which request produced it (`key`), so "loading" is simply
// "the newest result is not for the current request" - no state reset needed
// when the period changes.
type Loaded<T> = { key: string; data: T | null; error: string | null };
const notLoaded = <T,>(): Loaded<T> => ({ key: "", data: null, error: null });

const RANK_CAP = 5;

export default function InsightsPage() {
  const t = useTranslations("insights");
  const { isAdmin, hasPermission } = useAuth();
  // Checked here, before asking: core writes an "authz.denied" audit entry for
  // every refused request, so a member opening this page must not cause one.
  // (Same rule InsightsTeaser follows.) A 403 from the server is still handled
  // below, in case the role changed after sign-in.
  const mayViewUsage = !!isAdmin || hasPermission("VIEW_USAGE");
  const [days, setDays] = useState<PeriodDays>(7);
  const [spendAttempt, setSpendAttempt] = useState(0);
  const [jobsAttempt, setJobsAttempt] = useState(0);
  const [spend, setSpend] = useState<Loaded<SpendData>>(notLoaded);
  const [jobs, setJobs] = useState<Loaded<AgentJobCountsDto>>(notLoaded);
  // Sticky: once the server says this person may not see workspace usage, a
  // period change must not ask (and get refused) again.
  const [refused, setRefused] = useState(false);
  const forbidden = refused || !mayViewUsage;
  const [agentNames, setAgentNames] = useState<ReadonlyMap<string, string>>(new Map());

  const spendKey = `${days}:${spendAttempt}`;
  const jobsKey = `${days}:${jobsAttempt}`;

  useEffect(() => {
    if (forbidden) return undefined;
    let cancelled = false;
    const range = rangeForPeriod(days);
    void (async () => {
      try {
        // Summary first: a member without VIEW_USAGE is refused once, not four times.
        const summary = await insightsApi.getSpendSummary(range);
        const [byDay, byModel, byAgent] = await Promise.all([
          insightsApi.getSpendByDay(range),
          insightsApi.getSpendByModel(range),
          insightsApi.getSpendByAgent(range),
        ]);
        if (!cancelled) setSpend({ key: spendKey, data: { summary, byDay, byModel, byAgent }, error: null });
      } catch (err) {
        if (cancelled) return;
        if (isForbidden(err)) {
          setRefused(true);
          return;
        }
        setSpend({ key: spendKey, data: null, error: err instanceof Error && err.message ? err.message : "error" });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [days, spendKey, forbidden]);

  useEffect(() => {
    let cancelled = false;
    insightsApi
      .getAgentJobCounts({ window: jobWindowForPeriod(days) })
      .then((data) => {
        if (!cancelled) setJobs({ key: jobsKey, data, error: null });
      })
      .catch((err) => {
        if (!cancelled) setJobs({ key: jobsKey, data: null, error: err instanceof Error && err.message ? err.message : "error" });
      });
    return () => {
      cancelled = true;
    };
  }, [days, jobsKey]);

  // Agent names for the ranking. Best effort: without them a row reads "Agent 1a2b3c4d".
  const hasSpend = spend.data !== null;
  useEffect(() => {
    if (!hasSpend) return undefined;
    let cancelled = false;
    api
      .listAgents()
      .then((list: unknown) => {
        if (cancelled || !Array.isArray(list)) return;
        const names = new Map<string, string>();
        for (const agent of list as Array<{ id?: unknown; name?: unknown }>) {
          if (typeof agent?.id === "string" && typeof agent?.name === "string" && agent.name) names.set(agent.id, agent.name);
        }
        setAgentNames(names);
      })
      .catch(() => {
        /* names are optional */
      });
    return () => {
      cancelled = true;
    };
  }, [hasSpend]);

  const spendLoading = !forbidden && spend.key !== spendKey;
  const jobsLoading = jobs.key !== jobsKey;
  usePageReady((forbidden || spend.key !== "") && jobs.key !== "");

  const say = (msg: Msg, extra?: Record<string, string | number>) => t(msg.key, { ...msg.values, ...extra });

  const view = useMemo(() => {
    if (!spend.data) return null;
    const kpis = buildKpis(spend.data.summary);
    return {
      kpis,
      series: buildDailySeries(spend.data.summary, spend.data.byDay),
      models: rankModels(spend.data.byModel),
      agents: rankAgents(spend.data.byAgent, agentNames),
    };
  }, [spend.data, agentNames]);
  const jobsView = useMemo(() => (jobs.data ? buildJobs(jobs.data) : null), [jobs.data]);

  const periodSwitch = (
    <div role="group" aria-label={t("periodLabel")} className="flex flex-wrap items-center gap-1.5">
      {PERIODS.map((period) => (
        <Button
          key={period}
          type="button"
          size="sm"
          variant={period === days ? "default" : "outline"}
          aria-pressed={period === days}
          className={PERIOD_PILL_FOCUS_CLASS}
          onClick={() => setDays(period)}
        >
          {t(`period${period}`)}
        </Button>
      ))}
    </div>
  );

  const retryBlock = (message: string, onRetry: () => void) => (
    <div
      role="alert"
      className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border px-4 py-3 text-sm"
      style={{ color: "var(--state-error)" }}
    >
      <span className="min-w-0">{message}</span>
      <Button type="button" variant="outline" size="sm" onClick={onRetry}>
        {t("retry")}
      </Button>
    </div>
  );

  const rowLabel = (row: RankedRow, noneLabel: string) =>
    row.kind === "named" && row.label ? row.label : row.kind === "unknown" ? t("unknownAgent", { id: row.shortId ?? "" }) : noneLabel;

  const rankingCard = (title: string, nameHeader: string, ranking: Ranking, noneLabel: string) => {
    const summary = rankingSummary(ranking);
    return (
      <Card className="min-w-0 gap-0 p-5">
        <ChartFrame
          title={title}
          summary={summary ? say(summary.msg, { name: rowLabel(summary.top, noneLabel) }) : ""}
          empty={!ranking.hasCost}
          emptyTitle={t("noCostTitle")}
          emptyDescription={t("noCostBody")}
          emptyIcon={Coins}
          table={{
            columns: [
              { key: "name", header: nameHeader },
              { key: "calls", header: t("colCalls"), align: "right" },
              { key: "cost", header: t("colCost"), align: "right" },
            ],
            rows: ranking.rows.map((row) => ({
              id: row.key,
              name: rowLabel(row, noneLabel),
              calls: formatCount(row.calls),
              cost: row.costText,
            })),
          }}
        >
          <RankedBars
            cap={RANK_CAP}
            sort={false}
            items={ranking.rows.map((row) => ({
              key: row.key,
              label: rowLabel(row, noneLabel),
              value: row.costUsd,
              valueText: t(row.calls === 1 ? "rankValueOne" : "rankValue", { cost: row.costText, count: formatCount(row.calls) }),
            }))}
          />
        </ChartFrame>
      </Card>
    );
  };

  let spendBody: ReactNode = null;
  if (forbidden) {
    spendBody = (
      <p data-testid="insights-admin-only" className="rounded-xl border border-border bg-card/40 px-4 py-3 text-sm text-muted-foreground">
        {t("adminOnlyNote")}
      </p>
    );
  } else if (spendLoading) {
    spendBody = (
      <div aria-busy="true" data-testid="insights-spend-loading" className="grid grid-cols-1 gap-4 min-[480px]:grid-cols-2 lg:grid-cols-4">
        {[0, 1, 2, 3].map((i) => (
          <Skeleton key={i} className="h-28 w-full rounded-xl" />
        ))}
      </div>
    );
  } else if (spend.error || !view) {
    spendBody = retryBlock(t("spendError"), () => setSpendAttempt((n) => n + 1));
  } else {
    const { kpis, series, models, agents } = view;
    const from = formatDayLabel(kpis.fromDate);
    const to = formatDayLabel(kpis.toDate);
    const soFar = t("soFar");
    const lastIndex = series.days.length - 1;
    const dayName = (label: string, index: number) => (series.partialLast && index === lastIndex ? `${label} (${soFar})` : label);
    const callsMsg = callsSummary(kpis);
    const costMsg = costSummary(kpis);
    const dayTable = {
      columns: [
        { key: "day", header: t("colDay") },
        { key: "calls", header: t("colCalls"), align: "right" as const },
        { key: "failed", header: t("colFailed"), align: "right" as const },
        { key: "cost", header: t("colCost"), align: "right" as const },
      ],
      rows: series.days.map((day, index) => ({
        id: day.date,
        day: dayName(day.label, index),
        calls: formatCount(day.calls),
        failed: formatCount(day.failed),
        cost: formatUsd(day.costUsd),
      })),
    };
    const partialFooter = series.partialLast ? t("partialFooter") : undefined;

    spendBody = (
      <div className="min-w-0 space-y-6">
        <p data-testid="insights-range" className="text-sm text-muted-foreground">
          {kpis.fromDate && kpis.toDate ? t(kpis.includesLiveToday ? "rangeNoteLive" : "rangeNote", { from, to }) : ""}
        </p>

        <div className="grid grid-cols-1 gap-4 min-[480px]:grid-cols-2 lg:grid-cols-4" data-testid="insights-kpis">
          <StatCard
            label={t("kpiCalls")}
            value={kpis.callsText}
            icon={Zap}
            sub={kpis.hasData ? t("kpiCallsSub", { failed: formatCount(kpis.failed) }) : t("kpiNone")}
          />
          <StatCard
            label={t("kpiSuccess")}
            value={<span data-testid="insights-success-rate">{kpis.successRateText}</span>}
            icon={TrendingUp}
            sub={
              <>
                {kpis.hasData
                  ? t("kpiSuccessSub", { succeeded: formatCount(kpis.succeeded), total: formatCount(kpis.calls) })
                  : t("kpiNone")}
                {hasPermission("VIEW_AUDIT_LOG") && (
                  <>
                    {" "}
                    <Link href="/admin/issues" className="text-primary underline-offset-4 hover:underline">
                      {t("linkIssues")}
                    </Link>
                  </>
                )}
              </>
            }
          />
          <StatCard
            label={t("kpiCost")}
            value={kpis.costText}
            icon={Coins}
            sub={
              <>
                {t("kpiCostSub")}{" "}
                <Link href="/usage" className="text-primary underline-offset-4 hover:underline">
                  {t("linkUsage")}
                </Link>{" "}
                <SeeCostPerAgentLink />
              </>
            }
          />
          <StatCard
            label={t("kpiLatency")}
            value={kpis.p50Text}
            icon={Timer}
            sub={kpis.hasData ? t("kpiLatencySub", { p95: kpis.p95Text }) : t("kpiNone")}
          />
        </div>

        {!kpis.hasData ? (
          <EmptyState
            icon={BarChart3}
            title={t("emptyTitle")}
            description={t("emptyBody")}
            action={
              <Button asChild>
                <Link href="/agents">{t("emptyCta")}</Link>
              </Button>
            }
          />
        ) : (
          <div className="grid min-w-0 grid-cols-1 gap-4 lg:grid-cols-2">
            <Card className="min-w-0 gap-0 p-5">
              <ChartFrame title={t("callsChartTitle")} summary={callsMsg ? say(callsMsg) : ""} table={dayTable} footer={partialFooter}>
                <MiniBars
                  data={series.days.map((day) => ({
                    label: day.label,
                    value: day.calls,
                    valueText: t(day.calls === 1 ? "callsValueOne" : "callsValue", { count: formatCount(day.calls) }),
                  }))}
                  partialLast={series.partialLast}
                  partialLabel={soFar}
                />
              </ChartFrame>
            </Card>
            <Card className="min-w-0 gap-0 p-5">
              <ChartFrame title={t("costChartTitle")} summary={costMsg ? say(costMsg) : ""} table={dayTable} footer={partialFooter}>
                <MiniBars
                  data={series.days.map((day) => ({
                    label: day.label,
                    value: day.costUsd,
                    valueText: t("costValue", { cost: formatUsd(day.costUsd) }),
                  }))}
                  partialLast={series.partialLast}
                  partialLabel={soFar}
                  color="var(--state-running)"
                />
              </ChartFrame>
            </Card>
            {rankingCard(t("topAgentsTitle"), t("colAgent"), agents, t("noAgent"))}
            {rankingCard(t("topModelsTitle"), t("colModel"), models, t("unknownModel"))}
          </div>
        )}
      </div>
    );
  }

  const windowText = jobsView ? say(jobsView.window) : "";

  return (
    <AppShell eyebrow={t("eyebrow")}>
      {mayViewUsage && <DemoWatermark />}
      <PageHeader
        icon={BarChart3}
        title={t("pageTitle")}
        subtitle={t("pageSubtitle")}
        actions={
          <div className="flex flex-wrap items-center gap-3">
            {periodSwitch}
            <Button asChild variant="outline" size="sm">
              <Link href="/roi">{t("linkRoi")}</Link>
            </Button>
          </div>
        }
      />
      <div className="min-w-0 space-y-8">
        {spendBody}

        <Section
          title={
            <span className="flex flex-wrap items-center gap-2">
              {t("jobsTitle")}
              {jobsView && (
                <Badge variant="outline" data-testid="insights-jobs-scope">
                  {jobsView.tenantWide ? t("scopeWorkspace") : t("scopeYours")}
                </Badge>
              )}
            </span>
          }
          description={jobsView ? (jobsView.tenantWide ? t("jobsDescWorkspace") : t("jobsDescYours")) : undefined}
          className="min-w-0"
        >
          <Card className="min-w-0 gap-0 p-5">
            <ChartFrame
              title={t("jobsChartTitle")}
              summary={jobsView?.summary ? say(jobsView.summary, { window: windowText }) : ""}
              loading={jobsLoading}
              error={
                !jobsLoading && (jobs.error || !jobsView) ? (
                  <span className="flex flex-wrap items-center justify-between gap-3">
                    <span className="min-w-0">{t("jobsError")}</span>
                    <Button type="button" variant="outline" size="sm" onClick={() => setJobsAttempt((n) => n + 1)}>
                      {t("retry")}
                    </Button>
                  </span>
                ) : undefined
              }
              empty={!!jobsView && jobsView.total === 0}
              emptyIcon={ListChecks}
              emptyTitle={t("jobsEmptyTitle")}
              emptyDescription={t("jobsEmptyBody", { window: windowText })}
              emptyAction={
                <Button asChild>
                  <Link href="/jobs">{t("jobsEmptyCta")}</Link>
                </Button>
              }
              table={
                jobsView
                  ? {
                      columns: [
                        { key: "status", header: t("colStatus") },
                        { key: "count", header: t("colJobs"), align: "right" },
                      ],
                      rows: jobsView.segments.map((segment) => ({
                        id: segment.key,
                        status: t(segment.labelKey),
                        count: formatCount(segment.count),
                      })),
                    }
                  : undefined
              }
              footer={
                jobsView ? (
                  <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
                    <span>{t("jobsFooter", { window: windowText })}</span>
                    {jobsView.cancelled > 0 && <span>{t("jobsCancelled", { count: formatCount(jobsView.cancelled) })}</span>}
                    <Link href="/jobs" className="text-primary underline-offset-4 hover:underline">
                      {t("linkJobs")}
                    </Link>
                  </span>
                ) : undefined
              }
            >
              {jobsView && (
                <StackedStatusBar
                  segments={jobsView.segments.map((segment) => ({
                    key: segment.key,
                    label: t(segment.labelKey),
                    count: segment.count,
                    state: segment.state,
                    countText: formatCount(segment.count),
                  }))}
                />
              )}
            </ChartFrame>
          </Card>
        </Section>
      </div>
    </AppShell>
  );
}
