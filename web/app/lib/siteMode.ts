import { getPublicEnv } from "./env";

// Site-mode switch (2026-10-12). Decides which set of PUBLIC (signed-out)
// pages a deployment serves:
//
//   oss      (default) technical open-source pages: what it is, the stack,
//            the standards it speaks, how to install, where to contribute.
//            No plans, prices, lead-generation or sales pages.
//   classic  the original marketing pages, unchanged (DemoLanding, /pricing,
//            /enterprise, /hire, ...). They were not deleted; git tag
//            backup/classic-marketing-pages-2026-10-12 marks the last commit
//            before the switch existed.
//
// Read at RUNTIME through getPublicEnv (window.__ENV > build-time value), so
// one image serves both. Unset or anything other than "classic" = oss.
// Independent of the billing flag (lib/billing-flag.ts), which governs the
// signed-in plans/billing UI.

export type SiteMode = "oss" | "classic";

export function siteMode(): SiteMode {
  return getPublicEnv("NEXT_PUBLIC_SITE_MODE")?.trim().toLowerCase() === "classic" ? "classic" : "oss";
}

export function isOssSite(): boolean {
  return siteMode() === "oss";
}

/** Public routes that exist to sell, qualify leads or compare vendors. In oss
 *  mode proxy.js redirects them to "/". (/pricing, /features and the policies
 *  are also covered by the billing flag.) */
export const COMMERCIAL_ROUTE_PREFIXES = [
  "/pricing",
  "/features",
  "/enterprise",
  "/hire",
  "/earn",
  "/why-vegaduta",
  "/compare",
  "/explore",
  "/whatsapp-cost-calculator",
  "/refund-policy",
  "/shipping-policy",
] as const;

/** True when `pathname` (locale prefix already stripped) is a commercial route. */
export function isCommercialRoute(pathname: string): boolean {
  return COMMERCIAL_ROUTE_PREFIXES.some((p) => pathname === p || pathname.startsWith(`${p}/`));
}
