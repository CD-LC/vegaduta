"use client";

import { ArrowUpRight as ArrowUpRightIcon } from "lucide-react";
import type { TenantSubscriptionResponse } from "../../lib/api";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card } from "../../components/ui/card";
import { useTranslations } from "../../lib/i18n";
import { FREE_MODE } from "../../lib/freeMode";
import { UsageBar, type TenantBillingSettings } from "./billingShared";

interface PlanCardProps {
  plan: string;
  planLabel: string;
  price: string;
  blurbKey: string;
  subscription: TenantSubscriptionResponse | null;
  trialValid: boolean;
  trialDaysLeft: number | null;
  trialEndDate: string;
  canUpgrade: boolean;
  canCancel: boolean;
  onUpgrade: () => void;
  onCancel: () => void;
}

/** Current plan: accent hero card carrying the page's single solid-primary action. */
export function PlanCard(p: PlanCardProps) {
  const t = useTranslations("adminBillingPage");
  const { subscription } = p;
  return (
    <Card className="mb-0 gap-0 border-[var(--accent-200)] bg-[var(--accent-50)] p-6">
      <div className="flex flex-wrap items-center justify-between gap-2.5">
        <span className="font-mono text-[0.68rem] tracking-[0.12em] text-[var(--accent-700)]">
          {t("currentPlanBadge")}
        </span>
        {subscription?.pastDue ? (
          <Badge
            variant="outline"
            className="border-[var(--danger-border)] bg-[var(--danger-bg)] text-xs text-[var(--danger-text)]"
          >
            {t("statusPastDue")}
          </Badge>
        ) : subscription?.cancelScheduled ? (
          <Badge
            variant="outline"
            className="border-[var(--warning-border)] bg-[var(--warning-bg)] text-xs text-[var(--warning-text)]"
          >
            {t("statusCancelScheduled")}
          </Badge>
        ) : (
          <Badge
            variant="outline"
            className="border-[var(--success-border)] bg-[var(--success-bg)] text-xs text-[var(--success-text)]"
          >
            {t("statusActive")}
          </Badge>
        )}
      </div>
      <div className="mt-3 flex items-baseline gap-2.5">
        <span className="text-[1.75rem] font-bold tracking-tight text-foreground">{p.planLabel}</span>
        <span className="font-mono text-[0.95rem] text-muted-foreground">{p.price}</span>
      </div>
      <p className="mb-0 mt-2 text-[0.8rem] leading-relaxed text-muted-foreground">
        {t(FREE_MODE ? "planBlurbFreeMode" : p.blurbKey)}
      </p>
      {!FREE_MODE && p.trialValid && p.trialDaysLeft !== null && p.trialDaysLeft >= 0 && (
        <div
          role="status"
          data-testid="billing-trial-note"
          className="mt-3 rounded-[10px] border border-[var(--success-border)] bg-[var(--success-bg)] px-3 py-2.5"
        >
          <p className="m-0 text-[0.8rem] font-semibold text-[var(--success-text)]">{t("trialActiveTitle")}</p>
          <p className="mb-0 mt-1 text-[0.78rem] leading-relaxed text-muted-foreground">
            {t(p.trialDaysLeft <= 14 ? "trialActiveBodySoon" : "trialActiveBody", {
              date: p.trialEndDate,
              days: p.trialDaysLeft,
            })}
          </p>
        </div>
      )}
      {!FREE_MODE && !p.trialValid && p.plan === "FREE" && (
        <p
          className="mb-0 mt-2.5 text-[0.78rem] leading-relaxed text-muted-foreground"
          data-testid="billing-trial-ended"
        >
          {t("trialFreeNote")}
        </p>
      )}
      <div className="mt-6 flex flex-wrap items-center gap-3">
        {p.canUpgrade ? (
          <Button type="button" className="mb-0" onClick={p.onUpgrade}>
            <ArrowUpRightIcon width={14} height={14} className="me-1" aria-hidden="true" />
            {t("upgradePlanButton")}
          </Button>
        ) : (
          // Free mode: "you're on the top tier" would be wrong for a stored-FREE row.
          <span className="text-[0.8rem] text-muted-foreground">
            {t(FREE_MODE ? "freeModePlanNote" : "topTierMessage")}
          </span>
        )}
        {p.canCancel && (
          <Button type="button" variant="ghost" size="sm" className="mb-0" onClick={p.onCancel}>
            {t("cancelSubscriptionButton")}
          </Button>
        )}
      </div>
      {subscription?.cancelScheduled && (
        <p className="mb-0 mt-2.5 text-xs leading-relaxed text-muted-foreground">{t("cancelScheduledNote")}</p>
      )}
    </Card>
  );
}

interface UsageCardProps {
  settings: TenantBillingSettings | null;
  usersCount: number | null;
  agentsCount: number | null;
  workflowsCount: number | null;
  showUpgrade: boolean;
  onUpgrade: () => void;
}

/** Real counts vs real tier caps; the inline upgrade button shows at 80%+ of any cap. */
export function UsageCard({
  settings,
  usersCount,
  agentsCount,
  workflowsCount,
  showUpgrade,
  onUpgrade,
}: UsageCardProps) {
  const t = useTranslations("adminBillingPage");
  return (
    <Card className="mb-0 gap-0 p-6">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <h2 className="m-0 font-mono text-[0.68rem] font-normal tracking-[0.1em] text-muted-foreground">
          {t("usageSectionTitle")}
        </h2>
        {showUpgrade && (
          <Button type="button" variant="outline" size="sm" onClick={onUpgrade} className="mb-0">
            <ArrowUpRightIcon width={12} height={12} aria-hidden="true" />
            {t("upgradePlanButton")}
          </Button>
        )}
      </div>
      <div className="flex flex-col gap-4">
        <UsageBar label={t("usageTeamSeats")} used={usersCount} cap={settings?.maxUsers} />
        <UsageBar label={t("usageAgents")} used={agentsCount} cap={settings?.maxAgents} />
        <UsageBar label={t("usageWorkflows")} used={workflowsCount} cap={settings?.maxWorkflows} />
      </div>
      <p className="mb-0 mt-4 text-[0.72rem] text-muted-foreground">{t("usageMeteringHint")}</p>
    </Card>
  );
}
