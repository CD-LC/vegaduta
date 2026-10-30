"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { API_BASE } from "../lib/api";
import { useAuth } from "../components/AuthProvider";
import AppShell from "../components/AppShell";
import { usePageReady } from "../components/RouteTransition";
import HudCounter from "../components/dashboard/HudCounter";
import AdminEventTail from "../components/dashboard/AdminEventTail";
import ValueReceiptCard from "../components/dashboard/ValueReceiptCard";
import InsightsTeaser from "../insights/InsightsTeaser";
import SetupChecklistAlert from "../components/SetupChecklistAlert";
import PageHeader from "../components/ax/PageHeader";
import FreePlanIncludes from "../components/FreePlanIncludes";
import { isBillingEnabled } from "../lib/billing-flag";
import { isLocalAuth } from "../lib/authMode";
import { useFirstRunBuildRoute } from "../components/tour/firstRun";
import { Button } from "../components/ui/button";
import { Card } from "../components/ui/card";
import { Activity, ArrowRight, Bot, Cpu, LayoutTemplate, Radio, Users, Workflow } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import { llmProvidersTileSub } from "../lib/llmProviders";
import "../components/dashboard/dashboardStyles";
import { TIER_AGENTS, TIER_SEATS, buildSetupSteps, healthChip } from "./_overview/model";
import { useAdminOverview } from "./_overview/useAdminOverview";
import OutcomeDonutTile from "./_overview/OutcomeDonutTile";
import PendingAndChannelsRow from "./_overview/PendingAndChannelsRow";
import PulseHero from "./_overview/PulseHero";
import SystemHealthCard from "./_overview/SystemHealthCard";
import { CardHeading, CardLink, Eyebrow, StatTile } from "./_overview/ui";

/**
 * Admin Overview - where a tenant-admin lands after login (unchanged
 * route/heading from before the nav split, so the existing "lands on the
 * Admin Control Panel" login-flow behavior/tests still hold). Previously
 * this page stacked all four admin panels (Users, Approvals, Audit Log,
 * Channels) on top of each other; each now has its own dedicated page
 * reachable both from the floating nav's Administration group and from the
 * cards below.
 */
export default function AdminOverviewPage() {
  const tHC = useTranslations("hcAdminPage");
  const t = useTranslations("adminOverview");
  const router = useRouter();
  const { isAdmin } = useAuth();
  const {
    stats,
    loadError,
    coreReachable,
    loaded,
    agentsKnown,
    systemStatus,
    systemStatusError,
    platforms,
    pendingApprovals,
    avgApprovalSeconds,
    approvalOutcomes,
  } = useAdminOverview(isAdmin);

  useEffect(() => {
    if (!isAdmin) router.replace("/");
  }, [isAdmin, router]);

  // Non-admins are redirected to "/" (return null below), so they're ready
  // immediately; an admin holds the transition cover until refresh() completes.
  usePageReady(loaded || !isAdmin);

  // No agents AND no envoys: the Build Center, once (tour/firstRun.ts). Above
  // the bail-out: hooks run every render.
  useFirstRunBuildRoute(isAdmin && loaded && agentsKnown ? stats.agents : null);

  if (!isAdmin) return null;

  const setupSteps = buildSetupSteps(stats, t);
  const coreStepsDone = stats.providers != null && stats.agents != null;
  // First-run state: agents have loaded and there are none. A signup admin
  // lands here (page.tsx redirects isAdmin straight to /admin), and until
  // now the first thing they saw was a telemetry grid; the one thing that
  // actually unblocks them - create an agent - lived in a popover. While
  // this is true the page leads with a full-width "Create your first agent"
  // card and hides the ops tiles (workflows live, Kafka queue) that are noise
  // for a workspace with nothing running yet.
  const firstRun = stats.agents === 0;
  // Plan caps only mean something where plans exist (billing on).
  const isFreePlan = stats.plan === "FREE" && isBillingEnabled();
  // Personal install: one owner by design, so no seat meter nudging them to fill seats.
  const solo = isLocalAuth();
  // Direct feedback: "once user completes all 4 minimum steps that will be
  // vanished from dashboard" - previously only the first 3 steps gated
  // this (the 4th, inviting a teammate, is optional per its own hint text),
  // so the checklist could disappear before an admin who cares about all 4
  // ever saw them all checked off. Now requires every step in the list,
  // matching "4" literally.
  const allCoreDone = setupSteps.every((step) => step.done);

  // Derived, real values for the telemetry tiles below - every number here
  // traces straight back to stats/platforms/approvalOutcomes already
  // fetched in refresh(), no placeholders.
  const connectedChannels = platforms.filter((p) => p.connected).length;
  const totalChannels = platforms.length;
  const channelPct = totalChannels > 0 ? Math.round((connectedChannels / totalChannels) * 100) : 0;
  const seatPct = stats.users != null ? Math.min(100, Math.round((stats.users / TIER_SEATS) * 100)) : 0;

  // Direct feedback: "sections showing duplicate data... publish a
  // different set of data available instead of replication" - the hero
  // surfaces real, already-fetched systemStatus fields that aren't shown
  // anywhere else on this page (LlmMetricsAdvisor's real call-count /
  // latency / error totals, the health probe's own overall verdict).
  const llm = systemStatus?.llm;
  // The tile counts the workspace's OWN provider keys; say so when chat actually runs on the shared platform model.
  const llmSub = llmProvidersTileSub(stats.onPlatformLlm);
  const overall = healthChip(systemStatus?.overallStatus, t);
  const avgResolveMinutes = avgApprovalSeconds != null ? Math.round(avgApprovalSeconds / 60) : null;

  return (
    <AppShell
      eyebrow={t("pageEyebrow")}
      actions={coreStepsDone ? <SetupChecklistAlert steps={setupSteps} allDone={allCoreDone} /> : null}
    >
      <div className="adm-ovw flex min-w-0 flex-col gap-6" data-testid="admin-overview">
        {!coreReachable && (
          <div className="error-box">
            {t("apiUnreachablePrefix", { apiBase: API_BASE })}
            <code>core</code>
            {t("apiUnreachableSuffix", { loadError })}
          </div>
        )}

        {/* mb-0: this column already spaces its children with gap-6, so
            PageHeader's own mb-6 would double the gap under the divider. */}
        <PageHeader
          title={t("pageTitle")}
          subtitle="A snapshot of this workspace: setup progress, agents, channels, approvals and system health."
          className="mb-0"
        />

        {/* First-run card (see the `firstRun` comment above). The CTA
            carries data-tour="create-agent" so the product tour's step 1
            spotlights it here, before the person has even reached the
            workbench (TourProvider's data-tour note). The "included free"
            list only renders for a positively-known FREE tenant - it is a
            statement of fact about that plan, not a marketing strip. */}
        {firstRun && (
          <Card className="gap-0 p-6" data-testid="admin-first-run">
            <div className="flex flex-col gap-6 lg:flex-row lg:items-start lg:justify-between">
              <div className="max-w-xl space-y-2">
                <h2 className="text-xl font-semibold tracking-tight text-foreground">{t("firstRunTitle")}</h2>
                <p className="text-sm leading-relaxed text-muted-foreground">{t("firstRunBody")}</p>
                <div className="flex flex-wrap items-center gap-3 pt-2">
                  <Button asChild size="lg">
                    <Link href="/agents" data-tour="create-agent">
                      <Bot className="size-4" aria-hidden="true" />
                      {t("firstRunCta")}
                    </Link>
                  </Button>
                  <Button asChild variant="ghost" size="sm">
                    <Link href="/agents/templates">
                      <LayoutTemplate className="size-4" aria-hidden="true" />
                      {t("firstRunTemplates")}
                    </Link>
                  </Button>
                  <Link
                    href="/help#getting-started"
                    className="text-sm font-medium text-primary underline-offset-4 hover:underline"
                  >
                    {tHC("s3")}
                  </Link>
                </div>
              </div>
              {isFreePlan && <FreePlanIncludes compact className="lg:w-[24rem] lg:shrink-0" />}
            </div>
          </Card>
        )}

        {/* D2-lite value receipt - hides itself entirely when the quota
            endpoint is unavailable or there's nothing to show. */}
        <ValueReceiptCard />

        {/* 7-day AI-calls teaser linking to /insights. Shows only to an admin or a
            VIEW_USAGE holder, and renders nothing at all when its data call
            fails, is refused, or there were no calls. */}
        <InsightsTeaser />

        {/* ROW 1 - six stat tiles (five on first run: "0 of 0 workflows" is
            noise next to the create-first-agent card). 1 / 2 / 3 / 6 across
            at phone / sm / lg / 2xl so nothing is squeezed at 1280. */}
        <div
          className={`grid gap-4 sm:grid-cols-2 lg:grid-cols-3 ${firstRun ? "2xl:grid-cols-5" : "2xl:grid-cols-6"}`}
          data-testid="admin-stat-tiles"
        >
          <StatTile icon={<Cpu aria-hidden="true" />} label={t("llmProvidersLabel")} sub={t(llmSub.key, llmSub.values)}>
            <HudCounter value={stats.providers} />
          </StatTile>

          <StatTile
            icon={<Bot aria-hidden="true" />}
            label={t("agentsDeployedLabel")}
            sub={isFreePlan ? t("agentsDeployedSub", { count: TIER_AGENTS }) : undefined}
          >
            <HudCounter value={stats.agents} />
          </StatTile>

          {!firstRun && (
            <StatTile
              icon={<Workflow aria-hidden="true" />}
              label={t("workflowsLiveLabel")}
              sub={t("workflowsLiveSub", { count: stats.workflows ?? "—" })}
            >
              <HudCounter value={stats.workflowsActive} />
            </StatTile>
          )}

          {/* Approval outcome donut: accent / ok / neutral segments, legend in
              ink with a coloured dot - colour is never the only cue. */}
          <OutcomeDonutTile
            pendingN={stats.approvals ?? 0}
            approvedN={approvalOutcomes.approved ?? 0}
            rejectedN={approvalOutcomes.rejected ?? 0}
          />

          {!solo && (
            <Card className="gap-3 p-5">
              <Eyebrow icon={<Users aria-hidden="true" />}>{t("seatsUsedLabel")}</Eyebrow>
              <div className="font-mono text-[1.75rem] font-semibold leading-none tabular-nums text-foreground">
                {seatPct}
                <span className="text-base text-muted-foreground">%</span>
              </div>
              <div
                className="h-1.5 overflow-hidden rounded-full"
                style={{ background: "var(--surface-sunken)" }}
                role="progressbar"
                aria-label={t("seatsUsedLabel")}
                aria-valuemin={0}
                aria-valuemax={100}
                aria-valuenow={seatPct}
              >
                <div
                  className="h-full rounded-full"
                  style={{ width: `${seatPct}%`, background: "var(--accent-500)" }}
                />
              </div>
              <div className="text-sm text-muted-foreground">
                {t("seatsUsedSub", { users: stats.users ?? "—", tierSeats: TIER_SEATS })}
              </div>
            </Card>
          )}

          <Card className="gap-3 p-5">
            <Eyebrow icon={<Radio aria-hidden="true" />}>{t("channelsLiveLabel")}</Eyebrow>
            <div className="flex items-center gap-4">
              <svg viewBox="0 0 36 36" className="size-14 shrink-0 -rotate-90" aria-hidden="true">
                <circle cx="18" cy="18" r="15.915" fill="none" stroke="var(--surface-sunken)" strokeWidth="4" />
                <circle
                  cx="18"
                  cy="18"
                  r="15.915"
                  fill="none"
                  stroke="var(--state-success)"
                  strokeWidth="4"
                  strokeDasharray={`${channelPct} ${100 - channelPct}`}
                  strokeDashoffset="25"
                  strokeLinecap="round"
                />
              </svg>
              <div className="min-w-0">
                <div className="font-mono text-[1.75rem] font-semibold leading-none tabular-nums text-foreground">
                  {connectedChannels}
                </div>
                <div className="mt-1 text-sm text-muted-foreground">
                  {t("channelsLiveSub", { count: totalChannels })}
                </div>
              </div>
            </div>
          </Card>
        </div>

        {/* ROW 2 - system readouts | workspace pulse hero | live audit tail. */}
        <div className="grid min-w-0 gap-4 lg:grid-cols-[minmax(0,17rem)_minmax(0,1fr)_minmax(0,22rem)]">
          <SystemHealthCard systemStatus={systemStatus} firstRun={firstRun} />

          {/* Workspace pulse hero - the same CognitionCore canvas the tenant
              Dashboard uses. Every child is position:absolute, so the panel
              carries its own min-height instead of relying on a grid row.
              Surfaces the health verdict and LlmMetricsAdvisor's real
              call / latency / error totals, which row 1 doesn't repeat. */}
          <PulseHero llm={llm} overall={overall} />

          {/* Live audit tail + approval-resolution footer. The footer's real,
              non-duplicated value is avgApprovalSeconds (how long approvals
              actually take to resolve), not another restatement of the
              pending count. */}
          <Card className="gap-0 overflow-hidden p-0" data-testid="admin-audit-tail">
            <div className="flex items-center justify-between gap-3 px-5 pb-3 pt-5">
              <CardHeading>
                <span className="inline-flex items-center gap-2">
                  <Activity aria-hidden="true" className="size-4 text-muted-foreground" />
                  {tHC("s2")}
                </span>
              </CardHeading>
              <CardLink href="/admin/audit-tail">{tHC("s1")}</CardLink>
            </div>
            <AdminEventTail />
            <div className="flex items-center gap-3 border-t border-border px-5 py-3">
              <span className="font-mono text-2xl font-semibold tabular-nums text-foreground">
                {avgResolveMinutes ?? "—"}
              </span>
              <div className="min-w-0 flex-1">
                <div className="text-sm font-medium text-foreground">{t("avgResolveMinutesLabel")}</div>
                <div className="text-xs text-muted-foreground">
                  {avgResolveMinutes != null ? t("approvalsWaitingLabel") : t("noResolutionData")}
                </div>
              </div>
              <Button asChild variant="outline" size="sm">
                <Link href="/admin/maintenance">
                  Review
                  <ArrowRight aria-hidden="true" />
                </Link>
              </Button>
            </div>
          </Card>
        </div>

        {systemStatusError && (
          <p className="m-0 text-sm text-muted-foreground">
            {t("systemTelemetryUnavailable", { error: systemStatusError })}
          </p>
        )}

        {/* ROW 3 - pending approvals | connected channels. */}
        <PendingAndChannelsRow pendingApprovals={pendingApprovals} platforms={platforms} />
      </div>
    </AppShell>
  );
}
