import { isPersonalEdition } from "./edition";
import { getPublicEnv } from "./env";

// Billing switch (OSS default, 2026-09-29). Self-hosted installs have no
// plans, prices or payment processor, so every plans/prices/upgrade/billing
// surface is hidden unless the operator sets NEXT_PUBLIC_BILLING_ENABLED=1
// (the hosted SaaS does). Read at RUNTIME through getPublicEnv (window.__ENV
// > build-time value), so one image serves both postures.
//
// Unset / anything other than "1" = OFF. When ON, every gated call site takes
// the exact code path it took before this flag existed.
//
// A personal install (lib/edition.ts) has no billing whatever this variable says: core's
// app.edition.billing-enabled is false there, so isBillingEnabled() is false for it and the redirect,
// the rail rows and every upgrade link follow the edition rather than a second switch that a stray
// WEB_BILLING_ENABLED=1 could flip. lite and full read the variable exactly as before.
//
// Independent of FREE_MODE (lib/freeMode.ts): FREE_MODE changes what the
// pricing surfaces SAY; this flag decides whether they exist at all.

/** Live read - use in components and tests (re-evaluates per call). */
export function isBillingEnabled(): boolean {
  if (isPersonalEdition()) return false;
  return getPublicEnv("NEXT_PUBLIC_BILLING_ENABLED") === "1";
}

/** Module-load snapshot, for module-level constants. Prefer isBillingEnabled(). */
export const BILLING_ENABLED = isBillingEnabled();

/** Routes that only exist to sell, compare or manage a paid plan (/features
 *  is the priced Free/Premium/Unlimited comparison; /pricing is the public
 *  price list and /refund-policy + /shipping-policy cover paid plans only). */
export const BILLING_ROUTE_PREFIXES = [
  "/billing",
  "/admin/billing",
  "/features",
  "/pricing",
  "/refund-policy",
  "/shipping-policy",
] as const;

/** True when `pathname` (locale prefix already stripped) is a billing route. */
export function isBillingRoute(pathname: string): boolean {
  return BILLING_ROUTE_PREFIXES.some((p) => pathname === p || pathname.startsWith(`${p}/`));
}

/** Where a billing route goes when billing is off: the admin billing page
 *  also hosted the LLM-spend view, which /usage covers; everything else goes
 *  to the console home. */
export function billingOffRedirect(pathname: string): string {
  return pathname === "/admin/billing" || pathname.startsWith("/admin/billing/") ? "/usage" : "/";
}

/** Link/nav destination check - tolerates ?query and #hash. */
export function isBillingNavHref(href: string): boolean {
  return isBillingRoute(href.split(/[?#]/)[0]);
}

/** Drop items whose href is a plans/billing destination when billing is off. */
export function filterBillingItems<T extends { href?: string }>(
  items: readonly T[],
  enabled = isBillingEnabled()
): T[] {
  if (enabled) return [...items];
  return items.filter((item) => !item.href || !isBillingNavHref(item.href));
}

/** Same, applied to grouped nav; sections left empty drop out. */
export function filterBillingNav<S extends { items: readonly { href: string }[] }>(
  sections: readonly S[],
  enabled = isBillingEnabled()
): S[] {
  if (enabled) return [...sections];
  return sections.map((s) => ({ ...s, items: filterBillingItems(s.items, false) })).filter((s) => s.items.length > 0);
}

/** Where the tenant LLM-spend view (components/billing/LlmSpendSection)
 *  lives: on Billing & usage when billing is on, on /usage when it is off. */
export function llmSpendHref(enabled = isBillingEnabled()): string {
  return enabled ? "/admin/billing#llm-usage" : "/usage#llm-usage";
}

/** Should an upgrade-style link to `href` render? Always when billing is on;
 *  when off, only if it points somewhere other than a plans/billing page
 *  (e.g. an UpgradeLock reused as a "Connect -> /providers" hint). */
export function showUpgradeLink(href: string, enabled = isBillingEnabled()): boolean {
  return enabled || !isBillingNavHref(href);
}
