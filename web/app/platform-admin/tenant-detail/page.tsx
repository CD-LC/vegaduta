"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { RefreshCw, ShieldOff, ShieldCheck, Building2, BarChart3, LogOut, Eye, Users } from "lucide-react";
import AdminShell from "../components/AdminShell";
import { PageHint, SectionCard, timeAgo } from "../components/sections/common";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Skeleton } from "../components/ui/skeleton";
import { toast } from "../lib/toastBus";
import { tenantDetailApi, type TenantDetail, type TenantRef } from "../lib/adminApi/tenantDetail";
import { opsApi } from "../lib/adminApi/operations";
import { UNAVAILABLE, formatExact, formatRelative, formatUsdOrUnavailable } from "../lib/platformFormat";
import PaginationControls from "../components/PaginationControls";
import { pageForSizeChange } from "../lib/usePagination";
import { fetchPaged } from "../lib/pagedFetch";
import SpendCapCard from "../components/governance/SpendCapCard";
import ViewAsDialog, { type ViewAsTarget } from "../components/governance/ViewAsDialog";
import { useConfirm } from "../components/admin/ConfirmDialog";
import { Select } from "../../components/ui/select";

// Per-tenant control page. Lifecycle note: the request path only enforces
// status == ACTIVE && !emergencyStopped (TenantStatusGuardImpl), and nothing in
// core sets SUSPENDED, so "suspend / reactivate" here IS the emergency stop.

const ACCESS_COPY: Record<string, { label: string; tone: "default" | "destructive" }> = {
  ALLOWED: { label: "Users can sign in and use the app", tone: "default" },
  BLOCKED_STATUS: { label: "Blocked: tenant is not ACTIVE", tone: "destructive" },
  BLOCKED_EMERGENCY_STOP: { label: "Blocked: emergency stop is on", tone: "destructive" },
};

function fmtDate(iso: string | null): string {
  if (!iso) return "-";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "-" : d.toLocaleString();
}

function Stat({ label, value, note }: { label: string; value: any; note?: string }) {
  return (
    <div className="rounded-lg border border-border p-3">
      <p className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">{label}</p>
      <p className="mt-1 text-lg font-semibold">{value}</p>
      {note && <p className="text-[11px] text-muted-foreground">{note}</p>}
    </div>
  );
}

// Users of this tenant (GET /api/platform/users?tenantId=) with the read-only
// "View as" entry point. Platform operators are never offered (core refuses
// them anyway); a suspended user can still be viewed - support often needs to.
function TenantUsersCard({ tenantId, tenantName }: { tenantId: string; tenantName: string }) {
  const [data, setData] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);
  const [target, setTarget] = useState<ViewAsTarget | null>(null);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    setPage(1);
  }, [tenantId]);

  useEffect(() => {
    let cancelled = false;
    setData(null);
    const qs = new URLSearchParams({ tenantId, limit: String(pageSize), offset: String((page - 1) * pageSize) });
    fetchPaged<any>(`/api/platform/users?${qs}`)
      .then((r) => {
        const d = { ...r.body, total: r.total ?? r.body?.total };
        if (!cancelled) {
          setData(d);
          setError(null);
        }
      })
      .catch((err: any) => {
        if (!cancelled) setError(err?.message || "Could not load users.");
      });
    return () => {
      cancelled = true;
    };
  }, [tenantId, page, pageSize, reload]);

  const users: any[] = data?.users || [];
  return (
    <SectionCard
      title="Users"
      icon={Users}
      subtitle={
        data
          ? `${data.total} user${data.total === 1 ? "" : "s"}${data.total > users.length ? `, showing ${users.length}` : ""}. View as opens a read-only, audited 30-minute session.`
          : "View as opens a read-only, audited 30-minute session."
      }
    >
      {error && (
        <p className="text-xs text-muted-foreground">
          Users unavailable: {error}{" "}
          <button type="button" className="underline" onClick={() => setReload((n) => n + 1)}>
            Retry
          </button>
        </p>
      )}
      {!data && !error && <Skeleton className="h-16 w-full" />}
      {data && users.length === 0 && <p className="text-xs text-muted-foreground">No users in this tenant.</p>}
      {users.length > 0 && (
        <div className="divide-y divide-border rounded-lg border border-border">
          {users.map((u) => (
            <div key={u.id} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2 text-sm">
              <span className="min-w-0">
                <span className="font-medium">{u.username}</span>
                <span className="ml-2 text-xs text-muted-foreground">{u.email || UNAVAILABLE}</span>
                {!u.enabled && (
                  <Badge variant="destructive" className="ml-2">
                    Suspended
                  </Badge>
                )}
              </span>
              <Button
                size="sm"
                variant="outline"
                onClick={() => setTarget({ tenantId, userId: u.id, label: u.email || u.username, tenantName })}
                title="Open the tenant app as this user, read-only"
              >
                <Eye /> View as (read-only)
              </Button>
            </div>
          ))}
        </div>
      )}
      {data && (
        <PaginationControls
          page={page}
          pageSize={pageSize}
          totalItems={typeof data.total === "number" ? data.total : undefined}
          hasMore={typeof data.total !== "number" && users.length === pageSize}
          pageItemCount={users.length}
          itemLabel="users"
          onPageChange={setPage}
          onPageSizeChange={(n) => {
            setPage(pageForSizeChange(page, pageSize, n));
            setPageSize(n);
          }}
        />
      )}
      <ViewAsDialog target={target} onClose={() => setTarget(null)} />
    </SectionCard>
  );
}

export default function TenantDetailPage() {
  const askConfirm = useConfirm();
  const [roster, setRoster] = useState<TenantRef[]>([]);
  const [selected, setSelected] = useState<string>("");
  const [detail, setDetail] = useState<TenantDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [filter, setFilter] = useState("");

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const rows = await tenantDetailApi.list();
        if (cancelled) return;
        setRoster(rows);
        const fromUrl = new URLSearchParams(window.location.search).get("id");
        if (fromUrl) setSelected(fromUrl);
      } catch (err: any) {
        if (!cancelled) setError(err?.message || "Could not load tenants.");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const load = useCallback(async (id: string) => {
    if (!id) return;
    setLoading(true);
    try {
      setDetail(await tenantDetailApi.get(id));
      setError(null);
    } catch (err: any) {
      setDetail(null);
      setError(err?.message || "Could not load this tenant.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    setDetail(null);
    if (selected) void load(selected);
  }, [selected, load]);

  async function stop() {
    if (!detail) return;
    const res = await askConfirm({
      title: `Stop ${detail.name}?`,
      description: "Every user in this tenant is blocked until you resume it. No data is deleted.",
      confirmLabel: "Stop tenant",
      destructive: true,
      reason: { label: "Reason for the stop (recorded on the tenant)" },
    });
    if (!res) return;
    const reason = res.reason;
    setBusy(true);
    try {
      await tenantDetailApi.stop(detail.tenantId, reason.trim());
      toast.success(`${detail.name} stopped.`);
      await load(detail.tenantId);
    } catch {
      // toJson already toasted the reason.
    } finally {
      setBusy(false);
    }
  }

  // Ends every Keycloak session of every user in this tenant
  // (POST /tenants/{id}/logout-all). Does not block sign-in - pair it with an
  // emergency stop to keep them out.
  async function logoutAll() {
    if (!detail) return;
    if (
      !(await askConfirm(
        `Log out every user of ${detail.name}? All their sessions end now and each must sign in again. This does not stop them signing back in.`
      ))
    )
      return;
    setBusy(true);
    try {
      const res = await opsApi.logoutTenant(detail.tenantId);
      const n = typeof res?.usersLoggedOut === "number" ? res.usersLoggedOut : null;
      toast.success(
        n == null ? `${detail.name}: sessions ended.` : `${detail.name}: ${n} user${n === 1 ? "" : "s"} signed out.`
      );
    } catch {
      // toJson already toasted the reason.
    } finally {
      setBusy(false);
    }
  }

  async function resume() {
    if (!detail) return;
    setBusy(true);
    try {
      await tenantDetailApi.resume(detail.tenantId);
      toast.success(`${detail.name} resumed.`);
      await load(detail.tenantId);
    } catch {
      // toJson already toasted the reason.
    } finally {
      setBusy(false);
    }
  }

  const shown = roster.filter((t) => !filter.trim() || t.name?.toLowerCase().includes(filter.trim().toLowerCase()));
  const access = detail ? ACCESS_COPY[detail.accessState] || ACCESS_COPY.ALLOWED : null;
  const q = detail?.quota;
  const pct =
    q && q.tokensMonthlyLimit > 0 ? Math.min(100, Math.round((q.tokensUsedMonth / q.tokensMonthlyLimit) * 100)) : null;
  const missing = (key: string) => !!detail?.unavailable.includes(key);

  return (
    <AdminShell title="Tenant detail">
      <PageHint>
        One tenant at a time: status, plan, residency, usage against quota and the emergency-stop switch. Emergency stop
        is the suspend control: it is the only per-tenant switch the request path enforces, and it is fully reversible.
      </PageHint>

      <SectionCard
        title="Pick a tenant"
        icon={Building2}
        subtitle="Includes suspended and failed-provisioning tenants that the main roster hides."
        actions={
          <Button size="sm" variant="outline" disabled={!selected || loading} onClick={() => void load(selected)}>
            <RefreshCw /> Refresh
          </Button>
        }
      >
        <div className="flex flex-wrap gap-2">
          <input
            className="h-9 rounded-md border border-border bg-background px-3 text-sm"
            placeholder="Filter by name"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            aria-label="Filter tenants"
          />
          <Select
            className="min-w-64"
            value={selected}
            onValueChange={setSelected}
            aria-label="Tenant"
            placeholder="Select a tenant..."
            options={[
              { value: "", label: "Select a tenant..." },
              ...shown.map((t) => ({
                value: t.tenantId,
                label: `${t.name} (${t.status}${t.emergencyStopped ? ", stopped" : ""})`,
              })),
            ]}
          />
        </div>
      </SectionCard>

      {error && (
        <Alert variant="destructive" className="mb-5">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {loading && !detail && <Skeleton className="h-48 w-full" />}

      {detail && access && (
        <>
          <SectionCard
            title={detail.name}
            subtitle={
              <>
                schema <code>{detail.schemaName}</code> · id <code>{detail.tenantId}</code>
              </>
            }
          >
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant={detail.status === "ACTIVE" ? "default" : "destructive"}>{detail.status}</Badge>
              <Badge variant="outline">{detail.plan}</Badge>
              {detail.accountType && <Badge variant="outline">{detail.accountType}</Badge>}
              {detail.emergencyStopped && <Badge variant="destructive">Emergency stop</Badge>}
              {detail.pastDue && <Badge variant="destructive">Payment past due</Badge>}
            </div>
            <p className="text-xs">
              <span
                className={access.tone === "destructive" ? "font-semibold text-destructive" : "text-muted-foreground"}
              >
                {access.label}
              </span>
              . The platform-wide kill switch is separate and overrides this.
            </p>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <Stat label="Created" value={fmtDate(detail.createdAt)} />
              <Stat
                label="Data residency"
                value={detail.dataResidencyRegion || "Unspecified"}
                note="Declared only; no data moves"
              />
              <Stat label="Subscription" value={detail.subscriptionStatus || "-"} />
              <Stat
                label="Comp plan expires"
                value={detail.compPlanExpiresAt ? fmtDate(detail.compPlanExpiresAt) : "-"}
              />
              <Stat label="Users" value={missing("users") ? "unavailable" : (detail.userCount ?? "-")} />
              <Stat
                label="Agents"
                value={missing("agents") ? "unavailable" : (detail.agentCount ?? "n/a")}
                note={detail.agentCount == null && !missing("agents") ? "Only counted for ACTIVE tenants" : undefined}
              />
              <Stat
                label="Workflows"
                value={missing("workflows") ? "unavailable" : (detail.workflowCount ?? "n/a")}
                note={
                  detail.workflowCount == null && !missing("workflows") ? "Only counted for ACTIVE tenants" : undefined
                }
              />
              <Stat
                label="Last LLM call"
                value={
                  missing("lastLlmCall")
                    ? "unavailable"
                    : !detail.lastLlmCallKnown
                      ? "n/a"
                      : detail.lastLlmCallAt
                        ? timeAgo(detail.lastLlmCallAt)
                        : "none"
                }
                note="Latest of the last 200 logged calls; not a login time"
              />
              <Stat
                label="Last activity"
                value={
                  <span title={formatExact(detail.lastActivityAt)}>
                    {detail.lastActivityAt ? formatRelative(detail.lastActivityAt) : "unavailable"}
                  </span>
                }
                note={detail.lastActivityAt ? formatExact(detail.lastActivityAt) : undefined}
              />
              <Stat
                label="LLM spend (30d)"
                value={detail.spend30dUsd == null ? "unavailable" : formatUsdOrUnavailable(detail.spend30dUsd)}
                note="All providers incl. BYOK; estimate, not an invoice"
              />
            </div>
            <Link
              href={`/platform-admin/spend?tenantId=${encodeURIComponent(detail.tenantId)}`}
              className="inline-flex items-center gap-1.5 text-xs text-primary underline underline-offset-2 hover:text-foreground"
            >
              <BarChart3 size={13} /> View spend for this tenant
            </Link>
          </SectionCard>

          <SectionCard
            title="Usage vs quota"
            subtitle="Monthly hosted-pool token counter, the same one the chat path enforces against."
          >
            {!q ? (
              <p className="text-xs text-muted-foreground">Quota counter unavailable right now.</p>
            ) : (
              <div className="space-y-2">
                <p className="text-sm">
                  {q.tokensUsedMonth.toLocaleString()} of {q.tokensMonthlyLimit.toLocaleString()} tokens
                  {pct != null ? ` (${pct}%)` : ""}
                </p>
                {pct != null && (
                  <div className="h-2 w-full max-w-md overflow-hidden rounded-full bg-muted">
                    <div
                      className={`h-full ${pct >= 90 ? "bg-destructive" : "bg-primary"}`}
                      style={{ width: `${pct}%` }}
                    />
                  </div>
                )}
                {!q.usesPlatformLlm && detail.plan !== "FREE" && (
                  <p className="text-[11px] text-muted-foreground">
                    This tenant has not opted into the platform LLM pool, so the counter may sit at zero while it uses
                    its own keys.
                  </p>
                )}
              </div>
            )}
          </SectionCard>

          <SpendCapCard tenantId={detail.tenantId} tenantName={detail.name} />

          <SectionCard
            title="Suspend / reactivate"
            subtitle="Emergency stop blocks every user in this tenant at the request filter. Data is untouched."
          >
            {detail.emergencyStopped && (
              <p className="text-xs">
                Stopped {detail.emergencyStoppedAt ? timeAgo(detail.emergencyStoppedAt) : ""}
                {detail.emergencyStopReason ? `: ${detail.emergencyStopReason}` : ""}
              </p>
            )}
            {detail.status !== "ACTIVE" && (
              <p className="text-xs text-muted-foreground">
                Status is {detail.status}; the guard blocks this tenant regardless of the emergency stop, and there is
                no control here to change status.
              </p>
            )}
            {detail.emergencyStopped ? (
              <Button size="sm" disabled={busy} onClick={resume}>
                <ShieldCheck /> {busy ? "Working..." : "Resume tenant"}
              </Button>
            ) : (
              <Button size="sm" variant="destructive" disabled={busy} onClick={stop}>
                <ShieldOff /> {busy ? "Working..." : "Stop tenant"}
              </Button>
            )}
          </SectionCard>

          <SectionCard
            title="Sessions"
            subtitle="Sign every user of this tenant out of every device. They can sign straight back in unless the tenant is also stopped."
          >
            <Button size="sm" variant="outline" disabled={busy} onClick={logoutAll}>
              <LogOut /> {busy ? "Working..." : "Log out all users"}
            </Button>
          </SectionCard>

          <TenantUsersCard tenantId={detail.tenantId} tenantName={detail.name} />
        </>
      )}
    </AdminShell>
  );
}
