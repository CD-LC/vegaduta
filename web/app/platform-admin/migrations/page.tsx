"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Database, RefreshCw, Search } from "lucide-react";
import AdminShell from "../components/AdminShell";
import { PageHint, SectionCard, InfoNote } from "../components/sections/common";
import { ToneChip } from "../components/ops/OpsWidgets";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Skeleton } from "../components/ui/skeleton";
import { Card, CardContent } from "../components/ui/card";
import PaginationControls from "../components/PaginationControls";
import { usePagination } from "../lib/usePagination";
import { opsApi, type MigrationsResponse } from "../lib/adminApi/operations";
import { UNAVAILABLE, formatCountOrUnavailable } from "../lib/platformFormat";
import {
  migrationDrift,
  migrationStateMeta,
  sortTenantMigrations,
  tenantMigrationState,
  type MigrationState,
} from "../lib/opsFormat";
import { AdminErrorState, AdminEmptyState } from "../components/admin/AdminKit";
import { Checkbox } from "../../components/ui/checkbox";
import { useTranslations } from "../../lib/i18n";

// Flyway state for the control-plane schema and every tenant schema
// (GET /api/platform/migrations). Read-only: a tenant behind or failed is
// fixed by a deploy / a repair run, never from a button here. Control-plane
// and tenant Flyway versions share one namespace (CLAUDE.md RULE 0), so the
// "expected tenant version" is the highest tenant migration the running core
// image ships.

export default function MigrationsPage() {
    const tHC = useTranslations("hcPlatformadminMigrationsPage");
  const [data, setData] = useState<MigrationsResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [q, setQ] = useState("");
  const [onlyProblems, setOnlyProblems] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setData(await opsApi.migrations());
      setError(null);
    } catch (e: any) {
      setError(e?.message || "Could not load migration state.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const drift = migrationDrift(data);
  const rows = useMemo(() => {
    if (!data) return [];
    const needle = q.trim().toLowerCase();
    return sortTenantMigrations(data).filter((t) => {
      const state: MigrationState = tenantMigrationState(t, data.expectedTenantVersion);
      if (onlyProblems && state === "ok") return false;
      if (!needle) return true;
      return [t.name, t.schema, t.tenantId].some((f) => (f || "").toLowerCase().includes(needle));
    });
  }, [data, q, onlyProblems]);

  const pg = usePagination(rows, 25);
  const cp = data?.controlPlane;

  return (
    <AdminShell
      title={tHC("s12")}
      actions={
        <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
          <RefreshCw className={loading ? "animate-spin" : undefined} /> Refresh
        </Button>
      }
    >
      <PageHint>
        {tHC("s11")}
      </PageHint>

      {error && <AdminErrorState className="mb-4" message={error} onRetry={() => void load()} />}
      {loading && !data && <Skeleton className="mb-5 h-24 w-full" />}

      {data && drift && (
        <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Card className="gap-0 py-0">
            <CardContent className="p-4">
              <div className="text-xs text-muted-foreground">{tHC("s10")}</div>
              <div className="mt-1 font-mono text-xl font-semibold">{cp?.version || UNAVAILABLE}</div>
              <div className="mt-1">
                {cp && cp.failed > 0 ? (
                  <ToneChip tone="bad" label={`${cp.failed} failed`} />
                ) : cp && cp.pending > 0 ? (
                  <ToneChip tone="warn" label={`${cp.pending} pending`} />
                ) : (
                  <ToneChip tone="ok" label="Current" />
                )}
              </div>
            </CardContent>
          </Card>
          <Card className="gap-0 py-0">
            <CardContent className="p-4">
              <div className="text-xs text-muted-foreground">{tHC("s9")}</div>
              <div className="mt-1 font-mono text-xl font-semibold">{data.expectedTenantVersion || UNAVAILABLE}</div>
              <div className="mt-1 text-[11px] text-muted-foreground">
                {formatCountOrUnavailable(data.tenants.length)} {tHC("s8")}
              </div>
            </CardContent>
          </Card>
          <Card className={`gap-0 py-0 ${drift.failed ? "border-destructive/40" : ""}`}>
            <CardContent className="p-4">
              <div className="text-xs text-muted-foreground">{tHC("s7")}</div>
              <div className={`mt-1 font-mono text-xl font-semibold ${drift.failed ? "text-destructive" : ""}`}>
                {drift.failed}
              </div>
            </CardContent>
          </Card>
          <Card className={`gap-0 py-0 ${drift.behind ? "border-[var(--warning-border)]" : ""}`}>
            <CardContent className="p-4">
              <div className="text-xs text-muted-foreground">{tHC("s6")}</div>
              <div
                className={`mt-1 font-mono text-xl font-semibold ${drift.behind ? "text-[var(--warning-text)]" : ""}`}
              >
                {drift.behind}
              </div>
              {drift.unknown > 0 && (
                <div className="mt-0.5 text-[11px] text-muted-foreground">{drift.unknown} unreadable</div>
              )}
            </CardContent>
          </Card>
        </div>
      )}

      {data && (
        <SectionCard
          title={tHC("s5")}
          icon={Database}
          subtitle={`${rows.length} shown${onlyProblems ? " (problems only)" : ""}`}
          actions={
            <div className="flex flex-wrap items-center gap-2">
              <div className="relative">
                <Search
                  size={14}
                  className="pointer-events-none absolute start-2.5 top-1/2 -translate-y-1/2 text-muted-foreground"
                />
                <Input
                  aria-label={tHC("s4")}
                  className="h-9 w-56 ps-8"
                  placeholder={tHC("s3")}
                  value={q}
                  onChange={(e) => setQ(e.target.value)}
                />
              </div>
              <div className="flex items-center gap-1.5 text-xs">
                <Checkbox
                  checked={onlyProblems}
                  onCheckedChange={(v) => setOnlyProblems(v)}
                  id="migrations-problems-only"
                />
                <label htmlFor="migrations-problems-only">{tHC("s2")}</label>
              </div>
            </div>
          }
        >
          {rows.length === 0 ? (
            <AdminEmptyState
              title={onlyProblems ? "No problems found" : "No tenants match"}
              description={onlyProblems ? "Every tenant schema is at the expected version." : "Try a different search."}
            />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[640px] text-start text-sm">
                <thead className="text-xs text-muted-foreground">
                  <tr className="border-b border-border">
                    <th className="py-2 pe-3 font-medium">Tenant</th>
                    <th className="py-2 pe-3 font-medium">Schema</th>
                    <th className="py-2 pe-3 font-medium">Version</th>
                    <th className="py-2 pe-3 font-medium">Pending</th>
                    <th className="py-2 pe-3 font-medium">Failed</th>
                    <th className="py-2 font-medium">State</th>
                  </tr>
                </thead>
                <tbody>
                  {pg.pageItems.map((t) => {
                    const state = tenantMigrationState(t, data.expectedTenantVersion);
                    const meta = migrationStateMeta(state);
                    return (
                      <tr
                        key={t.tenantId}
                        className={`border-b border-border/60 ${state === "failed" ? "bg-destructive/5" : state === "behind" ? "bg-[var(--warning-bg)]" : ""}`}
                        data-testid="migration-row"
                        data-state={state}
                      >
                        <td className="py-2 pe-3 font-medium">{t.name || t.tenantId}</td>
                        <td className="py-2 pe-3 font-mono text-xs">{t.schema}</td>
                        <td className="py-2 pe-3 font-mono text-xs">{t.version || UNAVAILABLE}</td>
                        <td className="py-2 pe-3 font-mono text-xs">{formatCountOrUnavailable(t.pending)}</td>
                        <td className="py-2 pe-3 font-mono text-xs">{formatCountOrUnavailable(t.failed)}</td>
                        <td className="py-2">
                          <ToneChip tone={meta.tone} label={meta.label} title={t.status} />
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
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
      )}

      <InfoNote>
        {tHC("s1")}
      </InfoNote>
    </AdminShell>
  );
}
