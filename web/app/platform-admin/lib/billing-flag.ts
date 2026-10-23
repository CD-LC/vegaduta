import { getPublicPolicy } from "./env";

// Billing switch (OSS default, 2026-09-29) - web-admin mirror of
// web/app/lib/billing-flag.ts. Self-hosted installs have no plans or payment
// processor, so the billing/subscriptions admin page is hidden unless the
// operator sets NEXT_PUBLIC_BILLING_ENABLED=1. Spend/budget/usage admin is
// not price-related and stays visible either way. Unset = OFF.

export function isBillingEnabled(): boolean {
  return getPublicPolicy("NEXT_PUBLIC_BILLING_ENABLED") === "1";
}

/** Admin routes that only exist to show paid plans / subscriptions. */
export const BILLING_ADMIN_ROUTES = ["/platform-admin/billing"] as const;

export function isBillingRoute(href: string): boolean {
  const path = href.split(/[?#]/)[0];
  return BILLING_ADMIN_ROUTES.some((p) => path === p || path.startsWith(`${p}/`));
}

/** Nav sections with billing items removed when billing is off; empty sections drop out. */
export function filterBillingNav<S extends { items: readonly { href: string }[] }>(
  sections: readonly S[],
  enabled = isBillingEnabled()
): S[] {
  if (enabled) return [...sections];
  return sections
    .map((s) => ({ ...s, items: s.items.filter((i) => !isBillingRoute(i.href)) }))
    .filter((s) => s.items.length > 0);
}
