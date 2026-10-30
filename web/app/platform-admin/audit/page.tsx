"use client";

import { useState } from "react";
import { ShieldAlert, ChevronRight, Search, Download } from "lucide-react";
import Link from "next/link";
import AdminShell from "../components/AdminShell";
import { PageHint, SectionCard } from "../components/sections/common";
import { ActivitySection } from "../components/sections/observability";
import { KillSwitchSection } from "../components/sections/platform-controls";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Select } from "../../components/ui/select";
import { Alert, AlertDescription } from "../components/ui/alert";
import PaginationControls from "../components/PaginationControls";
import { usePagination } from "../lib/usePagination";
import { searchAudit, auditRowsToCsv, type AuditSearchResult } from "../lib/adminApi/audit";
import { AdminEmptyState, AdminErrorState, AdminFilterBar, AdminTableWrap } from "../components/admin/AdminKit";
import { useTranslations } from "../../lib/i18n";

// Audit & Compliance §3.8 - cross-tenant audit search (PlatformAuditSearchController:
// fans out over every active tenant's audit_log_entry, bounded and honest about
// truncation), the merged live activity feed, the platform-wide kill switch
// (TenantEmergencyControlService), and data residency (per-tenant, on the tenants page).

const OUTCOMES = ["SUCCESS", "FAILURE", "PENDING_APPROVAL", "AUTO_APPROVED", "APPROVED", "REJECTED"];

// <input type="datetime-local"> yields local time without a zone; the API wants an instant.
function toInstant(local: string): string | undefined {
  if (!local) return undefined;
  const d = new Date(local);
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
}

function AuditSearchSection() {
  const tHC = useTranslations("hcPlatformadminAuditPage");
  const [f, setF] = useState({
    tenantId: "",
    actorId: "",
    actionType: "",
    targetType: "",
    outcome: "",
    from: "",
    to: "",
  });
  const [limit, setLimit] = useState(200);
  const [result, setResult] = useState<AuditSearchResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const pg = usePagination(result?.rows ?? [], 25);

  const set = (k: keyof typeof f) => (e: any) => setF((prev) => ({ ...prev, [k]: e.target.value }));

  async function run(e?: React.FormEvent) {
    e?.preventDefault();
    setLoading(true);
    setError(null);
    try {
      setResult(await searchAudit({ ...f, from: toInstant(f.from), to: toInstant(f.to), limit }));
    } catch (err: any) {
      setResult(null);
      setError(err?.message || "Search failed");
    } finally {
      setLoading(false);
    }
  }

  function download() {
    if (!result) return;
    const blob = new Blob([auditRowsToCsv(result.rows)], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `audit-search-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, "-")}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  return (
    <SectionCard
      title={tHC("s20")}
      icon={Search}
      subtitle="Searches every active tenant's audit log. Platform-operator actions (platform.user.*) are recorded in the target tenant's log, so they appear here. Use a trailing * on the action type for a prefix match."
      delay={0.02}
    >
      <form onSubmit={run}>
        <AdminFilterBar label={tHC("s19")}>
          <Input
            aria-label={tHC("s18")}
            className="h-9 w-40"
            placeholder={tHC("s18")}
            value={f.tenantId}
            onChange={set("tenantId")}
          />
          <Input
            aria-label={tHC("s17")}
            className="h-9 w-48"
            placeholder={tHC("s17")}
            value={f.actorId}
            onChange={set("actorId")}
          />
          <Input
            aria-label={tHC("s16")}
            className="h-9 w-52"
            placeholder={tHC("s15")}
            value={f.actionType}
            onChange={set("actionType")}
          />
          <Input
            aria-label={tHC("s14")}
            className="h-9 w-40"
            placeholder={tHC("s14")}
            value={f.targetType}
            onChange={set("targetType")}
          />
          <Select
            aria-label="Outcome"
            className="w-48"
            value={f.outcome}
            onValueChange={(v) => setF((prev) => ({ ...prev, outcome: v }))}
            options={[{ value: "", label: "Any outcome" }, ...OUTCOMES.map((o) => ({ value: o, label: o }))]}
          />
          <label className="text-xs text-muted-foreground">
            From
            <Input type="datetime-local" aria-label="From" className="h-9 w-48" value={f.from} onChange={set("from")} />
          </label>
          <label className="text-xs text-muted-foreground">
            To
            <Input type="datetime-local" aria-label="To" className="h-9 w-48" value={f.to} onChange={set("to")} />
          </label>
          <Select
            aria-label={tHC("s13")}
            className="w-40"
            value={String(limit)}
            onValueChange={(v) => setLimit(Number(v))}
            options={[100, 200, 500, 1000].map((n) => ({ value: String(n), label: `Max ${n} rows` }))}
          />
          <Button type="submit" size="sm" disabled={loading}>
            <Search /> {loading ? "Searching..." : "Search"}
          </Button>
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={!result || result.rows.length === 0}
            onClick={download}
          >
            <Download /> CSV
          </Button>
        </AdminFilterBar>
      </form>

      {error && <AdminErrorState message={error} onRetry={() => void run()} />}

      {result && (
        <>
          <p className="text-xs text-muted-foreground">
            {result.rows.length} row{result.rows.length === 1 ? "" : "s"} from {result.tenantsScanned} of{" "}
            {result.tenantsInScope} tenant{result.tenantsInScope === 1 ? "" : "s"} (caps: {result.rowLimit} rows,{" "}
            {result.maxTenants} {tHC("s12")}
            {result.tenantsFailed > 0 && ` ${result.tenantsFailed} tenant(s) failed and were skipped.`}
          </p>
          {result.truncated && (
            <Alert>
              <AlertDescription>{tHC("s11")}</AlertDescription>
            </Alert>
          )}
          {result.rows.length === 0 ? (
            <AdminEmptyState title={tHC("s10")} description="Widen the date range or clear a filter." />
          ) : (
            <AdminTableWrap>
              <table className="w-full min-w-[56rem] text-start text-xs">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-1 pe-3">Time</th>
                    <th className="pe-3">Tenant</th>
                    <th className="pe-3">Actor</th>
                    <th className="pe-3">Action</th>
                    <th className="pe-3">Target</th>
                    <th className="pe-3">Outcome</th>
                    <th>Detail</th>
                  </tr>
                </thead>
                <tbody>
                  {pg.pageItems.map((r) => (
                    <tr key={`${r.tenantId}:${r.id}`} className="border-t border-border align-top">
                      <td className="whitespace-nowrap py-1 pe-3">
                        {r.createdAt ? new Date(r.createdAt).toLocaleString() : ""}
                      </td>
                      <td className="pe-3">
                        <code>{r.tenantId}</code>
                      </td>
                      <td className="max-w-[10rem] truncate pe-3" title={r.actorId || ""}>
                        {r.actorId || "-"}
                      </td>
                      <td className="pe-3">
                        <code>{r.actionType}</code>
                      </td>
                      <td className="pe-3">
                        {r.targetType || "-"}
                        {r.targetId ? ` / ${r.targetId}` : ""}
                      </td>
                      <td className="pe-3">
                        <Badge
                          variant={r.outcome === "FAILURE" || r.outcome === "REJECTED" ? "destructive" : "outline"}
                        >
                          {r.outcome || "-"}
                        </Badge>
                      </td>
                      <td className="max-w-md break-words text-muted-foreground">{r.detail || ""}</td>
                    </tr>
                  ))}
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
            itemLabel="audit rows"
            capped={result.truncated ? result.rows.length : undefined}
          />
        </>
      )}
    </SectionCard>
  );
}

export default function AuditCompliancePage() {
  const tHC = useTranslations("hcPlatformadminAuditPage");
  return (
    <AdminShell title={tHC("s9")}>
      <PageHint>{tHC("s8")}</PageHint>

      <KillSwitchSection pollMs={15000} delay={0} />

      <AuditSearchSection />

      <ActivitySection pollMs={10000} delay={0.03} />

      <SectionCard title={tHC("s7")} icon={ShieldAlert} delay={0.06}>
        <p className="text-sm text-muted-foreground">{tHC("s6")}</p>
        <Link
          href="/platform-admin/tenants"
          className="inline-flex items-center gap-1.5 text-sm font-medium text-primary hover:underline"
        >
          {tHC("s5")} <ChevronRight size={13} />
        </Link>
      </SectionCard>

      <SectionCard title={tHC("s4")} icon={ShieldAlert} delay={0.09}>
        <p className="text-sm text-muted-foreground">
          {tHC("s3")}
          <code>{tHC("s2")}</code>
          {tHC("s1")}
        </p>
      </SectionCard>
    </AdminShell>
  );
}
