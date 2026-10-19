import { getPublicEnv } from "./env";
// Public origin of the marketing site (canonical URLs, sitemap, robots,
// JSON-LD, Open Graph). Pure module - safe to import from client and server
// code alike. Set NEXT_PUBLIC_SITE_URL (or SITE_URL) to your deployment's
// origin; the localhost default is for local development only.
export const SITE_URL = (
  getPublicEnv("NEXT_PUBLIC_SITE_URL") ||
  process.env.SITE_URL ||
  "http://localhost:3000"
).replace(/\/+$/, "");

/** Same value as SITE_URL, resolved at call time. Use from request-time
 *  handlers (sitemap.ts, robots.ts) so a runtime SITE_URL is honoured and the
 *  localhost default is never frozen into the build output. */
export function siteUrl(): string {
  return (
    getPublicEnv("NEXT_PUBLIC_SITE_URL") ||
    process.env.SITE_URL ||
    "http://localhost:3000"
  ).replace(/\/+$/, "");
}
