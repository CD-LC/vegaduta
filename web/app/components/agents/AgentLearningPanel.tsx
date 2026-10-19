"use client";

import { useCallback, useEffect, useState } from "react";
import { ThumbsUp, ThumbsDown, Star, PencilLine, TrendingUp, Sparkles, ShieldCheck, Construction, Download } from "lucide-react";
import { api } from "../../lib/api";
import { useAuth } from "../AuthProvider";
import { Card } from "../ui/card";
import { Badge } from "../ui/badge";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import { Section, StatCard, EmptyState } from "../ax/page-kit";
import { Button } from "../ui/button";
import { cn } from "../../lib/utils";
import RlArmsEditor from "./RlArmsEditor";
import { dimensionMeta } from "../../lib/rl-arms";
import { Select } from "../ui/select";
import { useTranslations } from "../../lib/i18n";

/**
 * Agent Learning dashboard - the read/observe surface of the reinforcement-
 * learning loop. Shows the reward trend over time, feedback tallies, the
 * contextual-bandit arms table (with the leading arm per dimension highlighted)
 * and, for tenant admins only, per-dimension policy controls (enable/disable
 * exploration + an exploration-rate slider).
 *
 * Backed by GET /api/rl/agents/{id}/summary and /policies. Both reads return
 * null while the RL endpoints are still 404ing (the sibling backend is being
 * built to match), so this degrades to a calm "no learning data yet" state
 * rather than an error - nothing here fabricates a number.
 */
export default function AgentLearningPanel({ agent }: { agent: any }) {
  const t = useTranslations("f2GapsAgents");
  const { hasPermission } = useAuth();
  const canManageAgents = hasPermission("MANAGE_AGENTS");
  const [summary, setSummary] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  // Policies are loaded HERE rather than inside PolicyControls because two
  // surfaces need them and only one of those is admin-only: the arms editor
  // has to know whether MODEL_ROUTE is switched on (an enabled policy with no
  // arms is the silent failure worth shouting about) and it renders for every
  // user, admin or not.
  const [policies, setPolicies] = useState<any[] | null>(null);
  const [policiesLoading, setPoliciesLoading] = useState(true);

  const load = useCallback(async () => {
    if (!agent?.id) return;
    setLoading(true);
    try {
      const data = await api.getRlSummary(agent.id);
      setSummary(data);
    } catch {
      setSummary(null);
    } finally {
      setLoading(false);
    }
  }, [agent?.id]);

  const loadPolicies = useCallback(async () => {
    if (!agent?.id) return;
    setPoliciesLoading(true);
    try {
      setPolicies(await api.getRlPolicies(agent.id));
    } catch {
      setPolicies(null);
    } finally {
      setPoliciesLoading(false);
    }
  }, [agent?.id]);

  const [exporting, setExporting] = useState(false);
  const exportTrajectories = useCallback(async () => {
    if (!agent?.id) return;
    setExporting(true);
    try {
      const ndjson = await api.getRlTrajectoriesJsonl(agent.id);
      const blob = new Blob([ndjson], { type: "application/x-ndjson" });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `trajectories-${agent.id}.jsonl`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(url);
    } catch {
      /* toJson already raised the shared toast (403 on an older/non-admin token included) */
    } finally {
      setExporting(false);
    }
  }, [agent?.id]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void loadPolicies();
  }, [loadPolicies]);

  if (!agent) {
    return (
      <EmptyState
        icon={Sparkles}
        title={t("lrnSelectAgent")}
        description={t("lrnSelectAgentBody")}
      />
    );
  }

  if (loading && !summary) {
    return <p className="text-sm text-muted-foreground">{t("lrnLoading")}</p>;
  }

  const trend: any[] = summary?.rewardTrend || [];
  const counts = summary?.feedbackCounts || { up: 0, down: 0, rating: 0, edit: 0 };
  const arms: any[] = summary?.arms || [];
  const totalFeedback = (counts.up || 0) + (counts.down || 0) + (counts.rating || 0) + (counts.edit || 0);
  const hasData = trend.length > 0 || arms.length > 0 || totalFeedback > 0;
  const modelRoutePolicy = (policies || []).find((p) => p.dimension === "MODEL_ROUTE") || null;

  return (
    <div className="space-y-10">
      {!summary && (
        <Alert>
          <Sparkles />
          <AlertTitle>{t("lrnUnavailableTitle")}</AlertTitle>
          <AlertDescription>
            <p>{t("lrnUnavailableBody")}</p>
          </AlertDescription>
        </Alert>
      )}

      {/* Feedback tallies */}
      <Section title={t("lrnFeedbackTitle")} description={t("lrnFeedbackBody")}>
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
          <StatCard label={t("lrnThumbsUp")} value={counts.up ?? 0} icon={ThumbsUp} accent />
          <StatCard label={t("lrnThumbsDown")} value={counts.down ?? 0} icon={ThumbsDown} />
          <StatCard label={t("lrnRatings")} value={counts.rating ?? 0} icon={Star} />
          <StatCard label={t("lrnEdits")} value={counts.edit ?? 0} icon={PencilLine} />
        </div>
      </Section>

      {/* Reward trend */}
      <Section title={t("lrnTrendTitle")} description={t("lrnTrendBody")}>
        <Card className="gap-0 p-5">
          {trend.length > 0 ? (
            <RewardTrend trend={trend} />
          ) : (
            <p className="py-6 text-center text-sm text-muted-foreground">{t("lrnNoHistory")}</p>
          )}
        </Card>
      </Section>

      {/*
       * Model routing arms. This replaced a read-only cross-dimension arms
       * table fed by the summary endpoint. That table had two problems: it
       * showed seeded arms for dimensions nothing reads (crowning a "Leading"
       * arm on a policy that never runs), and when the one dimension that IS
       * read had no arms it said "No arms have been pulled yet" — which reads
       * as "early days", not as "this is misconfigured and cannot ever learn".
       */}
      <Section
        title={t("lrnArmsTitle")}
        description={t("lrnArmsBody")}
      >
        {policiesLoading ? (
          <p className="text-sm text-muted-foreground">{t("lrnArmsLoading")}</p>
        ) : (
          <RlArmsEditor
            agent={agent}
            policy={modelRoutePolicy}
            canEdit={canManageAgents}
            onChanged={() => {
              void load();
              void loadPolicies();
            }}
          />
        )}
      </Section>

      {/* Policy controls (admin) */}
      {canManageAgents ? (
        <Section
          title={t("lrnPolicyTitle")}
          description={t("lrnPolicyBody")}
        >
          <PolicyControls
            agentId={agent.id}
            policies={policies}
            loading={policiesLoading}
            reload={loadPolicies}
            onSaved={load}
          />
          {/* Offline-optimization export (V395): one NDJSON line per episode
              with steps inlined - the substrate for GEPA/DSPy-style prompt
              optimization, not an online RL loop. Raw conversation text, so
              it stays behind the same admin gate as policy controls. */}
          <div className="flex items-center justify-between gap-3 rounded-md border border-border p-3">
            <p className="text-xs text-muted-foreground">{t("lrnExportHint")}</p>
            <Button
              variant="outline"
              size="sm"
              disabled={exporting}
              onClick={exportTrajectories}
            >
              <Download size={14} /> {exporting ? t("lrnExporting") : t("lrnExport")}
            </Button>
          </div>
        </Section>
      ) : (
        <Alert>
          <ShieldCheck />
          <AlertTitle>{t("lrnAdminOnlyTitle")}</AlertTitle>
          <AlertDescription>
            <p>{t("lrnAdminOnlyBody")}</p>
          </AlertDescription>
        </Alert>
      )}

      {!hasData && summary && (
        <p className="text-center text-xs text-muted-foreground">{t("lrnNotEnough")}</p>
      )}
    </div>
  );
}

/**
 * Reward trend as a lightweight inline SVG area + line - no charting
 * dependency. Theme-token driven: the container carries text-primary and the
 * marks paint with currentColor, so it reads correctly in light and dark.
 */
function RewardTrend({ trend }: { trend: any[] }) {
  const t = useTranslations("f2GapsAgents");
  const values = trend.map((p) => Number(p.avgReward) || 0);
  const max = Math.max(...values);
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
  const totalCount = trend.reduce((s, p) => s + (Number(p.count) || 0), 0);
  const gradId = "rlrewardgrad";

  return (
    <div className="text-primary">
      <div className="mb-3 flex items-end justify-between gap-3">
        <div>
          <div className="text-2xl font-semibold tabular-nums text-foreground">{latest.toFixed(3)}</div>
          <div className="text-xs text-muted-foreground">{t("lrnLatestAvg")}</div>
        </div>
        <div
          className="flex items-center gap-1 text-xs font-medium tabular-nums"
          style={{ color: delta >= 0 ? "var(--state-success)" : "var(--state-error)" }}
        >
          <TrendingUp size={13} className={delta >= 0 ? "" : "-scale-y-100"} />
          {delta >= 0 ? "+" : ""}
          {delta.toFixed(3)}
        </div>
      </div>
      <svg
        viewBox={`0 0 ${W} ${H}`}
        preserveAspectRatio="none"
        className="block h-24 w-full"
        role="img"
        aria-label={t("lrnTrendAria", { days: n, latest: latest.toFixed(3) })}
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
        <span>{trend[0]?.date || ""}</span>
        <span className="tabular-nums">{t("lrnSignals", { count: totalCount.toLocaleString() })}</span>
        <span>{trend[trend.length - 1]?.date || ""}</span>
      </div>
    </div>
  );
}

/**
 * Admin-only per-dimension policy editor. Policies are fetched by the parent
 * (the arms editor needs them too) and written back via PUT /policies/{dim}.
 *
 * All five dimensions are now consulted by AgentChatService#chat (2026-08-15
 * wiring) — the "Not wired up yet" badge and the toggle-ON lock below are
 * driven entirely by rl-arms.ts's `RL_DIMENSIONS[...].active`, so they're
 * inert today but stay in place as the guard against a FUTURE dimension
 * (added to the backend's PolicyDimension enum before its runtime wiring
 * lands) silently claiming to work. Turning a policy OFF is always allowed,
 * regardless of `active` - a tenant that already has one enabled must never
 * be stranded with a stuck switch it can't undo.
 */
function PolicyControls({
  agentId,
  policies,
  loading,
  reload,
  onSaved,
}: {
  agentId: string;
  policies: any[] | null;
  loading: boolean;
  reload: () => void;
  onSaved: () => void;
}) {
  const t = useTranslations("f2GapsAgents");
  const [drafts, setDrafts] = useState<Record<string, { enabled: boolean; explorationRate: number; algorithm: string }>>({});
  const [savingKey, setSavingKey] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Re-seed the drafts whenever the parent hands over a fresh policy list, so
  // the controls never drift from persisted state (including after a failed
  // save, which triggers a reload upstream).
  useEffect(() => {
    const next: Record<string, { enabled: boolean; explorationRate: number; algorithm: string }> = {};
    for (const p of policies || []) {
      next[p.dimension] = {
        enabled: !!p.enabled,
        explorationRate: Number(p.explorationRate) || 0,
        algorithm: p.algorithm || "THOMPSON",
      };
    }
    setDrafts(next);
  }, [policies]);

  async function save(dimension: string, patch: { enabled: boolean; explorationRate: number; algorithm: string }) {
    setSavingKey(dimension);
    setError(null);
    try {
      await api.updateRlPolicy(agentId, dimension, patch);
      setDrafts((d) => ({ ...d, [dimension]: patch }));
      onSaved();
    } catch (err: any) {
      setError(err?.message || t("lrnSaveFailed"));
      // Re-sync from server so the UI never drifts from persisted state.
      reload();
    } finally {
      setSavingKey(null);
    }
  }

  if (loading) return <p className="text-sm text-muted-foreground">{t("lrnPoliciesLoading")}</p>;
  if (!policies || policies.length === 0) {
    return <p className="text-sm text-muted-foreground">{t("lrnNoPolicies")}</p>;
  }

  return (
    <div className="space-y-4">
      {error && <p className="text-sm text-destructive">{error}</p>}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        {policies.map((p) => {
          const draft = drafts[p.dimension] || {
            enabled: !!p.enabled,
            explorationRate: Number(p.explorationRate) || 0,
            algorithm: p.algorithm || "THOMPSON",
          };
          const busy = savingKey === p.dimension;
          const meta = dimensionMeta(p.dimension);
          // Inert dimension: allow switching OFF, never ON.
          const toggleLocked = !meta.active && !draft.enabled;
          // Tuning an inert (or paused) policy is pointless — grey it out.
          const tuningDisabled = busy || !draft.enabled || !meta.active;
          return (
            <Card key={p.dimension} className={cn("gap-4 p-5", !meta.active && "opacity-95")}>
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-semibold text-foreground">{meta.label}</span>
                    {!meta.active && (
                      <Badge variant="secondary" className="gap-1">
                        <Construction size={11} /> {t("lrnNotWired")}
                      </Badge>
                    )}
                  </div>
                  <div className="font-mono text-[11px] uppercase tracking-wider text-muted-foreground">
                    {p.dimension}
                  </div>
                </div>
                <button
                  type="button"
                  role="switch"
                  aria-checked={draft.enabled}
                  aria-label={t("lrnExplorationFor", { label: meta.label })}
                  disabled={busy || toggleLocked}
                  title={
                    toggleLocked
                      ? t("lrnToggleLocked")
                      : undefined
                  }
                  className={cn(
                    "ax-toggle",
                    draft.enabled && "ax-toggle-on",
                    toggleLocked && "cursor-not-allowed opacity-40"
                  )}
                  onClick={() => {
                    if (toggleLocked) return;
                    void save(p.dimension, { ...draft, enabled: !draft.enabled });
                  }}
                />
              </div>

              <p className="text-xs leading-relaxed text-muted-foreground">{meta.description}</p>

              {!meta.active && (
                <Alert>
                  <Construction />
                  <AlertTitle>{t("lrnInertTitle")}</AlertTitle>
                  <AlertDescription>
                    <p>
                      {t("lrnInertBody")}
                      {draft.enabled && ` ${t("lrnInertOn")}`}
                    </p>
                  </AlertDescription>
                </Alert>
              )}

              <div className="space-y-1.5">
                <div className="flex items-center justify-between">
                  <label className="text-xs font-medium text-foreground" htmlFor={`explore-${p.dimension}`}>
                    {t("lrnExplorationRate")}
                  </label>
                  <span className="font-mono text-xs tabular-nums text-primary">
                    {(draft.explorationRate * 100).toFixed(0)}%
                  </span>
                </div>
                <input
                  id={`explore-${p.dimension}`}
                  type="range"
                  min={0}
                  max={1}
                  step={0.05}
                  value={draft.explorationRate}
                  disabled={tuningDisabled}
                  className="w-full accent-primary disabled:opacity-50"
                  onChange={(e) =>
                    setDrafts((d) => ({
                      ...d,
                      [p.dimension]: { ...draft, explorationRate: Number(e.target.value) },
                    }))
                  }
                  onPointerUp={() => save(p.dimension, drafts[p.dimension] || draft)}
                  onKeyUp={() => save(p.dimension, drafts[p.dimension] || draft)}
                  aria-valuetext={t("lrnPercent", { value: (draft.explorationRate * 100).toFixed(0) })}
                />
                <p className="text-xs text-muted-foreground">{t("lrnExplorationHint")}</p>
              </div>

              <div className="space-y-1.5">
                <label className="text-xs font-medium text-foreground" htmlFor={`algorithm-${p.dimension}`}>
                  {t("lrnAlgorithm")}
                </label>
                <Select
                  id={`algorithm-${p.dimension}`}
                  value={draft.algorithm}
                  disabled={tuningDisabled}
                  onValueChange={(algorithm) => {
                    const next = { ...draft, algorithm };
                    setDrafts((d) => ({ ...d, [p.dimension]: next }));
                    void save(p.dimension, next);
                  }}
                  options={[
                    { value: "THOMPSON", label: t("lrnThompson") },
                    { value: "UCB1", label: "UCB1" },
                  ]}
                />
                <p className="text-xs text-muted-foreground">{t("lrnAlgorithmHint")}</p>
              </div>
              {busy && <p className="text-xs text-muted-foreground">{t("lrnSaving")}</p>}
            </Card>
          );
        })}
      </div>
    </div>
  );
}
