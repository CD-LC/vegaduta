"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Gauge, Sparkles, X } from "lucide-react";
import { api, type TenantUsageQuotaResponse } from "../lib/api";
import { useTranslations } from "../lib/i18n";
import { isBillingEnabled } from "../lib/billing-flag";
import { visibleInterval } from "../lib/visibleInterval";

// Visible usage meter (item B3, docs/PRICING-MONETIZATION-AUDIT-2026-07-31.md
// §5.2). The platform enforces a single rolling-30-day hosted-LLM-pool token
// window server-side (FreeTierUsageLimiterService, plan-scaled - the
// 2026-09-26 simplification removed the message-count and daily-token
// windows) - but with no visible meter, hitting the cap would read as a
// random failure instead of an upgrade path. This module is the shared
// client half:
//
//   useUsageQuota()   - one best-effort fetch of GET /api/tenant/usage-quota,
//                       called once by AppShellBody (same never-blocks-render
//                       posture as its tenantSettings/account fetches) and
//                       passed down to both widgets so the shell issues ONE
//                       request, not one per widget.
//   UsageMeterPill    - compact "12,345/400,000 tokens" topbar pill, links
//                       to /usage.
//   UsageAlertBanner  - dismissible ≥80% / ≥100% warning bar with the upgrade
//                       CTA, rendered above page content inside AppShell.
//
// Every widget renders null for BYOK tenants (usesPlatformPool false - the
// window isn't enforced against their chats, so a meter would be a lie) and
// on any fetch failure - the meter is an upsell/transparency surface, never
// something that may degrade the console when core hiccups.

/**
 * `username` is REQUIRED, not a convenience - it is the fix for authenticated
 * calls firing on public pages (production E2E, 2026-08-16: a logged-out
 * visitor on /terms, /privacy, /features, /help, /billing, /ai, /earn and
 * /whatsapp-cost-calculator was firing GET /api/tenant/usage-quota and taking a
 * 401 on every one of them).
 *
 * Root cause: this hook's only caller is AppChromeProvider, which is mounted at
 * the ROOT layout - above the public/authenticated route split - and which
 * carefully gates each of its OWN four fetches on `username` for exactly that
 * reason (see its header comment, which claims that gate covers everything it
 * fetches). It didn't cover this one: `const usageQuota = useUsageQuota()` was
 * called unconditionally, so this effect ran on literally every route in the
 * app, session or not, and then kept re-firing on its own 30s interval for as
 * long as the visitor stayed on the page. The `.catch()` below meant the only
 * visible trace was the 401 itself plus the token layer's auth_failure report.
 *
 * Taking the caller's `username` as an argument puts the gate INSIDE the
 * hook, where it can't be forgotten again by a future call site. Do not
 * restore a zero-argument overload.
 */
export function useUsageQuota(username: string | null) {
  const [quota, setQuota] = useState<TenantUsageQuotaResponse | null>(null);
  useEffect(() => {
    if (!username) return undefined;
    let cancelled = false;
    function poll() {
      api
        .getUsageQuota()
        .then((q) => {
          if (!cancelled) setQuota(q);
        })
        .catch(() => {
          // Old core without the endpoint (404), transient failure, etc. -
          // quietly render nothing rather than toast/complain. State is left
          // untouched, so the initial value stays null (nothing renders) and
          // a later failed poll keeps the last-known snapshot rather than
          // blanking the pill - same posture as the MCP attention-badge poll
          // in AppChromeProvider: a failed probe isn't evidence of a change.
        });
    }
    poll();
    // The caller (AppChromeProvider) mounts once per SESSION, in the root
    // layout - so without this interval the pill/banner would render a
    // snapshot taken at sign-in for the rest of the session, while /usage
    // refetches on every visit. That divergence was a real, reported bug:
    // an admin raised the platform-wide token limit mid-session and the
    // topbar kept showing "296,521/300,000 tokens today - 99% used" against
    // the stale cap while /usage correctly showed 2% of the new one. It also
    // froze the USED counter, so the meter never moved as the user chatted.
    const stop = visibleInterval(poll, 30000);
    return () => {
      cancelled = true;
      stop();
    };
  }, [username]);
  return quota;
}

/** Fraction used (0..1+) with a zero-limit guard - a 0 limit reads as fully used, not NaN. */
export function usageFraction(used: number, limit: number): number {
  if (!limit || limit <= 0) return used > 0 ? 1 : 0;
  return used / limit;
}

/**
 * Fraction used of the single enforced window (rolling-30-day token count,
 * plan-scaled) - the value the pill colour and the banner threshold key off.
 */
export function quotaUsageFraction(quota: TenantUsageQuotaResponse): number {
  return usageFraction(quota.tokensUsedMonth, quota.tokensMonthlyLimit);
}

export type UsageLevel = "ok" | "near" | "over";

export function usageLevel(fraction: number): UsageLevel {
  if (fraction >= 1) return "over";
  if (fraction >= 0.8) return "near";
  return "ok";
}

/** Where "upgrade" goes for this user - the billing screen for whoever holds MANAGE_BILLING (the Button default upgradeHref), the plans/features comparison for everyone else (/admin/billing hard-redirects callers without the scope). */
export function upgradeHrefFor(canManageBilling: boolean): string {
  return canManageBilling ? "/admin/billing" : "/features";
}

/**
 * Compact topbar meter - "12,345/400,000 tokens", linking to /usage. Shows
 * the rolling-30-day token window (the only enforced quota). Hidden below sm
 * like the system-health pill next to it (topbar space).
 */
export function UsageMeterPill({ quota }: { quota: TenantUsageQuotaResponse | null }) {
  const t = useTranslations("usage");
  // No limit on this install (billing off, e.g. personal mode): "used/limit" would be a made-up cap.
  if (!quota || !quota.usesPlatformPool || quota.limitEnforced === false) return null;
  const level = usageLevel(quotaUsageFraction(quota));
  const tone = level === "over" ? "var(--destructive)" : level === "near" ? "var(--upgrade)" : "var(--text-muted)";
  const used = quota.tokensUsedMonth.toLocaleString();
  const limit = quota.tokensMonthlyLimit.toLocaleString();
  return (
    <Link
      href="/usage"
      aria-label={t("pillAriaLabelTokens", { used, limit })}
      title={t("pillTitle")}
      className="hidden items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-semibold tabular-nums transition-colors hover:bg-[var(--surface-raised)] sm:inline-flex"
      style={{
        color: tone,
        borderColor: "color-mix(in srgb, " + tone + " 35%, transparent)",
      }}
    >
      <Gauge size={13} className="shrink-0" aria-hidden="true" />
      <span className="topbar-label">{t("pillTokens", { used, limit })}</span>
    </Link>
  );
}

// Dismissal is per level AND per day (localStorage): dismissing the 80%
// notice stays dismissed for the rest of the day (the daily window resets
// overnight anyway), but crossing into 100% is a new, louder fact and gets
// its own banner even if 80% was dismissed earlier.
function dismissKey(level: UsageLevel): string {
  return `vd-usage-banner:${level}:${new Date().toISOString().slice(0, 10)}`;
}

/**
 * Dismissible in-app warning bar once usage crosses 80% (approaching) or 100%
 * (blocked) of any enforced window, with the upgrade CTA. Mounted by
 * AppShellBody above the page content, so it follows the user anywhere in the
 * console rather than only warning people who happen to visit /usage.
 */
export function UsageAlertBanner({
  quota,
  canManageBilling,
}: {
  quota: TenantUsageQuotaResponse | null;
  canManageBilling: boolean;
}) {
  const t = useTranslations("usage");
  const [dismissed, setDismissed] = useState<Record<string, boolean>>({});
  // localStorage is only readable client-side; hydrate the dismissal state
  // once on mount rather than reading it during render.
  const [hydrated, setHydrated] = useState(false);
  useEffect(() => {
    try {
      setDismissed({
        [dismissKey("near")]: window.localStorage.getItem(dismissKey("near")) === "1",
        [dismissKey("over")]: window.localStorage.getItem(dismissKey("over")) === "1",
      });
    } catch {
      /* storage unavailable (private mode etc.) - banner just re-shows */
    }
    setHydrated(true);
  }, []);

  if (!quota || !quota.usesPlatformPool || quota.limitEnforced === false || !hydrated) return null;
  const level = usageLevel(quotaUsageFraction(quota));
  if (level === "ok") return null;
  const key = dismissKey(level);
  if (dismissed[key]) return null;

  const over = level === "over";
  const tone = over ? "var(--destructive)" : "var(--upgrade)";
  const percent = Math.min(999, Math.round(quotaUsageFraction(quota) * 100));

  function dismiss() {
    try {
      window.localStorage.setItem(key, "1");
    } catch {
      /* non-persistent dismissal is still a dismissal */
    }
    setDismissed((prev) => ({ ...prev, [key]: true }));
  }

  return (
    <div
      role="status"
      className="mb-5 flex flex-wrap items-center gap-x-3 gap-y-2 rounded-lg border px-3.5 py-2.5 text-sm"
      style={{
        borderColor: "color-mix(in srgb, " + tone + " 35%, transparent)",
        backgroundColor: "color-mix(in srgb, " + tone + " 10%, transparent)",
      }}
    >
      <Sparkles size={15} className="shrink-0" style={{ color: tone }} aria-hidden="true" />
      <span className="min-w-0 flex-1">
        <span className="font-semibold" style={{ color: tone }}>
          {over ? t("bannerOverTitle") : t("bannerNearTitle", { percent })}
        </span>{" "}
        <span className="text-muted-foreground">{over ? t("bannerOverBody") : t("bannerNearBody")}</span>
      </span>
      <span className="flex shrink-0 items-center gap-2">
        {/* Billing off (lib/billing-flag.ts): the warning stays, the upgrade CTA goes. */}
        {isBillingEnabled() && (
          <Link
            href={upgradeHrefFor(canManageBilling)}
            className="font-semibold hover:underline"
            style={{ color: tone }}
          >
            {t("bannerCta")}
          </Link>
        )}
        <Link href="/usage" className="text-muted-foreground hover:underline">
          {t("bannerDetails")}
        </Link>
        {/* border-0 bg-transparent p-0 shadow-none: neutralize the global
            `button {}` base rule that would otherwise clip this icon-only
            button (see AppShell's own icon buttons). */}
        <button
          type="button"
          onClick={dismiss}
          aria-label={t("bannerDismiss")}
          className="flex size-6 shrink-0 items-center justify-center rounded-md border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-[var(--surface-raised)] hover:text-foreground"
        >
          <X aria-hidden="true" size={14} />
        </button>
      </span>
    </div>
  );
}
