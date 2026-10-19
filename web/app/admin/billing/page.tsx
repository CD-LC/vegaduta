"use client";

import { useCallback, useEffect, useState } from "react";
import {
  CreditCard as CreditCardIcon,
  Receipt as ReceiptPercentIcon,
  Landmark as BuildingLibraryIcon,
  FileText as DocumentTextIcon,
  AlertTriangle as ExclamationTriangleIcon,
} from "lucide-react";
import { api } from "../../lib/api";
import type { TenantReferralResponse, TenantSubscriptionResponse, TenantInvoiceSummary } from "../../lib/api";
import { useRequireScope } from "../../components/useRequireScope";
import AppShell from "../../components/AppShell";
import { usePageReady } from "../../components/RouteTransition";
import PageHeader from "../../components/ax/PageHeader";
import UpgradePrompt from "../../components/UpgradePrompt";
import UpgradeCelebration from "../../components/UpgradeCelebration";
import LlmSpendSection from "../../components/billing/LlmSpendSection";
import { Button } from "../../components/ui/button";
import { Card } from "../../components/ui/card";
import { Skeleton } from "../../components/ui/skeleton";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../../components/ui/alert-dialog";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import { FREE_MODE } from "../../lib/freeMode";
import { errorMessage } from "../../lib/errors";
import LearnThisButton from "../../learn/LearnThisButton";
import { limitRatio, NEAR_LIMIT_RATIO, PLAN_INFO, ProfileRow, type TenantBillingSettings } from "./billingShared";
import { PlanCard, UsageCard } from "./PlanUsageCards";
import ReferralCard from "./ReferralCard";
import InvoicesCard from "./InvoicesCard";
import BillingProfileForm from "./BillingProfileForm";
import SupportTicketCard from "./SupportTicketCard";

// Tenant-admin "Billing & usage" screen (#968). NOT web/app/billing/page.js,
// which is the PUBLIC pre-signup pricing page. This is the authenticated,
// in-console counterpart: a tenant admin looking at their OWN workspace's plan
// and usage (same source admin/page.js's SEATS USED / AGENTS DEPLOYED use).
//
// This codebase has a hard rule against shipping fabricated data as if it were
// real, and there is no backend model for payment methods or billing contacts,
// so those are consolidated into ONE quiet "billing profile" panel of honest
// empty-state rows. Exactly one solid-primary action on the page (Upgrade plan,
// in the plan card); usage bars are state-coloured (accent / amber at 80% /
// red at the cap) so colour only ever means something.
//
// The page owns data loading and the dialogs; each card lives in its own file
// next to this one (PlanUsageCards, ReferralCard, InvoicesCard,
// BillingProfileForm, SupportTicketCard, billingShared).
export default function AdminBillingPage() {
  const t = useTranslations("adminBillingPage");
  const x = useTranslations("adminBillingExtra");

  // MANAGE_BILLING scope (granular RBAC, 2026-09-23): a delegated finance user
  // can reach billing without any other admin surface.
  const allowed = useRequireScope("MANAGE_BILLING");
  const [settings, setSettings] = useState<TenantBillingSettings | null>(null);
  const [usersCount, setUsersCount] = useState<number | null>(null);
  const [agentsCount, setAgentsCount] = useState<number | null>(null);
  const [workflowsCount, setWorkflowsCount] = useState<number | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [upgradeOpen, setUpgradeOpen] = useState(false);
  const [loaded, setLoaded] = useState(false);
  // Post-upgrade celebration - driven by the ?upgraded=<plan> contract (see
  // UpgradeCelebration's header comment). PREMIUM/UNLIMITED only.
  const [celebrationPlan, setCelebrationPlan] = useState<string | null>(null);

  const plan = settings?.plan || "FREE";
  const info = PLAN_INFO[plan as keyof typeof PLAN_INFO] || PLAN_INFO.FREE;
  const planLabel = t(info.labelKey);
  // Free mode (lib/freeMode.ts): nothing is behind a paywall, so the upgrade
  // affordances are absent.
  const canUpgrade = !FREE_MODE && plan !== "UNLIMITED";
  const nearAnyLimit =
    limitRatio(usersCount, settings?.maxUsers) >= NEAR_LIMIT_RATIO ||
    limitRatio(agentsCount, settings?.maxAgents) >= NEAR_LIMIT_RATIO ||
    limitRatio(workflowsCount, settings?.maxWorkflows) >= NEAR_LIMIT_RATIO;

  // Wave-2 monetization surfaces (audit D3/D5/D6). All reads are DEFENSIVE: a
  // null means "endpoint unavailable on this core" and the section does not
  // render. No fabricated placeholders.
  const [subscription, setSubscription] = useState<TenantSubscriptionResponse | null>(null);
  // The signup trial is a complimentary Unlimited grant (core: comp_plan_expires_at),
  // so its end date is the date the workspace drops to Free.
  const trialEndsAt = subscription?.compPlanExpiresAt ? new Date(subscription.compPlanExpiresAt) : null;
  const trialValid = trialEndsAt !== null && !Number.isNaN(trialEndsAt.getTime()) && trialEndsAt.getTime() > Date.now();
  const trialDaysLeft = trialValid && trialEndsAt ? Math.ceil((trialEndsAt.getTime() - Date.now()) / 86_400_000) : null;
  const trialEndDate =
    trialValid && trialEndsAt
      ? trialEndsAt.toLocaleDateString(undefined, { day: "numeric", month: "long", year: "numeric" })
      : "";
  const [referral, setReferral] = useState<TenantReferralResponse | null>(null);
  const [invoices, setInvoices] = useState<TenantInvoiceSummary[] | null>(null);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [cancelling, setCancelling] = useState(false);

  // Detect a confirmed-upgrade return (?upgraded=PREMIUM|UNLIMITED) once on mount.
  useEffect(() => {
    const raw = new URLSearchParams(window.location.search).get("upgraded");
    const p = raw ? raw.toUpperCase() : null;
    if (p === "PREMIUM" || p === "UNLIMITED") setCelebrationPlan(p);
  }, []);

  // Dismiss once and strip ?upgraded from the URL so a refresh never replays it.
  const dismissCelebration = useCallback(() => {
    setCelebrationPlan(null);
    window.history.replaceState(null, "", window.location.pathname);
  }, []);

  const refresh = useCallback(async () => {
    setLoadError(null);
    try {
      setSettings(await api.getTenantSettings());
    } catch (err) {
      setLoadError(errorMessage(err, t("loadErrorFallback")));
    }
    try {
      const users = await api.listAdminUsers();
      setUsersCount(users.length);
    } catch {
      setUsersCount(null);
    }
    try {
      const agents = await api.listAgents();
      setAgentsCount(agents.length);
    } catch {
      setAgentsCount(null);
    }
    try {
      const workflows = await api.listWorkflows();
      setWorkflowsCount(workflows.length);
    } catch {
      setWorkflowsCount(null);
    }
    // Independent Promise.allSettled so one missing endpoint (older core) never
    // delays or blanks the others. Failure -> null -> that section hides itself.
    const [subRes, refRes, invRes] = await Promise.allSettled([
      api.getTenantSubscription(),
      api.getTenantReferral(),
      api.getTenantInvoices(),
    ]);
    setSubscription(subRes.status === "fulfilled" && subRes.value ? subRes.value : null);
    setReferral(refRes.status === "fulfilled" && refRes.value?.code ? refRes.value : null);
    if (invRes.status === "fulfilled") {
      // Accept either a bare array or an { invoices: [...] } envelope - the
      // exact wire shape isn't backend-confirmed yet (see api.ts).
      const raw = invRes.value;
      setInvoices(Array.isArray(raw) ? raw : Array.isArray(raw?.invoices) ? raw.invoices : null);
    } else {
      setInvoices(null);
    }
    setLoaded(true);
  }, [t]);

  useEffect(() => {
    if (allowed) void refresh();
  }, [allowed, refresh]);
  // Non-scoped callers redirect to "/" (return null below) -> ready immediately.
  usePageReady(loaded || !allowed);

  // D5 - cancel-at-cycle-end. The dialog stays open while the request runs.
  const confirmCancel = useCallback(async () => {
    setCancelling(true);
    try {
      await api.cancelTenantSubscription();
      toast.success(t("cancelScheduledToast"));
      setCancelOpen(false);
      await refresh();
    } catch (err) {
      toast.error(errorMessage(err, t("cancelErrorToast")));
    }
    setCancelling(false);
  }, [refresh, t]);

  // D6 - the invoice HTML must be fetched with the bearer token (a plain
  // window.open would arrive unauthenticated), then shown as a blob URL.
  const openInvoice = useCallback(
    async (invoiceId: string) => {
      try {
        const html = await api.getTenantInvoiceHtml(invoiceId);
        const url = URL.createObjectURL(new Blob([html], { type: "text/html" }));
        window.open(url, "_blank", "noopener");
        setTimeout(() => URL.revokeObjectURL(url), 60000);
      } catch (err) {
        toast.error(errorMessage(err, t("invoiceOpenError")));
      }
    },
    [t]
  );

  // Cancel only makes sense on a real paid subscription not already winding down.
  const canCancel = plan !== "FREE" && !!subscription && !subscription.cancelScheduled;

  if (!allowed) return null;

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      <PageHeader
        title={t("pageTitle")}
        subtitle={
          <>
            {x("pageSubtitle")}{" "}
            <span className="whitespace-nowrap font-mono text-xs">
              {t("workspaceLabel")} <span className="text-foreground">{settings?.organizationName || "—"}</span>
            </span>
          </>
        }
        actions={<LearnThisButton path="/admin/billing" />}
      />

      {loadError && (
        <div
          role="alert"
          className="error-box mb-4 flex flex-wrap items-center justify-between gap-3"
          data-testid="billing-load-error"
        >
          <span>{loadError}</span>
          <Button type="button" variant="outline" size="sm" className="mb-0" onClick={() => void refresh()}>
            {x("retry")}
          </Button>
        </div>
      )}

      {/* D5 - dunning banner. Only shown when the subscription endpoint itself reports pastDue. */}
      {subscription?.pastDue && (
        <div
          role="alert"
          className="mb-4 flex items-start gap-2.5 rounded-xl border border-[var(--ax-amber)] bg-[color-mix(in_srgb,var(--ax-amber)_12%,transparent)] px-4 py-3 text-[0.85rem] text-foreground"
        >
          <ExclamationTriangleIcon
            width={16}
            height={16}
            className="mt-0.5 shrink-0 text-[var(--ax-amber)]"
            aria-hidden="true"
          />
          <span>{t("pastDueBanner")}</span>
        </div>
      )}

      {!loaded ? (
        <div className="ax-bill-plan-grid mb-4 grid gap-4" aria-busy="true" aria-label={x("loading")}>
          <Skeleton className="h-52 w-full rounded-xl" />
          <Skeleton className="h-52 w-full rounded-xl" />
        </div>
      ) : (
        <div className="ax-bill-plan-grid mb-4 grid gap-4">
          <PlanCard
            plan={plan}
            planLabel={planLabel}
            price={info.price}
            blurbKey={info.blurbKey}
            subscription={subscription}
            trialValid={trialValid}
            trialDaysLeft={trialDaysLeft}
            trialEndDate={trialEndDate}
            canUpgrade={canUpgrade}
            canCancel={canCancel}
            onUpgrade={() => setUpgradeOpen(true)}
            onCancel={() => setCancelOpen(true)}
          />
          <UsageCard
            settings={settings}
            usersCount={usersCount}
            agentsCount={agentsCount}
            workflowsCount={workflowsCount}
            showUpgrade={canUpgrade && nearAnyLimit}
            onUpgrade={() => setUpgradeOpen(true)}
          />
        </div>
      )}

      {/* AI model spend: per-provider cost, today's spend, model mix and the
          full call log. The FREE-plan locked state reuses this page's UpgradePrompt. */}
      <LlmSpendSection canUpgrade={canUpgrade} onUpgrade={() => setUpgradeOpen(true)} />

      {/* D3 - renders ONLY when the referral endpoint answered with a code. */}
      {referral && <ReferralCard referral={referral} onRedeemed={refresh} />}

      {/* D6 - invoices + billing profile (GSTIN). Renders ONLY once the invoices
          endpoint exists (even with zero rows - that signals this core has GST
          invoicing, so the profile form is meaningful). */}
      {invoices !== null && (
        <InvoicesCard invoices={invoices} onOpenInvoice={(id) => void openInvoice(id)}>
          <BillingProfileForm />
        </InvoicesCard>
      )}

      {/* Payment method / billing contact / tax / invoice history: honest empty
          states. The tax and invoice-history rows yield to the real D6 card above. */}
      <Card className="mb-4 gap-0 overflow-hidden p-0">
        <ProfileRow first icon={CreditCardIcon} title={t("paymentMethodTitle")} note={t("paymentMethodNote")} />
        <ProfileRow icon={BuildingLibraryIcon} title={t("billingContactTitle")} note={t("billingContactNote")} />
        {invoices === null && <ProfileRow icon={ReceiptPercentIcon} title={t("taxTitle")} note={t("taxNote")} />}
        {invoices === null && (
          <ProfileRow icon={DocumentTextIcon} title={t("invoiceHistoryTitle")} note={t("invoiceHistoryNote")} />
        )}
      </Card>

      <SupportTicketCard
        settings={settings}
        planLabel={planLabel}
        planPrice={info.price}
        usersCount={usersCount}
        agentsCount={agentsCount}
        workflowsCount={workflowsCount}
      />

      <UpgradePrompt
        open={upgradeOpen}
        onClose={() => setUpgradeOpen(false)}
        reason={t("upgradeReason", { plan: planLabel })}
      />

      <UpgradeCelebration open={!!celebrationPlan} plan={celebrationPlan ?? ""} onDismiss={dismissCelebration} />

      {/* D5 - cancel confirmation. preventDefault keeps Radix from closing the
          dialog before the request settles; confirmCancel closes it on success. */}
      <AlertDialog open={cancelOpen} onOpenChange={(open) => !cancelling && setCancelOpen(open)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("cancelDialogTitle")}</AlertDialogTitle>
            <AlertDialogDescription>{t("cancelDialogBody", { plan: planLabel })}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={cancelling}>{t("cancelDialogKeep")}</AlertDialogCancel>
            <AlertDialogAction
              disabled={cancelling}
              onClick={(e) => {
                e.preventDefault();
                void confirmCancel();
              }}
            >
              {cancelling ? t("cancellingButton") : t("cancelDialogConfirm")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </AppShell>
  );
}
