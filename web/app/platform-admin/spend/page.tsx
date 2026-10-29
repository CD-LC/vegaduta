"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { BarChart3, Download, RefreshCw } from "lucide-react";
import AdminShell from "../components/AdminShell";
import { PageHint, SectionCard, InfoNote, formatUsd } from "../components/sections/common";
import { LlmUsageSection, HostedCostSection } from "../components/sections/observability";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Skeleton } from "../components/ui/skeleton";
import PaginationControls from "../components/PaginationControls";
import { usePagination } from "../lib/usePagination";
import { toast } from "../lib/toastBus";
import { api } from "../lib/api";
import {
  downloadSpendCsv,
  getSpendByDay,
  getSpendByModel,
  getSpendByProvider,
  getSpendByTenant,
  getSpendSummary,
  type SpendDayRow,
  type SpendGroup,
  type SpendMeta,
  type SpendMetrics,
  type SpendModelRow,
  type SpendProviderRow,
  type SpendSummary,
  type SpendTenantRow,
} from "../lib/adminApi/spend";
import { AdminErrorState, AdminTableWrap } from "../components/admin/AdminKit";
import { Select } from "../../components/ui/select";

// Spend & Reports - platform-wide, from PlatformSpendController.
// TWO different money figures are shown side by side and never blended:
//   "All providers"  = estimated cost of every recorded LLM call, including
//                      tenants' own BYOK keys. Usage, NOT what the platform paid.
//   "Hosted pool"    = PLATFORM_HOSTED only. This is the platform's own outlay
//                      (FREE-tier traffic it pays for).
// Both are estimates from per-call stored figures, not provider invoices.
// The two all-time sections further down (LlmUsageSection, HostedCostSection)
// are the pre-existing live views and are kept as they were - they ignore both
// the date range and the tenant selector, and are labelled as such.
//
// Tenant selector: every report endpoint accepts an optional tenantId. The
// page reads ?tenantId= on load so Tenant detail can deep-link "View spend".

function isoDay(d: Date): string {
  return d.toISOString().slice(0, 10);
}

const n = (v: number | null | undefined) => (v ?? 0).toLocaleString();

function Th({ children, right }: { children: any; right?: boolean }) {
  return <th className={`px-2 py-1.5 font-medium ${right ? "text-end" : "text-start"}`}>{children}</th>;
}
function Td({ children, right }: { children: any; right?: boolean }) {
  return <td className={`px-2 py-1.5 ${right ? "text-end tabular-nums" : ""}`}>{children}</td>;
}

function Table({ head, children }: { head: any; children: any }) {
  return (
    <AdminTableWrap>
      <table className="w-full text-xs">
        <thead className="border-b border-border text-muted-foreground">
          <tr>{head}</tr>
        </thead>
        <tbody className="divide-y divide-border">{children}</tbody>
      </table>
    </AdminTableWrap>
  );
}

function Empty() {
  return <p className="py-3 text-xs text-muted-foreground">No LLM usage recorded in this date range.</p>;
}

function Tile({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="rounded-md border border-border p-3">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="mt-1 text-lg font-semibold tabular-nums">{value}</div>
      {sub && <div className="text-xs text-muted-foreground">{sub}</div>}
    </div>
  );
}

function CoverageNote({ meta }: { meta: SpendMeta | undefined }) {
  if (!meta) return null;
  const problems: string[] = [];
  if (meta.truncated) {
    problems.push(
      `Only the first ${meta.tenantsScanned} of ${meta.tenantsTotal} active tenants were scanned - totals are incomplete.`
    );
  }
  if (meta.tenantsFailed > 0) {
    problems.push(`${meta.tenantsFailed} tenant(s) could not be read and are missing from these totals.`);
  }
  if (problems.length === 0) return null;
  return (
    <Alert variant="destructive">
      <AlertDescription>{problems.join(" ")}</AlertDescription>
    </Alert>
  );
}

export default function SpendReportsPage() {
  const today = useMemo(() => new Date(), []);
  const [from, setFrom] = useState(isoDay(new Date(today.getTime() - 29 * 86400000)));
  const [to, setTo] = useState(isoDay(today));
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [summary, setSummary] = useState<SpendSummary | null>(null);
  const [days, setDays] = useState<SpendDayRow[]>([]);
  const [tenants, setTenants] = useState<SpendTenantRow[]>([]);
  const [models, setModels] = useState<SpendModelRow[]>([]);
  const [modelTotal, setModelTotal] = useState(0);
  const [providers, setProviders] = useState<SpendProviderRow[]>([]);
  const [exporting, setExporting] = useState<SpendGroup | null>(null);
  // null until ?tenantId= has been read on mount (reading window during render
  // would mismatch the prerendered HTML); no report loads before that.
  const [tenantId, setTenantId] = useState<string | null>(null);
  const [tenantOptions, setTenantOptions] = useState<{ tenantId: string; name: string; status?: string }[]>([]);

  useEffect(() => {
    setTenantId(new URLSearchParams(window.location.search).get("tenantId") || "");
  }, []);

  useEffect(() => {
    // Selector options only - a failure leaves "All tenants" (plus a deep-linked id) usable.
    api
      .getPlatformTenants()
      .then((rows: any) => setTenantOptions(Array.isArray(rows) ? rows : []))
      .catch(() => setTenantOptions([]));
  }, []);

  useEffect(() => {
    // Keep the URL shareable without a navigation.
    if (tenantId === null) return;
    const url = new URL(window.location.href);
    if (tenantId) url.searchParams.set("tenantId", tenantId);
    else url.searchParams.delete("tenantId");
    window.history.replaceState(null, "", url.toString());
  }, [tenantId]);

  const selectedTenantName = tenantId ? tenantOptions.find((t) => t.tenantId === tenantId)?.name || tenantId : null;

  const load = useCallback(async () => {
    if (tenantId === null) return;
    setLoading(true);
    setError(null);
    const range = { from, to, tenantId: tenantId || undefined };
    try {
      const [s, d, t, m, p] = await Promise.all([
        getSpendSummary(range),
        getSpendByDay(range),
        getSpendByTenant(range),
        getSpendByModel(range),
        getSpendByProvider(range),
      ]);
      setSummary(s);
      setDays(d.rows);
      setTenants(t.rows);
      setModels(m.rows);
      setModelTotal(m.totalRows);
      setProviders(p.rows);
    } catch (err: any) {
      setSummary(null);
      setError(err?.message || "Could not load spend reports.");
    } finally {
      setLoading(false);
    }
  }, [from, to, tenantId]);

  useEffect(() => {
    void load();
  }, [load]);

  async function exportCsv(group: SpendGroup) {
    setExporting(group);
    try {
      const name = await downloadSpendCsv(group, { from, to, tenantId: tenantId || undefined });
      toast.success(`Downloaded ${name}`);
    } catch {
      // toJson already toasted the reason.
    } finally {
      setExporting(null);
    }
  }

  const tenantPg = usePagination(tenants, 25);
  const dayPg = usePagination(days, 25);
  const modelPg = usePagination(models, 25);

  const all: SpendMetrics | undefined = summary?.allProviders;
  const hosted: SpendMetrics | undefined = summary?.hostedPool;

  const exportButtons = (
    <div className="flex flex-wrap items-center gap-2">
      {(["tenant", "day", "model", "provider"] as SpendGroup[]).map((g) => (
        <Button key={g} variant="outline" size="sm" disabled={exporting !== null} onClick={() => void exportCsv(g)}>
          <Download size={14} /> {exporting === g ? "Exporting..." : `CSV by ${g}`}
        </Button>
      ))}
    </div>
  );

  return (
    <AdminShell title="Spend & Reports">
      <PageHint>
        Platform-wide LLM spend across every active tenant. Two different figures are shown and never blended:
        &ldquo;All providers&rdquo; is the estimated cost of every recorded call including tenants&rsquo; own BYOK keys
        (usage, not what the platform paid); &ldquo;Hosted pool&rdquo; is the platform-paid PLATFORM_HOSTED provider
        only. Both are estimates from stored per-call figures, not provider invoices.
      </PageHint>

      <SectionCard
        title={selectedTenantName ? `Spend report - ${selectedTenantName}` : "Platform spend report"}
        subtitle="UTC days, max 92-day range. Today is a live figure; earlier days come from the daily rollup."
        icon={BarChart3}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <Select
              aria-label="Tenant"
              className="max-w-56"
              value={tenantId ?? ""}
              onValueChange={setTenantId}
              options={[
                { value: "", label: "All tenants" },
                ...(tenantId && !tenantOptions.some((t) => t.tenantId === tenantId)
                  ? [{ value: tenantId, label: tenantId }]
                  : []),
                ...tenantOptions.map((t) => ({
                  value: t.tenantId,
                  label: `${t.name}${t.status && t.status !== "ACTIVE" ? ` (${t.status})` : ""}`,
                })),
              ]}
            />
            <Input
              type="date"
              value={from}
              max={to}
              onChange={(e) => setFrom(e.target.value)}
              className="h-8 w-auto text-xs"
            />
            <span className="text-xs text-muted-foreground">to</span>
            <Input
              type="date"
              value={to}
              min={from}
              onChange={(e) => setTo(e.target.value)}
              className="h-8 w-auto text-xs"
            />
            <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
              <RefreshCw size={14} /> Refresh
            </Button>
          </div>
        }
      >
        {error && <AdminErrorState message={error} onRetry={() => void load()} />}
        <CoverageNote meta={summary?.meta} />
        {loading && !summary ? (
          <Skeleton className="h-20 w-full" />
        ) : (
          all &&
          hosted && (
            <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
              <Tile
                label="All providers - est. cost"
                value={formatUsd(all.estimatedCostUsd)}
                sub="incl. tenants' BYOK keys"
              />
              <Tile
                label="Hosted pool - est. cost"
                value={formatUsd(hosted.estimatedCostUsd)}
                sub="platform-paid (PLATFORM_HOSTED)"
              />
              <Tile label="Calls (all providers)" value={n(all.totalCalls)} sub={`${n(all.errorCount)} errors`} />
              <Tile
                label="Tokens (all providers)"
                value={n(all.totalTokens)}
                sub={all.avgLatencyMs == null ? undefined : `avg latency ${Math.round(all.avgLatencyMs)} ms`}
              />
            </div>
          )
        )}
        <div className="pt-2">{exportButtons}</div>
        <InfoNote>
          CSV files are fetched with your session token and saved from the browser. The by-model view covers all
          providers only (the rollup cannot filter it to the hosted pool). Latency percentiles are not shown because
          they cannot be merged across tenants truthfully.
        </InfoNote>
      </SectionCard>

      <SectionCard title="By tenant" subtitle="Ordered by all-provider estimated cost" delay={0.03}>
        {tenants.length === 0 ? (
          <Empty />
        ) : (
          <>
            <Table
              head={
                <>
                  <Th>Tenant</Th>
                  <Th right>Calls</Th>
                  <Th right>Tokens</Th>
                  <Th right>All providers (est.)</Th>
                  <Th right>Hosted pool (est.)</Th>
                </>
              }
            >
              {tenantPg.pageItems.map((r) => (
                <tr key={r.tenantId}>
                  <Td>{r.tenantName}</Td>
                  <Td right>{n(r.allProviders.totalCalls)}</Td>
                  <Td right>{n(r.allProviders.totalTokens)}</Td>
                  <Td right>{formatUsd(r.allProviders.estimatedCostUsd)}</Td>
                  <Td right>{formatUsd(r.hostedPool.estimatedCostUsd)}</Td>
                </tr>
              ))}
            </Table>
            <PaginationControls
              page={tenantPg.page}
              totalPages={tenantPg.totalPages}
              totalItems={tenantPg.totalItems}
              pageSize={tenantPg.pageSize}
              onPageChange={tenantPg.setPage}
              onPageSizeChange={tenantPg.setPageSize}
              itemLabel="tenants"
            />
          </>
        )}
      </SectionCard>

      <SectionCard title="By day" subtitle="Hosted-pool cost is the platform-paid share of that day" delay={0.06}>
        {days.length === 0 ? (
          <Empty />
        ) : (
          <>
            <Table
              head={
                <>
                  <Th>Date (UTC)</Th>
                  <Th right>Calls</Th>
                  <Th right>Tokens</Th>
                  <Th right>All providers (est.)</Th>
                  <Th right>Hosted calls</Th>
                  <Th right>Hosted pool (est.)</Th>
                </>
              }
            >
              {dayPg.pageItems.map((r) => (
                <tr key={r.date}>
                  <Td>{r.date}</Td>
                  <Td right>{n(r.allProviders.totalCalls)}</Td>
                  <Td right>{n(r.allProviders.totalTokens)}</Td>
                  <Td right>{formatUsd(r.allProviders.estimatedCostUsd)}</Td>
                  <Td right>{n(r.hostedCalls)}</Td>
                  <Td right>{formatUsd(r.hostedCostUsd)}</Td>
                </tr>
              ))}
            </Table>
            <PaginationControls
              page={dayPg.page}
              totalPages={dayPg.totalPages}
              totalItems={dayPg.totalItems}
              pageSize={dayPg.pageSize}
              onPageChange={dayPg.setPage}
              onPageSizeChange={dayPg.setPageSize}
              itemLabel="days"
            />
          </>
        )}
      </SectionCard>

      <SectionCard
        title="By provider"
        subtitle="The PLATFORM_HOSTED row is the platform's own outlay; the rest is tenant BYOK usage"
        delay={0.09}
      >
        {providers.length === 0 ? (
          <Empty />
        ) : (
          <Table
            head={
              <>
                <Th>Provider</Th>
                <Th right>Calls</Th>
                <Th right>Errors</Th>
                <Th right>Tokens</Th>
                <Th right>Est. cost</Th>
              </>
            }
          >
            {providers.map((r) => (
              <tr key={r.provider} className={r.hostedPool ? "font-medium" : ""}>
                <Td>
                  {r.provider}
                  {r.hostedPool ? " (platform-paid)" : ""}
                </Td>
                <Td right>{n(r.metrics.totalCalls)}</Td>
                <Td right>{n(r.metrics.errorCount)}</Td>
                <Td right>{n(r.metrics.totalTokens)}</Td>
                <Td right>{formatUsd(r.metrics.estimatedCostUsd)}</Td>
              </tr>
            ))}
          </Table>
        )}
      </SectionCard>

      <SectionCard
        title="By model (all providers)"
        subtitle={
          modelTotal > models.length
            ? `Top ${models.length} of ${modelTotal} models by estimated cost`
            : "Ordered by estimated cost"
        }
        delay={0.12}
      >
        {models.length === 0 ? (
          <Empty />
        ) : (
          <>
            <Table
              head={
                <>
                  <Th>Model</Th>
                  <Th>Modality</Th>
                  <Th right>Calls</Th>
                  <Th right>Tokens</Th>
                  <Th right>Est. cost</Th>
                </>
              }
            >
              {modelPg.pageItems.map((r) => (
                <tr key={r.model}>
                  <Td>{r.model}</Td>
                  <Td>{r.modality}</Td>
                  <Td right>{n(r.allProviders.totalCalls)}</Td>
                  <Td right>{n(r.allProviders.totalTokens)}</Td>
                  <Td right>{formatUsd(r.allProviders.estimatedCostUsd)}</Td>
                </tr>
              ))}
            </Table>
            <PaginationControls
              page={modelPg.page}
              totalPages={modelPg.totalPages}
              totalItems={modelPg.totalItems}
              pageSize={modelPg.pageSize}
              onPageChange={modelPg.setPage}
              onPageSizeChange={modelPg.setPageSize}
              itemLabel="models"
            />
          </>
        )}
      </SectionCard>

      <PageHint>
        <strong>All-time</strong> live views below - they ignore both the date range and the tenant selector above, and
        always cover every tenant since records began.
      </PageHint>
      <LlmUsageSection pollMs={10000} delay={0} />
      <HostedCostSection pollMs={10000} delay={0.03} />
    </AdminShell>
  );
}
