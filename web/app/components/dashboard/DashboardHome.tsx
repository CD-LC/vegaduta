"use client";

// The signed-in member dashboard - the body of the "/" route. This component
// WAS app/page.tsx verbatim until 2026-09-17; it moved here, unchanged apart
// from its import paths and name, so that app/page.tsx can load it through
// next/dynamic. See the comment block in app/page.tsx for why: "/" is also the
// anonymous landing page, and while this lived in the route module its whole
// static closure (AppShell -> EdgeSetupChip -> lib/edge/*, CognitionCore,
// Tilt3d, page-kit, Tour*, ...) was downloaded by every anonymous visitor
// before AuthProvider swapped in the landing. Nothing about the dashboard's
// own behaviour changed: usePageReady(loaded || isAdmin), the admin redirect,
// the 30s telemetry tick and the visibility handling are all as they were.
//
import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { api, API_BASE } from "../../lib/api";
import { useAuth } from "../AuthProvider";
import AppShell from "../AppShell";
import { usePageReady } from "../RouteTransition";
import CognitionCore from "./CognitionCore";
import AdminEventTail from "./AdminEventTail";
import ValueReceiptCard from "./ValueReceiptCard";
import InsightsTeaser from "../../insights/InsightsTeaser";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import PageHeader from "../ax/PageHeader";
import { Section, StatCard } from "../ax/page-kit";
import Tilt3d from "../ax/Tilt3d";
import { Card } from "../ui/card";
import FreePlanIncludes from "../FreePlanIncludes";
import { isBillingEnabled } from "../../lib/billing-flag";
import { useFirstRunBuildRoute } from "../tour/firstRun";
import ResearchEntryCard from "../research/ResearchEntryCard";
import StartHereCard from "../../start/StartHereCard";
import { Bot, Zap, Coins, Radio, ShieldAlert, LayoutTemplate } from "lucide-react";
import { errorMessage } from "../../lib/errors";
import { sharedPendingApprovalCount, sharedPendingApprovals, sharedSystemStatus } from "../../lib/sharedPoll";
import { approvalsBreakdown, fmtUptime, fmtUsd, kafkaTooltip, modelArcs } from "./dashboardFormat";
import LatencyRoutingRow from "./LatencyRoutingRow";
import ActivityRunsRow from "./ActivityRunsRow";

// AXON Dashboard - "Cognition Cockpit" (design_handoff_axon/design_files/
// AXON Dashboard.dc.html), the design handoff's flagship/reference console
// screen. Ported from the handoff's exact grid (5-tile telemetry strip |
// gauge stack + Cognition Core hero + live event tail | agent activity +
// workflow runs) using the --ax-* tokens and .ax-* classes added to
// globals.css for this rebuild.
//
// What's real vs illustrative, stated plainly (same "no fabricated claims"
// posture as SystemStatusPanel.js elsewhere in this app) - updated 2026-07-11
// after direct feedback ("is this real data? ... expectation is to really
// plug the live data"):
//
// REAL now: agent/workflow names+status (bottom row, unchanged); LLM latency
// percentiles, model routing split, cost today/per-conversation, calls-24h,
// conversations-today, and the "tokens/min recent" reading (SystemStatusController
// #llmTelemetry -> LlmDashboardMetrics, computed from the llm_call_log table
// via LlmCallLogService#dashboardMetrics - see that class's javadoc for the
// exact window each field covers); Kafka connection status and JVM process
// uptime (SystemStatusController#get, already-real fields that just weren't
// wired into this screen before); the CHANNELS LIVE dots (ChannelStatusController,
// same aggregate the Channels screen itself uses); the approvals count and
// breakdown (GET /api/approvals?status=PENDING); and the Live Event Tail,
// which now polls the real audit log via AdminEventTail (same component/data
// source as /admin/audit-tail) instead of replaying eight canned sample lines.
//
// FORMERLY ILLUSTRATIVE, NOW CUT (hierarchy pass, 2026-07-20): the
// RETRIEVAL avg-similarity tile, GUARDRAILS PASS rate, CONTEXT WINDOW %,
// CSAT ("LLM-judged" - no judging pipeline exists), and each agent's
// per-row "confidence score" were all hardcoded prototype numbers with no
// backing data. Rather than keep shipping sample figures on the flagship
// screen, they are removed; if retrieval-similarity tracking, a guardrail
// pass/fail counter or a CSAT pipeline get built, those tiles can return
// as real metrics. The freed space promotes the one genuinely actionable
// item - pending approvals - to the top of the gauge column, carrying the
// page's single primary CTA (previously an easy-to-miss footer strip).
export default function DashboardHome() {
  const router = useRouter();
  const t = useTranslations("dashboard");
  const { isAdmin } = useAuth();
  const [agents, setAgents] = useState([]);
  const [workflows, setWorkflows] = useState([]);
  const [coreReachable, setCoreReachable] = useState(true);
  const [loadError, setLoadError] = useState(null);
  const [loaded, setLoaded] = useState(false);

  const [llm, setLlm] = useState(null);
  const [systemStatus, setSystemStatus] = useState(null);
  const [channelStatus, setChannelStatus] = useState(null);
  const [pendingApprovals, setPendingApprovals] = useState([]);
  // Real pending total (X-Total-Count); the list above is capped server-side.
  const [pendingTotal, setPendingTotal] = useState<number | null>(null);
  // TenantSettingsResponse#plan - only read to decide whether the first-run
  // card's "included free" list is a true statement for this tenant. One
  // best-effort fetch (not on the 30s telemetry tick - a plan doesn't change
  // while you watch); null = unknown, and unknown renders nothing.
  const [tenantPlan, setTenantPlan] = useState<string | null>(null);

  useEffect(() => {
    if (isAdmin) router.replace("/admin");
  }, [isAdmin, router]);

  useEffect(() => {
    if (isAdmin) return;
    api
      .getTenantSettings()
      .then((s: { plan?: string | null }) => setTenantPlan(s?.plan ?? null))
      .catch(() => setTenantPlan(null));
  }, [isAdmin]);

  const refresh = useCallback(async () => {
    try {
      const [agentList, workflowList] = await Promise.all([api.listAgents(), api.listWorkflows()]);
      setCoreReachable(true);
      setLoadError(null);
      setAgents(agentList);
      setWorkflows(workflowList);
    } catch (err) {
      setCoreReachable(false);
      setLoadError(errorMessage(err));
    } finally {
      setLoaded(true);
    }
  }, []);

  // Separate from the agents/workflows fetch above on purpose: none of these
  // four telemetry sources should be able to blank the whole page (or flip
  // the "core unreachable" banner) just because one of them fails - each
  // degrades independently to its own "no data" state instead.
  const refreshTelemetry = useCallback(async () => {
    const results = await Promise.allSettled([
      api.getLlmTelemetry(),
      sharedSystemStatus(),
      api.getChannelsStatus(),
      sharedPendingApprovals(),
      sharedPendingApprovalCount(),
    ]);
    if (results[0].status === "fulfilled") setLlm(results[0].value);
    if (results[1].status === "fulfilled") setSystemStatus(results[1].value);
    if (results[2].status === "fulfilled") setChannelStatus(results[2].value);
    if (results[3].status === "fulfilled") setPendingApprovals(results[3].value);
    if (results[4].status === "fulfilled") setPendingTotal(results[4].value);
  }, []);

  useEffect(() => {
    // `void`: both settle their own errors into state (see refresh /
    // refreshTelemetry above), so there is nothing left to await here.
    void refresh();
    void refreshTelemetry();
    // Efficient background processing (modern-web-guidance): a hidden tab
    // skips telemetry ticks entirely (four API calls per tick otherwise
    // continue for as long as the tab exists), and coming back to the tab
    // refreshes immediately so the numbers are never 30s staler than the
    // moment the user is actually looking.
    const id = setInterval(() => {
      if (!document.hidden) void refreshTelemetry();
    }, 30000);
    const onVisibility = () => {
      if (!document.hidden) void refreshTelemetry();
    };
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      clearInterval(id);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [refresh, refreshTelemetry]);

  // Admins are redirected to /admin (return null below), so they're "ready"
  // immediately; everyone else holds the cover until agents/workflows load.
  usePageReady(loaded || isAdmin);

  // A tenant with no agents AND no envoys goes to the Build Center once; see
  // tour/firstRun.ts. Before the isAdmin bail-out below: hooks run every render.
  useFirstRunBuildRoute(loaded && coreReachable && !isAdmin ? agents.length : null);

  if (isAdmin) return null;

  const tooltip = kafkaTooltip(systemStatus, t);
  const breakdown = approvalsBreakdown(pendingApprovals);
  const arcs = modelArcs(llm);

  const kafkaStatus = systemStatus?.kafka?.status;
  const kafkaTone =
    kafkaStatus === "UP" ? "text-emerald-500" : kafkaStatus === "DOWN" ? "text-destructive" : "text-muted-foreground";

  // First-run state (funnel audit, 2026-09): agents have loaded from a
  // reachable core and there are none. A member with zero agents used to
  // meet "Cognition Cockpit", a mesh readout and a Quick Task button; the
  // only create-agent affordance was an EmptyState inside a deferred
  // below-the-fold section. While first-run is true the page leads with a
  // "Create your first agent" card, the header action becomes "Create
  // agent", and the activity/runs row is not deferred (so the EmptyState
  // is painted, not skipped by content-visibility).
  const firstRun = loaded && coreReachable && agents.length === 0;
  // Plan caps only mean something where plans exist (billing on).
  const isFreePlan = tenantPlan === "FREE" && isBillingEnabled();

  return (
    <AppShell eyebrow={t("homeEyebrow")}>
      {/* Was eyebrow "Cognition Cockpit" / title "Agentic AI Platform" -
          the product's name, not the page's; a member landing here got no
          plain-language answer to "where am I". */}
      <PageHeader
        eyebrow={t("homeEyebrow")}
        title={t("homeTitle")}
        subtitle={t("homeSubtitle")}
        actions={
          firstRun ? (
            <Button asChild size="sm">
              <Link href="/agents">
                <Bot className="size-4" aria-hidden="true" /> {t("createAgentCta")}
              </Link>
            </Button>
          ) : (
            <Button asChild variant="outline" size="sm">
              <Link href="/quick-task">
                <Zap className="size-4" aria-hidden="true" /> {t("quickTask")}
              </Link>
            </Button>
          )
        }
      />

      {!coreReachable && (
        <div className="mb-6 rounded-xl border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
          {t("errorCantReachPrefix", { apiBase: API_BASE })} <code className="font-mono">core</code>{" "}
          {t("errorCantReachSuffix", { error: loadError })}
        </div>
      )}

      <div className="space-y-6">
        {/* "Start here" (onboarding lane, 2026-10-04): a dismissible link to the
            guided /start flow, first-run only. Links, never redirects. */}
        {firstRun && <StartHereCard />}

        {/* First-run card - the same card /admin shows a tenant-admin, so the
            first post-login screen says the same thing regardless of role.
            data-tour="create-agent" lets the product tour's step 1 spotlight
            this CTA (see TourProvider's data-tour note). */}
        {firstRun && (
          <Card className="gap-0 p-6" data-testid="dashboard-first-run">
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
                </div>
              </div>
              {isFreePlan && <FreePlanIncludes compact className="lg:w-[24rem] lg:shrink-0" />}
            </div>
          </Card>
        )}

        <ResearchEntryCard />
        {/* ---- Telemetry tiles (all real; see the no-fabrication note above) ----
             Aurora depth pass (2026-08-12): each tile tilts in 3D under the
             pointer (Tilt3d - CSS transforms only, self-disabling on touch/
             reduced-motion) and carries a category-hued icon chip, so the
             strip reads as four distinct instruments instead of four grey
             rectangles. */}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <Tilt3d>
            <StatCard
              label={t("tokensPerMinLabel")}
              icon={Zap}
              accent
              cat="var(--cat-data)"
              value={
                llm?.tokensPerMinuteRecent != null ? Math.round(llm.tokensPerMinuteRecent).toLocaleString("en-US") : "—"
              }
              sub={llm?.tokensPerMinuteRecent != null ? t("tokensPerMinSubActive") : t("tokensPerMinSubEmpty")}
            />
          </Tilt3d>
          <Tilt3d>
            <StatCard
              label={t("costTodayLabel")}
              icon={Coins}
              cat="var(--cat-creative)"
              value={fmtUsd(llm?.costTodayUsd)}
              sub={`${fmtUsd(llm?.costPerConversationTodayUsd)}${t("costPerConvSuffix")}`}
            />
          </Tilt3d>
          <Tilt3d>
            <StatCard
              label={t("llmCalls24h")}
              icon={Bot}
              cat="var(--cat-assistant)"
              value={llm?.callsLast24h ?? "—"}
              sub={t("conversationsToday") + ": " + (llm?.conversationsToday ?? "—")}
            />
          </Tilt3d>
          <Tilt3d>
            <StatCard
              label={t("uptimeLabel")}
              icon={Radio}
              cat="var(--cat-comms)"
              value={fmtUptime(systemStatus?.jvm?.uptimeSeconds)}
            />
          </Tilt3d>
        </div>

        {/* D2-lite value receipt - hides itself entirely when the quota
            endpoint is unavailable or there's nothing to show. */}
        <ValueReceiptCard />

        {/* 7-day AI-calls teaser linking to /insights. Shows only to an admin or a
            VIEW_USAGE holder, and renders nothing at all when its data call
            fails, is refused, or there were no calls. */}
        <InsightsTeaser />

        {/* ---- Cognition Core hero + the one actionable column ---- */}
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
          {/* Bespoke data-viz kept: the CognitionCore canvas, framed in a clean
              Card - now ringed by the slowly-rotating aurora gradient border
              (.aurora-ring), marking it as the page's hero instrument. */}
          <Card className="aurora-ring relative min-h-[360px] overflow-hidden p-0 lg:col-span-2">
            <CognitionCore />
            <div className="pointer-events-none absolute inset-0 z-10 flex flex-col justify-between p-6">
              <div className="flex items-start justify-between">
                <div>
                  <div className="font-mono text-xs font-semibold uppercase tracking-[0.16em] text-primary">
                    {t("cognitionCoreLabel")}
                  </div>
                  <div className="mt-1 text-xs text-muted-foreground">{t("cognitionCoreSub")}</div>
                </div>
                <div className="text-right">
                  <div className="font-mono text-xs font-semibold uppercase tracking-[0.14em] text-foreground">
                    {t("agentsThinking", { count: agents.length })}
                  </div>
                  <div className="mt-1 text-xs text-muted-foreground">{t("agentsThinkingSub")}</div>
                </div>
              </div>
              <div className="text-center">
                <div className="text-gradient-accent text-4xl font-semibold tracking-tight tabular-nums">
                  {llm?.tokensPerMinuteRecent != null
                    ? Math.round(llm.tokensPerMinuteRecent).toLocaleString("en-US")
                    : "—"}
                </div>
                <div className="mt-1 font-mono text-xs uppercase tracking-[0.14em] text-muted-foreground">
                  {t("tokensAcrossMesh")}
                </div>
              </div>
            </div>
          </Card>

          {/* Gauge column stretches to exactly fill the hero's height (flex +
              flex-1 children) so no dead space trails under the third card. */}
          <div className="flex flex-col gap-4">
            {/* Pending approvals — the single most actionable item, carries the CTA. */}
            <Card
              className={
                (pendingApprovals.length > 0 ? "gap-3 border-amber-500/30 p-5" : "gap-3 p-5") +
                " card-interactive card-sheen flex-1 justify-center"
              }
            >
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">
                  {t("approvalsLabel")}
                </span>
                <ShieldAlert
                  className={
                    pendingApprovals.length > 0 ? "size-4 text-[var(--state-warning)]" : "size-4 text-muted-foreground"
                  }
                  aria-hidden="true"
                />
              </div>
              <div className="flex items-center gap-3">
                <span
                  className={
                    "text-3xl font-semibold tabular-nums " +
                    (pendingApprovals.length > 0 ? "text-[var(--state-warning)]" : "text-foreground")
                  }
                >
                  {pendingTotal ?? pendingApprovals.length}
                </span>
                <div className="min-w-0">
                  <div className="text-sm font-medium text-foreground">{t("approvalsWaiting")}</div>
                  <div className="truncate text-xs text-muted-foreground">{breakdown || t("nothingPending")}</div>
                </div>
              </div>
              <Button asChild size="sm" className="w-full">
                <Link href="/admin/maintenance">{t("review")}</Link>
              </Button>
            </Card>

            {/* Event bus / Kafka — real describeCluster() status + config summary.
                Admin-only (funnel audit, 2026-09): broker topics, consumer
                groups and DLQ depth are ops facts a tenant member can't act on
                (the Admin Overview has its own Kafka panel for the people who
                can). Since isAdmin visitors are redirected to /admin above,
                this card is effectively retired from the member dashboard
                while keeping the wiring for the day member/admin split
                changes. */}
            {isAdmin && (
              <Card className="flex-1 justify-center gap-3 p-5" title={tooltip}>
                <div className="flex items-center justify-between">
                  <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">
                    {t("kafkaLabel")}
                  </span>
                  <span className={"text-sm font-semibold " + kafkaTone}>
                    {kafkaStatus === "NOT_CONFIGURED" ? t("kafkaNotEnabled") : kafkaStatus || "…"}
                  </span>
                </div>
                {systemStatus?.kafka?.config && (
                  <div className="text-xs text-muted-foreground">
                    {t("kafkaConfigSummary", {
                      topic: systemStatus.kafka.config.topic,
                      partitions: systemStatus.kafka.config.partitions,
                      group: systemStatus.kafka.config.consumerGroup,
                    })}
                  </div>
                )}
                {systemStatus?.kafka?.queue && (
                  <div className="text-xs text-muted-foreground">
                    {t("kafkaQueueSummary", {
                      pending: systemStatus.kafka.queue.pendingCount,
                      dead: systemStatus.kafka.queue.deadLetteredCount,
                      dlq: systemStatus.kafka.dlq?.messageCount ?? "—",
                    })}
                  </div>
                )}
              </Card>
            )}

            {/* Channels live — real dot row (ChannelStatusController). */}
            <Card className="flex-1 justify-center gap-3 p-5">
              <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">
                {t("channelsLiveLabel")}
              </span>
              <div className="flex flex-wrap items-center gap-1.5">
                {channelStatus?.channels?.map((c: { key: string; label: string; state: string }) => (
                  <span
                    key={c.key}
                    title={`${c.label}: ${c.state}`}
                    className="inline-flex items-center gap-1.5 rounded-full border border-border/60 px-2 py-0.5"
                  >
                    <span
                      aria-hidden="true"
                      className={
                        "size-2 shrink-0 rounded-full " +
                        (c.state === "CONNECTED"
                          ? "bg-[var(--state-success)] shadow-[0_0_8px] shadow-[var(--state-success)]/70"
                          : c.state === "NOT_CONNECTED"
                            ? "bg-destructive/70"
                            : "bg-muted-foreground/40")
                      }
                    />
                    <span className="text-[11px] text-muted-foreground">{c.label}</span>
                  </span>
                ))}
                {!channelStatus && <span className="text-xs text-muted-foreground">{t("loadingEllipsis")}</span>}
              </div>
            </Card>
          </div>
        </div>

        {/* ---- Latency + model routing (bespoke gauges kept, framed in Cards) ----
             Everything from here down sits below the initial fold (header +
             tile strip + 360px hero fill the viewport), so these sections
             carry .defer-offscreen (content-visibility) per the deferred-
             rendering guidance - layout/paint for them is skipped until the
             user actually scrolls toward them. */}
        <LatencyRoutingRow llm={llm} modelArcs={arcs} />

        {/* ---- Agent activity | Workflow runs ----
             Deferred once there is an agent; see ActivityRunsRow for why not on first run. */}
        <ActivityRunsRow agents={agents} workflows={workflows} firstRun={firstRun} />

        {/* ---- Live event tail (bespoke feed kept, framed in a Card) ---- */}
        <Section title={t("liveEventTail")} className="defer-offscreen">
          <Card className="gap-0 p-0">
            <div className="flex items-center justify-between border-b border-border px-5 py-3">
              <span className="font-mono text-xs font-semibold uppercase tracking-[0.14em] text-muted-foreground">
                {t("liveEventTail")}
              </span>
              <span className="inline-flex items-center gap-1.5 text-xs font-medium text-[var(--state-success)]">
                <span className="size-1.5 rounded-full bg-[var(--state-success)]" aria-hidden="true" />
                {t("live")}
              </span>
            </div>
            <div className="p-4">
              <AdminEventTail limit={14} />
            </div>
          </Card>
        </Section>
      </div>
    </AppShell>
  );
}
