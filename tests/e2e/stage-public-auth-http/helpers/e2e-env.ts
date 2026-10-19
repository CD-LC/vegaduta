// Target URLs for the E2E suite, all overridable by env var.
//
// Defaults are the local development ports (web :3000 (incl. /platform-admin),
// core :8080, Keycloak :8180, Academy :4100). Point the suite at a deployed
// environment by setting E2E_BASE_URL / E2E_API_BASE_URL / E2E_ADMIN_BASE_URL /
// E2E_AUTH_URL (and E2E_SITE_URL for the canonical/sitemap host). Several specs
// assert "no browser-facing URL is localhost" - those are meant for deployed
// environments and will fail against the local defaults by design.

const trim = (u: string) => u.replace(/\/+$/, "");

export const APP_BASE_URL = trim(process.env.E2E_BASE_URL || "http://localhost:3000");
export const API_BASE_URL = trim(process.env.E2E_API_BASE_URL || process.env.E2E_API_BASE || "http://localhost:8080");
export const ADMIN_BASE_URL = trim(process.env.E2E_ADMIN_BASE_URL || "http://localhost:3000/platform-admin");
export const AUTH_BASE_URL = trim(process.env.E2E_AUTH_URL || "http://localhost:8180");
export const ACADEMY_BASE_URL = trim(process.env.E2E_ACADEMY_URL || "http://localhost:4100");
/** Canonical site URL the web app was built/run with (SITE_URL / NEXT_PUBLIC_SITE_URL). */
export const SITE_URL = trim(process.env.E2E_SITE_URL || APP_BASE_URL);

export const escapeRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/** Host (hostname[:port]) of a URL. */
export const hostOf = (url: string) => new URL(url).host;

/** RegExp matching any URL on the given base URL's host. */
export const hostRe = (url: string) => new RegExp(escapeRe(hostOf(url)));

export const AUTH_HOST = hostOf(AUTH_BASE_URL);
export const AUTH_HOST_RE = hostRe(AUTH_BASE_URL);
export const APP_HOST_RE = hostRe(APP_BASE_URL);
export const ADMIN_HOST_RE = hostRe(ADMIN_BASE_URL);
