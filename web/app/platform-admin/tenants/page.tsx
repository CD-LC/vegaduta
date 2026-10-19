"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { motion, AnimatePresence } from "motion/react";
import Link from "next/link";
import {
  RefreshCw,
  CreditCard,
  Search,
  ArrowDown,
  ArrowUp,
} from "lucide-react";
import { api } from "../lib/api";
import AdminShell from "../components/AdminShell";
import Select from "../components/Select";
import { toast } from "../lib/toastBus";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import { usePagination } from "../lib/usePagination";
import PaginationControls from "../components/PaginationControls";
import { FeatureFlagsSection } from "../components/sections/feature-flags";
import { SiteWidePromoSection } from "../components/sections/platform-controls";
import TemplateReasonDialog from "../components/templates/TemplateReasonDialog";
import DeleteTenantDialog from "../components/tenants/DeleteTenantDialog";
import MasterKeyPrompt from "../components/tenants/MasterKeyPrompt";
import { demoSandboxSummary, normalizeTenantCounts } from "../lib/tenantCounts";
import {
  CAP_FILTER_LABEL,
  TENANT_STATUSES,
  TENANT_STATUS_LABEL,
  countByStatus,
  countCaps,
  filterByCap,
  filterTenants,
  sortTenants,
  type CapFilter,
  type PlatformTenantSummary,
  type SortDir,
  type TenantSortKey,
} from "../lib/tenantRoster";
import { UNAVAILABLE } from "../lib/platformFormat";
import { useConfirm } from "../components/admin/ConfirmDialog";
import { AdminEmptyState, AdminErrorState, AdminFilterBar } from "../components/admin/AdminKit";
import { PLAN_OPTIONS, RESIDENCY_OPTIONS, SORT_OPTIONS } from "./tenantOptions";
import { PlanLimitsCard } from "./PlanLimitsCard";
import { TenantRosterRow } from "./TenantRosterRow";

// Tenants & Users (PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §3.9) - the tenant
// lifecycle hub: the cross-tenant roster with a super-admin plan-tier override,
// the per-plan limits editor (max users/agents/workflows/KB + BYOK/WhatsApp/
// white-label gates), and the A/B feature-flag config. This absorbs the old
// standalone Subscription Plans screen; endpoints/behaviour are unchanged.

export default function TenantsUsersPage() {
  const askConfirm = useConfirm();
  const [tenants, setTenants] = useState<PlatformTenantSummary[]>([]);
  const [tenantCounts, setTenantCounts] = useState(null);
  const [tenantsError, setTenantsError] = useState(null);
  const [refreshing, setRefreshing] = useState(false);
  const [lastChecked, setLastChecked] = useState(null);
  const [draftPlan, setDraftPlan] = useState<Record<string, string>>({});
  const [savingTenantId, setSavingTenantId] = useState(null);

  // Fail-safe / per-tenant kill switch (TenantEmergencyControlService) -
  // reversible, no master key needed (unlike delete below). stopTarget holds
  // the tenant a reason is being collected for; resumingTenantId is the one
  // currently being un-stopped.
  const [stopTarget, setStopTarget] = useState(null);
  const [stopping, setStopping] = useState(false);
  const [resumingTenantId, setResumingTenantId] = useState(null);

  // Support-desk action (2026-08-09): give one tenant a clean slate on its
  // current daily/monthly free-tier usage counters (message + token, both
  // windows) without changing the limit itself - see the Platform Token
  // Quota section on Budgets & Limits for changing the limit. No local flag
  // to flip afterward (unlike emergencyStopped) - it's a point-in-time
  // action, not a persistent state.
  const [resettingUsageTenantId, setResettingUsageTenantId] = useState(null);

  // Platform-admin "delete tenant completely" control - masterKey lives in
  // memory only for this browser session (never localStorage), re-sent on
  // every delete call; the server re-validates it every time regardless.
  const [masterKey, setMasterKey] = useState(null);
  const [masterKeyPromptOpen, setMasterKeyPromptOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState(null);
  const [deleting, setDeleting] = useState(false);

  const [tenantSearch, setTenantSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState("ALL");
  const [capFilter, setCapFilter] = useState<CapFilter>("ALL");
  const [sortKey, setSortKey] = useState<TenantSortKey>("spend");
  const [sortDir, setSortDir] = useState<SortDir>("desc");
  const statusCounts = useMemo(() => countByStatus(tenants), [tenants]);
  const filteredTenants = useMemo(
    () => sortTenants(filterByCap(filterTenants(tenants, tenantSearch, statusFilter), capFilter), sortKey, sortDir),
    [tenants, tenantSearch, statusFilter, capFilter, sortKey, sortDir]
  );
  const capCounts = useMemo(() => countCaps(tenants), [tenants]);
  const capOptions = useMemo(
    () => [
      { value: "ALL", label: CAP_FILTER_LABEL.ALL },
      { value: "CAPPED", label: `${CAP_FILTER_LABEL.CAPPED} (${capCounts.capped})` },
      { value: "BLOCKED", label: `${CAP_FILTER_LABEL.BLOCKED} (${capCounts.blocked})` },
    ],
    [capCounts]
  );
  const statusOptions = useMemo(
    () => [
      { value: "ALL", label: `All statuses (${tenants.length})` },
      ...TENANT_STATUSES.map((s) => ({ value: s, label: `${TENANT_STATUS_LABEL[s]} (${statusCounts[s] || 0})` })),
    ],
    [tenants.length, statusCounts]
  );
  const { page, pageSize, totalPages, totalItems, pageItems, setPage, setPageSize } = usePagination(filteredTenants);

  const refresh = useCallback(async () => {
    setRefreshing(true);
    try {
      const [list, counts] = await Promise.all([
        api.getPlatformTenants(),
        // Independent of the roster: a core without the endpoint must not blank the page.
        api.getPlatformTenantCounts().catch((): null => null),
      ]);
      setTenants(list);
      setTenantCounts(normalizeTenantCounts(counts));
      setTenantsError(null);
      setDraftPlan((prev) => {
        const next = { ...prev };
        for (const t of list) {
          if (!next[t.tenantId]) next[t.tenantId] = t.plan;
        }
        return next;
      });
    } catch (err) {
      setTenants([]);
      setTenantsError((err as Error).message);
    }
    setLastChecked(new Date());
    setRefreshing(false);
  }, []);

  useEffect(() => {
    refresh().catch((e) => console.error("Failed to load tenants", e));
  }, [refresh]);

  async function handleChangePlan(tenant: PlatformTenantSummary) {
    const nextPlan = draftPlan[tenant.tenantId];
    if (!nextPlan || nextPlan === tenant.plan) return;
    if (
      !(await askConfirm(
        `Change ${tenant.name}'s plan from ${tenant.plan} to ${nextPlan}? This changes their billing/feature entitlements immediately.`
      ))
    ) {
      return;
    }
    setSavingTenantId(tenant.tenantId);
    try {
      const updated = await api.updateTenantPlan(tenant.tenantId, nextPlan);
      setTenants((prev) =>
        prev.map((t) => (t.tenantId === updated.tenantId ? { ...t, status: updated.status, plan: updated.plan } : t))
      );
      toast.success(
        `${tenant.name} moved to ${PLAN_OPTIONS.find((p) => p.value === updated.plan)?.label || updated.plan}.`
      );
    } catch (err) {
      toast.error((err as Error).message || `Could not change ${tenant.name}'s plan.`);
      setDraftPlan((prev) => ({ ...prev, [tenant.tenantId]: tenant.plan }));
    } finally {
      setSavingTenantId(null);
    }
  }

  // Declared data-residency region (PlatformTenantDataResidencyService) -
  // informational jurisdiction metadata, no data moves, so it saves on select
  // without the plan change's confirm dialog.
  const [savingResidencyTenantId, setSavingResidencyTenantId] = useState(null);
  async function handleChangeResidency(tenant: PlatformTenantSummary, region: string) {
    if (!region || region === (tenant.dataResidencyRegion || "UNSPECIFIED")) return;
    setSavingResidencyTenantId(tenant.tenantId);
    try {
      const updated = await api.updateTenantDataResidency(tenant.tenantId, region);
      setTenants((prev) =>
        prev.map((t) =>
          t.tenantId === tenant.tenantId ? { ...t, dataResidencyRegion: updated.dataResidencyRegion ?? region } : t
        )
      );
      toast.success(
        `${tenant.name}'s data-residency set to ${RESIDENCY_OPTIONS.find((r) => r.value === region)?.label || region}.`
      );
    } catch (err) {
      toast.error((err as Error).message || `Could not update ${tenant.name}'s data residency.`);
    } finally {
      setSavingResidencyTenantId(null);
    }
  }

  async function handleConfirmStop(reason: string) {
    if (!stopTarget) return;
    setStopping(true);
    try {
      await api.stopTenant(stopTarget.tenantId, reason);
      setTenants((prev) =>
        prev.map((t) => (t.tenantId === stopTarget.tenantId ? { ...t, emergencyStopped: true } : t))
      );
      toast.success(`${stopTarget.name}'s users can no longer sign in.`);
      setStopTarget(null);
    } catch (err) {
      toast.error((err as Error).message || `Could not disable ${stopTarget.name}.`);
    } finally {
      setStopping(false);
    }
  }

  async function handleResume(tenant: PlatformTenantSummary) {
    setResumingTenantId(tenant.tenantId);
    try {
      await api.resumeTenant(tenant.tenantId);
      setTenants((prev) => prev.map((t) => (t.tenantId === tenant.tenantId ? { ...t, emergencyStopped: false } : t)));
      toast.success(`${tenant.name}'s users can sign in again.`);
    } catch (err) {
      toast.error((err as Error).message || `Could not re-enable ${tenant.name}.`);
    } finally {
      setResumingTenantId(null);
    }
  }

  async function handleResetUsage(tenant: PlatformTenantSummary) {
    if (
      !(await askConfirm(
        `Reset ${tenant.name}'s daily and monthly usage quota now? This clears both message and token counters back to zero.`
      ))
    ) {
      return;
    }
    setResettingUsageTenantId(tenant.tenantId);
    try {
      await api.resetTenantUsageQuota(tenant.tenantId);
      toast.success(`${tenant.name}'s usage quota has been reset.`);
    } catch (err) {
      toast.error((err as Error).message || `Could not reset ${tenant.name}'s usage quota.`);
    } finally {
      setResettingUsageTenantId(null);
    }
  }

  function handleDeleteClick(tenant: PlatformTenantSummary) {
    setDeleteTarget(tenant);
    if (!masterKey) setMasterKeyPromptOpen(true);
  }

  function handleMasterKeySubmit(key: string) {
    setMasterKey(key);
    setMasterKeyPromptOpen(false);
  }

  function handleMasterKeyCancel() {
    setMasterKeyPromptOpen(false);
    setDeleteTarget(null);
  }

  async function handleConfirmDelete() {
    if (!deleteTarget || !masterKey) return;
    setDeleting(true);
    try {
      await api.deletePlatformTenant(deleteTarget.tenantId, { confirmTenantName: deleteTarget.name, masterKey });
      setTenants((prev) => prev.filter((t) => t.tenantId !== deleteTarget.tenantId));
      toast.success(`${deleteTarget.name} and all of its data have been deleted.`);
      setDeleteTarget(null);
    } catch (err) {
      // A rejected master key is almost certainly wrong, not transient -
      // clear it so the next delete attempt re-prompts instead of retrying
      // the same bad value silently on every call.
      if ((err as { status?: unknown }).status === 401 || (err as { status?: unknown }).status === 403) {
        setMasterKey(null);
      }
      toast.error((err as Error).message || `Could not delete ${deleteTarget.name}.`);
    } finally {
      setDeleting(false);
    }
  }

  return (
    <AdminShell
      title="Tenants & Users"
      actions={
        <Button variant="outline" size="sm" onClick={refresh} disabled={refreshing} title="Refresh tenants">
          <RefreshCw size={14} className={refreshing ? "animate-spin" : undefined} /> Refresh
        </Button>
      }
    >
      <motion.p
        className="mb-5 max-w-3xl text-sm leading-relaxed text-muted-foreground"
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3 }}
      >
        Tenant lifecycle - the cross-tenant roster with a super-admin plan-tier override, per-plan limits, and A/B
        feature flags. A plan change here is a super-admin override; it does not create or change a Razorpay
        subscription, so a tenant already paying through checkout keeps its own billing state untouched.
        {lastChecked && ` Last checked ${lastChecked.toLocaleTimeString()}.`}
      </motion.p>

      <motion.div
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.35, ease: [0.16, 1, 0.3, 1] }}
      >
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-sm">
              <CreditCard size={16} className="text-primary" /> Tenant Roster & Plans
            </CardTitle>
            <p className="text-xs text-muted-foreground">
              {tenants.length} registered tenant{tenants.length === 1 ? "" : "s"} ({statusCounts.ACTIVE || 0} active) -
              spend and LLM calls are trailing 30 days, all providers incl. BYOK (estimates); &ldquo;{UNAVAILABLE}
              &rdquo; means core could not measure it
            </p>
            {tenantCounts && (
              <p className="text-xs text-muted-foreground">
                Demo sandboxes (anonymous /try, not listed or counted here): {demoSandboxSummary(tenantCounts)} -{" "}
                <Link
                  href="/platform-admin/demo-sandbox"
                  className="underline underline-offset-2 hover:text-foreground"
                >
                  details
                </Link>{" "}
                ·{" "}
                <Link
                  href="/platform-admin/demo-sandboxes"
                  className="underline underline-offset-2 hover:text-foreground"
                >
                  reclaim legacy sandboxes
                </Link>
              </p>
            )}
          </CardHeader>
          <CardContent className="space-y-2">
            {tenantsError && <AdminErrorState message={tenantsError} onRetry={() => void refresh()} />}
            {tenants.length > 0 && (
              <AdminFilterBar label="Tenant filters" className="mb-1">
                <div className="relative w-full max-w-sm">
                  <Search
                    size={14}
                    className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-muted-foreground"
                  />
                  <Input
                    value={tenantSearch}
                    onChange={(e) => setTenantSearch(e.target.value)}
                    placeholder="Search by name or owner email…"
                    className="pl-8"
                  />
                </div>
                <div className="w-full space-y-1.5 sm:w-52">
                  <Label htmlFor="tenant-filter-status" className="text-xs text-muted-foreground">
                    Status
                  </Label>
                  <Select
                    id="tenant-filter-status"
                    value={statusFilter}
                    onChange={(e) => setStatusFilter(e.target.value)}
                    options={statusOptions}
                  />
                </div>
                <div className="w-full space-y-1.5 sm:w-44">
                  <Label htmlFor="tenant-filter-cap" className="text-xs text-muted-foreground">
                    Spend cap
                  </Label>
                  <Select
                    id="tenant-filter-cap"
                    value={capFilter}
                    onChange={(e) => setCapFilter(e.target.value as CapFilter)}
                    options={capOptions}
                  />
                </div>
                <div className="w-full space-y-1.5 sm:w-44">
                  <Label htmlFor="tenant-filter-sort" className="text-xs text-muted-foreground">
                    Sort by
                  </Label>
                  <Select
                    id="tenant-filter-sort"
                    value={sortKey}
                    onChange={(e) => setSortKey(e.target.value as TenantSortKey)}
                    options={SORT_OPTIONS}
                  />
                </div>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setSortDir((d) => (d === "asc" ? "desc" : "asc"))}
                  title={
                    sortDir === "desc"
                      ? "Highest / newest first - click for ascending"
                      : "Lowest / oldest first - click for descending"
                  }
                  aria-label={`Sort direction: ${sortDir === "desc" ? "descending" : "ascending"}`}
                >
                  {sortDir === "desc" ? <ArrowDown size={13} /> : <ArrowUp size={13} />}
                  {sortDir === "desc" ? "Desc" : "Asc"}
                </Button>
              </AdminFilterBar>
            )}
            {tenants.length === 0 && !tenantsError && (
              <AdminEmptyState title="No tenants found" description="Tenants appear here once they sign up." />
            )}
            {tenants.length > 0 && filteredTenants.length === 0 && (
              <p className="text-sm text-muted-foreground">
                No tenants match the current search, status and spend-cap filters.
              </p>
            )}

            <AnimatePresence initial={false}>
              {pageItems.map((t) => {
                return (
                  <TenantRosterRow
                    key={t.tenantId}
                    t={t}
                    draftPlan={draftPlan[t.tenantId]}
                    onDraftPlanChange={(plan) => setDraftPlan((prev) => ({ ...prev, [t.tenantId]: plan }))}
                    saving={savingTenantId === t.tenantId}
                    onSavePlan={() => handleChangePlan(t)}
                    savingResidency={savingResidencyTenantId === t.tenantId}
                    onChangeResidency={(region) => handleChangeResidency(t, region)}
                    resuming={resumingTenantId === t.tenantId}
                    onResume={() => handleResume(t)}
                    onStop={() => setStopTarget(t)}
                    resettingUsage={resettingUsageTenantId === t.tenantId}
                    onResetUsage={() => handleResetUsage(t)}
                    onDelete={() => handleDeleteClick(t)}
                  />
                );
              })}
            </AnimatePresence>

            <PaginationControls
              page={page}
              totalPages={totalPages}
              totalItems={totalItems}
              pageSize={pageSize}
              onPageChange={setPage}
              onPageSizeChange={setPageSize}
              itemLabel="tenants"
            />
          </CardContent>
        </Card>
      </motion.div>

      <PlanLimitsCard />

      <FeatureFlagsSection />

      <SiteWidePromoSection delay={0.08} />

      <TemplateReasonDialog
        open={!!stopTarget}
        title={`Disable sign-in for ${stopTarget?.name || ""}`}
        description="Blocks every authenticated request from this tenant's users immediately - reversible any time from the Enable login button. Does not touch any of their data."
        confirmLabel="Disable login"
        submitting={stopping}
        required={false}
        onCancel={() => setStopTarget(null)}
        onConfirm={handleConfirmStop}
      />

      <MasterKeyPrompt open={masterKeyPromptOpen} onCancel={handleMasterKeyCancel} onSubmit={handleMasterKeySubmit} />

      <DeleteTenantDialog
        open={!!deleteTarget && !!masterKey}
        tenantName={deleteTarget?.name || ""}
        submitting={deleting}
        onCancel={() => setDeleteTarget(null)}
        onConfirm={handleConfirmDelete}
      />
    </AdminShell>
  );
}
