// The console's single "which edition am I" signal, and the ONE route table that says which pages an
// edition does not have. Everything that used to ask "is this a personal install?" in its own way
// (isLocalAuth(), NEXT_PUBLIC_DEPLOY_ENV, a hand-kept hidden-routes list, a separate deep-link gate
// list, a proxy deny list) reads this file.
//
// CONTRACT WITH CORE. core runs as one tier, app.edition.tier = personal | lite | full (absent = every
// feature on), and reports it on the public GET /api/public/status as
// `edition: {tier, billingEnabled, authMode, features}`. The console cannot wait for that fetch to
// decide what the rail shows, so it takes the tier SYNCHRONOUSLY from NEXT_PUBLIC_EDITION (deploy/compose
// derives it and core's APP_EDITION_TIER from the single VEGADUTA_EDITION variable). lib/platformStatus.ts
// compares the two once the status arrives and warns on a mismatch; rendering never waits on it.
//
// FALLBACK. An older image, a unit test or a bare `next dev` has no NEXT_PUBLIC_EDITION. Then the tier is
// derived exactly as the console did before this file existed: local auth (NEXT_PUBLIC_AUTH_MODE=local)
// is personal, NEXT_PUBLIC_DEPLOY_ENV=lite|full names that tier, and anything else is a shared
// install in which nothing is hidden (core's "absent means every feature on").
//
// Deliberately dependency-free apart from env.ts and authMode.ts (no React, no icons, no i18n), so
// proxy.js, server components and the keycloak-js-free public pages can all import it.
import { isLocalAuth } from "./authMode";
import { getPublicEnv } from "./env";

export type Edition = "personal" | "lite" | "full";

/** Parse a tier name the way core does (trimmed, case-insensitive); null for anything unrecognised. */
export function parseEdition(raw: string | null | undefined): Edition | null {
  const v = raw?.trim().toLowerCase();
  return v === "personal" || v === "lite" || v === "full" ? v : null;
}

/**
 * The tier this build was told it is, or null when nothing says: NEXT_PUBLIC_EDITION first, else the
 * pre-edition derivation (local auth, then NEXT_PUBLIC_DEPLOY_ENV=lite|full).
 */
export function declaredEdition(): Edition | null {
  const explicit = parseEdition(getPublicEnv("NEXT_PUBLIC_EDITION"));
  if (explicit) return explicit;
  if (isLocalAuth()) return "personal";
  const deploy = parseEdition(getPublicEnv("NEXT_PUBLIC_DEPLOY_ENV"));
  return deploy === "lite" || deploy === "full" ? deploy : null;
}

/** The tier, with an undeclared one meaning "every feature on" (the same as core with no tier set). */
export function edition(): Edition {
  return declaredEdition() ?? "full";
}

/** One person on one machine: local sign-in, no tenants, no billing, no operator. */
export function isPersonalEdition(): boolean {
  return edition() === "personal";
}

/** True only when the install says it is the lite tier (it never defaults to it). */
export function isLiteEdition(): boolean {
  return declaredEdition() === "lite";
}

/** True only when the install says it is the full tier (an undeclared shared install is not "full"). */
export function isFullEdition(): boolean {
  return declaredEdition() === "full";
}

// ------------------------------------------------------------------ channels

/**
 * Channels whose adapters (and so their webhooks and credentials) core does not run on a personal install:
 * core's SOCIAL_CHANNELS feature. Telegram, Signal, WhatsApp, iMessage and email stay.
 */
const PERSONAL_UNOFFERED_CHANNELS: readonly string[] = ["slack", "discord", "teams", "x"];

/** False for a channel key the channels grid, triggers, approvals, contacts and scheduled-task delivery should not offer. */
export function channelOffered(key: string): boolean {
  if (!isPersonalEdition()) return true;
  return !PERSONAL_UNOFFERED_CHANNELS.includes(key.trim().toLowerCase());
}

// ------------------------------------------------------------------ the route table

/** Why a personal install does not have a page; indexes the `featureUnavailable.reason.*` copy. */
export type UnavailableReason = "identity" | "team" | "billing" | "hosted" | "service";

/** Icon the deep-link gate draws; lib/editionCapabilities.ts maps it to a component, keeping this file icon-free. */
export type EditionIcon = "key" | "users" | "card" | "network" | "building" | "activity";

export interface EditionRoute {
  /** Route prefix (the route and everything nested under it). */
  route: string;
  /** `nav` i18n key naming the feature; null for routes outside the console shell that are never gated in-app. */
  navKey: string | null;
  reason: UnavailableReason;
  icon: EditionIcon;
  /** The gate applies on a personal install (default) or whenever the Code Intel service is not configured. */
  scope: "personal" | "no-code-intel";
  /** The rail (and Ctrl+K, which reads the same blueprint) does not list this destination when the gate applies. */
  rail: boolean;
  /** A deep link renders the shared "not available" state (components/shell/FeatureGate). Needs a navKey. */
  gate: boolean;
  /** The proxy answers 404 for this path. False where another mechanism already answers (a redirect). */
  deny: boolean;
  /** The page is replaced by a redirect to this route, so it is neither gated nor denied. */
  redirectTo?: string;
}

const row = (
  route: string,
  navKey: string | null,
  reason: UnavailableReason,
  icon: EditionIcon,
  flags: Partial<Pick<EditionRoute, "rail" | "gate" | "deny" | "scope" | "redirectTo">> = {}
): EditionRoute => ({
  route,
  navKey,
  reason,
  icon,
  scope: "personal",
  rail: true,
  gate: navKey !== null,
  deny: true,
  ...flags,
});

/** A page on the console rail that a personal install does not have. Gated, hidden and denied. */
const rail = (route: string, navKey: string, reason: UnavailableReason, icon: EditionIcon) =>
  row(route, navKey, reason, icon);

/** A page outside the rail (public, hosted or operator-only) that a personal install does not serve. */
const hidden = (route: string, reason: UnavailableReason, icon: EditionIcon) =>
  row(route, null, reason, icon, { rail: false, gate: false });

/**
 * THE table. Every page a personal install does not have, once: the rail flag, the deep-link gate and
 * the proxy 404 all derive from it. lite and full gate nothing here (see {@link railHidden}), so adding
 * a row can only ever change the personal edition. Rows in NAV_SECTIONS stay; they are filtered at
 * render.
 */
export const EDITION_ROUTES: readonly EditionRoute[] = [
  // identity: sign-in is core's local account, there is no Keycloak to configure SSO on
  rail("/admin/sso", "railSso", "identity", "key"),
  // team: one person on one machine, nothing to share, delegate or issue keys for
  rail("/shared-with-me", "railShared", "team", "users"),
  rail("/admin/agent-trust", "railAgentTrust", "team", "users"),
  rail("/admin/api-keys", "railApiKeys", "team", "key"),
  rail("/admin/access", "railAccess", "team", "users"),
  rail("/admin/users", "railAccess", "team", "users"),
  // billing: app.edition.billing-enabled is false. /admin/billing and /features are also redirected
  // by the billing switch (lib/billing-flag.ts), which answers first, so the proxy does not 404 them.
  row("/admin/billing", "railBill", "billing", "card", { rail: false, deny: false }),
  rail("/features", "railPlans", "billing", "card"),
  rail("/roi", "railRoi", "billing", "card"),
  rail("/admin/payments", "railPayments", "billing", "card"),
  rail("/admin/commerce", "railCommerce", "billing", "card"),
  // hosted: a multi-tenant / public-facing surface a single local box does not run
  rail("/directory", "railDirectory", "hosted", "network"),
  rail("/studio", "railStudio", "hosted", "building"),
  rail("/admin/leads", "railLeads", "hosted", "building"),
  rail("/admin/branding", "railBrand", "hosted", "building"),
  rail("/admin/mcp-servers", "railMcp", "hosted", "network"),
  // Folded into /assistant on a personal install (the agent picker, "In background" and "Quick task"):
  // the page redirects there, so only the rail has to stop listing them as separate destinations.
  row("/playground", null, "hosted", "building", { gate: false, deny: false, redirectTo: "/assistant" }),
  row("/quick-task", null, "hosted", "building", { gate: false, deny: false, redirectTo: "/assistant" }),
  row("/jobs", null, "hosted", "building", { gate: false, deny: false, redirectTo: "/assistant" }),
  // Pages outside the rail that exist for tenants, visitors and other agents, not for the owner.
  hidden("/view-as", "team", "users"),
  hidden("/signup", "hosted", "building"),
  hidden("/try", "hosted", "building"),
  hidden("/templates/mine", "hosted", "building"),
  hidden("/.well-known/agent-card.json", "hosted", "network"),
  hidden("/.well-known/agent.json", "hosted", "network"),
  // service: a separate service this deployment has no URL for. Applies on every edition.
  row("/code-intel", "railCode", "service", "activity", { scope: "no-code-intel", rail: false, deny: false }),
];

/** Pathname without query or hash. */
const bare = (path: string): string => path.split(/[?#]/, 1)[0];

/** True when {@code path} is {@code route} or nested under it (never a mere string prefix: `/directory-2` is not `/directory`). */
export function underRoute(path: string, route: string): boolean {
  const p = bare(path);
  return p === route || p.startsWith(`${route}/`);
}

const personalRows = EDITION_ROUTES.filter((r) => r.scope === "personal");

/** The personal-edition row {@code path} falls under, or null. Edition-independent: callers decide whether it applies. */
export function personalRoute(path: string): EditionRoute | null {
  return personalRows.find((r) => underRoute(path, r.route)) ?? null;
}

/** True when the rail (and Ctrl+K) should not list {@code href} on this install. Always false off personal. */
export function railHidden(href: string): boolean {
  if (!isPersonalEdition()) return false;
  const r = personalRoute(href);
  return r !== null && r.rail;
}

/**
 * True when the proxy should answer 404 for {@code pathname} (locale prefix already stripped) on a
 * personal install. Path-only on purpose: proxy.js decides whether the edition is personal itself
 * (it must read the edition from the process env, not from the bundled public-env helpers).
 */
export function isPersonalDenied(pathname: string): boolean {
  const r = personalRoute(pathname);
  return r !== null && r.deny;
}

/**
 * The deny entries outside the console shell (no deep-link gate renders for them): sign-up, the public
 * try-it page, impersonation, the template submissions page and the public agent card. The proxy 404s
 * these together with every other deny row, via {@link isPersonalDenied}.
 */
export const PERSONAL_PUBLIC_DENY: readonly string[] = personalRows
  .filter((r) => r.deny && !r.gate && r.redirectTo === undefined)
  .map((r) => r.route);

// ------------------------------------------------------------------ platform admin

/**
 * The only platform-admin pages a personal install keeps: the LLM pools and keys, the tool credentials
 * (the Brave key for web_search) and the platform settings. Everything else in /platform-admin is
 * operator tooling for a fleet of tenants.
 */
export const PERSONAL_PLATFORM_ADMIN_ALLOW: readonly string[] = [
  "/platform-admin/llm-providers",
  "/platform-admin/tool-credentials",
  "/platform-admin/settings",
];

/** Where the platform-admin Overview goes on a personal install. */
export const PERSONAL_PLATFORM_ADMIN_HOME = "/platform-admin/llm-providers";

/**
 * True when a personal install may open {@code pathname} under /platform-admin: the allowlist, plus the
 * Overview root itself (which redirects to {@link PERSONAL_PLATFORM_ADMIN_HOME}). Paths outside
 * /platform-admin are not this function's business and return true.
 */
export function isPersonalPlatformAdminAllowed(pathname: string): boolean {
  const p = bare(pathname);
  if (p !== "/platform-admin" && !p.startsWith("/platform-admin/")) return true;
  if (p === "/platform-admin") return true;
  return PERSONAL_PLATFORM_ADMIN_ALLOW.some((a) => underRoute(p, a));
}
