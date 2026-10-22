"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import {
  Gauge,
  KeyRound,
  Cpu,
  Sparkles,
  ArrowRight,
  Shield,
  Database,
  Route,
  SlidersHorizontal,
  ShieldCheck,
  CheckCircle2,
  CircleX,
  type LucideIcon,
} from "lucide-react";
import { api, type TenantUsageQuotaResponse, type SpendProtectionResponse } from "../lib/api";
import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import { usePageReady } from "../components/RouteTransition";
import { useAuth } from "../components/AuthProvider";
import { useTranslations } from "../lib/i18n";
import {
  usageFraction,
  usageLevel,
  quotaUsageFraction,
  upgradeHrefFor,
  type UsageLevel,
} from "../components/UsageQuota";
import { Card, CardContent } from "../components/ui/card";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Badge } from "../components/ui/badge";
import { Skeleton } from "../components/ui/skeleton";
import { Button } from "../components/ui/button";
import { EmptyState } from "../components/ax/page-kit";
import LlmSpendSection from "../components/billing/LlmSpendSection";
import { isBillingEnabled } from "../lib/billing-flag";
import { isPersonalEdition } from "../lib/edition";
import { hiddenOnPersonalInstall } from "../lib/personalInstall";
import SpendCapCard from "../components/SpendCapCard";
import BudgetTopUpCard from "./BudgetTopUpCard";

// Visible usage meter (item B3, docs/PRICING-MONETIZATION-AUDIT-2026-07-31.md
// §5.2) - the full-page view behind AppShell's compact topbar pill. Backed by
// GET /api/tenant/usage-quota (TenantUsageQuotaController, core/app), which
// reads the SAME Redis counters FreeTierUsageLimiterService enforces against
// - so what this page shows is what actually blocks the next call, not a
// parallel estimate. Every string goes through the "usage" i18n namespace
// (all keys present in messages/en.json - the t() fallback returns the raw
// key path on a miss, so no `t(...) || "text"` idiom anywhere).

function toneFor(level: UsageLevel): string {
  if (level === "over") return "var(--destructive)";
  if (level === "near") return "var(--upgrade)";
  return "var(--primary)";
}

function MeterRow({
  label,
  resetNote,
  used,
  limit,
  icon: Icon,
}: {
  label: string;
  resetNote: string;
  used: number;
  limit: number;
  icon: LucideIcon;
}) {
  const t = useTranslations("usage");
  const fraction = usageFraction(used, limit);
  const level = usageLevel(fraction);
  const tone = toneFor(level);
  // Bug fix (2026-08-06, live user report): a tenant whose usage exceeds its
  // limit (real and possible - the counter increments before the cap check,
  // so a retried/racing request can push `used` past `limit`)
  // rendered as e.g. "256% used" here, even though the bar's own width
  // (below) and its aria-valuenow were already clamped to 100. Clamping the
  // displayed percent the same way - the raw used/limit numbers themselves
  // stay honest and unclamped; only the derived "% used" text,
  // which reads as nonsensical past 100%, is capped.
  const percent = Math.min(100, Math.round(fraction * 100));
  const widthPct = Math.min(100, Math.max(0, fraction * 100));

  return (
    <div className="space-y-1.5">
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
        <span className="flex items-center gap-1.5 text-sm font-medium text-foreground">
          <Icon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          {label}
        </span>
        <span className="font-mono text-sm tabular-nums text-foreground">
          {t("usedOf", { used: used.toLocaleString(), limit: limit.toLocaleString() })}
          <span className="ml-2 text-xs text-muted-foreground">{t("percentUsed", { percent })}</span>
        </span>
      </div>
      <div
        className="h-2 w-full overflow-hidden rounded-full bg-[var(--surface-border)]"
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.min(100, percent)}
        aria-label={label}
      >
        <div
          className="h-full rounded-full transition-[width] duration-500"
          style={{ width: `${widthPct}%`, backgroundColor: tone }}
        />
      </div>
      <div className="text-xs text-muted-foreground">{resetNote}</div>
    </div>
  );
}

// "What's protecting your spend" transparency panel - additive section,
// backed by GET /api/tenant/spend-protection (TenantUsageQuotaController,
// core/app). Reuses the same Card/Badge components and MeterRow-adjacent
// styling as the meters above rather than introducing new visual language.
// Fetched independently of the quota above (own try/catch, never blocks
// usePageReady) - same "fails silently, never degrades the page" posture
// UsageQuota.tsx's useUsageQuota already uses.

function MechanismRow({
  icon: Icon,
  title,
  status,
}: {
  icon: typeof Shield;
  title: string;
  status: { active: boolean; description: string; detail: string | null };
}) {
  const t = useTranslations("usage");
  return (
    <div className="flex items-start gap-3">
      <span className="flex size-8 shrink-0 items-center justify-center rounded-lg border border-border bg-muted/40 text-muted-foreground">
        <Icon className="size-4" aria-hidden="true" />
      </span>
      <div className="min-w-0 flex-1 space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="text-sm font-semibold text-foreground">{title}</h3>
          <Badge
            variant="outline"
            className="gap-1"
            style={{
              color: status.active ? "var(--primary)" : "var(--text-muted)",
              borderColor: `color-mix(in srgb, ${status.active ? "var(--primary)" : "var(--text-muted)"} 35%, transparent)`,
            }}
          >
            {status.active ? (
              <CheckCircle2 className="size-3" aria-hidden="true" />
            ) : (
              <CircleX className="size-3" aria-hidden="true" />
            )}
            {status.active ? t("protectionActive") : t("protectionInactive")}
          </Badge>
        </div>
        <p className="text-sm leading-relaxed text-muted-foreground">{status.description}</p>
        {status.detail && <p className="text-xs text-muted-foreground">{status.detail}</p>}
      </div>
    </div>
  );
}

function SpendProtectionSection() {
  const t = useTranslations("usage");
  const [protection, setProtection] = useState<SpendProtectionResponse | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .getSpendProtection()
      .then((p) => {
        if (!cancelled) setProtection(p);
      })
      .catch(() => {
        // Same non-blocking, silently-absent posture as useUsageQuota - a
        // transparency panel that fails to load must never degrade the rest
        // of the usage page.
        if (!cancelled) setProtection(null);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (!protection) return null;

  return (
    <Card>
      <CardContent className="space-y-5">
        <div className="flex items-center gap-2">
          <Shield className="size-4 text-muted-foreground" aria-hidden="true" />
          <h2 className="text-sm font-semibold uppercase tracking-wider text-muted-foreground">
            {t("protectionTitle")}
          </h2>
        </div>
        <p className="-mt-3 text-sm leading-relaxed text-muted-foreground">{t("protectionSubtitle")}</p>

        <div className="space-y-4 border-t border-border pt-4">
          <div className="flex items-start gap-3">
            <span className="flex size-8 shrink-0 items-center justify-center rounded-lg border border-border bg-muted/40 text-muted-foreground">
              <Database className="size-4" aria-hidden="true" />
            </span>
            <div className="min-w-0 flex-1 space-y-2">
              <h3 className="text-sm font-semibold text-foreground">{t("protectionCachingTitle")}</h3>
              {protection.caching.length === 0 ? (
                <p className="text-sm leading-relaxed text-muted-foreground">{t("protectionCachingNone")}</p>
              ) : (
                <div className="space-y-2.5">
                  {protection.caching.map((c) => (
                    <div key={c.provider} className="flex flex-wrap items-center gap-x-2.5 gap-y-1 text-sm">
                      <Badge
                        variant="outline"
                        className="gap-1 uppercase tracking-wide"
                        style={{
                          color: "var(--primary)",
                          borderColor: "color-mix(in srgb, var(--primary) 35%, transparent)",
                        }}
                      >
                        <CheckCircle2 className="size-3" aria-hidden="true" />
                        {c.provider}
                      </Badge>
                      <span className="text-muted-foreground">
                        {c.cacheReadTokenShare == null
                          ? t("protectionCachingNoData", { days: protection.cacheWindowDays })
                          : t("protectionCachingReadShare", {
                              percent: Math.round(c.cacheReadTokenShare * 100),
                            })}
                        {c.callsWithCacheHitShare != null && (
                          <>
                            {" "}
                            ·{" "}
                            {t("protectionCachingHitShare", {
                              percent: Math.round(c.callsWithCacheHitShare * 100),
                            })}
                          </>
                        )}
                      </span>
                    </div>
                  ))}
                  <p className="text-xs text-muted-foreground">
                    {t("protectionCachingWindowNote", { days: protection.cacheWindowDays })}
                  </p>
                </div>
              )}
            </div>
          </div>

          <MechanismRow icon={Route} title={t("protectionRoutingTitle")} status={protection.costAwareRouting} />
          <MechanismRow
            icon={SlidersHorizontal}
            title={t("protectionToolsTitle")}
            status={protection.toolSchemaMinimization}
          />
          <MechanismRow icon={ShieldCheck} title={t("protectionApprovalTitle")} status={protection.approvalGates} />
        </div>
      </CardContent>
    </Card>
  );
}

function planLabelKey(plan: string): string {
  if (plan === "PREMIUM") return "planPremium";
  if (plan === "UNLIMITED") return "planUnlimited";
  return "planFree";
}

export default function UsagePage() {
  const t = useTranslations("usage");
  const tx = useTranslations("channelsUsageMcp");
  // Billing switch (lib/billing-flag.ts) - plan/upgrade CTAs only when on.
  const billingOn = isBillingEnabled();
  const { hasPermission } = useAuth();
  const [quota, setQuota] = useState<TenantUsageQuotaResponse | null>(null);
  const [error, setError] = useState(false);
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(() => {
    setLoaded(false);
    return api
      .getUsageQuota()
      .then((q) => {
        setQuota(q);
        setError(false);
      })
      .catch(() => {
        setQuota(null);
        setError(true);
      })
      .finally(() => setLoaded(true));
  }, []);

  useEffect(() => {
    void load();
  }, [load]);
  // Gate the route-transition cover on the fetch settling - registered
  // unconditionally BEFORE any conditional rendering below (the known trap:
  // a page that early-returns before calling usePageReady leaves the cover
  // up forever).
  usePageReady(loaded);

  // Billing off (personal mode): usage is counted, but no limit applies and there is nothing to upgrade to.
  const noLimit = quota?.limitEnforced === false;
  const showUpgrade = quota != null && quota.usesPlatformPool && !noLimit && quotaUsageFraction(quota) >= 0.8;
  const upgradeLevel: UsageLevel = quota ? usageLevel(quotaUsageFraction(quota)) : "ok";

  return (
    <AppShell title={t("title")} eyebrow={t("eyebrow")}>
      <PageHeader
        as="h2"
        title={noLimit ? t("titleNoLimit") : t("title")}
        subtitle={noLimit ? t("subtitleNoLimit") : t("subtitle")}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <Button asChild variant="outline" size="sm">
              <Link href="/insights">{tx("usageLinkInsights")}</Link>
            </Button>
            {!hiddenOnPersonalInstall("/roi") && (
              <Button asChild variant="outline" size="sm">
                <Link href="/roi">{tx("usageLinkRoi")}</Link>
              </Button>
            )}
          </div>
        }
      />

      {error && (
        <Alert variant="destructive" className="mb-5">
          <AlertDescription className="flex flex-wrap items-center gap-3">
            <span>{t("loadError")}</span>
            <Button type="button" size="sm" variant="outline" onClick={() => void load()}>
              {tx("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {!loaded && (
        <div className="space-y-4">
          <Skeleton className="h-24 w-full rounded-xl" />
          <Skeleton className="h-56 w-full rounded-xl" />
          <Skeleton className="h-56 w-full rounded-xl" />
        </div>
      )}

      {/* Loaded, no error, but nothing to meter yet - say so instead of
          rendering a blank page under the header. */}
      {loaded && !quota && !error && (
        <EmptyState
          icon={Gauge}
          title={tx("usageEmptyTitle")}
          description={tx("usageEmptyBody")}
          action={
            <div className="flex flex-wrap items-center justify-center gap-3">
              <Button asChild size="sm">
                <Link href="/playground">{tx("usageEmptyCta")}</Link>
              </Button>
              <Link
                href="/help#getting-started"
                className="text-sm font-medium text-primary underline-offset-4 hover:underline"
              >
                {tx("usageEmptyLearn")}
              </Link>
            </div>
          }
        />
      )}

      {loaded && quota && (
        <div className="space-y-4">
          {/* Plan summary strip */}
          <Card>
            <CardContent className="flex flex-wrap items-center gap-x-4 gap-y-2">
              <span className="flex size-10 shrink-0 items-center justify-center rounded-lg border border-border bg-muted/40 text-primary">
                <Gauge className="size-5" aria-hidden="true" />
              </span>
              <div className="min-w-0 flex-1 space-y-0.5">
                <div className="flex flex-wrap items-center gap-2">
                  {/* No plans without billing: the stored plan name (e.g. UNLIMITED) means nothing here. */}
                  {!noLimit && (
                    <>
                      <span className="text-sm font-medium text-muted-foreground">{t("planLabel")}</span>
                      <Badge variant="secondary" className="uppercase tracking-wide">
                        {t(planLabelKey(quota.plan))}
                      </Badge>
                    </>
                  )}
                  {quota.usesPlatformPool && (
                    <Badge variant="outline" className="border-primary/40 text-primary">
                      {t("poolBadge")}
                    </Badge>
                  )}
                </div>
                <p className="text-sm text-muted-foreground">
                  {quota.usesPlatformPool ? (noLimit ? t("poolNoLimitNote") : t("poolActiveNote")) : t("byokBody")}
                </p>
              </div>
              {billingOn && (
                <Link
                  href="/features"
                  className="inline-flex shrink-0 items-center gap-1.5 text-sm font-medium text-primary hover:underline"
                >
                  {t("seePlans")}
                  <ArrowRight className="size-3.5" aria-hidden="true" />
                </Link>
              )}
            </CardContent>
          </Card>

          {/* Monthly hosted-AI spend cap (platform-admin set); hidden when none. */}
          <SpendCapCard />

          {/* Buy more of the per-user budget (BudgetTopUpController); hides itself when top-ups are off.
              Not mounted at all without billing (a personal install has no payments): the card would
              otherwise call /api/budget on load only to hide itself. */}
          {billingOn && !isPersonalEdition() && <BudgetTopUpCard />}

          {/* Cross-link to the money view (2026-09-24): this page is the
              allowance meter every member can read; the per-provider cost,
              today's spend and the full call log live on Billing & usage,
              which only MANAGE_BILLING holders can open - so the card is
              gated the same way the rail item is. */}
          {/* Billing off (lib/billing-flag.ts): /admin/billing does not
              exist, so the LLM spend view itself renders here instead of the
              cross-link. No plan to upgrade to, so canUpgrade is false. */}
          {!billingOn && hasPermission("MANAGE_BILLING") && <LlmSpendSection canUpgrade={false} onUpgrade={() => {}} />}
          {billingOn && hasPermission("MANAGE_BILLING") && (
            <Card data-testid="usage-spend-link">
              <CardContent className="flex flex-wrap items-center gap-x-4 gap-y-2">
                <span className="flex size-10 shrink-0 items-center justify-center rounded-lg border border-border bg-muted/40 text-primary">
                  <Cpu className="size-5" aria-hidden="true" />
                </span>
                <div className="min-w-0 flex-1 space-y-0.5">
                  <h3 className="text-sm font-semibold text-foreground">{t("spendLinkTitle")}</h3>
                  <p className="text-sm text-muted-foreground">{t("spendLinkBody")}</p>
                </div>
                <Link
                  href="/admin/billing#llm-usage"
                  className="inline-flex shrink-0 items-center gap-1.5 text-sm font-medium text-primary hover:underline"
                >
                  {t("spendLinkCta")}
                  <ArrowRight className="size-3.5" aria-hidden="true" />
                </Link>
              </CardContent>
            </Card>
          )}

          {!quota.usesPlatformPool ? (
            // BYOK tenants: these windows aren't enforced against their
            // chats, so meters would be theater. One honest card instead.
            <Card>
              <CardContent className="flex items-start gap-3">
                <span className="flex size-10 shrink-0 items-center justify-center rounded-lg border border-border bg-muted/40 text-muted-foreground">
                  <KeyRound className="size-5" aria-hidden="true" />
                </span>
                <div className="space-y-1">
                  <h3 className="text-sm font-semibold text-foreground">{t("byokTitle")}</h3>
                  <p className="text-sm leading-relaxed text-muted-foreground">{t("byokBody")}</p>
                </div>
              </CardContent>
            </Card>
          ) : (
            <>
              <Card>
                <CardContent className="space-y-5">
                  <h2 className="text-sm font-semibold uppercase tracking-wider text-muted-foreground">
                    {t("sectionMonth")}
                  </h2>
                  {noLimit ? (
                    <p className="flex items-center gap-1.5 text-sm text-foreground" data-testid="usage-no-limit">
                      <Cpu className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                      {t("tokensUsedNoLimit", { used: quota.tokensUsedMonth.toLocaleString() })}
                    </p>
                  ) : (
                    <MeterRow
                      label={t("tokensLabel")}
                      resetNote={t("resetsMonthly")}
                      used={quota.tokensUsedMonth}
                      limit={quota.tokensMonthlyLimit}
                      icon={Cpu}
                    />
                  )}
                </CardContent>
              </Card>

              {showUpgrade && (
                <Card
                  style={{
                    borderColor: "color-mix(in srgb, " + toneFor(upgradeLevel) + " 35%, transparent)",
                  }}
                >
                  <CardContent className="flex flex-wrap items-center gap-x-4 gap-y-3">
                    <span
                      className="flex size-10 shrink-0 items-center justify-center rounded-lg border border-border"
                      style={{ color: toneFor(upgradeLevel) }}
                    >
                      <Sparkles className="size-5" aria-hidden="true" />
                    </span>
                    <div className="min-w-0 flex-1 space-y-0.5">
                      <h3 className="text-sm font-semibold text-foreground">
                        {upgradeLevel === "over" ? t("overLimitTitle") : t("nearLimitTitle")}
                      </h3>
                      <p className="text-sm leading-relaxed text-muted-foreground">
                        {upgradeLevel === "over" ? t("overLimitBody") : t("nearLimitBody")}
                      </p>
                    </div>
                    {billingOn && (
                      <Button asChild size="sm" className="shrink-0">
                        <Link href={upgradeHrefFor(hasPermission("MANAGE_BILLING"))}>
                          {t("upgradeCta")}
                          <ArrowRight className="size-3.5" aria-hidden="true" />
                        </Link>
                      </Button>
                    )}
                  </CardContent>
                </Card>
              )}
            </>
          )}

          {/* Additive transparency panel - shown regardless of BYOK/platform-pool
              status, since tool-schema minimization and approval gates apply to
              every tenant's agents, not only hosted-pool usage. */}
          <SpendProtectionSection />
        </div>
      )}
    </AppShell>
  );
}
