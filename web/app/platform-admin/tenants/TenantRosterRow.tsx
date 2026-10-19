"use client";

// One tenant in the Tenants & Users roster (page.tsx): identity, status and
// usage facts, plan / residency pickers, and the sign-in, usage-reset and
// delete actions. State and API calls stay in the page.

import { motion } from "motion/react";
import Link from "next/link";
import { Gauge, ShieldCheck, ShieldOff, Trash2 } from "lucide-react";
import Select from "../components/Select";
import { Button } from "../components/ui/button";
import { Label } from "../components/ui/label";
import { TENANT_STATUS_LABEL, tenantStatusTone, type PlatformTenantSummary } from "../lib/tenantRoster";
import { formatCountOrUnavailable, formatDay, formatExact, formatRelative, formatUsdOrUnavailable, UNAVAILABLE } from "../lib/platformFormat";
import { capTone, formatCapUsage } from "../lib/governanceFormat";
import { CAP_TONE_CLASS, PLAN_OPTIONS, RESIDENCY_OPTIONS, STATUS_TONE_CLASS } from "./tenantOptions";

function Meta({ label, children, title }: { label: string; children: any; title?: string }) {
  return (
    <div className="min-w-0" title={title}>
      <div className="text-[10px] uppercase tracking-wide text-muted-foreground">{label}</div>
      <div className="truncate text-xs text-foreground">{children}</div>
    </div>
  );
}

export function TenantRosterRow({
  t,
  draftPlan,
  onDraftPlanChange,
  saving,
  onSavePlan,
  savingResidency,
  onChangeResidency,
  resuming,
  onResume,
  onStop,
  resettingUsage,
  onResetUsage,
  onDelete,
}: {
  t: PlatformTenantSummary;
  draftPlan: string | undefined;
  onDraftPlanChange: (plan: string) => void;
  saving: boolean;
  onSavePlan: () => void;
  savingResidency: boolean;
  onChangeResidency: (region: string) => void;
  resuming: boolean;
  onResume: () => void;
  onStop: () => void;
  resettingUsage: boolean;
  onResetUsage: () => void;
  onDelete: () => void;
}) {
  const dirty = draftPlan && draftPlan !== t.plan;
  return (
    <motion.div
      className="flex flex-wrap items-end justify-between gap-3 rounded-lg border border-border p-4"
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: -8 }}
      transition={{ duration: 0.25 }}
    >
      <div className="min-w-0 flex-1 basis-full lg:basis-auto">
        <div className="flex flex-wrap items-center gap-1.5 text-sm font-semibold text-foreground">
          <Link
            href={`/platform-admin/tenant-detail?id=${encodeURIComponent(t.tenantId)}`}
            className="hover:text-primary hover:underline hover:underline-offset-2"
            title="Open tenant detail"
          >
            {t.name}
          </Link>
          <span
            className={`rounded-full border px-1.5 py-0.5 text-xs font-medium uppercase tracking-wide ${
              STATUS_TONE_CLASS[tenantStatusTone(t.status)]
            }`}
          >
            {TENANT_STATUS_LABEL[t.status] || t.status || "Unknown"}
          </span>
          {t.emergencyStopped && (
            <span className="rounded-full border border-destructive/30 bg-destructive/10 px-1.5 py-0.5 text-xs font-medium uppercase tracking-wide text-destructive">
              Sign-in disabled
            </span>
          )}
        </div>
        <div className="mt-0.5 font-mono text-xs text-muted-foreground">
          {t.agentCount} agent{t.agentCount === 1 ? "" : "s"} · {t.workflowCount} workflow
          {t.workflowCount === 1 ? "" : "s"} · {t.userCount} user{t.userCount === 1 ? "" : "s"}
          {!t.metricsComplete && " · partial data"}
        </div>
        <div className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1.5 sm:grid-cols-3 xl:grid-cols-6">
          <Meta label="Owner" title={t.ownerEmail || undefined}>
            {t.ownerEmail || UNAVAILABLE}
          </Meta>
          <Meta label="Created" title={formatExact(t.createdAt)}>
            {formatDay(t.createdAt)}
          </Meta>
          <Meta label="Last activity" title={formatExact(t.lastActivityAt)}>
            {formatRelative(t.lastActivityAt)}
          </Meta>
          <Meta
            label="Spend (30d)"
            title="All providers incl. BYOK (estimate). Hosted = platform-paid pool share."
          >
            <span className="font-mono">{formatUsdOrUnavailable(t.spend30dUsd)}</span>
            <span className="ml-1 text-[11px] text-muted-foreground">
              hosted {formatUsdOrUnavailable(t.hostedSpend30dUsd)}
            </span>
          </Meta>
          <Meta label="LLM calls (30d)">
            <span className="font-mono">{formatCountOrUnavailable(t.llmCalls30d)}</span>
          </Meta>
          <Meta
            label="Spend cap (month)"
            title="Hosted-pool spend this calendar month (UTC) against the tenant's monthly cap. BYOK is not counted."
          >
            <span
              className={`font-mono ${
                CAP_TONE_CLASS[
                  capTone({
                    capUsd: t.spendCapUsd,
                    monthToDateUsd: t.capMonthToDateUsd,
                    blocked: t.capBlocked,
                  })
                ]
              }`}
            >
              {formatCapUsage(t.spendCapUsd, t.capMonthToDateUsd)}
            </span>
            {t.capBlocked && (
              <span className="ml-1 rounded-full border border-destructive/30 bg-destructive/10 px-1.5 py-0.5 text-[10px] font-medium uppercase text-destructive">
                Blocked
              </span>
            )}
          </Meta>
        </div>
      </div>
      <div className="flex flex-wrap items-end gap-2">
        <div className="w-full space-y-1.5 sm:w-40">
          <Label htmlFor={`tenant-plan-${t.tenantId}`} className="text-xs text-muted-foreground">
            Plan
          </Label>
          <Select
            id={`tenant-plan-${t.tenantId}`}
            value={draftPlan || t.plan}
            onChange={(e) => onDraftPlanChange(e.target.value)}
            options={PLAN_OPTIONS}
          />
        </div>
        <div className="w-full space-y-1.5 sm:w-36">
          <Label htmlFor={`tenant-residency-${t.tenantId}`} className="text-xs text-muted-foreground">
            Residency
          </Label>
          <Select
            id={`tenant-residency-${t.tenantId}`}
            value={t.dataResidencyRegion || "UNSPECIFIED"}
            disabled={savingResidency}
            onChange={(e) => onChangeResidency(e.target.value)}
            options={RESIDENCY_OPTIONS}
          />
        </div>
        <Button
          size="sm"
          disabled={!dirty || saving}
          onClick={onSavePlan}
        >
          {saving ? "Saving…" : "Save"}
        </Button>
        {t.emergencyStopped ? (
          <Button
            variant="outline"
            size="sm"
            disabled={resuming}
            onClick={onResume}
            title="Let this tenant's users sign in again"
          >
            <ShieldCheck size={13} /> {resuming ? "Enabling…" : "Enable login"}
          </Button>
        ) : (
          <Button
            variant="outline"
            size="sm"
            onClick={onStop}
            title="Block every sign-in and API call from this tenant's users - reversible"
          >
            <ShieldOff size={13} /> Disable login
          </Button>
        )}
        <Button
          variant="outline"
          size="sm"
          disabled={resettingUsage}
          onClick={onResetUsage}
          title="Clear this tenant's daily and monthly free-tier usage counters (message + token) back to zero - the limit itself is unchanged"
        >
          <Gauge size={13} /> {resettingUsage ? "Resetting…" : "Reset usage"}
        </Button>
        <Button
          variant="destructive"
          size="sm"
          onClick={onDelete}
          title="Permanently delete this tenant's entire data - irreversible, master key required"
        >
          <Trash2 size={13} /> Delete
        </Button>
      </div>
    </motion.div>
  );
}
