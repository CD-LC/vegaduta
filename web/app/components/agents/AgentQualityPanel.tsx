"use client";

import { useCallback, useEffect, useState } from "react";
import { Award, CheckCircle2, Gauge, Lock, MessageSquareText, ShieldCheck, Sparkles, TrendingUp } from "lucide-react";
import { api } from "../../lib/api";
import type { QualityConfigResponse, QualityHistoryResponse, QualityStatisticsResponse, QualitySummaryResponse } from "../../lib/api";
import { hasQualityRecords } from "../../lib/api/quality";
import { Card } from "../ui/card";
import { Badge } from "../ui/badge";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import { Section, StatCard, EmptyState } from "../ax/page-kit";
import { UpgradeLock } from "../ui/upgrade-lock";
import JudgeSamplingNote from "./JudgeSamplingNote";
import { useTranslations } from "../../lib/i18n";

// EvaluationType.name() -> display name, mirroring EvaluationType.getDisplayName()
// (core/quality) since the summary/config endpoints echo the enum name, not the
// display string (see api.ts's QualitySummaryResponse/QualityConfigResponse
// comments) - QualityHistoryResponse.scoresByType is the one map that's already
// keyed by display name server-side, so it's rendered straight from the wire.
const EVAL_TYPE_LABELS: Record<string, string> = {
  HELPFULNESS: "Helpfulness",
  ACCURACY: "Accuracy",
  TONE: "Tone",
  LENGTH: "Length",
  RELEVANCE: "Relevance",
  SAFETY: "Safety",
};

function labelFor(key: string): string {
  return EVAL_TYPE_LABELS[key] || key;
}

function pct(v: number | null | undefined): string {
  if (v === null || v === undefined || Number.isNaN(Number(v))) return "—";
  return `${Math.round(Number(v) * 100)}%`;
}

/**
 * Agent Quality dashboard - the tenant-facing surface for QualityController's
 * eval-scoring data (core/quality): the latest per-turn scores, a score trend
 * for charting, aggregated statistics, and which evaluation dimensions this
 * tenant's plan has enabled. Mirrors AgentLearningPanel.tsx's shape (a
 * read/observe surface keyed off the selected agent) but adds a real
 * plan-gate: every one of the four backing endpoints 403s for FREE-plan
 * tenants (QualityController#assertPremiumPlan), so this panel renders an
 * inline upgrade card instead of the dashboard when that happens, rather than
 * silently degrading to an empty state the way AgentLearningPanel does for
 * its (currently ungated) RL endpoints.
 */
// Only the id is read here (every quality endpoint keys off it) - a minimal
// structural prop type, same reasoning as ShareAgentPanel's, so any
// agent-shaped object from the quality page works without importing the full
// workbench agent shape.
export default function AgentQualityPanel({
  agent,
  attackTestHref,
}: {
  agent: { id: string } | null;
  /** Where the "Test against attacks" link goes; omitted = no link (the attack test is not available). */
  attackTestHref?: string;
}) {
    const tHC = useTranslations("hcComponentsAgentsAgentQualityPanel");
  const [config, setConfig] = useState<QualityConfigResponse | null>(null);
  const [summary, setSummary] = useState<QualitySummaryResponse | null>(null);
  const [statistics, setStatistics] = useState<QualityStatisticsResponse | null>(null);
  const [history, setHistory] = useState<QualityHistoryResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [locked, setLocked] = useState(false);
  const [unavailable, setUnavailable] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!agent?.id) return;
    setLoading(true);
    setError(null);
    try {
      // Config first: it's the cheapest signal for the plan gate (a single
      // 403 tells us the whole surface is locked, no point firing the other
      // three requests too) and it also carries the real plan tier + enabled
      // evaluation set the rest of the panel renders against.
      const cfg = await api.getAgentQualityConfig(agent.id);
      setConfig(cfg);
      setLocked(false);
      // The catch callbacks carry explicit return-type annotations because
      // with strictNullChecks still off a bare `() => null` infers an *any*
      // return (null widens), which noImplicitAny rejects (TS7011).
      const [statsData, historyData] = await Promise.all([
        api.getAgentQualityStatistics(agent.id).catch((): QualityStatisticsResponse | null => null),
        api.getAgentQualityHistory(agent.id, 100).catch((): QualityHistoryResponse | null => null),
      ]);
      // The summary endpoint answers 404 for "no evaluations recorded yet",
      // and a browser logs every 404 however quietly it is handled. History
      // answers 200 with an empty list for the same state, so it decides
      // whether the summary is worth asking for at all (prod pass bug 12).
      const summaryData = hasQualityRecords(historyData)
        ? await api.getAgentQualitySummary(agent.id).catch((): QualitySummaryResponse | null => null)
        : null;
      setSummary(summaryData);
      setStatistics(statsData);
      setHistory(historyData);
    } catch (err: any) {
      if (err?.status === 403) {
        setLocked(true);
        setConfig(null);
        setSummary(null);
        setStatistics(null);
        setHistory(null);
      } else if (err?.status === 404 || err?.status === 501) {
        // The web build is ahead of the running core: QualityController's
        // routes don't exist there yet. A raw error banner ("Something went
        // wrong. Please try again.") reads as breakage; the honest state is
        // "not on this deployment", the same convention the sibling sections
        // (EvalGateSection etc.) already use to self-hide on a 404.
        setUnavailable(true);
      } else {
        setError(err?.message || "Couldn't load quality data.");
      }
    } finally {
      setLoading(false);
    }
  }, [agent?.id]);

  useEffect(() => {
    void load();
  }, [load]);

  if (!agent) {
    return (
      <EmptyState
        icon={Gauge}
        title={tHC("hcComponentsAgentsAgentQualityPanel.s22")}
        description="Pick an agent to see how its responses are scoring on the evaluation dimensions your plan has enabled."
      />
    );
  }

  if (loading) {
    return <p className="text-sm text-muted-foreground">{tHC("hcComponentsAgentsAgentQualityPanel.s21")}</p>;
  }

  if (locked) {
    return (
      <Alert>
        <Lock />
        <AlertTitle>{tHC("hcComponentsAgentsAgentQualityPanel.s20")}</AlertTitle>
        <AlertDescription>
          <p>
            {tHC("hcComponentsAgentsAgentQualityPanel.s19")}
          </p>
          <UpgradeLock
            reason="Agent quality evaluations (scores, history, statistics) are a Premium feature - upgrade to view them."
            href="/admin/billing"
            className="mt-3"
          >
            <Badge variant="secondary" className="cursor-not-allowed gap-1.5 opacity-80">
              <Sparkles size={11} /> {tHC("hcComponentsAgentsAgentQualityPanel.s18")}
            </Badge>
          </UpgradeLock>
        </AlertDescription>
      </Alert>
    );
  }

  if (unavailable) {
    return (
      <Alert>
        <Gauge />
        <AlertTitle>{tHC("hcComponentsAgentsAgentQualityPanel.s17")}</AlertTitle>
        <AlertDescription>
          {tHC("hcComponentsAgentsAgentQualityPanel.s16")}
        </AlertDescription>
      </Alert>
    );
  }

  if (error) {
    return <p className="text-sm text-destructive">{error}</p>;
  }

  const scoresByType = summary?.scoresByType || {};
  const feedbackByType = summary?.feedbackByType || {};
  const scoreEntries = Object.entries(scoresByType);
  const enabledTypes = config?.enabledEvaluations || [];
  const hasData = !!summary || (statistics?.totalEvaluations || 0) > 0;

  return (
    <div className="space-y-10">
      {!hasData && (
        <Alert>
          <Gauge />
          <AlertTitle>{tHC("hcComponentsAgentsAgentQualityPanel.s15")}</AlertTitle>
          <AlertDescription>
            <p>{tHC("hcComponentsAgentsAgentQualityPanel.s14")}</p>
          </AlertDescription>
        </Alert>
      )}

      {/* Overview stat row — l22-8 teaches this whole surface. */}
      <Section
        title={tHC("hcComponentsAgentsAgentQualityPanel.s13")}
        description="Aggregated across every recorded evaluation for this agent."
        actions={
          <>
            {attackTestHref && (
              <a
                href={attackTestHref}
                data-testid="quality-attack-link"
                className="text-xs font-medium text-muted-foreground underline hover:text-foreground"
              >
                {tHC("hcComponentsAgentsAgentQualityPanel.s12")}
              </a>
            )}
          </>
        }
      >
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
          <StatCard label={tHC("hcComponentsAgentsAgentQualityPanel.s11")} value={pct(statistics?.averageQualityScore ?? null)} icon={Award} accent />
          <StatCard label={tHC("hcComponentsAgentsAgentQualityPanel.s10")} value={statistics?.totalEvaluations ?? 0} icon={CheckCircle2} />
          <StatCard label={tHC("hcComponentsAgentsAgentQualityPanel.s9")} value={summary ? pct(summary.overallScore) : "—"} icon={TrendingUp} />
          <StatCard label={tHC("hcComponentsAgentsAgentQualityPanel.s8")} value={config?.planTier || "—"} icon={ShieldCheck} />
        </div>
      </Section>

      {/* Score trend */}
      <Section title={tHC("hcComponentsAgentsAgentQualityPanel.s7")} description="Overall quality score across recent turns.">
        <Card className="gap-0 p-5">
          {history && history.overallScores.length > 0 ? (
            <QualityTrend history={history} />
          ) : (
            <p className="py-6 text-center text-sm text-muted-foreground">{tHC("hcComponentsAgentsAgentQualityPanel.s6")}</p>
          )}
        </Card>
      </Section>

      {/* Per-dimension scores from the latest evaluated turn */}
      <Section title={tHC("hcComponentsAgentsAgentQualityPanel.s5")} description="From the most recent evaluated turn.">
        {scoreEntries.length > 0 ? (
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            {scoreEntries.map(([type, score]) => (
              <Card key={type} className="gap-2 p-4">
                <div className="flex items-center justify-between gap-3">
                  <span className="text-sm font-medium text-foreground">{labelFor(type)}</span>
                  <span className="font-mono text-sm tabular-nums text-primary">{pct(score)}</span>
                </div>
                <div className="h-1.5 w-full overflow-hidden rounded-full bg-secondary/40">
                  <div
                    className="h-full rounded-full bg-primary"
                    style={{ width: `${Math.max(0, Math.min(100, Math.round((score || 0) * 100)))}%` }}
                  />
                </div>
                {feedbackByType[type] && (
                  <p className="text-xs leading-relaxed text-muted-foreground">{feedbackByType[type]}</p>
                )}
              </Card>
            ))}
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">{tHC("hcComponentsAgentsAgentQualityPanel.s4")}</p>
        )}
      </Section>

      {/* Which dimensions this plan tier runs */}
      <Section title={tHC("hcComponentsAgentsAgentQualityPanel.s3")} description="Which dimensions run on this agent, based on your plan tier.">
        <div className="flex flex-wrap gap-2">
          {enabledTypes.length > 0 ? (
            enabledTypes.map((type) => (
              <Badge key={type} variant="secondary" className="gap-1.5">
                <MessageSquareText size={11} /> {labelFor(type)}
              </Badge>
            ))
          ) : (
            <p className="text-sm text-muted-foreground">{tHC("hcComponentsAgentsAgentQualityPanel.s2")}</p>
          )}
        </div>
      </Section>

      <JudgeSamplingNote agentId={agent.id} />
    </div>
  );
}

/**
 * Overall-score trend as a lightweight inline SVG line - same no-charting-
 * dependency approach as AgentLearningPanel's RewardTrend, theme-token driven
 * via currentColor so it reads correctly in light and dark.
 */
function QualityTrend({ history }: { history: QualityHistoryResponse }) {
    const tHC = useTranslations("hcComponentsAgentsAgentQualityPanel");
  const values = history.overallScores.map((v) => Number(v) || 0);
  const max = Math.max(...values, 1);
  const min = Math.min(...values, 0);
  const range = max - min || 1;
  const W = 100;
  const H = 40;
  const n = values.length;
  const x = (i: number) => (n === 1 ? W / 2 : (i / (n - 1)) * W);
  const y = (v: number) => H - ((v - min) / range) * (H - 4) - 2;
  const line = values.map((v, i) => `${x(i).toFixed(2)},${y(v).toFixed(2)}`).join(" ");
  const area = `${x(0).toFixed(2)},${H} ${line} ${x(n - 1).toFixed(2)},${H}`;
  const latest = values[values.length - 1] ?? 0;
  const first = values[0] ?? 0;
  const delta = latest - first;
  const gradId = "qualitytrendgrad";

  return (
    <div className="text-primary">
      <div className="mb-3 flex items-end justify-between gap-3">
        <div>
          <div className="text-2xl font-semibold tabular-nums text-foreground">{pct(latest)}</div>
          <div className="text-xs text-muted-foreground">{tHC("hcComponentsAgentsAgentQualityPanel.s1")}</div>
        </div>
        <div
          className="flex items-center gap-1 text-xs font-medium tabular-nums"
          style={{ color: delta >= 0 ? "var(--state-success)" : "var(--state-error)" }}
        >
          <TrendingUp size={13} className={delta >= 0 ? "" : "-scale-y-100"} />
          {delta >= 0 ? "+" : ""}
          {(delta * 100).toFixed(1)}pp
        </div>
      </div>
      <svg
        viewBox={`0 0 ${W} ${H}`}
        preserveAspectRatio="none"
        className="block h-24 w-full"
        role="img"
        aria-label={`Quality score trend across ${n} turns, latest ${pct(latest)}`}
      >
        <defs>
          <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="currentColor" stopOpacity="0.22" />
            <stop offset="100%" stopColor="currentColor" stopOpacity="0" />
          </linearGradient>
        </defs>
        <polygon points={area} fill={`url(#${gradId})`} />
        <polyline
          points={line}
          fill="none"
          stroke="currentColor"
          strokeWidth="1.4"
          strokeLinecap="round"
          strokeLinejoin="round"
          vectorEffect="non-scaling-stroke"
        />
      </svg>
      <div className="mt-2 flex items-center justify-between text-xs text-muted-foreground">
        <span>{history.timestamps[0] ? new Date(history.timestamps[0]).toLocaleDateString() : ""}</span>
        <span className="tabular-nums">{history.recordCount.toLocaleString()} turns</span>
        <span>
          {history.timestamps[history.timestamps.length - 1]
            ? new Date(history.timestamps[history.timestamps.length - 1]).toLocaleDateString()
            : ""}
        </span>
      </div>
    </div>
  );
}
