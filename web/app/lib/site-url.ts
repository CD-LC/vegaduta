import { getPublicEnv } from "./env";
// Public origin of the marketing site (canonical URLs, sitemap, robots,
// JSON-LD, Open Graph). Pure module - safe to import from client and server
// code alike. Set NEXT_PUBLIC_SITE_URL (or SITE_URL) to your deployment's
// origin.
//
// AGENTS.md rule 4: a production build never falls back to localhost. With no
// origin configured the value is "" so `${SITE_URL}/path` degrades to a
// relative URL (valid for canonical/OG/alternates against the page origin)
// instead of freezing a dead localhost origin into crawler-facing output.
// Only non-production builds (dev server, tests) default to localhost:3000.
function resolveSiteUrl(): string {
  const configured = getPublicEnv("NEXT_PUBLIC_SITE_URL") || process.env.SITE_URL;
  const origin = configured || (process.env.NODE_ENV !== "production" ? "http://localhost:3000" : "");
  return origin.replace(/\/+$/, "");
}

export const SITE_URL = resolveSiteUrl();

/** Same value as SITE_URL, resolved at call time. Use from request-time
 *  handlers (sitemap.ts, robots.ts) so a runtime SITE_URL is honoured and no
 *  origin is frozen into the build output. "" when unconfigured in production:
 *  callers that need an absolute URL (sitemap, robots) must handle that. */
export function siteUrl(): string {
  return resolveSiteUrl();
}
