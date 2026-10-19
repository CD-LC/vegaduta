"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import {
  RefreshCw,
  HeartPulse,
  GitCommit,
  Construction,
  Megaphone,
  Timer,
  Database,
  Siren,
  Radar,
  ShieldAlert,
  MailX,
  ArrowRight,
} from "lucide-react";
import AdminShell from "../components/AdminShell";
import { PageHint } from "../components/sections/common";
import { ToneChip } from "../components/ops/OpsWidgets";
import { Button } from "../components/ui/button";
import { Card, CardContent } from "../components/ui/card";
import { api } from "../lib/api";
import { getPublicEnv } from "../lib/env";
import { reliabilityApi, type DependenciesResponse } from "../lib/adminApi/reliability";
import {
  opsApi,
  type AnnouncementState,
  type MaintenanceState,
  type MigrationsResponse,
  type ScheduledJob,
  type VersionsResponse,
} from "../lib/adminApi/operations";
import { countStatuses, overallSummary, statusMeta } from "../reliability/format";
import { formatRelative } from "../lib/platformFormat";
import {
  announcementLive,
  failedJobCount,
  migrationDrift,
  ownBuildSha,
  serviceStatusMeta,
  shortSha,
  unreachableServices,
  versionLabel,
} from "../lib/opsFormat";

// Operations hub - one screen that answers "is anything wrong right now?".
// Each card is a glance-level read of the same endpoint its own page renders in
// full, and links there. A card whose read failed says "unavailable" - it never
// shows a reassuring zero it did not measure.

type Settled<T> = { state: "loading" } | { state: "ok"; value: T } | { state: "unavailable"; reason?: string };
const LOADING = { state: "loading" } as const;

async function settle<T>(p: Promise<T>): Promise<Settled<T>> {
  try {
    const value = await p;
    if (value == null) return { state: "unavailable" };
    return { state: "ok", value };
  } catch (e: any) {
    return { state: "unavailable", reason: e?.status === 404 ? "endpoint not deployed on this core yet" : e?.message };
  }
}

const OWN_SHA = ownBuildSha(getPublicEnv("NEXT_PUBLIC_BUILD_SHA"));

function HubCard({
  href,
  title,
  icon: Icon,
  children,
  tone,
}: {
  href: string;
  title: string;
  icon: any;
  children: any;
  tone?: "bad" | "warn" | null;
}) {
  const border = tone === "bad" ? "border-destructive/40" : tone === "warn" ? "border-[var(--warning-border)]" : "";
  return (
    <Link href={href} className="group block h-full">
      <Card className={`h-full gap-0 py-0 transition-colors hover:border-primary/40 ${border}`}>
        <CardContent className="flex h-full flex-col gap-2 p-4">
          <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            <Icon size={14} className="text-primary" /> {title}
            <ArrowRight size={13} className="ml-auto opacity-0 transition-opacity group-hover:opacity-100" />
          </div>
          <div className="flex-1 text-sm">{children}</div>
        </CardContent>
      </Card>
    </Link>
  );
}

function Unavailable({ reason }: { reason?: string }) {
  return (
    <div className="text-sm text-muted-foreground" title={reason}>
      unavailable{reason ? <span className="block text-[11px]">{reason}</span> : null}
    </div>
  );
}

function Loading() {
  return <div className="font-mono text-2xl font-semibold text-muted-foreground">…</div>;
}

export default function OpsHubPage() {
  const [deps, setDeps] = useState<Settled<DependenciesResponse>>(LOADING);
  const [versions, setVersions] = useState<Settled<VersionsResponse>>(LOADING);
  const [maint, setMaint] = useState<Settled<MaintenanceState>>(LOADING);
  const [ann, setAnn] = useState<Settled<AnnouncementState>>(LOADING);
  const [jobs, setJobs] = useState<Settled<ScheduledJob[]>>(LOADING);
  const [migrations, setMigrations] = useState<Settled<MigrationsResponse>>(LOADING);
  const [alerts, setAlerts] = useState<Settled<any>>(LOADING);
  const [refreshing, setRefreshing] = useState(false);
  const [checkedAt, setCheckedAt] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setRefreshing(true);
    try {
      const [d, v, m, a, j, mg, al] = await Promise.all([
        settle(reliabilityApi.dependencies()),
        settle(opsApi.versions()),
        settle(opsApi.maintenanceQuiet()),
        settle(opsApi.announcementQuiet()),
        settle(opsApi.jobsQuiet()),
        settle(opsApi.migrationsQuiet()),
        settle(api.getPlatformIssuesOpenCount()),
      ]);
      setDeps(d);
      setVersions(v);
      setMaint(m);
      setAnn(a);
      setJobs(j);
      setMigrations(mg);
      setAlerts(al);
      setCheckedAt(new Date().toISOString());
    } finally {
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const depCounts = deps.state === "ok" ? countStatuses(deps.value.dependencies || []) : null;
  const depTone = depCounts ? (depCounts.DOWN ? "bad" : depCounts.DEGRADED ? "warn" : null) : null;
  const overall = deps.state === "ok" ? statusMeta(deps.value.overall) : null;

  const unreachable = versions.state === "ok" ? unreachableServices(versions.value.services) : [];
  const failedJobs = jobs.state === "ok" ? failedJobCount(jobs.value) : null;
  const drift = migrations.state === "ok" ? migrationDrift(migrations.value) : null;
  const driftCount = drift ? drift.failed + drift.behind : null;
  const openAlerts = alerts.state === "ok" && typeof alerts.value?.open === "number" ? alerts.value.open : null;

  return (
    <AdminShell
      title="Operations"
      actions={
        <Button variant="outline" size="sm" onClick={() => void refresh()} disabled={refreshing}>
          <RefreshCw className={refreshing ? "animate-spin" : undefined} /> Refresh
        </Button>
      }
    >
      <PageHint>
        Is anything wrong right now? Dependency health, what is deployed, maintenance and banner state, failed jobs,
        schema drift and open alerts on one screen. Every card opens its full page.
        {checkedAt && ` Checked ${formatRelative(checkedAt)}.`}
      </PageHint>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <HubCard href="/platform-admin/reliability" title="Dependencies" icon={HeartPulse} tone={depTone}>
          {deps.state === "loading" && <Loading />}
          {deps.state === "unavailable" && <Unavailable reason={deps.reason} />}
          {deps.state === "ok" && overall && (
            <>
              <ToneChip tone={overall.tone} label={`Overall: ${overall.label}`} />
              <p className="mt-2 text-xs text-muted-foreground">{overallSummary(deps.value.dependencies || [])}</p>
              {(deps.value.dependencies || [])
                .filter((x) => x.status === "DOWN" || x.status === "DEGRADED")
                .slice(0, 4)
                .map((x) => (
                  <div key={x.name} className="mt-1 text-xs">
                    <span className="font-medium">{x.name}</span>{" "}
                    <span className="text-muted-foreground">{statusMeta(x.status).label.toLowerCase()}</span>
                  </div>
                ))}
            </>
          )}
        </HubCard>

        <HubCard
          href="/platform-admin/ops#versions"
          title="Versions"
          icon={GitCommit}
          tone={unreachable.length ? "warn" : null}
        >
          {versions.state === "loading" && <Loading />}
          {versions.state === "unavailable" && <Unavailable reason={versions.reason} />}
          {versions.state === "ok" && (
            <p className="text-xs text-muted-foreground">
              {versions.value.services?.length ?? 0} service(s) reported
              {unreachable.length ? ` · ${unreachable.length} unreachable` : ""}. Full list below.
            </p>
          )}
          <p className="mt-1 text-xs">
            web-admin build: <span className="font-mono">{OWN_SHA ? shortSha(OWN_SHA) : "not stamped"}</span>
          </p>
        </HubCard>

        <HubCard
          href="/platform-admin/maintenance"
          title="Maintenance & banner"
          icon={Construction}
          tone={maint.state === "ok" && maint.value.enabled ? "bad" : null}
        >
          {maint.state === "loading" && <Loading />}
          {maint.state === "unavailable" && <Unavailable reason={maint.reason} />}
          {maint.state === "ok" && (
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-xs">Maintenance</span>
              {maint.value.enabled ? <ToneChip tone="bad" label="ON" /> : <ToneChip tone="ok" label="Off" />}
              {maint.value.enabled && maint.value.startedAt && (
                <span className="text-[11px] text-muted-foreground">since {formatRelative(maint.value.startedAt)}</span>
              )}
            </div>
          )}
          <div className="mt-2 flex flex-wrap items-center gap-2">
            <Megaphone size={12} className="text-muted-foreground" />
            <span className="text-xs">Banner</span>
            {ann.state === "ok" ? (
              announcementLive(ann.value) ? (
                <ToneChip tone="warn" label="Live" />
              ) : (
                <ToneChip tone="muted" label="Not showing" />
              )
            ) : ann.state === "loading" ? (
              <span className="text-xs text-muted-foreground">…</span>
            ) : (
              <span className="text-xs text-muted-foreground">unavailable</span>
            )}
          </div>
        </HubCard>

        <HubCard href="/platform-admin/jobs" title="Scheduled jobs" icon={Timer} tone={failedJobs ? "bad" : null}>
          {jobs.state === "loading" && <Loading />}
          {jobs.state === "unavailable" && <Unavailable reason={jobs.reason} />}
          {jobs.state === "ok" && (
            <>
              <div className={`font-mono text-2xl font-semibold ${failedJobs ? "text-destructive" : ""}`}>
                {failedJobs}
              </div>
              <p className="text-xs text-muted-foreground">failed on last run, of {jobs.value.length} jobs</p>
            </>
          )}
        </HubCard>

        <HubCard
          href="/platform-admin/migrations"
          title="Schema migrations"
          icon={Database}
          tone={drift?.failed ? "bad" : driftCount ? "warn" : null}
        >
          {migrations.state === "loading" && <Loading />}
          {migrations.state === "unavailable" && <Unavailable reason={migrations.reason} />}
          {migrations.state === "ok" && drift && (
            <>
              <div className={`font-mono text-2xl font-semibold ${driftCount ? "text-[var(--warning-text)]" : ""}`}>
                {driftCount}
              </div>
              <p className="text-xs text-muted-foreground">
                tenant schema(s) behind or failed ({drift.failed} failed, {drift.behind} behind)
                {drift.controlPlaneProblem ? " · control plane has pending/failed migrations" : ""}
              </p>
            </>
          )}
        </HubCard>

        <HubCard href="/platform-admin/fraud" title="Open alerts" icon={Siren} tone={openAlerts ? "warn" : null}>
          {alerts.state === "loading" && <Loading />}
          {alerts.state === "ok" && openAlerts != null ? (
            <>
              <div className="font-mono text-2xl font-semibold">{openAlerts}</div>
              <p className="text-xs text-muted-foreground">open platform issues</p>
            </>
          ) : alerts.state !== "loading" ? (
            <Unavailable reason={alerts.state === "unavailable" ? alerts.reason : undefined} />
          ) : null}
        </HubCard>
      </div>

      <div className="mt-4 grid gap-3 sm:grid-cols-3">
        <HubCard href="/platform-admin/observability" title="LLM traces" icon={Radar}>
          <p className="text-xs text-muted-foreground">Langfuse traces, cost and latency, external dashboards.</p>
        </HubCard>
        <HubCard href="/platform-admin/skill-security" title="Skill security" icon={ShieldAlert}>
          <p className="text-xs text-muted-foreground">Skill scan findings and the approval queue.</p>
        </HubCard>
        <HubCard href="/platform-admin/email-suppressions" title="Email suppressions" icon={MailX}>
          <p className="text-xs text-muted-foreground">Bounced, complained and unsubscribed addresses.</p>
        </HubCard>
      </div>

      <section id="versions" className="mt-6 scroll-mt-20">
        <Card>
          <CardContent className="p-4">
            <h2 className="mb-3 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              <GitCommit size={14} /> Deployed versions
            </h2>
            <div className="overflow-x-auto">
              <table className="w-full min-w-[560px] text-left text-sm">
                <thead className="text-xs text-muted-foreground">
                  <tr className="border-b border-border">
                    <th className="py-2 pr-3 font-medium">Service</th>
                    <th className="py-2 pr-3 font-medium">Version</th>
                    <th className="py-2 pr-3 font-medium">Git SHA</th>
                    <th className="py-2 pr-3 font-medium">Built</th>
                    <th className="py-2 font-medium">Status</th>
                  </tr>
                </thead>
                <tbody>
                  <tr className="border-b border-border/60">
                    <td className="py-2 pr-3 font-medium">web-admin (this console)</td>
                    <td className="py-2 pr-3 font-mono text-xs">—</td>
                    <td className="py-2 pr-3 font-mono text-xs" title={OWN_SHA || undefined}>
                      {OWN_SHA ? shortSha(OWN_SHA) : "not stamped"}
                    </td>
                    <td className="py-2 pr-3 text-xs">—</td>
                    <td className="py-2">
                      <ToneChip tone="ok" label="Serving you" />
                    </td>
                  </tr>
                  {versions.state === "ok" &&
                    (versions.value.services || []).map((s) => {
                      const m = serviceStatusMeta(s.status);
                      return (
                        <tr key={s.name} className="border-b border-border/60" data-testid="version-row">
                          <td className="py-2 pr-3 font-medium">{s.name}</td>
                          <td className="py-2 pr-3 font-mono text-xs">{versionLabel(s)}</td>
                          <td className="py-2 pr-3 font-mono text-xs" title={s.gitSha || undefined}>
                            {shortSha(s.gitSha)}
                          </td>
                          <td className="py-2 pr-3 text-xs">{s.buildTime ? formatRelative(s.buildTime) : "—"}</td>
                          <td className="py-2">
                            <ToneChip tone={m.tone} label={m.label} />
                          </td>
                        </tr>
                      );
                    })}
                </tbody>
              </table>
              {versions.state === "unavailable" && (
                <p className="pt-2 text-xs text-muted-foreground">
                  Backend versions unavailable{versions.reason ? `: ${versions.reason}` : ""}.
                </p>
              )}
            </div>
          </CardContent>
        </Card>
      </section>
    </AdminShell>
  );
}
