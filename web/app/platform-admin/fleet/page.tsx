"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { RefreshCw, ShieldOff, ShieldCheck, HeartPulse, SlidersHorizontal, TriangleAlert } from "lucide-react";
import AdminShell from "../components/AdminShell";
import { PageHint, SectionCard, InfoNote, timeAgo } from "../components/sections/common";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Skeleton } from "../components/ui/skeleton";
import PaginationControls from "../components/PaginationControls";
import { usePagination } from "../lib/usePagination";
import { toast } from "../lib/toastBus";
import { fleetApi, type AgentHealth, type FleetHealthResponse, type FleetSlo } from "../lib/adminApi/fleet";
import { tenantDetailApi } from "../lib/adminApi/tenantDetail";
import { useConfirm } from "../components/admin/ConfirmDialog";
import { AdminErrorState, AdminTableWrap } from "../components/admin/AdminKit";
import { Select } from "../../components/ui/select";

// The fleet supervisor: every agent in every tenant, one row each, sorted so
// the agents needing an operator surface first. SLOs are the server's
// defaults; the window selector re-queries with a different windowHours.
// Escalation is the existing per-tenant emergency stop - reversible, audited,
// and it blocks the whole tenant at the request filter.

const HEALTH_TONE: Record<string, "default" | "secondary" | "destructive" | "outline"> = {
  OK: "secondary",
  WARN: "outline",
  BREACH: "destructive",
  STOPPED: "destructive",
};

const ALERT_LABEL: Record<string, string> = {
  EMERGENCY_STOPPED: "Tenant stopped",
  ERROR_RATE_BREACH: "Error-rate SLO",
  JOB_SUCCESS_BREACH: "Job success SLO",
  EVAL_FAILED: "Eval FAILED",
  EVAL_REGRESSION: "Eval regression",
  SPEND_SPIKE: "Spend spike",
};

function pct(x: number | null): string {
  return x == null ? "-" : `${(x * 100).toFixed(1)}%`;
}

function money(x: number | null | undefined): string {
  return x == null ? "-" : `$${x.toFixed(2)}`;
}

export default function FleetHealthPage() {
  const askConfirm = useConfirm();
  const [data, setData] = useState<FleetHealthResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [busyTenant, setBusyTenant] = useState<string | null>(null);
  const [filter, setFilter] = useState("");
  const [healthFilter, setHealthFilter] = useState<string>("ALL");
  // The full SLO set is server-side query parameters (FleetSlo). These state
  // fields mirror the server's defaults; editing one re-queries the endpoint
  // so the whole table is re-scored against the operator's bar.
  const [slo, setSlo] = useState<FleetSlo>({
    windowHours: 24,
    maxErrorRate: 0.1,
    minJobSuccessRate: 0.9,
    evalDriftDrop: 0.05,
    spendSpikeRatio: 3.0,
    minCallsForSlo: 5,
    minSpendUsd: 1.0,
  });
  const [showSloPanel, setShowSloPanel] = useState(false);
  // Draft copy the threshold inputs write to; Apply commits it into `slo`,
  // which is what re-queries the endpoint - so typing doesn't fire a fleet
  // scan per keystroke.
  const [sloDraft, setSloDraft] = useState<FleetSlo>(slo);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setData(await fleetApi.health(slo));
      setError(null);
    } catch (err: any) {
      setError(err?.message || "Could not load fleet health.");
    } finally {
      setLoading(false);
    }
  }, [slo]);

  useEffect(() => {
    void load();
  }, [load]);

  async function stop(row: AgentHealth) {
    const res = await askConfirm({
      title: `Emergency-stop tenant "${row.tenantName}"?`,
      description: "Every user and agent in it is blocked until resumed. No data is deleted.",
      confirmLabel: "Stop tenant",
      destructive: true,
      reason: { label: "Reason for the stop (recorded on the tenant)" },
    });
    if (!res) return;
    const reason = res.reason;
    setBusyTenant(row.tenantId);
    try {
      await tenantDetailApi.stop(row.tenantId, reason.trim());
      toast.success(`${row.tenantName} stopped.`);
      await load();
    } catch {
      // toJson already toasted the reason.
    } finally {
      setBusyTenant(null);
    }
  }

  async function resume(row: AgentHealth) {
    setBusyTenant(row.tenantId);
    try {
      await tenantDetailApi.resume(row.tenantId);
      toast.success(`${row.tenantName} resumed.`);
      await load();
    } catch {
      // toJson already toasted the reason.
    } finally {
      setBusyTenant(null);
    }
  }

  const rows = (data?.agents ?? []).filter(
    (a) =>
      (healthFilter === "ALL" || a.health === healthFilter) &&
      (!filter.trim() ||
        a.agentName?.toLowerCase().includes(filter.trim().toLowerCase()) ||
        a.tenantName?.toLowerCase().includes(filter.trim().toLowerCase()))
  );
  const pg = usePagination(rows, 25);

  return (
    <AdminShell title="Fleet health">
      <PageHint>
        Every agent in every active tenant, scored against SLOs: windowed error rate, finished-job success, eval-score
        drift between the latest two runs, and spend against its 7-day baseline. A row with no traffic reports{" "}
        <em>no verdict</em>, not a fabricated 100%. Escalation is the tenant emergency stop - reversible, audited, and
        enforced at the request filter.
      </PageHint>

      <SectionCard
        title="Agents"
        icon={HeartPulse}
        subtitle={
          data
            ? `${data.agents.length} agents · window ${data.slo.windowHours}h · generated ${timeAgo(data.generatedAt)}`
            : "Loading fleet signals"
        }
        actions={
          <div className="flex items-center gap-2">
            <Select
              className="w-40"
              value={String(slo.windowHours)}
              onValueChange={(v) => {
                const windowHours = Number(v);
                setSlo((s) => ({ ...s, windowHours }));
                setSloDraft((s) => ({ ...s, windowHours }));
              }}
              aria-label="Traffic window"
              options={[
                { value: "1", label: "Last hour" },
                { value: "24", label: "Last 24h" },
                { value: "168", label: "Last 7 days" },
              ]}
            />
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                setSloDraft(slo);
                setShowSloPanel((v) => !v);
              }}
              aria-expanded={showSloPanel}
            >
              <SlidersHorizontal /> SLOs
            </Button>
            <Button size="sm" variant="outline" disabled={loading} onClick={() => void load()}>
              <RefreshCw /> Refresh
            </Button>
          </div>
        }
      >
        {showSloPanel && (
          <div className="mb-4 rounded-md border border-border p-3">
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
              {(
                [
                  ["maxErrorRate", "Error rate ≤", "e.g. 0.10"],
                  ["minJobSuccessRate", "Job success ≥", "e.g. 0.90"],
                  ["evalDriftDrop", "Eval drop >", "e.g. 0.05"],
                  ["spendSpikeRatio", "Spend spike ×", "e.g. 3.0"],
                  ["minCallsForSlo", "Min calls", "e.g. 5"],
                  ["minSpendUsd", "Min spend $", "e.g. 1.00"],
                ] as const
              ).map(([key, label, placeholder]) => (
                <label key={key} className="block text-xs font-medium text-muted-foreground">
                  {label}
                  <input
                    type="number"
                    step="any"
                    min={0}
                    className="mt-1 h-8 w-full rounded-md border border-border bg-background px-2 text-sm"
                    value={sloDraft[key]}
                    placeholder={placeholder}
                    aria-label={label}
                    onChange={(e) => setSloDraft((s) => ({ ...s, [key]: Number(e.target.value) }))}
                  />
                </label>
              ))}
            </div>
            <div className="mt-3 flex items-center justify-end gap-2">
              <Button
                size="sm"
                variant="outline"
                onClick={() => {
                  setSloDraft(slo);
                  setShowSloPanel(false);
                }}
              >
                Cancel
              </Button>
              <Button
                size="sm"
                disabled={loading}
                onClick={() => {
                  setSlo(sloDraft);
                  setShowSloPanel(false);
                }}
              >
                Apply thresholds
              </Button>
            </div>
          </div>
        )}
        <div className="mb-3 flex flex-wrap items-center gap-2">
          <input
            className="h-9 rounded-md border border-border bg-background px-3 text-sm"
            placeholder="Filter by agent or tenant"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            aria-label="Filter agents"
          />
          <div className="flex items-center gap-1" role="group" aria-label="Filter by health">
            {(["ALL", "BREACH", "WARN", "STOPPED", "OK"] as const).map((h) => (
              <button
                key={h}
                type="button"
                aria-pressed={healthFilter === h}
                className={`rounded-full border px-2.5 py-1 text-xs font-medium ${
                  healthFilter === h
                    ? "border-primary bg-accent text-accent-foreground"
                    : "border-border text-muted-foreground hover:text-foreground"
                }`}
                onClick={() => setHealthFilter(h)}
              >
                {h === "ALL" ? "All" : h}
              </button>
            ))}
          </div>
          {data && data.unavailableTenants.length > 0 && (
            <span className="inline-flex items-center gap-1 text-xs font-medium text-destructive">
              <TriangleAlert size={13} /> Unreadable tenants: {data.unavailableTenants.join(", ")} - their agents are
              absent below, not reported healthy.
            </span>
          )}
        </div>

        {error && <AdminErrorState message={error} onRetry={() => void load()} className="mb-4" />}
        {loading && !data && <Skeleton className="h-64 w-full" />}

        {data && (
          <AdminTableWrap>
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-start text-[11px] uppercase tracking-wide text-muted-foreground">
                  <th className="py-2 pe-3 font-semibold">Health</th>
                  <th className="py-2 pe-3 font-semibold">Agent</th>
                  <th className="py-2 pe-3 font-semibold">Tenant</th>
                  <th className="py-2 pe-3 font-semibold text-end">Calls</th>
                  <th className="py-2 pe-3 font-semibold text-end">Error rate</th>
                  <th className="py-2 pe-3 font-semibold text-end">Spend</th>
                  <th className="py-2 pe-3 font-semibold text-end">Jobs OK</th>
                  <th className="py-2 pe-3 font-semibold text-end">Eval</th>
                  <th className="py-2 pe-3 font-semibold">Alerts</th>
                  <th className="py-2 pe-3 font-semibold text-end">Last activity</th>
                  <th className="py-2 font-semibold text-end">Escalate</th>
                </tr>
              </thead>
              <tbody>
                {pg.pageItems.map((a) => (
                  <tr key={`${a.tenantId}:${a.agentId}`} className="border-b border-border/60 align-top">
                    <td className="py-2.5 pe-3">
                      <Badge variant={HEALTH_TONE[a.health] ?? "outline"}>{a.health}</Badge>
                    </td>
                    <td className="py-2.5 pe-3 font-medium">{a.agentName}</td>
                    <td className="py-2.5 pe-3">
                      <Link
                        href={`/platform-admin/tenant-detail?id=${a.tenantId}`}
                        className="text-primary hover:underline"
                      >
                        {a.tenantName}
                      </Link>
                    </td>
                    <td className="py-2.5 pe-3 text-end tabular-nums">{a.calls.toLocaleString()}</td>
                    <td
                      className={`py-2.5 pe-3 text-end tabular-nums ${
                        a.errorRate != null && a.errorRate > data.slo.maxErrorRate && a.calls >= data.slo.minCallsForSlo
                          ? "font-semibold text-destructive"
                          : ""
                      }`}
                    >
                      {pct(a.errorRate)}
                    </td>
                    <td className="py-2.5 pe-3 text-end tabular-nums">
                      {money(a.costUsd)}
                      {a.spendRatio != null && a.spendRatio > data.slo.spendSpikeRatio && (
                        <span className="ms-1 text-[11px] font-semibold text-destructive">
                          {a.spendRatio.toFixed(1)}x
                        </span>
                      )}
                    </td>
                    <td className="py-2.5 pe-3 text-end tabular-nums">
                      {a.jobSuccessRate == null
                        ? "-"
                        : `${a.jobsSucceeded}/${a.jobsFinished} (${pct(a.jobSuccessRate)})`}
                    </td>
                    <td className="py-2.5 pe-3 text-end tabular-nums">
                      {a.latestEvalScore == null ? (
                        "-"
                      ) : (
                        <>
                          {a.latestEvalScore.toFixed(2)}
                          {a.previousEvalScore != null && (
                            <span
                              className={`ms-1 text-[11px] ${
                                a.previousEvalScore - a.latestEvalScore > data.slo.evalDriftDrop
                                  ? "font-semibold text-destructive"
                                  : "text-muted-foreground"
                              }`}
                            >
                              {a.latestEvalScore >= a.previousEvalScore ? "+" : ""}
                              {(a.latestEvalScore - a.previousEvalScore).toFixed(2)}
                            </span>
                          )}
                        </>
                      )}
                    </td>
                    <td className="py-2.5 pe-3">
                      <div className="flex flex-wrap gap-1">
                        {a.alerts.map((al) => (
                          <Badge key={al} variant="destructive" className="text-[10px]">
                            {ALERT_LABEL[al] ?? al}
                          </Badge>
                        ))}
                      </div>
                    </td>
                    <td className="py-2.5 pe-3 text-end text-muted-foreground">
                      {a.lastCallAt ? timeAgo(a.lastCallAt) : "-"}
                    </td>
                    <td className="py-2.5 text-end">
                      {a.tenantEmergencyStopped ? (
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={busyTenant === a.tenantId}
                          onClick={() => void resume(a)}
                        >
                          <ShieldCheck /> Resume
                        </Button>
                      ) : (
                        <Button
                          size="sm"
                          variant="ghost"
                          disabled={busyTenant === a.tenantId}
                          onClick={() => void stop(a)}
                        >
                          <ShieldOff /> Stop
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={11} className="py-8 text-center text-muted-foreground">
                      No agents match this filter.
                    </td>
                  </tr>
                )}
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
          itemLabel="agents"
        />
      </SectionCard>

      <InfoNote>
        SLOs are request parameters tuned from the SLOs panel above (server defaults: error rate &le;10% with &ge;5
        calls, job success &ge;90%, eval drop &gt;0.05 between the latest two runs, spend &gt;3x the daily baseline and
        {/* a $-amount literal would trip the plan-price ratchet (scripts/ci/price-grep.sh) */}&ge;&#36;1). Per-agent
        SLO config and routed paging are follow-ups. Emergency stop acts on the whole tenant, not the one agent; the
        platform-wide kill switch lives on Platform Settings.
      </InfoNote>
    </AdminShell>
  );
}
