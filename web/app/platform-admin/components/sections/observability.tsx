"use client";

import { useCallback, useEffect, useState } from "react";
import { motion, AnimatePresence } from "motion/react";
import { AlertTriangle, AlertOctagon, Activity, Zap, CircleDollarSign, Siren } from "lucide-react";
import { api } from "../../lib/api";
import PaginationControls from "../PaginationControls";
import { usePagination } from "../../lib/usePagination";
import { toast } from "../../lib/toastBus";
import { Badge } from "../ui/badge";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Button } from "../ui/button";
import { Alert, AlertDescription } from "../ui/alert";
import { formatUsd, timeAgo, SectionCard } from "./common";

// Self-contained, prop-light observability sections lifted out of the old
// single-page /observability screen so they can be reused across the new IA:
// LLM usage + hosted cost land in Spend & Reports (§3.4), the activity/audit
// feed in Audit & Compliance (§3.8), issues in Diagnostics, and the fallback
// anomaly signal in Fraud & Anomaly (§3.7) - and every one of them (plus system
// status) is also embedded as a native panel in Diagnostics & Observability
// (§10). Each fetches its own slice on mount so pages compose them without prop
// plumbing; pass `pollMs` to opt into the same live cadence the original page
// used. Rebuilt on shadcn tokens to match the rest of the app.

// text-*-600 measured ~2.95-3.43:1 on this badge's own light bg tint - under
// AA at this text size. -700 clears it comfortably (dark mode's -400 pairing
// was already AA and is untouched).
const OK_BADGE = "border-[var(--success-border)] bg-[var(--success-bg)] text-[var(--success-text)]";
const WARN_BADGE = "border-[var(--warning-border)] bg-[var(--warning-bg)] text-[var(--warning-text)]";
const META_CLS = "font-mono text-xs text-muted-foreground";

export function useSectionData<T>(loader: () => Promise<T>, initial: T, pollMs?: number) {
  const [data, setData] = useState<T>(initial);
  const [error, setError] = useState<any>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    try {
      const res = await loader();
      setData(res);
      setError(null);
    } catch (err: any) {
      setError(err.message || "Could not load data.");
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    refresh().catch((e) => console.error("Observability section load failed", e));
    if (!pollMs) return undefined;
    const t = setInterval(() => {
      refresh().catch((e) => console.error("Observability section poll failed", e));
    }, pollMs);
    return () => clearInterval(t);
  }, [refresh, pollMs]);

  return { data, error, loading, refresh };
}

function SourceBadge({ source }: { source: any }) {
  const label = ({ WORKFLOW_RUN: "Workflow", TOOL_CALL: "Tool call", EXCEPTION: "Exception" } as any)[source] || source;
  return <Badge variant="secondary">{label}</Badge>;
}

function StatusBadge({ status }: { status: any }) {
  if (status === "RESOLVED")
    return (
      <Badge variant="outline" className={OK_BADGE}>
        Resolved
      </Badge>
    );
  if (status === "ACKNOWLEDGED") return <Badge variant="secondary">Acknowledged</Badge>;
  return (
    <Badge variant="outline" className={WARN_BADGE}>
      Open
    </Badge>
  );
}

function OutcomeBadge({ outcome }: { outcome: any }) {
  if (outcome === "SUCCESS" || outcome === "AUTO_APPROVED" || outcome === "APPROVED") {
    return (
      <Badge variant="outline" className={OK_BADGE}>
        {outcome.replace("_", " ")}
      </Badge>
    );
  }
  if (outcome === "FAILURE" || outcome === "REJECTED") {
    return (
      <Badge variant="outline" className={WARN_BADGE}>
        {outcome}
      </Badge>
    );
  }
  return <Badge variant="secondary">{outcome === "PENDING_APPROVAL" ? "Pending approval" : outcome}</Badge>;
}

// ── Issues (fingerprinted platform issues portal) ───────────────────────────
export function IssuesSection({ pollMs, delay }: { pollMs?: number; delay?: number }) {
  const {
    data: issues,
    error,
    loading,
  } = useSectionData<any[]>(() => api.getPlatformIssues().then((r) => (Array.isArray(r) ? r : [])), [], pollMs);
  const openCount = issues.filter((i) => i.status === "OPEN").length;
  const pg = usePagination(issues, 10);

  return (
    <SectionCard
      title="Issues"
      subtitle="Fingerprinted platform issues across every active tenant (PlatformIssueService)"
      icon={AlertTriangle}
      delay={delay}
      actions={
        openCount > 0 ? (
          <Badge variant="outline" className={WARN_BADGE}>
            {openCount} open
          </Badge>
        ) : undefined
      }
    >
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {loading ? (
        <p className="py-1 text-sm text-muted-foreground">Loading…</p>
      ) : issues.length === 0 ? (
        <p className="py-1 text-sm text-muted-foreground">No issues reported across any active tenant.</p>
      ) : (
        <AnimatePresence initial={false}>
          {pg.pageItems.map((issue) => (
            <motion.div
              key={issue.issueId}
              className="flex items-start gap-3 rounded-lg border border-border p-3"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
            >
              <div className="mt-0.5">
                {issue.severity === "ERROR" ? (
                  <AlertOctagon size={16} className="text-destructive" />
                ) : (
                  <AlertTriangle size={16} className="text-[var(--warning)]" />
                )}
              </div>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <strong className="text-sm text-foreground">{issue.title}</strong>
                  <SourceBadge source={issue.source} />
                  <StatusBadge status={issue.status} />
                  {issue.occurrenceCount > 1 && <Badge variant="secondary">&times;{issue.occurrenceCount}</Badge>}
                </div>
                <p className="mt-1 text-xs text-muted-foreground">
                  <span className="font-mono">{issue.tenantName}</span> · first seen {timeAgo(issue.firstSeenAt)} · last
                  seen {timeAgo(issue.lastSeenAt)}
                </p>
              </div>
            </motion.div>
          ))}
        </AnimatePresence>
      )}
      <PaginationControls
        page={pg.page}
        totalPages={pg.totalPages}
        totalItems={pg.totalItems}
        pageSize={pg.pageSize}
        onPageChange={pg.setPage}
        onPageSizeChange={pg.setPageSize}
        itemLabel="issues"
      />
    </SectionCard>
  );
}

// ── LLM usage / cost per tenant ─────────────────────────────────────────────
export function LlmUsageSection({ pollMs, delay }: { pollMs?: number; delay?: number }) {
  const {
    data: rows,
    error,
    loading,
  } = useSectionData<any[]>(() => api.getPlatformLlmUsage().then((r) => (Array.isArray(r) ? r : [])), [], pollMs);
  const pg = usePagination(rows, 10);

  return (
    <SectionCard
      title="LLM usage & cost by tenant"
      subtitle="Durable per-tenant LLM call volume, tokens, latency and estimated spend (blends every provider a tenant uses)"
      icon={Zap}
      delay={delay}
    >
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {loading ? (
        <p className="py-1 text-sm text-muted-foreground">Loading…</p>
      ) : rows.length === 0 ? (
        <p className="py-1 text-sm text-muted-foreground">No LLM usage recorded across any active tenant.</p>
      ) : (
        pg.pageItems.map((row) => (
          <div
            key={row.tenantId}
            className="flex flex-wrap items-center gap-3 rounded-lg border border-border px-4 py-3"
          >
            <div className="min-w-[160px] flex-1">
              <strong className="text-sm text-foreground">{row.tenantName}</strong>
              {row.errorCount > 0 && (
                <Badge variant="outline" className={`ms-2 ${WARN_BADGE}`}>
                  {row.errorCount} error{row.errorCount === 1 ? "" : "s"}
                </Badge>
              )}
            </div>
            <div className={META_CLS}>
              {row.totalCalls} call{row.totalCalls === 1 ? "" : "s"}
            </div>
            <div className={META_CLS}>
              {row.avgLatencyMs != null ? `${Math.round(row.avgLatencyMs)}ms avg` : "no calls yet"}
            </div>
            <div className={META_CLS}>{Number(row.totalTokens || 0).toLocaleString()} tokens</div>
            <div className="min-w-[70px] text-end font-mono text-sm text-foreground">
              {formatUsd(row.estimatedCostUsd)}
            </div>
          </div>
        ))
      )}
      <PaginationControls
        page={pg.page}
        totalPages={pg.totalPages}
        totalItems={pg.totalItems}
        pageSize={pg.pageSize}
        onPageChange={pg.setPage}
        onPageSizeChange={pg.setPageSize}
        itemLabel="tenants"
      />
    </SectionCard>
  );
}

// ── Hosted-pool cost (what FREE-tier traffic costs the platform) ────────────
// Direct feedback: platform admin dashboard needs (1) total LLM cost incurred
// so far, broken down by day, and (2) remaining balance/budget available.
// "Cost" here is deliberately PLATFORM_HOSTED-only (what the platform itself
// pays for) - a tenant's own BYOK spend never touches this number, matching
// PlatformHostedLlmCostSummary's javadoc. The budget line is admin-set (no
// fabricated default) via PlatformObservabilityService#updateHostedLlmBudget.
export function HostedCostSection({ pollMs, delay }: { pollMs?: number; delay?: number }) {
  const {
    data: hostedCost,
    error,
    loading,
    refresh,
  } = useSectionData<any>(() => api.getPlatformHostedLlmCost(), null, pollMs);
  const {
    data: byDay,
    error: trendError,
    loading: trendLoading,
  } = useSectionData<any[]>(
    () => api.getPlatformHostedLlmCostByDay(30).then((r: any) => (Array.isArray(r) ? r : [])),
    [],
    pollMs
  );

  const [budgetInput, setBudgetInput] = useState("");
  const [editingBudget, setEditingBudget] = useState(false);
  const [savingBudget, setSavingBudget] = useState(false);

  useEffect(() => {
    if (!editingBudget) {
      setBudgetInput(hostedCost?.budgetUsd != null ? String(hostedCost.budgetUsd) : "");
    }
  }, [hostedCost, editingBudget]);

  async function saveBudget() {
    const trimmed = budgetInput.trim();
    const parsed = trimmed === "" ? null : Number(trimmed);
    if (parsed !== null && (!Number.isFinite(parsed) || parsed < 0)) {
      toast.error("Budget must be a non-negative dollar amount (or blank to clear it).");
      return;
    }
    setSavingBudget(true);
    try {
      await api.updatePlatformHostedLlmBudget(parsed);
      await refresh();
      setEditingBudget(false);
      toast.success(parsed === null ? "Hosted-LLM budget cleared." : `Hosted-LLM budget set to ${formatUsd(parsed)}.`);
    } catch (err: any) {
      toast.error(err.message || "Could not update the hosted-LLM budget.");
    }
    setSavingBudget(false);
  }

  const totalCost = Number(hostedCost?.estimatedCostUsd ?? 0);
  const hasBudget = hostedCost?.budgetUsd != null;
  const remaining = hostedCost?.remainingBudgetUsd;
  const overBudget = hasBudget && Number(remaining) < 0;
  const maxDayCost = (byDay || []).reduce((m, d) => Math.max(m, Number(d.estimatedCostUsd || 0)), 0);

  return (
    <SectionCard
      title="Hosted LLM cost (FREE tier)"
      subtitle="PLATFORM_HOSTED-only totals - what FREE-tier traffic actually costs the platform, separate from tenants' own BYOK spend"
      icon={CircleDollarSign}
      delay={delay}
    >
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {loading ? (
        <p className="py-1 text-sm text-muted-foreground">Loading…</p>
      ) : (
        <>
          <div className="mb-3 flex flex-wrap gap-3">
            {[
              ["Total cost (all-time)", formatUsd(totalCost)],
              ["Hosted-pool calls", hostedCost?.totalCalls ?? 0],
              ["Errors", hostedCost?.errorCount ?? 0],
              ["Tokens", Number(hostedCost?.totalTokens || 0).toLocaleString()],
            ].map(([label, val]) => (
              <div key={label as string} className="flex-1 basis-36">
                <div className="text-xs text-muted-foreground">{label}</div>
                <div className="mt-1 font-mono text-xl font-semibold text-foreground">{val}</div>
              </div>
            ))}
          </div>

          {/* Remaining balance / budget editor */}
          <div className="mb-3 flex flex-wrap items-end justify-between gap-4 rounded-lg border border-border px-4 py-3">
            <div>
              <div className="text-xs text-muted-foreground">Remaining balance</div>
              {hasBudget ? (
                <div className="mt-1 flex items-center gap-2">
                  <span
                    className={`font-mono text-xl font-semibold ${overBudget ? "text-destructive" : "text-foreground"}`}
                  >
                    {formatUsd(remaining)}
                  </span>
                  <span className="text-xs text-muted-foreground">of {formatUsd(hostedCost.budgetUsd)} budget</span>
                  {overBudget && (
                    <Badge variant="outline" className={WARN_BADGE}>
                      Over budget
                    </Badge>
                  )}
                </div>
              ) : (
                <div className="mt-1 text-sm text-muted-foreground">No platform budget configured yet.</div>
              )}
            </div>
            <div className="flex items-end gap-2">
              <div className="space-y-1.5">
                <Label htmlFor="hosted-budget-input" className="text-xs text-muted-foreground">
                  All-time budget (USD)
                </Label>
                <Input
                  id="hosted-budget-input"
                  type="number"
                  min="0"
                  step="0.01"
                  placeholder="unset"
                  className="w-32"
                  value={budgetInput}
                  onFocus={() => setEditingBudget(true)}
                  onChange={(e) => {
                    setEditingBudget(true);
                    setBudgetInput(e.target.value);
                  }}
                />
              </div>
              <Button variant="outline" size="sm" onClick={saveBudget} disabled={savingBudget}>
                {savingBudget ? "Saving…" : "Save"}
              </Button>
            </div>
          </div>

          {/* Day-by-day trend */}
          <div className="mb-3 rounded-lg border border-border px-4 py-3">
            <div className="mb-2 text-xs font-medium text-muted-foreground">Last 30 days</div>
            {trendError && (
              <Alert variant="destructive">
                <AlertDescription>{trendError}</AlertDescription>
              </Alert>
            )}
            {trendLoading ? (
              <p className="py-1 text-sm text-muted-foreground">Loading…</p>
            ) : !byDay || byDay.length === 0 ? (
              <p className="py-1 text-sm text-muted-foreground">No hosted-pool cost recorded in the last 30 days.</p>
            ) : (
              <div className="space-y-1">
                {byDay.map((d: any) => {
                  const cost = Number(d.estimatedCostUsd || 0);
                  const widthPct = maxDayCost > 0 ? Math.max((cost / maxDayCost) * 100, cost > 0 ? 2 : 0) : 0;
                  return (
                    <div key={d.date} className="flex items-center gap-2">
                      <div className="w-20 shrink-0 font-mono text-[11px] text-muted-foreground">{d.date}</div>
                      <div className="h-4 flex-1 overflow-hidden rounded bg-secondary/40">
                        <div className="h-full rounded bg-primary/70" style={{ width: `${widthPct}%` }} />
                      </div>
                      <div className="w-20 shrink-0 text-end font-mono text-[11px] text-foreground">
                        {formatUsd(cost)}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>

          {hostedCost?.totalCalls === 0 ? (
            <p className="py-1 text-sm text-muted-foreground">
              No PLATFORM_HOSTED calls yet - FREE-tier tenants haven&rsquo;t used the hosted pool so far.
            </p>
          ) : (
            (hostedCost?.byTenant || []).map((row: any) => (
              <div
                key={row.tenantId}
                className="flex flex-wrap items-center gap-3 rounded-lg border border-border px-4 py-3"
              >
                <div className="min-w-[160px] flex-1">
                  <strong className="text-sm text-foreground">{row.tenantName}</strong>
                </div>
                <div className={META_CLS}>
                  {row.totalCalls} call{row.totalCalls === 1 ? "" : "s"}
                </div>
                <div className={META_CLS}>{Number(row.totalTokens || 0).toLocaleString()} tokens</div>
                <div className="min-w-[70px] text-end font-mono text-sm text-foreground">
                  {formatUsd(row.estimatedCostUsd)}
                </div>
              </div>
            ))
          )}
        </>
      )}
    </SectionCard>
  );
}

// ── Activity / audit feed ───────────────────────────────────────────────────
export function ActivitySection({ pollMs, delay }: { pollMs?: number; delay?: number }) {
  const {
    data: activity,
    error,
    loading,
  } = useSectionData<any[]>(() => api.getPlatformActivity().then((r) => (Array.isArray(r) ? r : [])), [], pollMs);
  const pg = usePagination(activity, 25);

  return (
    <SectionCard
      title="Audit log"
      subtitle="Actor / action / resource / outcome across every active tenant (AuditLogService), merged and capped for one readable feed"
      icon={Activity}
      delay={delay}
    >
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {loading ? (
        <p className="py-1 text-sm text-muted-foreground">Loading…</p>
      ) : activity.length === 0 ? (
        <p className="py-1 text-sm text-muted-foreground">No activity recorded across any active tenant.</p>
      ) : (
        <AnimatePresence initial={false}>
          {pg.pageItems.map((entry) => (
            <motion.div
              key={entry.entryId}
              className="flex items-start gap-3 rounded-lg border border-border p-3"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
            >
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge variant="secondary">{entry.actionType}</Badge>
                  {entry.targetType && (
                    <span className={META_CLS}>
                      {entry.targetType}
                      {entry.targetId ? ` #${entry.targetId}` : ""}
                    </span>
                  )}
                  <OutcomeBadge outcome={entry.outcome} />
                </div>
                <p className="mt-1 text-xs text-muted-foreground">
                  <span className="font-mono">{entry.tenantName}</span> · by {entry.actorId || "system"} ·{" "}
                  {timeAgo(entry.createdAt)}
                </p>
                {entry.detail && <p className="mt-1 break-words text-xs text-muted-foreground">{entry.detail}</p>}
              </div>
            </motion.div>
          ))}
        </AnimatePresence>
      )}
      <PaginationControls
        page={pg.page}
        totalPages={pg.totalPages}
        totalItems={pg.totalItems}
        pageSize={pg.pageSize}
        onPageChange={pg.setPage}
        onPageSizeChange={pg.setPageSize}
        itemLabel="entries"
      />
    </SectionCard>
  );
}

// ── Provider-fallback alert status + threshold editor ───────────────────────
export function FallbackSection({ pollMs, delay }: { pollMs?: number; delay?: number }) {
  const {
    data: fallbackStatus,
    error,
    loading,
    refresh,
  } = useSectionData<any>(() => api.getFallbackAlertStatus(), null, pollMs);
  const [thresholdInput, setThresholdInput] = useState("");
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    setThresholdInput((cur) => (cur === "" ? String(fallbackStatus?.threshold ?? "") : cur));
  }, [fallbackStatus]);

  async function save() {
    const parsed = Number(thresholdInput);
    if (!Number.isInteger(parsed) || parsed < 0) {
      toast.error("Threshold must be a whole number, 0 or greater.");
      return;
    }
    setSaving(true);
    try {
      await api.updateFallbackAlertThreshold(parsed);
      await refresh();
      toast.success(`Fallback-failure alert threshold set to ${parsed}.`);
    } catch (err: any) {
      toast.error(err.message || "Could not update the fallback alert threshold.");
    }
    setSaving(false);
  }

  return (
    <SectionCard
      title="Provider-fallback anomaly signal"
      subtitle="Fleet-wide lifetime provider-fallback totals vs. a configurable alert threshold (an early spend/reliability anomaly signal)"
      icon={Siren}
      delay={delay}
    >
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {fallbackStatus?.alertActive && (
        <Alert variant="destructive">
          <AlertOctagon />
          <AlertDescription>
            Fleet-wide provider-fallback failures ({fallbackStatus.totalFallbackFailures}) have crossed the alert
            threshold ({fallbackStatus.threshold}). {fallbackStatus.tenantBreakdown?.length || 0} tenant
            {fallbackStatus.tenantBreakdown?.length === 1 ? "" : "s"} affected.
          </AlertDescription>
        </Alert>
      )}
      {loading ? (
        <p className="py-1 text-sm text-muted-foreground">Loading…</p>
      ) : (
        <div className="flex flex-wrap items-end gap-4">
          <div>
            <div className="text-xs text-muted-foreground">Provider fallbacks (all-time)</div>
            <div className="mt-1 flex items-center gap-2 font-mono text-xl font-semibold text-foreground">
              {fallbackStatus ? fallbackStatus.totalFallbackTriggered : "-"} triggered
              {fallbackStatus && fallbackStatus.totalFallbackFailures > 0 && (
                <Badge variant="outline" className={WARN_BADGE}>
                  {fallbackStatus.totalFallbackFailures} also failed
                </Badge>
              )}
            </div>
          </div>
          <div className="flex items-end gap-2">
            <div className="space-y-1.5">
              <Label htmlFor="fallback-threshold-input" className="text-xs text-muted-foreground">
                Alert threshold (fallback failures)
              </Label>
              <Input
                id="fallback-threshold-input"
                type="number"
                min="0"
                step="1"
                className="w-24"
                value={thresholdInput}
                onChange={(e) => setThresholdInput(e.target.value)}
              />
            </div>
            <Button variant="outline" size="sm" onClick={save} disabled={saving}>
              {saving ? "Saving…" : "Save"}
            </Button>
          </div>
          {fallbackStatus?.tenantBreakdown?.length > 0 && (
            <div className="flex w-full flex-wrap gap-2">
              {fallbackStatus.tenantBreakdown.map((row: any) => (
                <Badge key={row.tenantId} variant="secondary">
                  {row.tenantName}: {row.fallbackTriggeredCount} triggered
                  {row.fallbackFailedCount > 0 ? `, ${row.fallbackFailedCount} failed` : ""}
                </Badge>
              ))}
            </div>
          )}
        </div>
      )}
    </SectionCard>
  );
}
