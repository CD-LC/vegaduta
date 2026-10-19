"use client";

import { useCallback, useEffect, useState } from "react";
import { CreditCard, Gift, Receipt, RefreshCw, Search } from "lucide-react";
import AdminShell from "../components/AdminShell";
import { InfoNote, PageHint, SectionCard, timeUntil } from "../components/sections/common";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Skeleton } from "../components/ui/skeleton";
import PaginationControls from "../components/PaginationControls";
import { usePagination } from "../lib/usePagination";
import {
  billingApi,
  formatMinor,
  type BillingState,
  type BillingSummary,
  type CompGrantRow,
  type InvoiceRow,
  type SubscriptionList,
} from "../lib/adminApi/billing";
import { AdminEmptyState, AdminErrorState, TONE_TEXT } from "../components/admin/AdminKit";
import { Select } from "../../components/ui/select";

// Platform billing & subscriptions - READ ONLY (PlatformBillingController,
// /api/platform/billing/**). Plan overrides and the site-wide promo live on
// their own pages; nothing here can charge, refund, cancel or change a plan.

const PLANS = ["FREE", "PREMIUM", "UNLIMITED"];
const STATES: BillingState[] = ["PAID", "COMP", "PAST_DUE", "CANCELING", "ADMIN_OVERRIDE", "FREE"];

const STATE_LABEL: Record<BillingState, string> = {
  FREE: "Free",
  PAID: "Paid",
  COMP: "Comp grant",
  PAST_DUE: "Past due",
  CANCELING: "Canceling",
  ADMIN_OVERRIDE: "Admin override",
};

function stateVariant(s: BillingState): "default" | "secondary" | "outline" | "accent" | "destructive" {
  if (s === "PAST_DUE") return "destructive";
  if (s === "PAID") return "default";
  if (s === "COMP" || s === "CANCELING") return "accent";
  return "outline";
}

function date(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" }) : "-";
}

function Stat({ label, value, tone }: { label: string; value: number | string; tone?: "warn" }) {
  return (
    <div className="rounded-lg border border-border p-3">
      <p className="text-[11px] uppercase tracking-wide text-muted-foreground">{label}</p>
      <p className={`mt-1 text-xl font-semibold ${tone === "warn" ? TONE_TEXT.warn : ""}`}>{value}</p>
    </div>
  );
}

function ErrorAlert({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return <AdminErrorState message={message} onRetry={onRetry} />;
}

function Pager({ pg, label, capped }: { pg: ReturnType<typeof usePagination<any>>; label: string; capped?: number }) {
  return (
    <PaginationControls
      page={pg.page}
      totalPages={pg.totalPages}
      totalItems={pg.totalItems}
      pageSize={pg.pageSize}
      onPageChange={pg.setPage}
      onPageSizeChange={pg.setPageSize}
      itemLabel={label}
      capped={capped}
    />
  );
}

function SummaryPanel() {
  const [data, setData] = useState<BillingSummary | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setData(await billingApi.summary());
      setError(null);
    } catch (err: any) {
      setError(err?.message || "Could not load the billing summary.");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <SectionCard
      title="Overview"
      subtitle="Live tenants only (demo sandboxes excluded)"
      icon={CreditCard}
      actions={
        <Button type="button" size="icon" variant="outline" aria-label="Refresh overview" onClick={() => void load()}>
          <RefreshCw />
        </Button>
      }
    >
      {error && <ErrorAlert message={error} onRetry={() => void load()} />}
      {!data && !error && <Skeleton className="h-24 w-full" />}
      {data && (
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <Stat label="Live tenants" value={data.liveTenants} />
            {Object.entries(data.tenantsByPlan).map(([plan, n]) => (
              <Stat key={plan} label={`${plan} plan`} value={n} />
            ))}
            <Stat label="Active comp grants" value={data.activeCompGrants} />
            <Stat
              label="Comp expiring in 7d"
              value={data.compGrantsExpiringWithin7d}
              tone={data.compGrantsExpiringWithin7d > 0 ? "warn" : undefined}
            />
            <Stat label="Past due" value={data.pastDue} tone={data.pastDue > 0 ? "warn" : undefined} />
            <Stat label="Cancel scheduled" value={data.cancelScheduled} />
            <Stat label="Upgrade pending" value={data.pendingUpgrade} />
          </div>
          {data.compGrantsOverdueForRevert > 0 && (
            <p className={`text-xs ${TONE_TEXT.warn}`}>
              {data.compGrantsOverdueForRevert} comp grant(s) are past expiry and waiting for the hourly reverter.
            </p>
          )}
          <div className="space-y-1">
            <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              Captured payments (invoiced)
            </p>
            {data.invoiceTotals.length === 0 ? (
              <p className="text-xs text-muted-foreground">No invoices recorded.</p>
            ) : (
              data.invoiceTotals.map((c) => (
                <p key={c.currency} className="text-sm">
                  <span className="font-medium">{c.currency}</span>: {formatMinor(c.amountMinor, c.currency)} across{" "}
                  {c.invoices} invoice
                  {c.invoices === 1 ? "" : "s"} (GST {formatMinor(c.gstMinor, c.currency)}) · last 30 days{" "}
                  {formatMinor(c.amountMinorLast30d, c.currency)} / {c.invoicesLast30d}
                </p>
              ))
            )}
            <p className="text-[11px] text-muted-foreground">Currencies are never added together.</p>
          </div>
          <details className="text-xs text-muted-foreground">
            <summary className="cursor-pointer">Gateway subscription status counts</summary>
            <div className="mt-1 flex flex-wrap gap-1.5">
              {Object.entries(data.tenantsBySubscriptionStatus).map(([s, n]) => (
                <Badge key={s} variant="outline">
                  {s}: {n}
                </Badge>
              ))}
            </div>
          </details>
        </div>
      )}
    </SectionCard>
  );
}

function SubscriptionsPanel() {
  const [q, setQ] = useState("");
  const [plan, setPlan] = useState("");
  const [state, setState] = useState("");
  const [applied, setApplied] = useState({ q: "", plan: "", state: "" });
  const [data, setData] = useState<SubscriptionList | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setData(await billingApi.subscriptions({ ...applied, limit: 500 }));
      setError(null);
    } catch (err: any) {
      setError(err?.message || "Could not load subscriptions.");
    } finally {
      setLoading(false);
    }
  }, [applied]);

  useEffect(() => {
    void load();
  }, [load]);

  const rows = data?.subscriptions ?? [];
  const pg = usePagination(rows, 25);

  return (
    <SectionCard
      title="Subscriptions"
      subtitle={
        data
          ? `${data.total} tenant${data.total === 1 ? "" : "s"}${data.total > rows.length ? `, showing ${rows.length}` : ""}`
          : undefined
      }
      icon={CreditCard}
      delay={0.03}
      actions={
        <form
          onSubmit={(e) => {
            e.preventDefault();
            setApplied({ q: q.trim(), plan, state });
          }}
          className="flex flex-wrap items-center gap-2"
        >
          <Input
            aria-label="Search tenants"
            className="h-9 w-48"
            placeholder="Tenant name"
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
          <Select
            aria-label="Plan filter"
            className="w-40"
            value={plan}
            onValueChange={setPlan}
            options={[{ value: "", label: "All plans" }, ...PLANS.map((p) => ({ value: p, label: p }))]}
          />
          <Select
            aria-label="State filter"
            className="w-44"
            value={state}
            onValueChange={setState}
            options={[{ value: "", label: "All states" }, ...STATES.map((s) => ({ value: s, label: STATE_LABEL[s] }))]}
          />
          <Button type="submit" size="sm">
            <Search /> Filter
          </Button>
        </form>
      }
    >
      {loading && !data && <Skeleton className="h-24 w-full" />}
      {error && <ErrorAlert message={error} onRetry={() => void load()} />}
      {data?.scanTruncated && (
        <InfoNote>The tenant scan hit its 5,000 row cap; narrow the filters to see the rest.</InfoNote>
      )}
      {!loading && !error && rows.length === 0 && (
        <AdminEmptyState title="No tenants match" description="Adjust the filters to see more tenants." />
      )}
      {pg.pageItems.map((r) => (
        <div
          key={r.tenantId}
          className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border p-3 text-sm"
        >
          <span className="min-w-0">
            <span className="font-medium">{r.name}</span>
            <span className="ml-2 text-xs text-muted-foreground">
              <code>{r.tenantId.slice(0, 8)}</code>
            </span>
            {r.state === "COMP" && (
              <span className="block text-xs text-muted-foreground">
                Comp until {date(r.compExpiresAt)} ({timeUntil(r.compExpiresAt)})
                {r.planBeforeComp ? `, reverts to ${r.planBeforeComp}` : ", reverts to FREE"}
                {r.compReason ? ` - ${r.compReason}` : ""}
              </span>
            )}
            {r.pastDueSince && (
              <span className="block text-xs text-destructive">Payment failing since {date(r.pastDueSince)}</span>
            )}
            {r.pendingPlan && (
              <span className="block text-xs text-muted-foreground">Upgrade to {r.pendingPlan} awaiting checkout</span>
            )}
          </span>
          <span className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
            <Badge variant="outline">{r.plan}</Badge>
            <Badge variant={stateVariant(r.state)}>{STATE_LABEL[r.state]}</Badge>
            {r.subscriptionStatus && <span>gateway: {r.subscriptionStatus}</span>}
            {r.trialEndsAt && <span>trial ends {date(r.trialEndsAt)}</span>}
            {r.currentPeriodEnd && <span>renews/ends {date(r.currentPeriodEnd)}</span>}
            {r.cancelScheduled && <span>cancels at cycle end</span>}
          </span>
        </div>
      ))}
      <Pager pg={pg} label="tenants" capped={data && data.total > rows.length ? rows.length : undefined} />
    </SectionCard>
  );
}

function CompGrantsPanel() {
  const [grants, setGrants] = useState<CompGrantRow[] | null>(null);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setError(null);
    billingApi
      .compGrants()
      .then((d) => {
        setGrants(d.grants);
        setTotal(d.total ?? d.grants.length);
      })
      .catch((err: any) => setError(err?.message || "Could not load comp grants."));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const pg = usePagination(grants ?? [], 10);

  return (
    <SectionCard title="Comp grants" subtitle="Soonest expiry first" icon={Gift} delay={0.06}>
      {error && <ErrorAlert message={error} onRetry={load} />}
      {!grants && !error && <Skeleton className="h-16 w-full" />}
      {grants && grants.length === 0 && <p className="text-sm text-muted-foreground">No comp grants on record.</p>}
      {pg.pageItems.map((g) => (
        <div
          key={g.tenantId}
          className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border p-3 text-sm"
        >
          <span className="min-w-0">
            <span className="font-medium">{g.name}</span>
            {g.reason && <span className="block text-xs text-muted-foreground">{g.reason}</span>}
          </span>
          <span className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
            <Badge variant="outline">{g.plan}</Badge>
            <span>granted {date(g.grantedAt)}</span>
            <Badge variant={g.overdue ? "destructive" : "accent"}>
              {g.overdue ? "Past expiry, revert pending" : `Expires ${timeUntil(g.expiresAt)}`}
            </Badge>
          </span>
        </div>
      ))}
      <Pager pg={pg} label="grants" capped={grants && total > grants.length ? grants.length : undefined} />
    </SectionCard>
  );
}

function InvoicesPanel() {
  const [tenantId, setTenantId] = useState("");
  const [applied, setApplied] = useState("");
  const [rows, setRows] = useState<InvoiceRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setRows((await billingApi.invoices({ tenantId: applied, limit: 100 })).invoices);
      setError(null);
    } catch (err: any) {
      setRows(null);
      setError(err?.message || "Could not load invoices.");
    }
  }, [applied]);

  useEffect(() => {
    void load();
  }, [load]);

  const pg = usePagination(rows ?? [], 10);

  return (
    <SectionCard
      title="Recent invoices"
      subtitle="One per captured payment, newest first (latest 100)"
      icon={Receipt}
      delay={0.09}
      actions={
        <form
          onSubmit={(e) => {
            e.preventDefault();
            setApplied(tenantId.trim());
          }}
          className="flex items-center gap-2"
        >
          <Input
            aria-label="Tenant id filter"
            className="h-9 w-72"
            placeholder="Tenant id (optional)"
            value={tenantId}
            onChange={(e) => setTenantId(e.target.value)}
          />
          <Button type="submit" size="sm">
            <Search /> Filter
          </Button>
        </form>
      }
    >
      <InfoNote>
        Only captured payments are recorded. There is no store of failed charges or refunds; a failing subscription
        shows as &ldquo;Past due&rdquo; above instead.
      </InfoNote>
      {error && <ErrorAlert message={error} onRetry={() => void load()} />}
      {!rows && !error && <Skeleton className="h-16 w-full" />}
      {rows && rows.length === 0 && <p className="text-sm text-muted-foreground">No invoices.</p>}
      {pg.pageItems.map((i) => (
        <div
          key={i.id}
          className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border p-3 text-sm"
        >
          <span className="min-w-0">
            <span className="font-medium">{i.invoiceNumber}</span>
            <span className="ml-2 text-xs text-muted-foreground">{i.tenantName || i.tenantId.slice(0, 8)}</span>
          </span>
          <span className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
            {i.plan && (
              <Badge variant="outline">
                {i.plan}
                {i.period ? ` / ${i.period}` : ""}
              </Badge>
            )}
            <span className="text-sm font-medium text-foreground">{formatMinor(i.amountMinor, i.currency)}</span>
            <span>{date(i.issuedAt)}</span>
          </span>
        </div>
      ))}
      <Pager pg={pg} label="invoices" capped={rows && rows.length >= 100 ? 100 : undefined} />
    </SectionCard>
  );
}

export default function PlatformBillingPage() {
  return (
    <AdminShell title="Billing & Subscriptions">
      <PageHint>
        A read-only, cross-tenant view of plans, subscription state, comp grants and invoices. Plan overrides and the
        site-wide promo are managed elsewhere; nothing on this page can charge, refund or cancel.
      </PageHint>
      <SummaryPanel />
      <SubscriptionsPanel />
      <CompGrantsPanel />
      <InvoicesPanel />
    </AdminShell>
  );
}
