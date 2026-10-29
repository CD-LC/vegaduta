"use client";

import { Fragment, useCallback, useEffect, useMemo, useState } from "react";
import { Cpu, Loader2, Download, Lock, ArrowUpRight, RefreshCw } from "lucide-react";
import { api } from "../../lib/api";
import type { LlmCallDiagnosis } from "../../lib/api";
import Select from "../Select";
import PaginationControls from "../PaginationControls";
import { usePagination } from "../../lib/usePagination";
import { useTranslations } from "../../lib/i18n";
import { isBillingEnabled } from "../../lib/billing-flag";
import { Card } from "../ui/card";
import { Button } from "../ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../ui/table";
import BrandText from "../BrandText";

// AI model spend - the tenant-facing "what is my LLM usage costing me"
// surface, on the Billing & usage page (/admin/billing).
//
// History: this started life as dashboard/LlmUsagePanel on the Platform
// Maintenance page's Overview tab (#606). Direct feedback (2026-09-24):
// "LLM cost and usage in maintenance page ... it's really hard for customers
// to see that ... move to the right place where they can see billing and
// everything together." Maintenance is a health/ops screen; money belongs
// next to the plan card, the usage meters and the invoices, so the panel
// moved here and was rebuilt on the same shadcn Card/Table primitives the
// rest of this page uses (the old .ax-mnt-llm-* CSS is gone with it).
//
// Data sources - all real, nothing fabricated:
//   - GET /api/admin/llm-usage/summary   one row per provider actually used
//   - GET /api/admin/llm-usage/calls     recent call log (newest first)
//   - GET /api/system/status/llm-telemetry  today's cost / cost per
//     conversation / 24h model mix (LlmDashboardMetrics; any field null
//     when there is no data in its window)
// The first two are PREMIUM+ by design (LlmCallLogController#assertPremiumPlan
// answers 403 on FREE) - that state renders as an honest "Premium feature"
// notice with the page's own Upgrade action, never as a raw error string.
//
// Cost is explicitly an ESTIMATE (LlmPricingCatalog's javadoc): token counts
// x published base rates, no prompt-cache or tool-overhead accounting. A
// model the platform doesn't price renders "—", never a made-up number.
const POLL_MS = 15000;
const CALL_LOG_LIMIT = 200; // LlmCallLogController clamps to [1, 200]

// TS mirrors of LlmCallLogController.java's response records (the api.ts
// reads are untyped `.then(toJsonQuietOn(...))`, so the contract is declared
// at the consumer). providerName is the ProviderName enum serialized as its
// name - kept a plain string so a new enum constant renders instead of
// failing the build. estimatedCostUsd is a Java BigDecimal (JSON number).
interface LlmCallLogEntry {
  id: string;
  providerName: string;
  model: string | null;
  totalTokens: number | null;
  estimatedCostUsd: number | null;
  success: boolean;
  errorMessage: string | null;
  latencyMs: number;
  startedAt: string; // ISO-8601 instant
}

interface LlmProviderUsageSummary {
  providerName: string;
  totalCalls: number;
  errorCount: number;
  totalTokens: number;
  estimatedCostUsd: number | null;
  firstCallAt: string | null;
  lastCallAt: string | null;
}

// Mirror of LlmDashboardMetrics.java - every field is null (never 0) when
// its window holds no data.
interface LlmTelemetry {
  callsLast24h: number;
  costTodayUsd: number | null;
  costPerConversationTodayUsd: number | null;
  conversationsToday: number;
  modelSplitLast24h: { model: string; calls: number; percent: number }[] | null;
}

function num(v: unknown): number | null {
  if (v === null || v === undefined) return null;
  const n = Number(v);
  return Number.isNaN(n) ? null : n;
}

// Per-call amounts are fractions of a cent, so they keep 6 decimals; totals
// read better at 4.
function fmtUsd(v: unknown, digits: number) {
  const n = num(v);
  return n === null ? "—" : `$${n.toFixed(digits)}`;
}

function fmtInt(v: unknown) {
  const n = num(v);
  return n === null ? "—" : n.toLocaleString();
}

function fmtTime(iso: string | null | undefined) {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "—" : d.toLocaleString();
}

function fmtDate(iso: string | null | undefined) {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "—" : d.toLocaleDateString();
}

function fmtLatency(ms: number | null | undefined) {
  if (ms === null || ms === undefined) return "—";
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`;
}

// ProviderName.PLATFORM_HOSTED is an internal accounting label for calls a
// tenant makes through the platform-included pool - it must never leak as
// the raw enum string onto a customer screen (direct feedback, see the enum's
// javadoc). Every other provider is one the tenant activated with their own
// key, so those render as-is.
function displayProviderName(name: string) {
  return name === "PLATFORM_HOSTED" ? "VegaDūta (included)" : name;
}

// Error objects thrown by api.ts carry the HTTP status as `status`.
function statusOf(err: unknown): number | undefined {
  return typeof err === "object" && err !== null && typeof (err as { status?: unknown }).status === "number"
    ? (err as { status: number }).status
    : undefined;
}

function messageOf(err: unknown): string | undefined {
  return typeof err === "object" && err !== null && typeof (err as { message?: unknown }).message === "string"
    ? (err as { message: string }).message
    : undefined;
}

function csvCell(v: unknown) {
  const s = v === null || v === undefined ? "" : String(v);
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

function StatTile({ label, value, hint, testId }: { label: string; value: string; hint?: string; testId?: string }) {
  return (
    <div className="rounded-xl border border-border bg-secondary/20 px-4 py-3" data-testid={testId}>
      <div className="font-mono text-[length:var(--fs-label)] uppercase tracking-[0.1em] text-muted-foreground">{label}</div>
      <div className="mt-1 font-mono text-xl font-semibold tabular-nums tracking-tight text-foreground">{value}</div>
      {hint && <div className="mt-0.5 text-xs text-muted-foreground">{hint}</div>}
    </div>
  );
}

export interface LlmSpendSectionProps {
  /** True when a higher tier exists for this workspace (mirrors the page's own Upgrade affordance). */
  canUpgrade: boolean;
  /** Opens the page's UpgradePrompt - the locked state reuses it rather than growing a second checkout path. */
  onUpgrade: () => void;
}

export default function LlmSpendSection({ canUpgrade, onUpgrade }: LlmSpendSectionProps) {
  const t = useTranslations("llmUsagePanel");
  // Self-hosted/personal installs price nothing - the dollar estimate is
  // meaningless against a local model, so every $ cell hides and tokens,
  // calls, latency and providers stay (NEXT_PUBLIC_BILLING_ENABLED=1 is the
  // hosted SaaS posture).
  const billing = isBillingEnabled();
  const STATUS_TABS = [
    { value: "all", label: t("statusTabAll") },
    { value: "success", label: t("statusTabSuccess") },
    { value: "error", label: t("statusTabError") },
  ];
  const [statusFilter, setStatusFilter] = useState("all");
  const [calls, setCalls] = useState<LlmCallLogEntry[]>([]);
  const [summary, setSummary] = useState<LlmProviderUsageSummary[]>([]);
  const [telemetry, setTelemetry] = useState<LlmTelemetry | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [premiumLocked, setPremiumLocked] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  // Per-row "Diagnose" state - cached by call id so a diagnosis is fetched at
  // most once per row and survives the poll (which replaces `calls` only).
  const [diagnoses, setDiagnoses] = useState<Record<string, LlmCallDiagnosis>>({});
  const [diagnosisOpen, setDiagnosisOpen] = useState<Record<string, boolean>>({});
  const [diagnosisErrors, setDiagnosisErrors] = useState<Record<string, boolean>>({});
  const [diagnosingId, setDiagnosingId] = useState<string | null>(null);

  async function handleDiagnose(id: string) {
    if (diagnoses[id]) {
      setDiagnosisOpen((prev) => ({ ...prev, [id]: !prev[id] }));
      return;
    }
    if (diagnosingId) return;
    setDiagnosingId(id);
    setDiagnosisErrors((prev) => {
      const next = { ...prev };
      delete next[id];
      return next;
    });
    try {
      const result = await api.diagnoseLlmCall(id);
      if (result) {
        setDiagnoses((prev) => ({ ...prev, [id]: result }));
        setDiagnosisOpen((prev) => ({ ...prev, [id]: true }));
      }
    } catch (err) {
      // 403 (FREE plan) / 404 (row pruned, or the call succeeded) are
      // expected states; anything else gets an inline retryable notice.
      const status = statusOf(err);
      if (status !== 403 && status !== 404) {
        setDiagnosisErrors((prev) => ({ ...prev, [id]: true }));
      }
    } finally {
      setDiagnosingId(null);
    }
  }

  // `status` is the filter value ("all" | "success" | "error"), passed
  // straight through as getLlmCalls' query param.
  const refresh = useCallback(async (status: string, manual = false) => {
    if (manual) setRefreshing(true);
    const [callsRes, summaryRes, telemetryRes] = await Promise.allSettled([
      api.getLlmCalls(status, CALL_LOG_LIMIT),
      api.getLlmUsageSummary(),
      api.getLlmTelemetry(),
    ]);
    const gated = [callsRes, summaryRes].find((r) => r.status === "rejected");
    if (gated && gated.status === "rejected") {
      const status403 = statusOf(gated.reason) === 403;
      setPremiumLocked(status403);
      setError(status403 ? null : messageOf(gated.reason) || t("couldNotLoad"));
    } else {
      setPremiumLocked(false);
      setError(null);
      if (callsRes.status === "fulfilled") setCalls(Array.isArray(callsRes.value) ? callsRes.value : []);
      if (summaryRes.status === "fulfilled") setSummary(Array.isArray(summaryRes.value) ? summaryRes.value : []);
    }
    // Telemetry is a bonus layer (today's spend, model mix) - an older core
    // without it, or any failure, just hides those tiles.
    setTelemetry(telemetryRes.status === "fulfilled" && telemetryRes.value ? telemetryRes.value : null);
    setLastUpdated(new Date());
    setLoaded(true);
    if (manual) setRefreshing(false);
    // `t`'s identity changes with the locale; a stale-locale fallback string
    // on a transient error is a lower-stakes edge case than re-arming the
    // poll every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    void refresh(statusFilter);
    const id = setInterval(() => {
      void refresh(statusFilter);
    }, POLL_MS);
    return () => clearInterval(id);
  }, [statusFilter, refresh]);

  const totals = useMemo(() => {
    const cost = summary.reduce((sum, s) => sum + (num(s.estimatedCostUsd) ?? 0), 0);
    const callsCount = summary.reduce((sum, s) => sum + (num(s.totalCalls) ?? 0), 0);
    const errors = summary.reduce((sum, s) => sum + (num(s.errorCount) ?? 0), 0);
    const tokens = summary.reduce((sum, s) => sum + (num(s.totalTokens) ?? 0), 0);
    const errorRate = callsCount > 0 ? (errors / callsCount) * 100 : 0;
    return { cost, callsCount, errors, tokens, errorRate };
  }, [summary]);

  // Providers ordered by spend, highest first, so the biggest line item is
  // the first thing a customer reads.
  const providersBySpend = useMemo(
    () => [...summary].sort((a, b) => (num(b.estimatedCostUsd) ?? 0) - (num(a.estimatedCostUsd) ?? 0)),
    [summary]
  );

  const modelMix = useMemo(() => {
    const list = telemetry?.modelSplitLast24h;
    return Array.isArray(list) ? [...list].sort((a, b) => b.calls - a.calls).slice(0, 8) : [];
  }, [telemetry]);

  const { page, pageSize, pageItems, totalPages, totalItems, setPage, setPageSize } = usePagination(calls);

  // CSV of the currently loaded call log (the active filter, up to
  // CALL_LOG_LIMIT rows) - built client-side from data already on screen.
  const exportCsv = useCallback(() => {
    const header = ["started_at", "provider", "model", "status", "total_tokens", "estimated_cost_usd", "latency_ms", "error"];
    const rows = calls.map((c) => [
      c.startedAt,
      displayProviderName(c.providerName),
      c.model ?? "",
      c.success ? "ok" : "error",
      c.totalTokens ?? "",
      c.estimatedCostUsd ?? "",
      c.latencyMs,
      c.errorMessage ?? "",
    ]);
    const csv = [header, ...rows].map((r) => r.map(csvCell).join(",")).join("\r\n");
    const blob = new Blob(["﻿" + csv], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `llm-calls-${statusFilter}-${new Date().toISOString().slice(0, 10)}.csv`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }, [calls, statusFilter]);

  const heading = (
    <div className="mb-1 flex flex-wrap items-center justify-between gap-2">
      <span className="flex items-center gap-2 text-[length:var(--fs-lede)] font-semibold">
        <Cpu size={15} aria-hidden="true" /> {t("heading")}
      </span>
      {!premiumLocked && (
        <div className="flex flex-wrap items-center gap-2">
          {lastUpdated && (
            <span className="font-mono text-[length:var(--fs-label)] text-muted-foreground">
              {t("lastUpdated", { time: lastUpdated.toLocaleTimeString() })}
            </span>
          )}
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="rounded-full"
            style={{ marginBottom: 0 }}
            onClick={() => {
              void refresh(statusFilter, true);
            }}
            disabled={refreshing}
            title={t("refreshTitle")}
          >
            <RefreshCw size={12} className={refreshing ? "qt-spin" : undefined} aria-hidden="true" /> {t("refresh")}
          </Button>
        </div>
      )}
    </div>
  );

  // FREE plan: the backend answers 403 by design. Say so plainly and hand
  // the customer the page's own upgrade path.
  if (premiumLocked) {
    return (
      <Card id="llm-usage" className="mb-4 scroll-mt-24 gap-0 p-6" data-testid="llm-spend-section">
        {heading}
        <div className="mt-2 flex flex-wrap items-start gap-3 rounded-xl border border-border bg-secondary/20 px-4 py-3">
          <Lock size={16} className="mt-0.5 shrink-0 text-muted-foreground" aria-hidden="true" />
          <div className="min-w-0 flex-1">
            <div className="text-sm font-medium text-foreground">{t("premiumLockedTitle")}</div>
            <p className="mt-1 text-sm text-muted-foreground">{t("premiumLockedBody")}</p>
          </div>
          {canUpgrade && (
            <Button type="button" variant="outline" size="sm" style={{ marginBottom: 0 }} onClick={onUpgrade}>
              <ArrowUpRightIcon /> {t("premiumLockedCta")}
            </Button>
          )}
        </div>
      </Card>
    );
  }

  return (
    <Card id="llm-usage" className="mb-4 scroll-mt-24 gap-0 p-6" data-testid="llm-spend-section">
      {heading}
      <p className="mb-4 mt-1 text-[length:var(--fs-meta)] text-muted-foreground">
        {t("sectionHint")}
      </p>

      {error && <div className="ax-kb-error mb-3">{error}</div>}

      {loaded && summary.length === 0 && !error && (
        <p className="text-sm text-muted-foreground" style={{ fontSize: "0.8rem" }}>
          {t("noCallsLoggedYet")}
        </p>
      )}

      {summary.length > 0 && (
        <>
          {/* Headline numbers - all-time from the provider summary, plus
              today's view from telemetry when the core serves it. */}
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4" data-testid="llm-spend-tiles">
            {billing && (
              <>
                <StatTile
                  label={t("tileTotalSpend")}
                  value={fmtUsd(totals.cost, 4)}
                  hint={t("tileTotalSpendHint", { count: summary.length })}
                  testId="llm-spend-tile-total"
                />
                <StatTile
                  label={t("tileSpendToday")}
                  value={telemetry ? fmtUsd(telemetry.costTodayUsd, 4) : "—"}
                  hint={
                    telemetry && num(telemetry.costPerConversationTodayUsd) !== null
                      ? t("tileSpendTodayHint", {
                          perConversation: fmtUsd(telemetry.costPerConversationTodayUsd, 4),
                          conversations: fmtInt(telemetry.conversationsToday),
                        })
                      : t("tileSpendTodayNoData")
                  }
                  testId="llm-spend-tile-today"
                />
              </>
            )}
            <StatTile
              label={t("tileCalls")}
              value={fmtInt(totals.callsCount)}
              hint={t("tileCallsHint", { errors: fmtInt(totals.errors), rate: totals.errorRate.toFixed(1) })}
              testId="llm-spend-tile-calls"
            />
            <StatTile label={t("tileTokens")} value={fmtInt(totals.tokens)} hint={t("tileTokensHint")} testId="llm-spend-tile-tokens" />
          </div>

          {/* Per-provider breakdown - the "outstanding balance of activated
              LLMs" ask, now with each provider's share of total spend. */}
          <div className="mt-5">
            <div className="mb-2 font-mono text-[0.68rem] uppercase tracking-[0.1em] text-muted-foreground">
              {t("providerBreakdownTitle")}
            </div>
            <div className="rounded-xl border border-border">
              <Table>
                <TableHeader>
                  <TableRow className="hover:bg-transparent">
                    <TableHead>{t("columnProvider")}</TableHead>
                    <TableHead className="text-right">{t("columnCalls")}</TableHead>
                    <TableHead className="text-right">{t("columnErrors")}</TableHead>
                    <TableHead className="text-right">{t("columnTokens")}</TableHead>
                    {billing && <TableHead className="text-right">{t("columnEstCost")}</TableHead>}
                    {billing && <TableHead className="min-w-[140px]">{t("columnShare")}</TableHead>}
                    <TableHead>{t("columnLastUsed")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {providersBySpend.map((s) => {
                    const cost = num(s.estimatedCostUsd);
                    const share = cost !== null && totals.cost > 0 ? (cost / totals.cost) * 100 : null;
                    return (
                      <TableRow key={s.providerName} data-testid={`llm-spend-provider-${s.providerName}`}>
                        <TableCell className="font-medium">{displayProviderName(s.providerName)}</TableCell>
                        <TableCell className="text-right font-mono tabular-nums">{fmtInt(s.totalCalls)}</TableCell>
                        <TableCell
                          className="text-right font-mono tabular-nums"
                          style={s.errorCount > 0 ? { color: "var(--state-error)" } : undefined}
                        >
                          {fmtInt(s.errorCount)}
                        </TableCell>
                        <TableCell className="text-right font-mono tabular-nums">{fmtInt(s.totalTokens)}</TableCell>
                        {billing && (
                          <TableCell className="text-right font-mono tabular-nums">{fmtUsd(cost, 4)}</TableCell>
                        )}
                        {billing && (
                          <TableCell>
                            {share === null ? (
                              <span className="text-muted-foreground">—</span>
                            ) : (
                              <div className="flex items-center gap-2">
                                <div
                                  className="h-1.5 flex-1 overflow-hidden rounded-full bg-secondary"
                                  role="progressbar"
                                  aria-valuemin={0}
                                  aria-valuemax={100}
                                  aria-valuenow={Math.round(share)}
                                >
                                  <div className="h-full rounded-full bg-primary" style={{ width: `${Math.max(2, share)}%` }} />
                                </div>
                                <span className="w-10 text-right font-mono text-xs tabular-nums text-muted-foreground">
                                  {share.toFixed(0)}%
                                </span>
                              </div>
                            )}
                          </TableCell>
                        )}
                        <TableCell className="whitespace-nowrap font-mono text-xs text-muted-foreground">
                          {fmtDate(s.lastCallAt)}
                          {s.firstCallAt && (
                            <span className="ml-1">{t("sinceDate", { date: fmtDate(s.firstCallAt) })}</span>
                          )}
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </div>
          </div>

          {/* Model mix (last 24h) - which models are actually answering, so
              a customer can see where a spike came from. */}
          {modelMix.length > 0 && (
            <div className="mt-5" data-testid="llm-spend-model-mix">
              <div className="mb-2 font-mono text-[0.68rem] uppercase tracking-[0.1em] text-muted-foreground">
                {t("modelMixTitle")}
              </div>
              <div className="grid gap-2 sm:grid-cols-2">
                {modelMix.map((m) => (
                  <div key={m.model} className="rounded-lg border border-border px-3 py-2">
                    <div className="flex items-center justify-between gap-2">
                      <span className="min-w-0 truncate font-mono text-xs text-foreground" title={m.model}>
                        {m.model}
                      </span>
                      <span className="shrink-0 font-mono text-xs tabular-nums text-muted-foreground">
                        {t("modelMixCalls", { count: fmtInt(m.calls), percent: Math.round(m.percent) })}
                      </span>
                    </div>
                    <div className="mt-1.5 h-1 overflow-hidden rounded-full bg-secondary">
                      <div className="h-full rounded-full bg-primary/70" style={{ width: `${Math.min(100, Math.max(2, m.percent))}%` }} />
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </>
      )}

      {/* Call log - every call, newest first, with the success/error filter,
          CSV export and the per-row failure diagnosis. */}
      {(calls.length > 0 || summary.length > 0) && (
        <div className="mt-5">
          <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
            <div className="font-mono text-[0.68rem] uppercase tracking-[0.1em] text-muted-foreground">
              {t("callLogTitle", { limit: CALL_LOG_LIMIT })}
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Select
                className="select-inline"
                data-testid="llm-usage-status-filter-select"
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value)}
                options={STATUS_TABS}
              />
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="rounded-full"
                style={{ marginBottom: 0 }}
                onClick={exportCsv}
                disabled={calls.length === 0}
                data-testid="llm-usage-export-csv"
              >
                <Download size={12} aria-hidden="true" /> {t("exportCsv")}
              </Button>
            </div>
          </div>

          <div className="rounded-xl border border-border">
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead>{t("columnTime")}</TableHead>
                  <TableHead>{t("columnProviderModel")}</TableHead>
                  <TableHead>{t("columnStatus")}</TableHead>
                  <TableHead className="text-right">{t("columnTokens")}</TableHead>
                  {billing && <TableHead className="text-right">{t("columnEstCost")}</TableHead>}
                  <TableHead className="text-right">{t("columnLatency")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {calls.length === 0 && (
                  <TableRow className="hover:bg-transparent">
                    <TableCell colSpan={billing ? 6 : 5} className="py-6 text-center text-muted-foreground">
                      {t("noCallsForFilter")}
                    </TableCell>
                  </TableRow>
                )}
                {/* Explicit param type: usePagination is untyped (`items: any`). */}
                {pageItems.map((c: LlmCallLogEntry) => (
                  <Fragment key={c.id}>
                    <TableRow className={!c.success ? "border-b-0" : undefined}>
                      <TableCell className="whitespace-nowrap font-mono text-xs text-muted-foreground">{fmtTime(c.startedAt)}</TableCell>
                      <TableCell>
                        <div className="text-sm">{displayProviderName(c.providerName)}</div>
                        <div className="font-mono text-[length:var(--fs-caption)] text-muted-foreground">{c.model || "—"}</div>
                      </TableCell>
                      <TableCell>
                        <span
                          className="state-pill"
                          style={{ ["--state" as string]: c.success ? "var(--state-success)" : "var(--state-error)" }}
                        >
                          <span className="state-dot" />
                          {c.success ? t("statusOk") : t("statusError")}
                        </span>
                      </TableCell>
                      <TableCell className="text-right font-mono text-xs tabular-nums">{fmtInt(c.totalTokens)}</TableCell>
                      {billing && (
                        <TableCell className="text-right font-mono text-xs tabular-nums">{fmtUsd(c.estimatedCostUsd, 6)}</TableCell>
                      )}
                      <TableCell className="text-right font-mono text-xs tabular-nums">{fmtLatency(c.latencyMs)}</TableCell>
                    </TableRow>
                    {!c.success && c.errorMessage && (
                      <TableRow className="hover:bg-transparent">
                        <TableCell colSpan={billing ? 6 : 5} className="pt-0" style={{ background: "color-mix(in srgb, var(--state-error) 8%, transparent)" }}>
                          <div className="flex flex-wrap items-start justify-between gap-2 text-xs" style={{ color: "var(--state-error)" }}>
                            <span className="min-w-0 flex-1 break-words">{c.errorMessage}</span>
                            <Button
                              type="button"
                              variant="outline"
                              size="sm"
                              className="shrink-0"
                              disabled={diagnosingId === c.id}
                              onClick={() => {
                                void handleDiagnose(c.id);
                              }}
                              data-testid={`llm-call-diagnose-${c.id}`}
                            >
                              {diagnosingId === c.id && <Loader2 size={12} className="animate-spin" aria-hidden="true" />}
                              {diagnosingId === c.id
                                ? t("diagnosing")
                                : diagnoses[c.id] && diagnosisOpen[c.id]
                                  ? t("hideDiagnosis")
                                  : t("diagnose")}
                            </Button>
                          </div>
                          {diagnosisErrors[c.id] && (
                            <div className="mt-1.5 text-xs text-muted-foreground">{t("diagnosisFailed")}</div>
                          )}
                          {diagnoses[c.id] && diagnosisOpen[c.id] && (
                            <div className="mt-2 rounded-lg border border-border bg-secondary/20 px-3 py-2">
                              <div className="flex flex-wrap items-center gap-1.5">
                                <span className="state-pill" style={{ ["--state" as string]: "var(--state-warning)" }}>
                                  <span className="state-dot" />
                                  {diagnoses[c.id].category.replace(/_/g, " ")}
                                </span>
                                {diagnoses[c.id].source === "AI" && (
                                  <span className="font-mono text-[11px] text-muted-foreground">{t("diagnosisAiBadge")}</span>
                                )}
                              </div>
                              <div className="mt-1.5 text-xs font-medium text-foreground">{diagnoses[c.id].title}</div>
                              {diagnoses[c.id].explanation && (
                                <div className="mt-1 text-xs text-muted-foreground">{diagnoses[c.id].explanation}</div>
                              )}
                              {diagnoses[c.id].suggestedFix && (
                                <div className="mt-1 text-xs text-foreground">{diagnoses[c.id].suggestedFix}</div>
                              )}
                              {(diagnoses[c.id].suggestedModels || []).length > 0 && (
                                <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
                                  <span className="text-xs text-muted-foreground">{t("diagnosisSuggestedModels")}:</span>
                                  {diagnoses[c.id].suggestedModels.map((m) => (
                                    <span
                                      key={m}
                                      className="rounded-full border border-border px-2 py-0.5 font-mono text-[11px] text-muted-foreground"
                                    >
                                      {m}
                                    </span>
                                  ))}
                                </div>
                              )}
                            </div>
                          )}
                        </TableCell>
                      </TableRow>
                    )}
                  </Fragment>
                ))}
              </TableBody>
            </Table>
          </div>
          <PaginationControls
            page={page}
            totalPages={totalPages}
            totalItems={totalItems}
            pageSize={pageSize}
            onPageChange={setPage}
            onPageSizeChange={setPageSize}
            itemLabel={t("itemLabelCalls")}
          />
        </div>
      )}

      <p className="mt-4 text-xs text-muted-foreground" style={{ fontSize: "0.72rem" }}>
        <BrandText text={t("estimateDisclaimerLong")} />
      </p>
    </Card>
  );
}

// Tiny local icon wrapper so the locked-state CTA matches the page's other
// Upgrade buttons (which use heroicons' ArrowUpRightIcon at 14px).
function ArrowUpRightIcon() {
  return <ArrowUpRight size={14} aria-hidden="true" />;
}
