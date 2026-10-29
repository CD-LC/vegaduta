"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import {
  Database,
  HardDrive,
  Zap,
  Activity,
  ListOrdered,
  Workflow,
  KeyRound,
  ShieldCheck,
  ArrowRight,
  type LucideIcon,
} from "lucide-react";
import { api, type TenantDataUsageResponse, type TenantDataUsageSystem } from "../lib/api";
import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import { usePageReady } from "../components/RouteTransition";
import { useAuth } from "../components/AuthProvider";
import { Card, CardContent } from "../components/ui/card";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Skeleton } from "../components/ui/skeleton";
import { StatusDot } from "../components/ui/status-dot";
import { useTranslations } from "../lib/i18n";

// Tenant-facing "how does our infrastructure use your data" transparency
// page - backed by GET /api/tenant/data-usage (TenantDataUsageController,
// built by a parallel core-3 session against the fixed contract this file
// consumes). One card per underlying system; the copy for each (description,
// isolation fact, live stat) comes straight from the API response - this
// page renders it faithfully rather than writing its own marketing copy, so
// the Temporal card (built but not yet handling live traffic) reads exactly
// as honest as the backend states it, no frontend embellishment either way.
//
// No i18n namespace: page copy that isn't API-sourced (headings, labels) is
// inline English, same posture as /features and /help/workflow-recipes -
// only the nav rail label ("railData") goes through i18n, matching how
// every other item in the PLANS & HELP nav group already works.

// Fixed display order + a sensible lucide icon per system id (contract §2).
// The API doesn't guarantee array order, so this list drives layout order;
// any id the backend returns that isn't in this map still renders (falls
// back to a generic icon) rather than silently disappearing.
const SYSTEM_DISPLAY: Record<string, { icon: LucideIcon }> = {
  postgres: { icon: Database },
  minio: { icon: HardDrive },
  redis: { icon: Zap },
  langfuse: { icon: Activity },
  kafka: { icon: ListOrdered },
  temporal: { icon: Workflow },
  keycloak: { icon: KeyRound },
};
const SYSTEM_ORDER = ["postgres", "minio", "redis", "langfuse", "kafka", "temporal", "keycloak"];

function orderedSystems(systems: TenantDataUsageSystem[]): TenantDataUsageSystem[] {
  const byId = new Map(systems.map((s) => [s.id, s]));
  const ordered: TenantDataUsageSystem[] = [];
  for (const id of SYSTEM_ORDER) {
    const s = byId.get(id);
    if (s) {
      ordered.push(s);
      byId.delete(id);
    }
  }
  // Anything the backend returns that this page doesn't know about yet
  // still shows up, appended at the end, instead of being dropped silently.
  return [...ordered, ...byId.values()];
}

function SystemCard({ system }: { system: TenantDataUsageSystem }) {
    const tHC = useTranslations("hcDatausagePage");
  const Icon = SYSTEM_DISPLAY[system.id]?.icon || Database;
  const isActive = system.status === "active";

  return (
    <Card>
      <CardContent className="space-y-4">
        <div className="flex items-start gap-3">
          <span className="flex size-10 shrink-0 items-center justify-center rounded-lg border border-border bg-muted/40 text-primary">
            <Icon className="size-5" aria-hidden="true" />
          </span>
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-baseline gap-x-2">
              <h3 className="text-base font-semibold tracking-tight text-foreground">{system.name}</h3>
              <span className="text-xs text-muted-foreground">{system.vendor}</span>
            </div>
            <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{system.role}</p>
          </div>
          <div className="flex shrink-0 flex-col items-end gap-1 text-end">
            <span className="flex items-center gap-1.5">
              <StatusDot status={isActive ? "up" : "pending"} />
              <span className="text-xs font-medium text-foreground">{isActive ? "Active" : "Built, not yet live"}</span>
            </span>
            {/* Direct instruction: don't hide or downplay "available, not yet
             * engaged" (Temporal) behind a vague "coming soon" - state it as
             * plainly as every active system's own status line. */}
            {!isActive && (
              <span className="max-w-[9rem] text-[11px] leading-tight text-muted-foreground">
                {tHC("hcDatausagePage.s5")}
              </span>
            )}
          </div>
        </div>

        <p className="text-sm leading-relaxed text-muted-foreground">{system.description}</p>

        {/* The trust payload of this page - called out visually, not just
         * another paragraph. */}
        <div className="rounded-lg border border-primary/25 bg-primary/5 px-3.5 py-3">
          <div className="mb-1 flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-primary">
            <ShieldCheck className="size-3.5" aria-hidden="true" />
            Isolation
          </div>
          <p className="text-sm leading-relaxed text-foreground">{system.isolationFact}</p>
        </div>

        {system.liveStat && (
          <div className="flex items-center justify-between rounded-md border border-border bg-muted/30 px-3 py-2">
            <span className="text-xs text-muted-foreground">{system.liveStat.label}</span>
            <span className="font-mono text-sm font-semibold text-foreground">{system.liveStat.value}</span>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function SystemCardSkeleton() {
  return (
    <Card>
      <CardContent className="space-y-4">
        <div className="flex items-start gap-3">
          <Skeleton className="size-10 shrink-0 rounded-lg" />
          <div className="min-w-0 flex-1 space-y-1.5">
            <Skeleton className="h-4 w-1/2" />
            <Skeleton className="h-3 w-1/3" />
          </div>
        </div>
        <Skeleton className="h-4 w-full" />
        <Skeleton className="h-16 w-full rounded-lg" />
      </CardContent>
    </Card>
  );
}

export default function DataUsagePage() {
    const tHC = useTranslations("hcDatausagePage");
  const { hasPermission } = useAuth();
  const canManageData = hasPermission("MANAGE_DATA_GOVERNANCE");
  const [data, setData] = useState<TenantDataUsageResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const response = await api.getTenantDataUsage();
      setData(response);
      setError(null);
    } catch (err: any) {
      setData(null);
      setError(err?.message || "Could not load your infrastructure data-usage summary.");
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);
  usePageReady(loaded);

  const systems = data ? orderedSystems(data.systems) : [];

  return (
    <AppShell eyebrow="Trust">
      <PageHeader
        title={tHC("hcDatausagePage.s4")}
        subtitle="A plain accounting of every system your workspace's data touches, and exactly how it's kept isolated from every other tenant."
      />

      {/* Scoped inline error, not a full-page banner - the rest of the
       * console (nav, header) still works fine even if this one fetch
       * fails. */}
      {error && (
        <Alert variant="destructive" className="mb-5">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      {!loaded && (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          {Array.from({ length: 7 }).map((_, i) => (
            <SystemCardSkeleton key={i} />
          ))}
        </div>
      )}

      {loaded && data && (
        <>
          <p className="mb-4 text-xs text-muted-foreground">
            {tHC("hcDatausagePage.s3")} {new Date(data.generatedAt).toLocaleString()}
          </p>

          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            {systems.map((system) => (
              <SystemCard key={system.id} system={system} />
            ))}
          </div>

          <Card className="mt-4">
            <CardContent>
              <h3 className="text-sm font-semibold text-foreground">{tHC("hcDatausagePage.s2")}</h3>
              <p className="mt-1 text-sm text-muted-foreground">
                {tHC("hcDatausagePage.s1")}
              </p>
              {canManageData ? (
                <Link
                  href="/admin/data-privacy"
                  className="mt-3 inline-flex items-center gap-1.5 text-sm font-medium text-primary hover:underline"
                >
                  Go to data export &amp; deletion
                  <ArrowRight className="size-3.5" aria-hidden="true" />
                </Link>
              ) : (
                <p className="mt-3 text-xs text-muted-foreground">
                  Data export and workspace deletion are managed by your workspace admin, under Platform Maintenance.
                </p>
              )}
            </CardContent>
          </Card>
        </>
      )}
    </AppShell>
  );
}
