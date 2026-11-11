"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { RefreshCw, KeyRound, CreditCard } from "lucide-react";
import { api, API_BASE } from "../../lib/api";
import { useAuth } from "../../components/AuthProvider";
import AppShell from "../../components/AppShell";
import { usePageReady } from "../../components/RouteTransition";
import PageHeader from "../../components/ax/PageHeader";
import SystemStatusPanel from "../../components/dashboard/SystemStatusPanel";
import ConnectedPlatformsPanel from "../../components/dashboard/ConnectedPlatformsPanel";
import AdminUsersPanel, { type AdminUser } from "../../components/AdminUsersPanel";
import ApprovalsPanel from "../../components/ApprovalsPanel";
import DataPrivacySection from "../../components/DataPrivacySection";
import { Button } from "../../components/ui/button";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "../../components/ui/tabs";
import { useTranslations } from "../../lib/i18n";
import "../../components/dashboard/dashboardStyles";
import { llmSpendHref, isBillingEnabled } from "../../lib/billing-flag";
import { errorMessage } from "../../lib/errors";
import { channelOffered, isPersonalEdition } from "../../lib/edition";

// Same per-channel credential GETs the Dashboard and ChannelsPanel/
// SocialChannelsPanel already call (api.js) - every one resolves to null
// when nothing's activated yet, so "connected" is real, never guessed. Kept
// as its own copy (not imported from page.js, a client route file) rather
// than introducing a new shared module for one small array.
// labelKey (not a literal label) because this array is built at module
// scope, outside any component, so it can't call useTranslations() itself -
// the component resolves each key via t() when it builds `platforms` state.
interface PlatformStatus {
  key: string;
  label: string;
  connected: boolean | null;
}

const ALL_PLATFORM_CHECKS = [
  { key: "twilio", labelKey: "platformLabelTwilio", fetch: () => api.getTwilioCredential() },
  { key: "meta-whatsapp", labelKey: "platformLabelMetaWhatsapp", fetch: () => api.getMetaWhatsAppCredential() },
  { key: "meta-social", labelKey: "platformLabelMetaSocial", fetch: () => api.getMetaSocialCredential() },
  { key: "slack", labelKey: "platformLabelSlack", fetch: () => api.getSlackCredential() },
  { key: "discord", labelKey: "platformLabelDiscord", fetch: () => api.getDiscordCredential() },
  { key: "google", labelKey: "platformLabelGoogle", fetch: () => api.getGoogleConnection() },
  { key: "x", labelKey: "platformLabelX", fetch: () => api.getXCredential() },
];

// Only the platforms this install offers: a personal install runs no Slack, Discord or X adapter, so it neither
// lists them nor fires their credential GETs on every refresh. Read once at module load (lib/env.ts: the
// public-env head script runs before any client module).
const PLATFORM_CHECKS = ALL_PLATFORM_CHECKS.filter((p) => channelOffered(p.key));

// Direct response to "there should be some page for platform maintenance,
// only admin users can log in" - a dedicated, isAdmin-gated view of the same
// real telemetry the Dashboard's hero already surfaces (SystemStatusController
// + per-channel credential checks). Direct follow-up feedback: "remove this
// [Related administration card grid], capture any metrics like latency, how
// many LLM calls were made, and health of core and other microservices" -
// the quick-link cards to Audit Log/Channels/Integrations/Users are gone
// (still reachable via the main rail's ADMIN item), replaced by real
// LLM-call-count/latency (LlmCallMetricsService, fed by every ChatClient
// call via LlmMetricsAdvisor) and sidecar microservice health
// (a live GET probe against its own /health endpoint) - both
// now rendered as extra rows in SystemStatusPanel below. No invented
// "restart service" or "clear cache" buttons - there's no backend support
// for those actions today, and this page follows the same "real or
// explicitly absent, never fabricated" rule as every other status readout
// in this console.
//
// Direct follow-up feedback: "i want users and approvals section to be
// moved to platform maintenance" - AdminUsersPanel and ApprovalsPanel
// (previously each on their own standalone /admin/users and /admin/approvals
// route) now render below the telemetry deck on this page instead. The old
// routes still exist (they now just client-redirect here - see their own
// files' comments) so any bookmark/external link to them still lands
// somewhere real rather than 404ing. State management for the Users panel
// (the out-of-order-response guard via requestIdRef) is carried over
// unchanged from the old admin/users/page.js - same real bug it was
// originally added to fix (KeycloakAdminClient issues a fresh admin token
// per call, so two listAdminUsers() calls in flight can resolve out of
// order).
//
// Master Roadmap 2026 item #22 (GDPR/data-residency basics, §G) - "Request
// data export" / "Request account deletion" section near the bottom of this
// page. Deliberately NOT routed through app/lib/api.ts: that module is
// actively being migrated to TypeScript by another agent in this same
// session as of this task, so these two calls are implemented as small,
// local fetch helpers below instead - same `${API_BASE}${path}` + Bearer
// token shape api.ts's own internal apiFetch() uses (see auth.getToken()),
// just not routed through that shared module. `auth` (lib/auth.ts) and
// `API_BASE` (lib/api.ts) are both read-only imports of already-existing
// exports, not new additions to either file. English copy is hardcoded
// rather than added to the i18n key set for the same reason: this task's
// explicit boundary is this one page file, not the translation catalog.

export default function PlatformMaintenancePage() {
  const tHC = useTranslations("hcAdminMaintenancePage");
  const t = useTranslations("adminMaintenancePage");
  const router = useRouter();
  const { isAdmin, hasPermission } = useAuth();
  // A personal install is one person on one machine: there is no team to list or invite (/admin/users and
  // /admin/access are not served either), so the Users tab and its listAdminUsers() call are skipped.
  const showUsers = !isPersonalEdition();
  const [systemStatus, setSystemStatus] = useState(null);
  const [systemStatusError, setSystemStatusError] = useState(null);
  const [platforms, setPlatforms] = useState<PlatformStatus[]>(
    PLATFORM_CHECKS.map((p): PlatformStatus => ({ key: p.key, label: t(p.labelKey), connected: null }))
  );
  const [refreshing, setRefreshing] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [lastChecked, setLastChecked] = useState(null);

  // Direct follow-up feedback: reorganize this page's already-existing
  // sections (telemetry / users / approvals / data & privacy) into tabs
  // instead of one long stacked scroll. Purely a layout regrouping - every
  // panel below still renders the exact same props/markup it did before,
  // just inside a <TabsContent> instead of directly on the page. Default
  // tab is "overview" so a plain page load still lands on the telemetry
  // deck that used to be the top of the page.
  const [activeTab, setActiveTab] = useState("overview");

  // Users panel state - moved here verbatim from the old admin/users/page.js.
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [usersLoadError, setUsersLoadError] = useState(null);
  const [usersCoreReachable, setUsersCoreReachable] = useState(true);
  const requestIdRef = useRef(0);

  // GDPR data-export/deletion moved into <DataPrivacySection> (granular RBAC
  // 2026-09-23) so the identical section can also render on the scoped
  // /admin/data-privacy page for delegated MANAGE_DATA_GOVERNANCE holders.

  useEffect(() => {
    if (!isAdmin) router.replace("/");
  }, [isAdmin, router]);

  const refreshUsers = useCallback(async () => {
    const requestId = ++requestIdRef.current;
    try {
      const usersData = await api.listAdminUsers();
      if (requestId !== requestIdRef.current) return;
      setUsers(usersData);
      setUsersCoreReachable(true);
      setUsersLoadError(null);
    } catch (err) {
      if (requestId !== requestIdRef.current) return;
      setUsersCoreReachable(false);
      setUsersLoadError(errorMessage(err));
    }
  }, []);

  const handleUserCreated = useCallback((user: AdminUser) => {
    setUsers((prev) => [...prev, user]);
  }, []);

  const refresh = useCallback(async () => {
    setRefreshing(true);
    try {
      setSystemStatus(await api.getSystemStatus());
      setSystemStatusError(null);
    } catch (err) {
      setSystemStatus(null);
      setSystemStatusError(errorMessage(err));
    }
    // Independent, best-effort per platform - one channel erroring (e.g. X
    // requires BYOK app credentials first) must never blank out the other
    // rows' real connected/not status.
    const results = await Promise.all(
      PLATFORM_CHECKS.map(async (p) => {
        try {
          const value = await p.fetch();
          return { key: p.key, label: t(p.labelKey), connected: Boolean(value) };
        } catch {
          return { key: p.key, label: t(p.labelKey), connected: false };
        }
      })
    );
    setPlatforms(results);
    setLastChecked(new Date());
    setRefreshing(false);
    setLoaded(true);
  }, [t]);

  useEffect(() => {
    if (isAdmin) {
      void refresh();
      if (showUsers) void refreshUsers();
    }
  }, [isAdmin, showUsers, refresh, refreshUsers]);

  // Non-admins redirect to "/" (return null below) → ready immediately.
  usePageReady(loaded || !isAdmin);

  if (!isAdmin) return null;

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      <PageHeader
        title={t("pageTitle")}
        subtitle={
          <>
            {t("hintPrefix")}
            {lastChecked && t("hintLastChecked", { time: lastChecked.toLocaleTimeString() })}
          </>
        }
        actions={
          <>
            {/* Direct feedback: "there should be link to activate the providers
             * from platform maintenance" - LLM provider keys are workspace-wide
             * admin config (same class of setting as everything else on this
             * page), but activating one lives on its own dedicated /providers
             * screen (ProvidersPanel.js) rather than being duplicated here -
             * this is a direct shortcut to it. */}
            <Button asChild variant="outline" size="sm" className="rounded-full" style={{ marginBottom: 0 }}>
              <Link href="/providers">
                <KeyRound size={14} aria-hidden="true" /> {t("manageProvidersLink")}
              </Link>
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="rounded-full"
              onClick={() => {
                void refresh();
                if (showUsers) void refreshUsers();
              }}
              disabled={refreshing}
              title={t("refreshButtonTitle")}
              style={{ marginBottom: 0 }}
            >
              <RefreshCw size={14} className={refreshing ? "qt-spin" : undefined} aria-hidden="true" />{" "}
              {t("refreshButton")}
            </Button>
          </>
        }
      />

      {/* Direct follow-up feedback: reorganize this page's sections into
       * tabs. Each tab below wraps an existing block verbatim (same
       * component props, same markup, same className/data-testid/heading
       * text) - only the grouping is new. PageHeader above stays rendered
       * once, not duplicated per tab, and Refresh's behavior is unchanged -
       * it still refreshes everything regardless of which tab is active. */}
      <Tabs value={activeTab} onValueChange={setActiveTab}>
        <TabsList className="h-auto flex-wrap">
          <TabsTrigger value="overview" className="flex-none">
            Overview
          </TabsTrigger>
          {showUsers && (
            <TabsTrigger value="users" className="flex-none">
              Users
            </TabsTrigger>
          )}
          <TabsTrigger value="approvals" className="flex-none">
            Approvals
          </TabsTrigger>
          <TabsTrigger value="data-privacy" className="flex-none">
            {tHC("s1")}
          </TabsTrigger>
        </TabsList>

        <TabsContent value="overview">
          <div className="mc-status-deck">
            <SystemStatusPanel status={systemStatus} error={systemStatusError} />
            <ConnectedPlatformsPanel platforms={platforms} />
          </div>

          {/* LLM usage & cost used to render here (#606). It moved to
           * /admin/billing as components/billing/LlmSpendSection (direct
           * feedback, 2026-09-24: customers could not find it on an ops
           * screen). This pointer stays so an admin who remembers it being
           * here lands on the new home in one click. */}
          <div
            className="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border bg-secondary/20 px-4 py-3"
            data-testid="llm-usage-moved-note"
          >
            <span className="text-sm text-muted-foreground">
              {isBillingEnabled() ? t("llmUsageMovedNote") : t("llmUsageMovedNoteNoBilling")}
            </span>
            <Button asChild variant="outline" size="sm" className="rounded-full" style={{ marginBottom: 0 }}>
              <Link href={llmSpendHref()}>
                <CreditCard size={14} aria-hidden="true" />{" "}
                {isBillingEnabled() ? t("llmUsageMovedLink") : t("llmUsageMovedLinkNoBilling")}
              </Link>
            </Button>
          </div>
        </TabsContent>

        {showUsers && (
          <TabsContent value="users">
            {!usersCoreReachable && (
              <div className="error-box" style={{ marginTop: "1.25rem" }}>
                {t("usersApiUnreachablePrefix")}
                {API_BASE}
                {t("usersApiUnreachableSuffix", { error: usersLoadError })}
              </div>
            )}
            <AdminUsersPanel users={users} onChanged={refreshUsers} onCreated={handleUserCreated} />
          </TabsContent>
        )}

        <TabsContent value="approvals">
          {/* The prop is still named isAdmin but the panel only uses it to
           * show/hide the per-action policy-override form - under granular
           * RBAC the real question is the MANAGE_APPROVALS scope, which a
           * trimmed tenant-admin can lack. */}
          <ApprovalsPanel isAdmin={hasPermission("MANAGE_APPROVALS")} />
        </TabsContent>

        <TabsContent value="data-privacy">
          <DataPrivacySection />
        </TabsContent>
      </Tabs>
    </AppShell>
  );
}
